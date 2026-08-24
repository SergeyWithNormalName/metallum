package com.metallum.client.lighting.reflection;

import com.metallum.client.radiance.CompactSectionPayload;
import com.metallum.client.radiance.RadianceRuntimeStorage;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Accepted-Sodium-only producer for one fixed 128-block reflection domain.
 *
 * <p>It is intentionally not a clipmap: the first camera pose selects a section-aligned origin;
 * all 512 sections must be published from Sodium's successful geometry uploads before a source
 * snapshot exists.  An empty section is known transparent data. A missing, stale, or removed
 * section invalidates the build rather than being treated as empty.</p>
 */
public final class FrozenReflectionFieldController {
    public static final int SPAN_BLOCKS = 128;
    public static final int SOURCE_EDGE = 64;
    public static final int SOURCE_CELL_BLOCKS = 2;
    public static final int SECTIONS_PER_EDGE = SPAN_BLOCKS / 16;
    public static final int EXPECTED_SECTION_COUNT = SECTIONS_PER_EDGE * SECTIONS_PER_EDGE * SECTIONS_PER_EDGE;
    private static final int MAX_LATEST_TASK_FAILURES = 4;

    public enum State {
        OFF,
        COLLECTING,
        READY_FOR_GPU_UPLOAD,
        GPU_BUILD_QUEUED,
        READY,
        INVALID
    }

    public enum SectionState {
        PENDING,
        PUBLISHED_CONTENT,
        KNOWN_EMPTY,
        UNAVAILABLE
    }

    /** Immutable CPU payload. validity=255 means real published data, including transparent air. */
    public record SourceSnapshot(
            long worldGeneration,
            long fieldGeneration,
            int originX,
            int originY,
            int originZ,
            short[] packedRgba,
            byte[] validity
    ) {
        public SourceSnapshot {
            if (packedRgba.length != SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE * 4
                    || validity.length != SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE) {
                throw new IllegalArgumentException("Frozen reflection source dimensions are invalid");
            }
        }
    }

    public record Snapshot(
            State state,
            long worldGeneration,
            long fieldGeneration,
            int originX,
            int originY,
            int originZ,
            int publishedContent,
            int knownEmpty,
            int unavailable,
            int expectedSections,
            @Nullable String invalidationReason
    ) {
    }

    private static final FrozenReflectionFieldController GLOBAL = new FrozenReflectionFieldController();

    private @Nullable Object worldIdentity;
    private long nextWorldGeneration;
    private long fieldGeneration;
    private long nextTaskGeneration;
    private State state = State.OFF;
    private int originX;
    private int originY;
    private int originZ;
    private final Set<Long> expectedSections = new HashSet<>();
    private final Map<Long, SectionState> sections = new HashMap<>();
    private final Map<Long, Long> latestTaskGenerations = new HashMap<>();
    private final Map<Long, Integer> latestTaskFailures = new HashMap<>();
    private short[] packedRgba = new short[SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE * 4];
    private byte[] validity = new byte[SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE];
    private @Nullable SourceSnapshot uploadSnapshot;
    private @Nullable String invalidationReason;

    private FrozenReflectionFieldController() {
    }

    public static FrozenReflectionFieldController global() {
        return GLOBAL;
    }

    public synchronized void openWorld(final Object identity) {
        Objects.requireNonNull(identity, "identity");
        if (this.worldIdentity == identity) {
            return;
        }
        this.worldIdentity = identity;
        this.nextWorldGeneration++;
        resetField(State.OFF);
    }

    public synchronized void closeWorld(final Object identity) {
        if (this.worldIdentity == identity) {
            this.worldIdentity = null;
            this.nextWorldGeneration++;
            resetField(State.OFF);
        }
    }

    /** Latches the only domain origin once; later camera motion deliberately does nothing. */
    public synchronized boolean activateAtCamera(final Object identity, final double cameraX, final double cameraY, final double cameraZ) {
        if (!VertexReflectionExperiment.isRuntimeEnabled() || this.worldIdentity != identity || this.state != State.OFF) {
            return false;
        }
        this.fieldGeneration++;
        this.originX = sectionAlignedOrigin(cameraX);
        this.originY = sectionAlignedOrigin(cameraY);
        this.originZ = sectionAlignedOrigin(cameraZ);
        this.expectedSections.clear();
        this.sections.clear();
        this.latestTaskGenerations.clear();
        this.latestTaskFailures.clear();
        Arrays.fill(this.packedRgba, (short) 0);
        Arrays.fill(this.validity, (byte) 0);
        this.uploadSnapshot = null;
        this.invalidationReason = null;
        for (int z = 0; z < SECTIONS_PER_EDGE; z++) {
            for (int y = 0; y < SECTIONS_PER_EDGE; y++) {
                for (int x = 0; x < SECTIONS_PER_EDGE; x++) {
                    long key = SectionPos.asLong((this.originX >> 4) + x, (this.originY >> 4) + y, (this.originZ >> 4) + z);
                    this.expectedSections.add(key);
                    this.sections.put(key, SectionState.PENDING);
                }
            }
        }
        this.state = State.COLLECTING;
        return true;
    }

    public synchronized @Nullable FrozenReflectionSectionTask beginSectionTask(final Object identity, final long sectionKey) {
        if (this.worldIdentity != identity || this.state != State.COLLECTING || !this.expectedSections.contains(sectionKey)) {
            return null;
        }
        long taskGeneration = ++this.nextTaskGeneration;
        this.latestTaskGenerations.put(sectionKey, taskGeneration);
        return new FrozenReflectionSectionTask(this.nextWorldGeneration, this.fieldGeneration, sectionKey, taskGeneration);
    }

    /** Sodium applies air-only sections synchronously without a geometry upload. */
    public synchronized boolean publishAuthoritativeEmpty(final Object identity, final long sectionKey) {
        FrozenReflectionSectionTask task = beginSectionTask(identity, sectionKey);
        return task != null && publishAccepted(new FrozenReflectionSectionCandidate(
                task,
                CompactSectionPayload.empty(sectionKey, task.worldGeneration())
        ));
    }

    /** True only when no worker result is outstanding for a still-required non-empty section. */
    public synchronized boolean needsSectionTask(final Object identity, final long sectionKey) {
        return this.worldIdentity == identity
                && this.state == State.COLLECTING
                && this.sections.get(sectionKey) == SectionState.PENDING
                && !this.latestTaskGenerations.containsKey(sectionKey);
    }

    /** Called only after Sodium has accepted the exact geometry output that carried the candidate. */
    public synchronized boolean publishAccepted(final FrozenReflectionSectionCandidate candidate) {
        if (candidate == null || !candidate.claimForPublication()) {
            return false;
        }
        FrozenReflectionSectionTask task = candidate.task();
        if (!isCurrentCollectingTask(task)) {
            consume(candidate.payload());
            return false;
        }
        SectionState previous = this.sections.get(task.sectionKey());
        if (previous != SectionState.PENDING) {
            consume(candidate.payload());
            return false;
        }
        CompactSectionPayload payload = candidate.payload();
        depositSection(task.sectionKey(), payload);
        this.sections.put(task.sectionKey(), payload.isEmpty() ? SectionState.KNOWN_EMPTY : SectionState.PUBLISHED_CONTENT);
        this.latestTaskGenerations.remove(task.sectionKey());
        this.latestTaskFailures.remove(task.sectionKey());
        consume(payload);
        if (publishedSections() == EXPECTED_SECTION_COUNT) {
            this.uploadSnapshot = new SourceSnapshot(
                    this.nextWorldGeneration,
                    this.fieldGeneration,
                    this.originX,
                    this.originY,
                    this.originZ,
                    this.packedRgba,
                    this.validity
            );
            this.state = State.READY_FOR_GPU_UPLOAD;
        }
        return true;
    }

    /** A superseded output is harmless; a latest output gets a bounded retry before fail-closed. */
    public synchronized void discardCandidate(final @Nullable FrozenReflectionSectionCandidate candidate) {
        if (candidate == null || !candidate.discard()) {
            return;
        }
        FrozenReflectionSectionTask task = candidate.task();
        consume(candidate.payload());
        retryOrInvalidateTask(task, "Sodium discarded the latest expected-section output");
    }

    public synchronized void removeSection(final Object identity, final long sectionKey) {
        if (this.worldIdentity != identity || !this.expectedSections.contains(sectionKey)) {
            return;
        }
        if (this.state == State.COLLECTING || this.state == State.READY_FOR_GPU_UPLOAD || this.state == State.GPU_BUILD_QUEUED) {
            this.sections.put(sectionKey, SectionState.UNAVAILABLE);
            this.state = State.INVALID;
            this.uploadSnapshot = null;
            this.invalidationReason = "Sodium removed an expected section before the frozen field was ready";
        }
    }

    /**
     * The experimental preload owns its finite Sodium section set only until the immutable source
     * snapshot has been queued.  Retaining those sections prevents culling from disposing an
     * in-flight accepted-output task; it never retains terrain after the field is READY.
     */
    public synchronized boolean retainsSectionDuringCollection(final Object identity, final long sectionKey) {
        return this.worldIdentity == identity
                && this.expectedSections.contains(sectionKey)
                && (this.state == State.COLLECTING
                || this.state == State.READY_FOR_GPU_UPLOAD
                || this.state == State.GPU_BUILD_QUEUED);
    }

    public synchronized @Nullable SourceSnapshot claimReadySnapshotForGpuUpload() {
        if (this.state != State.READY_FOR_GPU_UPLOAD || this.uploadSnapshot == null) {
            return null;
        }
        this.state = State.GPU_BUILD_QUEUED;
        return this.uploadSnapshot;
    }

    public synchronized void noteGpuReady(final long worldGeneration, final long completedFieldGeneration) {
        if (this.state == State.GPU_BUILD_QUEUED
                && this.nextWorldGeneration == worldGeneration
                && this.fieldGeneration == completedFieldGeneration) {
            this.state = State.READY;
        }
    }

    public synchronized void noteGpuFailure(final long worldGeneration, final long failedFieldGeneration) {
        if (this.nextWorldGeneration == worldGeneration && this.fieldGeneration == failedFieldGeneration) {
            this.state = State.INVALID;
            this.uploadSnapshot = null;
            this.invalidationReason = "Metal rejected the immutable frozen-field upload";
        }
    }

    /** Extraction failed before a candidate existed; this expected section is unavailable. */
    public synchronized void noteCollectionFailure(final FrozenReflectionSectionTask task) {
        if (task != null) {
            retryOrInvalidateTask(task, "Sodium failed extraction for the latest expected-section output");
        }
    }

    public synchronized Snapshot snapshot() {
        int content = 0;
        int empty = 0;
        int unavailable = 0;
        for (SectionState section : this.sections.values()) {
            switch (section) {
                case PUBLISHED_CONTENT -> content++;
                case KNOWN_EMPTY -> empty++;
                case UNAVAILABLE -> unavailable++;
                case PENDING -> { }
            }
        }
        return new Snapshot(this.state, this.nextWorldGeneration, this.fieldGeneration,
                this.originX, this.originY, this.originZ, content, empty, unavailable, this.expectedSections.size(),
                this.invalidationReason);
    }

    synchronized @Nullable SourceSnapshot sourceSnapshotForTests() {
        return this.uploadSnapshot;
    }

    private boolean isCurrentCollectingTask(final FrozenReflectionSectionTask task) {
        return this.state == State.COLLECTING
                && task.worldGeneration() == this.nextWorldGeneration
                && task.fieldGeneration() == this.fieldGeneration
                && this.expectedSections.contains(task.sectionKey())
                && this.latestTaskGenerations.getOrDefault(task.sectionKey(), Long.MIN_VALUE) == task.taskGeneration();
    }

    private void retryOrInvalidateTask(final FrozenReflectionSectionTask task, final String reason) {
        if (isCurrentCollectingTask(task)) {
            int failures = this.latestTaskFailures.getOrDefault(task.sectionKey(), 0) + 1;
            if (failures < MAX_LATEST_TASK_FAILURES) {
                this.latestTaskFailures.put(task.sectionKey(), failures);
                this.latestTaskGenerations.remove(task.sectionKey());
                return;
            }
            this.sections.put(task.sectionKey(), SectionState.UNAVAILABLE);
            this.state = State.INVALID;
            this.uploadSnapshot = null;
            this.invalidationReason = reason + " after " + failures + " attempts";
        }
    }

    private void depositSection(final long sectionKey, final CompactSectionPayload payload) {
        int sectionX = SectionPos.x(sectionKey) << 4;
        int sectionY = SectionPos.y(sectionKey) << 4;
        int sectionZ = SectionPos.z(sectionKey) << 4;
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int worldX = sectionX + x * SOURCE_CELL_BLOCKS;
                    int worldY = sectionY + y * SOURCE_CELL_BLOCKS;
                    int worldZ = sectionZ + z * SOURCE_CELL_BLOCKS;
                    int sourceX = (worldX - this.originX) / SOURCE_CELL_BLOCKS;
                    int sourceY = (worldY - this.originY) / SOURCE_CELL_BLOCKS;
                    int sourceZ = (worldZ - this.originZ) / SOURCE_CELL_BLOCKS;
                    int target = sourceIndex(sourceX, sourceY, sourceZ);
                    this.validity[target] = (byte) 0xff;
                    if (!payload.isEmpty()) {
                        int local = (y * 2 << 8) | (z * 2 << 4) | (x * 2);
                        int src = local * 4;
                        int dst = target * 4;
                        System.arraycopy(payload.packedRgba(), src, this.packedRgba, dst, 4);
                    }
                }
            }
        }
    }

    private int publishedSections() {
        int count = 0;
        for (SectionState section : this.sections.values()) {
            if (section == SectionState.PUBLISHED_CONTENT || section == SectionState.KNOWN_EMPTY) {
                count++;
            }
        }
        return count;
    }

    private void consume(final CompactSectionPayload payload) {
        RadianceRuntimeStorage.global().notePayloadConsumed(payload);
    }

    private void resetField(final State nextState) {
        this.expectedSections.clear();
        this.sections.clear();
        this.latestTaskGenerations.clear();
        this.latestTaskFailures.clear();
        this.uploadSnapshot = null;
        this.invalidationReason = null;
        this.state = nextState;
    }

    private static int sectionAlignedOrigin(final double cameraCoordinate) {
        return ((int) Math.floor((cameraCoordinate - SPAN_BLOCKS * 0.5) / 16.0)) << 4;
    }

    private static int sourceIndex(final int x, final int y, final int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SOURCE_EDGE || y >= SOURCE_EDGE || z >= SOURCE_EDGE) {
            throw new IllegalStateException("Accepted reflection section escaped frozen source bounds");
        }
        return (y * SOURCE_EDGE + z) * SOURCE_EDGE + x;
    }
}
