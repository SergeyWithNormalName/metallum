package com.metallum.client.renderer.interpolation;

import com.metallum.client.metalfx.TemporalScalingMode;
import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.RendererConfig;

import java.util.Objects;

/** Pure admission for FI Auto's non-persistent, known-bounded upstream profile. */
public final class FrameInterpolationCompatibilityProfile {
    public static final TemporalScalingMode TEMPORAL_MODE = TemporalScalingMode.ULTRA_PERFORMANCE;
    /** Bounded probation profile; runtime presentedTime evidence decides whether it stays active. */
    public static final int SOURCE_FRAME_LIMIT = 30;

    public enum Reason {
        ACTIVE,
        USER_DISABLED,
        FRAME_INTERPOLATION_UNSUPPORTED,
        TEMPORAL_UNSUPPORTED,
        NATIVE_PROFILE_UNVALIDATED,
        DISPLAY_SYNC_DISABLED,
        DISPLAY_REFRESH_UNSUPPORTED
    }

    public record Decision(boolean active, Reason reason) {
        public Decision {
            Objects.requireNonNull(reason, "reason");
            if (active != (reason == Reason.ACTIVE)) {
                throw new IllegalArgumentException("FI compatibility decision and reason disagree");
            }
        }

        public TemporalScalingMode temporalMode() {
            return active ? TEMPORAL_MODE : TemporalScalingMode.OFF;
        }

        public int sourceFrameLimit() {
            return active ? SOURCE_FRAME_LIMIT : 0;
        }
    }

    private FrameInterpolationCompatibilityProfile() {
    }

    public static Decision evaluate(
            final RendererConfig config,
            final MetalCapabilities capabilities,
            final boolean displaySyncEnabled
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(capabilities, "capabilities");
        if (!config.frameInterpolation()) {
            return new Decision(false, Reason.USER_DISABLED);
        }
        if (!capabilities.supports(MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION)) {
            return new Decision(false, Reason.FRAME_INTERPOLATION_UNSUPPORTED);
        }
        if (!capabilities.supports(MetalCapabilities.Feature.METALFX_TEMPORAL)
                || !capabilities.temporalProfile().diagnosticsSupported()) {
            return new Decision(false, Reason.TEMPORAL_UNSUPPORTED);
        }
        if (!capabilities.frameInterpolationProfile().nativeProfileValidated()) {
            return new Decision(false, Reason.NATIVE_PROFILE_UNVALIDATED);
        }
        if (!displaySyncEnabled) {
            return new Decision(false, Reason.DISPLAY_SYNC_DISABLED);
        }
        MetalCapabilities.DisplayCapabilities display = capabilities.displayCapabilities();
        if (!display.refreshKnown() || display.maximumFramesPerSecond() < 60) {
            return new Decision(false, Reason.DISPLAY_REFRESH_UNSUPPORTED);
        }
        return new Decision(true, Reason.ACTIVE);
    }

    public static int applySourceLimit(final int ordinaryLimit, final boolean profileActive) {
        return profileActive ? Math.min(Math.max(ordinaryLimit, 1), SOURCE_FRAME_LIMIT) : ordinaryLimit;
    }
}
