package com.metallum.client.gi.capture;

import com.metallum.client.gi.semantic.GiSemanticMaterial;
import com.metallum.client.gi.semantic.GiSemanticMedium;
import com.metallum.client.gi.semantic.GiSemanticPalette;
import com.metallum.client.gi.semantic.GiSemanticProvenance;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic global palette source; its key set is frozen before workers receive task stamps. */
public final class GiSemanticPaletteFactory {
    private GiSemanticPaletteFactory() {
    }

    public static GiSemanticPalette build(
            final long generation,
            final long resourceEpoch,
            final long materialEpoch
    ) {
        return GiSemanticPalette.build(generation, resourceEpoch, materialEpoch, seeds());
    }

    public static List<GiSemanticPalette.Seed> seeds() {
        List<GiSemanticPalette.Seed> seeds = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                addFamilies(seeds, state, SurfaceMaterialPolicy.forTerrain(state, false));
                SurfaceMaterialPolicy.Descriptor translucent = SurfaceMaterialPolicy.forTerrain(state, true);
                if (translucent.kind() != SurfaceMaterialPolicy.forTerrain(state, false).kind()) {
                    addFamilies(seeds, state, translucent);
                }
            }
        }
        return List.copyOf(seeds);
    }

    public static String canonicalKey(
            final BlockState state,
            final SurfaceMaterialPolicy.Descriptor descriptor,
            final GiSemanticMedium medium
    ) {
        return "block:" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + "|family:" + descriptor.kind().name().toLowerCase(Locale.ROOT)
                + "|medium:" + medium.name().toLowerCase(Locale.ROOT)
                + "|emission:" + Math.clamp(state.getLightEmission(), 0, 15);
    }

    private static void addFamilies(
            final List<GiSemanticPalette.Seed> seeds,
            final BlockState state,
            final SurfaceMaterialPolicy.Descriptor descriptor
    ) {
        for (GiSemanticMedium medium : new GiSemanticMedium[]{
                GiSemanticMedium.OPAQUE,
                GiSemanticMedium.CUTOUT,
                GiSemanticMedium.TRANSLUCENT,
                GiSemanticMedium.WATER
        }) {
            String key = canonicalKey(state, descriptor, medium);
            int emission = Math.clamp(state.getLightEmission(), 0, 15);
            seeds.add(new GiSemanticPalette.Seed(
                    key, GiSemanticMaterial.from(descriptor), medium,
                    0.5F, 0.5F, 0.5F,
                    1.0F, 1.0F, 1.0F, emission / 15.0F,
                    GiSemanticProvenance.RESOURCE_DERIVED | GiSemanticProvenance.MATERIAL_DERIVED
            ));
        }
    }
}
