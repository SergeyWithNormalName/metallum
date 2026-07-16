package com.metallum.client.lighting;

import java.util.Objects;

/**
 * Process-local, fail-closed admission state for the L3 lighting generation.
 *
 * <p>Collection follows the user's request so the first admitted frame already
 * has a registry snapshot. GPU work and shader selection follow {@link
 * #isActive()} only after both native and shader preflight have succeeded.</p>
 */
public final class AdvancedLightingRuntime {
    private enum GateState {
        PENDING,
        READY,
        REJECTED
    }

    public record Admission(boolean ready, long epoch, String blocker) {
        public Admission {
            if (epoch < 0L) {
                throw new IllegalArgumentException("Admission epoch must be non-negative");
            }
            blocker = blocker == null ? "" : blocker;
            if (ready && !blocker.isEmpty()) {
                throw new IllegalArgumentException("Ready admission cannot have a blocker");
            }
        }
    }

    private static volatile boolean requested;
    private static volatile GateState nativeState = GateState.PENDING;
    private static volatile GateState shaderState = GateState.PENDING;
    private static volatile GateState registryState = GateState.READY;
    private static volatile boolean active;
    private static String nativeBlocker = "native lighting backend is not initialized";
    private static String shaderBlocker = "Advanced shader coverage is not initialized";
    private static String registryBlocker = "";
    private static long epoch;
    private static long registryAdmissionGeneration;

    private AdvancedLightingRuntime() {
    }

    public static synchronized void reset() {
        requested = false;
        nativeState = GateState.PENDING;
        shaderState = GateState.PENDING;
        registryState = GateState.READY;
        active = false;
        nativeBlocker = "native lighting backend is not initialized";
        shaderBlocker = "Advanced shader coverage is not initialized";
        registryBlocker = "";
        registryAdmissionGeneration = AdvancedLightRegistry.global().resetAdmissionHealth();
        AdvancedLightRegistry.global().clear();
        epoch = Math.addExact(epoch, 1L);
    }

    public static synchronized void configureRequested(final boolean value) {
        if (requested == value) {
            return;
        }
        requested = value;
        active = false;
        if (!value) {
            AdvancedLightRegistry.global().clear();
        }
        epoch = Math.addExact(epoch, 1L);
    }

    public static synchronized void reportNativePending(final String blocker) {
        installNativeState(GateState.PENDING, requireBlocker(blocker));
    }

    public static synchronized void reportNativeAdmission(
            final boolean ready,
            final String blocker
    ) {
        installNativeState(
                ready ? GateState.READY : GateState.REJECTED,
                ready ? "" : requireBlocker(blocker)
        );
    }

    public static synchronized void reportShaderPending(final String blocker) {
        installShaderState(GateState.PENDING, requireBlocker(blocker));
    }

    public static synchronized void reportShaderAdmission(
            final boolean ready,
            final String blocker
    ) {
        installShaderState(
                ready ? GateState.READY : GateState.REJECTED,
                ready ? "" : requireBlocker(blocker)
        );
    }

    public static synchronized void reportRegistryAdmission(
            final boolean ready,
            final String blocker
    ) {
        GateState state = ready ? GateState.READY : GateState.REJECTED;
        String checked = ready ? "" : requireBlocker(blocker);
        installRegistryState(state, checked);
    }

    static synchronized void reportRegistryFailure(
            final long failedGeneration,
            final String blocker
    ) {
        String checked = requireBlocker(blocker);
        if (!AdvancedLightRegistry.global().matchesAdmissionFailure(failedGeneration)) {
            return;
        }
        installRegistryState(GateState.REJECTED, checked);
    }

    private static void installRegistryState(
            final GateState state,
            final String blocker
    ) {
        if (registryState == state && registryBlocker.equals(blocker)) {
            return;
        }
        registryState = state;
        registryBlocker = blocker;
        if (state != GateState.READY) {
            active = false;
            AdvancedLightRegistry.global().clear();
        }
        epoch = Math.addExact(epoch, 1L);
    }

    public static synchronized Admission admission() {
        if (!requested) {
            return new Admission(false, epoch, "Advanced Lighting is not requested");
        }
        if (nativeState != GateState.READY) {
            return new Admission(false, epoch, nativeBlocker);
        }
        if (shaderState != GateState.READY) {
            return new Admission(false, epoch, shaderBlocker);
        }
        if (registryState != GateState.READY) {
            return new Admission(false, epoch, registryBlocker);
        }
        return new Admission(true, epoch, "");
    }

    public static synchronized void admitGeneration(final boolean value) {
        if (!tryAdmitGeneration(epoch, value)) {
            throw new IllegalStateException("Cannot activate an unadmitted Advanced generation");
        }
    }

    /**
     * Atomically commits a renderer generation only while the admission snapshot used to
     * construct it is still current.
     *
     * <p>The caller must discard any speculative Advanced resources when this returns false.
     * Admission reads registry health only in the established Runtime -&gt; Registry lock order;
     * {@code failClosed} releases the Registry monitor before reporting to Runtime.</p>
     */
    public static synchronized boolean tryAdmitGeneration(
            final long expectedEpoch,
            final boolean value
    ) {
        if (expectedEpoch < 0L) {
            throw new IllegalArgumentException("Expected admission epoch must be non-negative");
        }
        if (epoch != expectedEpoch
                || (value && (!admission().ready()
                || !AdvancedLightRegistry.global().matchesHealthyAdmission(
                        registryAdmissionGeneration
                )))) {
            return false;
        }
        active = value;
        return true;
    }

    public static boolean shouldCollect() {
        return requested
                && nativeState != GateState.REJECTED
                && shaderState != GateState.REJECTED
                && registryState != GateState.REJECTED;
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isRequested() {
        return requested;
    }

    public static synchronized boolean isNativeReady() {
        return nativeState == GateState.READY;
    }

    public static synchronized boolean isShaderReady() {
        return shaderState == GateState.READY;
    }

    private static void installNativeState(final GateState state, final String blocker) {
        if (nativeState == state && nativeBlocker.equals(blocker)) {
            return;
        }
        nativeState = state;
        nativeBlocker = blocker;
        if (state != GateState.READY) {
            active = false;
        }
        if (state == GateState.REJECTED) {
            AdvancedLightRegistry.global().clear();
        }
        epoch = Math.addExact(epoch, 1L);
    }

    private static void installShaderState(final GateState state, final String blocker) {
        if (shaderState == state && shaderBlocker.equals(blocker)) {
            return;
        }
        shaderState = state;
        shaderBlocker = blocker;
        if (state != GateState.READY) {
            active = false;
        }
        if (state == GateState.REJECTED) {
            AdvancedLightRegistry.global().clear();
        }
        epoch = Math.addExact(epoch, 1L);
    }

    private static String requireBlocker(final String blocker) {
        String value = Objects.requireNonNull(blocker, "blocker").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Rejected admission needs a blocker");
        }
        return value;
    }
}
