package com.metallum.client.radiance;

import com.metallum.client.voxel.VoxelWorldToken;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe runtime coordinator and deterministic provenance/memory tracker for the radiance extraction.
 */
public final class RadianceRuntimeStorage {

    private static final RadianceRuntimeStorage INSTANCE = new RadianceRuntimeStorage();

    public static final long MAX_INFLIGHT_CANDIDATE_BYTES = 4 * 1024 * 1024L; // 4.0 MiB hard bound

    public record ProvenanceCounters(
            long acceptedSectionsExtracted,
            long acceptedSectionsPublished,
            long sectionsConsumed,
            long blocksEvaluated,
            long fallbackColors,
            long fallbackLights,
            long moddedFallbacks,
            long emissionHits,
            long missingSections,
            long surfaceLightNeighborSamples,
            long surfaceLightExposedFaces,
            long surfaceLightMissingNeighbors,
            long surfaceLightOpaqueSelfZeroButNeighborLit,
            long appliedPayloadsCount,
            long discardedPayloadsCount,
            long candidateQueueOverruns,
            long activeInflightBytes,
            long peakInflightBytes
    ) {
    }

    private final AtomicLong currentWorldGeneration = new AtomicLong(1L);
    private volatile @Nullable Object activeWorldIdentity;
    private volatile @Nullable VoxelWorldToken activeWorldToken;

    private final AtomicLong acceptedSectionsExtracted = new AtomicLong();
    private final AtomicLong acceptedSectionsPublished = new AtomicLong();
    private final AtomicLong sectionsConsumed = new AtomicLong();
    private final AtomicLong blocksEvaluated = new AtomicLong();
    private final AtomicLong fallbackColors = new AtomicLong();
    private final AtomicLong fallbackLights = new AtomicLong();
    private final AtomicLong moddedFallbacks = new AtomicLong();
    private final AtomicLong emissionHits = new AtomicLong();
    private final AtomicLong missingSections = new AtomicLong();

    private final AtomicLong surfaceLightNeighborSamples = new AtomicLong();
    private final AtomicLong surfaceLightExposedFaces = new AtomicLong();
    private final AtomicLong surfaceLightMissingNeighbors = new AtomicLong();
    private final AtomicLong surfaceLightOpaqueSelfZeroButNeighborLit = new AtomicLong();

    private final AtomicLong appliedPayloadsCount = new AtomicLong();
    private final AtomicLong discardedPayloadsCount = new AtomicLong();
    private final AtomicLong candidateQueueOverruns = new AtomicLong();
    private final AtomicLong activeInflightBytes = new AtomicLong();
    private final AtomicLong peakInflightBytes = new AtomicLong();

    private RadianceRuntimeStorage() {
    }

    public static RadianceRuntimeStorage global() {
        return INSTANCE;
    }

    public synchronized VoxelWorldToken openWorld(final Object worldIdentity, final String dimensionId) {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimensionId, "dimensionId");

        if (this.activeWorldIdentity == worldIdentity
                && this.activeWorldToken != null
                && this.activeWorldToken.dimensionId().equals(dimensionId)) {
            return this.activeWorldToken;
        }

        this.activeWorldIdentity = worldIdentity;
        long nextGen = this.currentWorldGeneration.incrementAndGet();
        VoxelWorldToken token = new VoxelWorldToken(nextGen, dimensionId);
        this.activeWorldToken = token;
        this.resetProvenanceCounters();
        return token;
    }

    public @Nullable VoxelWorldToken activeWorldToken() {
        return this.activeWorldToken;
    }

    public boolean isCurrentWorldGeneration(final long worldGeneration) {
        VoxelWorldToken token = this.activeWorldToken;
        return token != null && token.generation() == worldGeneration;
    }

    public boolean canAcceptCandidatePayload(final long estimatedBytes) {
        return this.activeInflightBytes.get() + estimatedBytes <= MAX_INFLIGHT_CANDIDATE_BYTES;
    }

    public void noteCandidateQueueOverrun() {
        this.candidateQueueOverruns.incrementAndGet();
    }

    public void noteSectionExtracted(
            final CompactSectionPayload payload,
            final int blockCount,
            final int fallbackLightCount,
            final int moddedFallbackCount,
            final int emissiveCount,
            final int neighborSamples,
            final int exposedFaces,
            final int missingNeighbors,
            final int opaqueLitNeighbors
    ) {
        this.acceptedSectionsExtracted.incrementAndGet();
        this.blocksEvaluated.addAndGet(blockCount);
        this.fallbackLights.addAndGet(fallbackLightCount);
        this.moddedFallbacks.addAndGet(moddedFallbackCount);
        this.emissionHits.addAndGet(emissiveCount);
        this.surfaceLightNeighborSamples.addAndGet(neighborSamples);
        this.surfaceLightExposedFaces.addAndGet(exposedFaces);
        this.surfaceLightMissingNeighbors.addAndGet(missingNeighbors);
        this.surfaceLightOpaqueSelfZeroButNeighborLit.addAndGet(opaqueLitNeighbors);

        long payloadBytes = payload.payloadByteSize();
        long currentInflight = this.activeInflightBytes.addAndGet(payloadBytes);
        this.peakInflightBytes.accumulateAndGet(currentInflight, Math::max);
    }

    public void notePayloadConsumed(final CompactSectionPayload payload) {
        this.sectionsConsumed.incrementAndGet();
        long payloadBytes = payload.payloadByteSize();
        this.activeInflightBytes.addAndGet(-payloadBytes);
    }

    public void notePayloadDiscarded(final CompactSectionPayload payload) {
        this.discardedPayloadsCount.incrementAndGet();
        long payloadBytes = payload.payloadByteSize();
        this.activeInflightBytes.addAndGet(-payloadBytes);
    }

    public void noteMissingSection() {
        this.missingSections.incrementAndGet();
    }

    public ProvenanceCounters snapshotProvenanceCounters() {
        return new ProvenanceCounters(
                this.acceptedSectionsExtracted.get(),
                this.acceptedSectionsPublished.get(),
                this.sectionsConsumed.get(),
                this.blocksEvaluated.get(),
                this.fallbackColors.get(),
                this.fallbackLights.get(),
                this.moddedFallbacks.get(),
                this.emissionHits.get(),
                this.missingSections.get(),
                this.surfaceLightNeighborSamples.get(),
                this.surfaceLightExposedFaces.get(),
                this.surfaceLightMissingNeighbors.get(),
                this.surfaceLightOpaqueSelfZeroButNeighborLit.get(),
                this.appliedPayloadsCount.get(),
                this.discardedPayloadsCount.get(),
                this.candidateQueueOverruns.get(),
                this.activeInflightBytes.get(),
                this.peakInflightBytes.get()
        );
    }

    public void resetProvenanceCounters() {
        this.acceptedSectionsExtracted.set(0L);
        this.acceptedSectionsPublished.set(0L);
        this.sectionsConsumed.set(0L);
        this.blocksEvaluated.set(0L);
        this.fallbackColors.set(0L);
        this.fallbackLights.set(0L);
        this.moddedFallbacks.set(0L);
        this.emissionHits.set(0L);
        this.missingSections.set(0L);
        this.surfaceLightNeighborSamples.set(0L);
        this.surfaceLightExposedFaces.set(0L);
        this.surfaceLightMissingNeighbors.set(0L);
        this.surfaceLightOpaqueSelfZeroButNeighborLit.set(0L);
        this.appliedPayloadsCount.set(0L);
        this.discardedPayloadsCount.set(0L);
        this.candidateQueueOverruns.set(0L);
        this.activeInflightBytes.set(0L);
        this.peakInflightBytes.set(0L);
    }
}
