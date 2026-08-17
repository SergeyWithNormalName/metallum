package com.metallum.client.lighting.water;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector2d;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Pure Java reference policy and optical model for Metallum WATER-CAUSTICS-1.
 *
 * <p>Underwater caustics are causally and visually synchronized with the existing
 * surface water-wave field. Both systems evaluate the identical world-space wave phase,
 * frequencies, noise harmonics, and animation clock.</p>
 */
public final class WaterCausticsPolicy {
    public static final float AIR_IOR = 1.0f;
    public static final float WATER_IOR = 1.3333333f;
    public static final float ETA = AIR_IOR / WATER_IOR; // 0.75f
    public static final float ETA_SQUARED = ETA * ETA;   // 0.5625f
    public static final float DEPTH_ATTENUATION_SIGMA = 0.08f;
    public static final float CAUSTIC_CONTRAST = 1.25f;
    public static final float CAUSTIC_MEAN_FOCUS = 0.85f;
    public static final float MIN_CAUSTIC_GAIN = 0.45f;
    public static final float MAX_CAUSTIC_GAIN = 2.40f;

    public record WaveState(
            float slopeX,
            float slopeZ,
            float crest,
            float causticFocusing
    ) {}

    private WaterCausticsPolicy() {
    }

    /** Exact bitwise hash matching GLSL {@code metallumWaterHashV1}. */
    public static float hash(final int cellX, final int cellY, final int periodMask) {
        int wrappedX = cellX & periodMask;
        int wrappedY = cellY & periodMask;
        int hash = wrappedX * 0x9e3779b9 + wrappedY * 0x85ebca6b;
        hash = (hash ^ (hash >>> 16)) * 0x7feb352d;
        hash = (hash ^ (hash >>> 15)) * 0x846ca68b;
        return (float) ((hash ^ (hash >>> 16)) & 0x00ffffff) * 0.000000059604644775390625f;
    }

    /** Exact value noise matching GLSL {@code metallumWaterValueNoiseV1}. */
    public static float valueNoise(final double posX, final double posY, final int periodMask) {
        int cellX = (int) Math.floor(posX);
        int cellY = (int) Math.floor(posY);
        float fracX = (float) (posX - Math.floor(posX));
        float fracY = (float) (posY - Math.floor(posY));
        float fadeX = fracX * fracX * (3.0f - 2.0f * fracX);
        float fadeY = fracY * fracY * (3.0f - 2.0f * fracY);

        float a = hash(cellX, cellY, periodMask);
        float b = hash(cellX + 1, cellY, periodMask);
        float c = hash(cellX, cellY + 1, periodMask);
        float d = hash(cellX + 1, cellY + 1, periodMask);

        float mixAB = a + (b - a) * fadeX;
        float mixCD = c + (d - c) * fadeX;
        return mixAB + (mixCD - mixAB) * fadeY;
    }

    /**
     * Evaluates the authoritative water wave field at a continuous world XZ coordinate and time.
     * Both the surface normal perturbation and underwater caustics consume this exact state.
     */
    public static WaveState evaluateWaterWaves(final double worldX, final double worldZ, final double timeSeconds) {
        float time = (float) timeSeconds;
        float macroNoise1 = valueNoise(
                worldX * 0.0625 + (time * 0.08f),
                worldZ * 0.0625 - (time * 0.06f),
                255
        );
        float macroNoise2 = valueNoise(
                worldZ * 0.0625 - (time * 0.07f) + 17.3,
                worldX * 0.0625 + (time * 0.09f) + 31.7,
                255
        );
        double domainWarpX = (macroNoise1 - 0.5f) * 3.2;
        double domainWarpZ = (macroNoise2 - 0.5f) * 3.2;
        double warpedPosX = worldX + domainWarpX;
        double warpedPosZ = worldZ + domainWarpZ;

        double phase1 = (warpedPosX * 0.7071 + warpedPosZ * 0.7071) * 0.28 + (time * 1.25);
        double phase2 = (warpedPosX * -0.5000 + warpedPosZ * 0.8660) * 0.42 - (time * 1.05);
        double phase3 = (warpedPosX * 0.9239 + warpedPosZ * -0.3827) * 0.65 + (time * 1.60);

        float medNoise = valueNoise(
                warpedPosX * 0.25 - (time * 0.20f),
                warpedPosZ * 0.25 + (time * 0.15f),
                255
        );
        float medCentered = medNoise - 0.5f;

        float wave1 = (float) Math.sin(phase1 + medCentered * 1.8f);
        float wave2 = (float) Math.cos(phase2 - medCentered * 1.4f);
        float wave3 = (float) Math.sin(phase3 + medCentered * 1.2f);

        float slopeX = wave1 * 0.7071f - wave2 * 0.5000f + wave3 * 0.9239f;
        float slopeZ = wave1 * 0.7071f + wave2 * 0.8660f - wave3 * 0.3827f;

        float microNoise1 = valueNoise(
                warpedPosX * 0.65 + (time * 0.45f),
                warpedPosZ * 0.65 + (time * 0.35f),
                255
        );
        float microNoise2 = valueNoise(
                warpedPosZ * 0.65 - (time * 0.40f) + 43.1,
                warpedPosX * 0.65 + (time * 0.50f) + 19.4,
                255
        );
        float microSlopeX = (microNoise1 - 0.5f) * 0.65f;
        float microSlopeZ = (microNoise2 - 0.5f) * 0.65f;

        float localAmplitude = 0.055f + (0.095f - 0.055f) * macroNoise1;
        float totalSlopeX = (slopeX * 0.60f + microSlopeX) * localAmplitude;
        float totalSlopeZ = (slopeZ * 0.60f + microSlopeZ) * localAmplitude;

        float rawCrest = (wave1 * 0.35f + wave2 * 0.30f + wave3 * 0.30f + medCentered * 0.40f - 0.28f) * 3.2f;
        float crest = Math.clamp(rawCrest, 0.0f, 1.0f);

        // Derive non-linear caustic focusing from wave interference ridges:
        float ridge1 = 1.0f - Math.abs(wave1 + wave2 * 0.65f);
        float ridge2 = 1.0f - Math.abs(wave2 + wave3 * 0.65f);
        float ridge3 = 1.0f - Math.abs(wave3 + wave1 * 0.65f);
        float focus1 = Math.max(ridge1, 0.0f);
        float focus2 = Math.max(ridge2, 0.0f);
        float focus3 = Math.max(ridge3, 0.0f);
        float rawFocus = (focus1 * focus1 * 0.45f + focus2 * focus2 * 0.35f + focus3 * focus3 * 0.20f);
        rawFocus = rawFocus * (2.2f + localAmplitude * 10.0f) + crest * 0.45f;

        return new WaveState(totalSlopeX, totalSlopeZ, crest, rawFocus);
    }

    /**
     * Refracts incoming celestial light ray from air into water via Snell's law.
     * Returns the unit direction R from receiver pointing UP towards incoming light in water.
     */
    public static Vector3f refractCelestialDirection(
            final float toLightX,
            final float toLightY,
            final float toLightZ
    ) {
        if (toLightY <= 0.001f) {
            return new Vector3f(0.0f, 1.0f, 0.0f);
        }
        float cosTheta1 = Math.clamp(toLightY, 0.0f, 1.0f);
        float k = 1.0f - ETA_SQUARED * (1.0f - cosTheta1 * cosTheta1);
        float refractedY = (float) Math.sqrt(Math.max(k, 0.4375f));
        float refractedX = toLightX * ETA;
        float refractedZ = toLightZ * ETA;
        return new Vector3f(refractedX, refractedY, refractedZ).normalize();
    }

    /**
     * Projects a submerged receiver at (posX, posY, posZ) along the refracted light ray R
     * up to the water surface plane at waterSurfaceY.
     */
    public static Vector2d projectToWaterSurface(
            final double posX,
            final double posY,
            final double posZ,
            final float waterSurfaceY,
            final Vector3f refractedRay
    ) {
        double depth = waterSurfaceY - posY;
        if (depth <= 0.0) {
            return new Vector2d(posX, posZ);
        }
        float rY = Math.max(refractedRay.y(), 0.6614f);
        double dist = depth / rY;
        return new Vector2d(
                posX + refractedRay.x() * dist,
                posZ + refractedRay.z() * dist
        );
    }

    /** Physical exponential depth attenuation: exp(-sigma * depth). */
    public static float evaluateDepthAttenuation(final float depth) {
        if (depth <= 0.0f || Float.isNaN(depth) || Float.isInfinite(depth)) {
            return 0.0f;
        }
        return (float) Math.exp(-DEPTH_ATTENUATION_SIGMA * depth);
    }

    /** Evaluates receiver normal orientation factor against incoming refracted light ray. */
    public static float evaluateOrientationFactor(
            final float normalX,
            final float normalY,
            final float normalZ,
            final Vector3f refractedRay
    ) {
        float nDotR = normalX * refractedRay.x()
                + normalY * refractedRay.y()
                + normalZ * refractedRay.z();
        if (nDotR <= 0.001f || Float.isNaN(nDotR)) {
            return 0.0f;
        }
        return Math.clamp(nDotR * 1.25f, 0.0f, 1.0f);
    }

    /**
     * Evaluates complete mean-preserving caustic gain on a submerged surface.
     * Supports both camera-underwater and above-water viewing of submerged receivers.
     * Guaranteed to return in [0.45, 2.40] and 1.0 when not submerged or unlit.
     */
    public static float evaluateCausticGain(
            final double worldX,
            final double worldY,
            final double worldZ,
            final float normalX,
            final float normalY,
            final float normalZ,
            final boolean receiverSubmerged,
            final float submergedDepth,
            final boolean cameraUnderwater,
            final float cameraWaterSurfaceY,
            final float toLightX,
            final float toLightY,
            final float toLightZ,
            final double timeSeconds
    ) {
        if (!receiverSubmerged && !cameraUnderwater) {
            return 1.0f;
        }
        float depth;
        float effectiveSurfaceY;
        if (receiverSubmerged && submergedDepth > 0.0f) {
            depth = submergedDepth;
            effectiveSurfaceY = (float) (worldY + depth);
        } else if (cameraUnderwater) {
            depth = (float) (cameraWaterSurfaceY - worldY);
            effectiveSurfaceY = cameraWaterSurfaceY;
        } else {
            return 1.0f;
        }
        if (depth <= 0.0f || Float.isNaN(depth) || Float.isInfinite(depth)) {
            return 1.0f;
        }
        if (toLightY <= 0.001f) {
            return 1.0f;
        }
        Vector3f refractedRay = refractCelestialDirection(toLightX, toLightY, toLightZ);
        float orientation = evaluateOrientationFactor(normalX, normalY, normalZ, refractedRay);
        if (orientation <= 0.0f) {
            return 1.0f;
        }
        float depthWeight = evaluateDepthAttenuation(depth);
        if (depthWeight <= 0.0001f) {
            return 1.0f;
        }
        Vector2d surfaceUV = projectToWaterSurface(worldX, worldY, worldZ, effectiveSurfaceY, refractedRay);
        WaveState wave = evaluateWaterWaves(surfaceUV.x(), surfaceUV.y(), timeSeconds);

        float centeredFocus = wave.causticFocusing() - CAUSTIC_MEAN_FOCUS;
        float causticStrength = depthWeight * orientation * CAUSTIC_CONTRAST;
        return Math.clamp(1.0f + centeredFocus * causticStrength, MIN_CAUSTIC_GAIN, MAX_CAUSTIC_GAIN);
    }

    /**
     * Backward-compatible evaluation overload assuming camera underwater.
     */
    public static float evaluateCausticGain(
            final double worldX,
            final double worldY,
            final double worldZ,
            final float normalX,
            final float normalY,
            final float normalZ,
            final float waterSurfaceY,
            final float toLightX,
            final float toLightY,
            final float toLightZ,
            final double timeSeconds
    ) {
        return evaluateCausticGain(
                worldX, worldY, worldZ,
                normalX, normalY, normalZ,
                true, (float) (waterSurfaceY - worldY),
                true, waterSurfaceY,
                toLightX, toLightY, toLightZ,
                timeSeconds
        );
    }

    /**
     * Resolves the authoritative local water surface height above the given camera position.
     * Bounded upward scan in the current level, deterministic, 0 allocations.
     */
    public static float resolveWaterSurfaceY(
            final Level level,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        if (level == null) {
            return (float) (cameraY + 1.0);
        }
        int blockX = (int) Math.floor(cameraX);
        int blockY = (int) Math.floor(cameraY);
        int blockZ = (int) Math.floor(cameraZ);
        int maxY = Math.min(level.getMaxY(), blockY + 32);
        int minY = Math.max(level.getMinY(), blockY - 32);
        if (blockY < minY || blockY > maxY) {
            return (float) (cameraY + 1.0);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, blockY, blockZ);
        FluidState currentFluid = level.getFluidState(pos);
        if (!currentFluid.is(FluidTags.WATER)) {
            for (int dy = 1; dy <= 8; dy++) {
                pos.setY(blockY - dy);
                if (level.getFluidState(pos).is(FluidTags.WATER)) {
                    blockY = blockY - dy;
                    break;
                }
            }
        }
        float resolvedSurfaceY = (float) (blockY + 1.0);
        for (int y = blockY; y <= maxY; y++) {
            pos.setY(y);
            FluidState fluidState = level.getFluidState(pos);
            if (fluidState.is(FluidTags.WATER)) {
                float height = fluidState.getHeight(level, pos);
                resolvedSurfaceY = y + height;
            } else {
                break;
            }
        }
        return resolvedSurfaceY;
    }
}
