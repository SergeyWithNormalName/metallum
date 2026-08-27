package com.metallum.client.gi.semantic;

import com.metallum.client.lighting.SurfaceMaterialPolicy;

/** Stable material family stored in versioned G2 palette entries. */
public enum GiSemanticMaterial {
    DIELECTRIC(0),
    STONE(1),
    WOOD(2),
    POROUS(3),
    SMOOTH_DIELECTRIC(4),
    METAL(5),
    GLASS(6),
    WATER(7);

    private final int abiId;

    GiSemanticMaterial(final int abiId) {
        this.abiId = abiId;
    }

    public int abiId() {
        return this.abiId;
    }

    /** G2 exposes the source L8 decision instead of maintaining a second receiver list. */
    public SurfaceMaterialPolicy.VoxelReflectionMode voxelReflectionMode() {
        return SurfaceMaterialPolicy.voxelReflectionMode(switch (this) {
            case DIELECTRIC -> SurfaceMaterialPolicy.Kind.DIELECTRIC;
            case STONE -> SurfaceMaterialPolicy.Kind.STONE;
            case WOOD -> SurfaceMaterialPolicy.Kind.WOOD;
            case POROUS -> SurfaceMaterialPolicy.Kind.POROUS;
            case SMOOTH_DIELECTRIC -> SurfaceMaterialPolicy.Kind.SMOOTH_DIELECTRIC;
            case METAL -> SurfaceMaterialPolicy.Kind.METAL;
            case GLASS -> SurfaceMaterialPolicy.Kind.GLASS;
            case WATER -> SurfaceMaterialPolicy.Kind.WATER;
        });
    }

    public static GiSemanticMaterial from(final SurfaceMaterialPolicy.Descriptor descriptor) {
        return switch (descriptor.kind()) {
            case DIELECTRIC -> DIELECTRIC;
            case STONE -> STONE;
            case WOOD -> WOOD;
            case POROUS -> POROUS;
            case SMOOTH_DIELECTRIC -> SMOOTH_DIELECTRIC;
            case METAL -> METAL;
            case GLASS -> GLASS;
            case WATER -> WATER;
        };
    }
}
