package com.metallum.client.lighting.cloud;

import com.metallum.client.lighting.EnvironmentDescriptor;
import net.minecraft.client.CloudStatus;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Pure unit test suite for Metallum CLOUD-1: Vanilla-synchronized Cloud Shadows.
 */
public final class CloudShadowTests {

    public static void main(final String[] args) {
        runAll();
    }

    public static void runAll() {
        testModeResolution();
        testProjectionMathAndReceiverAboveClouds();
        testNearHorizonStability();
        testPeriodicWrappingAndNegativeCoordinates();
        testOpacityScalingAndMonotonicity();
        testEmptyAndOccupiedCellTransmittance();
        testVolumetricThicknessPathLength();
        testAnimationMovementOffset();
        testResourceGenerationAndFailOpen();
        System.out.println("All CloudShadowTests passed successfully.");
    }

    private static void testModeResolution() {
        // Minecraft OFF -> NONE
        require(CloudShadowMode.fromMinecraft(CloudStatus.OFF, 192.0f, 0.8f, true) == CloudShadowMode.NONE,
                "CloudStatus.OFF did not resolve to NONE");

        // Null status -> NONE
        require(CloudShadowMode.fromMinecraft(null, 192.0f, 0.8f, true) == CloudShadowMode.NONE,
                "Null cloud status did not resolve to NONE");

        // FAST -> FLAT
        require(CloudShadowMode.fromMinecraft(CloudStatus.FAST, 192.0f, 0.8f, true) == CloudShadowMode.FLAT,
                "CloudStatus.FAST did not resolve to FLAT");

        // FANCY -> VOLUMETRIC
        require(CloudShadowMode.fromMinecraft(CloudStatus.FANCY, 192.0f, 0.8f, true) == CloudShadowMode.VOLUMETRIC,
                "CloudStatus.FANCY did not resolve to VOLUMETRIC");

        // Zero opacity -> NONE
        require(CloudShadowMode.fromMinecraft(CloudStatus.FANCY, 192.0f, 0.0f, true) == CloudShadowMode.NONE,
                "Zero cloud opacity did not resolve to NONE");
        require(CloudShadowMode.fromMinecraft(CloudStatus.FANCY, 192.0f, 0.003f, true) == CloudShadowMode.NONE,
                "Sub-threshold cloud opacity did not resolve to NONE");

        // NaN or invalid cloud height (e.g. Nether / End) -> NONE
        require(CloudShadowMode.fromMinecraft(CloudStatus.FANCY, Float.NaN, 0.8f, true) == CloudShadowMode.NONE,
                "Float.NaN cloud height did not resolve to NONE");
        require(CloudShadowMode.fromMinecraft(CloudStatus.FANCY, 0.0f, 0.8f, true) == CloudShadowMode.NONE,
                "Zero cloud height did not resolve to NONE");
        require(CloudShadowMode.fromMinecraft(CloudStatus.FANCY, -64.0f, 0.8f, true) == CloudShadowMode.NONE,
                "Negative cloud height did not resolve to NONE");

        // Source unavailable -> NONE
        require(CloudShadowMode.fromMinecraft(CloudStatus.FANCY, 192.0f, 0.8f, false) == CloudShadowMode.NONE,
                "Unavailable source did not resolve to NONE");

        // Mode IDs
        require(CloudShadowMode.NONE.id() == 0, "NONE id must be 0");
        require(CloudShadowMode.FLAT.id() == 1, "FLAT id must be 1");
        require(CloudShadowMode.VOLUMETRIC.id() == 2, "VOLUMETRIC id must be 2");
        require(CloudShadowMode.fromId(0) == CloudShadowMode.NONE, "fromId(0) mismatch");
        require(CloudShadowMode.fromId(1) == CloudShadowMode.FLAT, "fromId(1) mismatch");
        require(CloudShadowMode.fromId(2) == CloudShadowMode.VOLUMETRIC, "fromId(2) mismatch");
    }

    private static void testProjectionMathAndReceiverAboveClouds() {
        float cloudHeight = 192.0f;
        float cloudThickness = 4.0f;

        // Receiver below cloud (y = 64.0) with vertical sun (Ly = 1.0)
        float tVertical = CloudShadowPolicy.rayIntersectionT(64.0f, 1.0f, cloudHeight);
        require(approxEqual(tVertical, 128.0f), "Vertical ray t mismatch");

        // Receiver below cloud (y = 64.0) with 45-degree sun (Lx = 0.7071, Ly = 0.7071)
        float lightY = 0.70710678f;
        float lightX = 0.70710678f;
        float tOblique = CloudShadowPolicy.rayIntersectionT(64.0f, lightY, cloudHeight);
        require(approxEqual(tOblique, (192.0f - 64.0f) / lightY), "Oblique ray t mismatch");
        float projectedX = 100.0f + lightX * tOblique;
        require(approxEqual(projectedX, 100.0f + 128.0f), "Projected X coordinate mismatch");

        // Receiver above clouds (y = 250.0): cloud slab is [192, 196]
        require(CloudShadowPolicy.isReceiverAboveClouds(250.0f, cloudHeight, cloudThickness),
                "Receiver at y=250 should be recognized as above clouds");
        require(CloudShadowPolicy.isReceiverAboveClouds(196.0f, cloudHeight, cloudThickness),
                "Receiver at y=196 (cloud top) should be recognized as above clouds");
        require(!CloudShadowPolicy.isReceiverAboveClouds(195.0f, cloudHeight, cloudThickness),
                "Receiver at y=195 (inside cloud) should not be above clouds");
        require(!CloudShadowPolicy.isReceiverAboveClouds(64.0f, cloudHeight, cloudThickness),
                "Receiver at y=64 (ground) should not be above clouds");

        // If light is from below horizon (Ly <= 0), ray intersection is invalid
        require(CloudShadowPolicy.rayIntersectionT(64.0f, 0.0f, cloudHeight) < 0.0f,
                "Ly = 0 must return negative/invalid t");
        require(CloudShadowPolicy.rayIntersectionT(64.0f, -0.5f, cloudHeight) < 0.0f,
                "Ly < 0 must return negative/invalid t");
    }

    private static void testNearHorizonStability() {
        // Ly <= 0.04 -> stability weight 0.0
        require(approxEqual(CloudShadowPolicy.horizonStabilityWeight(0.04f), 0.0f),
                "Ly = 0.04 must have 0.0 stability weight");
        require(approxEqual(CloudShadowPolicy.horizonStabilityWeight(0.01f), 0.0f),
                "Ly = 0.01 must have 0.0 stability weight");
        require(approxEqual(CloudShadowPolicy.horizonStabilityWeight(0.0f), 0.0f),
                "Ly = 0.0 must have 0.0 stability weight");
        require(approxEqual(CloudShadowPolicy.horizonStabilityWeight(-0.5f), 0.0f),
                "Ly < 0 must have 0.0 stability weight");

        // Ly >= 0.10 -> stability weight 1.0
        require(approxEqual(CloudShadowPolicy.horizonStabilityWeight(0.10f), 1.0f),
                "Ly = 0.10 must have 1.0 stability weight");
        require(approxEqual(CloudShadowPolicy.horizonStabilityWeight(0.50f), 1.0f),
                "Ly = 0.50 must have 1.0 stability weight");
        require(approxEqual(CloudShadowPolicy.horizonStabilityWeight(1.0f), 1.0f),
                "Ly = 1.0 must have 1.0 stability weight");

        // In between: monotonically increasing and continuous
        float prev = 0.0f;
        for (float ly = 0.04f; ly <= 0.10f; ly += 0.005f) {
            float weight = CloudShadowPolicy.horizonStabilityWeight(ly);
            require(weight >= prev && weight <= 1.0f, "Stability weight must increase monotonically in transition zone");
            prev = weight;
        }
    }

    private static void testPeriodicWrappingAndNegativeCoordinates() {
        // Create 4x4 synthetic pattern with a single occupied block at (1, 1)
        boolean[][] grid = new boolean[4][4];
        grid[1][1] = true;
        CloudShadowSource source = CloudShadowSource.createSynthetic(4, 4, grid, 1L);

        require(source.width() == 4 && source.height() == 4, "Synthetic dimension mismatch");
        require(approxEqual(source.gridWidthBlocks(), 48.0f), "Grid width blocks mismatch");
        require(approxEqual(source.gridHeightBlocks(), 48.0f), "Grid height blocks mismatch");

        // Discrete wrapping: (1, 1) should match (1 + 4*k, 1 + 4*m)
        float c11 = source.baseCoverage(1, 1);
        require(c11 > 0.0f, "Cell (1, 1) must have positive coverage");
        require(approxEqual(source.baseCoverage(5, 1), c11), "X wrap (+4) mismatch");
        require(approxEqual(source.baseCoverage(1, 9), c11), "Z wrap (+8) mismatch");
        require(approxEqual(source.baseCoverage(-3, 1), c11), "Negative X wrap (-3 == 1 mod 4) mismatch");
        require(approxEqual(source.baseCoverage(1, -7), c11), "Negative Z wrap (-7 == 1 mod 4) mismatch");

        // Continuous bilinear wrapping
        float samplePositive = source.sampleBilinear(1.25f, 1.75f);
        float sampleWrapped = source.sampleBilinear(1.25f + 400.0f, 1.75f - 400.0f);
        require(approxEqual(samplePositive, sampleWrapped), "Continuous wrapped bilinear sampling mismatch");
    }

    private static void testOpacityScalingAndMonotonicity() {
        // Opacity 0 -> transmittance is 1.0
        require(approxEqual(CloudShadowPolicy.flatTransmittance(1.0f, 0.0f), 1.0f),
                "Flat transmittance with 0 opacity must be 1.0");
        require(approxEqual(CloudShadowPolicy.volumetricTransmittance(1.0f, 0.0f), 1.0f),
                "Volumetric transmittance with 0 opacity must be 1.0");

        // Monotonicity: higher opacity -> lower or equal transmittance
        float prevFlat = 1.0f;
        float prevVol = 1.0f;
        for (float opacity = 0.0f; opacity <= 1.0f; opacity += 0.1f) {
            float tFlat = CloudShadowPolicy.flatTransmittance(1.0f, opacity);
            float tVol = CloudShadowPolicy.volumetricTransmittance(1.0f, opacity);
            require(tFlat <= prevFlat, "Flat transmittance must decrease monotonically with opacity");
            require(tVol <= prevVol, "Volumetric transmittance must decrease monotonically with opacity");
            require(tFlat >= 0.0f && tFlat <= 1.0f, "Flat transmittance must stay in [0, 1]");
            require(tVol >= 0.0f && tVol <= 1.0f, "Volumetric transmittance must stay in [0, 1]");
            prevFlat = tFlat;
            prevVol = tVol;
        }

        // Bounded attenuation at full opacity:
        // Flat min transmittance = 1.0 - 0.90 = 0.10
        require(approxEqual(CloudShadowPolicy.flatTransmittance(1.0f, 1.0f), 0.10f),
                "Flat min transmittance at 100% opacity must be 0.10");
        // Volumetric min transmittance = 1.0 - 0.95 = 0.05
        require(approxEqual(CloudShadowPolicy.volumetricTransmittance(1.0f, 1.0f), 0.05f),
                "Volumetric min transmittance at 100% opacity must be 0.05");
    }

    private static void testEmptyAndOccupiedCellTransmittance() {
        boolean[][] grid = new boolean[8][8];
        // All false (completely clear sky)
        CloudShadowSource emptySource = CloudShadowSource.createSynthetic(8, 8, grid, 10L);
        ByteBuffer buffer = ByteBuffer.allocate(8 * 8);

        emptySource.generateTransmittanceBytes(CloudShadowMode.FLAT, 0.0f, 1.0f, 0.0f, 1.0f, buffer);
        buffer.flip();
        while (buffer.hasRemaining()) {
            int val = Byte.toUnsignedInt(buffer.get());
            require(val == 255, "Empty cloud pattern must produce unshadowed 255 transmittance byte");
        }

        // Fully solid cloud sky
        boolean[][] solidGrid = new boolean[8][8];
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                solidGrid[z][x] = true;
            }
        }
        CloudShadowSource solidSource = CloudShadowSource.createSynthetic(8, 8, solidGrid, 11L);
        buffer.clear();
        solidSource.generateTransmittanceBytes(CloudShadowMode.FLAT, 0.0f, 1.0f, 0.0f, 1.0f, buffer);
        buffer.flip();
        int solidVal = Byte.toUnsignedInt(buffer.get());
        // 0.10 * 255 = 26
        require(solidVal == 26, "Solid flat cloud byte value mismatch: expected 26, got " + solidVal);
    }

    private static void testVolumetricThicknessPathLength() {
        boolean[][] solidGrid = new boolean[8][8];
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                solidGrid[z][x] = true;
            }
        }
        CloudShadowSource solidSource = CloudShadowSource.createSynthetic(8, 8, solidGrid, 20L);
        ByteBuffer bufferVertical = ByteBuffer.allocate(8 * 8);
        ByteBuffer bufferOblique = ByteBuffer.allocate(8 * 8);

        // Sun at zenith (Ly = 1.0)
        solidSource.generateTransmittanceBytes(CloudShadowMode.VOLUMETRIC, 0.0f, 1.0f, 0.0f, 1.0f, bufferVertical);
        // Sun low in sky (Ly = 0.50, Lx = 0.866)
        solidSource.generateTransmittanceBytes(CloudShadowMode.VOLUMETRIC, 0.866f, 0.50f, 0.0f, 1.0f, bufferOblique);

        bufferVertical.flip();
        bufferOblique.flip();
        int valVertical = Byte.toUnsignedInt(bufferVertical.get());
        int valOblique = Byte.toUnsignedInt(bufferOblique.get());

        // Longer optical path at lower angle must produce lower or equal transmittance (stronger shadow)
        require(valOblique <= valVertical, "Oblique volumetric path must have lower or equal transmittance than vertical");
    }

    private static void testAnimationMovementOffset() {
        int width = 256;
        long gameTime = 1200L;
        float partialTick = 0.5f;

        float offsetX = CloudShadowPolicy.computeCloudOffsetX(gameTime, partialTick, width);
        // Formula: ((1200 % 102400) + 0.5) * 0.030000001
        float expectedX = (1200.0f + 0.5f) * 0.030000001f;
        require(approxEqual(offsetX, expectedX), "Animation X offset mismatch");

        float offsetZ = CloudShadowPolicy.computeCloudOffsetZ();
        require(approxEqual(offsetZ, 3.9600000381469727f), "Animation Z offset mismatch");
    }

    private static void testResourceGenerationAndFailOpen() {
        CloudShadowSource empty = CloudShadowSource.empty();
        require(!empty.isAvailable(), "Empty source must not be available");
        require(empty.width() == 1 && empty.height() == 1, "Empty source dimensions mismatch");

        // Disabled frame state
        CloudShadowFrameState disabled = CloudShadowFrameState.disabled();
        require(!disabled.enabled(), "Disabled state must have enabled=false");
        require(disabled.mode() == CloudShadowMode.NONE, "Disabled state mode must be NONE");

        // Pattern generation tracking
        boolean[][] g = new boolean[2][2];
        CloudShadowSource gen1 = CloudShadowSource.createSynthetic(2, 2, g, 100L);
        CloudShadowSource gen2 = CloudShadowSource.createSynthetic(2, 2, g, 200L);
        require(gen1.generation() == 100L, "gen1 generation mismatch");
        require(gen2.generation() == 200L, "gen2 generation mismatch");
        require(gen1.generation() != gen2.generation(), "Distinct generations must differ");
    }

    private static boolean approxEqual(final float a, final float b) {
        return Math.abs(a - b) <= 0.0001f;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
