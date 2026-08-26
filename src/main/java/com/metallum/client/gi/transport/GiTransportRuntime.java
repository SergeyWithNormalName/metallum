package com.metallum.client.gi.transport;

import java.util.Locale;

/** Explicit diagnostic-only G4 admission; absence of the environment flag is structural off. */
public final class GiTransportRuntime {
    public static final String TRANSPORT_ENV = "METALLUM_GI_G4_TRANSPORT";
    public enum AdmissionState { WAITING, READY, INVALID }

    private static final boolean BENCHMARK_ACTIVE = isEnabled(
            System.getenv("METALLUM_BENCHMARK")
    );
    private static final boolean REQUESTED = isEnabled(System.getenv(TRANSPORT_ENV));

    private static volatile boolean sourceReady;
    private static volatile boolean sourcePreparationStarted;
    private static volatile boolean benchmarkWarmupStarted;
    private static volatile boolean benchmarkMeasurementStarted;
    private static volatile AdmissionState admissionState = AdmissionState.WAITING;
    private static volatile String invalidReason = "none";

    private GiTransportRuntime() {
    }

    public static boolean isRequested() {
        return REQUESTED;
    }

    /** Resets the per-device diagnostic handshake before any G3/G4 owner is admitted. */
    public static void resetDeviceState() {
        sourceReady = false;
        sourcePreparationStarted = false;
        benchmarkWarmupStarted = false;
        benchmarkMeasurementStarted = false;
        admissionState = AdmissionState.WAITING;
        invalidReason = "none";
    }

    /** Publishes the exact G3 capability state without exposing its native owner. */
    public static void reportSourceReady(final boolean ready) {
        sourceReady = isRequested() && ready;
    }

    public static boolean isBenchmarkSourceReady() {
        return sourceReady;
    }

    /** Opens frozen G3 preparation only after the deterministic fixture is quiescent. */
    public static void beginSourcePreparation() {
        if (REQUESTED) {
            sourcePreparationStarted = true;
        }
    }

    public static boolean isSourcePreparationAllowed() {
        return sourcePreparationAllowed(BENCHMARK_ACTIVE, sourcePreparationStarted);
    }

    static boolean sourcePreparationAllowed(
            final boolean benchmarkActive,
            final boolean preparationStarted
    ) {
        return !benchmarkActive || preparationStarted;
    }

    public static boolean hasSourcePreparationStarted() {
        return sourcePreparationStarted;
    }

    /** The benchmark controller opens the sole allowed dispatch window at segment start. */
    public static void beginBenchmarkWarmup() {
        if (isRequested()) {
            benchmarkWarmupStarted = true;
        }
    }

    public static boolean isSubmissionAllowed() {
        return submissionAllowed(
                BENCHMARK_ACTIVE, benchmarkWarmupStarted, benchmarkMeasurementStarted,
                admissionState == AdmissionState.INVALID
        );
    }

    public static boolean isBenchmarkActive() {
        return BENCHMARK_ACTIVE;
    }

    public static boolean isBenchmarkWarmup() {
        return BENCHMARK_ACTIVE && benchmarkWarmupStarted && !benchmarkMeasurementStarted;
    }

    public static void beginBenchmarkMeasurement() {
        if (isRequested()) {
            benchmarkMeasurementStarted = true;
        }
    }

    static boolean submissionAllowed(
            final boolean benchmarkActive,
            final boolean warmupStarted,
            final boolean measurementStarted,
            final boolean invalid
    ) {
        return !invalid && (!benchmarkActive || (warmupStarted && !measurementStarted));
    }

    public static void reportResolvedReady() {
        if (isRequested() && admissionState == AdmissionState.WAITING) {
            admissionState = AdmissionState.READY;
        }
    }

    public static boolean isResolvedReady() {
        return admissionState == AdmissionState.READY;
    }

    /** READY may only transition terminally to INVALID; a later frame cannot heal drift. */
    public static void reportInvalid(final String reason) {
        if (isRequested()) {
            invalidReason = reason == null || reason.isBlank() ? "unspecified" : reason;
            admissionState = AdmissionState.INVALID;
        }
    }

    public static boolean isInvalid() {
        return admissionState == AdmissionState.INVALID;
    }

    public static String invalidReason() {
        return invalidReason;
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
