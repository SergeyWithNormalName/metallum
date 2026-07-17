package com.metallum.client.renderer;

import java.util.Objects;

/** Pure preset and memory contract for the first non-cached cascaded sun shadow maps. */
public final class SunShadowLayout {
    public static final int ABI_VERSION = 1;
    public static final int MAX_CASCADES = 3;
    public static final int PARAMS_BYTES = 384;
    public static final int PARAMS_RING_SLOTS = 3;
    public static final int SHADOW_COLOR_BYTES_PER_PIXEL = 1;
    public static final int SHADOW_DEPTH_BYTES_PER_PIXEL = 4;

    public record Budget(
            int cascadeCount,
            int resolution,
            float maximumDistance,
            float splitLambda,
            float blendFraction,
            float pcfRadiusTexels,
            float receiverDepthBias,
            float receiverNormalBias,
            float receiverNormalBiasTexels,
            float rasterDepthBias,
            float rasterSlopeBias,
            long paramsRingBytes,
            long shadowTextureBytes,
            long totalBytes
    ) {
        public Budget {
            if (cascadeCount < 2 || cascadeCount > MAX_CASCADES || resolution <= 0) {
                throw new IllegalArgumentException("Invalid sun-shadow cascade layout");
            }
            requirePositive(maximumDistance, "maximum distance");
            requireRange(splitLambda, 0.0f, 1.0f, "split lambda");
            requireRange(blendFraction, 0.0f, 0.25f, "blend fraction");
            requireRange(pcfRadiusTexels, 0.5f, 2.0f, "PCF radius");
            requirePositive(receiverDepthBias, "receiver depth bias");
            requirePositive(receiverNormalBias, "receiver normal bias");
            requireRange(receiverNormalBiasTexels, 0.25f, 2.0f,
                    "receiver normal texel bias");
            requirePositive(rasterDepthBias, "raster depth bias");
            requirePositive(rasterSlopeBias, "raster slope bias");
            if (paramsRingBytes != (long) PARAMS_BYTES * PARAMS_RING_SLOTS
                    || shadowTextureBytes <= 0L
                    || totalBytes != paramsRingBytes + shadowTextureBytes) {
                throw new IllegalArgumentException("Invalid sun-shadow byte declaration");
            }
        }
    }

    private SunShadowLayout() {
    }

    public static Budget forPreset(final LightingPreset preset) {
        Objects.requireNonNull(preset, "preset");
        int cascades;
        int resolution;
        float maximumDistance;
        float pcfRadius;
        float depthBias;
        float normalBias;
        float normalBiasTexels;
        float rasterBias;
        float slopeBias;
        switch (preset) {
            case PERFORMANCE -> {
                cascades = 2;
                resolution = 768;
                maximumDistance = 64.0f;
                pcfRadius = 1.0f;
                depthBias = 0.0018f;
                normalBias = 0.085f;
                normalBiasTexels = 0.50f;
                rasterBias = 1.00f;
                slopeBias = 1.45f;
            }
            case BALANCED -> {
                cascades = 3;
                resolution = 1024;
                maximumDistance = 112.0f;
                pcfRadius = 1.15f;
                depthBias = 0.00135f;
                normalBias = 0.070f;
                normalBiasTexels = 0.50f;
                rasterBias = 1.10f;
                slopeBias = 1.60f;
            }
            case ULTRA -> {
                cascades = 3;
                resolution = 1536;
                maximumDistance = 160.0f;
                pcfRadius = 1.35f;
                depthBias = 0.0010f;
                normalBias = 0.055f;
                normalBiasTexels = 0.50f;
                rasterBias = 1.15f;
                slopeBias = 1.70f;
            }
            default -> throw new IllegalStateException("Unhandled lighting preset " + preset);
        }
        long paramsBytes = (long) PARAMS_BYTES * PARAMS_RING_SLOTS;
        long textureBytes = Math.multiplyExact(
                Math.multiplyExact((long) cascades, Math.multiplyExact(resolution, resolution)),
                SHADOW_COLOR_BYTES_PER_PIXEL + SHADOW_DEPTH_BYTES_PER_PIXEL
        );
        return new Budget(
                cascades,
                resolution,
                maximumDistance,
                0.65f,
                0.09f,
                pcfRadius,
                depthBias,
                normalBias,
                normalBiasTexels,
                rasterBias,
                slopeBias,
                paramsBytes,
                textureBytes,
                Math.addExact(paramsBytes, textureBytes)
        );
    }

    public static float[] cascadeSplits(
            final Budget budget,
            final float nearPlane,
            final float farPlane
    ) {
        Objects.requireNonNull(budget, "budget");
        requirePositive(nearPlane, "near plane");
        if (!Float.isFinite(farPlane) || farPlane <= nearPlane) {
            throw new IllegalArgumentException("Invalid far plane");
        }
        float shadowFar = Math.min(farPlane, budget.maximumDistance());
        if (shadowFar <= nearPlane) {
            shadowFar = Math.nextUp(nearPlane);
        }
        float[] splits = new float[MAX_CASCADES];
        float previous = nearPlane;
        for (int cascade = 1; cascade <= budget.cascadeCount(); cascade++) {
            float ratio = cascade / (float) budget.cascadeCount();
            float logarithmic = (float) (nearPlane * Math.pow(shadowFar / nearPlane, ratio));
            float uniform = nearPlane + (shadowFar - nearPlane) * ratio;
            float split = uniform + (logarithmic - uniform) * budget.splitLambda();
            split = cascade == budget.cascadeCount() ? shadowFar : Math.max(split, Math.nextUp(previous));
            splits[cascade - 1] = split;
            previous = split;
        }
        for (int cascade = budget.cascadeCount(); cascade < MAX_CASCADES; cascade++) {
            splits[cascade] = shadowFar;
        }
        return splits;
    }

    /** Start of the receiver overlap used when blending the next cascade. */
    public static float cascadeBlendStart(
            final Budget budget,
            final float previousSplit,
            final float split
    ) {
        Objects.requireNonNull(budget, "budget");
        if (!Float.isFinite(previousSplit) || previousSplit < 0.0f
                || !Float.isFinite(split) || split <= previousSplit) {
            throw new IllegalArgumentException("Invalid cascade transition interval");
        }
        return split - (split - previousSplit) * budget.blendFraction();
    }

    private static void requirePositive(final float value, final String label) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(label + " must be positive and finite");
        }
    }

    private static void requireRange(
            final float value,
            final float minimum,
            final float maximum,
            final String label
    ) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " is outside its supported range");
        }
    }
}
