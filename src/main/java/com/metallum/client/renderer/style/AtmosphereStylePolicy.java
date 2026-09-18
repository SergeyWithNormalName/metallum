package com.metallum.client.renderer.style;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;

/**
 * Deterministic CPU policy applying style-aware aerial perspective, open-sky awareness,
 * altitude response, and weather response to Minecraft's existing fog parameters without GPU pipeline additions.
 */
public final class AtmosphereStylePolicy {

    private AtmosphereStylePolicy() {
    }

    /**
     * Applies style-aware atmospheric parameters to {@link FogData}.
     *
     * <p>Fails open to the original parameters if context is unavailable, if the style is
     * {@link VisualStyle#VANILLA}, or if the environment is a non-celestial, enclosed cave, or specialized medium.</p>
     */
    public static void apply(
            final FogData fogData,
            final Camera camera,
            final int renderDistanceChunks,
            final DeltaTracker deltaTracker,
            final float bossOverlayDarkening,
            final ClientLevel clientLevel,
            final VisualStyleProfile profile
    ) {
        if (profile == null || profile.atmosphere().isVanillaIdentity()) {
            return;
        }
        if (fogData == null || camera == null || clientLevel == null || deltaTracker == null) {
            return;
        }
        // Medium safety: strictly AIR only
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        // Dimension safety: celestial Overworld-like environments with skylight only
        if (!clientLevel.dimensionType().hasSkyLight()) {
            return;
        }
        // Gameplay-critical special fog safety: blindness, darkness, boss fog
        if (bossOverlayDarkening > 0.0f) {
            return;
        }
        if (camera.entity() instanceof LivingEntity living) {
            if (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)) {
                return;
            }
        }

        // 1. Open-sky exposure: O(1) sky light layer query (0..15), isolated from block light
        int skyLight = clientLevel.getBrightness(LightLayer.SKY, camera.blockPosition());
        float rawExposure = Math.clamp(skyLight / 15.0f, 0.0f, 1.0f);
        float skyExposure = smoothstep(0.0f, 1.0f, rawExposure);
        if (skyExposure <= 1.0e-5f) {
            // Sealed cave / zero sky access: retain original Minecraft fog with zero delta
            return;
        }

        // 2. Conservative altitude response: O(1) reference sea-level comparison clamped to [0.85, 1.15]
        double cameraY = camera.position().y;
        int seaLevel = clientLevel.getSeaLevel();
        float altitudeDelta = (float) (cameraY - seaLevel);
        float altitudeFactor = Math.clamp(1.0f - altitudeDelta * 0.001f, 0.85f, 1.15f);

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float rain = Math.clamp(clientLevel.getRainLevel(partialTick), 0.0f, 1.0f);
        float thunder = Math.clamp(clientLevel.getThunderLevel(partialTick), 0.0f, 1.0f);
        net.minecraft.world.attribute.EnvironmentAttributeProbe probe = camera.attributeProbe();
        Float sunAngleDeg = probe != null
                ? probe.getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE, partialTick)
                : null;
        float sunAngle = sunAngleDeg != null ? sunAngleDeg * 0.017453292f : 0.0f;

        evaluateAdjustments(
                fogData,
                renderDistanceChunks,
                rain,
                thunder,
                sunAngle,
                profile,
                skyExposure,
                altitudeFactor
        );
    }

    /**
     * Overload for testing and backwards-compatible callers evaluating adjustments at standard open-sky sea level.
     */
    public static void evaluateAdjustments(
            final FogData fogData,
            final int renderDistanceChunks,
            final float rain,
            final float thunder,
            final float sunAngle,
            final VisualStyleProfile profile
    ) {
        evaluateAdjustments(
                fogData,
                renderDistanceChunks,
                rain,
                thunder,
                sunAngle,
                profile,
                1.0f,
                1.0f
        );
    }

    /**
     * Internal pure evaluation adjusting distance and color on the mutable {@link FogData}.
     */
    public static void evaluateAdjustments(
            final FogData fogData,
            final int renderDistanceChunks,
            final float rain,
            final float thunder,
            final float sunAngle,
            final VisualStyleProfile profile,
            final float skyExposure,
            final float altitudeFactor
    ) {
        if (fogData == null || profile == null || profile.atmosphere().isVanillaIdentity()) {
            return;
        }

        float effectiveStyleStrength = Math.clamp(skyExposure * altitudeFactor, 0.0f, 1.15f);
        if (effectiveStyleStrength <= 1.0e-5f) {
            return;
        }

        AtmosphereProfile atmo = profile.atmosphere();
        CelestialLightingProfile celestial = profile.celestialLighting();

        float safeRain = Math.clamp(rain, 0.0f, 1.0f);
        float safeThunder = Math.clamp(thunder, 0.0f, 1.0f);

        // 1. Distance adjustment scaled by open-sky and altitude strength
        float renderDistanceBlocks = renderDistanceChunks * 16.0f;
        float origStart = fogData.renderDistanceStart;
        float rainReduction = Math.clamp(
                (safeRain * atmo.rainDistanceScale() + safeThunder * atmo.thunderDistanceScale()) * effectiveStyleStrength,
                0.0f,
                0.90f
        );
        float effectiveStartRatio = Math.clamp(
                atmo.fogDistanceStartRatio() * (1.0f - rainReduction),
                0.05f,
                1.0f
        );
        float targetStyledStart = renderDistanceBlocks * effectiveStartRatio;
        float styledStart = origStart + (targetStyledStart - origStart) * effectiveStyleStrength;
        fogData.renderDistanceStart = Math.min(fogData.renderDistanceStart, styledStart);
        fogData.renderDistanceStart = Math.max(
                0.0f,
                Math.min(fogData.renderDistanceStart, fogData.renderDistanceEnd - 1.0f)
        );

        // 2. Color adjustment in linear space
        float sunY = (float) Math.cos(sunAngle);
        boolean moon = sunY < 0.0f;
        float altitude = Math.max(sunY, 0.0f);

        LinearColor originalLinear = LinearColor.fromSrgb(
                fogData.color.x,
                fogData.color.y,
                fogData.color.z
        );

        if (!moon) {
            // Day / Sunrise / Sunset warmth coupling scaled by effectiveStyleStrength
            float warmth = 1.0f - smoothstep(
                    celestial.sunTransitionMinAltitude(),
                    celestial.sunTransitionMaxAltitude(),
                    altitude
            );
            float sunCoupling = atmo.sunsetAtmosphereWeight() * warmth * (1.0f - safeRain * 0.70f) * effectiveStyleStrength;
            if (sunCoupling > 1.0e-4f) {
                LinearColor styledSun = celestial.evaluateSunColor(altitude);
                LinearColor blended = LinearColor.lerp(originalLinear, styledSun, sunCoupling);
                float origY = originalLinear.luminance();
                float blendY = blended.luminance();
                if (blendY > 1.0e-6f && origY > 1.0e-6f) {
                    blended = blended.scale(origY / blendY);
                }
                originalLinear = blended;
            }
        } else {
            // Night cool atmosphere coupling scaled by effectiveStyleStrength
            float nightCoupling = atmo.nightCoolWeight() * (1.0f - safeRain * 0.50f) * effectiveStyleStrength;
            if (nightCoupling > 1.0e-4f) {
                LinearColor styledMoon = celestial.moonColor();
                LinearColor blended = LinearColor.lerp(originalLinear, styledMoon, nightCoupling);
                float origY = originalLinear.luminance();
                float blendY = blended.luminance();
                if (blendY > 1.0e-6f && origY > 1.0e-6f) {
                    blended = blended.scale(origY / blendY);
                }
                originalLinear = blended;
            }
        }

        // Weather darkening (Rain / Thunder in AIR) scaled by effectiveStyleStrength
        float weatherDarken = Math.clamp(
                1.0f - (safeRain * 0.50f + safeThunder * 0.50f) * atmo.weatherDarkeningScale() * effectiveStyleStrength,
                0.10f,
                1.0f
        );
        if (weatherDarken < 0.999f) {
            originalLinear = originalLinear.scale(weatherDarken);
        }

        // Encode back to sRGB in fogData.color
        fogData.color.x = Math.clamp(originalLinear.toSrgbRed(), 0.0f, 1.0f);
        fogData.color.y = Math.clamp(originalLinear.toSrgbGreen(), 0.0f, 1.0f);
        fogData.color.z = Math.clamp(originalLinear.toSrgbBlue(), 0.0f, 1.0f);
    }

    private static float smoothstep(final float low, final float high, final float value) {
        if (high <= low) {
            return value >= high ? 1.0f : 0.0f;
        }
        float t = Math.clamp((value - low) / (high - low), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
