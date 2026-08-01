package com.metallum.client.voxel;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** One complete logical 32^3 brick patch ready for a future L5 upload ring. */
public final class VoxelBrickPatch {
    public static final int LOGICAL_EDGE = 32;
    public static final int OCCUPANCY_WORDS = LOGICAL_EDGE * LOGICAL_EDGE;
    public static final int OCCUPANCY_BYTES = OCCUPANCY_WORDS * Integer.BYTES;

    private final int level;
    private final int destinationBrickX;
    private final int destinationBrickY;
    private final int destinationBrickZ;
    private final int logicalBrickX;
    private final int logicalBrickY;
    private final int logicalBrickZ;
    private final int contentStamp;
    private final long worldGeneration;
    private final long clipmapGeneration;
    private final byte[] packedPayload;
    private final int opticalLength;
    private final int chromaticLength;

    public VoxelBrickPatch(
            final int level,
            final int destinationBrickX,
            final int destinationBrickY,
            final int destinationBrickZ,
            final int logicalBrickX,
            final int logicalBrickY,
            final int logicalBrickZ,
            final int contentStamp,
            final long worldGeneration,
            final long clipmapGeneration,
            final int[] occupancyWords,
            final byte[] optical
    ) {
        this(
                level, destinationBrickX, destinationBrickY, destinationBrickZ,
                logicalBrickX, logicalBrickY, logicalBrickZ, contentStamp,
                worldGeneration, clipmapGeneration, occupancyWords, optical,
                VoxelChromaticFilter.neutralPackedValues(optical == null ? 0 : optical.length)
        );
    }

    public VoxelBrickPatch(
            final int level,
            final int destinationBrickX,
            final int destinationBrickY,
            final int destinationBrickZ,
            final int logicalBrickX,
            final int logicalBrickY,
            final int logicalBrickZ,
            final int contentStamp,
            final long worldGeneration,
            final long clipmapGeneration,
            final int[] occupancyWords,
            final byte[] optical,
            final byte[] chromatic
    ) {
        if (level < 0 || destinationBrickX < 0 || destinationBrickY < 0 || destinationBrickZ < 0) {
            throw new IllegalArgumentException("Voxel patch level and toroidal destination must be non-negative");
        }
        if (worldGeneration <= 0L || clipmapGeneration <= 0L) {
            throw new IllegalArgumentException("Voxel patch generations must be positive");
        }
        if (contentStamp == 0) {
            throw new IllegalArgumentException("Voxel patch content stamp must be non-zero");
        }
        if (occupancyWords == null || occupancyWords.length != OCCUPANCY_WORDS) {
            throw new IllegalArgumentException("Voxel brick needs exactly 1024 occupancy words");
        }
        if (optical == null || optical.length == 0) {
            throw new IllegalArgumentException("Voxel brick optical payload must not be empty");
        }
        if (chromatic == null
                || chromatic.length != VoxelChromaticFilter.packedBytesFor(optical.length)) {
            throw new IllegalArgumentException("Voxel brick chromatic payload does not match optics");
        }
        this.level = level;
        this.destinationBrickX = destinationBrickX;
        this.destinationBrickY = destinationBrickY;
        this.destinationBrickZ = destinationBrickZ;
        this.logicalBrickX = logicalBrickX;
        this.logicalBrickY = logicalBrickY;
        this.logicalBrickZ = logicalBrickZ;
        this.contentStamp = contentStamp;
        this.worldGeneration = worldGeneration;
        this.clipmapGeneration = clipmapGeneration;
        this.packedPayload = pack(occupancyWords, optical, chromatic);
        this.opticalLength = optical.length;
        this.chromaticLength = chromatic.length;
    }

    private VoxelBrickPatch(
            final int level,
            final int destinationBrickX,
            final int destinationBrickY,
            final int destinationBrickZ,
            final int logicalBrickX,
            final int logicalBrickY,
            final int logicalBrickZ,
            final int contentStamp,
            final long worldGeneration,
            final long clipmapGeneration,
            final byte[] ownedPackedPayload,
            final int opticalLength,
            final int chromaticLength
    ) {
        if (level < 0 || destinationBrickX < 0 || destinationBrickY < 0 || destinationBrickZ < 0
                || worldGeneration <= 0L || clipmapGeneration <= 0L || contentStamp == 0
                || ownedPackedPayload == null || opticalLength <= 0 || chromaticLength <= 0
                || chromaticLength != VoxelChromaticFilter.packedBytesFor(opticalLength)
                || ownedPackedPayload.length != OCCUPANCY_BYTES + opticalLength + chromaticLength) {
            throw new IllegalArgumentException("Invalid worker-owned voxel patch");
        }
        this.level = level;
        this.destinationBrickX = destinationBrickX;
        this.destinationBrickY = destinationBrickY;
        this.destinationBrickZ = destinationBrickZ;
        this.logicalBrickX = logicalBrickX;
        this.logicalBrickY = logicalBrickY;
        this.logicalBrickZ = logicalBrickZ;
        this.contentStamp = contentStamp;
        this.worldGeneration = worldGeneration;
        this.clipmapGeneration = clipmapGeneration;
        this.packedPayload = ownedPackedPayload;
        this.opticalLength = opticalLength;
        this.chromaticLength = chromaticLength;
    }

    static VoxelBrickPatch fromOwnedPackedPayload(
            final int level,
            final int destinationBrickX,
            final int destinationBrickY,
            final int destinationBrickZ,
            final int logicalBrickX,
            final int logicalBrickY,
            final int logicalBrickZ,
            final int contentStamp,
            final long worldGeneration,
            final long clipmapGeneration,
            final byte[] ownedPackedPayload,
            final int opticalLength,
            final int chromaticLength
    ) {
        return new VoxelBrickPatch(
                level, destinationBrickX, destinationBrickY, destinationBrickZ,
                logicalBrickX, logicalBrickY, logicalBrickZ, contentStamp,
                worldGeneration, clipmapGeneration, ownedPackedPayload, opticalLength, chromaticLength
        );
    }

    public int level() {
        return this.level;
    }

    public int destinationBrickX() {
        return this.destinationBrickX;
    }

    public int destinationBrickY() {
        return this.destinationBrickY;
    }

    public int destinationBrickZ() {
        return this.destinationBrickZ;
    }

    public int logicalBrickX() {
        return this.logicalBrickX;
    }

    public int logicalBrickY() {
        return this.logicalBrickY;
    }

    public int logicalBrickZ() {
        return this.logicalBrickZ;
    }

    public int contentStamp() {
        return this.contentStamp;
    }

    public long worldGeneration() {
        return this.worldGeneration;
    }

    public long clipmapGeneration() {
        return this.clipmapGeneration;
    }

    public int[] occupancyWords() {
        int[] words = new int[OCCUPANCY_WORDS];
        ByteBuffer.wrap(this.packedPayload, 0, OCCUPANCY_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer()
                .get(words);
        return words;
    }

    /** Native packet ABI requires these 1024 words in little-endian order. */
    public byte[] occupancyPayloadLittleEndian() {
        return Arrays.copyOf(this.packedPayload, OCCUPANCY_BYTES);
    }

    public byte[] opticalPayload() {
        return Arrays.copyOfRange(
                this.packedPayload, OCCUPANCY_BYTES, OCCUPANCY_BYTES + this.opticalLength
        );
    }

    public int opticalLength() {
        return this.opticalLength;
    }

    public byte[] chromaticPayload() {
        return Arrays.copyOfRange(
                this.packedPayload,
                OCCUPANCY_BYTES + this.opticalLength,
                this.packedPayload.length
        );
    }

    public int chromaticLength() {
        return this.chromaticLength;
    }

    public int packedPayloadLength() {
        return this.packedPayload.length;
    }

    /** Performs the bridge's single allocation-free bounded copy into its reusable packet. */
    public void copyPackedPayloadTo(final MemorySegment destination, final long offset) {
        if (destination == null || offset < 0L
                || offset > destination.byteSize() - this.packedPayload.length) {
            throw new IllegalArgumentException("Voxel patch copy exceeds its destination");
        }
        MemorySegment.copy(
                this.packedPayload, 0, destination, ValueLayout.JAVA_BYTE,
                offset, this.packedPayload.length
        );
    }

    private static byte[] pack(
            final int[] occupancyWords,
            final byte[] optical,
            final byte[] chromatic
    ) {
        byte[] packed = new byte[OCCUPANCY_BYTES + optical.length + chromatic.length];
        ByteBuffer output = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : occupancyWords) {
            output.putInt(word);
        }
        output.put(optical);
        output.put(chromatic);
        return packed;
    }
}
