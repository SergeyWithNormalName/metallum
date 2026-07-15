package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable inputs needed to run Sodium's block-quad light pipeline again.
 *
 * <p>The original quad is never retained. All mutable vertex data is copied into
 * primitive arrays so a later light-only rebuild cannot observe renderer-owned
 * scratch storage.</p>
 */
public final class SodiumRelightQuadRecipe implements ModelQuadView {
    public static final int VERTEX_COUNT = 4;

    private static final int POSITION_COMPONENTS = 3;
    private static final int SHADE_BIT = 1;
    private static final int ENHANCED_SHADE_BIT = 1 << 1;
    private static final int EMISSIVE_BIT = 1 << 2;
    private static final int NULL_DIRECTION = -1;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final LightMode[] LIGHT_MODES = LightMode.values();

    private final float[] positions;
    private final int[] vertexNormals;
    private final int[] originalLights;
    private final long blockPos;
    private final int faceNormal;
    private final int flags;
    private final byte cullFace;
    private final byte lightFace;
    private final byte lightMode;
    private final byte options;

    private SodiumRelightQuadRecipe(
            final float[] positions,
            final int[] vertexNormals,
            final int[] originalLights,
            final long blockPos,
            final int faceNormal,
            final int flags,
            final int cullFace,
            final int lightFace,
            final int lightMode,
            final int options
    ) {
        // The constructor is private and capture owns these freshly allocated
        // arrays, so retaining them avoids a second copy without weakening
        // immutability.
        this.positions = positions;
        this.vertexNormals = vertexNormals;
        this.originalLights = originalLights;
        this.blockPos = blockPos;
        this.faceNormal = faceNormal;
        this.flags = flags;
        this.cullFace = checkedByte("cull face", cullFace, NULL_DIRECTION, DIRECTIONS.length - 1);
        this.lightFace = checkedByte("light face", lightFace, 0, DIRECTIONS.length - 1);
        this.lightMode = checkedByte("light mode", lightMode, 0, LIGHT_MODES.length - 1);
        this.options = checkedByte("options", options, 0, SHADE_BIT | ENHANCED_SHADE_BIT | EMISSIVE_BIT);
        requireLength("positions", this.positions.length, VERTEX_COUNT * POSITION_COMPONENTS);
        requireLength("vertex normals", this.vertexNormals.length, VERTEX_COUNT);
        requireLength("original lights", this.originalLights.length, VERTEX_COUNT);
        for (float position : this.positions) {
            if (!Float.isFinite(position)) {
                throw new IllegalArgumentException("non-finite relight recipe position: " + position);
            }
        }
    }

    public static SodiumRelightQuadRecipe capture(
            final ModelQuadView quad,
            final BlockPos blockPos,
            @Nullable final Direction cullFace,
            final Direction lightFace,
            final LightMode lightMode,
            final boolean shade,
            final boolean enhancedShade,
            final boolean emissive
    ) {
        Objects.requireNonNull(quad, "quad");
        Objects.requireNonNull(blockPos, "blockPos");
        Objects.requireNonNull(lightFace, "lightFace");
        Objects.requireNonNull(lightMode, "lightMode");

        float[] positions = new float[VERTEX_COUNT * POSITION_COMPONENTS];
        int[] vertexNormals = new int[VERTEX_COUNT];
        int[] originalLights = new int[VERTEX_COUNT];
        for (int vertex = 0; vertex < VERTEX_COUNT; vertex++) {
            int position = vertex * POSITION_COMPONENTS;
            positions[position] = quad.getX(vertex);
            positions[position + 1] = quad.getY(vertex);
            positions[position + 2] = quad.getZ(vertex);
            vertexNormals[vertex] = quad.getVertexNormal(vertex);
            originalLights[vertex] = quad.getLight(vertex);
        }

        int options = (shade ? SHADE_BIT : 0)
                | (enhancedShade ? ENHANCED_SHADE_BIT : 0)
                | (emissive ? EMISSIVE_BIT : 0);
        return new SodiumRelightQuadRecipe(
                positions,
                vertexNormals,
                originalLights,
                blockPos.asLong(),
                quad.getFaceNormal(),
                quad.getFlags(),
                cullFace == null ? NULL_DIRECTION : cullFace.ordinal(),
                lightFace.ordinal(),
                lightMode.ordinal(),
                options
        );
    }

    public BlockPos blockPos() {
        return BlockPos.of(this.blockPos);
    }

    public long blockPosLong() {
        return this.blockPos;
    }

    @Nullable
    public Direction cullFace() {
        return this.cullFace == NULL_DIRECTION ? null : DIRECTIONS[this.cullFace];
    }

    public LightMode lightMode() {
        return LIGHT_MODES[this.lightMode];
    }

    public boolean shade() {
        return (this.options & SHADE_BIT) != 0;
    }

    public boolean enhancedShade() {
        return (this.options & ENHANCED_SHADE_BIT) != 0;
    }

    public boolean emissive() {
        return (this.options & EMISSIVE_BIT) != 0;
    }

    @Override
    public float getX(final int vertex) {
        return this.positions[checkedVertex(vertex) * POSITION_COMPONENTS];
    }

    @Override
    public float getY(final int vertex) {
        return this.positions[checkedVertex(vertex) * POSITION_COMPONENTS + 1];
    }

    @Override
    public float getZ(final int vertex) {
        return this.positions[checkedVertex(vertex) * POSITION_COMPONENTS + 2];
    }

    @Override
    public int getColor(final int vertex) {
        checkedVertex(vertex);
        return 0xffffffff;
    }

    @Override
    public float getTexU(final int vertex) {
        checkedVertex(vertex);
        return 0.0f;
    }

    @Override
    public float getTexV(final int vertex) {
        checkedVertex(vertex);
        return 0.0f;
    }

    @Override
    public int getVertexNormal(final int vertex) {
        return this.vertexNormals[checkedVertex(vertex)];
    }

    @Override
    public int getFaceNormal() {
        return this.faceNormal;
    }

    @Override
    public int getLight(final int vertex) {
        return this.originalLights[checkedVertex(vertex)];
    }

    @Override
    public int getFlags() {
        return this.flags;
    }

    @Override
    public int getTintIndex() {
        return -1;
    }

    @Override
    @Nullable
    public TextureAtlasSprite getSprite() {
        return null;
    }

    @Override
    public Direction getLightFace() {
        return DIRECTIONS[this.lightFace];
    }

    @Override
    public int getMaxLightQuad(final int vertex) {
        return this.originalLights[checkedVertex(vertex)];
    }

    private static int checkedVertex(final int vertex) {
        if (vertex < 0 || vertex >= VERTEX_COUNT) {
            throw new IndexOutOfBoundsException("quad vertex: " + vertex);
        }
        return vertex;
    }

    private static byte checkedByte(
            final String name,
            final int value,
            final int minimum,
            final int maximum
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " outside " + minimum + ".." + maximum + ": " + value);
        }
        return (byte) value;
    }

    private static void requireLength(final String name, final int actual, final int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException(name + " length " + actual + " != " + expected);
        }
    }
}
