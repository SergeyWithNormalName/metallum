package com.metallum.client.renderer.interpolation;

import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.RendererConfig;
import com.metallum.client.renderer.temporal.FrameSynthesisContract;

import java.util.Objects;
import java.util.Set;

/** Pure, executor-neutral policy evaluator for Apple MetalFX Frame Interpolation. */
public final class FrameInterpolationPolicy {
    public enum UpstreamMode {
        FIXED_TEMPORAL,
        SPATIAL,
        NATIVE,
        DYNAMIC_TEMPORAL
    }

    public enum EligibilityReason {
        ELIGIBLE_FIXED_TEMPORAL,
        ELIGIBLE_SPATIAL,
        USER_REQUEST_DISABLED,
        FEATURE_UNSUPPORTED,
        DISPLAY_SYNC_DISABLED,
        LIVE_PRESENTATION_PROFILE_UNVALIDATED,
        REFRESH_RATE_UNSATISFIED,
        CADENCE_OUT_OF_BOUNDS,
        UNSUPPORTED_UPSTREAM_MODE,
        HISTORY_DISCONTINUITY
    }

    public enum EffectiveReason {
        ADMITTED_FIXED_TEMPORAL,
        ADMITTED_SPATIAL,
        NATIVE_PROFILE_UNVALIDATED,
        NOT_PROFILE_ELIGIBLE
    }

    public record Evaluation(
            boolean requested,
            boolean profileEligible,
            boolean effectiveAdmitted,
            EligibilityReason eligibilityReason,
            EffectiveReason effectiveReason
    ) {
        public Evaluation {
            Objects.requireNonNull(eligibilityReason, "eligibilityReason");
            Objects.requireNonNull(effectiveReason, "effectiveReason");
        }
    }

    private FrameInterpolationPolicy() {
    }

    public static Evaluation evaluate(
            final RendererConfig config,
            final MetalCapabilities capabilities,
            final UpstreamMode upstreamMode,
            final boolean displaySyncEnabled,
            final boolean livePresentationProfileValidated,
            final double renderFps,
            final Set<FrameSynthesisContract.Discontinuity> discontinuities
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(upstreamMode, "upstreamMode");
        Objects.requireNonNull(discontinuities, "discontinuities");

        boolean requested = config.frameInterpolation();
        if (!requested) {
            return new Evaluation(
                    false, false, false,
                    EligibilityReason.USER_REQUEST_DISABLED,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        if (!capabilities.supports(MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION)) {
            return new Evaluation(
                    true, false, false,
                    EligibilityReason.FEATURE_UNSUPPORTED,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        if (!displaySyncEnabled) {
            return new Evaluation(
                    true, false, false,
                    EligibilityReason.DISPLAY_SYNC_DISABLED,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        if (!livePresentationProfileValidated) {
            return new Evaluation(
                    true, false, false,
                    EligibilityReason.LIVE_PRESENTATION_PROFILE_UNVALIDATED,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        MetalCapabilities.DisplayCapabilities display = capabilities.displayCapabilities();
        if (!display.refreshKnown() || display.maximumFramesPerSecond() < 60) {
            return new Evaluation(
                    true, false, false,
                    EligibilityReason.REFRESH_RATE_UNSATISFIED,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        double targetHz = display.maximumFramesPerSecond();
        double desiredRealHz = targetHz / 2.0;
        double lowerBound = Math.max(30.0, desiredRealHz * 0.85);
        double upperBound = desiredRealHz * 1.05;

        if (!Double.isFinite(renderFps) || renderFps < lowerBound || renderFps > upperBound) {
            return new Evaluation(
                    true, false, false,
                    EligibilityReason.CADENCE_OUT_OF_BOUNDS,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        if (upstreamMode != UpstreamMode.FIXED_TEMPORAL && upstreamMode != UpstreamMode.SPATIAL) {
            return new Evaluation(
                    true, false, false,
                    EligibilityReason.UNSUPPORTED_UPSTREAM_MODE,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        if (!discontinuities.isEmpty()) {
            return new Evaluation(
                    true, false, false,
                    EligibilityReason.HISTORY_DISCONTINUITY,
                    EffectiveReason.NOT_PROFILE_ELIGIBLE
            );
        }

        if (!capabilities.frameInterpolationProfile().nativeProfileValidated()) {
            return new Evaluation(
                    true, true, false,
                    upstreamMode == UpstreamMode.SPATIAL
                            ? EligibilityReason.ELIGIBLE_SPATIAL
                            : EligibilityReason.ELIGIBLE_FIXED_TEMPORAL,
                    EffectiveReason.NATIVE_PROFILE_UNVALIDATED
            );
        }

        return new Evaluation(
                true, true, true,
                upstreamMode == UpstreamMode.SPATIAL
                        ? EligibilityReason.ELIGIBLE_SPATIAL
                        : EligibilityReason.ELIGIBLE_FIXED_TEMPORAL,
                upstreamMode == UpstreamMode.SPATIAL
                        ? EffectiveReason.ADMITTED_SPATIAL
                        : EffectiveReason.ADMITTED_FIXED_TEMPORAL
        );
    }
}
