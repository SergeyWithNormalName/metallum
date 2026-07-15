package com.metallum.client.sodium;

/** Fail-closed cause attached to one exact Sodium meshing task. */
public enum SodiumRelightRebuildCause {
    NONE,
    LIGHT_ONLY,
    GEOMETRY_OR_UNKNOWN;

    public SodiumRelightRebuildCause merge(final SodiumRelightRebuildCause other) {
        if (this == GEOMETRY_OR_UNKNOWN || other == GEOMETRY_OR_UNKNOWN) {
            return GEOMETRY_OR_UNKNOWN;
        }
        if (this == LIGHT_ONLY || other == LIGHT_ONLY) {
            return LIGHT_ONLY;
        }
        return NONE;
    }
}
