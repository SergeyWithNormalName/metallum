package com.metallum.client.hdr;

public final class HdrSemanticState {
    private static volatile boolean requested;

    private HdrSemanticState() {
    }

    public static boolean isRequested() {
        return requested;
    }

    public static void configure(final HdrMode mode, final EdrCapabilities capabilities) {
        requested = (mode == HdrMode.AUTO || mode == HdrMode.ENHANCED)
                && capabilities.isHdrDisplay();
    }

    public static void reset() {
        requested = false;
    }
}
