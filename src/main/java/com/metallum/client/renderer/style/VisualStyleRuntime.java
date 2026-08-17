package com.metallum.client.renderer.style;

import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.TemporalResetEvents;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local owner of the active visual style and its immutable rendering profile.
 *
 * <p>Provides zero-allocation reads on render hot paths and coordinates one-shot temporal
 * history invalidation on live style transitions.</p>
 */
public final class VisualStyleRuntime {
    private static final AtomicReference<VisualStyle> ACTIVE_STYLE =
            new AtomicReference<>(VisualStyle.DEFAULT);
    private static volatile VisualStyleProfile ACTIVE_PROFILE =
            VisualStyleProfiles.profile(VisualStyle.DEFAULT);

    private VisualStyleRuntime() {
    }

    /**
     * Initializes the active style during renderer/device startup from persistent configuration.
     *
     * <p>This operation MUST NOT emit a {@link FrameState.HistoryResetReason#VISUAL_STYLE_CHANGE}
     * event.</p>
     */
    public static void initialize(final VisualStyle initialStyle) {
        VisualStyle safe = initialStyle != null ? initialStyle : VisualStyle.DEFAULT;
        ACTIVE_STYLE.set(safe);
        ACTIVE_PROFILE = VisualStyleProfiles.profile(safe);
    }

    /**
     * Updates the active visual style live at runtime.
     *
     * <p>If the style actually changes, updates the active profile and signals exactly one
     * {@link FrameState.HistoryResetReason#VISUAL_STYLE_CHANGE} temporal reset event.
     * Re-selecting the currently active style is a no-op.</p>
     *
     * @param newStyle the requested visual style
     * @return {@code true} if the active style changed, {@code false} if it was already active
     */
    public static boolean setStyle(final VisualStyle newStyle) {
        VisualStyle target = newStyle != null ? newStyle : VisualStyle.DEFAULT;
        VisualStyle current = ACTIVE_STYLE.get();
        if (current == target) {
            return false;
        }
        if (ACTIVE_STYLE.compareAndSet(current, target)) {
            ACTIVE_PROFILE = VisualStyleProfiles.profile(target);
            TemporalResetEvents.signal(FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE);
            return true;
        }
        // Handle concurrent update retry
        return setStyle(target);
    }

    /**
     * Returns the currently active visual style.
     */
    public static VisualStyle activeStyle() {
        return ACTIVE_STYLE.get();
    }

    /**
     * Returns the currently active immutable visual style profile without per-frame allocations.
     */
    public static VisualStyleProfile activeProfile() {
        return ACTIVE_PROFILE;
    }

    /**
     * Returns the celestial lighting policy of the active visual style profile.
     */
    public static CelestialLightingProfile activeCelestialLighting() {
        return ACTIVE_PROFILE.celestialLighting();
    }

    /**
     * Test isolation helper resetting runtime state to default without emitting reset events.
     */
    public static void resetForTests() {
        ACTIVE_STYLE.set(VisualStyle.DEFAULT);
        ACTIVE_PROFILE = VisualStyleProfiles.profile(VisualStyle.DEFAULT);
    }
}
