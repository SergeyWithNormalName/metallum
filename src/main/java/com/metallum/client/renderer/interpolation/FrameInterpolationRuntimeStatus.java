package com.metallum.client.renderer.interpolation;

import java.util.Objects;

/** Truthful session-local FI state; ACTIVE is never inferred from configuration alone. */
public record FrameInterpolationRuntimeStatus(
        State state,
        Reason reason,
        long sessionId,
        long presentedGeneratedCount
) {
    public enum State {
        DISABLED,
        WARMING,
        ACTIVE,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        USER_DISABLED,
        AWAITING_PRODUCTION_SOURCE,
        MEASURING_ON_GLASS,
        FRAME_INTERPOLATION_UNSUPPORTED,
        TEMPORAL_UNSUPPORTED,
        NATIVE_PROFILE_UNVALIDATED,
        DISPLAY_SYNC_DISABLED,
        DISPLAY_REFRESH_UNSUPPORTED,
        NATIVE_INTERPOLATOR_UNAVAILABLE,
        ON_GLASS_CADENCE,
        ON_GLASS_TIMESTAMP,
        WARMUP_TIMEOUT,
        COORDINATOR_NOT_INSTALLED,
        NATIVE_FACTORY_UNAVAILABLE,
        NATIVE_STATUS_INVALID
    }

    public FrameInterpolationRuntimeStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        if (sessionId < 0L || presentedGeneratedCount < 0L) {
            throw new IllegalArgumentException("FI session identity and shown count must be non-negative");
        }
        if (state == State.ACTIVE && reason != Reason.NONE) {
            throw new IllegalArgumentException("Active FI cannot carry an unavailability reason");
        }
    }

    public static FrameInterpolationRuntimeStatus fromPackedNative(
            final long packed,
            final long sessionId
    ) {
        int rawState = (int) (packed & 0xffL);
        int rawReason = (int) ((packed >>> 8) & 0xffL);
        long presentedGeneratedCount = packed >>> 16;
        return switch (rawState) {
            case 0 -> new FrameInterpolationRuntimeStatus(
                    State.DISABLED, Reason.NONE, sessionId, presentedGeneratedCount
            );
            case 1 -> switch (rawReason) {
                case 1 -> new FrameInterpolationRuntimeStatus(
                        State.WARMING,
                        Reason.MEASURING_ON_GLASS,
                        sessionId,
                        presentedGeneratedCount
                );
                case 5 -> new FrameInterpolationRuntimeStatus(
                        State.WARMING,
                        Reason.AWAITING_PRODUCTION_SOURCE,
                        sessionId,
                        presentedGeneratedCount
                );
                default -> unavailable(
                        Reason.NATIVE_STATUS_INVALID, sessionId, presentedGeneratedCount
                );
            };
            case 2 -> rawReason == 0
                    ? new FrameInterpolationRuntimeStatus(
                            State.ACTIVE, Reason.NONE, sessionId, presentedGeneratedCount
                    )
                    : unavailable(Reason.NATIVE_STATUS_INVALID, sessionId, presentedGeneratedCount);
            case 3 -> unavailable(
                    switch (rawReason) {
                        case 2 -> Reason.ON_GLASS_CADENCE;
                        case 3 -> Reason.ON_GLASS_TIMESTAMP;
                        case 4 -> Reason.NATIVE_INTERPOLATOR_UNAVAILABLE;
                        default -> Reason.NATIVE_STATUS_INVALID;
                    },
                    sessionId,
                    presentedGeneratedCount
            );
            default -> unavailable(Reason.NATIVE_STATUS_INVALID, sessionId, presentedGeneratedCount);
        };
    }

    public static FrameInterpolationRuntimeStatus fromCompatibilityDecision(
            final FrameInterpolationCompatibilityProfile.Decision decision,
            final long sessionId,
            final long presentedGeneratedCount
    ) {
        Objects.requireNonNull(decision, "decision");
        if (decision.active()) {
            return new FrameInterpolationRuntimeStatus(
                    State.WARMING,
                    Reason.MEASURING_ON_GLASS,
                    sessionId,
                    presentedGeneratedCount
            );
        }
        if (decision.reason() == FrameInterpolationCompatibilityProfile.Reason.USER_DISABLED) {
            return new FrameInterpolationRuntimeStatus(
                    State.DISABLED,
                    Reason.USER_DISABLED,
                    sessionId,
                    presentedGeneratedCount
            );
        }
        return unavailable(switch (decision.reason()) {
            case FRAME_INTERPOLATION_UNSUPPORTED -> Reason.FRAME_INTERPOLATION_UNSUPPORTED;
            case TEMPORAL_UNSUPPORTED -> Reason.TEMPORAL_UNSUPPORTED;
            case NATIVE_PROFILE_UNVALIDATED -> Reason.NATIVE_PROFILE_UNVALIDATED;
            case DISPLAY_SYNC_DISABLED -> Reason.DISPLAY_SYNC_DISABLED;
            case DISPLAY_REFRESH_UNSUPPORTED -> Reason.DISPLAY_REFRESH_UNSUPPORTED;
            case ACTIVE, USER_DISABLED -> Reason.NATIVE_STATUS_INVALID;
        }, sessionId, presentedGeneratedCount);
    }

    public static FrameInterpolationRuntimeStatus unavailable(
            final Reason reason,
            final long sessionId,
            final long presentedGeneratedCount
    ) {
        if (reason == Reason.NONE || reason == Reason.USER_DISABLED
                || reason == Reason.AWAITING_PRODUCTION_SOURCE
                || reason == Reason.MEASURING_ON_GLASS) {
            throw new IllegalArgumentException("Unavailable FI requires an unavailable reason");
        }
        return new FrameInterpolationRuntimeStatus(
                State.UNAVAILABLE,
                reason,
                sessionId,
                presentedGeneratedCount
        );
    }

    /**
     * Bounds FI probation without confusing CPU/GPU completion with proof of
     * a healthy display cadence. ACTIVE still comes exclusively from native
     * presented-time observations; this watchdog only prevents an endless
     * WARMING state when no comparable on-glass pairs ever arrive.
     */
    public static boolean warmupBudgetExhausted(
            final State state,
            final Reason reason,
            final long accumulatedActiveNanos,
            final long budgetNanos
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        if (accumulatedActiveNanos < 0L || budgetNanos <= 0L) {
            throw new IllegalArgumentException("FI warm-up budget must be positive and monotonic");
        }
        return state == State.WARMING
                && reason == Reason.MEASURING_ON_GLASS
                && accumulatedActiveNanos >= budgetNanos;
    }

    /**
     * Counts active render progress while capping one stalled frame's budget
     * contribution. Pause/AFK/iconified exclusion belongs to the caller,
     * using Vanilla's explicit throttle reason rather than inferred cadence.
     */
    public static long boundedWarmupSample(
            final long previousFrameNanos,
            final long currentFrameNanos,
            final long maximumContributionNanos
    ) {
        if (maximumContributionNanos <= 0L) {
            throw new IllegalArgumentException("FI warm-up sample contribution must be positive");
        }
        if (previousFrameNanos == Long.MIN_VALUE) {
            return 0L;
        }
        long delta = currentFrameNanos - previousFrameNanos;
        return delta > 0L ? Math.min(delta, maximumContributionNanos) : 0L;
    }
}
