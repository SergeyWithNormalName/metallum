package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.light.data.QuadLightData;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, normalized relight recipes for one Sodium render section.
 *
 * <p>Recipes remain bucketed by pass and {@link ModelQuadFacing}. A replay is
 * flattened only after validating the current {@link BuiltSectionMeshParts}
 * vertex segments, which makes the result byte-for-byte comparable with a full
 * mesh even when Sodium chooses a different legal segment order.</p>
 */
public final class SodiumRelightPlan {
    private static final int FACING_COUNT = ModelQuadFacing.COUNT;
    private static final int SEGMENT_ENTRY_COUNT = FACING_COUNT * 2;
    private static final Pass[] PASSES = Pass.values();
    private static final int ESTIMATED_BASE_BYTES = 512;
    private static final int ESTIMATED_RECIPE_BYTES = 256;

    private final SodiumRelightQuadRecipe[][] buckets;
    @Nullable
    private final SodiumRelightTopologySnapshot topologySnapshot;
    private final long estimatedRetainedBytes;

    private SodiumRelightPlan(
            final SodiumRelightQuadRecipe[][] buckets,
            @Nullable final SodiumRelightTopologySnapshot topologySnapshot
    ) {
        this(buckets, topologySnapshot, false);
    }

    private SodiumRelightPlan(
            final SodiumRelightQuadRecipe[][] buckets,
            @Nullable final SodiumRelightTopologySnapshot topologySnapshot,
            final boolean trustedImmutableBuckets
    ) {
        this.buckets = trustedImmutableBuckets ? buckets : deepCopy(buckets);
        this.topologySnapshot = topologySnapshot;
        long recipeCount = 0L;
        for (SodiumRelightQuadRecipe[] bucket : this.buckets) {
            recipeCount = Math.addExact(recipeCount, bucket.length);
        }
        this.estimatedRetainedBytes = Math.addExact(
                Math.addExact(
                        ESTIMATED_BASE_BYTES,
                        topologySnapshot == null ? 0L : topologySnapshot.estimatedRetainedBytes()
                ),
                Math.multiplyExact(recipeCount, ESTIMATED_RECIPE_BYTES)
        );
    }

    public long estimatedRetainedBytes() {
        return this.estimatedRetainedBytes;
    }

    @Nullable
    public SodiumRelightTopologySnapshot topologySnapshot() {
        return this.topologySnapshot;
    }

    /** Adds the exact topology without copying the already immutable recipe buckets. */
    public SodiumRelightPlan withTopologySnapshot(
            final SodiumRelightTopologySnapshot topologySnapshot
    ) {
        if (this.topologySnapshot != null) {
            throw new IllegalStateException("relight plan already has a topology snapshot");
        }
        return new SodiumRelightPlan(
                this.buckets,
                Objects.requireNonNull(topologySnapshot, "topologySnapshot"),
                true
        );
    }

    public int quadCount() {
        int count = 0;
        for (SodiumRelightQuadRecipe[] bucket : this.buckets) {
            count = Math.addExact(count, bucket.length);
        }
        return count;
    }

    public int quadCount(final Pass pass, final ModelQuadFacing facing) {
        return this.bucket(pass, facing).length;
    }

    /**
     * Replays through Sodium's current light pipelines and current mesh order.
     * Layout rejection is fail-closed and does not invoke a light pipeline.
     */
    public ReplayResult replay(
            final LightPipelineProvider provider,
            @Nullable final MeshLayout solid,
            @Nullable final MeshLayout cutout,
            @Nullable final MeshLayout translucent
    ) {
        Objects.requireNonNull(provider, "provider");
        Validation validation = this.validateLayouts(solid, cutout, translucent);
        if (!validation.accepted()) {
            return ReplayResult.rejected(validation.reason());
        }

        QuadLightData scratch = new QuadLightData();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        byte[][] lightBytes = new byte[PASSES.length][];
        for (Pass pass : PASSES) {
            SodiumRelightQuadRecipe[] recipes = validation.ordered()[pass.ordinal()];
            byte[] output = new byte[Math.multiplyExact(
                    Math.multiplyExact(recipes.length, SodiumRelightQuadRecipe.VERTEX_COUNT),
                    SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
            )];
            int outputOffset = 0;
            for (SodiumRelightQuadRecipe recipe : recipes) {
                LightPipeline pipeline = Objects.requireNonNull(
                        provider.getLighter(recipe.lightMode()),
                        "Sodium light pipeline"
                );
                pipeline.calculate(
                        recipe,
                        blockPos.set(recipe.blockPosLong()),
                        scratch,
                        recipe.cullFace(),
                        recipe.getLightFace(),
                        recipe.shade(),
                        recipe.enhancedShade()
                );
                for (int vertex = 0; vertex < SodiumRelightQuadRecipe.VERTEX_COUNT; vertex++) {
                    int light = recipe.emissive()
                            ? LightCoordsUtil.FULL_BRIGHT
                            : maxBrightness(recipe.getLight(vertex), scratch.lm[vertex]);
                    output[outputOffset++] = (byte) encodeCoordinate(light);
                    output[outputOffset++] = (byte) encodeCoordinate(light >>> 16);
                }
            }
            lightBytes[pass.ordinal()] = output;
        }
        return ReplayResult.accepted(new Replay(lightBytes));
    }

    public ReplayResult replayFromMeshes(
            final LightPipelineProvider provider,
            @Nullable final BuiltSectionMeshParts solid,
            @Nullable final BuiltSectionMeshParts cutout,
            @Nullable final BuiltSectionMeshParts translucent
    ) {
        return this.replay(
                provider,
                MeshLayout.captureNullable(solid),
                MeshLayout.captureNullable(cutout),
                MeshLayout.captureNullable(translucent)
        );
    }

    private Validation validateLayouts(
            @Nullable final MeshLayout solid,
            @Nullable final MeshLayout cutout,
            @Nullable final MeshLayout translucent
    ) {
        if (translucent != null) {
            return Validation.rejected("translucent terrain mesh is not replay-safe");
        }

        SodiumRelightQuadRecipe[][] ordered = new SodiumRelightQuadRecipe[PASSES.length][];
        MeshLayout[] layouts = {solid, cutout};
        for (Pass pass : PASSES) {
            Flatten flatten = this.flatten(pass, layouts[pass.ordinal()]);
            if (!flatten.accepted()) {
                return Validation.rejected(pass.name().toLowerCase() + ": " + flatten.reason());
            }
            ordered[pass.ordinal()] = flatten.recipes();
        }
        return Validation.accepted(ordered);
    }

    private Flatten flatten(final Pass pass, @Nullable final MeshLayout layout) {
        int passOffset = pass.ordinal() * FACING_COUNT;
        if (layout == null) {
            for (int facing = 0; facing < FACING_COUNT; facing++) {
                if (this.buckets[passOffset + facing].length != 0) {
                    return Flatten.rejected("recipes exist without a mesh");
                }
            }
            return Flatten.accepted(new SodiumRelightQuadRecipe[0]);
        }

        int[] segments = layout.vertexSegments();
        if (segments.length != SEGMENT_ENTRY_COUNT) {
            return Flatten.rejected(
                    "invalid vertex segment length " + segments.length + " != " + SEGMENT_ENTRY_COUNT
            );
        }
        if (layout.geometryBytes() <= 0
                || layout.geometryBytes() % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0) {
            return Flatten.rejected("invalid compact geometry length " + layout.geometryBytes());
        }

        int expectedVertices = layout.geometryBytes() / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
        boolean[] seen = new boolean[FACING_COUNT];
        SodiumRelightQuadRecipe[] ordered = new SodiumRelightQuadRecipe[
                expectedVertices / SodiumRelightQuadRecipe.VERTEX_COUNT
        ];
        int orderedQuads = 0;
        int segmentVertices = 0;
        for (int index = 0; index < segments.length; index += 2) {
            int vertexCount = segments[index];
            int facing = segments[index + 1];
            if (vertexCount < 0) {
                return Flatten.rejected("negative vertex count at segment " + (index / 2));
            }
            if (facing < 0 || facing >= FACING_COUNT) {
                return Flatten.rejected("invalid facing " + facing + " at segment " + (index / 2));
            }
            if (seen[facing]) {
                return Flatten.rejected("duplicate facing " + facing);
            }
            seen[facing] = true;

            if (vertexCount == 0) {
                continue;
            }
            if (vertexCount % SodiumRelightQuadRecipe.VERTEX_COUNT != 0) {
                return Flatten.rejected("non-quad vertex count " + vertexCount + " at segment " + (index / 2));
            }

            SodiumRelightQuadRecipe[] bucket = this.buckets[passOffset + facing];
            int expectedQuads = vertexCount / SodiumRelightQuadRecipe.VERTEX_COUNT;
            if (bucket.length != expectedQuads) {
                return Flatten.rejected(
                        "facing " + facing + " recipe count " + bucket.length + " != " + expectedQuads
                );
            }
            System.arraycopy(bucket, 0, ordered, orderedQuads, bucket.length);
            orderedQuads += bucket.length;
            segmentVertices = Math.addExact(segmentVertices, vertexCount);
        }
        if (segmentVertices != expectedVertices) {
            return Flatten.rejected(
                    "segment vertices " + segmentVertices + " != geometry vertices " + expectedVertices
            );
        }
        if (orderedQuads != ordered.length) {
            return Flatten.rejected(
                    "ordered quads " + orderedQuads + " != geometry quads " + ordered.length
            );
        }
        for (int facing = 0; facing < FACING_COUNT; facing++) {
            if (!seen[facing] && this.buckets[passOffset + facing].length != 0) {
                return Flatten.rejected("extra recipes for facing " + facing);
            }
        }
        return Flatten.accepted(ordered);
    }

    private SodiumRelightQuadRecipe[] bucket(final Pass pass, final ModelQuadFacing facing) {
        Objects.requireNonNull(pass, "pass");
        Objects.requireNonNull(facing, "facing");
        return this.buckets[pass.ordinal() * FACING_COUNT + facing.ordinal()];
    }

    private static int maxBrightness(final int original, final int calculated) {
        return Math.max(original & 0xffff, calculated & 0xffff)
                | Math.max(original & 0xffff0000, calculated & 0xffff0000);
    }

    /** Exact copy of CompactChunkVertex's light-coordinate rounding and clamp. */
    public static int encodeCoordinate(final int coordinate) {
        return Math.max(8, Math.min(248, (coordinate & 0xff) + 8));
    }

    private static SodiumRelightQuadRecipe[][] deepCopy(final SodiumRelightQuadRecipe[][] source) {
        SodiumRelightQuadRecipe[][] copy = new SodiumRelightQuadRecipe[source.length][];
        for (int index = 0; index < source.length; index++) {
            copy[index] = source[index].clone();
            for (SodiumRelightQuadRecipe recipe : copy[index]) {
                Objects.requireNonNull(recipe, "relight recipe");
            }
        }
        return copy;
    }

    public enum Pass {
        SOLID,
        CUTOUT
    }

    /** Immutable copy of the mesh fields that determine flattened vertex order. */
    public static final class MeshLayout {
        private final int geometryBytes;
        private final int[] vertexSegments;

        private MeshLayout(final int geometryBytes, final int[] vertexSegments) {
            this.geometryBytes = geometryBytes;
            this.vertexSegments = vertexSegments.clone();
        }

        public static MeshLayout capture(final BuiltSectionMeshParts mesh) {
            Objects.requireNonNull(mesh, "mesh");
            return new MeshLayout(mesh.getVertexData().getLength(), mesh.getVertexSegments());
        }

        @Nullable
        private static MeshLayout captureNullable(@Nullable final BuiltSectionMeshParts mesh) {
            return mesh == null ? null : capture(mesh);
        }

        /** Public for dependency-free validation without allocating a Sodium NativeBuffer. */
        public static MeshLayout of(final int geometryBytes, final int[] vertexSegments) {
            Objects.requireNonNull(vertexSegments, "vertexSegments");
            return new MeshLayout(geometryBytes, vertexSegments);
        }

        public int geometryBytes() {
            return this.geometryBytes;
        }

        public int[] vertexSegments() {
            return this.vertexSegments.clone();
        }
    }

    public static final class Replay {
        private final byte[][] lightBytes;

        private Replay(final byte[][] lightBytes) {
            // The constructor is private and replay owns these new arrays.
            this.lightBytes = lightBytes;
        }

        public int vertexCount(final Pass pass) {
            return this.bytes(pass).length / SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
        }

        public byte[] copyLightBytes(final Pass pass) {
            return this.bytes(pass).clone();
        }

        public void write(final Pass pass, final ByteBuffer destination) {
            Objects.requireNonNull(destination, "destination");
            byte[] bytes = this.bytes(pass);
            if (destination.remaining() < bytes.length) {
                throw new IllegalArgumentException(
                        "relight destination remaining " + destination.remaining() + " < " + bytes.length
                );
            }
            destination.put(bytes);
        }

        /** Compares only compact geometry bytes 16/17, without changing its position. */
        public Comparison compare(final Pass pass, final ByteBuffer fullMesh) {
            Objects.requireNonNull(fullMesh, "fullMesh");
            byte[] expected = this.bytes(pass);
            ByteBuffer geometry = fullMesh.duplicate();
            int expectedGeometryBytes = Math.multiplyExact(
                    expected.length / SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE,
                    SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
            );
            if (geometry.remaining() != expectedGeometryBytes) {
                return Comparison.mismatch(
                        -1,
                        "geometry bytes " + geometry.remaining() + " != " + expectedGeometryBytes
                );
            }
            int start = geometry.position();
            int vertices = expected.length / SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
            for (int vertex = 0; vertex < vertices; vertex++) {
                int expectedOffset = vertex * SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
                int geometryOffset = start
                        + vertex * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
                if (expected[expectedOffset]
                        != geometry.get(geometryOffset + SodiumLightSidecarPacking.BLOCK_LIGHT_OFFSET)
                        || expected[expectedOffset + 1]
                        != geometry.get(geometryOffset + SodiumLightSidecarPacking.SKY_LIGHT_OFFSET)) {
                    return Comparison.mismatch(vertex, "compact light bytes differ");
                }
            }
            return Comparison.match();
        }

        private byte[] bytes(final Pass pass) {
            Objects.requireNonNull(pass, "pass");
            return this.lightBytes[pass.ordinal()];
        }
    }

    public record Comparison(boolean matches, int firstMismatchVertex, String reason) {
        private static Comparison match() {
            return new Comparison(true, -1, "");
        }

        private static Comparison mismatch(final int vertex, final String reason) {
            return new Comparison(false, vertex, reason);
        }
    }

    public record ReplayResult(@Nullable Replay replay, String rejectionReason) {
        public ReplayResult {
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            if ((replay == null) == rejectionReason.isEmpty()) {
                throw new IllegalArgumentException("replay result must contain exactly one outcome");
            }
        }

        public boolean accepted() {
            return this.replay != null;
        }

        private static ReplayResult accepted(final Replay replay) {
            return new ReplayResult(Objects.requireNonNull(replay, "replay"), "");
        }

        private static ReplayResult rejected(final String reason) {
            return new ReplayResult(null, Objects.requireNonNull(reason, "reason"));
        }
    }

    public record BuildResult(@Nullable SodiumRelightPlan plan, String rejectionReason) {
        public BuildResult {
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            if ((plan == null) == rejectionReason.isEmpty()) {
                throw new IllegalArgumentException("build result must contain exactly one outcome");
            }
        }

        public boolean accepted() {
            return this.plan != null;
        }

        private static BuildResult accepted(final SodiumRelightPlan plan) {
            return new BuildResult(Objects.requireNonNull(plan, "plan"), "");
        }

        private static BuildResult rejected(final String reason) {
            return new BuildResult(null, Objects.requireNonNull(reason, "reason"));
        }
    }

    public static final class Builder {
        private final List<SodiumRelightQuadRecipe>[] buckets;
        @Nullable
        private String rejectionReason;

        @SuppressWarnings("unchecked")
        public Builder() {
            this.buckets = (List<SodiumRelightQuadRecipe>[]) new List<?>[PASSES.length * FACING_COUNT];
            for (int index = 0; index < this.buckets.length; index++) {
                this.buckets[index] = new ArrayList<>();
            }
        }

        public Builder add(
                final Pass pass,
                final ModelQuadFacing facing,
                final SodiumRelightQuadRecipe recipe
        ) {
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(facing, "facing");
            Objects.requireNonNull(recipe, "recipe");
            this.buckets[pass.ordinal() * FACING_COUNT + facing.ordinal()].add(recipe);
            return this;
        }

        /** Convenience bridge for BlockRenderer.bufferQuad capture. */
        public Builder add(
                final Material material,
                final ModelQuadFacing facing,
                final SodiumRelightQuadRecipe recipe
        ) {
            Objects.requireNonNull(material, "material");
            if (material.pass == DefaultTerrainRenderPasses.SOLID) {
                return this.add(Pass.SOLID, facing, recipe);
            }
            if (material.pass == DefaultTerrainRenderPasses.CUTOUT) {
                return this.add(Pass.CUTOUT, facing, recipe);
            }
            return this.reject("unsupported terrain pass");
        }

        public Builder reject(final String reason) {
            if (this.rejectionReason == null) {
                this.rejectionReason = Objects.requireNonNull(reason, "reason");
            }
            return this;
        }

        public BuildResult build(
                @Nullable final MeshLayout solid,
                @Nullable final MeshLayout cutout,
                @Nullable final MeshLayout translucent
        ) {
            return this.buildInternal(null, solid, cutout, translucent);
        }

        /** Production overload that binds the captured topology to the immutable plan. */
        public BuildResult build(
                final SodiumRelightTopologySnapshot topologySnapshot,
                @Nullable final MeshLayout solid,
                @Nullable final MeshLayout cutout,
                @Nullable final MeshLayout translucent
        ) {
            return this.buildInternal(
                    Objects.requireNonNull(topologySnapshot, "topologySnapshot"),
                    solid,
                    cutout,
                    translucent
            );
        }

        private BuildResult buildInternal(
                @Nullable final SodiumRelightTopologySnapshot topologySnapshot,
                @Nullable final MeshLayout solid,
                @Nullable final MeshLayout cutout,
                @Nullable final MeshLayout translucent
        ) {
            if (this.rejectionReason != null) {
                return BuildResult.rejected(this.rejectionReason);
            }
            SodiumRelightQuadRecipe[][] normalized = new SodiumRelightQuadRecipe[this.buckets.length][];
            int recipes = 0;
            for (int index = 0; index < this.buckets.length; index++) {
                normalized[index] = this.buckets[index].toArray(SodiumRelightQuadRecipe[]::new);
                recipes = Math.addExact(recipes, normalized[index].length);
            }
            if (recipes == 0) {
                return BuildResult.rejected("empty relight plan");
            }

            SodiumRelightPlan plan = new SodiumRelightPlan(normalized, topologySnapshot);
            Validation validation = plan.validateLayouts(solid, cutout, translucent);
            return validation.accepted()
                    ? BuildResult.accepted(plan)
                    : BuildResult.rejected(validation.reason());
        }

        public BuildResult buildFromMeshes(
                @Nullable final BuiltSectionMeshParts solid,
                @Nullable final BuiltSectionMeshParts cutout,
                @Nullable final BuiltSectionMeshParts translucent
        ) {
            return this.build(
                    MeshLayout.captureNullable(solid),
                    MeshLayout.captureNullable(cutout),
                    MeshLayout.captureNullable(translucent)
            );
        }

        /** Production bridge that captures layouts while retaining the topology snapshot. */
        public BuildResult buildFromMeshes(
                final SodiumRelightTopologySnapshot topologySnapshot,
                @Nullable final BuiltSectionMeshParts solid,
                @Nullable final BuiltSectionMeshParts cutout,
                @Nullable final BuiltSectionMeshParts translucent
        ) {
            return this.build(
                    Objects.requireNonNull(topologySnapshot, "topologySnapshot"),
                    MeshLayout.captureNullable(solid),
                    MeshLayout.captureNullable(cutout),
                    MeshLayout.captureNullable(translucent)
            );
        }
    }

    private record Validation(
            boolean accepted,
            SodiumRelightQuadRecipe[][] ordered,
            String reason
    ) {
        private static Validation accepted(final SodiumRelightQuadRecipe[][] ordered) {
            return new Validation(true, ordered, "");
        }

        private static Validation rejected(final String reason) {
            return new Validation(false, new SodiumRelightQuadRecipe[0][], reason);
        }
    }

    private record Flatten(
            boolean accepted,
            SodiumRelightQuadRecipe[] recipes,
            String reason
    ) {
        private static Flatten accepted(final SodiumRelightQuadRecipe[] recipes) {
            return new Flatten(true, recipes, "");
        }

        private static Flatten rejected(final String reason) {
            return new Flatten(false, new SodiumRelightQuadRecipe[0], reason);
        }
    }
}
