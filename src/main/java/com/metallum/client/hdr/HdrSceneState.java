package com.metallum.client.hdr;

/**
 * Startup-only gate for the FP16 scene path.
 *
 * <p>MainTarget and shader formats are selected during renderer construction,
 * so changing this value while the game is running would leave incompatible
 * resources and pipelines behind. Configuration changes therefore require a
 * restart, just like semantic MRT selection.</p>
 */
public final class HdrSceneState {
    private static volatile boolean requested;
    private static volatile HdrSourceEncoding configuredSourceEncoding = HdrSourceEncoding.SRGB;

    private HdrSceneState() {
    }

    public static boolean isRequested() {
        return requested;
    }

    public static HdrSourceEncoding sourceEncoding() {
        if (requested && SceneLinearPreflightGate.isActive()) {
            return HdrSourceEncoding.LINEAR;
        }
        // A requested FP16 scene whose preflight failed must remain wholly on
        // the legacy gamma contract, even if an old config requested LINEAR.
        return requested ? HdrSourceEncoding.SRGB : configuredSourceEncoding;
    }

    public static HdrSourceEncoding configuredSourceEncoding() {
        return configuredSourceEncoding;
    }

    public static void configure(final HdrConfig config, final EdrCapabilities capabilities) {
        SceneLinearPreflightGate.reset();
        configuredSourceEncoding = config.sourceEncoding();
        HdrMode mode = config.mode();
        boolean modeRequestsScene = mode == HdrMode.AUTO
                || mode == HdrMode.SCENE
                || (mode == HdrMode.ENHANCED && config.experimentalFp16());
        requested = modeRequestsScene && capabilities.isHdrDisplay();
    }

    public static void reset() {
        requested = false;
        configuredSourceEncoding = HdrSourceEncoding.SRGB;
        SceneLinearPreflightGate.reset();
    }
}
