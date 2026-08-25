package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable CPU truth for the complete three-cascade G2 field. */
public final class GiSemanticFieldSnapshot {
    public static final int TOTAL_CELLS = GiFieldLayout.CASCADE_COUNT * GiFieldLayout.CELLS_PER_CASCADE;

    private final GiSemanticWorldToken world;
    private final long clipmapGeneration;
    private final long paletteGeneration;
    private final long contentGeneration;
    private final String paletteDigest;
    private final int[] origins;
    private final short[] albedoRgb;
    private final short[] emissionRgbIntensity;
    private final byte[] occupancy;
    private final byte[] mediumMasks;
    private final byte[] validity;
    private final byte[] provenance;
    private final byte[] faceWeights;
    private final byte[] knownCoverage;
    private final short[] dominantMaterialIds;
    private final long[] contentGenerations;
    private final int residentSections;
    private final String digest;

    GiSemanticFieldSnapshot(
            final GiSemanticWorldToken world,
            final long clipmapGeneration,
            final long paletteGeneration,
            final long contentGeneration,
            final String paletteDigest,
            final int[] origins,
            final short[] albedoRgb,
            final short[] emissionRgbIntensity,
            final byte[] occupancy,
            final byte[] mediumMasks,
            final byte[] validity,
            final byte[] provenance,
            final byte[] faceWeights,
            final byte[] knownCoverage,
            final short[] dominantMaterialIds,
            final long[] contentGenerations,
            final int residentSections
    ) {
        this.world = java.util.Objects.requireNonNull(world, "world");
        this.paletteDigest = java.util.Objects.requireNonNull(paletteDigest, "paletteDigest");
        if (clipmapGeneration <= 0L || paletteGeneration <= 0L || contentGeneration <= 0L || origins == null
                || origins.length != GiFieldLayout.CASCADE_COUNT * 3
                || albedoRgb.length != TOTAL_CELLS * 3
                || emissionRgbIntensity.length != TOTAL_CELLS * 4
                || occupancy.length != TOTAL_CELLS || mediumMasks.length != TOTAL_CELLS
                || validity.length != TOTAL_CELLS || provenance.length != TOTAL_CELLS
                || faceWeights.length != TOTAL_CELLS * 6 || knownCoverage.length != TOTAL_CELLS
                || dominantMaterialIds.length != TOTAL_CELLS || contentGenerations.length != TOTAL_CELLS
                || residentSections < 0) {
            throw new IllegalArgumentException("Invalid complete G2 field snapshot");
        }
        this.clipmapGeneration = clipmapGeneration;
        this.paletteGeneration = paletteGeneration;
        this.contentGeneration = contentGeneration;
        this.origins = origins.clone();
        this.albedoRgb = albedoRgb.clone();
        this.emissionRgbIntensity = emissionRgbIntensity.clone();
        this.occupancy = occupancy.clone();
        this.mediumMasks = mediumMasks.clone();
        this.validity = validity.clone();
        this.provenance = provenance.clone();
        this.faceWeights = faceWeights.clone();
        this.knownCoverage = knownCoverage.clone();
        this.dominantMaterialIds = dominantMaterialIds.clone();
        this.contentGenerations = contentGenerations.clone();
        this.residentSections = residentSections;
        this.digest = computeDigest();
    }

    public GiSemanticWorldToken world() { return this.world; }
    public long clipmapGeneration() { return this.clipmapGeneration; }
    public long paletteGeneration() { return this.paletteGeneration; }
    public long contentGeneration() { return this.contentGeneration; }
    public String paletteDigest() { return this.paletteDigest; }
    public int[] origins() { return this.origins.clone(); }
    public short[] albedoRgb() { return this.albedoRgb.clone(); }
    public short[] emissionRgbIntensity() { return this.emissionRgbIntensity.clone(); }
    public byte[] occupancy() { return this.occupancy.clone(); }
    public byte[] mediumMasks() { return this.mediumMasks.clone(); }
    public byte[] validity() { return this.validity.clone(); }
    public byte[] provenance() { return this.provenance.clone(); }
    public byte[] faceWeights() { return this.faceWeights.clone(); }
    public byte[] knownCoverage() { return this.knownCoverage.clone(); }
    public short[] dominantMaterialIds() { return this.dominantMaterialIds.clone(); }
    public long[] contentGenerations() { return this.contentGenerations.clone(); }
    public int residentSections() { return this.residentSections; }
    public String digest() { return this.digest; }

    public int cellIndexForWorld(final int cascade, final int worldX, final int worldY, final int worldZ) {
        if (cascade < 0 || cascade >= GiFieldLayout.CASCADE_COUNT) {
            return -1;
        }
        int cellSize = GiFieldLayout.cellSizeBlocks(cascade);
        int base = cascade * 3;
        int relativeX = worldX - this.origins[base];
        int relativeY = worldY - this.origins[base + 1];
        int relativeZ = worldZ - this.origins[base + 2];
        if (relativeX < 0 || relativeY < 0 || relativeZ < 0
                || relativeX >= GiFieldLayout.spanBlocks(cascade)
                || relativeY >= GiFieldLayout.spanBlocks(cascade)
                || relativeZ >= GiFieldLayout.spanBlocks(cascade)) {
            return -1;
        }
        return cascade * GiFieldLayout.CELLS_PER_CASCADE + GiFieldLayout.cellIndex(
                Math.floorDiv(relativeX, cellSize), Math.floorDiv(relativeY, cellSize),
                Math.floorDiv(relativeZ, cellSize), GiFieldLayout.CELLS_PER_AXIS
        );
    }

    public GiSemanticValidity validity(final int globalCell) {
        requireCell(globalCell);
        return GiSemanticValidity.fromAbiId(Byte.toUnsignedInt(this.validity[globalCell]));
    }

    public int occupancyUnsigned(final int globalCell) {
        requireCell(globalCell);
        return Byte.toUnsignedInt(this.occupancy[globalCell]);
    }

    private void requireCell(final int cell) {
        if (cell < 0 || cell >= TOTAL_CELLS) {
            throw new IndexOutOfBoundsException("G2 field cell outside complete topology");
        }
    }

    private String computeDigest() {
        try {
            MessageDigest message = MessageDigest.getInstance("SHA-256");
            ByteBuffer header = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(this.world.worldGeneration()).putLong(this.world.resourceEpoch())
                    .putLong(this.world.materialEpoch()).putLong(this.clipmapGeneration)
                    .putLong(this.paletteGeneration).putLong(this.contentGeneration)
                    .putInt(this.residentSections);
            for (int origin : this.origins) {
                header.putInt(origin);
            }
            message.update(header.array());
            message.update(this.paletteDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            updateShorts(message, this.albedoRgb);
            updateShorts(message, this.emissionRgbIntensity);
            message.update(this.occupancy);
            message.update(this.mediumMasks);
            message.update(this.validity);
            message.update(this.provenance);
            message.update(this.faceWeights);
            message.update(this.knownCoverage);
            updateShorts(message, this.dominantMaterialIds);
            ByteBuffer generations = ByteBuffer.allocate(this.contentGenerations.length * Long.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            generations.asLongBuffer().put(this.contentGenerations);
            message.update(generations.array());
            return HexFormat.of().formatHex(message.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void updateShorts(final MessageDigest digest, final short[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(values);
        digest.update(bytes.array());
    }
}
