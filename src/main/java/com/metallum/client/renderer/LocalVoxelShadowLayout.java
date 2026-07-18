package com.metallum.client.renderer;

import java.util.Objects;

/** Fixed L6 local-shadow work and upload limits; no preset may exceed the compile-time caps. */
public final class LocalVoxelShadowLayout {
    public static final int ABI_VERSION = 2;
    /** Compatibility bound for the legacy contiguous builder; production uses atlas descriptors. */
    public static final int MAX_SHADOWED_LOCAL_LIGHTS = 2;
    public static final int MAX_SHADOW_DESCRIPTORS =
            AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS;
    public static final int MAX_DDA_STEPS = 96;
    public static final int MAX_ENTITY_PROXIES = 32;
    public static final int PARAMS_BYTES = 256;
    public static final int PARAMS_RING_SLOTS = 3;
    public static final int PROXY_STRIDE_BYTES = 32;
    public static final int CACHE_FACE_EDGE = 64;
    public static final int CACHE_FACE_COUNT = 6;
    public static final int CACHE_LAYER_COUNT = 4;
    public static final int CACHE_HIT_STRIDE_BYTES = 8;

    public record Budget(
            LightingPreset preset,
            int shadowedLocalLights,
            int maxShadowDescriptors,
            int maxSteps,
            int maxEntityProxies,
            long paramsRingBytes,
            long proxyRingBytes,
            long shadowReferenceRingBytes,
            long visibilityCacheBytes,
            long totalDedicatedBytes
    ) {
        public Budget {
            Objects.requireNonNull(preset, "preset");
            if (shadowedLocalLights < 1 || shadowedLocalLights > MAX_SHADOWED_LOCAL_LIGHTS
                    || maxShadowDescriptors != MAX_SHADOW_DESCRIPTORS
                    || maxSteps < 1 || maxSteps > MAX_DDA_STEPS
                    || maxEntityProxies < 1 || maxEntityProxies > MAX_ENTITY_PROXIES) {
                throw new IllegalArgumentException("L6 work declaration exceeds its hard compile cap");
            }
            if (paramsRingBytes != (long) PARAMS_BYTES * PARAMS_RING_SLOTS
                    || proxyRingBytes != (long) PROXY_STRIDE_BYTES * maxEntityProxies * PARAMS_RING_SLOTS
                    || shadowReferenceRingBytes
                    != LocalVoxelShadowAtlasLayout.descriptorRingBytes()
                    || visibilityCacheBytes
                    != LocalVoxelShadowAtlasLayout.forPreset(preset).atlasBytes()
                    || totalDedicatedBytes != paramsRingBytes + proxyRingBytes
                    + shadowReferenceRingBytes + visibilityCacheBytes) {
                throw new IllegalArgumentException("L6 upload-ring accounting changed");
            }
        }
    }

    private LocalVoxelShadowLayout() {
    }

    public static Budget forPreset(final LightingPreset preset) {
        Objects.requireNonNull(preset, "preset");
        return switch (preset) {
            case PERFORMANCE -> budget(preset, 1, 32, 8);
            case BALANCED -> budget(preset, 2, 64, 16);
            case ULTRA -> budget(preset, 2, 80, 24);
        };
    }

    private static Budget budget(
            final LightingPreset preset,
            final int shadowedLocalLights,
            final int maxSteps,
            final int maxEntityProxies
    ) {
        return new Budget(
                preset,
                shadowedLocalLights,
                MAX_SHADOW_DESCRIPTORS,
                maxSteps,
                maxEntityProxies,
                (long) PARAMS_BYTES * PARAMS_RING_SLOTS,
                (long) PROXY_STRIDE_BYTES * maxEntityProxies * PARAMS_RING_SLOTS,
                LocalVoxelShadowAtlasLayout.descriptorRingBytes(),
                LocalVoxelShadowAtlasLayout.forPreset(preset).atlasBytes(),
                (long) PARAMS_BYTES * PARAMS_RING_SLOTS
                        + (long) PROXY_STRIDE_BYTES * maxEntityProxies * PARAMS_RING_SLOTS
                        + LocalVoxelShadowAtlasLayout.descriptorRingBytes()
                        + LocalVoxelShadowAtlasLayout.forPreset(preset).atlasBytes()
        );
    }

    public static long cacheBytes(final int shadowedLocalLights) {
        if (shadowedLocalLights < 1
                || shadowedLocalLights > MAX_SHADOWED_LOCAL_LIGHTS) {
            throw new IllegalArgumentException("L6 cache light count is outside its hard cap");
        }
        return Math.multiplyExact(
                Math.multiplyExact(
                        Math.multiplyExact(
                                Math.multiplyExact(
                                        (long) shadowedLocalLights,
                                        CACHE_FACE_COUNT
                                ),
                                (long) CACHE_FACE_EDGE * CACHE_FACE_EDGE
                        ),
                        CACHE_LAYER_COUNT
                ),
                CACHE_HIT_STRIDE_BYTES
        );
    }
}
