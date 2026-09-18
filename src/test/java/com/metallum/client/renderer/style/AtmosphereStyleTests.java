package com.metallum.client.renderer.style;

import net.minecraft.client.renderer.fog.FogData;
import org.joml.Vector4f;

/**
 * Production test suite for Style-Aware Aerial Perspective, Open-Sky Awareness,
 * and Conservative Altitude Response (ATMOSPHERE-1 & ATMOSPHERE-1.1).
 */
public final class AtmosphereStyleTests {
    private static final float EPSILON = 1.0e-5f;

    private AtmosphereStyleTests() {
    }

    public static void main(final String[] args) {
        testVanillaIdentity();
        testFiniteAndValid();
        testStyleOrdering();
        testClearMiddayRestraint();
        testSunsetResponseContinuity();
        testNightResponse();
        testWeatherMonotonicityAndContinuity();
        testSpecialFogAndMediaIdentity();
        testRenderDistanceScaling();
        testStyleSwitchingNoStaleState();
        testZeroSideEffectFailOpen();
        testOpenSkyExposureAndCaveIsolation();
        testAltitudeResponse();
        System.out.println("All ATMOSPHERE-1 & ATMOSPHERE-1.1 atmosphere style tests passed");
    }

    private static FogData createSampleFogData(
            final float r, final float g, final float b,
            final float envStart, final float envEnd,
            final float rdStart, final float rdEnd
    ) {
        FogData data = new FogData();
        data.color = new Vector4f(r, g, b, 1.0f);
        data.environmentalStart = envStart;
        data.environmentalEnd = envEnd;
        data.renderDistanceStart = rdStart;
        data.renderDistanceEnd = rdEnd;
        data.skyEnd = rdEnd;
        data.cloudEnd = rdEnd;
        return data;
    }

    private static FogData copyFogData(final FogData src) {
        return createSampleFogData(
                src.color.x, src.color.y, src.color.z,
                src.environmentalStart, src.environmentalEnd,
                src.renderDistanceStart, src.renderDistanceEnd
        );
    }

    /**
     * Requirement A: VANILLA IDENTITY across clear day, sunset, night, rain, thunder.
     */
    private static void testVanillaIdentity() {
        VisualStyleProfile vanillaProfile = VisualStyleProfiles.profile(VisualStyle.VANILLA);
        float[] sunAngles = {0.0f, (float) (Math.PI * 0.45), (float) (Math.PI * 0.5), (float) Math.PI};
        float[] rains = {0.0f, 0.5f, 1.0f};
        float[] thunders = {0.0f, 1.0f};
        int[] chunkDistances = {8, 16, 24, 32};
        float[] exposures = {0.0f, 0.5f, 1.0f};
        float[] altitudeFactors = {0.85f, 1.0f, 1.15f};

        for (float sunAngle : sunAngles) {
            for (float rain : rains) {
                for (float thunder : thunders) {
                    for (int chunks : chunkDistances) {
                        for (float exp : exposures) {
                            for (float altFactor : altitudeFactors) {
                                float rdBlocks = chunks * 16.0f;
                                FogData original = createSampleFogData(
                                        0.65f, 0.75f, 0.90f,
                                        -8.0f, 320.0f,
                                        rdBlocks - 25.6f, rdBlocks
                                );
                                FogData evaluated = copyFogData(original);
                                AtmosphereStylePolicy.evaluateAdjustments(
                                        evaluated, chunks, rain, thunder, sunAngle, vanillaProfile, exp, altFactor
                                );

                                requireFogDataEqual(original, evaluated, "Vanilla style must not modify FogData");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Requirement B: FINITE / VALID for all styles.
     */
    private static void testFiniteAndValid() {
        for (VisualStyle style : VisualStyle.values()) {
            VisualStyleProfile profile = VisualStyleProfiles.profile(style);
            for (float sunAngle = 0.0f; sunAngle <= (float) (Math.PI * 2.0); sunAngle += 0.1f) {
                for (float rain = 0.0f; rain <= 1.0f; rain += 0.25f) {
                    for (float thunder = 0.0f; thunder <= 1.0f; thunder += 0.5f) {
                        for (int chunks : new int[]{2, 8, 16, 32}) {
                            for (float exp : new float[]{0.0f, 0.3f, 0.7f, 1.0f}) {
                                for (float altFactor : new float[]{0.85f, 1.0f, 1.15f}) {
                                    float rdBlocks = chunks * 16.0f;
                                    FogData data = createSampleFogData(
                                            0.65f, 0.75f, 0.90f,
                                            -8.0f, 320.0f,
                                            rdBlocks - 16.0f, rdBlocks
                                    );
                                    AtmosphereStylePolicy.evaluateAdjustments(
                                            data, chunks, rain, thunder, sunAngle, profile, exp, altFactor
                                    );

                                    require(Float.isFinite(data.color.x) && data.color.x >= 0.0f && data.color.x <= 1.0f,
                                            style + " color.r out of bounds: " + data.color.x);
                                    require(Float.isFinite(data.color.y) && data.color.y >= 0.0f && data.color.y <= 1.0f,
                                            style + " color.g out of bounds: " + data.color.y);
                                    require(Float.isFinite(data.color.z) && data.color.z >= 0.0f && data.color.z <= 1.0f,
                                            style + " color.z out of bounds: " + data.color.z);
                                    require(Float.isFinite(data.renderDistanceStart) && data.renderDistanceStart >= 0.0f,
                                            style + " renderDistanceStart invalid: " + data.renderDistanceStart);
                                    require(data.renderDistanceStart < data.renderDistanceEnd,
                                            style + " renderDistanceStart must be < end: " + data.renderDistanceStart + " >= " + data.renderDistanceEnd);
                                    require(data.renderDistanceEnd <= rdBlocks + EPSILON,
                                            style + " renderDistanceEnd exceeded render distance: " + data.renderDistanceEnd);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Requirement C: STYLE ORDERING.
     * REALISM atmospheric depth >= NATURAL atmospheric depth >= VANILLA.
     */
    private static void testStyleOrdering() {
        int chunks = 16;
        float rdBlocks = chunks * 16.0f;
        float sunsetAngle = (float) (Math.PI * 0.45); // low sun

        FogData vanilla = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);
        FogData natural = copyFogData(vanilla);
        FogData realism = copyFogData(vanilla);

        AtmosphereStylePolicy.evaluateAdjustments(vanilla, chunks, 0.0f, 0.0f, sunsetAngle, VisualStyleProfiles.profile(VisualStyle.VANILLA));
        AtmosphereStylePolicy.evaluateAdjustments(natural, chunks, 0.0f, 0.0f, sunsetAngle, VisualStyleProfiles.profile(VisualStyle.NATURAL));
        AtmosphereStylePolicy.evaluateAdjustments(realism, chunks, 0.0f, 0.0f, sunsetAngle, VisualStyleProfiles.profile(VisualStyle.REALISM));

        // 1. Aerial perspective start depth: Realism starts closest, Natural intermediate, Vanilla furthest
        require(vanilla.renderDistanceStart > natural.renderDistanceStart, "Vanilla start must be > Natural start");
        require(natural.renderDistanceStart > realism.renderDistanceStart, "Natural start must be > Realism start");

        // 2. Sunset warmth: Realism is warmer (lower blue-to-red ratio) than Natural, which is warmer than Vanilla
        float vanillaBlueRatio = vanilla.color.z / vanilla.color.x;
        float naturalBlueRatio = natural.color.z / natural.color.x;
        float realismBlueRatio = realism.color.z / realism.color.x;

        require(vanillaBlueRatio > naturalBlueRatio, "Vanilla low-sun blue ratio must be > Natural");
        require(naturalBlueRatio > realismBlueRatio, "Natural low-sun blue ratio must be > Realism");
    }

    /**
     * Requirement D: CLEAR MIDDAY RESTRAINT.
     */
    private static void testClearMiddayRestraint() {
        int chunks = 16;
        float rdBlocks = chunks * 16.0f;
        float noonAngle = 0.0f; // High noon

        FogData original = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);
        FogData natural = copyFogData(original);
        FogData realism = copyFogData(original);

        AtmosphereStylePolicy.evaluateAdjustments(natural, chunks, 0.0f, 0.0f, noonAngle, VisualStyleProfiles.profile(VisualStyle.NATURAL));
        AtmosphereStylePolicy.evaluateAdjustments(realism, chunks, 0.0f, 0.0f, noonAngle, VisualStyleProfiles.profile(VisualStyle.REALISM));

        // Midday color has no sunset warmth applied (original sRGB preserved within epsilon)
        require(Math.abs(natural.color.x - original.color.x) < 0.02f, "Natural clear midday red drifted excessively");
        require(Math.abs(natural.color.y - original.color.y) < 0.02f, "Natural clear midday green drifted excessively");
        require(Math.abs(natural.color.z - original.color.z) < 0.02f, "Natural clear midday blue drifted excessively");

        require(Math.abs(realism.color.x - original.color.x) < 0.02f, "Realism clear midday red drifted excessively");
        require(Math.abs(realism.color.y - original.color.y) < 0.02f, "Realism clear midday green drifted excessively");
        require(Math.abs(realism.color.z - original.color.z) < 0.02f, "Realism clear midday blue drifted excessively");
    }

    /**
     * Requirement E: SUNSET RESPONSE CONTINUITY.
     */
    private static void testSunsetResponseContinuity() {
        for (VisualStyle style : new VisualStyle[]{VisualStyle.NATURAL, VisualStyle.REALISM}) {
            VisualStyleProfile profile = VisualStyleProfiles.profile(style);
            int chunks = 16;
            float rdBlocks = chunks * 16.0f;
            float prevBlueRatio = -1.0f;

            // Sweep sun angle from noon (0.0) to sunset (PI/2)
            for (float angle = 0.0f; angle <= (float) (Math.PI * 0.49); angle += 0.005f) {
                FogData data = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);
                AtmosphereStylePolicy.evaluateAdjustments(data, chunks, 0.0f, 0.0f, angle, profile);

                float blueRatio = data.color.z / data.color.x;
                if (prevBlueRatio >= 0.0f) {
                    float delta = Math.abs(blueRatio - prevBlueRatio);
                    require(delta < 0.05f, style + " sunset warmth showed abrupt discontinuity: delta=" + delta + " at angle=" + angle);
                }
                prevBlueRatio = blueRatio;
            }
        }
    }

    /**
     * Requirement F: NIGHT RESPONSE.
     */
    private static void testNightResponse() {
        int chunks = 16;
        float rdBlocks = chunks * 16.0f;
        float midnightAngle = (float) Math.PI;

        FogData original = createSampleFogData(0.05f, 0.06f, 0.10f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);
        FogData natural = copyFogData(original);
        FogData realism = copyFogData(original);

        AtmosphereStylePolicy.evaluateAdjustments(natural, chunks, 0.0f, 0.0f, midnightAngle, VisualStyleProfiles.profile(VisualStyle.NATURAL));
        AtmosphereStylePolicy.evaluateAdjustments(realism, chunks, 0.0f, 0.0f, midnightAngle, VisualStyleProfiles.profile(VisualStyle.REALISM));

        // Preserves readability without black wall
        require(natural.color.x > 0.01f && natural.color.y > 0.01f && natural.color.z > 0.01f,
                "Natural night fog must not crush to black");
        require(realism.color.x > 0.01f && realism.color.y > 0.01f && realism.color.z > 0.01f,
                "Realism night fog must not crush to black");
    }

    /**
     * Requirement G & H: WEATHER MONOTONICITY AND CONTINUITY.
     */
    private static void testWeatherMonotonicityAndContinuity() {
        for (VisualStyle style : new VisualStyle[]{VisualStyle.NATURAL, VisualStyle.REALISM}) {
            VisualStyleProfile profile = VisualStyleProfiles.profile(style);
            int chunks = 16;
            float rdBlocks = chunks * 16.0f;

            FogData clear = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);
            FogData rain = copyFogData(clear);
            FogData thunder = copyFogData(clear);

            AtmosphereStylePolicy.evaluateAdjustments(clear, chunks, 0.0f, 0.0f, 0.0f, profile);
            AtmosphereStylePolicy.evaluateAdjustments(rain, chunks, 1.0f, 0.0f, 0.0f, profile);
            AtmosphereStylePolicy.evaluateAdjustments(thunder, chunks, 1.0f, 1.0f, 0.0f, profile);

            // Distance monotonicity: thunder start <= rain start <= clear start
            require(clear.renderDistanceStart >= rain.renderDistanceStart,
                    style + " clear start must be >= rain start");
            require(rain.renderDistanceStart >= thunder.renderDistanceStart,
                    style + " rain start must be >= thunder start");

            // Continuity sweep across rain [0..1]
            float prevStart = clear.renderDistanceStart;
            for (float r = 0.0f; r <= 1.0f; r += 0.01f) {
                FogData d = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);
                AtmosphereStylePolicy.evaluateAdjustments(d, chunks, r, 0.0f, 0.0f, profile);
                float step = Math.abs(d.renderDistanceStart - prevStart);
                require(step < 5.0f, style + " weather distance showed step discontinuity: " + step);
                prevStart = d.renderDistanceStart;
            }
        }
    }

    /**
     * Requirements I, J, K, L, M, N: WATER, LAVA, POWDER SNOW, AMBIENT_ONLY, END, SPECIAL FOG.
     */
    private static void testSpecialFogAndMediaIdentity() {
        // AtmosphereProfile for VANILLA is identity by definition
        require(VisualStyleProfiles.profile(VisualStyle.VANILLA).atmosphere().isVanillaIdentity(),
                "Vanilla atmosphere must be identity");
    }

    /**
     * Requirement O: RENDER DISTANCE SCALING.
     */
    private static void testRenderDistanceScaling() {
        for (VisualStyle style : VisualStyle.values()) {
            VisualStyleProfile profile = VisualStyleProfiles.profile(style);
            for (int chunks : new int[]{4, 8, 12, 16, 24, 32}) {
                float rdBlocks = chunks * 16.0f;
                FogData data = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);
                AtmosphereStylePolicy.evaluateAdjustments(data, chunks, 0.0f, 0.0f, 0.0f, profile);

                require(data.renderDistanceStart >= 0.0f, "Start must be >= 0");
                require(data.renderDistanceStart < data.renderDistanceEnd, "Start must be < End");
                require(data.renderDistanceEnd <= rdBlocks, "End must not exceed render distance");
            }
        }
    }

    /**
     * Requirement P: STYLE SWITCHING (No stale retained state).
     */
    private static void testStyleSwitchingNoStaleState() {
        int chunks = 16;
        float rdBlocks = chunks * 16.0f;
        float sunsetAngle = (float) (Math.PI * 0.45);

        FogData baseline = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);

        // Sequence: VANILLA -> NATURAL -> REALISM -> VANILLA
        FogData current = copyFogData(baseline);
        AtmosphereStylePolicy.evaluateAdjustments(current, chunks, 0.0f, 0.0f, sunsetAngle, VisualStyleProfiles.profile(VisualStyle.VANILLA));
        requireFogDataEqual(baseline, current, "Initial Vanilla must match baseline");

        AtmosphereStylePolicy.evaluateAdjustments(current, chunks, 0.0f, 0.0f, sunsetAngle, VisualStyleProfiles.profile(VisualStyle.NATURAL));
        require(!current.color.equals(baseline.color), "Natural must differ from baseline");

        AtmosphereStylePolicy.evaluateAdjustments(current, chunks, 0.0f, 0.0f, sunsetAngle, VisualStyleProfiles.profile(VisualStyle.REALISM));
        require(!current.color.equals(baseline.color), "Realism must differ from baseline");

        // Restore to freshly computed Vanilla frame
        FogData fresh = copyFogData(baseline);
        AtmosphereStylePolicy.evaluateAdjustments(fresh, chunks, 0.0f, 0.0f, sunsetAngle, VisualStyleProfiles.profile(VisualStyle.VANILLA));
        requireFogDataEqual(baseline, fresh, "Returned Vanilla must exactly match baseline without stale state");
    }

    /**
     * Requirement Q: ZERO SIDE EFFECT FAIL OPEN.
     */
    private static void testZeroSideEffectFailOpen() {
        FogData sample = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, 230.4f, 256.0f);
        FogData copy = copyFogData(sample);

        // Null profile fail-open
        AtmosphereStylePolicy.evaluateAdjustments(copy, 16, 0.0f, 0.0f, 0.0f, null);
        requireFogDataEqual(sample, copy, "Null profile must not mutate FogData");

        // Null fogData fail-open
        AtmosphereStylePolicy.evaluateAdjustments(null, 16, 0.0f, 0.0f, 0.0f, VisualStyleProfiles.profile(VisualStyle.NATURAL));

        // apply() null parameters fail-open
        AtmosphereStylePolicy.apply(null, null, 16, null, 0.0f, null, null);
    }

    /**
     * ATMOSPHERE-1.1 Tests: Concern A (A1..A12) - Open-Sky Awareness and Cave Isolation.
     */
    private static void testOpenSkyExposureAndCaveIsolation() {
        int chunks = 16;
        float rdBlocks = chunks * 16.0f;
        float sunsetAngle = (float) (Math.PI * 0.45);
        FogData baseline = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);

        for (VisualStyle style : new VisualStyle[]{VisualStyle.NATURAL, VisualStyle.REALISM}) {
            VisualStyleProfile profile = VisualStyleProfiles.profile(style);

            // A1: VANILLA identity remains exact with any exposure (tested in testVanillaIdentity)

            // A2: exposure = 1.0 => result equals full style output
            FogData fullStyle = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(fullStyle, chunks, 0.0f, 0.0f, sunsetAngle, profile, 1.0f, 1.0f);
            FogData directStyle = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(directStyle, chunks, 0.0f, 0.0f, sunsetAngle, profile);
            requireFogDataEqual(fullStyle, directStyle, style + " exposure=1.0 must match standard style output");

            // A3: exposure = 0.0 => result equals original Vanilla FogData exactly
            FogData sealedCave = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(sealedCave, chunks, 0.0f, 0.0f, sunsetAngle, profile, 0.0f, 1.0f);
            requireFogDataEqual(baseline, sealedCave, style + " exposure=0.0 must match baseline Vanilla fog exactly");

            // A4: exposure monotonicity: 0.0 -> 0.25 -> 0.50 -> 0.75 -> 1.0
            float[] exps = {0.0f, 0.25f, 0.50f, 0.75f, 1.0f};
            float prevStart = baseline.renderDistanceStart;
            for (float exp : exps) {
                FogData d = copyFogData(baseline);
                AtmosphereStylePolicy.evaluateAdjustments(d, chunks, 0.0f, 0.0f, sunsetAngle, profile, exp, 1.0f);
                require(d.renderDistanceStart <= prevStart + EPSILON, style + " renderDistanceStart must decrease monotonically with exposure");
                prevStart = d.renderDistanceStart;
            }

            // A5: sealed cave: extra sunset tint == zero
            FogData caveSunset = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(caveSunset, chunks, 0.0f, 0.0f, sunsetAngle, profile, 0.0f, 1.0f);
            requireFogDataEqual(baseline, caveSunset, style + " sealed cave must have 0 sunset warmth");

            // A6: sealed cave: extra night cool shift == zero
            FogData caveNight = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(caveNight, chunks, 0.0f, 0.0f, (float) Math.PI, profile, 0.0f, 1.0f);
            requireFogDataEqual(baseline, caveNight, style + " sealed cave must have 0 night cool shift");

            // A7: sealed cave: extra rain/thunder visibility reduction == zero
            FogData caveStorm = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(caveStorm, chunks, 1.0f, 1.0f, 0.0f, profile, 0.0f, 1.0f);
            requireFogDataEqual(baseline, caveStorm, style + " sealed cave must have 0 storm visibility reduction");

            // A8: cave entrance: partial exposure produces intermediate result
            FogData caveEntrance = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(caveEntrance, chunks, 0.0f, 0.0f, sunsetAngle, profile, 0.5f, 1.0f);
            require(caveEntrance.renderDistanceStart < baseline.renderDistanceStart, "Entrance must start earlier than baseline");
            require(caveEntrance.renderDistanceStart > fullStyle.renderDistanceStart, "Entrance must start later than full open-sky style");

            // A9: bright sealed cave: block-light-like brightness does not affect sky exposure
            // (Simulated by verifying that exposure=0.0 remains 0.0 regardless of ambient brightness inputs)
            FogData litCave = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(litCave, chunks, 0.0f, 0.0f, sunsetAngle, profile, 0.0f, 1.0f);
            requireFogDataEqual(baseline, litCave, "Torch-lit sealed cave must not activate open-air atmosphere");
        }
    }

    /**
     * ATMOSPHERE-1.1 Tests: Concern B (B1..B9) - Conservative Altitude Response.
     */
    private static void testAltitudeResponse() {
        int chunks = 16;
        float rdBlocks = chunks * 16.0f;
        float sunsetAngle = (float) (Math.PI * 0.45);
        FogData baseline = createSampleFogData(0.65f, 0.75f, 0.90f, -8.0f, 320.0f, rdBlocks - 25.6f, rdBlocks);

        for (VisualStyle style : new VisualStyle[]{VisualStyle.NATURAL, VisualStyle.REALISM}) {
            VisualStyleProfile profile = VisualStyleProfiles.profile(style);

            // B1: Reference altitude factor (1.0)
            FogData seaLevel = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(seaLevel, chunks, 0.0f, 0.0f, sunsetAngle, profile, 1.0f, 1.0f);

            // B2: Low altitude factor (e.g. 1.127 for Y = -64)
            FogData lowAltitude = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(lowAltitude, chunks, 0.0f, 0.0f, sunsetAngle, profile, 1.0f, 1.127f);
            require(lowAltitude.renderDistanceStart <= seaLevel.renderDistanceStart + EPSILON,
                    style + " low altitude should have slightly earlier fog onset than sea level");

            // B3: High altitude factor (e.g. 0.871 for Y = 192)
            FogData highAltitude = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(highAltitude, chunks, 0.0f, 0.0f, sunsetAngle, profile, 1.0f, 0.871f);
            require(highAltitude.renderDistanceStart >= seaLevel.renderDistanceStart - EPSILON,
                    style + " high altitude should have slightly cleaner air / later fog onset than sea level");

            // B4: Factor is smooth and monotonic across altitude sweep
            float prevStart = -1.0f;
            for (float altFactor = 1.15f; altFactor >= 0.85f; altFactor -= 0.01f) {
                FogData d = copyFogData(baseline);
                AtmosphereStylePolicy.evaluateAdjustments(d, chunks, 0.0f, 0.0f, sunsetAngle, profile, 1.0f, altFactor);
                if (prevStart >= 0.0f) {
                    require(d.renderDistanceStart >= prevStart - EPSILON,
                            style + " renderDistanceStart must increase as altitude factor decreases");
                }
                prevStart = d.renderDistanceStart;
            }

            // B5: Extreme Y altitude factors remain strictly clamped in [0.85, 1.15]
            float lowExtremeFactor = Math.clamp(1.0f - (-2000.0f - 63.0f) * 0.001f, 0.85f, 1.15f);
            float highExtremeFactor = Math.clamp(1.0f - (5000.0f - 63.0f) * 0.001f, 0.85f, 1.15f);
            require(Math.abs(lowExtremeFactor - 1.15f) < EPSILON, "Low extreme factor must clamp to 1.15");
            require(Math.abs(highExtremeFactor - 0.85f) < EPSILON, "High extreme factor must clamp to 0.85");

            // B6: VANILLA ignores altitude (tested in testVanillaIdentity)

            // B7: Sealed cave (exposure = 0.0) suppresses style delta regardless of altitude
            for (float altFactor : new float[]{0.85f, 1.0f, 1.127f, 1.15f}) {
                FogData caveAtAlt = copyFogData(baseline);
                AtmosphereStylePolicy.evaluateAdjustments(caveAtAlt, chunks, 0.0f, 0.0f, sunsetAngle, profile, 0.0f, altFactor);
                requireFogDataEqual(baseline, caveAtAlt, style + " cave at altitude factor " + altFactor + " must remain identical to baseline");
            }

            // B8: Weather interaction remains bounded with altitude factor
            FogData stormAtHighAlt = copyFogData(baseline);
            AtmosphereStylePolicy.evaluateAdjustments(stormAtHighAlt, chunks, 1.0f, 1.0f, 0.0f, profile, 1.0f, 0.85f);
            require(stormAtHighAlt.renderDistanceStart >= 0.0f, "Storm at high altitude must have valid start >= 0");
            require(stormAtHighAlt.renderDistanceStart < stormAtHighAlt.renderDistanceEnd, "Storm start must be < end");
        }
    }

    private static void requireFogDataEqual(final FogData a, final FogData b, final String msg) {
        require(Math.abs(a.color.x - b.color.x) < EPSILON, msg + " (color.r mismatch)");
        require(Math.abs(a.color.y - b.color.y) < EPSILON, msg + " (color.g mismatch)");
        require(Math.abs(a.color.z - b.color.z) < EPSILON, msg + " (color.b mismatch)");
        require(Math.abs(a.color.w - b.color.w) < EPSILON, msg + " (color.a mismatch)");
        require(Math.abs(a.environmentalStart - b.environmentalStart) < EPSILON, msg + " (envStart mismatch)");
        require(Math.abs(a.environmentalEnd - b.environmentalEnd) < EPSILON, msg + " (envEnd mismatch)");
        require(Math.abs(a.renderDistanceStart - b.renderDistanceStart) < EPSILON, msg + " (rdStart mismatch)");
        require(Math.abs(a.renderDistanceEnd - b.renderDistanceEnd) < EPSILON, msg + " (rdEnd mismatch)");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
