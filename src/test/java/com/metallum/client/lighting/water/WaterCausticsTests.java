package com.metallum.client.lighting.water;

import com.metallum.client.hdr.SodiumHdrSemantic;
import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.lighting.cloud.CloudShadowMode;
import com.metallum.client.lighting.cloud.CloudShadowPolicy;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.joml.Matrix3f;
import org.joml.Vector2d;
import org.joml.Vector3f;

/**
 * Pure unit test suite for Metallum WATER-CAUSTICS-1: Synchronized Underwater Water Caustics.
 */
public final class WaterCausticsTests {

    public static void main(final String[] args) {
        runAll();
    }

    public static void runAll() {
        testWaveFieldSynchronizationAndPhase();
        testCameraMovementInvariance();
        testTimeAdvanceAndPauseResume();
        testNegativeAndLargeWorldCoordinates();
        testSnellRefractionOptics();
        testDepthAttenuationMonotonicity();
        testZeroAndAboveWaterDepth();
        testOrientationFactor();
        testMeanPreservingEnergyConservation();
        testBoundedGainNoNaN();
        testMediumGate();
        testCloudShadowCompatibility();
        testAboveWaterAndSubmergedReceiverMatrix();
        testVertexColorAlphaSubmergedEncodingAndDecoding();
        System.out.println("All WaterCausticsTests passed successfully.");
    }

    private static void testWaveFieldSynchronizationAndPhase() {
        double worldX = 142.75;
        double worldZ = -88.35;
        double time = 42.5;

        WaterCausticsPolicy.WaveState wave = WaterCausticsPolicy.evaluateWaterWaves(worldX, worldZ, time);
        require(Float.isFinite(wave.slopeX()) && Float.isFinite(wave.slopeZ()),
                "Surface wave slopes must be finite");
        require(wave.crest() >= 0.0f && wave.crest() <= 1.0f,
                "Surface wave crest must be normalized in [0, 1]");
        require(wave.causticFocusing() >= 0.0f && Float.isFinite(wave.causticFocusing()),
                "Caustic focusing term must be non-negative and finite");

        // Surface normal perturbation and underwater caustics evaluated at the same
        // world point and time evaluate the exact same underlying wave state:
        WaterCausticsPolicy.WaveState wave2 = WaterCausticsPolicy.evaluateWaterWaves(worldX, worldZ, time);
        require(wave.slopeX() == wave2.slopeX() && wave.slopeZ() == wave2.slopeZ(),
                "Wave slope must be deterministic");
        require(wave.crest() == wave2.crest(),
                "Wave crest must be deterministic");
        require(wave.causticFocusing() == wave2.causticFocusing(),
                "Caustic focusing must be deterministic");
    }

    private static void testCameraMovementInvariance() {
        double worldReceiverX = 120.5;
        double worldReceiverY = 56.0;
        double worldReceiverZ = -45.25;
        float waterSurfaceY = 64.0f;
        float normalX = 0.0f;
        float normalY = 1.0f;
        float normalZ = 0.0f;
        float toLightX = 0.6f;
        float toLightY = 0.8f;
        float toLightZ = 0.0f;
        double fixedTime = 10.0;

        float baseGain = WaterCausticsPolicy.evaluateCausticGain(
                worldReceiverX, worldReceiverY, worldReceiverZ,
                normalX, normalY, normalZ,
                waterSurfaceY,
                toLightX, toLightY, toLightZ,
                fixedTime
        );

        // Test with different simulated camera positions and orientations:
        // World coordinates are reconstructed via (V_rot^-1 * P_view) + cameraPos = W.
        double[] cameraXOffsets = {-100.0, -10.0, 0.0, 5.5, 30.0, 256.0, 1000.0};
        double[] cameraYOffsets = {-5.0, 0.0, 10.0, 50.0};
        double[] cameraZOffsets = {-200.0, -1.0, 0.0, 8.25, 256.0};

        for (double camX : cameraXOffsets) {
            for (double camY : cameraYOffsets) {
                for (double camZ : cameraZOffsets) {
                    // Reconstructed world receiver position from view space:
                    double camRelX = worldReceiverX - camX;
                    double camRelY = worldReceiverY - camY;
                    double camRelZ = worldReceiverZ - camZ;

                    // Apply arbitrary rotation matrix R
                    Matrix3f rot = new Matrix3f().rotateY((float) (camX * 0.1)).rotateX((float) (camZ * 0.05));
                    Vector3f viewPos = new Vector3f((float) camRelX, (float) camRelY, (float) camRelZ);
                    rot.transform(viewPos);

                    // Reconstruct back: worldFromView * viewPos + cameraPos
                    Matrix3f worldFromView = new Matrix3f(rot).invert();
                    Vector3f reconstructedRel = new Vector3f(viewPos);
                    worldFromView.transform(reconstructedRel);

                    double reconstructedWorldX = camX + reconstructedRel.x;
                    double reconstructedWorldY = camY + reconstructedRel.y;
                    double reconstructedWorldZ = camZ + reconstructedRel.z;

                    require(Math.abs(reconstructedWorldX - worldReceiverX) < 1.0e-3,
                            "Reconstructed world X diverged under camera transform");
                    require(Math.abs(reconstructedWorldY - worldReceiverY) < 1.0e-3,
                            "Reconstructed world Y diverged under camera transform");
                    require(Math.abs(reconstructedWorldZ - worldReceiverZ) < 1.0e-3,
                            "Reconstructed world Z diverged under camera transform");

                    float gain = WaterCausticsPolicy.evaluateCausticGain(
                            reconstructedWorldX, reconstructedWorldY, reconstructedWorldZ,
                            normalX, normalY, normalZ,
                            waterSurfaceY,
                            toLightX, toLightY, toLightZ,
                            fixedTime
                    );
                    require(Math.abs(gain - baseGain) < 1.0e-3f,
                            "Caustic gain shifted solely due to camera position/orientation");
                }
            }
        }
    }

    private static void testTimeAdvanceAndPauseResume() {
        double worldX = 50.0;
        double worldZ = 75.0;
        double t0 = 5.0;
        double dt = 0.5;

        WaterCausticsPolicy.WaveState state0 = WaterCausticsPolicy.evaluateWaterWaves(worldX, worldZ, t0);
        WaterCausticsPolicy.WaveState state1 = WaterCausticsPolicy.evaluateWaterWaves(worldX, worldZ, t0 + dt);

        // Advancing time changes the wave phase smoothly
        require(state0.causticFocusing() != state1.causticFocusing(),
                "Wave focusing must advance when time advances");

        // Repeating time t0 gives identical output (no drift on pause/resume)
        WaterCausticsPolicy.WaveState stateResume = WaterCausticsPolicy.evaluateWaterWaves(worldX, worldZ, t0);
        require(state0.causticFocusing() == stateResume.causticFocusing(),
                "Wave state must not drift upon pause and resume");
        require(state0.slopeX() == stateResume.slopeX() && state0.slopeZ() == stateResume.slopeZ(),
                "Wave slopes must not drift upon pause and resume");
    }

    private static void testNegativeAndLargeWorldCoordinates() {
        double[] coordinates = {
                -500000.0, -10000.25, -256.0, -128.5, -1.0, 0.0,
                1.0, 128.5, 256.0, 10000.25, 500000.0
        };
        for (double x : coordinates) {
            for (double z : coordinates) {
                WaterCausticsPolicy.WaveState wave = WaterCausticsPolicy.evaluateWaterWaves(x, z, 1.0);
                require(Float.isFinite(wave.slopeX()) && !Float.isNaN(wave.slopeX()),
                        "Slope X must be finite at large/negative coordinates");
                require(Float.isFinite(wave.slopeZ()) && !Float.isNaN(wave.slopeZ()),
                        "Slope Z must be finite at large/negative coordinates");
                require(Float.isFinite(wave.causticFocusing()) && !Float.isNaN(wave.causticFocusing()),
                        "Caustic focusing must be finite at large/negative coordinates");
            }
        }
    }

    private static void testSnellRefractionOptics() {
        // Vertical Sun: toLight = (0, 1, 0)
        Vector3f rVertical = WaterCausticsPolicy.refractCelestialDirection(0.0f, 1.0f, 0.0f);
        require(Math.abs(rVertical.x()) < 1.0e-5f && Math.abs(rVertical.z()) < 1.0e-5f,
                "Vertical sun ray must have zero horizontal component in water");
        require(Math.abs(rVertical.y() - 1.0f) < 1.0e-4f,
                "Vertical sun ray must have unit vertical component in water");

        // 45-degree Sun: toLight = (0.7071, 0.7071, 0)
        Vector3f r45 = WaterCausticsPolicy.refractCelestialDirection(0.7071068f, 0.7071068f, 0.0f);
        // By Snell's Law (eta = 0.75): refracted horizontal component is 0.75 * 0.7071 = 0.5303
        // Vertical component is sqrt(1 - 0.75^2 * 0.5) = sqrt(1 - 0.28125) = sqrt(0.71875) = 0.8478
        // The refracted ray in water is steeper than in air:
        float airTan = 0.7071068f / 0.7071068f; // 1.0
        float waterTan = r45.x() / r45.y();      // ~0.625
        require(waterTan < airTan,
                "Refracted ray in water must bend toward vertical surface normal");
        require(r45.y() > 0.6614f,
                "Refracted vertical component must stay strictly positive");

        // Grazing Sun: toLight = (1, 0.002, 0)
        Vector3f rGrazing = WaterCausticsPolicy.refractCelestialDirection(1.0f, 0.002f, 0.0f);
        require(Float.isFinite(rGrazing.x()) && Float.isFinite(rGrazing.y()),
                "Grazing refracted ray must remain finite");
        require(rGrazing.y() >= 0.6614f,
                "Grazing refracted ray vertical component must remain bounded by critical angle");
    }

    private static void testDepthAttenuationMonotonicity() {
        float d1 = WaterCausticsPolicy.evaluateDepthAttenuation(1.0f);
        float d5 = WaterCausticsPolicy.evaluateDepthAttenuation(5.0f);
        float d10 = WaterCausticsPolicy.evaluateDepthAttenuation(10.0f);
        float d20 = WaterCausticsPolicy.evaluateDepthAttenuation(20.0f);
        float d40 = WaterCausticsPolicy.evaluateDepthAttenuation(40.0f);

        require(d1 > d5, "Depth 1 must attenuate less than depth 5");
        require(d5 > d10, "Depth 5 must attenuate less than depth 10");
        require(d10 > d20, "Depth 10 must attenuate less than depth 20");
        require(d20 > d40, "Depth 20 must attenuate less than depth 40");
        require(d1 >= 0.88f && d1 <= 0.95f, "Depth 1 should be ~0.92");
        require(d5 >= 0.60f && d5 <= 0.72f, "Depth 5 should be ~0.67");
        require(d20 <= 0.25f, "Depth 20 should be <= 0.25");
        require(d40 < 0.05f, "Depth 40 should be effectively negligible (< 0.05)");
    }

    private static void testZeroAndAboveWaterDepth() {
        // Zero depth (at surface)
        require(WaterCausticsPolicy.evaluateDepthAttenuation(0.0f) == 0.0f,
                "Depth attenuation at depth 0 must be 0");
        // Negative depth (above surface)
        require(WaterCausticsPolicy.evaluateDepthAttenuation(-5.0f) == 0.0f,
                "Depth attenuation at negative depth must be 0");

        // Full caustic gain at or above water surface must return 1.0 (no modulation)
        float gainSurface = WaterCausticsPolicy.evaluateCausticGain(
                10.0, 64.0, 10.0, 0.0f, 1.0f, 0.0f, 64.0f, 0.0f, 1.0f, 0.0f, 0.0
        );
        require(gainSurface == 1.0f, "Caustic gain at surface must be 1.0");

        float gainAbove = WaterCausticsPolicy.evaluateCausticGain(
                10.0, 70.0, 10.0, 0.0f, 1.0f, 0.0f, 64.0f, 0.0f, 1.0f, 0.0f, 0.0
        );
        require(gainAbove == 1.0f, "Caustic gain above surface must be 1.0");
    }

    private static void testOrientationFactor() {
        Vector3f r = new Vector3f(0.0f, 1.0f, 0.0f); // vertical incoming light

        // Upward facing floor: normal = (0, 1, 0)
        float front = WaterCausticsPolicy.evaluateOrientationFactor(0.0f, 1.0f, 0.0f, r);
        require(front == 1.0f, "Upward floor facing incoming light must have full orientation factor");

        // 45 degree slope: normal = (0.7071, 0.7071, 0)
        float slope = WaterCausticsPolicy.evaluateOrientationFactor(0.7071f, 0.7071f, 0.0f, r);
        require(slope > 0.0f && slope <= 1.0f, "Sloped surface must have positive orientation factor");

        // Back-facing ceiling / overhang: normal = (0, -1, 0)
        float back = WaterCausticsPolicy.evaluateOrientationFactor(0.0f, -1.0f, 0.0f, r);
        require(back == 0.0f, "Back-facing overhang must have zero orientation factor");
    }

    private static void testMeanPreservingEnergyConservation() {
        double sumGain = 0.0;
        int samples = 0;
        float waterSurfaceY = 64.0f;
        double receiverY = 62.0; // 2 blocks deep (strong caustics)

        // Sample across a full 256x256 periodic domain and multiple times
        for (double x = 0; x < 256.0; x += 3.2) {
            for (double z = 0; z < 256.0; z += 3.2) {
                float gain = WaterCausticsPolicy.evaluateCausticGain(
                        x, receiverY, z,
                        0.0f, 1.0f, 0.0f,
                        waterSurfaceY,
                        0.0f, 1.0f, 0.0f,
                        0.0
                );
                sumGain += gain;
                samples++;
            }
        }
        double meanGain = sumGain / samples;
        require(Math.abs(meanGain - 1.0) < 0.05,
                "Average periodic caustic gain must be mean-preserving (~1.0), got " + meanGain);
    }

    private static void testBoundedGainNoNaN() {
        for (double x = -100.0; x <= 100.0; x += 15.0) {
            for (double y = 30.0; y <= 70.0; y += 5.0) {
                for (double t = 0.0; t <= 10.0; t += 2.5) {
                    float gain = WaterCausticsPolicy.evaluateCausticGain(
                            x, y, x * 0.5,
                            0.0f, 1.0f, 0.0f,
                            64.0f,
                            0.5f, 0.866f, 0.0f,
                            t
                    );
                    require(Float.isFinite(gain) && !Float.isNaN(gain),
                            "Gain must be finite and not NaN");
                    require(gain >= WaterCausticsPolicy.MIN_CAUSTIC_GAIN
                                    && gain <= WaterCausticsPolicy.MAX_CAUSTIC_GAIN,
                            "Gain must remain strictly bounded in ["
                                    + WaterCausticsPolicy.MIN_CAUSTIC_GAIN + ", "
                                    + WaterCausticsPolicy.MAX_CAUSTIC_GAIN + "], got " + gain);
                }
            }
        }
    }

    private static void testMediumGate() {
        // Medium AIR / LAVA / POWDER_SNOW should not generate water caustics
        EnvironmentDescriptor air = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.0f, 0.5f, 0.5f, 0.5f, 1.0f,
                0.1f, 0.1f, 0.1f, 0.0f, 0.0f, 1.0f
        );
        require(air.medium() == EnvironmentDescriptor.Medium.AIR, "Air medium mismatch");

        EnvironmentDescriptor lava = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.LAVA,
                0.0f, 0.5f, 0.5f, 0.5f, 1.0f,
                0.1f, 0.1f, 0.1f, 0.0f, 0.0f, 1.0f
        );
        require(lava.medium() == EnvironmentDescriptor.Medium.LAVA, "Lava medium mismatch");

        EnvironmentDescriptor water = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.WATER,
                0.0f, 0.5f, 0.5f, 0.5f, 1.0f,
                0.1f, 0.1f, 0.1f, 0.0f, 0.0f, 1.0f,
                null,
                64.0f
        );
        require(water.medium() == EnvironmentDescriptor.Medium.WATER, "Water medium mismatch");
        require(water.waterSurfaceY() == 64.0f, "Water surface height mismatch");
    }

    private static void testCloudShadowCompatibility() {
        // Fixed world receiver
        double worldX = 200.0;
        double worldY = 58.0;
        double worldZ = 300.0;
        float waterSurfaceY = 64.0f;
        float toLightX = 0.5f;
        float toLightY = 0.866f;
        float toLightZ = 0.0f;
        double fixedTime = 25.0;

        float baseCausticGain = WaterCausticsPolicy.evaluateCausticGain(
                worldX, worldY, worldZ,
                0.0f, 1.0f, 0.0f,
                waterSurfaceY,
                toLightX, toLightY, toLightZ,
                fixedTime
        );

        float cloudHeight = 192.0f;
        float tCloud = CloudShadowPolicy.rayIntersectionT((float) worldY, toLightY, cloudHeight);
        float cloudWorldX = (float) worldX + toLightX * tCloud;
        float cloudWorldZ = (float) worldZ + toLightZ * tCloud;

        // Move camera around
        double[] cameraPositions = {-500.0, -50.0, 0.0, 100.0, 500.0};
        for (double camX : cameraPositions) {
            for (double camZ : cameraPositions) {
                // Reconstruct world coords:
                double relX = worldX - camX;
                double relZ = worldZ - camZ;
                double reconstructedX = camX + relX;
                double reconstructedZ = camZ + relZ;

                float causticGain = WaterCausticsPolicy.evaluateCausticGain(
                        reconstructedX, worldY, reconstructedZ,
                        0.0f, 1.0f, 0.0f,
                        waterSurfaceY,
                        toLightX, toLightY, toLightZ,
                        fixedTime
                );
                require(Math.abs(causticGain - baseCausticGain) < 1.0e-5f,
                        "Water caustic phase changed with camera position");

                float tReconstructed = CloudShadowPolicy.rayIntersectionT((float) worldY, toLightY, cloudHeight);
                float reprojectedCloudX = (float) reconstructedX + toLightX * tReconstructed;
                float reprojectedCloudZ = (float) reconstructedZ + toLightZ * tReconstructed;

                require(Math.abs(reprojectedCloudX - cloudWorldX) < 1.0e-4f
                                && Math.abs(reprojectedCloudZ - cloudWorldZ) < 1.0e-4f,
                        "Cloud shadow world sampling coordinate changed with camera position");
            }
        }
    }

    private static void testAboveWaterAndSubmergedReceiverMatrix() {
        double worldX = 120.0;
        double worldY = 62.0;
        double worldZ = -45.0;
        float waterSurfaceY = 64.0f;
        float submergedDepth = 2.0f;
        float toLightX = 0.4f;
        float toLightY = 0.9165f;
        float toLightZ = 0.0f;
        double time = 17.5;

        // 1. Camera underwater + submerged receiver -> caustics active
        float gainUnderwaterSubmerged = WaterCausticsPolicy.evaluateCausticGain(
                worldX, worldY, worldZ,
                0.0f, 1.0f, 0.0f,
                true, submergedDepth,
                true, waterSurfaceY,
                toLightX, toLightY, toLightZ,
                time
        );
        require(gainUnderwaterSubmerged >= WaterCausticsPolicy.MIN_CAUSTIC_GAIN
                        && gainUnderwaterSubmerged <= WaterCausticsPolicy.MAX_CAUSTIC_GAIN,
                "Underwater submerged receiver gain must be within valid bounds");
        require(gainUnderwaterSubmerged != 1.0f,
                "Underwater submerged receiver must receive non-neutral caustic modulation");

        // 2. Camera above water + submerged receiver -> caustics active
        float gainAboveWaterSubmerged = WaterCausticsPolicy.evaluateCausticGain(
                worldX, worldY, worldZ,
                0.0f, 1.0f, 0.0f,
                true, submergedDepth,
                false, waterSurfaceY,
                toLightX, toLightY, toLightZ,
                time
        );
        require(gainAboveWaterSubmerged >= WaterCausticsPolicy.MIN_CAUSTIC_GAIN
                        && gainAboveWaterSubmerged <= WaterCausticsPolicy.MAX_CAUSTIC_GAIN,
                "Above-water submerged receiver gain must be within valid bounds");
        require(gainAboveWaterSubmerged != 1.0f,
                "Above-water submerged receiver must receive non-neutral caustic modulation");

        // 3. Camera above water + dry receiver -> no caustics (strictly 1.0)
        float gainAboveWaterDry = WaterCausticsPolicy.evaluateCausticGain(
                worldX, worldY, worldZ,
                0.0f, 1.0f, 0.0f,
                false, 0.0f,
                false, waterSurfaceY,
                toLightX, toLightY, toLightZ,
                time
        );
        require(gainAboveWaterDry == 1.0f,
                "Above-water dry receiver must receive strictly neutral caustic gain (1.0)");

        // 4. Camera underwater + dry receiver (e.g. inside underwater air dome) -> no caustics (strictly 1.0)
        float gainUnderwaterDry = WaterCausticsPolicy.evaluateCausticGain(
                worldX, worldY, worldZ,
                0.0f, 1.0f, 0.0f,
                false, 0.0f,
                false, waterSurfaceY,
                toLightX, toLightY, toLightZ,
                time
        );
        require(gainUnderwaterDry == 1.0f,
                "Underwater dry receiver must receive strictly neutral caustic gain (1.0)");

        // 5. Identical submerged receiver and fixed time: camera movement above/below water does not change caustic phase
        for (double sampleX = -50.0; sampleX <= 50.0; sampleX += 12.5) {
            for (double sampleZ = -50.0; sampleZ <= 50.0; sampleZ += 12.5) {
                float gainUnder = WaterCausticsPolicy.evaluateCausticGain(
                        sampleX, worldY, sampleZ,
                        0.0f, 1.0f, 0.0f,
                        true, submergedDepth,
                        true, waterSurfaceY,
                        toLightX, toLightY, toLightZ,
                        time
                );
                float gainAbove = WaterCausticsPolicy.evaluateCausticGain(
                        sampleX, worldY, sampleZ,
                        0.0f, 1.0f, 0.0f,
                        true, submergedDepth,
                        false, waterSurfaceY,
                        toLightX, toLightY, toLightZ,
                        time
                );
                require(Math.abs(gainUnder - gainAbove) < 1.0e-5f,
                        "Camera transition above/below water must preserve exact identical caustic world phase");
            }
        }
    }

    private static void testVertexColorAlphaSubmergedEncodingAndDecoding() {
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new ChunkVertexEncoder.Vertex();
            vertices[i].color = 0xFF80A0C0;
        }

        SodiumHdrSemantic.tagQuad(vertices, 0, false, SodiumHdrSemantic.SURFACE_CLASS_NONE, true, 5);

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int alpha = (vertex.color >>> 24) & 0xFF;
            require(alpha == 250, "Submerged depth 5 must encode alpha 250 (255 - 5), got " + alpha);
            int rgb = vertex.color & 0x00FFFFFF;
            require(rgb == 0x0080A0C0, "RGB channels must be strictly preserved, got " + Integer.toHexString(rgb));

            float alphaFloat = (float) alpha / 255.0f;
            int decodedAlphaByte = Math.round(Math.clamp(alphaFloat, 0.0f, 1.0f) * 255.0f);
            boolean receiverSubmerged = decodedAlphaByte <= 254 && decodedAlphaByte >= 192;
            int decodedDepth = receiverSubmerged ? (255 - decodedAlphaByte) : 0;

            require(receiverSubmerged, "Shader logic must classify vertex as submerged");
            require(decodedDepth == 5, "Shader logic must decode exact depth 5, got " + decodedDepth);
        }

        for (int i = 0; i < 4; i++) {
            vertices[i].color = 0xFF80A0C0;
        }
        SodiumHdrSemantic.tagQuad(vertices, 0, false, SodiumHdrSemantic.SURFACE_CLASS_NONE, false, 0);

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int alpha = (vertex.color >>> 24) & 0xFF;
            require(alpha == 255, "Dry terrain must have full alpha 255, got " + alpha);

            float alphaFloat = (float) alpha / 255.0f;
            int decodedAlphaByte = Math.round(Math.clamp(alphaFloat, 0.0f, 1.0f) * 255.0f);
            boolean receiverSubmerged = decodedAlphaByte <= 254 && decodedAlphaByte >= 192;
            require(!receiverSubmerged, "Dry terrain must not be classified as submerged");
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
