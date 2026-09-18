package com.metallum.client.lighting.reflection;

import com.metallum.Metallum;
import com.metallum.client.radiance.CompactSectionPayload;
import com.metallum.client.radiance.Float16Compressor;
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
 * Accepted-Sodium-only producer for a world-snapped 128-block reflection domain.
 *
 * <p>The camera stays inside a 32-block guard band while the current domain is stable. Crossing
 * that band starts a new accepted-Sodium collection while Metal keeps the previous completed
 * field bound. All 512 sections of a new domain must publish before the replacement is eligible.
 * An empty section is known transparent data; missing data never becomes implicit air.</p>
 */
public final class FrozenReflectionFieldController {
    public static final int SPAN_BLOCKS = 128;
    public static final int SOURCE_EDGE = 64;
    public static final int SOURCE_CELL_BLOCKS = 2;
    public static final int SECTIONS_PER_EDGE = SPAN_BLOCKS / 16;
    public static final int EXPECTED_SECTION_COUNT = SECTIONS_PER_EDGE * SECTIONS_PER_EDGE * SECTIONS_PER_EDGE;
    public static final int RECENTER_GUARD_BLOCKS = 32;
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
    private Set<Long> expectedSections = new HashSet<>();
    private Set<Long> spareExpectedSections = new HashSet<>();
    private Map<Long, SectionState> sections = new HashMap<>();
    private Map<Long, SectionState> spareSections = new HashMap<>();
    private final Map<Long, Long> latestTaskGenerations = new HashMap<>();
    private final Map<Long, Integer> latestTaskFailures = new HashMap<>();
    private short[] packedRgba = new short[SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE * 4];
    private byte[] validity = new byte[SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE];
    private short[] sparePackedRgba = new short[SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE * 4];
    private byte[] spareValidity = new byte[SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE];
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

    /** Starts the first domain or a guarded, section-aligned replacement after camera motion. */
    public synchronized boolean activateAtCamera(final Object identity, final double cameraX, final double cameraY, final double cameraZ) {
        if (!VertexReflectionExperiment.isRuntimeEnabled() || this.worldIdentity != identity) {
            return false;
        }
        if (this.state != State.OFF
                && (this.state != State.READY || !cameraRequiresRecenter(cameraX, cameraY, cameraZ))) {
            return false;
        }
        startCollection(cameraX, cameraY, cameraZ);
        return true;
    }

    private void startCollection(final double cameraX, final double cameraY, final double cameraZ) {
        State previousState = this.state;
        long previousFieldGeneration = this.fieldGeneration;
        int previousOriginX = this.originX;
        int previousOriginY = this.originY;
        int previousOriginZ = this.originZ;
        Map<Long, SectionState> previousSections = this.sections;
        short[] previousPackedRgba = this.packedRgba;
        byte[] previousValidity = this.validity;
        this.fieldGeneration++;
        this.originX = previousState == State.READY
                && !coordinateRequiresRecenter(cameraX, previousOriginX)
                ? previousOriginX : sectionAlignedOrigin(cameraX);
        this.originY = previousState == State.READY
                && !coordinateRequiresRecenter(cameraY, previousOriginY)
                ? previousOriginY : sectionAlignedOrigin(cameraY);
        this.originZ = previousState == State.READY
                && !coordinateRequiresRecenter(cameraZ, previousOriginZ)
                ? previousOriginZ : sectionAlignedOrigin(cameraZ);
        Set<Long> nextExpectedSections = this.spareExpectedSections;
        Map<Long, SectionState> nextSections = this.spareSections;
        nextExpectedSections.clear();
        nextSections.clear();
        this.spareExpectedSections = this.expectedSections;
        this.expectedSections = nextExpectedSections;
        this.spareSections = previousSections;
        this.sections = nextSections;
        this.latestTaskGenerations.clear();
        this.latestTaskFailures.clear();
        if (previousState == State.READY) {
            this.packedRgba = this.sparePackedRgba;
            this.validity = this.spareValidity;
            this.sparePackedRgba = previousPackedRgba;
            this.spareValidity = previousValidity;
        }
        Arrays.fill(this.packedRgba, (short) 0);
        Arrays.fill(this.validity, (byte) 0);
        if (previousState == State.READY) {
            copyOverlappingSource(
                    previousOriginX,
                    previousOriginY,
                    previousOriginZ,
                    previousPackedRgba,
                    previousValidity
            );
        }
        this.uploadSnapshot = null;
        this.invalidationReason = null;
        for (int z = 0; z < SECTIONS_PER_EDGE; z++) {
            for (int y = 0; y < SECTIONS_PER_EDGE; y++) {
                for (int x = 0; x < SECTIONS_PER_EDGE; x++) {
                    long key = SectionPos.asLong((this.originX >> 4) + x, (this.originY >> 4) + y, (this.originZ >> 4) + z);
                    this.expectedSections.add(key);
                    SectionState previous = previousState == State.READY
                            ? previousSections.get(key) : null;
                    this.sections.put(
                            key,
                            previous == SectionState.PUBLISHED_CONTENT
                                    || previous == SectionState.KNOWN_EMPTY
                                    ? previous
                                    : SectionState.PENDING
                    );
                }
            }
        }
        this.state = State.COLLECTING;
        if (System.getenv("METALLUM_BENCHMARK") != null) {
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=VERTEX_REFLECTION_FIELD_START previous_state={} generation={}->{} origin=[{},{},{}]->[{},{},{}] camera=[{},{},{}] reused_sections={} pending_sections={}",
                    previousState,
                    previousFieldGeneration,
                    this.fieldGeneration,
                    previousOriginX, previousOriginY, previousOriginZ,
                    this.originX, this.originY, this.originZ,
                    cameraX, cameraY, cameraZ,
                    publishedSections(), EXPECTED_SECTION_COUNT - publishedSections()
            );
        }
    }

    private boolean cameraRequiresRecenter(final double cameraX, final double cameraY, final double cameraZ) {
        return coordinateRequiresRecenter(cameraX, this.originX)
                || coordinateRequiresRecenter(cameraY, this.originY)
                || coordinateRequiresRecenter(cameraZ, this.originZ);
    }

    private static boolean coordinateRequiresRecenter(final double cameraCoordinate, final int origin) {
        return cameraCoordinate < origin + RECENTER_GUARD_BLOCKS
                || cameraCoordinate >= origin + SPAN_BLOCKS - RECENTER_GUARD_BLOCKS;
    }

    /**
     * Reuses the immutable cells shared by two guarded domains. A guarded recenter therefore
     * recollects only the entering slabs instead of rebuilding every overlapping section.
     */
    private void copyOverlappingSource(
            final int previousOriginX,
            final int previousOriginY,
            final int previousOriginZ,
            final short[] previousPackedRgba,
            final byte[] previousValidity
    ) {
        int deltaX = (this.originX - previousOriginX) / SOURCE_CELL_BLOCKS;
        int deltaY = (this.originY - previousOriginY) / SOURCE_CELL_BLOCKS;
        int deltaZ = (this.originZ - previousOriginZ) / SOURCE_CELL_BLOCKS;
        int copyWidth = SOURCE_EDGE - Math.abs(deltaX);
        int copyHeight = SOURCE_EDGE - Math.abs(deltaY);
        int copyDepth = SOURCE_EDGE - Math.abs(deltaZ);
        if (copyWidth <= 0 || copyHeight <= 0 || copyDepth <= 0) {
            return;
        }
        int newStartX = Math.max(0, -deltaX);
        int newStartY = Math.max(0, -deltaY);
        int newStartZ = Math.max(0, -deltaZ);
        int oldStartX = Math.max(0, deltaX);
        int oldStartY = Math.max(0, deltaY);
        int oldStartZ = Math.max(0, deltaZ);
        for (int y = 0; y < copyHeight; y++) {
            int oldY = oldStartY + y;
            int newY = newStartY + y;
            for (int z = 0; z < copyDepth; z++) {
                int oldIndex = sourceIndex(oldStartX, oldY, oldStartZ + z);
                int newIndex = sourceIndex(newStartX, newY, newStartZ + z);
                System.arraycopy(
                        previousValidity,
                        oldIndex,
                        this.validity,
                        newIndex,
                        copyWidth
                );
                System.arraycopy(
                        previousPackedRgba,
                        oldIndex * 4,
                        this.packedRgba,
                        newIndex * 4,
                        copyWidth * 4
                );
            }
        }
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
            if (System.getenv("METALLUM_BENCHMARK") != null) {
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=VERTEX_REFLECTION_FIELD_READY generation={} origin=[{},{},{}] sections={}/{}",
                        this.fieldGeneration,
                        this.originX, this.originY, this.originZ,
                        publishedSections(), EXPECTED_SECTION_COUNT
                );
            }
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
                        int dst = target * 4;
                        aggregateSourceCell(payload, x * 2, y * 2, z * 2, this.packedRgba, dst);
                    }
                }
            }
        }
    }

    /** Aggregates the complete 2x2x2 block footprint so thin/off-grid landmarks survive. */
    private static void aggregateSourceCell(
            final CompactSectionPayload payload,
            final int baseX,
            final int baseY,
            final int baseZ,
            final short[] destination,
            final int destinationOffset
    ) {
        float red = 0.0F;
        float green = 0.0F;
        float blue = 0.0F;
        float totalWeight = 0.0F;
        float maxOpacity = 0.0F;
        for (int oz = 0; oz < 2; oz++) {
            for (int oy = 0; oy < 2; oy++) {
                for (int ox = 0; ox < 2; ox++) {
                    int local = ((baseY + oy) << 8) | ((baseZ + oz) << 4) | (baseX + ox);
                    byte classification = payload.classification()[local];
                    if (classification == CompactSectionPayload.CLASS_WATER) {
                        continue;
                    }
                    int source = local * 4;
                    float opacity = Math.clamp(Float16Compressor.unpackFloat(payload.packedRgba()[source + 3]), 0.0F, 1.0F);
                    float emissiveBoost = classification == CompactSectionPayload.CLASS_EMISSIVE ? 2.0F : 0.0F;
                    float weight = opacity + emissiveBoost;
                    if (weight > 0.0F) {
                        red += Math.max(0.0F, Float16Compressor.unpackFloat(payload.packedRgba()[source])) * weight;
                        green += Math.max(0.0F, Float16Compressor.unpackFloat(payload.packedRgba()[source + 1])) * weight;
                        blue += Math.max(0.0F, Float16Compressor.unpackFloat(payload.packedRgba()[source + 2])) * weight;
                        totalWeight += weight;
                    }
                    maxOpacity = Math.max(maxOpacity, opacity);
                }
            }
        }
        float inverseWeight = totalWeight > 1.0e-6F ? 1.0F / totalWeight : 0.0F;
        destination[destinationOffset] = Float16Compressor.packFloat(red * inverseWeight);
        destination[destinationOffset + 1] = Float16Compressor.packFloat(green * inverseWeight);
        destination[destinationOffset + 2] = Float16Compressor.packFloat(blue * inverseWeight);
        destination[destinationOffset + 3] = Float16Compressor.packFloat(maxOpacity);
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
        // Round the ideal centered origin to the nearest section. Always flooring could bias the
        // camera by almost one complete section and needlessly clip forward reflection landmarks.
        return ((int) Math.floor((cameraCoordinate - SPAN_BLOCKS * 0.5 + 8.0) / 16.0)) << 4;
    }

    private static int sourceIndex(final int x, final int y, final int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SOURCE_EDGE || y >= SOURCE_EDGE || z >= SOURCE_EDGE) {
            throw new IllegalStateException("Accepted reflection section escaped frozen source bounds");
        }
        return (y * SOURCE_EDGE + z) * SOURCE_EDGE + x;
    }
}
