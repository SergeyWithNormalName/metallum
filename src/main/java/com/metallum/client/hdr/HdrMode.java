package com.metallum.client.hdr;

import java.util.Locale;

public enum HdrMode {
    OFF,
    EDR,
    ENHANCED,
    SCENE,
    AUTO;

    static HdrMode parse(final String value) {
        if (value == null) {
            return AUTO;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "off", "sdr", "false", "0" -> OFF;
            case "edr", "edr_sdr" -> EDR;
            case "enhanced", "hdr", "on", "true", "1" -> ENHANCED;
            case "scene", "hdr_scene", "full" -> SCENE;
            default -> AUTO;
        };
    }

    public HdrOutputMode resolve(final EdrCapabilities capabilities) {
        if (!capabilities.isHdrDisplay()) {
            return HdrOutputMode.SDR;
        }
        return switch (this) {
            case OFF -> HdrOutputMode.SDR;
            case EDR -> HdrOutputMode.EDR;
            case ENHANCED, SCENE, AUTO -> HdrOutputMode.ENHANCED;
        };
    }
}
