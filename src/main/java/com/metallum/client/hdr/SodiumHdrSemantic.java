package com.metallum.client.hdr;

import com.metallum.Metallum;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicBoolean;

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
        tagQuad(vertices, lightEmission, exact, SURFACE_CLASS_NONE, false, 0);
    }

    public static void tagQuad(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int lightEmission,
            final boolean exact,
            final int surfaceClass
    ) {
        tagQuad(vertices, lightEmission, exact, surfaceClass, false, 0);
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
        int semantic = SodiumHdrShaderPatcher.encodeVertexSemantic(lightEmission, exact);
        int boundedSurfaceClass = Math.clamp(
                surfaceClass, SURFACE_CLASS_NONE, SURFACE_CLASS_DIELECTRIC);
        if (semantic == 0 && boundedSurfaceClass != SURFACE_CLASS_NONE) {
            semantic = SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT
                    | (boundedSurfaceClass << SURFACE_CLASS_SHIFT);
        }
        if (submerged) {
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
        int packedBase = materialBits;
        if (semantic == 0 && surfaceClass != SURFACE_CLASS_NONE) {
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
