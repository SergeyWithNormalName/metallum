package com.metallum.client.hdr;

import java.util.Locale;

public enum HdrSourceEncoding {
    SRGB(0),
    LINEAR(1);

    private final int nativeValue;

    HdrSourceEncoding(final int nativeValue) {
        this.nativeValue = nativeValue;
    }

    static HdrSourceEncoding parse(final String value) {
        if (value == null) {
            return SRGB;
        }
        return "linear".equals(value.strip().toLowerCase(Locale.ROOT)) ? LINEAR : SRGB;
    }

    public int nativeValue() {
        return this.nativeValue;
    }
}
