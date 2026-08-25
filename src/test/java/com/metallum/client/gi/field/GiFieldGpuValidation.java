package com.metallum.client.gi.field;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;

import java.lang.foreign.MemorySegment;

/** Actual Metal allocation, private upload, mip, reset, capture, and release validation for G1. */
public final class GiFieldGpuValidation {
    private GiFieldGpuValidation() {
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
            validateField(device, queue);
            validateReleaseWhileInFlight(device, queue);
            System.out.println("G1 Java/FFM/private-3D Metal field validation passed");
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

    private static void validateField(final MemorySegment device, final MTLCommandQueue queue) {
        try (GiFieldGpuResources resources = GiFieldGpuResources.create(
                device,
                queue.nativeHandle(),
                101L,
                MetalNativeBridge::metallum_gi_field_release_context_v1
        )) {
            require(resources != null, "Native G1 field context creation failed");
            GiFieldGpuResources.Stats initial = resources.stats();
            require(!initial.ready() && !initial.buildInFlight() && initial.worldGeneration() == 101L,
                    "G1 field initial lifecycle state differs");
            GiFieldMemoryAuditor.Report memory = GiFieldMemoryAuditor.audit(initial.persistentBytes());
            require(memory.withinBudget(), "Actual Metal G1 field allocation is outside the 24 MiB budget: "
                    + initial.persistentBytes());
            System.out.printf(java.util.Locale.ROOT,
                    "G1 field memory: arithmetic=%d actual=%d budget=%d bytes%n",
                    memory.arithmeticBytes(), memory.actualAllocatedBytes(), memory.budgetBytes());

            GiFieldSnapshot snapshot = patternedSnapshot(101L);
            require(resources.uploadOnce(snapshot) == GiFieldGpuResources.STATUS_OK,
                    "G1 private field upload was rejected");
            require(resources.awaitReady(10_000L), "G1 private field upload did not complete");
            GiFieldGpuResources.Stats ready = resources.stats();
            require(ready.ready() && !ready.buildInFlight()
                            && ready.uploadCount() == 1L
                            && ready.mipDispatchCount() == 15L
                            && ready.nearOriginX() == snapshot.origins()[0],
                    "G1 upload/mip completion counters differ");

            GiFieldGpuResources.Capture mip = resources.captureOnce(0, 1);
            require(mip != null && mip.edge() == 16, "G1 one-shot mip capture failed");
            float red = Float.float16ToFloat(mip.packedField()[0]);
            float green = Float.float16ToFloat(mip.packedField()[1]);
            float alpha = Float.float16ToFloat(mip.packedField()[3]);
            float coverage = (mip.coverage()[0] & 0xff) / 255.0F;
            require(Math.abs(red - 0.5F) < 0.01F
                            && Math.abs(green - 0.25F) < 0.01F
                            && Math.abs(alpha - 1.0F) < 0.01F
                            && Math.abs(coverage - 1.0F) < 0.01F,
                    "G1 coverage-aware mip result differs from the synthetic fixture");
            require(resources.captureOnce(0, 1) == null,
                    "G1 diagnostic capture was not one-shot");

            require(resources.reset(102L) == GiFieldGpuResources.STATUS_OK,
                    "G1 generation reset failed");
            require(resources.uploadOnce(snapshot) == GiFieldGpuResources.STATUS_STALE,
                    "G1 stale snapshot survived reset");
            GiFieldSnapshot replacement = patternedSnapshot(102L);
            require(resources.uploadOnce(replacement) == GiFieldGpuResources.STATUS_OK
                            && resources.awaitReady(10_000L),
                    "G1 post-reset upload failed");
            GiFieldGpuResources.Stats replaced = resources.stats();
            require(replaced.worldGeneration() == 102L
                            && replaced.uploadCount() == 2L
                            && replaced.resetCount() == 1L
                            && replaced.rejectedCount() >= 1L,
                    "G1 reset/stale accounting differs");
        }
    }

    private static void validateReleaseWhileInFlight(
            final MemorySegment device,
            final MTLCommandQueue queue
    ) {
        GiFieldGpuResources resources = GiFieldGpuResources.create(
                device,
                queue.nativeHandle(),
                201L,
                MetalNativeBridge::metallum_gi_field_release_context_v1
        );
        require(resources != null, "G1 lifetime-test context creation failed");
        require(resources.uploadOnce(patternedSnapshot(201L)) == GiFieldGpuResources.STATUS_OK,
                "G1 lifetime-test upload failed");
        resources.close();

        MTLCommandBuffer fence = queue.makeCommandBuffer("G1 release lifetime fence");
        try {
            fence.commit();
            require(fence.waitUntilCompleted(10_000L),
                    "G1 release lifetime fence did not complete");
        } finally {
            fence.close();
        }
    }

    private static GiFieldSnapshot patternedSnapshot(final long generation) {
        int cells = GiFieldLayout.CASCADE_COUNT * GiFieldLayout.CELLS_PER_CASCADE;
        int[] origins = new int[GiFieldLayout.CASCADE_COUNT * 3];
        for (int cascade = 0; cascade < GiFieldLayout.CASCADE_COUNT; cascade++) {
            origins[cascade * 3] = GiFieldLayout.centeredOriginBlock(17, cascade);
            origins[cascade * 3 + 1] = GiFieldLayout.centeredOriginBlock(71, cascade);
            origins[cascade * 3 + 2] = GiFieldLayout.centeredOriginBlock(-33, cascade);
        }
        short[] field = new short[cells * GiFieldLayout.FIELD_CHANNELS];
        byte[] coverage = new byte[cells];
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    int cell = GiFieldLayout.cellIndex(x, y, z, GiFieldLayout.CELLS_PER_AXIS);
                    int base = cell * GiFieldLayout.FIELD_CHANNELS;
                    field[base] = Float.floatToFloat16(0.5F);
                    field[base + 1] = Float.floatToFloat16(0.25F);
                    field[base + 2] = Float.floatToFloat16(0.125F);
                    field[base + 3] = Float.floatToFloat16(1.0F);
                    coverage[cell] = (byte) 0xff;
                }
            }
        }
        return new GiFieldSnapshot(generation, origins, field, coverage);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
