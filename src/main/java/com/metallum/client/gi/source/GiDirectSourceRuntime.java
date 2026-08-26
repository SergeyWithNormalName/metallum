package com.metallum.client.gi.source;

import java.util.Locale;

/** Explicit field-only G3 admission; absence of the environment variable is structural off. */
public final class GiDirectSourceRuntime {
    public static final String INJECT_ENV = "METALLUM_GI_G3_INJECT";

    private GiDirectSourceRuntime() {
    }

    public static boolean isRequested() {
        String value = System.getenv(INJECT_ENV);
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
