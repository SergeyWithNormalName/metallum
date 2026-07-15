package com.metallum.client.renderer.temporal;

/** Process-level opt-in; production temporal inputs remain unavailable. */
public final class TemporalDiagnostics {
    public static final String ENVIRONMENT_VARIABLE = "METALLUM_TEMPORAL_DIAGNOSTICS";
    private static final boolean CONFIGURED = "1".equals(System.getenv(ENVIRONMENT_VARIABLE));

    private TemporalDiagnostics() {
    }

    public static boolean configured() {
        return CONFIGURED;
    }
}
