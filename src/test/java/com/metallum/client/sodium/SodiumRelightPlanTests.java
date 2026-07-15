package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess;
import net.caffeinemc.mods.sodium.client.model.light.data.QuadLightData;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Dependency-free executable tests for immutable Sodium relight recipes. */
public final class SodiumRelightPlanTests {
    private static final int FACING_COUNT = ModelQuadFacing.COUNT;

    private SodiumRelightPlanTests() {
    }

    public static void main(final String[] args) {
        testRecipeDefensivelyCopiesQuadInputs();
        testCompactLightEncodingBoundaries();
        testNormalizedBucketsReplayInCurrentSegmentOrder();
        testStrictLayoutRejection();
        testTopologySnapshotUsesExactHaloIdentities();
        testPlanOptionallyRetainsTopologySnapshot();
        testBoundedPinSafeCache();
        testResidentStateLeasePinsPlanAndMetadata();
        System.out.println("Sodium relight plan tests passed");
    }

    private static void testCompactLightEncodingBoundaries() {
        for (int coordinate = 0; coordinate <= 0xff; coordinate++) {
            int expected = Math.max(8, Math.min(248, coordinate + 8));
            require(SodiumRelightPlan.encodeCoordinate(coordinate) == expected,
                    "compact light encoding changed at " + coordinate);
            require(SodiumRelightPlan.encodeCoordinate(0x5a00 | coordinate) == expected,
                    "compact light encoding used bits outside the coordinate byte");
        }
    }

    private static void testRecipeDefensivelyCopiesQuadInputs() {
        MutableQuad quad = new MutableQuad(2.0f, 0x00300020);
        SodiumRelightQuadRecipe recipe = SodiumRelightQuadRecipe.capture(
                quad,
                new BlockPos(7, 11, -13),
                Direction.NORTH,
                Direction.UP,
                LightMode.SMOOTH,
                true,
                true,
                false
        );
        quad.positions[0] = 99.0f;
        quad.normals[0] = 123;
        quad.lights[0] = 456;

        require(recipe.getX(0) == 2.0f, "recipe retained mutable position storage");
        require(recipe.getVertexNormal(0) == 101, "recipe retained mutable normal storage");
        require(recipe.getLight(0) == 0x00300020, "recipe retained mutable light storage");
        require(recipe.getMaxLightQuad(0) == 0x00300020, "recipe changed original max-light input");
        require(recipe.blockPos().equals(new BlockPos(7, 11, -13)), "recipe changed owner position");
        require(recipe.cullFace() == Direction.NORTH, "recipe changed cull face");
        require(recipe.getLightFace() == Direction.UP, "recipe changed light face");
        require(recipe.lightMode() == LightMode.SMOOTH, "recipe changed light mode");
        require(recipe.shade() && recipe.enhancedShade() && !recipe.emissive(),
                "recipe changed shade options");
    }

    private static void testNormalizedBucketsReplayInCurrentSegmentOrder() {
        SodiumRelightQuadRecipe ordinary = recipe(1.0f, false);
        SodiumRelightQuadRecipe emissive = recipe(9.0f, true);
        SodiumRelightPlan.Builder builder = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.POS_X, ordinary)
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.NEG_X, emissive);

        SodiumRelightPlan.MeshLayout capturedOrder = layout(
                new int[][]{
                        {4, ModelQuadFacing.POS_X.ordinal()},
                        {4, ModelQuadFacing.NEG_X.ordinal()}
                }
        );
        SodiumRelightPlan.BuildResult build = builder.build(capturedOrder, null, null);
        require(build.accepted(), "valid normalized relight plan was rejected: " + build.rejectionReason());
        SodiumRelightPlan plan = requirePlan(build);

        SodiumRelightPlan.MeshLayout currentOrder = layout(
                new int[][]{
                        {4, ModelQuadFacing.NEG_X.ordinal()},
                        {4, ModelQuadFacing.POS_X.ordinal()}
                }
        );
        LightPipeline pipeline = (quad, pos, output, cullFace, lightFace, shade, enhanced) -> {
            require(pos.equals(new BlockPos(3, 5, 7)), "replay changed owner position");
            require(cullFace == Direction.SOUTH, "replay changed cull face");
            require(lightFace == Direction.UP, "replay changed light face");
            require(shade && enhanced, "replay changed shade flags");
            for (int vertex = 0; vertex < 4; vertex++) {
                output.lm[vertex] = 0x00100040;
            }
        };
        SodiumRelightPlan.ReplayResult replayResult = plan.replay(
                new SyntheticProvider(pipeline),
                currentOrder,
                null,
                null
        );
        require(replayResult.accepted(), "valid current segment order was rejected");
        SodiumRelightPlan.Replay replay = requireReplay(replayResult);
        byte[] bytes = replay.copyLightBytes(SodiumRelightPlan.Pass.SOLID);
        require(bytes.length == 16, "replay changed sidecar vertex count");

        for (int index = 0; index < 8; index++) {
            require(Byte.toUnsignedInt(bytes[index]) == 248, "emissive quad was not full-bright");
        }
        for (int vertex = 4; vertex < 8; vertex++) {
            int offset = vertex * 2;
            require(Byte.toUnsignedInt(bytes[offset]) == 0x48,
                    "block coordinate did not use exact maximum+rounding");
            require(Byte.toUnsignedInt(bytes[offset + 1]) == 0x38,
                    "sky coordinate did not use exact maximum+rounding");
        }

        ByteBuffer geometry = geometry(bytes, 5);
        int originalPosition = geometry.position();
        require(replay.compare(SodiumRelightPlan.Pass.SOLID, geometry).matches(),
                "byte-exact replay did not match a full mesh");
        require(geometry.position() == originalPosition, "comparator changed full-mesh position");
        geometry.put(originalPosition + 3, (byte) 77);
        require(replay.compare(SodiumRelightPlan.Pass.SOLID, geometry).matches(),
                "comparator included a non-light compact byte");
        geometry.put(
                originalPosition + 4 * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
                        + SodiumLightSidecarPacking.BLOCK_LIGHT_OFFSET,
                (byte) 1
        );
        SodiumRelightPlan.Comparison mismatch = replay.compare(SodiumRelightPlan.Pass.SOLID, geometry);
        require(!mismatch.matches() && mismatch.firstMismatchVertex() == 4,
                "comparator did not locate the first mismatched light vertex");
    }

    private static void testStrictLayoutRejection() {
        SodiumRelightQuadRecipe recipe = recipe(1.0f, false);

        SodiumRelightPlan.BuildResult countMismatch = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.POS_X, recipe)
                .build(layout(new int[][]{{8, ModelQuadFacing.POS_X.ordinal()}}), null, null);
        require(!countMismatch.accepted(), "quad-count mismatch entered a relight plan");

        SodiumRelightPlan.BuildResult translucent = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.POS_X, recipe)
                .build(
                        layout(new int[][]{{4, ModelQuadFacing.POS_X.ordinal()}}),
                        null,
                        layout(new int[][]{{4, ModelQuadFacing.UNASSIGNED.ordinal()}})
                );
        require(!translucent.accepted(), "translucent mesh entered a relight plan");

        int[] invalidSegments = new int[FACING_COUNT * 2];
        invalidSegments[0] = 4;
        invalidSegments[1] = FACING_COUNT;
        SodiumRelightPlan.BuildResult invalidFacing = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.POS_X, recipe)
                .build(SodiumRelightPlan.MeshLayout.of(80, invalidSegments), null, null);
        require(!invalidFacing.accepted(), "invalid facing entered a relight plan");

        int[] duplicateZeroFacing = new int[FACING_COUNT * 2];
        for (int facing = 0; facing < FACING_COUNT; facing++) {
            duplicateZeroFacing[facing * 2 + 1] = facing;
        }
        duplicateZeroFacing[3] = 0;
        SodiumRelightPlan.BuildResult duplicateFacing = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.POS_X, recipe)
                .build(SodiumRelightPlan.MeshLayout.of(80, duplicateZeroFacing), null, null);
        require(!duplicateFacing.accepted(), "duplicate zero-count facing entered a relight plan");

        SodiumRelightPlan.BuildResult extraRecipe = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.CUTOUT, ModelQuadFacing.POS_Y, recipe)
                .build(null, null, null);
        require(!extraRecipe.accepted(), "recipe without a mesh entered a relight plan");
    }

    private static void testTopologySnapshotUsesExactHaloIdentities() {
        int originX = 32;
        int originY = -16;
        int originZ = 48;
        Object[] identities = identityVolume();
        int[] calls = {0};
        int[] minimum = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] maximum = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        SodiumRelightTopologySnapshot snapshot = SodiumRelightTopologySnapshot.captureIdentities(
                originX,
                originY,
                originZ,
                (blockX, blockY, blockZ) -> {
                    calls[0]++;
                    minimum[0] = Math.min(minimum[0], blockX);
                    minimum[1] = Math.min(minimum[1], blockY);
                    minimum[2] = Math.min(minimum[2], blockZ);
                    maximum[0] = Math.max(maximum[0], blockX);
                    maximum[1] = Math.max(maximum[1], blockY);
                    maximum[2] = Math.max(maximum[2], blockZ);
                    return identityAt(identities, originX, originY, originZ, blockX, blockY, blockZ);
                }
        );

        require(calls[0] == SodiumRelightTopologySnapshot.STATE_COUNT,
                "topology snapshot did not capture every halo block");
        for (int axis = 0; axis < 3; axis++) {
            int origin = axis == 0 ? originX : axis == 1 ? originY : originZ;
            require(minimum[axis] == origin - SodiumRelightTopologySnapshot.HALO_RADIUS,
                    "topology snapshot missed the negative halo on axis " + axis);
            require(maximum[axis] == origin + SodiumRelightTopologySnapshot.SECTION_EDGE_LENGTH,
                    "topology snapshot missed the positive halo on axis " + axis);
        }
        int[] matchCalls = {0};
        require(snapshot.matchesIdentities(
                        originX,
                        originY,
                        originZ,
                        (blockX, blockY, blockZ) -> {
                            matchCalls[0]++;
                            return identityAt(
                                    identities,
                                    originX,
                                    originY,
                                    originZ,
                                    blockX,
                                    blockY,
                                    blockZ
                            );
                        }
                ),
                "unchanged exact topology identities did not match");
        require(matchCalls[0] == SodiumRelightTopologySnapshot.STATE_COUNT,
                "topology match did not compare every halo block");

        Object[] changedIdentities = identities.clone();
        int lastState = SodiumRelightTopologySnapshot.STATE_COUNT - 1;
        EqualToken equalReplacement = new EqualToken(lastState);
        require(equalReplacement.equals(changedIdentities[lastState]),
                "identity test fixture is not value-equal");
        changedIdentities[lastState] = equalReplacement;
        require(!snapshot.matchesIdentities(
                        originX,
                        originY,
                        originZ,
                        (blockX, blockY, blockZ) -> identityAt(
                                changedIdentities,
                                originX,
                                originY,
                                originZ,
                                blockX,
                                blockY,
                                blockZ
                        )
                ),
                "topology snapshot accepted an equal but non-identical state");

        int[] readsAfterOriginMismatch = {0};
        require(!snapshot.matchesIdentities(
                        originX + SodiumRelightTopologySnapshot.SECTION_EDGE_LENGTH,
                        originY,
                        originZ,
                        (blockX, blockY, blockZ) -> {
                            readsAfterOriginMismatch[0]++;
                            return identities[0];
                        }
                ),
                "topology snapshot matched a different render section origin");
        require(readsAfterOriginMismatch[0] == 0,
                "topology snapshot read states after the section origin mismatched");
        require(snapshot.estimatedRetainedBytes()
                        >= (long) SodiumRelightTopologySnapshot.STATE_COUNT * Long.BYTES,
                "topology snapshot under-reported its retained identity array");
    }

    private static void testPlanOptionallyRetainsTopologySnapshot() {
        SodiumRelightPlan legacyPlan = oneQuadPlan();
        require(legacyPlan.topologySnapshot() == null,
                "legacy builder unexpectedly attached a topology snapshot");

        int originX = 0;
        int originY = 16;
        int originZ = -32;
        Object[] identities = identityVolume();
        SodiumRelightTopologySnapshot snapshot = SodiumRelightTopologySnapshot.captureIdentities(
                originX,
                originY,
                originZ,
                (blockX, blockY, blockZ) -> identityAt(
                        identities,
                        originX,
                        originY,
                        originZ,
                        blockX,
                        blockY,
                        blockZ
                )
        );
        SodiumRelightPlan.BuildResult result = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.POS_X, recipe(1.0f, false))
                .build(
                        snapshot,
                        layout(new int[][]{{4, ModelQuadFacing.POS_X.ordinal()}}),
                        null,
                        null
                );
        require(result.accepted(), "topology-backed relight plan was rejected");
        SodiumRelightPlan topologyPlan = requirePlan(result);
        require(topologyPlan.topologySnapshot() == snapshot,
                "relight plan did not retain its immutable topology snapshot");
        require(topologyPlan.estimatedRetainedBytes()
                        == legacyPlan.estimatedRetainedBytes() + snapshot.estimatedRetainedBytes(),
                "relight plan byte estimate omitted or double-counted topology storage");
    }

    private static void testBoundedPinSafeCache() {
        SodiumRelightPlan plan = oneQuadPlan();
        long bytes = plan.estimatedRetainedBytes();
        SodiumRelightPlanCache cache = new SodiumRelightPlanCache(Math.multiplyExact(bytes, 2L));
        SodiumRelightPlanCache.Owner first = cache.capture(plan);
        SodiumRelightPlanCache.Owner second = cache.capture(plan);
        SodiumRelightPlanCache.Lease firstLease = first.acquire();
        require(firstLease != null, "resident owner did not issue a lease");
        SodiumRelightPlanCache.Owner third = cache.capture(plan);

        require(first.isResident(), "pinned LRU owner was evicted");
        require(!second.isResident(), "unpinned LRU owner survived capacity eviction");
        require(third.isResident(), "new plan was not admitted after safe eviction");
        require(cache.snapshot().liveBytes() <= cache.snapshot().capacityBytes(),
                "relight cache exceeded its byte budget");

        first.close();
        require(first.isResident(), "closing a pinned owner released its live lease storage");
        require(first.acquire() == null, "closing owner issued a new lease");
        firstLease.close();
        require(!first.isResident(), "last lease did not release a close-pending owner");

        SodiumRelightPlanCache pinnedCache = new SodiumRelightPlanCache(bytes);
        SodiumRelightPlanCache.Owner pinned = pinnedCache.capture(plan);
        SodiumRelightPlanCache.Lease pin = pinned.acquire();
        require(pin != null, "pin-pressure test could not acquire a lease");
        SodiumRelightPlanCache.Owner rejected = pinnedCache.capture(plan);
        require(!rejected.isResident(), "pinned pressure exceeded the cache budget");
        require(pinnedCache.snapshot().pinnedPressureRejectionCount() == 1L,
                "pinned pressure rejection was not counted");
        pin.close();
        pinned.close();

        SodiumRelightPlanCache oversizedCache = new SodiumRelightPlanCache(bytes - 1L);
        require(!oversizedCache.capture(plan).isResident(), "oversized plan entered the cache");
        require(oversizedCache.snapshot().oversizedRejectionCount() == 1L,
                "oversized rejection was not counted");

        third.close();
        second.close();
        cache.clear();
    }

    private static void testResidentStateLeasePinsPlanAndMetadata() {
        SodiumRelightPlan plan = oneQuadPlan();
        SodiumRelightPlanCache cache = new SodiumRelightPlanCache(plan.estimatedRetainedBytes());
        SodiumRelightPlanCache.Owner owner = cache.capture(plan);
        SodiumRelightResidentState state = new SodiumRelightResidentState(
                owner,
                BuiltSectionInfo.EMPTY,
                73
        );
        require(state.info() == BuiltSectionInfo.EMPTY,
                "resident state changed the exact BuiltSectionInfo identity");
        require(state.generation() == 73, "resident state changed its generation");
        require(state.isResident(), "new resident state did not own a resident plan");

        SodiumRelightResidentState.Lease lease = state.acquire();
        require(lease != null, "resident state did not issue a complete lease");
        require(lease.plan() == plan, "resident state lease changed the relight plan identity");
        require(lease.info() == BuiltSectionInfo.EMPTY,
                "resident state lease changed the BuiltSectionInfo identity");
        require(lease.generation() == 73, "resident state lease changed its generation");

        state.close();
        require(state.acquire() == null, "closed resident state issued a new lease");
        require(state.isResident(), "closing a pinned state released its active lease storage");
        require(lease.plan() == plan && lease.info() == BuiltSectionInfo.EMPTY,
                "state replacement invalidated pinned lease data");

        lease.close();
        require(!state.isResident(), "last resident-state lease did not release its owner");
        expectIllegalState(lease::plan);
        expectIllegalState(lease::info);
        expectIllegalState(lease::generation);
        lease.close();

        SodiumRelightPlanCache legacyCache = new SodiumRelightPlanCache(plan.estimatedRetainedBytes());
        SodiumRelightResidentState legacyState = new SodiumRelightResidentState(
                legacyCache.capture(plan),
                BuiltSectionInfo.EMPTY,
                SodiumRelightResidentState.LEGACY_GENERATION
        );
        require(legacyState.acquire() == null,
                "legacy plan-only generation issued an exact resident-state lease");
        SodiumRelightPlanCache.Lease legacyPlanLease = legacyState.acquirePlan();
        require(legacyPlanLease != null && legacyPlanLease.plan() == plan,
                "legacy generation broke the backward-compatible oracle lease");
        legacyPlanLease.close();
        legacyState.close();
    }

    private static SodiumRelightPlan oneQuadPlan() {
        SodiumRelightPlan.BuildResult result = new SodiumRelightPlan.Builder()
                .add(SodiumRelightPlan.Pass.SOLID, ModelQuadFacing.POS_X, recipe(1.0f, false))
                .build(layout(new int[][]{{4, ModelQuadFacing.POS_X.ordinal()}}), null, null);
        require(result.accepted(), "cache fixture plan was rejected");
        return requirePlan(result);
    }

    private static Object[] identityVolume() {
        Object[] identities = new Object[SodiumRelightTopologySnapshot.STATE_COUNT];
        Arrays.setAll(identities, EqualToken::new);
        return identities;
    }

    private static Object identityAt(
            final Object[] identities,
            final int originX,
            final int originY,
            final int originZ,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        int localX = blockX - (originX - SodiumRelightTopologySnapshot.HALO_RADIUS);
        int localY = blockY - (originY - SodiumRelightTopologySnapshot.HALO_RADIUS);
        int localZ = blockZ - (originZ - SodiumRelightTopologySnapshot.HALO_RADIUS);
        int edge = SodiumRelightTopologySnapshot.EDGE_LENGTH;
        if (localX < 0 || localX >= edge
                || localY < 0 || localY >= edge
                || localZ < 0 || localZ >= edge) {
            throw new AssertionError("topology reader escaped its 18-cubed halo");
        }
        return identities[(localY * edge + localZ) * edge + localX];
    }

    private static SodiumRelightQuadRecipe recipe(final float x, final boolean emissive) {
        return SodiumRelightQuadRecipe.capture(
                new MutableQuad(x, 0x00300020),
                new BlockPos(3, 5, 7),
                Direction.SOUTH,
                Direction.UP,
                LightMode.SMOOTH,
                true,
                true,
                emissive
        );
    }

    private static SodiumRelightPlan.MeshLayout layout(final int[][] entries) {
        int[] segments = new int[FACING_COUNT * 2];
        boolean[] usedFacings = new boolean[FACING_COUNT];
        int vertices = 0;
        for (int index = 0; index < entries.length; index++) {
            segments[index * 2] = entries[index][0];
            segments[index * 2 + 1] = entries[index][1];
            usedFacings[entries[index][1]] = true;
            vertices += entries[index][0];
        }
        int segment = entries.length;
        for (int facing = 0; facing < FACING_COUNT; facing++) {
            if (!usedFacings[facing]) {
                segments[segment * 2 + 1] = facing;
                segment++;
            }
        }
        return SodiumRelightPlan.MeshLayout.of(
                vertices * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                segments
        );
    }

    private static ByteBuffer geometry(final byte[] lightBytes, final int prefixBytes) {
        int vertices = lightBytes.length / SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
        int geometryBytes = vertices * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
        ByteBuffer storage = ByteBuffer.allocate(prefixBytes + geometryBytes + 3);
        storage.position(prefixBytes);
        storage.limit(prefixBytes + geometryBytes);
        for (int vertex = 0; vertex < vertices; vertex++) {
            int geometryOffset = prefixBytes
                    + vertex * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
            int lightOffset = vertex * SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
            storage.put(
                    geometryOffset + SodiumLightSidecarPacking.BLOCK_LIGHT_OFFSET,
                    lightBytes[lightOffset]
            );
            storage.put(
                    geometryOffset + SodiumLightSidecarPacking.SKY_LIGHT_OFFSET,
                    lightBytes[lightOffset + 1]
            );
        }
        return storage;
    }

    private static SodiumRelightPlan requirePlan(final SodiumRelightPlan.BuildResult result) {
        SodiumRelightPlan plan = result.plan();
        if (plan == null) {
            throw new AssertionError("missing accepted plan");
        }
        return plan;
    }

    private static SodiumRelightPlan.Replay requireReplay(final SodiumRelightPlan.ReplayResult result) {
        SodiumRelightPlan.Replay replay = result.replay();
        if (replay == null) {
            throw new AssertionError("missing accepted replay");
        }
        return replay;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegalState(final Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected IllegalStateException");
    }

    private static final class SyntheticProvider extends LightPipelineProvider {
        private final LightPipeline pipeline;

        private SyntheticProvider(final LightPipeline pipeline) {
            super(new LightDataAccess() {
                @Override
                public int get(final int x, final int y, final int z) {
                    return 0;
                }
            });
            this.pipeline = pipeline;
        }

        @Override
        public LightPipeline getLighter(final LightMode type) {
            return this.pipeline;
        }
    }

    private record EqualToken(int value) {
    }

    private static final class MutableQuad implements ModelQuadView {
        private final float[] positions = new float[12];
        private final int[] normals = {101, 102, 103, 104};
        private final int[] lights = new int[4];

        private MutableQuad(final float x, final int light) {
            for (int vertex = 0; vertex < 4; vertex++) {
                this.positions[vertex * 3] = x + vertex * 0.1f;
                this.positions[vertex * 3 + 1] = vertex * 0.2f;
                this.positions[vertex * 3 + 2] = vertex * 0.3f;
                this.lights[vertex] = light;
            }
        }

        @Override
        public float getX(final int vertex) {
            return this.positions[vertex * 3];
        }

        @Override
        public float getY(final int vertex) {
            return this.positions[vertex * 3 + 1];
        }

        @Override
        public float getZ(final int vertex) {
            return this.positions[vertex * 3 + 2];
        }

        @Override
        public int getColor(final int vertex) {
            return 0xffffffff;
        }

        @Override
        public float getTexU(final int vertex) {
            return 0.0f;
        }

        @Override
        public float getTexV(final int vertex) {
            return 0.0f;
        }

        @Override
        public int getVertexNormal(final int vertex) {
            return this.normals[vertex];
        }

        @Override
        public int getFaceNormal() {
            return 202;
        }

        @Override
        public int getLight(final int vertex) {
            return this.lights[vertex];
        }

        @Override
        public int getFlags() {
            return 3;
        }

        @Override
        public int getTintIndex() {
            return -1;
        }

        @Override
        public TextureAtlasSprite getSprite() {
            return null;
        }

        @Override
        public Direction getLightFace() {
            return Direction.UP;
        }

        @Override
        public int getMaxLightQuad(final int vertex) {
            return this.lights[vertex];
        }
    }
}
