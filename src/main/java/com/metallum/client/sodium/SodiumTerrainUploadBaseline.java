package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Immutable resident upload layout committed only after a successful Sodium upload. */
public final class SodiumTerrainUploadBaseline implements AutoCloseable {
    private final int sectionFlags;
    private final long[] visibilityData;
    private final SodiumTerrainMeshLayout[] meshes;
    private final SodiumTerrainStaticShadow[] staticShadows;
    private final int generation;
    @Nullable
    private final BuiltSectionInfo residentInfo;

    public SodiumTerrainUploadBaseline(
            final int sectionFlags,
            final long[] visibilityData,
            final SodiumTerrainMeshLayout[] meshes
    ) {
        this(sectionFlags, visibilityData, meshes, 0, null);
    }

    public SodiumTerrainUploadBaseline(
            final int sectionFlags,
            final long[] visibilityData,
            final SodiumTerrainMeshLayout[] meshes,
            final int generation
    ) {
        this(sectionFlags, visibilityData, meshes, generation, null);
    }

    /** Production constructor binding metadata identity and generation to one output. */
    public SodiumTerrainUploadBaseline(
            final BuiltSectionInfo residentInfo,
            final SodiumTerrainMeshLayout[] meshes,
            final int generation
    ) {
        this(
                residentInfo.flags,
                residentInfo.visibilityData,
                meshes,
                generation,
                residentInfo
        );
    }

    private SodiumTerrainUploadBaseline(
            final int sectionFlags,
            final long[] visibilityData,
            final SodiumTerrainMeshLayout[] meshes,
            final int generation,
            @Nullable final BuiltSectionInfo residentInfo
    ) {
        this(
                sectionFlags,
                visibilityData,
                meshes,
                new SodiumTerrainStaticShadow[meshes.length],
                generation,
                residentInfo
        );
    }

    private SodiumTerrainUploadBaseline(
            final int sectionFlags,
            final long[] visibilityData,
            final SodiumTerrainMeshLayout[] meshes,
            final SodiumTerrainStaticShadow[] staticShadows,
            final int generation,
            @Nullable final BuiltSectionInfo residentInfo
    ) {
        if (meshes.length != staticShadows.length) {
            throw new IllegalArgumentException("terrain layout/static shadow count mismatch");
        }
        this.sectionFlags = sectionFlags;
        this.visibilityData = visibilityData.clone();
        this.meshes = meshes.clone();
        this.staticShadows = staticShadows.clone();
        this.generation = generation;
        this.residentInfo = residentInfo;
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

    public int generation() {
        return this.generation;
    }

    /** Exact resident metadata guard paired with the explicit upload generation. */
    public boolean matchesResidentMetadata(
            final int generation,
            final BuiltSectionInfo info
    ) {
        return info != null
                && this.generation != SodiumRelightResidentState.LEGACY_GENERATION
                && this.generation == generation
                && this.residentInfo == info
                && this.sectionFlags == info.flags
                && Arrays.equals(this.visibilityData, info.visibilityData);
    }

    /**
     * Captures exact non-light bytes after a full resident refresh succeeded.
     * A cache eviction only makes {@link #matchesStaticGeometry(ByteBuffer[])}
     * return false and therefore restores the full upload path.
     */
    public SodiumTerrainUploadBaseline withStaticGeometry(final ByteBuffer[] geometryByMesh) {
        if (geometryByMesh.length != this.meshes.length) {
            throw new IllegalArgumentException("terrain geometry/static shadow count mismatch");
        }
        SodiumTerrainStaticShadow[] captured = new SodiumTerrainStaticShadow[this.meshes.length];
        try {
            for (int index = 0; index < this.meshes.length; index++) {
                SodiumTerrainMeshLayout layout = this.meshes[index];
                ByteBuffer geometry = geometryByMesh[index];
                if (layout == null) {
                    if (geometry != null) {
                        throw new IllegalArgumentException("terrain geometry exists without a mesh layout");
                    }
                    continue;
                }
                if (geometry == null || geometry.remaining() != layout.geometryBytes()) {
                    throw new IllegalArgumentException("terrain static shadow geometry length changed");
                }
                captured[index] = SodiumTerrainStaticShadow.capture(geometry);
            }
            return new SodiumTerrainUploadBaseline(
                    this.sectionFlags,
                    this.visibilityData,
                    this.meshes,
                    captured,
                    this.generation,
                    this.residentInfo
            );
        } catch (RuntimeException | Error throwable) {
            for (SodiumTerrainStaticShadow shadow : captured) {
                if (shadow != null) {
                    shadow.close();
                }
            }
            throw throwable;
        }
    }

    public boolean matchesStaticGeometry(final ByteBuffer[] geometryByMesh) {
        if (geometryByMesh.length != this.meshes.length) {
            return false;
        }
        for (int index = 0; index < this.meshes.length; index++) {
            SodiumTerrainMeshLayout layout = this.meshes[index];
            ByteBuffer geometry = geometryByMesh[index];
            SodiumTerrainStaticShadow shadow = this.staticShadows[index];
            if (layout == null) {
                if (geometry != null || shadow != null) {
                    return false;
                }
                continue;
            }
            if (geometry == null
                    || geometry.remaining() != layout.geometryBytes()
                    || shadow == null
                    || !shadow.matches(geometry)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasResidentStaticGeometry() {
        for (int index = 0; index < this.meshes.length; index++) {
            if (this.meshes[index] != null
                    && (this.staticShadows[index] == null || !this.staticShadows[index].isResident())) {
                return false;
            }
        }
        return true;
    }

    /** Reconstructs one full pass geometry, or fails closed when its shadow is unavailable. */
    public boolean reconstructGeometry(
            final int meshIndex,
            final ByteBuffer lightBytes,
            final ByteBuffer destination
    ) {
        if (meshIndex < 0 || meshIndex >= this.meshes.length) {
            return false;
        }
        SodiumTerrainMeshLayout layout = this.meshes[meshIndex];
        SodiumTerrainStaticShadow shadow = this.staticShadows[meshIndex];
        if (layout == null
                || shadow == null
                || lightBytes == null
                || destination == null
                || lightBytes.remaining() != Math.multiplyExact(
                        layout.vertexCount(),
                        SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
                )
                || destination.remaining() < layout.geometryBytes()) {
            return false;
        }
        return shadow.reconstruct(lightBytes, destination);
    }

    @Override
    public void close() {
        for (SodiumTerrainStaticShadow shadow : this.staticShadows) {
            if (shadow != null) {
                shadow.close();
            }
        }
    }
}
