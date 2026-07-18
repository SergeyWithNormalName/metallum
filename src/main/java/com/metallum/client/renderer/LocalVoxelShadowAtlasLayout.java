package com.metallum.client.renderer;

import java.util.List;

/**
 * Versioned sizing contract for the resident L6 point-shadow atlas.
 *
 * <p>The atlas deliberately preserves the current point-cache payload exactly: six cube faces,
 * four distance/transmittance hits per texel, and eight bytes per hit. It is only a layout
 * declaration shared by generation accounting, residency and fragment descriptors.</p>
 */
public final class LocalVoxelShadowAtlasLayout {
    public static final int ABI_VERSION = 1;
    public static final int PAGE_ALIGNMENT_BYTES = 256;
    public static final int FACE_COUNT = LocalVoxelShadowLayout.CACHE_FACE_COUNT;
    public static final int LAYER_COUNT = LocalVoxelShadowLayout.CACHE_LAYER_COUNT;
    public static final int HIT_STRIDE_BYTES = LocalVoxelShadowLayout.CACHE_HIT_STRIDE_BYTES;
    public static final List<Integer> PAGE_EDGES = List.of(8, 16, 32, 64);

    /** One per uploaded L3 candidate, packed as four unsigned 32-bit words. */
    public static final int DESCRIPTOR_STRIDE_BYTES = 16;
    public static final int DESCRIPTOR_STATE_OFFSET = 0;
    public static final int DESCRIPTOR_ATLAS_OFFSET_LO_OFFSET = Integer.BYTES;
    public static final int DESCRIPTOR_ATLAS_OFFSET_HI_OFFSET = Integer.BYTES * 2;
    public static final int DESCRIPTOR_PAGE_EDGE_OFFSET = Integer.BYTES * 3;
    public static final int DESCRIPTOR_RING_SLOTS = AdvancedLightingLayout.UPLOAD_RING_SLOTS;
    public static final int MAX_LIGHT_DESCRIPTORS = AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS;

    /**
     * Valid direct light without a resident page. The fragment shader contributes it with
     * visibility one and deliberately performs neither atlas sampling nor L5/DDA traversal.
     */
    public static final int DESCRIPTOR_STATE_APPROXIMATE_DIRECT = 0;
    /**
     * @deprecated State zero is no longer a DDA path. Use {@link #DESCRIPTOR_STATE_APPROXIMATE_DIRECT}.
     */
    @Deprecated(forRemoval = false)
    public static final int DESCRIPTOR_STATE_DDA_FALLBACK = DESCRIPTOR_STATE_APPROXIMATE_DIRECT;
    public static final int DESCRIPTOR_STATE_READY = 1;
    /** A prior valid page is retained while a replacement is built. */
    public static final int DESCRIPTOR_STATE_STALE_RETAINED = 2;
    /** A page is being prepared without a safe fallback; suppress direct until it is ready. */
    public static final int DESCRIPTOR_STATE_BUILDING = 3;
    /** No valid cache or bounded DDA coverage: suppress the direct term, never render unshadowed. */
    public static final int DESCRIPTOR_STATE_FAIL_CLOSED = 4;

    public static final long MEBIBYTE = 1L << 20;
    public static final long PERFORMANCE_ATLAS_BYTES = 32L * MEBIBYTE;
    public static final long BALANCED_ATLAS_BYTES = 64L * MEBIBYTE;
    public static final long ULTRA_ATLAS_BYTES = 128L * MEBIBYTE;

    public record Budget(
            long atlasBytes,
            long descriptorRingBytes,
            long totalDedicatedBytes
    ) {
        public Budget {
            if (atlasBytes <= 0L || atlasBytes % PAGE_ALIGNMENT_BYTES != 0L
                    || descriptorRingBytes != expectedDescriptorRingBytes()
                    || totalDedicatedBytes != Math.addExact(atlasBytes, descriptorRingBytes)) {
                throw new IllegalArgumentException("Invalid resident local-shadow atlas budget");
            }
        }

        private static long expectedDescriptorRingBytes() {
            return LocalVoxelShadowAtlasLayout.descriptorRingBytes();
        }
    }

    private LocalVoxelShadowAtlasLayout() {
    }

    static {
        if (DESCRIPTOR_PAGE_EDGE_OFFSET + Integer.BYTES != DESCRIPTOR_STRIDE_BYTES) {
            throw new IllegalStateException("Resident local-shadow descriptor layout is not 16 bytes");
        }
    }

    public static Budget balancedBudget() {
        return forPreset(LightingPreset.BALANCED);
    }

    public static Budget forPreset(final LightingPreset preset) {
        long atlasBytes = switch (java.util.Objects.requireNonNull(preset, "preset")) {
            case PERFORMANCE -> PERFORMANCE_ATLAS_BYTES;
            case BALANCED -> BALANCED_ATLAS_BYTES;
            case ULTRA -> ULTRA_ATLAS_BYTES;
        };
        return new Budget(
                atlasBytes,
                descriptorRingBytes(),
                Math.addExact(atlasBytes, descriptorRingBytes())
        );
    }

    public static boolean supportsPageEdge(final int edge) {
        return PAGE_EDGES.contains(edge);
    }

    /** Exact payload bytes, before the fixed Metal buffer alignment is applied. */
    public static long pagePayloadBytes(final int edge) {
        requirePageEdge(edge);
        return Math.multiplyExact(
                Math.multiplyExact(
                        Math.multiplyExact((long) edge, edge),
                        FACE_COUNT
                ),
                Math.multiplyExact(LAYER_COUNT, HIT_STRIDE_BYTES)
        );
    }

    /** Bytes reserved in the atlas; all currently supported page payloads are already aligned. */
    public static long pageAllocationBytes(final int edge) {
        return alignUp(pagePayloadBytes(edge), PAGE_ALIGNMENT_BYTES);
    }

    public static long descriptorRingBytes() {
        return Math.multiplyExact(
                Math.multiplyExact((long) MAX_LIGHT_DESCRIPTORS, DESCRIPTOR_STRIDE_BYTES),
                DESCRIPTOR_RING_SLOTS
        );
    }

    private static void requirePageEdge(final int edge) {
        if (!supportsPageEdge(edge)) {
            throw new IllegalArgumentException("Unsupported resident local-shadow page edge: " + edge);
        }
    }

    private static long alignUp(final long value, final long alignment) {
        if (value <= 0L || alignment <= 0L || alignment != PAGE_ALIGNMENT_BYTES) {
            throw new IllegalArgumentException("Invalid resident local-shadow atlas alignment");
        }
        long remainder = value % alignment;
        return remainder == 0L ? value : Math.addExact(value, alignment - remainder);
    }
}
