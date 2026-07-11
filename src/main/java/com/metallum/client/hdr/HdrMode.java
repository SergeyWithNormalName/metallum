package com.metallum.client.hdr;

import java.util.Locale;

public enum HdrMode {
    OFF,
    EDR,
    ENHANCED,
    AUTO;

    static HdrMode parse(final String value) {
        if (value == null) {
            return AUTO;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "off", "sdr", "false", "0" -> OFF;
            case "edr", "edr_sdr" -> EDR;
            case "enhanced", "hdr", "on", "true", "1" -> ENHANCED;
            default -> AUTO;
        };
    }

    public HdrOutputMode resolve(final EdrCapabilities capabilities) {
        if (!capabilities.isHdrDisplay()) {
            return HdrOutputMode.SDR;
        }
        return switch (this) {
            case OFF -> HdrOutputMode.SDR;
            case EDR, AUTO -> HdrOutputMode.EDR;
            case ENHANCED -> HdrOutputMode.ENHANCED;
        };
    }
}
