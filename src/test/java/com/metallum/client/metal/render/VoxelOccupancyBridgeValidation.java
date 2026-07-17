package com.metallum.client.metal.render;

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
import com.metallum.client.voxel.VoxelClipmapLayout;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelUploadBatch;
import com.metallum.client.voxel.VoxelWorldToken;

import java.lang.foreign.MemorySegment;
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
            VoxelBrickPatch patch = new VoxelBrickPatch(
                    0, 0, 0, 0, 0, 0, 0, 1,
                    world.generation(), snapshot.clipmapGeneration(), occupancy, optical
            );

            FrameState firstFrame = frame(3L);
            VoxelUploadBatch firstBatch = batch(1L, firstFrame.frameId(), world, snapshot, patch);
            VoxelOccupancyGpuResources.FrameUpload firstUpload = resources.encode(
                    firstBatch, firstFrame
            );
            require(firstUpload.patchCount() == 1 && firstUpload.uploadBytes() > 4_096L,
                    "Java L5 bridge did not encode the exact patch payload");
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
                    heldBatch, heldFrame
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
