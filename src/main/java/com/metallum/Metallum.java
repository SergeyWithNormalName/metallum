package com.metallum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared mod identity and logger; Metallum has no Fabric lifecycle initializer. */
public final class Metallum {
    public static final String MOD_ID = "metallum";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Metallum() {
    }
}
