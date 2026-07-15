package com.metallum.client.hdr;

/** Startup request and committed shader-coverage state for the L2 material contract. */
public final class MetallumMaterialState {
    public record Admission(boolean active, long coverageEpoch) {
    }

    private static volatile boolean requested;
    private static volatile boolean fp16SceneRequested;
    private static volatile boolean generationActive;

    private MetallumMaterialState() {
    }

    public static boolean isRequested() {
        return requested;
    }

    public static boolean isActive() {
        return requested && MetallumMaterialPreflightGate.isActive();
    }

    /** Atomically snapshots the committed shader coverage and its generation epoch. */
    public static Admission admission() {
        synchronized (MetallumMaterialPreflightGate.class) {
            return new Admission(
                    requested && MetallumMaterialPreflightGate.isActive(),
                    MetallumMaterialPreflightGate.epoch()
            );
        }
    }

    /** True only after one immutable renderer generation admitted METALLUM. */
    public static boolean isGenerationActive() {
        return generationActive;
    }

    /** Startup-fixed storage decision: HDR material output needs actual FP16 radiance. */
    public static boolean requiresFp16Scene() {
        return requested && fp16SceneRequested;
    }

    /** Validates that the startup-fixed MainTarget can serve the requested output generation. */
    public static boolean isSceneStorageCompatible(final boolean hdrOutput) {
        return requested && (fp16SceneRequested || !hdrOutput);
    }

    /**
     * Clamps only the output axis when a startup RGBA8 MainTarget cannot carry
     * actual HDR radiance. A startup FP16 target remains valid for both HDR and
     * SDR generations; changing the lighting axis is never part of this rule.
     */
    public static HdrOutputMode resolveCompatibleOutput(final HdrOutputMode requestedOutput) {
        if (requestedOutput == null) {
            throw new NullPointerException("requestedOutput");
        }
        return requested && !fp16SceneRequested && requestedOutput != HdrOutputMode.SDR
                ? HdrOutputMode.SDR
                : requestedOutput;
    }

    public static void configure(
            final boolean improvedLighting,
            final boolean hdrOutputRequested
    ) {
        requested = improvedLighting;
        fp16SceneRequested = improvedLighting && hdrOutputRequested;
        generationActive = false;
        MetallumMaterialPreflightGate.reset();
    }

    public static void publishGeneration(final boolean materialGenerationActive) {
        generationActive = requested && materialGenerationActive;
    }

    public static void reset() {
        requested = false;
        fp16SceneRequested = false;
        generationActive = false;
        MetallumMaterialPreflightGate.reset();
    }
}
