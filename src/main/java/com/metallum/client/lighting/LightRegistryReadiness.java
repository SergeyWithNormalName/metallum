package com.metallum.client.lighting;

/** Structural readiness, runtime health and observed hook coverage. */
public record LightRegistryReadiness(
        int contractVersion,
        boolean structuralReady,
        boolean healthy,
        boolean runtimeHooksCovered,
        int observedHookMask,
        int requiredHookMask,
        long failureCount,
        String failureReason
) {
    public LightRegistryReadiness {
        failureReason = failureReason == null ? "" : failureReason;
        if (healthy && (!failureReason.isEmpty() || failureCount != 0L)) {
            throw new IllegalArgumentException("Healthy registry cannot retain a failure");
        }
    }
}
