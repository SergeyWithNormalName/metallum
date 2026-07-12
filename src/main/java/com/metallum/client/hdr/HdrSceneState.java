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
    private static volatile HdrSourceEncoding sourceEncoding = HdrSourceEncoding.SRGB;

    private HdrSceneState() {
    }

    public static boolean isRequested() {
        return requested;
    }

    public static HdrSourceEncoding sourceEncoding() {
        return sourceEncoding;
    }

    public static void configure(final HdrConfig config, final EdrCapabilities capabilities) {
        sourceEncoding = config.sourceEncoding();
        HdrMode mode = config.mode();
        boolean modeRequestsScene = mode == HdrMode.AUTO
                || mode == HdrMode.SCENE
                || (mode == HdrMode.ENHANCED && config.experimentalFp16());
        requested = modeRequestsScene && capabilities.isHdrDisplay();
    }

    public static void reset() {
        requested = false;
        sourceEncoding = HdrSourceEncoding.SRGB;
    }
}
