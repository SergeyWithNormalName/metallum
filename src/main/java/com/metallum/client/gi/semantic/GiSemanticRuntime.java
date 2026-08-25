package com.metallum.client.gi.semantic;

/** Explicit opt-in admission gate; absence of the environment variable is structural off. */
public final class GiSemanticRuntime {
    public static final String CAPTURE_ENV = "METALLUM_GI_G2_CAPTURE";

    private GiSemanticRuntime() {
    }

    public static boolean isRequested() {
        String value = System.getenv(CAPTURE_ENV);
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
