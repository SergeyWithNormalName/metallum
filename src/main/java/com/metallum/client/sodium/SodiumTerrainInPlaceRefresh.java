package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

import java.nio.ByteBuffer;
import java.util.List;

/** Fully validated resident allocation refresh prepared before touching staging state. */
public record SodiumTerrainInPlaceRefresh(
        ChunkBuildOutput output,
        List<Mesh> meshes,
        SodiumTerrainUploadBaseline residentBaseline,
        ByteBuffer[] geometryByPass,
        boolean lightOnly
) {
    public SodiumTerrainInPlaceRefresh {
        meshes = List.copyOf(meshes);
        geometryByPass = duplicateGeometry(geometryByPass);
    }

    @Override
    public ByteBuffer[] geometryByPass() {
        return duplicateGeometry(this.geometryByPass);
    }

    public SodiumTerrainUploadBaseline captureStaticGeometry(
            final SodiumTerrainUploadBaseline nextBaseline
    ) {
        return nextBaseline.withStaticGeometry(this.geometryByPass);
    }

    private static ByteBuffer[] duplicateGeometry(final ByteBuffer[] geometry) {
        ByteBuffer[] copy = geometry.clone();
        for (int index = 0; index < copy.length; index++) {
            if (copy[index] != null) {
                copy[index] = copy[index].duplicate();
            }
        }
        return copy;
    }

    public record Mesh(
            GlBufferArena arena,
            GlBufferSegment allocation,
            ByteBuffer geometry
    ) {
    }
}
