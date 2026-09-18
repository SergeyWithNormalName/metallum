package com.metallum.client.renderer;

import java.util.Locale;

/** Persistent production GI policy. */
public enum GlobalIlluminationMode {
    OFF,
    DYNAMIC;

    public boolean isDynamic() {
        return this == DYNAMIC;
    }

    public String persistentName() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
