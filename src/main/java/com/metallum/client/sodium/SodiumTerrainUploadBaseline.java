package com.metallum.client.sodium;

import java.util.Arrays;

/** Immutable resident upload layout committed only after a successful Sodium upload. */
public final class SodiumTerrainUploadBaseline {
    private final int sectionFlags;
    private final long[] visibilityData;
    private final SodiumTerrainMeshLayout[] meshes;

    public SodiumTerrainUploadBaseline(
            final int sectionFlags,
            final long[] visibilityData,
            final SodiumTerrainMeshLayout[] meshes
    ) {
        this.sectionFlags = sectionFlags;
        this.visibilityData = visibilityData.clone();
        this.meshes = meshes.clone();
    }

    public boolean matchesUploadLayout(final SodiumTerrainUploadBaseline other) {
        if (other == null
                || this.sectionFlags != other.sectionFlags
                || !Arrays.equals(this.visibilityData, other.visibilityData)
                || this.meshes.length != other.meshes.length) {
            return false;
        }
        for (int index = 0; index < this.meshes.length; index++) {
            SodiumTerrainMeshLayout left = this.meshes[index];
            SodiumTerrainMeshLayout right = other.meshes[index];
            if (left == null ? right != null : !left.matches(right)) {
                return false;
            }
        }
        return true;
    }

    public SodiumTerrainMeshLayout mesh(final int index) {
        return this.meshes[index];
    }

    public int meshCount() {
        return this.meshes.length;
    }
}
