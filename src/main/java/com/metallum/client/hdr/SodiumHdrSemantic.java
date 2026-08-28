package com.metallum.client.hdr;

import com.metallum.Metallum;
import com.metallum.client.gi.receiver.CompactPositionCarrierSafety;
import com.metallum.client.gi.receiver.GiReceiverRuntime;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import com.metallum.client.lighting.reflection.VertexReflectionExperiment;
import com.metallum.client.lighting.reflection.VoxelReflectionFace;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SodiumHdrSemantic {
    public static final int SURFACE_CLASS_NONE = 0;
    public static final int SURFACE_CLASS_METAL = 1;
    public static final int SURFACE_CLASS_SMOOTH_DIELECTRIC = 2;
    public static final int SURFACE_CLASS_WATER = 3;
    public static final int SURFACE_CLASS_GLASS = 4;
    public static final int SURFACE_CLASS_STONE = 5;
    public static final int SURFACE_CLASS_WOOD = 6;
    public static final int SURFACE_CLASS_POROUS = 7;
    public static final int SURFACE_CLASS_DIELECTRIC = 8;

    /** Internal-only bits carried by the temporary Sodium vertices before final packing. */
    private static final int SURFACE_CLASS_SHIFT = 5;
    private static final int SURFACE_CLASS_MASK = 0x0f << SURFACE_CLASS_SHIFT;
    /** Internal-only submerged bits carried on temporary vertices before final packing. */
    public static final int SUBMERGED_SHIFT = 9;
    public static final int SUBMERGED_BIT = 1 << SUBMERGED_SHIFT;
    public static final int SUBMERGED_DEPTH_SHIFT = 10;
    public static final int SUBMERGED_DEPTH_MASK = 0x3f;
    public static final int PACKED_MATERIAL_SUBMERGED_BIT = 1 << 8;
    public static final int PACKED_MATERIAL_DEPTH_SHIFT = 9;
    public static final int PACKED_MATERIAL_DEPTH_MASK = 0x3f;
    /** Internal-only dominant material face bits retained until the compact vertex is encoded. */
    private static final int REFLECTION_FACE_SHIFT = 16;
    private static final int REFLECTION_FACE_MASK = 0x3f << REFLECTION_FACE_SHIFT;
    /** Strict G5 axis face; kept separate so L8 may retain its dominant-face approximation. */
    private static final int GI_AXIS_FACE_SHIFT = 22;
    private static final int GI_AXIS_FACE_MASK = 0x3f << GI_AXIS_FACE_SHIFT;
    /**
     * Sodium 0.9.1 CompactChunkVertex stores two 30-bit position words at offsets 0 and 4.
     * Its shader masks only the lower 30 bits of each word. The four version-locked spare bits
     * carry face 1..6 in bits 0..2 and the strict G5-axis flag in bit 3.
     */
    public static final int COMPACT_VERTEX_STRIDE = 20;
    public static final int COMPACT_QUAD_VERTEX_COUNT = 4;
    public static final int POSITION_CARRIER_G5_BIT = 0x8;
    public static final int POSITION_CARRIER_FACE_MASK = 0x7;
    private static final int POSITION_SPARE_MASK = 0xc000_0000;
    private static final int POSITION_PAYLOAD_MASK = 0x3fff_ffff;

    /** Version-locked unused base values in Sodium 0.9.1's current block shaders. */
    private static final int MATERIAL_BASE_METAL = 2;
    private static final int MATERIAL_BASE_SMOOTH_DIELECTRIC = 4;
    private static final int MATERIAL_BASE_WATER = 3;
    private static final int MATERIAL_BASE_GLASS = 6;
    private static final int MATERIAL_BASE_STONE = 1;
    private static final int MATERIAL_BASE_WOOD = 5;
    private static final int MATERIAL_BASE_POROUS = 7;
    private static final int MATERIAL_BASE_DIELECTRIC = 0;
    private static final AtomicBoolean ACTIVE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean MATERIAL_CONFLICT_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean POSITION_CARRIER_CONFLICT_LOGGED = new AtomicBoolean();
    private static final AtomicLong G5_CARRIER_WRITES = new AtomicLong();
    private static volatile Boolean reflectionFaceCarrierEnabled;
    private static final int AMETHYST_GROWTH_SURFACE_EMISSION = 2;
    /**
     * Gamma 2.5 remap of Minecraft's 0..15 light-source levels. Keeping it
     * as a lookup table makes terrain meshing allocation-free and ensures
     * level 15 remains the exact HDR reference point.
     */
    private static final int[] PARTIAL_OVERLAY_EMISSION_CURVE = {
            0, 1, 1, 1, 1, 1, 2, 2, 3, 4, 5, 7, 9, 10, 13, 15
    };

    private SodiumHdrSemantic() {
    }

    /**
     * Returns the HDR semantic used for a block's visible surface. This is deliberately
     * independent from MinecraftLightPolicy: adjusting a material's apparent brightness
     * must never change its direct-light contribution to the world.
     */
    public static int surfaceEmission(
            final BlockState state,
            final int blockLightEmission,
            final boolean exact
    ) {
        if (exact) {
            return 15;
        }

        int emission = Math.clamp(blockLightEmission, 0, 15);
        if (state != null && isAmethystGrowth(state)) {
            return Math.min(emission, AMETHYST_GROWTH_SURFACE_EMISSION);
        }
        return emission;
    }

    /**
     * Decides whether a terrain quad uses the exact HDR-emission semantic.
     * Partial-emission textures are written twice: their base pass is normally
     * lit, while the immediately following mask overlay is exact-emissive.
     *
     * <p>This stays allocation-free because Sodium calls it for every terrain
     * quad.</p>
     */
    public static boolean isExactTerrainQuad(
            final boolean quadExactEmission,
            final boolean hasPartialEmissionOverlay,
            final boolean bufferingPartialEmissionOverlay
    ) {
        return bufferingPartialEmissionOverlay || (quadExactEmission && !hasPartialEmissionOverlay);
    }

    /**
     * Resolves surface emission for either half of a partial-emission terrain
     * pair. The base must not emit. The overlay retains exact-HDR treatment
     * but uses the block state's emission strength whenever one exists, so a
     * low-power source (such as a redstone torch) is visibly weaker than a
     * normal torch. Purely visual authored sidecars on non-emitting blocks
     * retain the established full-strength fallback.
     */
    public static int terrainQuadSurfaceEmission(
            final BlockState state,
            final int blockLightEmission,
            final boolean quadExactEmission,
            final boolean hasPartialEmissionOverlay,
            final boolean bufferingPartialEmissionOverlay
    ) {
        if (hasPartialEmissionOverlay && !bufferingPartialEmissionOverlay) {
            return 0;
        }
        if (bufferingPartialEmissionOverlay) {
            int sourceEmission = Math.clamp(blockLightEmission, 0, 15);
            return sourceEmission > 0 ? partialOverlayEmissionStrength(sourceEmission) : 15;
        }

        return surfaceEmission(
                state,
                blockLightEmission,
                isExactTerrainQuad(
                        quadExactEmission,
                        hasPartialEmissionOverlay,
                        bufferingPartialEmissionOverlay
                )
        );
    }

    static int partialOverlayEmissionStrength(final int blockLightEmission) {
        return PARTIAL_OVERLAY_EMISSION_CURVE[Math.clamp(blockLightEmission, 0, 15)];
    }

    /**
     * Converts the CPU material policy to the compact terrain semantic.
     *
     * <p>Water must retain its own class even on a submerged quad.  Otherwise the water
     * interface is encoded as a caustic receiver and its alpha carries a fictitious water
     * depth, producing a dark block-shaped overlay on the water surface.</p>
     */
    public static int terrainSurfaceClass(
            final SurfaceMaterialPolicy.Kind kind,
            final boolean upwardFace,
            final boolean rainExposed
    ) {
        if (kind == null) {
            return SURFACE_CLASS_NONE;
        }
        return switch (kind) {
            // Intrinsic optics are material properties and therefore survive on every face.
            // Rain exposure is needed only for otherwise dry material families.
            case METAL -> SURFACE_CLASS_METAL;
            case SMOOTH_DIELECTRIC -> SURFACE_CLASS_SMOOTH_DIELECTRIC;
            case WATER -> SURFACE_CLASS_WATER;
            case GLASS -> SURFACE_CLASS_GLASS;
            case STONE -> rainExposed ? SURFACE_CLASS_STONE : SURFACE_CLASS_NONE;
            case WOOD -> rainExposed ? SURFACE_CLASS_WOOD : SURFACE_CLASS_NONE;
            case POROUS -> rainExposed ? SURFACE_CLASS_POROUS : SURFACE_CLASS_NONE;
            case DIELECTRIC -> rainExposed ? SURFACE_CLASS_DIELECTRIC : SURFACE_CLASS_NONE;
        };
    }

    private static boolean isAmethystGrowth(final BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD)
                || state.is(Blocks.MEDIUM_AMETHYST_BUD)
                || state.is(Blocks.LARGE_AMETHYST_BUD)
                || state.is(Blocks.AMETHYST_CLUSTER);
    }

    public static void tagQuad(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int lightEmission,
            final boolean exact
    ) {
        tagQuad(vertices, lightEmission, exact, SURFACE_CLASS_NONE, false, 0, 0);
    }

    public static void tagQuad(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int lightEmission,
            final boolean exact,
            final int surfaceClass
    ) {
        tagQuad(vertices, lightEmission, exact, surfaceClass, false, 0, 0);
    }

    /**
     * Carries the remesh-time L8 surface class and submerged water-column information in
     * temporary vertex-only bits. Final packing uses the conditional exact-emission bit plus
     * currently unused Sodium base combinations and upper material bits.
     */
    public static void tagQuad(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int lightEmission,
            final boolean exact,
            final int surfaceClass,
            final boolean submerged,
            final int submergedDepth
    ) {
        tagQuad(vertices, lightEmission, exact, surfaceClass, submerged, submergedDepth, 0);
    }

    public static void tagQuad(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int lightEmission,
            final boolean exact,
            final int surfaceClass,
            final boolean submerged,
            final int submergedDepth,
            final int reflectionFaceBit
    ) {
        tagQuad(
                vertices, lightEmission, exact, surfaceClass, submerged, submergedDepth,
                reflectionFaceBit, 0
        );
    }

    /**
     * Tags the independent reflection and G5 face contracts. The reflection face may be a
     * dominant-axis approximation; {@code giAxisFaceBit} must be an exact axis-aligned unit
     * normal or zero.
     */
    public static void tagQuad(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int lightEmission,
            final boolean exact,
            final int surfaceClass,
            final boolean submerged,
            final int submergedDepth,
            final int reflectionFaceBit,
            final int giAxisFaceBit
    ) {
        int semantic = SodiumHdrShaderPatcher.encodeVertexSemantic(lightEmission, exact);
        int boundedSurfaceClass = Math.clamp(
                surfaceClass, SURFACE_CLASS_NONE, SURFACE_CLASS_DIELECTRIC);
        if (semantic == 0 && boundedSurfaceClass != SURFACE_CLASS_NONE) {
            semantic = SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT
                    | (boundedSurfaceClass << SURFACE_CLASS_SHIFT);
        }
        // The face is a temporary geometric carrier, independent of whether this quad also has
        // an L8 material semantic. Reflection still consumes it only for material receivers;
        // G5 needs it on every supported Sodium terrain face.
        if (reflectionFaceBit != 0) {
            VoxelReflectionFace.index(reflectionFaceBit);
            semantic |= reflectionFaceBit << REFLECTION_FACE_SHIFT;
        }
        if (giAxisFaceBit != 0) {
            VoxelReflectionFace.index(giAxisFaceBit);
            semantic |= giAxisFaceBit << GI_AXIS_FACE_SHIFT;
        }
        // A water interface transmits/refracts the caustic; it is never its receiver. Encoding
        // a depth on it makes the receiver shader modulate the visible surface itself, producing
        // dark block-shaped caustic silhouettes that look like broken reflections.
        boolean causticReceiver = submerged && boundedSurfaceClass != SURFACE_CLASS_WATER;
        if (causticReceiver) {
            int boundedDepth = Math.clamp(submergedDepth, 1, 63);
            semantic |= SUBMERGED_BIT | (boundedDepth << SUBMERGED_DEPTH_SHIFT);
            int alpha = 255 - boundedDepth;
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                if (vertex instanceof HdrEmissionVertex hdrVertex) {
                    hdrVertex.metallum$setHdrSemantic(semantic);
                }
                vertex.color = (vertex.color & 0x00FFFFFF) | (alpha << 24);
            }
        } else {
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                if (vertex instanceof HdrEmissionVertex hdrVertex) {
                    hdrVertex.metallum$setHdrSemantic(semantic);
                }
            }
        }
        if ((semantic & SodiumHdrShaderPatcher.HDR_VERTEX_EMISSION_MASK) != 0
                && ACTIVE_LOGGED.compareAndSet(false, true)) {
            Metallum.LOGGER.info(
                    "Sodium semantic HDR emission tagging is active (strength {}, exact {})",
                    semantic & SodiumHdrShaderPatcher.HDR_VERTEX_EMISSION_MASK,
                    exact
            );
        }
    }

    /**
     * Restores the temporary per-quad semantic after Sodium 0.9.1 creates an interior vertex
     * while splitting translucent geometry for its BSP. Sodium interpolates the authored vertex
     * attributes through {@code Vertex.writeVertex}; that call deliberately clears our temporary
     * semantic so a reused vertex can never retain stale state. The split may inherit a semantic
     * only when both edge endpoints agree exactly.
     *
     * <p>The fixed three-destination signature mirrors the pinned Sodium interpolation helper and
     * keeps the translucent meshing path allocation-free. A mismatched edge is cleared and, when
     * the compact-position carrier is active, terminally rejects that carrier generation.</p>
     */
    public static int bspVertexSemanticSnapshot(final ChunkVertexEncoder.Vertex endpoint) {
        return vertexSemantic(endpoint);
    }

    public static boolean propagateBspInterpolatedSemantic(
            final int semanticA,
            final int semanticB,
            final ChunkVertexEncoder.Vertex destinationA,
            final ChunkVertexEncoder.Vertex destinationB,
            final ChunkVertexEncoder.Vertex destinationC
    ) {
        boolean matches = semanticA == semanticB;
        int inheritedSemantic = matches ? semanticA : 0;
        setVertexSemantic(destinationA, inheritedSemantic);
        setVertexSemantic(destinationB, inheritedSemantic);
        setVertexSemantic(destinationC, inheritedSemantic);
        if (!matches && compactPositionCarrierRequested()) {
            reportPositionCarrierConflict(
                    "Sodium translucent BSP edge has mismatched endpoint semantics",
                    0
            );
        }
        return matches;
    }

    public static int packMaterialBits(
            final int materialBits,
            final ChunkVertexEncoder.Vertex[] vertices
    ) {
        if ((materialBits & ~SodiumHdrShaderPatcher.SODIUM_MATERIAL_BASE_MASK) != 0) {
            if (MATERIAL_CONFLICT_LOGGED.compareAndSet(false, true)) {
                Metallum.LOGGER.warn(
                        "Sodium uses terrain material bits reserved by semantic HDR; HDR tagging is disabled for conflicting quads"
                );
            }
            return materialBits;
        }

        int emission = 0;
        boolean exact = false;
        int surfaceClass = SURFACE_CLASS_NONE;
        boolean submerged = false;
        int submergedDepth = 0;
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int vertexSemantic = (vertex instanceof HdrEmissionVertex hdrVertex)
                    ? hdrVertex.metallum$getHdrSemantic()
                    : 0;
            int vertexEmission = vertexSemantic & SodiumHdrShaderPatcher.HDR_VERTEX_EMISSION_MASK;
            emission = Math.max(emission, vertexEmission);
            exact |= vertexEmission != 0
                    && (vertexSemantic & SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT) != 0;
            if (vertexEmission == 0
                    && (vertexSemantic & SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT) != 0) {
                int vertexSurfaceClass = (vertexSemantic & SURFACE_CLASS_MASK)
                        >> SURFACE_CLASS_SHIFT;
                if (surfaceClass == SURFACE_CLASS_NONE) {
                    surfaceClass = vertexSurfaceClass;
                } else if (surfaceClass != vertexSurfaceClass) {
                    surfaceClass = SURFACE_CLASS_NONE;
                    break;
                }
            }
            if ((vertexSemantic & SUBMERGED_BIT) != 0) {
                submerged = true;
                int vDepth = (vertexSemantic >> SUBMERGED_DEPTH_SHIFT) & SUBMERGED_DEPTH_MASK;
                submergedDepth = Math.max(submergedDepth, vDepth);
            }
        }
        int semantic = SodiumHdrShaderPatcher.encodeVertexSemantic(emission, exact);
        boolean materialSurface = semantic == 0 && surfaceClass != SURFACE_CLASS_NONE;
        int packedBase = materialBits;
        if (materialSurface) {
            semantic = SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT;
            packedBase = materialBaseForSurfaceClass(materialBits, surfaceClass);
        }
        int packed = SodiumHdrShaderPatcher.packMaterialBits(packedBase, semantic);
        if (submerged) {
            int depth = Math.clamp(submergedDepth, 1, 63);
            packed |= PACKED_MATERIAL_SUBMERGED_BIT | (depth << PACKED_MATERIAL_DEPTH_SHIFT);
        }
        return packed;
    }

    public static int reflectionFaceCode(final int faceBit) {
        return VoxelReflectionFace.index(faceBit) + 1;
    }

    /**
     * Resolves one quad-level sideband code without touching authored color, light or material
     * data. A strict G5 face wins over the L8 dominant-face approximation and is marked by bit 3.
     */
    public static boolean compactPositionCarrierRequested() {
        return CompactPositionCarrierSafety.isSafe()
                && (g5PositionCarrierEnabled() || reflectionFaceCarrierEnabled());
    }

    public static int compactPositionCarrierCode(
            final ChunkVertexEncoder.Vertex[] vertices
    ) {
        if (!CompactPositionCarrierSafety.isSafe()) {
            if (GiReceiverRuntime.isRequested()) {
                GiReceiverRuntime.admission().reportCarrierConflict(
                        "installed Sodium compact position layout is not the exact G5 contract"
                );
            }
            return 0;
        }
        int reflectionFaceBit = consistentFaceBit(vertices, REFLECTION_FACE_MASK,
                REFLECTION_FACE_SHIFT);
        int giAxisFaceBit = consistentFaceBit(vertices, GI_AXIS_FACE_MASK, GI_AXIS_FACE_SHIFT);
        if (reflectionFaceBit < 0 || giAxisFaceBit < 0) {
            reportPositionCarrierConflict(
                    "inconsistent per-vertex face tags cannot form one compact position carrier",
                    giAxisFaceBit != 0 ? POSITION_CARRIER_G5_BIT : 0
            );
            return 0;
        }

        if (g5PositionCarrierEnabled() && giAxisFaceBit != 0) {
            if (reflectionFaceBit != 0 && reflectionFaceBit != giAxisFaceBit
                    && reflectionFaceCarrierEnabled()) {
                reportPositionCarrierConflict(
                        "G5 exact face disagrees with the coexisting L8 terrain face",
                        POSITION_CARRIER_G5_BIT
                );
                return 0;
            }
            return POSITION_CARRIER_G5_BIT | reflectionFaceCode(giAxisFaceBit);
        }
        if (reflectionFaceBit != 0 && reflectionFaceCarrierEnabled()) {
            return reflectionFaceCode(reflectionFaceBit);
        }
        return 0;
    }

    /**
     * Installs the carrier after Sodium 0.9.1 has written one compact quad. Returns false and
     * leaves every position word unchanged when the exact 20-byte layout contract is not met.
     */
    public static boolean writeCompactPositionCarrier(
            final long pointer,
            final long endPointer,
            final int carrierCode
    ) {
        int faceCode = carrierCode & POSITION_CARRIER_FACE_MASK;
        boolean validCode = carrierCode == 0 || (faceCode >= 1 && faceCode <= 6);
        if ((carrierCode & ~0x0f) != 0 || !validCode
                || pointer == 0L
                || endPointer - pointer != (long) COMPACT_VERTEX_STRIDE * COMPACT_QUAD_VERTEX_COUNT) {
            reportPositionCarrierConflict(
                    "Sodium compact vertex layout does not match the G5/L8 position carrier contract",
                    carrierCode
            );
            return false;
        }

        for (int vertex = 0; vertex < COMPACT_QUAD_VERTEX_COUNT; vertex++) {
            long vertexPointer = pointer + (long) vertex * COMPACT_VERTEX_STRIDE;
            int positionHi = MemoryIntrinsics.getInt(vertexPointer);
            int positionLo = MemoryIntrinsics.getInt(vertexPointer + Integer.BYTES);
            if ((positionHi & POSITION_SPARE_MASK) != 0
                    || (positionLo & POSITION_SPARE_MASK) != 0) {
                reportPositionCarrierConflict(
                        "Sodium compact position spare bits are already owned",
                        carrierCode
                );
                return false;
            }
        }
        if (carrierCode == 0) {
            return true;
        }

        int positionHiBits = (carrierCode & 0x3) << 30;
        int positionLoBits = ((carrierCode >>> 2) & 0x3) << 30;
        for (int vertex = 0; vertex < COMPACT_QUAD_VERTEX_COUNT; vertex++) {
            long vertexPointer = pointer + (long) vertex * COMPACT_VERTEX_STRIDE;
            MemoryIntrinsics.putInt(
                    vertexPointer,
                    MemoryIntrinsics.getInt(vertexPointer) | positionHiBits
            );
            MemoryIntrinsics.putInt(
                    vertexPointer + Integer.BYTES,
                    MemoryIntrinsics.getInt(vertexPointer + Integer.BYTES) | positionLoBits
            );
        }
        if ((carrierCode & POSITION_CARRIER_G5_BIT) != 0) {
            G5_CARRIER_WRITES.incrementAndGet();
        }
        return true;
    }

    /** Successful exact-axis code 9..14 writes in this process; allocation-free on mesh workers. */
    public static long g5CarrierWriteCount() {
        return G5_CARRIER_WRITES.get();
    }

    public static int decodeCompactPositionCarrier(
            final int positionHi,
            final int positionLo
    ) {
        return positionHi >>> 30 | (positionLo >>> 30) << 2;
    }

    public static int compactPositionPayload(final int positionWord) {
        return positionWord & POSITION_PAYLOAD_MASK;
    }

    private static int vertexSemantic(final ChunkVertexEncoder.Vertex vertex) {
        return vertex instanceof HdrEmissionVertex hdrVertex
                ? hdrVertex.metallum$getHdrSemantic() : 0;
    }

    private static void setVertexSemantic(
            final ChunkVertexEncoder.Vertex vertex,
            final int semantic
    ) {
        if (vertex instanceof HdrEmissionVertex hdrVertex) {
            hdrVertex.metallum$setHdrSemantic(semantic);
        }
    }

    private static int consistentFaceBit(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int mask,
            final int shift
    ) {
        int faceBit = -1;
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int semantic = vertexSemantic(vertex);
            int vertexFaceBit = (semantic & mask) >>> shift;
            if (vertexFaceBit != 0) {
                try {
                    VoxelReflectionFace.index(vertexFaceBit);
                } catch (IllegalArgumentException invalidFace) {
                    return -1;
                }
            }
            if (faceBit < 0) {
                faceBit = vertexFaceBit;
            } else if (faceBit != vertexFaceBit) {
                return -1;
            }
        }
        return Math.max(faceBit, 0);
    }

    private static void reportPositionCarrierConflict(
            final String reason,
            final int carrierCode
    ) {
        CompactPositionCarrierSafety.reportCarrierSkip(reason);
        if (POSITION_CARRIER_CONFLICT_LOGGED.compareAndSet(false, true)) {
            Metallum.LOGGER.warn(
                    "{} (carrier_code={}); affected vertex-field receivers were disabled fail-closed",
                    reason, carrierCode
            );
        }
    }

    private static boolean reflectionFaceCarrierEnabled() {
        Boolean current = reflectionFaceCarrierEnabled;
        if (current != null) {
            return current;
        }
        synchronized (SodiumHdrSemantic.class) {
            if (reflectionFaceCarrierEnabled == null) {
                // The option is explicitly restart-gated. Cache it once so the terrain quad hot
                // path never performs repeated property, environment, or config-file lookups.
                reflectionFaceCarrierEnabled = VertexReflectionExperiment.isLayoutEnabled();
            }
            return reflectionFaceCarrierEnabled;
        }
    }

    private static boolean g5PositionCarrierEnabled() {
        return GiReceiverRuntime.isRequested()
                && GiReceiverRuntime.admission().carrierSafe();
    }

    /** Final version-locked Sodium base used by one non-emissive L8 surface class. */
    public static int materialBaseForSurfaceClass(
            final int originalMaterialBits,
            final int surfaceClass
    ) {
        return switch (surfaceClass) {
            case SURFACE_CLASS_METAL -> MATERIAL_BASE_METAL;
            case SURFACE_CLASS_SMOOTH_DIELECTRIC -> MATERIAL_BASE_SMOOTH_DIELECTRIC;
            case SURFACE_CLASS_WATER -> MATERIAL_BASE_WATER;
            case SURFACE_CLASS_GLASS -> MATERIAL_BASE_GLASS;
            case SURFACE_CLASS_STONE -> MATERIAL_BASE_STONE;
            case SURFACE_CLASS_WOOD -> MATERIAL_BASE_WOOD;
            case SURFACE_CLASS_POROUS -> MATERIAL_BASE_POROUS;
            case SURFACE_CLASS_DIELECTRIC -> MATERIAL_BASE_DIELECTRIC;
            default -> originalMaterialBits;
        };
    }
}
