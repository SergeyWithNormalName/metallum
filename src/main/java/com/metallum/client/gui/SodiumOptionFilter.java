package com.metallum.client.gui;

import net.minecraft.resources.Identifier;
import java.util.Set;

public final class SodiumOptionFilter {
    public static final Set<Identifier> BLOCKED_OPTIONS = Set.of(
            Identifier.fromNamespaceAndPath("sodium", "performance.use_no_error_context"),
            Identifier.fromNamespaceAndPath("sodium", "general.fullscreen_resolution"),
            Identifier.fromNamespaceAndPath("sodium", "general.graphics_api"),
            Identifier.fromNamespaceAndPath("sodium", "quality.pixel_filtering_mode")
    );

    private SodiumOptionFilter() {
    }

    public static boolean isBlocked(final Identifier id) {
        return id != null && BLOCKED_OPTIONS.contains(id);
    }
}
