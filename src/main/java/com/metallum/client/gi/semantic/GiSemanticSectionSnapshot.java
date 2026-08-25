package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Immutable material/emission truth reduced to the 2/4/8-block G1 cell grids. */
public final class GiSemanticSectionSnapshot {
    public static final int CHANNELS_RGB = 3;
    public static final int CHANNELS_EMISSION = 4;
    public static final int FACE_COUNT = 6;
    public static final int[] CASCADE_CELL_EDGES = {8, 4, 2};
    public static final int[] CASCADE_CELL_OFFSETS = {0, 512, 576};
    public static final int CELL_COUNT = 584;
    public static final long PAYLOAD_BYTES = (long) CELL_COUNT * (
            CHANNELS_RGB * Short.BYTES
                    + CHANNELS_EMISSION * Short.BYTES
                    + FACE_COUNT
                    + Short.BYTES
                    + 5L
    );
    private static final GiSemanticSectionSnapshot UNKNOWN = createUnknown();

    private final short[] albedoRgb;
    private final short[] emissionRgbIntensity;
    private final byte[] occupancy;
    private final byte[] mediumMasks;
    private final byte[] validity;
    private final byte[] provenance;
    private final byte[] faceWeights;
    private final byte[] knownCoverage;
    private final short[] dominantMaterialIds;
    private final int observedQuads;
    private final int fallbackCells;
    private final String digest;

    public GiSemanticSectionSnapshot(
            final short[] albedoRgb,
            final short[] emissionRgbIntensity,
            final byte[] occupancy,
            final byte[] mediumMasks,
            final byte[] validity,
            final byte[] provenance,
            final byte[] faceWeights,
            final byte[] knownCoverage,
            final short[] dominantMaterialIds,
            final int observedQuads,
            final int fallbackCells
    ) {
        if (albedoRgb == null || albedoRgb.length != CELL_COUNT * CHANNELS_RGB
                || emissionRgbIntensity == null || emissionRgbIntensity.length != CELL_COUNT * CHANNELS_EMISSION
                || occupancy == null || occupancy.length != CELL_COUNT
                || mediumMasks == null || mediumMasks.length != CELL_COUNT
                || validity == null || validity.length != CELL_COUNT
                || provenance == null || provenance.length != CELL_COUNT
                || faceWeights == null || faceWeights.length != CELL_COUNT * FACE_COUNT
                || knownCoverage == null || knownCoverage.length != CELL_COUNT
                || dominantMaterialIds == null || dominantMaterialIds.length != CELL_COUNT
                || observedQuads < 0 || fallbackCells < 0 || fallbackCells > CELL_COUNT) {
            throw new IllegalArgumentException("G2 section payload does not match the fixed semantic topology");
        }
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            GiSemanticValidity.fromAbiId(Byte.toUnsignedInt(validity[cell]));
            if ((Byte.toUnsignedInt(mediumMasks[cell]) & ~GiSemanticMedium.ALL_MASK) != 0) {
                throw new IllegalArgumentException("G2 section contains unknown medium bits");
            }
        }
        this.albedoRgb = albedoRgb.clone();
        this.emissionRgbIntensity = emissionRgbIntensity.clone();
        this.occupancy = occupancy.clone();
        this.mediumMasks = mediumMasks.clone();
        this.validity = validity.clone();
        this.provenance = provenance.clone();
        this.faceWeights = faceWeights.clone();
        this.knownCoverage = knownCoverage.clone();
        this.dominantMaterialIds = dominantMaterialIds.clone();
        this.observedQuads = observedQuads;
        this.fallbackCells = fallbackCells;
        this.digest = computeDigest();
    }

    public static GiSemanticSectionSnapshot empty() {
        byte[] validity = new byte[CELL_COUNT];
        byte[] provenance = new byte[CELL_COUNT];
        byte[] coverage = new byte[CELL_COUNT];
        short[] ids = new short[CELL_COUNT];
        Arrays.fill(validity, (byte) GiSemanticValidity.KNOWN_EMPTY.abiId());
        Arrays.fill(provenance, (byte) GiSemanticProvenance.AUTHORITATIVE_EMPTY);
        Arrays.fill(coverage, (byte) 0xff);
        Arrays.fill(ids, (short) GiSemanticPalette.AIR_ID);
        return new GiSemanticSectionSnapshot(
                new short[CELL_COUNT * CHANNELS_RGB],
                new short[CELL_COUNT * CHANNELS_EMISSION],
                new byte[CELL_COUNT], new byte[CELL_COUNT], validity, provenance,
                new byte[CELL_COUNT * FACE_COUNT], coverage, ids, 0, 0
        );
    }

    public static GiSemanticSectionSnapshot unknown() {
        return UNKNOWN;
    }

    private static GiSemanticSectionSnapshot createUnknown() {
        byte[] occupancy = new byte[CELL_COUNT];
        byte[] medium = new byte[CELL_COUNT];
        short[] ids = new short[CELL_COUNT];
        Arrays.fill(occupancy, (byte) 0xff);
        Arrays.fill(medium, (byte) GiSemanticMedium.UNKNOWN_CONSERVATIVE.mask());
        Arrays.fill(ids, (short) GiSemanticPalette.UNKNOWN_ID);
        return new GiSemanticSectionSnapshot(
                new short[CELL_COUNT * CHANNELS_RGB],
                new short[CELL_COUNT * CHANNELS_EMISSION], occupancy, medium,
                new byte[CELL_COUNT], new byte[CELL_COUNT],
                new byte[CELL_COUNT * FACE_COUNT], new byte[CELL_COUNT], ids, 0, 0
        );
    }

    public static int cascadeCellIndex(final int cascade, final int x, final int y, final int z) {
        if (cascade < 0 || cascade >= GiFieldLayout.CASCADE_COUNT) {
            throw new IndexOutOfBoundsException("Invalid G2 section cascade: " + cascade);
        }
        int edge = CASCADE_CELL_EDGES[cascade];
        return CASCADE_CELL_OFFSETS[cascade] + GiFieldLayout.cellIndex(x, y, z, edge);
    }

    public int observedQuads() {
        return this.observedQuads;
    }

    public int fallbackCells() {
        return this.fallbackCells;
    }

    public String digest() {
        return this.digest;
    }

    public short[] albedoRgb() {
        return this.albedoRgb.clone();
    }

    public short[] emissionRgbIntensity() {
        return this.emissionRgbIntensity.clone();
    }

    public byte[] occupancy() {
        return this.occupancy.clone();
    }

    public byte[] mediumMasks() {
        return this.mediumMasks.clone();
    }

    public byte[] validity() {
        return this.validity.clone();
    }

    public byte[] provenance() {
        return this.provenance.clone();
    }

    public byte[] faceWeights() {
        return this.faceWeights.clone();
    }

    public byte[] knownCoverage() {
        return this.knownCoverage.clone();
    }

    public short[] dominantMaterialIds() {
        return this.dominantMaterialIds.clone();
    }

    short albedoUnchecked(final int component) {
        return this.albedoRgb[component];
    }

    short emissionUnchecked(final int component) {
        return this.emissionRgbIntensity[component];
    }

    byte occupancyUnchecked(final int cell) {
        return this.occupancy[cell];
    }

    byte mediumMaskUnchecked(final int cell) {
        return this.mediumMasks[cell];
    }

    byte validityUnchecked(final int cell) {
        return this.validity[cell];
    }

    byte provenanceUnchecked(final int cell) {
        return this.provenance[cell];
    }

    byte faceWeightUnchecked(final int component) {
        return this.faceWeights[component];
    }

    byte knownCoverageUnchecked(final int cell) {
        return this.knownCoverage[cell];
    }

    short dominantMaterialIdUnchecked(final int cell) {
        return this.dominantMaterialIds[cell];
    }

    private String computeDigest() {
        try {
            MessageDigest message = MessageDigest.getInstance("SHA-256");
            updateShorts(message, this.albedoRgb);
            updateShorts(message, this.emissionRgbIntensity);
            message.update(this.occupancy);
            message.update(this.mediumMasks);
            message.update(this.validity);
            message.update(this.provenance);
            message.update(this.faceWeights);
            message.update(this.knownCoverage);
            updateShorts(message, this.dominantMaterialIds);
            ByteBuffer counts = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            counts.putInt(this.observedQuads).putInt(this.fallbackCells);
            message.update(counts.array());
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
