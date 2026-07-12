package com.metallum.client.hdr;

import java.util.Locale;

public enum HdrSourceEncoding {
    SRGB(0),
    EXTENDED_SRGB(1),
    LINEAR(2);

    private final int nativeValue;

    HdrSourceEncoding(final int nativeValue) {
        this.nativeValue = nativeValue;
    }

    static HdrSourceEncoding parse(final String value) {
        if (value == null) {
            return SRGB;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "linear", "linear_extended_srgb" -> LINEAR;
            case "extended_srgb", "extended_srgb_encoded" -> EXTENDED_SRGB;
            default -> SRGB;
        };
    }

    public int nativeValue() {
        return this.nativeValue;
    }

    /**
     * An FP16 target does not imply linear values. Minecraft's current scene
     * shaders still produce display-encoded color, so the legacy SRGB setting
     * becomes an extended (unclamped) sRGB contract for an FP16 source.
     */
    public int nativeValue(final boolean fp16Source) {
        return this == SRGB && fp16Source ? EXTENDED_SRGB.nativeValue : this.nativeValue;
    }
}
