package com.metallum.client.gi.semantic;

/** Exact stamp for one Sodium full-mesh attempt, including the authoritative empty fast path. */
public record GiSemanticSectionTask(
        GiSemanticWorldToken world,
        long clipmapGeneration,
        long paletteGeneration,
        long sectionKey,
        long revision,
        long ownerToken
) {
    public GiSemanticSectionTask {
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (clipmapGeneration <= 0L || paletteGeneration <= 0L || revision <= 0L || ownerToken <= 0L) {
            throw new IllegalArgumentException("G2 task generations, revision and owner must be positive");
        }
    }
}
