package com.metallum.client.gi.capture;

import com.metallum.client.gi.semantic.GiSemanticMaterial;
import com.metallum.client.gi.semantic.GiSemanticMedium;
import com.metallum.client.gi.semantic.GiSemanticPacking;
import com.metallum.client.gi.semantic.GiSemanticPalette;
import com.metallum.client.gi.semantic.GiSemanticProvenance;
import com.metallum.client.gi.semantic.GiSemanticQuadObservation;
import com.metallum.client.gi.semantic.GiSemanticSectionBuilder;
import com.metallum.client.gi.semantic.GiSemanticSectionSeed;
import com.metallum.client.gi.semantic.GiSemanticSectionSnapshot;
import com.metallum.client.gi.semantic.GiSemanticSectionTask;
import com.metallum.client.gi.semantic.GiSemanticStateSeed;
import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** One reusable worker-thread scope around the exact Sodium full-mesh execute call. */
public final class GiSemanticCaptureScope implements AutoCloseable {
    private static final int MAX_AXIS_OBSERVATIONS = 65_536;
    public record Result(
            GiSemanticSectionTask task,
            GiSemanticSectionSeed seed,
            List<GiSemanticQuadObservation> observations
    ) {
    }

    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(State::new);

    private final State state;
    private boolean closed;

    private GiSemanticCaptureScope(final State state) {
        this.state = state;
    }

    public static GiSemanticCaptureScope open(final GiSemanticSectionTask task) {
        State state = LOCAL.get();
        if (state.active) {
            throw new IllegalStateException("Nested G2 meshing capture scope");
        }
        state.reset(java.util.Objects.requireNonNull(task, "task"));
        return new GiSemanticCaptureScope(state);
    }

    public static void seedIfNeeded(final LevelSlice slice) {
        State state = LOCAL.get();
        if (!state.active || state.seeded || state.failed) {
            return;
        }
        try {
            state.seed(slice);
        } catch (RuntimeException failure) {
            state.failed = true;
        }
    }

    public static boolean isActive() {
        State state = LOCAL.get();
        return state.active && !state.failed;
    }

    public static int localIndex(final BlockPos position) {
        State state = LOCAL.get();
        if (!state.active || position == null) {
            return -1;
        }
        int sectionX = net.minecraft.core.SectionPos.x(state.task.sectionKey());
        int sectionY = net.minecraft.core.SectionPos.y(state.task.sectionKey());
        int sectionZ = net.minecraft.core.SectionPos.z(state.task.sectionKey());
        int x = position.getX() - (sectionX << 4);
        int y = position.getY() - (sectionY << 4);
        int z = position.getZ() - (sectionZ << 4);
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) {
            return -1;
        }
        return y << 8 | z << 4 | x;
    }

    public static void observe(final GiSemanticQuadObservation observation) {
        State state = LOCAL.get();
        if (!state.active || state.failed || observation == null) {
            return;
        }
        if (state.observations.size() >= MAX_AXIS_OBSERVATIONS) {
            state.observations.clear();
            state.failed = true;
            return;
        }
        state.observations.add(observation);
    }

    public static void markUnknown(final BlockPos position) {
        State state = LOCAL.get();
        int localIndex = localIndex(position);
        if (state.active && localIndex >= 0) {
            state.unknownBlocks[localIndex] = true;
        }
    }

    public static void failClosed() {
        State state = LOCAL.get();
        if (state.active) {
            state.observations.clear();
            state.failed = true;
        }
    }

    public Result finish(final LevelSlice slice) {
        this.requireOpen();
        seedIfNeeded(slice);
        if (this.state.failed || !this.state.seeded) {
            return null;
        }
        GiSemanticPalette palette = GiSemanticController.global().paletteFor(this.state.task);
        if (palette == null) {
            return null;
        }
        GiSemanticSectionSeed seed = this.state.seed;
        boolean hasUnknown = false;
        for (boolean unknown : this.state.unknownBlocks) {
            hasUnknown |= unknown;
        }
        if (hasUnknown) {
            GiSemanticSectionSeed.Builder repaired = GiSemanticSectionSeed.unknownBuilder();
            for (int index = 0; index < GiSemanticSectionSeed.BLOCK_COUNT; index++) {
                repaired.set(this.state.unknownBlocks[index]
                        ? GiSemanticStateSeed.fallback(index, GiSemanticProvenance.RESOURCE_DERIVED)
                        : seed.state(index));
            }
            seed = repaired.build();
        }
        return new Result(this.state.task, seed, List.copyOf(this.state.observations));
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.state.clear();
    }

    private void requireOpen() {
        if (this.closed || !this.state.active) {
            throw new IllegalStateException("G2 capture scope is closed");
        }
    }

    private static final class State {
        private final List<GiSemanticQuadObservation> observations = new ArrayList<>();
        private final boolean[] unknownBlocks = new boolean[GiSemanticSectionSeed.BLOCK_COUNT];
        private GiSemanticSectionTask task;
        private GiSemanticSectionSeed seed;
        private boolean active;
        private boolean seeded;
        private boolean failed;

        private void reset(final GiSemanticSectionTask task) {
            this.task = task;
            this.seed = null;
            this.active = true;
            this.seeded = false;
            this.failed = false;
            this.observations.clear();
            java.util.Arrays.fill(this.unknownBlocks, false);
        }

        private void seed(final LevelSlice slice) {
            if (slice == null) {
                throw new NullPointerException("slice");
            }
            GiSemanticSectionSeed.Builder builder = GiSemanticSectionSeed.unknownBuilder();
            int originX = net.minecraft.core.SectionPos.x(this.task.sectionKey()) << 4;
            int originY = net.minecraft.core.SectionPos.y(this.task.sectionKey()) << 4;
            int originZ = net.minecraft.core.SectionPos.z(this.task.sectionKey()) << 4;
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int localIndex = y << 8 | z << 4 | x;
                        position.set(originX + x, originY + y, originZ + z);
                        BlockState block = slice.getBlockState(position);
                        if (block == null) {
                            builder.set(GiSemanticStateSeed.unknown(localIndex));
                            continue;
                        }
                        if (block.isAir()) {
                            builder.set(GiSemanticStateSeed.empty(localIndex));
                            continue;
                        }
                        if (block.getRenderShape() == RenderShape.INVISIBLE) {
                            builder.set(GiSemanticStateSeed.fallback(
                                    localIndex, GiSemanticProvenance.MODDED_FALLBACK
                            ));
                            continue;
                        }
                        SurfaceMaterialPolicy.Descriptor descriptor = SurfaceMaterialPolicy.forTerrain(
                                block, false
                        );
                        GiSemanticMedium medium = block.getFluidState().is(FluidTags.WATER)
                                ? GiSemanticMedium.WATER
                                : block.isSolidRender() ? GiSemanticMedium.OPAQUE : GiSemanticMedium.CUTOUT;
                        String key = GiSemanticPaletteFactory.canonicalKey(block, descriptor, medium);
                        builder.set(GiSemanticStateSeed.content(
                                localIndex, key, block.isSolidRender() ? 1.0F : 0.5F,
                                block.isSolidRender() ? GiSemanticPacking.FACE_MASK : 0,
                                GiSemanticProvenance.MATERIAL_DERIVED
                        ));
                    }
                }
            }
            this.seed = builder.build();
            this.seeded = true;
        }

        private void clear() {
            this.active = false;
            this.task = null;
            this.seed = null;
            this.observations.clear();
            java.util.Arrays.fill(this.unknownBlocks, false);
        }
    }
}
