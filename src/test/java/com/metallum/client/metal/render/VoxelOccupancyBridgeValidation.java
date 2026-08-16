package com.metallum.client.metal.render;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.RendererFeatureMask;
import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelChromaticFilter;
import com.metallum.client.voxel.VoxelClipmapLayout;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelShadowCacheBuilder;
import com.metallum.client.voxel.VoxelShadowCacheMirror;
import com.metallum.client.voxel.VoxelUploadBatch;
import com.metallum.client.voxel.VoxelWorldToken;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** End-to-end Java packet/FFM/native/Metal validation for the production L5 bridge. */
public final class VoxelOccupancyBridgeValidation {
    private static final long LIGHTING_GENERATION = 7L;

    private VoxelOccupancyBridgeValidation() {
    }

    public static void main(final String[] args) {
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        require(!MetalNativeBridge.isNullHandle(device), "Metal device is unavailable");
        MemorySegment layer = MemorySegment.NULL;
        MTLCommandQueue queue = null;
        try {
            layer = MetalNativeBridge.metallum_create_metal_layer(device, 1.0);
            require(!MetalNativeBridge.isNullHandle(layer), "Metal layer creation failed");
            queue = MTLCommandQueue.create(device, layer);
            int pipelineStatus = MetalNativeBridge.metallum_init_pipelines(device);
            require(pipelineStatus > 0, "Built-in Metal pipeline initialization failed");
            validate(device, queue);
            validateMultiPatchAndLiveUpdates(device, queue);
            System.out.println("L5 Java/FFM/native/Metal bridge validation passed");
        } finally {
            if (queue != null) {
                queue.close();
            }
            if (!MetalNativeBridge.isNullHandle(layer)) {
                MetalNativeBridge.metallum_release_object(layer);
            }
            MetalNativeBridge.metallum_release_device_caches(device);
            MetalNativeBridge.metallum_release_object(device);
        }
    }

    private static void validate(final MemorySegment device, final MTLCommandQueue queue) {
        VoxelClipmapLayout.Budget budget = VoxelClipmapLayout.forPreset(
                VoxelClipmapLayout.Preset.PERFORMANCE
        );
        VoxelWorldToken world = new VoxelWorldToken(11L, "minecraft:overworld");
        VoxelClipmapSnapshot snapshot = snapshot(world, 13L, budget);
        try (VoxelOccupancyGpuResources resources = VoxelOccupancyGpuResources.create(
                device, LIGHTING_GENERATION, budget, snapshot
        )) {
            int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
            occupancy[0] = 0x0000_000f;
            byte[] optical = new byte[Math.toIntExact(
                    budget.levels().getFirst().opticalBytesPerBrick()
            )];
            optical[0] = 0x20;
            byte[] chromatic = VoxelChromaticFilter.neutralPackedValues(optical.length);
            VoxelChromaticFilter.putPackedId(chromatic, 0, 14); // red stained glass
            VoxelBrickPatch patch = new VoxelBrickPatch(
                    0, 0, 0, 0, 0, 0, 0, 1,
                    world.generation(), snapshot.clipmapGeneration(), occupancy, optical, chromatic
            );

            FrameState firstFrame = frame(3L);
            VoxelUploadBatch firstBatch = batch(1L, firstFrame.frameId(), world, snapshot, patch);
            VoxelOccupancyGpuResources.FrameUpload firstUpload = resources.encode(
                    firstBatch, snapshot, firstFrame
            );
            require(firstUpload.patchCount() == 1
                            && firstUpload.uploadBytes() == VoxelClipmapLayout.PACKET_HEADER_BYTES
                            + VoxelClipmapLayout.PATCH_RECORD_BYTES + patch.packedPayloadLength()
                            && VoxelChromaticFilter.packedId(patch.chromaticPayload(), 0) == 14,
                    "Java L5 bridge did not encode the exact chromatic patch payload");
            try (CommandBufferScope command = new CommandBufferScope(queue.makeCommandBuffer(
                    "Metallum L5 Java bridge validation"
            ))) {
                require(resources.upload(command.value(), firstUpload)
                                == VoxelOccupancyGpuResources.STATUS_OK,
                        "Java L5 packet was rejected by the native ABI");
                command.commitAndWait();
            }
            VoxelOccupancyGpuResources.CompletedStats stats = resources.readLastCompletedStats();
            require(stats.lightingGeneration() == LIGHTING_GENERATION
                            && stats.clipmapGeneration() == snapshot.clipmapGeneration()
                            && stats.worldGeneration() == world.generation()
                            && stats.submitted() == 1L && stats.completed() == 1L,
                    "Native L5 completion statistics did not match the Java packet");

            FrameState heldFrame = frame(4L);
            VoxelUploadBatch heldBatch = batch(2L, heldFrame.frameId(), world, snapshot, patch);
            VoxelOccupancyGpuResources.FrameUpload heldUpload = resources.encode(
                    heldBatch, snapshot, heldFrame
            );
            try (CommandBufferScope held = new CommandBufferScope(queue.makeCommandBuffer(
                    "Metallum L5 held ring slot"
            )); CommandBufferScope competing = new CommandBufferScope(queue.makeCommandBuffer(
                    "Metallum L5 competing ring slot"
            ))) {
                require(resources.upload(held.value(), heldUpload)
                                == VoxelOccupancyGpuResources.STATUS_OK,
                        "Java L5 bridge could not reserve its ring slot");
                require(resources.upload(competing.value(), heldUpload)
                                == VoxelOccupancyGpuResources.STATUS_RING_SLOT_BUSY,
                        "Native L5 ring busy status did not survive the Java FFM bridge");
                held.commitAndWait();
            }

            try (CommandBufferScope checksum = new CommandBufferScope(queue.makeCommandBuffer(
                    "Metallum L5 checksum"
            ))) {
                require(resources.encodeDebugChecksum(checksum.value(), 0, 2)
                                == VoxelOccupancyGpuResources.STATUS_OK,
                        "Java L5 diagnostic checksum command was rejected");
                checksum.commitAndWait();
            }
            require(resources.readDebugChecksum() != 0,
                    "L5 diagnostic checksum did not observe the uploaded occupancy/material data");
        }
    }

    private static VoxelClipmapSnapshot snapshot(
            final VoxelWorldToken world,
            final long clipmapGeneration,
            final VoxelClipmapLayout.Budget budget
    ) {
        List<VoxelClipmapSnapshot.Level> levels = new ArrayList<>();
        for (int index = 0; index < budget.levels().size(); index++) {
            VoxelClipmapLayout.Level level = budget.levels().get(index);
            levels.add(new VoxelClipmapSnapshot.Level(
                    index,
                    level.subdivision().scale(),
                    level.logicalEdge(),
                    0L,
                    0L,
                    0L,
                    level.brickCountPerAxis()
            ));
        }
        return new VoxelClipmapSnapshot(world, clipmapGeneration, levels);
    }

    private static VoxelUploadBatch batch(
            final long batchId,
            final long frameId,
            final VoxelWorldToken world,
            final VoxelClipmapSnapshot snapshot,
            final VoxelBrickPatch patch
    ) {
        return new VoxelUploadBatch(
                batchId, world, snapshot.clipmapGeneration(), frameId,
                List.of(patch), 0, 0L, 0, 0, 0L, 0L
        );
    }

    private static FrameState frame(final long frameId) {
        return new FrameState(
                FrameContract.temporalPreparationV1(),
                frameId,
                1L,
                1L,
                1L,
                LIGHTING_GENERATION,
                1L,
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.SDR,
                LightingPreset.PERFORMANCE,
                RendererFeatureMask.NONE,
                MetalExecutorKind.METAL3,
                6,
                FrameState.ResourceBytes.NONE,
                MetalDevice.advancedLightingWork(0),
                FrameState.Transforms.identity(),
                FrameState.Transforms.identity(),
                new FrameState.Extent(64, 64),
                new FrameState.Extent(64, 64),
                1.0,
                1.0,
                FrameState.JitterOffset.ZERO,
                Set.of()
        );
    }

    private static void validateMultiPatchAndLiveUpdates(final MemorySegment device, final MTLCommandQueue queue) {
        VoxelClipmapLayout.Budget budget = VoxelClipmapLayout.forPreset(
                VoxelClipmapLayout.Preset.PERFORMANCE
        );
        VoxelWorldToken world = new VoxelWorldToken(11L, "minecraft:overworld");
        VoxelClipmapSnapshot snapshot = snapshot(world, 13L, budget);
        try (VoxelOccupancyGpuResources resources = VoxelOccupancyGpuResources.create(
                device, LIGHTING_GENERATION, budget, snapshot
        )) {
            // 1. Test multi-patch batch upload: 2 patches in a single batch
            int[] occ1 = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
            byte[] opt1 = new byte[Math.toIntExact(budget.levels().getFirst().opticalBytesPerBrick())];
            byte[] chr1 = VoxelChromaticFilter.neutralPackedValues(opt1.length);
            short[] shp1 = new short[opt1.length];
            VoxelBrickPatch patch1 = new VoxelBrickPatch(
                    0, 0, 0, 0, 0, 0, 0, 10,
                    world.generation(), snapshot.clipmapGeneration(), occ1, opt1, chr1, shp1
            );

            int[] occ2 = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
            occ2[0] = 0x0000_0001;
            byte[] opt2 = new byte[Math.toIntExact(budget.levels().getFirst().opticalBytesPerBrick())];
            opt2[0] = 0x20; // opaque
            byte[] chr2 = VoxelChromaticFilter.neutralPackedValues(opt2.length);
            short[] shp2 = new short[opt2.length];
            VoxelBrickPatch patch2 = new VoxelBrickPatch(
                    0, 1, 0, 0, 1, 0, 0, 20,
                    world.generation(), snapshot.clipmapGeneration(), occ2, opt2, chr2, shp2
            );

            FrameState frameMulti = frame(10L);
            VoxelUploadBatch multiBatch = new VoxelUploadBatch(
                    10L, world, snapshot.clipmapGeneration(), frameMulti.frameId(),
                    List.of(patch1, patch2), 0, 0L, 0, 0, 0L, 0L
            );
            VoxelOccupancyGpuResources.FrameUpload multiUpload = resources.encode(
                    multiBatch, snapshot, frameMulti
            );
            require(multiUpload.patchCount() == 2, "multi-patch batch did not encode 2 patches");
            long expectedMultiBytes = VoxelClipmapLayout.PACKET_HEADER_BYTES
                    + 2 * VoxelClipmapLayout.PATCH_RECORD_BYTES
                    + patch1.packedPayloadLength() + patch2.packedPayloadLength();
            require(multiUpload.uploadBytes() == expectedMultiBytes,
                    "multi-patch batch byte size mismatch: expected " + expectedMultiBytes + ", got " + multiUpload.uploadBytes());

            try (CommandBufferScope command = new CommandBufferScope(queue.makeCommandBuffer(
                    "Metallum L5 multi-patch batch validation"
            ))) {
                int status = resources.upload(command.value(), multiUpload);
                require(status == VoxelOccupancyGpuResources.STATUS_OK,
                        "Multi-patch L5 batch upload failed with status " + status);
                command.commitAndWait();
            }

            // 2. Test Live-Update Semantic Sequence (Place -> Break -> Replace)
            VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
            mirror.reset();

            // Initial: empty air in brick (0,0,0)
            int[] emptyOcc = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
            byte[] emptyOpt = new byte[Math.toIntExact(budget.levels().getFirst().opticalBytesPerBrick())];
            byte[] emptyChr = VoxelChromaticFilter.neutralPackedValues(emptyOpt.length);
            short[] emptyShp = new short[emptyOpt.length];
            VoxelBrickPatch initPatch = new VoxelBrickPatch(
                    0, 0, 0, 0, 0, 0, 0, 100,
                    world.generation(), snapshot.clipmapGeneration(), emptyOcc, emptyOpt, emptyChr, emptyShp
            );
            FrameState frameInit = frame(100L);
            VoxelUploadBatch initBatch = new VoxelUploadBatch(
                    100L, world, snapshot.clipmapGeneration(), frameInit.frameId(),
                    List.of(initPatch), 0, 0L, 0, 0, 0L, 0L
            );
            VoxelOccupancyGpuResources.FrameUpload initUpload = resources.encode(initBatch, snapshot, frameInit);
            try (CommandBufferScope cmd = new CommandBufferScope(queue.makeCommandBuffer("init empty upload"))) {
                int status = resources.upload(cmd.value(), initUpload);
                require(status == VoxelOccupancyGpuResources.STATUS_OK, "init upload failed: " + status);
                cmd.commitAndWait();
            }
            long rev1 = mirror.snapshot(snapshot).revision();
            require(rev1 > 0L, "mirror revision was not initialized");

            AdvancedLight testLight = new AdvancedLight(
                    701L, 1L, LightSourceKind.BLOCK,
                    8.5, 8.5, 8.5, 4.0f,
                    1.0f, 1.0f, 1.0f, 1.0f, 15
            );
            VoxelShadowCacheMirror.Snapshot snap1 = mirror.snapshot(snapshot);
            VoxelShadowCacheBuilder.PageResult initPage = VoxelShadowCacheBuilder.buildPage(
                    snap1, testLight, 64, 96
            );
            ByteBuffer initBuf = ByteBuffer.wrap(initPage.payload()).order(ByteOrder.nativeOrder());
            int centralOffset = pageEntryOffset(0, 32, 32, 0, 64);
            require(Float.isInfinite(initBuf.getFloat(centralOffset)),
                    "initial empty world had false shadow");

            // Update: place full opaque cube in brick (0,0,0) at block (9, 8, 8)
            int[] cubeOcc = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
            for (int cz = 0; cz < 2; cz++) {
                for (int cy = 0; cy < 2; cy++) {
                    int wordOffset = (16 + cz) * VoxelBrickPatch.LOGICAL_EDGE + (16 + cy);
                    cubeOcc[wordOffset] |= 0b11 << 18;
                }
            }
            byte[] cubeOpt = new byte[Math.toIntExact(budget.levels().getFirst().opticalBytesPerBrick())];
            int blockIndex = (8 * 16 + 8) * 16 + 9;
            cubeOpt[blockIndex] = 0x20; // block (9,8,8) is opaque
            byte[] cubeChr = VoxelChromaticFilter.neutralPackedValues(cubeOpt.length);
            short[] cubeShp = new short[cubeOpt.length];
            VoxelBrickPatch cubePatch = new VoxelBrickPatch(
                    0, 0, 0, 0, 0, 0, 0, 101,
                    world.generation(), snapshot.clipmapGeneration(), cubeOcc, cubeOpt, cubeChr, cubeShp
            );
            FrameState frameCube = frame(101L);
            VoxelUploadBatch cubeBatch = new VoxelUploadBatch(
                    101L, world, snapshot.clipmapGeneration(), frameCube.frameId(),
                    List.of(cubePatch), 0, 0L, 0, 0, 0L, 0L
            );
            VoxelOccupancyGpuResources.FrameUpload cubeUpload = resources.encode(cubeBatch, snapshot, frameCube);
            try (CommandBufferScope cmd = new CommandBufferScope(queue.makeCommandBuffer("cube place upload"))) {
                int status = resources.upload(cmd.value(), cubeUpload);
                require(status == VoxelOccupancyGpuResources.STATUS_OK, "cube place upload failed: " + status);
                cmd.commitAndWait();
            }
            long rev2 = mirror.snapshot(snapshot).revision();
            require(rev2 > rev1, "mirror revision did not advance after placing block: " + rev2 + " <= " + rev1);

            // Verify shadow cache builder through mirror snapshot sees the new occluder
            VoxelShadowCacheMirror.Snapshot snap2 = mirror.snapshot(snapshot);
            VoxelShadowCacheBuilder.PageResult cubePage = VoxelShadowCacheBuilder.buildPage(
                    snap2, testLight, 64, 96
            );
            ByteBuffer cubeBuf = ByteBuffer.wrap(cubePage.payload()).order(ByteOrder.nativeOrder());
            float cubeHitDist = cubeBuf.getFloat(centralOffset);
            require(Float.isFinite(cubeHitDist) && cubeHitDist > 0.1f && cubeHitDist < 2.0f,
                    "placed block failed to cast shadow in live update page builder: got " + cubeHitDist + " raysWithHits=" + cubePage.raysWithHits());

            // Remove: break the block (return to empty)
            VoxelBrickPatch breakPatch = new VoxelBrickPatch(
                    0, 0, 0, 0, 0, 0, 0, 102,
                    world.generation(), snapshot.clipmapGeneration(), emptyOcc, emptyOpt, emptyChr, emptyShp
            );
            FrameState frameBreak = frame(102L);
            VoxelUploadBatch breakBatch = new VoxelUploadBatch(
                    102L, world, snapshot.clipmapGeneration(), frameBreak.frameId(),
                    List.of(breakPatch), 0, 0L, 0, 0, 0L, 0L
            );
            VoxelOccupancyGpuResources.FrameUpload breakUpload = resources.encode(breakBatch, snapshot, frameBreak);
            try (CommandBufferScope cmd = new CommandBufferScope(queue.makeCommandBuffer("cube break upload"))) {
                int status = resources.upload(cmd.value(), breakUpload);
                require(status == VoxelOccupancyGpuResources.STATUS_OK, "cube break upload failed: " + status);
                cmd.commitAndWait();
            }
            long rev3 = mirror.snapshot(snapshot).revision();
            require(rev3 > rev2, "mirror revision did not advance after breaking block: " + rev3 + " <= " + rev2);

            VoxelShadowCacheMirror.Snapshot snap3 = mirror.snapshot(snapshot);
            VoxelShadowCacheBuilder.PageResult breakPage = VoxelShadowCacheBuilder.buildPage(
                    snap3, testLight, 64, 96
            );
            ByteBuffer breakBuf = ByteBuffer.wrap(breakPage.payload()).order(ByteOrder.nativeOrder());
            require(Float.isInfinite(breakBuf.getFloat(centralOffset)),
                    "broken block shadow did not disappear after removal upload");
        }
    }

    private static int pageEntryOffset(final int face, final int x, final int y, final int layer, final int edge) {
        long entry = ((long) face * edge * edge + (long) y * edge + x) * 2L + layer;
        return Math.toIntExact(entry * 8L);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class CommandBufferScope implements AutoCloseable {
        private final MTLCommandBuffer commandBuffer;

        private CommandBufferScope(final MTLCommandBuffer commandBuffer) {
            this.commandBuffer = commandBuffer;
        }

        private MTLCommandBuffer value() {
            return this.commandBuffer;
        }

        private void commitAndWait() {
            this.commandBuffer.commit();
            if (!this.commandBuffer.waitUntilCompleted(5_000L)) {
                throw new AssertionError("Metal command buffer did not complete successfully");
            }
        }

        @Override
        public void close() {
            this.commandBuffer.close();
        }
    }
}
