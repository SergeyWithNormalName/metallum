package com.metallum.client.renderer.temporal;

import java.util.Objects;
import java.util.Set;

/** Owns previous-frame identity and turns discontinuities into one-frame history resets. */
public final class FrameStateTracker {
    private FrameState previous;
    private long nextFrameId;
    private long historyGeneration;

    public FrameState publish(
            final FrameState candidate,
            final Set<FrameState.HistoryResetReason> eventReasons
    ) {
        FrameState published = this.prepare(candidate, eventReasons);
        this.commit(published);
        return published;
    }

    /** Builds a candidate without advancing previous state until native publication succeeds. */
    public FrameState prepare(
            final FrameState candidate,
            final Set<FrameState.HistoryResetReason> eventReasons
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Set<FrameState.HistoryResetReason> resets = FrameState.transitionResetReasons(
                this.previous,
                candidate,
                Objects.requireNonNull(eventReasons, "eventReasons")
        );
        long publishedHistoryGeneration = resets.isEmpty()
                ? this.historyGeneration
                : Math.addExact(this.historyGeneration, 1L);

        boolean reset = !resets.isEmpty();
        FrameState.Transforms previousTransforms = reset || this.previous == null
                ? candidate.currentTransforms()
                : this.previous.currentTransforms();
        FrameState.CameraPosition previousPosition = reset || this.previous == null
                ? candidate.currentCameraPosition()
                : this.previous.currentCameraPosition();
        FrameState published = new FrameState(
                candidate.contract(),
                this.nextFrameId,
                candidate.rendererGenerationId(),
                publishedHistoryGeneration,
                candidate.renderContractGenerationId(),
                candidate.lightingGenerationId(),
                candidate.outputGenerationId(),
                candidate.renderContractMode(),
                candidate.lightingModel(),
                candidate.outputMode(),
                candidate.lightingPreset(),
                candidate.featureMask(),
                candidate.executorKind(),
                candidate.frameGraphVersion(),
                candidate.resourceBytes(),
                candidate.advancedLightingWork(),
                candidate.currentTransforms(),
                previousTransforms,
                candidate.renderExtent(),
                candidate.displayExtent(),
                candidate.exposure(),
                candidate.preExposure(),
                JitterSequence.sample(this.nextFrameId, TemporalDiagnostics.configured() ? 1.0 : 0.0),
                resets,
                candidate.submitIndex(),
                candidate.inFlightSlot(),
                candidate.deltaSeconds(),
                candidate.nearPlane(),
                candidate.farPlane(),
                candidate.currentCameraPosition(),
                previousPosition,
                candidate.worldIdentity(),
                candidate.dimensionIdentity(),
                candidate.currentDisplayHeadroom(),
                candidate.potentialDisplayHeadroom()
        );
        return published;
    }

    public long nextFrameId() {
        return this.nextFrameId;
    }

    public void commit(final FrameState published) {
        Objects.requireNonNull(published, "published");
        if (published.frameId() != this.nextFrameId) {
            throw new IllegalArgumentException("FrameState commit does not match the prepared frame ID");
        }
        this.previous = published;
        this.historyGeneration = published.historyGeneration();
        this.nextFrameId = Math.addExact(this.nextFrameId, 1L);
    }

    public FrameState previous() {
        return this.previous;
    }

    public void reset() {
        this.previous = null;
        this.nextFrameId = 0L;
        this.historyGeneration = 0L;
    }
}
