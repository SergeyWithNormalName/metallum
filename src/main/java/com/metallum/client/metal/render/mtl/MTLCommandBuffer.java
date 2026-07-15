package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {
    public enum PresentResult {
        FAILED,
        NO_DRAWABLE,
        PRESENTED;

        private static PresentResult fromNative(final int value) {
            return switch (value) {
                case 1 -> PRESENTED;
                case 0 -> NO_DRAWABLE;
                default -> FAILED;
            };
        }
    }

    private MemorySegment handle;

    MTLCommandBuffer(final MemorySegment handle) {
        this.handle = handle;
    }

    public MTLBlitCommandEncoder makeBlitCommandEncoder() {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(handle());
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLBlitCommandEncoder");
        }
        return new MTLBlitCommandEncoder(encoder);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final MemorySegment semanticTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int colorLoadAction,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearSemanticEnabled,
            final int clearDepthEnabled,
            final double clearDepth,
            final int gpuTimingStage
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoder(
                handle(),
                colorTexture,
                semanticTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                colorLoadAction,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearSemanticEnabled,
                clearDepthEnabled,
                clearDepth,
                gpuTimingStage
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MemorySegment globalFence
    ) {
        MetalNativeBridge.MTLCommandBuffer_clearColorDepthTexturesRegion(
                handle(),
                colorTexture,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence
        );
    }

    public PresentResult encodePresentTextureToDrawable(
            final MemorySegment layer,
            final MemorySegment sourceTexture,
            final MemorySegment sceneTexture,
            final MemorySegment sceneDepthTexture,
            final MemorySegment semanticTexture,
            final MemorySegment uiTexture,
            final MemorySegment globalFence,
            final boolean spatialHdrPrecomposed,
            final int outputMode,
            final int sourceEncoding,
            final boolean diagnosticPattern,
            final float currentHeadroom,
            final float hdrStrength,
            final float bloomStrength
    ) {
        return PresentResult.fromNative(MetalNativeBridge.MTLCommandBuffer_encodePresentTextureToDrawable(
                handle(),
                layer,
                sourceTexture,
                sceneTexture,
                sceneDepthTexture,
                semanticTexture,
                uiTexture,
                globalFence,
                spatialHdrPrecomposed ? 1 : 0,
                outputMode,
                sourceEncoding,
                diagnosticPattern ? 1 : 0,
                currentHeadroom,
                hdrStrength,
                bloomStrength
        ));
    }

    public int encodeHdrUiBackdrop(
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final MemorySegment sceneDepthTexture,
            final MemorySegment semanticTexture,
            final MemorySegment globalFence,
            final int sourceEncoding,
            final boolean spatialScalingEnabled,
            final boolean hdrPrecomposeEnabled,
            final boolean perceptualScalingEnabled,
            final boolean deferSpatialHdrUiSeed,
            final float currentHeadroom,
            final float hdrStrength,
            final float bloomStrength
    ) {
        return MetalNativeBridge.MTLCommandBuffer_encodeHdrUiBackdrop(
                handle(),
                sourceTexture,
                destinationTexture,
                sceneDepthTexture,
                semanticTexture,
                globalFence,
                sourceEncoding,
                spatialScalingEnabled,
                hdrPrecomposeEnabled,
                perceptualScalingEnabled,
                deferSpatialHdrUiSeed,
                currentHeadroom,
                hdrStrength,
                bloomStrength
        );
    }

    public int materializePreparedHdrUiBackdrop(
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final MemorySegment globalFence
    ) {
        return MetalNativeBridge.MTLCommandBuffer_materializePreparedHdrUiBackdrop(
                handle(),
                sourceTexture,
                destinationTexture,
                globalFence
        );
    }

    public int encodeSpatialScreenshot(
            final MemorySegment rawSceneTexture,
            final MemorySegment uiTexture,
            final MemorySegment destinationTexture,
            final MemorySegment globalFence,
            final int sourceEncoding,
            final float currentHeadroom
    ) {
        return MetalNativeBridge.MTLCommandBuffer_encodeSpatialScreenshot(
                handle(),
                rawSceneTexture,
                uiTexture,
                destinationTexture,
                globalFence,
                sourceEncoding,
                currentHeadroom
        );
    }

    public void recordJavaWorkload(
            final long cpuToSharedBytes,
            final long cpuToSharedOperations,
            final long cpuTransientRequestedBytes,
            final long cpuTransientReservedBytes,
            final long gpuTransientRequestedBytes,
            final long gpuTransientReservedBytes
    ) {
        if (cpuToSharedBytes < 0L
                || cpuToSharedOperations < 0L
                || cpuTransientRequestedBytes < 0L
                || cpuTransientReservedBytes < 0L
                || gpuTransientRequestedBytes < 0L
                || gpuTransientReservedBytes < 0L) {
            throw new IllegalArgumentException("Java workload counters must be non-negative");
        }
        if (cpuTransientReservedBytes < cpuTransientRequestedBytes
                || gpuTransientReservedBytes < gpuTransientRequestedBytes) {
            throw new IllegalArgumentException("Transient reserved bytes must cover requested bytes");
        }
        MetalNativeBridge.metallum_gpu_timing_record_java_workload(
                handle(),
                cpuToSharedBytes,
                cpuToSharedOperations,
                cpuTransientRequestedBytes,
                cpuTransientReservedBytes,
                gpuTransientRequestedBytes,
                gpuTransientReservedBytes
        );
    }

    public void commit() {
        MetalNativeBridge.MTLCommandBuffer_commit(handle());
    }

    public void commitWithSignal(final MemorySegment semaphore) {
        MetalNativeBridge.MTLCommandBuffer_commitWithSignal(handle(), semaphore);
    }

    public boolean isCompleted() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return true;
        }
        return MetalNativeBridge.MTLCommandBuffer_isCompleted(handle()) == 1;
    }

    public boolean waitUntilCompleted(final long timeoutMs) {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return true;
        }
        return MetalNativeBridge.MTLCommandBuffer_waitUntilCompleted(handle(), Math.max(timeoutMs, 0L)) == 0;
    }

    public void pushDebugGroup(final String label) {
        MetalNativeBridge.MTLCommandBuffer_pushDebugGroup(handle(), label);
    }

    public void popDebugGroup() {
        MetalNativeBridge.MTLCommandBuffer_popDebugGroup(handle());
    }

    public int encodeSodiumLightLegacyPatchBatch(
            final MemorySegment packet,
            final long commandCount,
            final MemorySegment globalFence
    ) {
        return MetalNativeBridge.MTLCommandBuffer_encodeSodiumLightLegacyPatchBatch(
                handle(),
                globalFence,
                packet,
                commandCount
        );
    }

    public void close() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return;
        }
        MetalNativeBridge.metallum_release_object(handle);
        handle = MemorySegment.NULL;
    }

    MemorySegment handle() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }
}
