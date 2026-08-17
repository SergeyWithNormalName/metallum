package com.metallum.client.benchmark;

/**
 * Diagnostic shader ablation modes for Benchmark Environment v2.
 *
 * <p>Diagnostic ablations isolate the marginal GPU cost of individual lighting subsystems
 * during diagnostic screening runs. When diagnostics are off (default {@link #FULL_ADVANCED}),
 * all preprocessor ablation defines evaluate to 0, ensuring zero production hot-path branches.</p>
 */
public enum DiagnosticAblationMode {
    FULL_ADVANCED("FULL_ADVANCED", 0, 0, 0, 0),
    NO_L3_RECEIVER("NO_L3_RECEIVER", 1, 0, 0, 0),
    NO_L4_RECEIVER("NO_L4_RECEIVER", 0, 1, 0, 0),
    NO_L6_RECEIVER("NO_L6_RECEIVER", 0, 0, 1, 0),
    NO_L3_L4("NO_L3_L4", 1, 1, 0, 0),
    NO_L3_L6("NO_L3_L6", 1, 0, 1, 0),
    NO_L4_L6("NO_L4_L6", 0, 1, 1, 0),
    NO_L3_L4_L6("NO_L3_L4_L6", 1, 1, 1, 0),
    NO_SURFACE_PBR("NO_SURFACE_PBR", 0, 0, 0, 1);

    private final String id;
    private final int ablateL3;
    private final int ablateL4;
    private final int ablateL6;
    private final int ablatePbr;

    DiagnosticAblationMode(String id, int ablateL3, int ablateL4, int ablateL6, int ablatePbr) {
        this.id = id;
        this.ablateL3 = ablateL3;
        this.ablateL4 = ablateL4;
        this.ablateL6 = ablateL6;
        this.ablatePbr = ablatePbr;
    }

    public String id() {
        return id;
    }

    public int ablateL3() {
        return ablateL3;
    }

    public int ablateL4() {
        return ablateL4;
    }

    public int ablateL6() {
        return ablateL6;
    }

    public int ablatePbr() {
        return ablatePbr;
    }

    public static DiagnosticAblationMode getSystemCurrent() {
        String prop = System.getProperty("metallum.diagnostic.ablation");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("METALLUM_DIAGNOSTIC_ABLATION");
        }
        if (prop == null || prop.isBlank()) {
            return FULL_ADVANCED;
        }
        for (DiagnosticAblationMode mode : values()) {
            if (mode.id.equalsIgnoreCase(prop.trim())) {
                return mode;
            }
        }
        return FULL_ADVANCED;
    }
}
