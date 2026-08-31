package com.metallum.client.gi;

import com.metallum.client.gi.live.GiLiveRuntime;

import java.util.Locale;

/** Cross-stage admission rules shared by the isolated G5 receiver runtime. */
public final class GiRuntimeStages {
    private static final ReceiverRequest ENV_RECEIVER_REQUEST = GiLiveRuntime.isRequested()
            ? resolveReceiverRequest(null, null)
            : resolveIsolatedReceiverRequest(
                    System.getenv("METALLUM_GI_G5_RECEIVER"),
                    System.getenv("METALLUM_GI_G5_RECEIVER_ARM"),
                    enabled(System.getenv("METALLUM_GI_G2_CAPTURE")),
                    enabled(System.getenv("METALLUM_GI_G3_INJECT")),
                    enabled(System.getenv("METALLUM_GI_G4_TRANSPORT"))
            );

    public enum ReceiverArm {
        OFF(-1, false, false),
        CONTROL(0, true, false),
        CANDIDATE(1, true, false),
        FIELD(2, true, true);

        private final int nativeId;
        private final boolean requested;
        private final boolean populationRequested;

        ReceiverArm(
                final int nativeId,
                final boolean requested,
                final boolean populationRequested
        ) {
            this.nativeId = nativeId;
            this.requested = requested;
            this.populationRequested = populationRequested;
        }

        public int nativeId() {
            if (!this.requested) {
                throw new IllegalStateException("The OFF G5 arm has no native binding id");
            }
            return this.nativeId;
        }

        public boolean isRequested() {
            return this.requested;
        }

        public boolean requestsPopulation() {
            return this.populationRequested;
        }
    }

    public record ReceiverRequest(
            boolean enabled,
            ReceiverArm arm,
            boolean valid,
            String invalidReason
    ) {
        public ReceiverRequest {
            if (arm == null) {
                throw new IllegalArgumentException("G5 receiver arm is missing");
            }
            if (invalidReason == null) {
                throw new IllegalArgumentException("G5 receiver invalid reason is missing");
            }
            if (valid && !invalidReason.equals("none")) {
                throw new IllegalArgumentException("A valid G5 receiver request has an error");
            }
            if (!enabled && arm != ReceiverArm.OFF) {
                throw new IllegalArgumentException("A disabled G5 request selected a receiver arm");
            }
            if (enabled && valid && !arm.isRequested()) {
                throw new IllegalArgumentException("An enabled G5 request selected no receiver arm");
            }
        }

        public boolean requestsTransportResources() {
            return this.enabled && this.valid;
        }

        public boolean requestsTransportPopulation() {
            return this.requestsTransportResources() && this.arm.requestsPopulation();
        }
    }

    private GiRuntimeStages() {
    }

    /**
     * Resolves the explicit G5 enable and optional A/B arm. A blank arm selects the normal FIELD
     * route; any unknown non-blank value is terminally invalid.
     */
    public static ReceiverRequest resolveReceiverRequest(
            final String enabledValue,
            final String armValue
    ) {
        boolean enabled = enabled(enabledValue);
        if (!enabled) {
            return new ReceiverRequest(false, ReceiverArm.OFF, true, "none");
        }
        if (armValue == null || armValue.isBlank()) {
            return new ReceiverRequest(true, ReceiverArm.FIELD, true, "none");
        }
        ReceiverArm arm = switch (armValue.trim().toLowerCase(Locale.ROOT)) {
            case "control" -> ReceiverArm.CONTROL;
            case "candidate" -> ReceiverArm.CANDIDATE;
            case "field" -> ReceiverArm.FIELD;
            default -> ReceiverArm.OFF;
        };
        if (arm == ReceiverArm.OFF) {
            return new ReceiverRequest(
                    true, arm, false,
                    "METALLUM_GI_G5_RECEIVER_ARM must be control, candidate, or field"
            );
        }
        return new ReceiverRequest(true, arm, true, "none");
    }

    /**
     * Resolves the process-wide G5 request and rejects standalone G2/G3/G4 diagnostics. Those
     * modes have their own admissions and must never populate or claim the field behind a G5 arm.
     */
    public static ReceiverRequest resolveIsolatedReceiverRequest(
            final String enabledValue,
            final String armValue,
            final boolean standaloneG2,
            final boolean standaloneG3,
            final boolean standaloneG4
    ) {
        ReceiverRequest request = resolveReceiverRequest(enabledValue, armValue);
        if (request.requestsTransportResources()
                && (standaloneG2 || standaloneG3 || standaloneG4)) {
            return new ReceiverRequest(
                    true,
                    request.arm(),
                    false,
                    "G5 receiver arms cannot overlap standalone G2/G3/G4 diagnostics"
            );
        }
        return request;
    }

    /** G5 always requests the generic G4 allocation, but only FIELD requests population. */
    public static boolean transportResourcesRequested(
            final boolean g4Requested,
            final ReceiverRequest receiver
    ) {
        if (receiver == null) {
            throw new IllegalArgumentException("G5 receiver request is missing");
        }
        return g4Requested || receiver.requestsTransportResources();
    }

    public static boolean transportPopulationRequested(
            final boolean g4Requested,
            final ReceiverRequest receiver
    ) {
        if (receiver == null) {
            throw new IllegalArgumentException("G5 receiver request is missing");
        }
        return g4Requested || receiver.requestsTransportPopulation();
    }

    /** Process-wide route used by G4 to allocate a generic field owner for any valid G5 arm. */
    public static boolean requiresG4Resources() {
        return ENV_RECEIVER_REQUEST.requestsTransportResources();
    }

    /** CONTROL/CANDIDATE keep the G4 field zero; only FIELD requests physical population. */
    public static boolean requiresG4Population() {
        return ENV_RECEIVER_REQUEST.requestsTransportPopulation();
    }

    public static boolean enabled(final String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
