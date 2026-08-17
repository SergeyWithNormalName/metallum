package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.lang.foreign.MemorySegment;

/**
 * Exercises the production Java resource-extraction bridge with the native
 * precompiled library deliberately disabled. This proves every mandatory
 * source fallback is present next to the extracted dylib before device warmup.
 */
public final class ForcedSourceStartupValidation {
    private ForcedSourceStartupValidation() {
    }

    public static void main(final String[] args) {
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        require(!MetalNativeBridge.isNullHandle(device), "Metal device is unavailable");
        try {
            int status = MetalNativeBridge.metallum_init_pipelines(device);
            require(status == 2,
                    "Forced-source startup did not initialize every mandatory native pipeline: " + status);
            System.out.println("Forced-source Java/FFM/native startup validation passed");
        } finally {
            MetalNativeBridge.metallum_release_device_caches(device);
            MetalNativeBridge.metallum_release_object(device);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
