package com.metallum.client.hdr;

public final class HdrSemanticState {
    private static volatile boolean requested;

    private HdrSemanticState() {
    }

    public static boolean isRequested() {
        return requested;
    }

    public static void setRequested(final boolean requested) {
        HdrSemanticState.requested = requested;
    }
}
