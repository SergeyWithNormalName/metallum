package com.metallum.client.gi.transport;

import java.util.Locale;

/** Explicit diagnostic-only G4 admission; absence of the environment flag is structural off. */
public final class GiTransportRuntime {
    public static final String TRANSPORT_ENV = "METALLUM_GI_G4_TRANSPORT";

    private GiTransportRuntime() {
    }

    public static boolean isRequested() {
        return isEnabled(System.getenv(TRANSPORT_ENV));
    }

    static boolean isEnabled(final String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
