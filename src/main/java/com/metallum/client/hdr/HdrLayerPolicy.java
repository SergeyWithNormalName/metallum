package com.metallum.client.hdr;

/**
 * Declares the EDR range requested by the CAMetalLayer drawable.
 *
 * <p>This is deliberately based on content intent and display potential, not
 * the display's live headroom. The latter is fed to the present shader for
 * per-frame tone mapping. Mirroring live headroom here would make a value of
 * {@code 1.0} withdraw the EDR request that is needed to raise that value.</p>
 */
public final class HdrLayerPolicy {
    private HdrLayerPolicy() {
    }

    public static float requestedContentsHeadroom(
            final HdrOutputMode outputMode,
            final boolean diagnosticPattern,
            final EdrCapabilities capabilities
    ) {
        if (outputMode == HdrOutputMode.SDR) {
            return 1.0f;
        }
        if (diagnosticPattern) {
            return HdrConfig.OUTPUT_HEADROOM;
        }
        return Math.min(capabilities.potentialHeadroom(), HdrConfig.OUTPUT_HEADROOM);
    }
}
