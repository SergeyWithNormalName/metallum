package com.metallum.client.lighting.cloud;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable CPU source pattern and periodic 2D prefiltered coverage for Metallum Cloud Shadows.
 *
 * <p>Derives directly from the actual vanilla cloud pattern (textures/environment/clouds.png)
 * or mirrors the runtime {@link CloudRenderer.TextureData}. Recomputed safely on resource reload.</p>
 */
public final class CloudShadowSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudShadowSource.class);
    private static final Identifier TEXTURE_LOCATION = Identifier.withDefaultNamespace("textures/environment/clouds.png");

    private final int width;
    private final int height;
    private final float[] baseCoverage;
    private final long generation;
    private final boolean available;

    private static final CloudShadowSource EMPTY = new CloudShadowSource(1, 1, new float[]{0.0f}, 0L, false);

    private CloudShadowSource(
            final int width,
            final int height,
            final float[] baseCoverage,
            final long generation,
            final boolean available
    ) {
        this.width = width;
        this.height = height;
        this.baseCoverage = baseCoverage;
        this.generation = generation;
        this.available = available;
    }

    public static CloudShadowSource empty() {
        return EMPTY;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public float gridWidthBlocks() {
        return (float) this.width * CloudShadowPolicy.CELL_SIZE_BLOCKS;
    }

    public float gridHeightBlocks() {
        return (float) this.height * CloudShadowPolicy.CELL_SIZE_BLOCKS;
    }

    public long generation() {
        return this.generation;
    }

    public boolean isAvailable() {
        return this.available && this.width > 0 && this.height > 0;
    }

    /**
     * Reads base prefiltered coverage at discrete cell indices with toroidal wrap.
     */
    public float baseCoverage(final int x, final int z) {
        if (!this.available) {
            return 0.0f;
        }
        int wrapX = Math.floorMod(x, this.width);
        int wrapZ = Math.floorMod(z, this.height);
        return this.baseCoverage[wrapZ * this.width + wrapX];
    }

    /**
     * Bilinear continuous sampling of prefiltered base coverage in texel coordinates with toroidal wrap.
     */
    public float sampleBilinear(final float uTexels, final float vTexels) {
        if (!this.available) {
            return 0.0f;
        }
        int x0 = Math.floorMod((int) Math.floor(uTexels), this.width);
        int x1 = (x0 + 1) % this.width;
        int z0 = Math.floorMod((int) Math.floor(vTexels), this.height);
        int z1 = (z0 + 1) % this.height;
        float fx = uTexels - (float) Math.floor(uTexels);
        float fz = vTexels - (float) Math.floor(vTexels);

        float c00 = this.baseCoverage[z0 * this.width + x0];
        float c10 = this.baseCoverage[z0 * this.width + x1];
        float c01 = this.baseCoverage[z1 * this.width + x0];
        float c11 = this.baseCoverage[z1 * this.width + x1];

        float top = (1.0f - fx) * c00 + fx * c10;
        float bottom = (1.0f - fx) * c01 + fx * c11;
        return (1.0f - fz) * top + fz * bottom;
    }

    /**
     * Populates a contiguous R8_UNORM byte buffer representing one periodic transmittance tile.
     *
     * @param mode active cloud shadow mode (FLAT or VOLUMETRIC)
     * @param toLightX celestial ray direction X
     * @param toLightY celestial ray direction Y
     * @param toLightZ celestial ray direction Z
     * @param cloudOpacity resolved cloud visual opacity [0..1]
     * @param output destination byte buffer with capacity >= width * height
     */
    public void generateTransmittanceBytes(
            final CloudShadowMode mode,
            final float toLightX,
            final float toLightY,
            final float toLightZ,
            final float cloudOpacity,
            final ByteBuffer output
    ) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(output, "output");
        int totalPixels = this.width * this.height;
        if (output.remaining() < totalPixels) {
            throw new IllegalArgumentException("Destination buffer is too small for transmittance tile");
        }

        if (mode == CloudShadowMode.NONE || !this.available || cloudOpacity <= 0.005f) {
            // Unshadowed: all 255 (1.0)
            for (int index = 0; index < totalPixels; index++) {
                output.put((byte) 0xFF);
            }
            return;
        }

        if (mode == CloudShadowMode.FLAT) {
            for (int index = 0; index < totalPixels; index++) {
                float cov = this.baseCoverage[index];
                float trans = CloudShadowPolicy.flatTransmittance(cov, cloudOpacity);
                int byteVal = Math.clamp(Math.round(trans * 255.0f), 0, 255);
                output.put((byte) byteVal);
            }
            return;
        }

        // VOLUMETRIC mode: 3D ray preintegration across cloud slab
        float lightY = Math.max(toLightY, 0.001f);
        // Ray horizontal displacement across the 4.0 block slab:
        // dx_blocks = (toLightX / lightY) * 4.0
        // In texels (12 blocks/texel): deltaU = (toLightX / lightY) * (4.0 / 12.0) = (toLightX / lightY) * (1.0 / 3.0)
        float deltaU = (toLightX / lightY) * (CloudShadowPolicy.CLOUD_THICKNESS_BLOCKS / CloudShadowPolicy.CELL_SIZE_BLOCKS);
        float deltaV = (toLightZ / lightY) * (CloudShadowPolicy.CLOUD_THICKNESS_BLOCKS / CloudShadowPolicy.CELL_SIZE_BLOCKS);

        // Path ratio through the slab relative to vertical:
        float pathRatio = Math.clamp(1.0f / Math.max(lightY, 0.10f), 1.0f, 2.5f);
        final int sampleCount = CloudShadowPolicy.VOLUMETRIC_PREINTEGRATION_SAMPLES;

        for (int z = 0; z < this.height; z++) {
            for (int x = 0; x < this.width; x++) {
                float coverageSum = 0.0f;
                for (int step = 0; step < sampleCount; step++) {
                    float s = ((float) step + 0.5f) / (float) sampleCount;
                    float u = (float) x + s * deltaU;
                    float v = (float) z + s * deltaV;
                    coverageSum += this.sampleBilinear(u, v);
                }
                float avgCoverage = coverageSum / (float) sampleCount;
                float opticalDensity = avgCoverage * pathRatio;
                float trans = CloudShadowPolicy.volumetricTransmittance(opticalDensity, cloudOpacity);
                int byteVal = Math.clamp(Math.round(trans * 255.0f), 0, 255);
                output.put((byte) byteVal);
            }
        }
    }

    /**
     * Creates a CloudShadowSource from a Minecraft ResourceManager.
     */
    public static CloudShadowSource loadFromResourceManager(
            final ResourceManager manager,
            final long generation
    ) {
        Objects.requireNonNull(manager, "manager");
        try (InputStream stream = manager.open(TEXTURE_LOCATION)) {
            NativeImage image = NativeImage.read(stream);
            try {
                return createFromNativeImage(image, generation);
            } finally {
                image.close();
            }
        } catch (Exception exception) {
            LOGGER.warn("Failed to load vanilla cloud texture for Cloud Shadows; failing open", exception);
            return new CloudShadowSource(256, 256, new float[256 * 256], generation, false);
        }
    }

    /**
     * Creates a CloudShadowSource from an existing NativeImage.
     */
    public static CloudShadowSource createFromNativeImage(
            final NativeImage image,
            final long generation
    ) {
        Objects.requireNonNull(image, "image");
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return empty();
        }
        boolean[] rawOccupied = new boolean[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixel(x, z);
                int alpha = ARGB.alpha(pixel);
                rawOccupied[z * width + x] = alpha >= 10;
            }
        }
        float[] prefiltered = prefilterCoverage(rawOccupied, width, height);
        return new CloudShadowSource(width, height, prefiltered, generation, true);
    }

    /**
     * Creates a CloudShadowSource from Minecraft's CloudRenderer.TextureData.
     */
    public static CloudShadowSource createFromTextureData(
            final CloudRenderer.TextureData textureData,
            final long generation
    ) {
        if (textureData == null || textureData.width() <= 0 || textureData.height() <= 0) {
            return empty();
        }
        int width = textureData.width();
        int height = textureData.height();
        long[] cells = textureData.cells();
        if (cells == null || cells.length < width * height) {
            return empty();
        }
        boolean[] rawOccupied = new boolean[width * height];
        for (int index = 0; index < width * height; index++) {
            long packed = cells[index];
            int color = (int) (packed >> 4);
            int alpha = ARGB.alpha(color);
            rawOccupied[index] = alpha >= 10;
        }
        float[] prefiltered = prefilterCoverage(rawOccupied, width, height);
        return new CloudShadowSource(width, height, prefiltered, generation, true);
    }

    /**
     * Creates a synthetic pattern for deterministic unit testing.
     */
    public static CloudShadowSource createSynthetic(
            final int width,
            final int height,
            final boolean[][] occupiedGrid,
            final long generation
    ) {
        Objects.requireNonNull(occupiedGrid, "occupiedGrid");
        boolean[] rawOccupied = new boolean[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                rawOccupied[z * width + x] = (z < occupiedGrid.length && x < occupiedGrid[z].length) && occupiedGrid[z][x];
            }
        }
        float[] prefiltered = prefilterCoverage(rawOccupied, width, height);
        return new CloudShadowSource(width, height, prefiltered, generation, true);
    }

    /**
     * 3x3 periodic discrete box/gaussian filter to soften blocky cell edges smoothly.
     */
    private static float[] prefilterCoverage(
            final boolean[] rawOccupied,
            final int width,
            final int height
    ) {
        float[] result = new float[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                float center = rawOccupied[z * width + x] ? 4.0f : 0.0f;
                float north = rawOccupied[Math.floorMod(z - 1, height) * width + x] ? 2.0f : 0.0f;
                float south = rawOccupied[((z + 1) % height) * width + x] ? 2.0f : 0.0f;
                float west  = rawOccupied[z * width + Math.floorMod(x - 1, width)] ? 2.0f : 0.0f;
                float east  = rawOccupied[z * width + ((x + 1) % width)] ? 2.0f : 0.0f;
                float nw = rawOccupied[Math.floorMod(z - 1, height) * width + Math.floorMod(x - 1, width)] ? 1.0f : 0.0f;
                float ne = rawOccupied[Math.floorMod(z - 1, height) * width + ((x + 1) % width)] ? 1.0f : 0.0f;
                float sw = rawOccupied[((z + 1) % height) * width + Math.floorMod(x - 1, width)] ? 1.0f : 0.0f;
                float se = rawOccupied[((z + 1) % height) * width + ((x + 1) % width)] ? 1.0f : 0.0f;

                float sum = center + north + south + west + east + nw + ne + sw + se;
                result[z * width + x] = sum / 16.0f;
            }
        }
        return result;
    }
}
