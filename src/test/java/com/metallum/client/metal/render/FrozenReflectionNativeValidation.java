package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.metallum.client.radiance.RadianceGpuResources;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * End-to-end Java/FFM/Swift/Metal validation of the immutable frozen reflection context.
 * The polling loop is test-only; production observes readiness only while binding an existing
 * render encoder and performs neither synchronous GPU waits nor texture readbacks.
 */
public final class FrozenReflectionNativeValidation {
    private FrozenReflectionNativeValidation() {
    }

    public static void main(final String[] args) throws InterruptedException {
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        require(!MetalNativeBridge.isNullHandle(device), "Metal device is unavailable");
        MemorySegment layer = MemorySegment.NULL;
        MTLCommandQueue queue = null;
        try {
            layer = MetalNativeBridge.metallum_create_metal_layer(device, 1.0);
            require(!MetalNativeBridge.isNullHandle(layer), "Metal layer creation failed");
            queue = MTLCommandQueue.create(device, layer);
            short[] rgba = new short[64 * 64 * 64 * 4];
            byte[] validity = new byte[64 * 64 * 64];
            Arrays.fill(validity, (byte) 0xff);
            try (RadianceGpuResources resources = requireNonNull(RadianceGpuResources.create(
                    device,
                    queue.nativeHandle(),
                    71L,
                    MetalNativeBridge::metallum_radiance_context_destroy
            ), "frozen reflection native context creation failed")) {
                RadianceGpuResources.GpuStats initialStats = resources.getStats();
                require(initialStats != null && !initialStats.ready() && !initialStats.probeReady(),
                        "new frozen reflection context must start in the safe disabled state");
                validateDedicatedVertexBinding(device, queue, resources);
                require(resources.queueFrozenBuild(71L, -64, 0, 128, rgba, validity, 0.35F, 0.30F, false),
                        "frozen private-texture upload and compute build was not queued");
                long deadline = System.nanoTime() + 2_000_000_000L;
                RadianceGpuResources.GpuStats stats;
                do {
                    Thread.sleep(5L);
                    stats = resources.getStats();
                } while ((stats == null || stats.buildInFlight()) && System.nanoTime() < deadline);
                require(stats != null && stats.ready() && stats.probeReady() && !stats.buildInFlight(),
                        "native frozen reflection build did not complete");
                require(stats.worldGeneration() == 71L
                                && stats.sourceOriginX() == -64
                                && stats.sourceOriginY() == 0
                                && stats.sourceOriginZ() == 128
                                && stats.probeOriginX() == -64
                                && stats.probeOriginY() == 0
                                && stats.probeOriginZ() == 128,
                        "source and directional fields must share one fixed snapped origin");
                require(stats.persistentBytes() > 0L && stats.probePersistentBytes() > 0L
                                && stats.sourceMipBuildCount() == 1L
                                && stats.probeBuildCount() == 1L
                                && stats.probeMipBuildCount() == 1L,
                        "native stats did not prove one bounded source/probe build");
                validateDedicatedVertexBinding(device, queue, resources);
            }
            System.out.println("Frozen reflection Java/FFM/Swift/Metal validation passed");
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

    private static void validateDedicatedVertexBinding(
            final MemorySegment device,
            final MTLCommandQueue queue,
            final RadianceGpuResources resources
    ) {
        MemorySegment target = MetalNativeBridge.metallum_create_texture_2d(
                device,
                MTLPixelFormat.BGRA8Unorm,
                1L, 1L, 1L, 1L, 0L,
                MTLTextureUsage.RenderTarget.value,
                MTLStorageMode.Private,
                true,
                "Frozen reflection native validation target"
        );
        require(!MetalNativeBridge.isNullHandle(target), "validation render target creation failed");
        MTLCommandBuffer commandBuffer = queue.makeCommandBuffer("Frozen reflection binding validation");
        try {
            MTLRenderCommandEncoder encoder = commandBuffer.makeRenderCommandEncoder(
                    target, MemorySegment.NULL, MemorySegment.NULL,
                    1.0, 1.0, 2,
                    0.0F, 0.0F, 0.0F, 1.0F,
                    0, 0, 1.0, 0
            );
            try {
                require(resources.bindVertexResources(encoder.handle()),
                        "dedicated raw reflection binding rejected a live render encoder");
            } finally {
                encoder.endEncoding();
            }
            commandBuffer.commit();
            require(commandBuffer.waitUntilCompleted(5_000L),
                    "reflection binding validation command buffer did not complete");
        } finally {
            commandBuffer.close();
            MetalNativeBridge.metallum_release_object(target);
        }
    }

    private static <T> T requireNonNull(final T value, final String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
        return value;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
