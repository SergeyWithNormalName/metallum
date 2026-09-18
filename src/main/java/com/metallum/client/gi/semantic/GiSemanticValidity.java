package com.metallum.client.gi.semantic;

/** Two-bit G2 validity. Unknown, accepted empty, real content and fallback never alias. */
public enum GiSemanticValidity {
    UNKNOWN(0),
    KNOWN_EMPTY(1),
    KNOWN_CONTENT(2),
    KNOWN_FALLBACK(3);

    private final int abiId;

    GiSemanticValidity(final int abiId) {
        this.abiId = abiId;
    }

    public int abiId() {
        return this.abiId;
    }

    public static GiSemanticValidity fromAbiId(final int value) {
        for (GiSemanticValidity validity : values()) {
            if (validity.abiId == value) {
                return validity;
            }
        }
        throw new IllegalArgumentException("Unknown G2 validity ID: " + value);
    }
}
