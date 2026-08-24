package com.metallum.client.renderer;

import java.util.Locale;

/**
 * Runtime GI policy reserved by Stage G0.
 *
 * <p>Only the zero-work mode is admitted until G1 has passed its own resource,
 * lifetime and shader gates. Adding another value is therefore an explicit
 * stage transition, not a hidden experimental toggle.</p>
 */
public enum GlobalIlluminationMode {
    OFF;

    public String persistentName() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
