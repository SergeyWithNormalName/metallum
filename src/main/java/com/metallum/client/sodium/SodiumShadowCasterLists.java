package com.metallum.client.sodium;

import com.metallum.client.lighting.SunShadowClipVolume;
import com.metallum.client.metal.render.SunShadowRenderer;
import com.metallum.client.renderer.temporal.FrameState;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Exact Sodium 0.9.1 terrain-caster superset for the active L4 light frustum. */
public final class SodiumShadowCasterLists implements ChunkRenderListIterable {
    private static long activeToken;
    private static SodiumShadowCasterLists activeLists;
    private final List<ChunkRenderList> lists;

    private SodiumShadowCasterLists(final List<ChunkRenderList> lists) {
        this.lists = List.copyOf(lists);
    }

    public static ChunkRenderListIterable select(
            final ChunkRenderListIterable ordinaryLists,
            final RenderRegionManager regions
    ) {
        Objects.requireNonNull(ordinaryLists, "ordinaryLists");
        Objects.requireNonNull(regions, "regions");
        long token = SunShadowRenderer.activeCascadeToken();
        if (token == 0L) {
            release();
            return ordinaryLists;
        }
        if (activeLists != null && activeToken == token) {
            return activeLists;
        }

        release();
        Matrix4fc clipFromWorldRelative = Objects.requireNonNull(
                SunShadowRenderer.activeTerrainCasterProjection(),
                "active shadow caster projection"
        );
        FrameState.CameraPosition camera = Objects.requireNonNull(
                SunShadowRenderer.activeCameraPosition(),
                "active shadow camera position"
        );
        activeToken = token;
        SunShadowRenderer.registerTerrainCleanup(SodiumShadowCasterLists::release);
        activeLists = build(regions, clipFromWorldRelative, camera);
        return activeLists;
    }

    public static void release() {
        activeLists = null;
        activeToken = 0L;
    }

    private static SodiumShadowCasterLists build(
            final RenderRegionManager manager,
            final Matrix4fc clipFromWorldRelative,
            final FrameState.CameraPosition camera
    ) {
        ArrayList<RenderRegion> loaded = new ArrayList<>(manager.getLoadedRegions());
        loaded.sort(Comparator.comparingInt(RenderRegion::getX)
                .thenComparingInt(RenderRegion::getY)
                .thenComparingInt(RenderRegion::getZ));
        ArrayList<ChunkRenderList> lists = new ArrayList<>();
        for (RenderRegion region : loaded) {
            if (region.getResources() == null || !intersectsRegion(
                    clipFromWorldRelative,
                    camera,
                    region
            )) {
                continue;
            }
            ChunkRenderList list = new ChunkRenderList(region);
            long sections0 = 0L;
            long sections1 = 0L;
            long sections2 = 0L;
            long sections3 = 0L;
            for (int index = 0; index < RenderRegion.REGION_SIZE; index++) {
                if ((region.getSectionFlags(index)
                        & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) == 0) {
                    continue;
                }
                int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(index);
                int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(index);
                int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(index);
                double minimumX = (double) sectionX * 16.0;
                double minimumY = (double) sectionY * 16.0;
                double minimumZ = (double) sectionZ * 16.0;
                if (SunShadowClipVolume.intersectsAabb(
                        clipFromWorldRelative,
                        camera,
                        minimumX,
                        minimumY,
                        minimumZ,
                        minimumX + 16.0,
                        minimumY + 16.0,
                        minimumZ + 16.0
                )) {
                    list.add(index);
                    long bit = 1L << (index & 63);
                    switch (index >>> 6) {
                        case 0 -> sections0 |= bit;
                        case 1 -> sections1 |= bit;
                        case 2 -> sections2 |= bit;
                        case 3 -> sections3 |= bit;
                        default -> throw new IllegalStateException("Invalid region section " + index);
                    }
                }
            }
            if (list.getSectionsWithGeometryCount() > 0) {
                ((SodiumShadowBatchAccess) region).metallum$prepareShadowBatch(
                        activeToken,
                        sections0,
                        sections1,
                        sections2,
                        sections3
                );
                lists.add(list);
            }
        }
        return new SodiumShadowCasterLists(lists);
    }

    private static boolean intersectsRegion(
            final Matrix4fc clipFromWorldRelative,
            final FrameState.CameraPosition camera,
            final RenderRegion region
    ) {
        double minimumX = (double) region.getChunkX() * 16.0;
        double minimumY = (double) region.getChunkY() * 16.0;
        double minimumZ = (double) region.getChunkZ() * 16.0;
        return SunShadowClipVolume.intersectsAabb(
                clipFromWorldRelative,
                camera,
                minimumX,
                minimumY,
                minimumZ,
                minimumX + RenderRegion.REGION_WIDTH * 16.0,
                minimumY + RenderRegion.REGION_HEIGHT * 16.0,
                minimumZ + RenderRegion.REGION_LENGTH * 16.0
        );
    }

    @Override
    public Iterator<ChunkRenderList> iterator(final boolean reverse) {
        if (!reverse) {
            return this.lists.iterator();
        }
        return new Iterator<>() {
            private int index = SodiumShadowCasterLists.this.lists.size();

            @Override
            public boolean hasNext() {
                return this.index > 0;
            }

            @Override
            public ChunkRenderList next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return SodiumShadowCasterLists.this.lists.get(--this.index);
            }
        };
    }
}
