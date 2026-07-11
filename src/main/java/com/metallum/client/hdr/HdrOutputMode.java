package com.metallum.client.hdr;

public enum HdrOutputMode {
    SDR(0),
    EDR(1),
    ENHANCED(2);

    private final int nativeValue;

    HdrOutputMode(final int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int nativeValue() {
        return this.nativeValue;
    }
}
