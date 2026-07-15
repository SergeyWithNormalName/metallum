package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

import java.nio.ByteBuffer;
import java.util.List;

/** Fully validated resident allocation refresh prepared before touching staging state. */
public record SodiumTerrainInPlaceRefresh(
        ChunkBuildOutput output,
        List<Mesh> meshes
) {
    public SodiumTerrainInPlaceRefresh {
        meshes = List.copyOf(meshes);
    }

    public record Mesh(
            GlBufferArena arena,
            GlBufferSegment allocation,
            ByteBuffer geometry
    ) {
    }
}
