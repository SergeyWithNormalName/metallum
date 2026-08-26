package com.metallum.client.lighting.cloud;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Pure policy, mathematical contracts, and physical constants for Metallum Cloud Shadows.
 */
public final class CloudShadowPolicy {
    /** Vanilla cell edge length in blocks (X and Z). */
    public static final float CELL_SIZE_BLOCKS = 12.0f;

    /** Vanilla Fancy/3D cloud vertical thickness in blocks (Y). */
    public static final float CLOUD_THICKNESS_BLOCKS = 4.0f;

    /** Vanilla cloud animation speed: 400 ticks per 12-block cell (0.03 blocks/tick). */
    public static final int TICKS_PER_CELL = 400;
    public static final float BLOCKS_PER_TICK = 0.030000001f;

    /** Vanilla cloud Z-offset anchor. */
    public static final float VANILLA_Z_OFFSET = 3.9600000381469727f;

    /** Maximum direct-light attenuation for fully opaque flat clouds (e.g. 90% attenuation = 0.10 min transmittance). */
    public static final float FLAT_SHADOW_MAX_ATTENUATION = 0.90f;

    /** Maximum direct-light attenuation for fully opaque thick volumetric clouds (e.g. 95% attenuation = 0.05 min transmittance). */
    public static final float VOLUMETRIC_SHADOW_MAX_ATTENUATION = 0.95f;

    /** Near-horizon elevation threshold where shadow projection starts fading to unshadowed (Ly <= 0.04). */
    public static final float HORIZON_LOW_ELEVATION = 0.04f;

    /** Near-horizon elevation threshold where full shadow projection is stable (Ly >= 0.10). */
    public static final float HORIZON_STABLE_ELEVATION = 0.10f;

    /** Celestial direction change threshold (in radians/components) triggering volumetric preintegration update (~0.57°). */
    public static final float VOLUMETRIC_DIRECTION_UPDATE_THRESHOLD = 0.01f;

    /** Number of density samples along the celestial ray during volumetric precomputation. */
    public static final int VOLUMETRIC_PREINTEGRATION_SAMPLES = 8;

    /** Bounded artistic weight for the rough, water-only cloud reflection layer. */
    public static final float WATER_REFLECTION_STRENGTH = 1.0f;

    /**
     * Virtual distance of the translation-invariant water cloud environment.
     *
     * <p>This is deliberately not the physical cloud-plane height. Water reflections sample
     * clouds as a distant angular sky layer so camera translation and eye bob cannot move the
     * reflected pattern. Ninety-six blocks keeps vanilla 12-block cells broad enough to read as
     * rough clouds rather than small noisy tiles.</p>
     */
    public static final float WATER_REFLECTION_ANGULAR_DISTANCE_BLOCKS = 96.0f;

    /** Lower bound for the angular sky-layer projection near the horizon. */
    public static final float WATER_REFLECTION_MIN_ELEVATION = 0.10f;

    private CloudShadowPolicy() {
    }

    /**
     * Computes the smooth near-horizon projection stability weight in [0, 1].
     */
    public static float horizonStabilityWeight(final float lightY) {
        if (!Float.isFinite(lightY) || lightY <= HORIZON_LOW_ELEVATION) {
            return 0.0f;
        }
        if (lightY >= HORIZON_STABLE_ELEVATION) {
            return 1.0f;
        }
        float t = (lightY - HORIZON_LOW_ELEVATION) / (HORIZON_STABLE_ELEVATION - HORIZON_LOW_ELEVATION);
        return t * t * (3.0f - 2.0f * t); // smoothstep
    }

    /**
     * Computes the ray intersection parameter t with the cloud plane at targetHeight.
     * Ray: P + t * L. Intersection at Py + t * Ly = targetHeight.
     *
     * @return t if intersection is valid and ahead along ray, otherwise negative
     */
    public static float rayIntersectionT(
            final float receiverWorldY,
            final float lightY,
            final float targetHeight
    ) {
        if (!Float.isFinite(lightY) || lightY <= 0.001f) {
            return -1.0f;
        }
        return (targetHeight - receiverWorldY) / lightY;
    }

    /**
     * One horizontal coordinate of the translation-invariant angular cloud environment.
     *
     * <p>Only reflected direction and vanilla animation phase are inputs. Camera X/Y/Z and the
     * water receiver position are intentionally absent, making jump/bob/walk stability a data-flow
     * invariant instead of a tuned cancellation.</p>
     */
    public static float waterReflectionAngularCoordinate(
            final float horizontalDirection,
            final float rayElevation,
            final float animationOffset
    ) {
        if (!Float.isFinite(horizontalDirection)
                || !Float.isFinite(rayElevation)
                || !Float.isFinite(animationOffset)
                || rayElevation <= 0.02f) {
            return Float.NaN;
        }
        float stableElevation = Math.max(rayElevation, WATER_REFLECTION_MIN_ELEVATION);
        return horizontalDirection / stableElevation
                * WATER_REFLECTION_ANGULAR_DISTANCE_BLOCKS
                + animationOffset;
    }

    /**
     * Computes analytical flat cloud transmittance from coverage [0..1] and opacity [0..1].
     */
    public static float flatTransmittance(final float coverage, final float opacity) {
        float safeCoverage = Math.clamp(coverage, 0.0f, 1.0f);
        float safeOpacity = Math.clamp(opacity / 0.8f, 0.0f, 1.0f);
        float density = safeCoverage * safeOpacity;
        return Math.clamp(1.0f - FLAT_SHADOW_MAX_ATTENUATION * density, 0.0f, 1.0f);
    }

    /**
     * Computes volumetric cloud transmittance from effective optical density and opacity.
     */
    public static float volumetricTransmittance(final float opticalDensity, final float opacity) {
        float safeDensity = Math.max(opticalDensity, 0.0f);
        float safeOpacity = Math.clamp(opacity / 0.8f, 0.0f, 1.0f);
        float density = Math.min(safeDensity, 1.0f) * safeOpacity;
        return Math.clamp(1.0f - VOLUMETRIC_SHADOW_MAX_ATTENUATION * density, 0.0f, 1.0f);
    }

    /**
     * Evaluates whether a receiver world position is above the cloud slab.
     */
    public static boolean isReceiverAboveClouds(
            final float receiverWorldY,
            final float cloudHeight,
            final float cloudThickness
    ) {
        return receiverWorldY >= (cloudHeight + cloudThickness);
    }

    /**
     * Calculates the exact vanilla cloud animation X offset for a given gameTime and partialTick.
     */
    public static float computeCloudOffsetX(
            final long gameTime,
            final float partialTick,
            final int textureWidth
    ) {
        if (textureWidth <= 0) {
            return 0.0f;
        }
        long ticksPerGrid = (long) textureWidth * TICKS_PER_CELL;
        long modGameTime = Math.floorMod(gameTime, ticksPerGrid);
        float gameTimeOffset = (float) modGameTime + partialTick;
        return gameTimeOffset * BLOCKS_PER_TICK;
    }

    /**
     * Calculates the exact vanilla cloud animation Z offset.
     */
    public static float computeCloudOffsetZ() {
        return VANILLA_Z_OFFSET;
    }
}
