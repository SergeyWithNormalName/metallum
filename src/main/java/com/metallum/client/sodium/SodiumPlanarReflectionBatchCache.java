package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-region indirect batches for the reflected camera.
 *
 * <p>The batches deliberately do not share Sodium's main-camera cache or the sun-shadow cache:
 * each view has a different visible-section bitset, and overwriting either cache corrupts the
 * next draw that uses it.</p>
 */
public final class SodiumPlanarReflectionBatchCache {
    private static final int IN_FLIGHT_SLOTS = 3;
    private static final int MAX_COMMANDS = ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE + 1;

    private final Map<TerrainRenderPass, Entry[]> entries = new IdentityHashMap<>();
    private final long[] preparedTokens = new long[IN_FLIGHT_SLOTS];
    private final long[] preparedSections0 = new long[IN_FLIGHT_SLOTS];
    private final long[] preparedSections1 = new long[IN_FLIGHT_SLOTS];
    private final long[] preparedSections2 = new long[IN_FLIGHT_SLOTS];
    private final long[] preparedSections3 = new long[IN_FLIGHT_SLOTS];

    public void prepare(
            final long reflectionToken,
            final long sections0,
            final long sections1,
            final long sections2,
            final long sections3
    ) {
        int slot = slot(reflectionToken);
        this.preparedTokens[slot] = reflectionToken;
        this.preparedSections0[slot] = sections0;
        this.preparedSections1[slot] = sections1;
        this.preparedSections2[slot] = sections2;
        this.preparedSections3[slot] = sections3;
    }

    public MultiDrawBatch acquire(final TerrainRenderPass pass, final long reflectionToken) {
        int slot = slot(reflectionToken);
        if (this.preparedTokens[slot] != reflectionToken) {
            throw new IllegalStateException("Planar-reflection batch requested before view selection");
        }
        Entry[] passEntries = this.entries.computeIfAbsent(pass, ignored -> new Entry[IN_FLIGHT_SLOTS]);
        Entry entry = passEntries[slot];
        if (entry == null) {
            entry = new Entry(MultiDrawBatch.newBatch(MAX_COMMANDS));
            passEntries[slot] = entry;
        }
        long sections0 = this.preparedSections0[slot];
        long sections1 = this.preparedSections1[slot];
        long sections2 = this.preparedSections2[slot];
        long sections3 = this.preparedSections3[slot];
        if (!entry.matches(sections0, sections1, sections2, sections3)) {
            entry.batch.clear();
            entry.remember(sections0, sections1, sections2, sections3);
        }
        return entry.batch;
    }

    public void clearAll() {
        for (Entry[] passEntries : this.entries.values()) {
            for (Entry entry : passEntries) {
                if (entry != null) {
                    entry.batch.clear();
                }
            }
        }
    }

    public void clear(final TerrainRenderPass pass) {
        Entry[] passEntries = this.entries.get(pass);
        if (passEntries == null) {
            return;
        }
        for (Entry entry : passEntries) {
            if (entry != null) {
                entry.batch.clear();
            }
        }
    }

    public void delete() {
        for (Entry[] passEntries : this.entries.values()) {
            for (Entry entry : passEntries) {
                if (entry != null) {
                    entry.batch.delete();
                }
            }
        }
        this.entries.clear();
    }

    private static int slot(final long reflectionToken) {
        if (reflectionToken == 0L) {
            throw new IllegalArgumentException("A planar-reflection batch requires a token");
        }
        return (int) Math.floorMod(reflectionToken, IN_FLIGHT_SLOTS);
    }

    private static final class Entry {
        private final MultiDrawBatch batch;
        private long sections0;
        private long sections1;
        private long sections2;
        private long sections3;
        private boolean signatureValid;

        private Entry(final MultiDrawBatch batch) {
            this.batch = batch;
        }

        private boolean matches(
                final long expected0,
                final long expected1,
                final long expected2,
                final long expected3
        ) {
            return this.signatureValid
                    && this.sections0 == expected0
                    && this.sections1 == expected1
                    && this.sections2 == expected2
                    && this.sections3 == expected3;
        }

        private void remember(
                final long next0,
                final long next1,
                final long next2,
                final long next3
        ) {
            this.sections0 = next0;
            this.sections1 = next1;
            this.sections2 = next2;
            this.sections3 = next3;
            this.signatureValid = true;
        }
    }
}
