package com.metallum.mixin.sodium;

import com.metallum.client.benchmark.TorchEpochTelemetry;
import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.sodium.SodiumLightSidecarArena;
import com.metallum.client.sodium.SodiumLightSidecarPacking;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.PendingUpload;
import net.caffeinemc.mods.sodium.client.gpu.arena.staging.StagingBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.Collection;

@Mixin(value = GlBufferArena.class, remap = false)
abstract class GlBufferArenaLightSidecarMixin implements SodiumLightSidecarArena {
    @Shadow
    private GpuBuffer arenaBuffer;

    @Shadow
    @Final
    private StagingBuffer stagingBuffer;

    @Shadow
    @Final
    private int stride;

    @Unique
    @Nullable
    private GpuBuffer metallum$lightSidecar;

    @Unique
    @Nullable
    private GpuBuffer metallum$transferGeometrySource;

    @Unique
    @Nullable
    private GpuBuffer metallum$transferSidecarSource;

    @Override
    public void metallum$enableLightSidecar() {
        if (this.metallum$lightSidecar != null
                || !SodiumLightSidecar.isRuntimeActive()) {
            return;
        }

        GpuBuffer sidecar = null;
        try {
            if (!SodiumLightSidecar.isMetalBackend()) {
                return;
            }
            if (this.stride != SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE) {
                throw new IllegalStateException("Sodium compact terrain stride changed to " + this.stride);
            }

            sidecar = SodiumLightSidecar.createCompanion(this.arenaBuffer);
            SodiumLightSidecar.attach(this.arenaBuffer, sidecar);
            this.metallum$lightSidecar = sidecar;
            sidecar = null;
        } catch (Throwable throwable) {
            if (sidecar != null) {
                try {
                    sidecar.close();
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
            }
            SodiumLightSidecar.fail("could not create the geometry light companion", throwable);
        }
    }

    @Override
    public long metallum$enqueueInPlaceTerrainRefresh(
            final ByteBuffer geometry,
            final long allocationVertexOffset,
            final long allocationVertexCount
    ) {
        GpuBuffer sidecar = this.metallum$lightSidecar;
        if (sidecar == null || !SodiumLightSidecar.isRuntimeActive()) {
            throw new IllegalStateException("Sodium light sidecar is not available for an in-place refresh");
        }
        if (this.stride != SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE) {
            throw new IllegalStateException("in-place terrain refresh used a non-geometry arena");
        }
        if (allocationVertexOffset < 0L || allocationVertexCount <= 0L) {
            throw new IllegalStateException("in-place terrain refresh used an invalid allocation range");
        }

        ByteBuffer source = geometry.duplicate();
        int geometryBytes = source.remaining();
        if (geometryBytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0) {
            throw new IllegalStateException("unaligned in-place terrain refresh: " + geometryBytes);
        }
        long vertexCount = geometryBytes / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
        if (vertexCount != allocationVertexCount) {
            throw new IllegalStateException(
                    "in-place terrain allocation length changed: allocation=" + allocationVertexCount
                            + ", payload=" + vertexCount
            );
        }

        long geometryOffset = Math.multiplyExact(
                allocationVertexOffset,
                SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
        );
        if (Math.addExact(geometryOffset, geometryBytes) > this.arenaBuffer.size()) {
            throw new IllegalStateException("in-place terrain refresh exceeds its geometry buffer");
        }

        int sidecarBytes = Math.multiplyExact(
                Math.toIntExact(vertexCount),
                SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
        );
        long sidecarOffset = Math.multiplyExact(
                allocationVertexOffset,
                SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
        );
        if (Math.addExact(sidecarOffset, sidecarBytes) > sidecar.size()) {
            throw new IllegalStateException("in-place terrain refresh exceeds its light companion");
        }

        ByteBuffer packed = MemoryUtil.memAlloc(sidecarBytes);
        try {
            int packedVertices = SodiumLightSidecarPacking.packGeometry(source, packed);
            if (packedVertices != vertexCount) {
                throw new IllegalStateException("in-place terrain light vertex count changed while packing");
            }
            packed.flip();
            this.stagingBuffer.enqueueCopy(geometry.duplicate(), this.arenaBuffer, geometryOffset);
            this.stagingBuffer.enqueueCopy(packed, sidecar, sidecarOffset);
        } finally {
            MemoryUtil.memFree(packed);
        }
        return sidecarBytes;
    }

    @Inject(method = "tryUpload", at = @At("RETURN"))
    private void metallum$uploadLightSidecar(
            final PendingUpload upload,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        GpuBuffer sidecar = this.metallum$lightSidecar;
        if (!cir.getReturnValueZ() || sidecar == null || !SodiumLightSidecar.isRuntimeActive()) {
            return;
        }

        ByteBuffer geometry = upload.getDataBuffer().getDirectBuffer();
        int vertexCount;
        int sidecarBytes;
        ByteBuffer packed = null;
        try {
            int geometryBytes = geometry.remaining();
            if (geometryBytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0) {
                throw new IllegalStateException("unaligned compact terrain upload: " + geometryBytes);
            }
            vertexCount = geometryBytes / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
            sidecarBytes = Math.multiplyExact(vertexCount, SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE);
            packed = MemoryUtil.memAlloc(sidecarBytes);
            int packedVertices = SodiumLightSidecarPacking.packGeometry(geometry, packed);
            if (packedVertices != vertexCount) {
                throw new IllegalStateException("light sidecar vertex count changed while packing");
            }
            packed.flip();

            long allocationVertices = upload.getResult().getLength();
            if (allocationVertices != vertexCount) {
                throw new IllegalStateException(
                        "geometry allocation/sidecar vertex mismatch: allocation=" + allocationVertices
                                + ", sidecar=" + vertexCount
                );
            }
            long destinationOffset = Math.multiplyExact(
                    upload.getResult().getOffset(),
                    SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
            );
            if (destinationOffset + sidecarBytes > sidecar.size()) {
                throw new IllegalStateException("light sidecar upload exceeds its companion buffer");
            }
            this.stagingBuffer.enqueueCopy(packed, sidecar, destinationOffset);
            TorchEpochTelemetry.recordSidecarUpload(sidecarBytes, 1L);
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("could not mirror a compact terrain upload", throwable);
        } finally {
            if (packed != null) {
                MemoryUtil.memFree(packed);
            }
        }
    }

    @Inject(method = "transferSegments", at = @At("HEAD"))
    private void metallum$captureLightSidecarTransfer(
            final Collection<?> commands,
            final long newCapacity,
            final CallbackInfo ci
    ) {
        this.metallum$transferGeometrySource = this.arenaBuffer;
        this.metallum$transferSidecarSource = this.metallum$lightSidecar;
    }

    @Inject(method = "transferSegments", at = @At("RETURN"))
    private void metallum$transferLightSidecar(
            final Collection<?> commands,
            final long newCapacity,
            final CallbackInfo ci
    ) {
        GpuBuffer oldGeometry = this.metallum$transferGeometrySource;
        GpuBuffer oldSidecar = this.metallum$transferSidecarSource;
        this.metallum$transferGeometrySource = null;
        this.metallum$transferSidecarSource = null;
        if (oldGeometry == null || oldSidecar == null) {
            return;
        }
        if (!SodiumLightSidecar.isRuntimeActive()) {
            this.metallum$lightSidecar = null;
            this.metallum$discardLightSidecar(oldGeometry, oldSidecar, null);
            return;
        }

        GpuBuffer newSidecar = null;
        long copiedBytes = 0L;
        long copyCommands = 0L;
        try {
            newSidecar = SodiumLightSidecar.createCompanion(this.arenaBuffer);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            for (Object command : commands) {
                PendingBufferCopyCommandAccessor copy = (PendingBufferCopyCommandAccessor) command;
                long readVertex = Integer.toUnsignedLong(copy.metallum$getReadOffset());
                long writeVertex = Integer.toUnsignedLong(copy.metallum$getWriteOffset());
                long vertexCount = Integer.toUnsignedLong(copy.metallum$getLength());
                long readOffset = Math.multiplyExact(readVertex, SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE);
                long writeOffset = Math.multiplyExact(writeVertex, SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE);
                long bytes = Math.multiplyExact(vertexCount, SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE);
                if (readOffset + bytes > oldSidecar.size() || writeOffset + bytes > newSidecar.size()) {
                    throw new IllegalStateException("light sidecar arena transfer is out of bounds");
                }
                encoder.copyToBuffer(
                        oldSidecar.slice(readOffset, bytes),
                        newSidecar.slice(writeOffset, bytes)
                );
                copiedBytes = Math.addExact(copiedBytes, bytes);
                copyCommands++;
            }
            SodiumLightSidecar.replace(oldGeometry, oldSidecar, this.arenaBuffer, newSidecar);
            this.metallum$lightSidecar = newSidecar;
            newSidecar = null;
        } catch (Throwable throwable) {
            this.metallum$lightSidecar = null;
            this.metallum$discardLightSidecar(oldGeometry, oldSidecar, throwable);
            if (newSidecar != null) {
                try {
                    newSidecar.close();
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
            }
            SodiumLightSidecar.fail("could not mirror a geometry arena transfer", throwable);
            return;
        }

        try {
            oldSidecar.close();
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("could not release the previous geometry light companion", throwable);
        }
        try {
            TorchEpochTelemetry.recordSidecarResizeCopies(copiedBytes, copyCommands);
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("could not record a geometry light companion transfer", throwable);
        }
    }

    @Unique
    private void metallum$discardLightSidecar(
            final GpuBuffer geometry,
            final GpuBuffer sidecar,
            @Nullable final Throwable primaryFailure
    ) {
        try {
            SodiumLightSidecar.detach(geometry, sidecar);
        } catch (Throwable cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
        try {
            sidecar.close();
        } catch (Throwable cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void metallum$deleteLightSidecar(final CallbackInfo ci) {
        GpuBuffer sidecar = this.metallum$lightSidecar;
        if (sidecar == null) {
            return;
        }
        this.metallum$lightSidecar = null;
        try {
            SodiumLightSidecar.detach(this.arenaBuffer, sidecar);
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("light sidecar registry cleanup failed", throwable);
        }
        try {
            sidecar.close();
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("light sidecar release failed", throwable);
        }
    }
}
