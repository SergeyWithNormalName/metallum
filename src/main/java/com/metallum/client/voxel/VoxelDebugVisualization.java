package com.metallum.client.voxel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit, zero-production-overhead PPM slice visualization for one accepted L5 brick. */
public final class VoxelDebugVisualization {
    private static final int EDGE = VoxelBrickPatch.LOGICAL_EDGE;

    private VoxelDebugVisualization() {
    }

    /**
     * Renders one logical Z slice. Occupancy controls visibility; material class controls hue,
     * and quantized opacity controls brightness. The returned binary PPM can be opened directly
     * by Preview or attached to a validation report without any Minecraft render binding.
     */
    public static byte[] renderPpm(
            final VoxelBrickPatch patch,
            final VoxelSubdivision subdivision,
            final int logicalZ
    ) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(subdivision, "subdivision");
        if (logicalZ < 0 || logicalZ >= EDGE) {
            throw new IndexOutOfBoundsException("L5 debug slice must be in [0, 31]");
        }
        int baseEdge = EDGE / subdivision.scale();
        byte[] optical = patch.opticalPayload();
        int expectedOptical = baseEdge * baseEdge * baseEdge;
        if (optical.length != expectedOptical) {
            throw new IllegalArgumentException(
                    "L5 debug subdivision does not match the brick optical payload"
            );
        }
        int[] occupancy = patch.occupancyWords();
        String header = "P6\n# Metallum L5 level=" + patch.level()
                + " logical=" + patch.logicalBrickX() + ',' + patch.logicalBrickY() + ','
                + patch.logicalBrickZ() + " sliceZ=" + logicalZ + "\n"
                + EDGE + ' ' + EDGE + "\n255\n";
        ByteArrayOutputStream image = new ByteArrayOutputStream(
                header.length() + EDGE * EDGE * 3
        );
        image.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        for (int logicalY = EDGE - 1; logicalY >= 0; logicalY--) {
            int row = occupancy[logicalZ * EDGE + logicalY];
            for (int logicalX = 0; logicalX < EDGE; logicalX++) {
                if ((row & (1 << logicalX)) == 0) {
                    image.write(0);
                    image.write(0);
                    image.write(0);
                    continue;
                }
                int blockX = logicalX / subdivision.scale();
                int blockY = logicalY / subdivision.scale();
                int blockZ = logicalZ / subdivision.scale();
                int opticalIndex = (blockZ * baseEdge + blockY) * baseEdge + blockX;
                VoxelMaterialDescriptor material = VoxelMaterialDescriptor.fromPackedUnsignedByte(
                        Byte.toUnsignedInt(optical[opticalIndex])
                );
                int[] rgb = palette(material.materialClass());
                int opacity = VoxelMaterialDescriptor.TRANSMITTANCE_MAX
                        - material.quantizedTransmittance();
                int brightness = 96 + opacity * 159 / VoxelMaterialDescriptor.TRANSMITTANCE_MAX;
                image.write(rgb[0] * brightness / 255);
                image.write(rgb[1] * brightness / 255);
                image.write(rgb[2] * brightness / 255);
            }
        }
        return image.toByteArray();
    }

    public static void writePpm(
            final Path output,
            final VoxelBrickPatch patch,
            final VoxelSubdivision subdivision,
            final int logicalZ
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, renderPpm(patch, subdivision, logicalZ));
    }

    private static int[] palette(final VoxelMaterialClass materialClass) {
        return switch (materialClass) {
            case AIR -> new int[]{160, 160, 160};
            case OPAQUE -> new int[]{255, 255, 255};
            case CUTOUT -> new int[]{255, 200, 40};
            case GLASS -> new int[]{80, 220, 255};
            case FOLIAGE -> new int[]{80, 220, 80};
            case WATER -> new int[]{60, 120, 255};
            case TRANSLUCENT -> new int[]{200, 120, 255};
            case UNKNOWN_CONSERVATIVE -> new int[]{255, 0, 255};
        };
    }
}
