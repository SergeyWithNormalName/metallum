package com.metallum.client.gi.semantic;

import com.metallum.client.gi.source.GiDirectSourceRuntime;

/** Explicit opt-in admission gate; absence of the environment variable is structural off. */
public final class GiSemanticRuntime {
    public static final String CAPTURE_ENV = "METALLUM_GI_G2_CAPTURE";

    private GiSemanticRuntime() {
    }

    public static boolean isRequested() {
        return isEnabled(System.getenv(CAPTURE_ENV)) || GiDirectSourceRuntime.isRequested();
    }

    private static boolean isEnabled(final String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
