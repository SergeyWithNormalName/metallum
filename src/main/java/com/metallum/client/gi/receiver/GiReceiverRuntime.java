package com.metallum.client.gi.receiver;

import com.metallum.client.gi.GiRuntimeStages;
import com.metallum.client.gi.debug.GiTransportDebugSettings;

import java.util.Objects;

/** Explicit, fail-closed admission for the G5 vertex-only terrain receiver. */
public final class GiReceiverRuntime {
    public static final String RECEIVER_ENV = "METALLUM_GI_G5_RECEIVER";
    public static final String ARM_ENV = "METALLUM_GI_G5_RECEIVER_ARM";

    public enum AdmissionState { DISABLED, WAITING, READY, INVALID }
    public enum CarrierState { UNKNOWN, SAFE, CONFLICT }

    /** One immutable synchronized census used for the terminal benchmark decision. */
    public record FinalSnapshot(
            AdmissionState state,
            CarrierState carrierState,
            String invalidReason,
            long carrierSkipCount,
            String lastCarrierSkipReason,
            boolean benchmarkReceiptEmitted,
            boolean terrainDrawEncoded,
            long drawnG5CarrierSlices,
            long successfulCarrierWrites
    ) {
    }

    /** Mutable per-device state; all transitions are synchronized and INVALID is terminal. */
    public static final class Admission {
        private final GiRuntimeStages.ReceiverRequest request;
        private AdmissionState state;
        private CarrierState carrierState = CarrierState.UNKNOWN;
        private String invalidReason;
        private long carrierSkipCount;
        private String lastCarrierSkipReason = "none";
        private boolean benchmarkReceiptEmitted;
        private boolean terrainDrawEncoded;
        private long drawnG5CarrierSlices;
        private long carrierWriteBaseline;

        public Admission(final GiRuntimeStages.ReceiverRequest request) {
            this.request = Objects.requireNonNull(request, "request");
            if (!request.valid()) {
                this.state = AdmissionState.INVALID;
                this.invalidReason = request.invalidReason();
            } else if (!request.enabled()) {
                this.state = AdmissionState.DISABLED;
                this.invalidReason = "none";
            } else {
                this.state = AdmissionState.WAITING;
                this.invalidReason = "none";
            }
        }

        public synchronized GiRuntimeStages.ReceiverRequest request() {
            return this.request;
        }

        public synchronized AdmissionState state() {
            return this.state;
        }

        public synchronized CarrierState carrierState() {
            return this.carrierState;
        }

        public synchronized String invalidReason() {
            return this.invalidReason;
        }

        public synchronized long carrierSkipCount() {
            return this.carrierSkipCount;
        }

        public synchronized String lastCarrierSkipReason() {
            return this.lastCarrierSkipReason;
        }

        public synchronized boolean benchmarkReceiptEmitted() {
            return this.benchmarkReceiptEmitted;
        }

        public synchronized void reportBenchmarkReceiptEmitted() {
            if (this.state == AdmissionState.READY) {
                this.benchmarkReceiptEmitted = true;
            }
        }

        public synchronized boolean terrainDrawEncoded() {
            return this.terrainDrawEncoded;
        }

        public synchronized void reportTerrainDrawEncoded(final long drawnCarrierSlices) {
            if (drawnCarrierSlices <= 0L
                    || this.state == AdmissionState.DISABLED
                    || this.state == AdmissionState.INVALID) {
                return;
            }
            this.terrainDrawEncoded = true;
            try {
                this.drawnG5CarrierSlices = Math.addExact(
                        this.drawnG5CarrierSlices,
                        drawnCarrierSlices
                );
            } catch (ArithmeticException overflow) {
                this.drawnG5CarrierSlices = Long.MAX_VALUE;
            }
        }

        public synchronized long drawnG5CarrierSlices() {
            return this.drawnG5CarrierSlices;
        }

        public synchronized void beginCarrierWriteCensus(final long processWriteCount) {
            this.carrierWriteBaseline = Math.max(0L, processWriteCount);
        }

        public synchronized long successfulCarrierWrites(final long processWriteCount) {
            return Math.max(0L, processWriteCount - this.carrierWriteBaseline);
        }

        public synchronized FinalSnapshot finalSnapshot(final long processWriteCount) {
            return new FinalSnapshot(
                    this.state,
                    this.carrierState,
                    this.invalidReason,
                    this.carrierSkipCount,
                    this.lastCarrierSkipReason,
                    this.benchmarkReceiptEmitted,
                    this.terrainDrawEncoded,
                    this.drawnG5CarrierSlices,
                    Math.max(0L, processWriteCount - this.carrierWriteBaseline)
            );
        }

        public synchronized void reportNativeReady() {
            if (this.state == AdmissionState.WAITING) {
                this.state = AdmissionState.READY;
            }
        }

        public synchronized void reportCarrierSafe() {
            if (this.state != AdmissionState.INVALID
                    && this.state != AdmissionState.DISABLED
                    && this.carrierState == CarrierState.UNKNOWN) {
                this.carrierState = CarrierState.SAFE;
            }
        }

        /** A carrier collision cannot be healed later in the same renderer generation. */
        public synchronized void reportCarrierConflict(final String reason) {
            if (this.state == AdmissionState.DISABLED || this.state == AdmissionState.INVALID) {
                return;
            }
            this.carrierState = CarrierState.CONFLICT;
            this.state = AdmissionState.INVALID;
            this.invalidReason = reason == null || reason.isBlank()
                    ? "terrain face carrier conflict"
                    : reason;
        }

        /** A compact-layout conflict rejects this renderer generation terminally. */
        public synchronized void reportCarrierSkip(final String reason) {
            if (this.state == AdmissionState.DISABLED || this.state == AdmissionState.INVALID) {
                return;
            }
            this.carrierSkipCount++;
            this.lastCarrierSkipReason = reason == null || reason.isBlank()
                    ? "terrain face carrier skipped"
                    : reason;
            this.carrierState = CarrierState.CONFLICT;
            this.state = AdmissionState.INVALID;
            this.invalidReason = this.lastCarrierSkipReason;
        }

        public synchronized void reportInvalid(final String reason) {
            if (this.state == AdmissionState.DISABLED || this.state == AdmissionState.INVALID) {
                return;
            }
            this.state = AdmissionState.INVALID;
            this.invalidReason = reason == null || reason.isBlank()
                    ? "unspecified G5 receiver failure"
                    : reason;
        }

        /** Unknown and conflicting carrier states are both passed to native as unsafe. */
        public synchronized boolean carrierSafe() {
            return this.carrierState == CarrierState.SAFE
                    && this.state != AdmissionState.INVALID;
        }

        public synchronized boolean shaderFlavorRequested() {
            return this.request.requestsTransportResources();
        }

        public synchronized boolean bindingAllowed() {
            return this.state == AdmissionState.READY;
        }
    }

    private static final GiRuntimeStages.ReceiverRequest REQUEST =
            GiRuntimeStages.resolveIsolatedReceiverRequest(
                    System.getenv(RECEIVER_ENV),
                    System.getenv(ARM_ENV),
                    GiRuntimeStages.enabled(System.getenv("METALLUM_GI_G2_CAPTURE")),
                    GiRuntimeStages.enabled(System.getenv("METALLUM_GI_G3_INJECT")),
                    GiRuntimeStages.enabled(System.getenv("METALLUM_GI_G4_TRANSPORT"))
                            || GiTransportDebugSettings.isEnabled()
            );
    private static volatile Admission admission = new Admission(REQUEST);

    private GiReceiverRuntime() {
    }

    public static GiRuntimeStages.ReceiverRequest request() {
        return REQUEST;
    }

    public static GiRuntimeStages.ReceiverArm arm() {
        return REQUEST.arm();
    }

    public static boolean isRequested() {
        return REQUEST.requestsTransportResources();
    }

    public static boolean requestsTransportPopulation() {
        return REQUEST.requestsTransportPopulation();
    }

    public static boolean isConfigurationInvalid() {
        return REQUEST.enabled() && !REQUEST.valid();
    }

    public static Admission admission() {
        return admission;
    }

    /**
     * Admits the version-locked Sodium position sideband before any G5 terrain can be emitted.
     * A mismatch is terminal and callers must abort G5 initialization.
     */
    public static boolean admitCompactPositionCarrier(final boolean exactVersions) {
        return admitCompactPositionCarrier(admission, exactVersions);
    }

    static boolean admitCompactPositionCarrier(
            final Admission target,
            final boolean exactVersions
    ) {
        if (!target.request().requestsTransportResources()) {
            return false;
        }
        if (!exactVersions) {
            String reason = "G5 compact position carrier requires Minecraft 26.2, Sodium "
                    + GiReceiverCompatibility.SODIUM_VERSION
                    + " and MixinExtras " + GiReceiverCompatibility.MIXIN_EXTRAS_VERSION;
            CompactPositionCarrierSafety.reportConflict(reason);
            if (target != admission) {
                target.reportCarrierConflict(reason);
            }
            return false;
        }
        if (!CompactPositionCarrierSafety.isSafe()) {
            target.reportCarrierConflict(CompactPositionCarrierSafety.conflictReason());
            return false;
        }
        target.reportCarrierSafe();
        return target.carrierSafe();
    }

    public static void resetDeviceState() {
        admission = new Admission(REQUEST);
    }
}
