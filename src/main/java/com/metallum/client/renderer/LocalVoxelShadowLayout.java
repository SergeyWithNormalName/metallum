package com.metallum.client.renderer;

import java.util.Objects;

/** Fixed L6 local-shadow work and upload limits; no preset may exceed the compile-time caps. */
public final class LocalVoxelShadowLayout {
    /**
     * Version 3 makes descriptor state zero an explicit approximate-direct path rather than
     * the retired DDA fallback. This prevents a missing resident page from blacking out a
     * valid local light while its cache page is prepared.
     */
    public static final int ABI_VERSION = 3;
    /** Compatibility bound for the legacy contiguous builder; production uses atlas descriptors. */
    public static final int MAX_SHADOWED_LOCAL_LIGHTS = 2;
    public static final int MAX_SHADOW_DESCRIPTORS =
            AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS;
    public static final int MAX_DDA_STEPS = 96;
    public static final int MAX_DYNAMIC_SHADOW_LIGHTS = 8;
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
            DynamicShadowBudget dynamicShadows,
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
            Objects.requireNonNull(dynamicShadows, "dynamicShadows");
            if (dynamicShadows.preset() != preset) {
                throw new IllegalArgumentException("L6 dynamic-shadow preset differs from its owner");
            }
            if (paramsRingBytes != (long) PARAMS_BYTES * PARAMS_RING_SLOTS
                    || proxyRingBytes != (long) PROXY_STRIDE_BYTES * maxEntityProxies * PARAMS_RING_SLOTS
                    || shadowReferenceRingBytes
                    != LocalVoxelShadowAtlasLayout.descriptorRingBytes()
                    || visibilityCacheBytes
                    != LocalVoxelShadowAtlasLayout.forPreset(preset).atlasBytes()
                    || totalDedicatedBytes != paramsRingBytes + proxyRingBytes
                    + shadowReferenceRingBytes + visibilityCacheBytes
                    + dynamicShadows.atlasBytes()) {
                throw new IllegalArgumentException("L6 upload-ring accounting changed");
            }
        }

        /** Static residency plus the isolated triple-buffered dynamic suffix. */
        public long totalVisibilityAtlasBytes() {
            return Math.addExact(this.visibilityCacheBytes, this.dynamicShadows.atlasBytes());
        }
    }

    /** One centrally editable quality/admission declaration for moving shadow sources. */
    public record DynamicShadowBudget(
            LightingPreset preset,
            int heroSlots,
            int pageEdge,
            int maxSteps,
            long pageBytes,
            long atlasBytes
    ) {
        public DynamicShadowBudget {
            Objects.requireNonNull(preset, "preset");
            if (heroSlots < 1 || heroSlots > MAX_DYNAMIC_SHADOW_LIGHTS
                    || pageEdge != 16 && pageEdge != 32
                    || maxSteps < 1 || maxSteps > MAX_DDA_STEPS
                    || pageBytes != LocalVoxelShadowAtlasLayout.pageAllocationBytes(pageEdge)
                    || atlasBytes != Math.multiplyExact(
                    Math.multiplyExact(pageBytes, heroSlots), PARAMS_RING_SLOTS)) {
                throw new IllegalArgumentException("Invalid L6 dynamic-shadow budget");
            }
        }

        public int pageIndex(final int heroSlot, final int inFlightSlot) {
            if (heroSlot < 0 || heroSlot >= this.heroSlots
                    || inFlightSlot < 0 || inFlightSlot >= PARAMS_RING_SLOTS) {
                throw new IndexOutOfBoundsException("Dynamic L6 page slot is outside its budget");
            }
            return Math.addExact(
                    Math.multiplyExact(inFlightSlot, this.heroSlots), heroSlot
            );
        }

        public long pageOffset(final long staticAtlasBytes,
                               final int heroSlot,
                               final int inFlightSlot) {
            if (staticAtlasBytes <= 0L
                    || staticAtlasBytes % LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES != 0L) {
                throw new IllegalArgumentException("Dynamic L6 suffix has an invalid base offset");
            }
            return Math.addExact(
                    staticAtlasBytes,
                    Math.multiplyExact((long) pageIndex(heroSlot, inFlightSlot), this.pageBytes)
            );
        }
    }

    private LocalVoxelShadowLayout() {
    }

    public static Budget forPreset(final LightingPreset preset) {
        Objects.requireNonNull(preset, "preset");
        return switch (preset) {
            case PERFORMANCE -> budget(preset, 1, 32, 8, 1, 16, 32);
            case BALANCED -> budget(preset, 2, 96, 16, 2, 32, 96);
            case ULTRA -> budget(preset, 2, 96, 24, 4, 32, 96);
        };
    }

    private static Budget budget(
            final LightingPreset preset,
            final int shadowedLocalLights,
            final int maxSteps,
            final int maxEntityProxies,
            final int dynamicHeroSlots,
            final int dynamicPageEdge,
            final int dynamicMaxSteps
    ) {
        long dynamicPageBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(
                dynamicPageEdge
        );
        long dynamicAtlasBytes = Math.multiplyExact(
                Math.multiplyExact(dynamicPageBytes, dynamicHeroSlots), PARAMS_RING_SLOTS
        );
        DynamicShadowBudget dynamicShadows = new DynamicShadowBudget(
                preset,
                dynamicHeroSlots,
                dynamicPageEdge,
                dynamicMaxSteps,
                dynamicPageBytes,
                dynamicAtlasBytes
        );
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
                dynamicShadows,
                (long) PARAMS_BYTES * PARAMS_RING_SLOTS
                        + (long) PROXY_STRIDE_BYTES * maxEntityProxies * PARAMS_RING_SLOTS
                        + LocalVoxelShadowAtlasLayout.descriptorRingBytes()
                        + LocalVoxelShadowAtlasLayout.forPreset(preset).atlasBytes()
                        + dynamicAtlasBytes
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
