package com.metallum.client.display;

public record FullscreenSnapshot(
        int stateRaw,
        boolean isFullscreen,
        boolean isTransitioning,
        long generation
) {
    public static final int STATE_WINDOWED = 0;
    public static final int STATE_ENTERING = 1;
    public static final int STATE_FULLSCREEN = 2;
    public static final int STATE_EXITING = 3;

    public static FullscreenSnapshot decode(long packed) {
        int state = (int) (packed & 0x0FL);
        boolean isFs = (packed & (1L << 4)) != 0;
        boolean isTrans = (packed & (1L << 5)) != 0;
        long gen = packed >>> 8;
        return new FullscreenSnapshot(state, isFs, isTrans, gen);
    }

    public boolean isFullscreenOrEntering() {
        return isFullscreen || stateRaw == STATE_ENTERING;
    }
}
