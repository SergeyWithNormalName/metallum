package com.metallum.client.lighting;

import com.metallum.client.hdr.SodiumHdrShaderPatcher;
import com.metallum.client.hdr.SodiumHdrSemantic;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;

/** Deterministic numerical and compact-ABI checks for the L8 surface material policy. */
public final class SurfaceMaterialPolicyTests {
    private SurfaceMaterialPolicyTests() {
    }

    public static void main(final String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        testVanillaDefaults();
        testCompactSemanticCoexistence();
        testFresnelAndGgx();
        testWetnessAndAbsorption();
        System.out.println("PASS L8 GGX, water/glass optics, wetness, and compact material policy tests");
    }

    private static void testVanillaDefaults() {
        require(SurfaceMaterialPolicy.forBlock(Blocks.STONE.defaultBlockState())
                        == SurfaceMaterialPolicy.STONE,
                "stone did not resolve the bounded wet-stone profile");
        require(SurfaceMaterialPolicy.forBlock(Blocks.OAK_PLANKS.defaultBlockState())
                        == SurfaceMaterialPolicy.WOOD,
                "planks did not resolve the bounded wet-wood profile");
        require(SurfaceMaterialPolicy.forBlock(Blocks.SNOW_BLOCK.defaultBlockState())
                        == SurfaceMaterialPolicy.POROUS,
                "snow did not resolve the non-glazing porous profile");
        require(SurfaceMaterialPolicy.forBlock(Blocks.IRON_BLOCK.defaultBlockState())
                        == SurfaceMaterialPolicy.METAL,
                "iron block did not resolve the metal material");
        require(SurfaceMaterialPolicy.forBlock(Blocks.GLASS.defaultBlockState())
                        == SurfaceMaterialPolicy.GLASS,
                "glass block did not resolve the transmissive CPU policy");
        require(SurfaceMaterialPolicy.forTerrain(
                        Blocks.OAK_LEAVES.defaultBlockState(), false)
                        == SurfaceMaterialPolicy.POROUS
                        && SurfaceMaterialPolicy.forTerrain(
                        Blocks.GRASS_BLOCK.defaultBlockState(), false)
                        == SurfaceMaterialPolicy.DIELECTRIC,
                "cutout foliage or grass entered the wrong material profile");
        require(SurfaceMaterialPolicy.forTerrain(
                        Blocks.SLIME_BLOCK.defaultBlockState(), true)
                        == SurfaceMaterialPolicy.GLASS,
                "genuine translucent terrain lost its conservative glass fallback");
        require(SodiumHdrSemantic.SURFACE_CLASS_METAL
                        != SodiumHdrSemantic.SURFACE_CLASS_SMOOTH_DIELECTRIC
                        && SodiumHdrSemantic.SURFACE_CLASS_WATER
                        != SodiumHdrSemantic.SURFACE_CLASS_METAL
                        && SodiumHdrSemantic.SURFACE_CLASS_GLASS
                        != SodiumHdrSemantic.SURFACE_CLASS_WATER
                        && SodiumHdrSemantic.SURFACE_CLASS_STONE
                        != SodiumHdrSemantic.SURFACE_CLASS_WOOD
                        && SodiumHdrSemantic.SURFACE_CLASS_WOOD
                        != SodiumHdrSemantic.SURFACE_CLASS_POROUS,
                "compact L8 surface classes are not distinct");
    }

    private static void testCompactSemanticCoexistence() {
        int metal = SodiumHdrShaderPatcher.packMaterialBits(
                2, SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT);
        int smooth = SodiumHdrShaderPatcher.packMaterialBits(
                4, SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT);
        int water = SodiumHdrShaderPatcher.packMaterialBits(
                3, SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT);
        int glass = SodiumHdrShaderPatcher.packMaterialBits(
                6, SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT);
        int stone = SodiumHdrShaderPatcher.packMaterialBits(
                1, SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT);
        int wood = SodiumHdrShaderPatcher.packMaterialBits(
                5, SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT);
        int porous = SodiumHdrShaderPatcher.packMaterialBits(
                7, SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT);
        require(metal == 130 && smooth == 132 && water == 131 && glass == 134
                        && stone == 129 && wood == 133 && porous == 135,
                "non-emissive L8 classes changed their compact material encoding");
        require(((metal >> 3) & 15) == 0 && ((metal >> 7) & 1) == 1
                        && ((smooth >> 3) & 15) == 0 && ((smooth >> 7) & 1) == 1
                        && ((water >> 3) & 15) == 0 && ((water >> 7) & 1) == 1
                        && ((glass >> 3) & 15) == 0 && ((glass >> 7) & 1) == 1,
                "L8 surface class aliases a non-zero emission strength");
        require(SodiumHdrSemantic.materialBaseForSurfaceClass(
                        1, SodiumHdrSemantic.SURFACE_CLASS_WATER) == 3
                        && SodiumHdrSemantic.materialBaseForSurfaceClass(
                        5, SodiumHdrSemantic.SURFACE_CLASS_METAL) == 2
                        && SodiumHdrSemantic.materialBaseForSurfaceClass(
                        3, SodiumHdrSemantic.SURFACE_CLASS_SMOOTH_DIELECTRIC) == 4
                        && SodiumHdrSemantic.materialBaseForSurfaceClass(
                        1, SodiumHdrSemantic.SURFACE_CLASS_GLASS) == 6
                        && SodiumHdrSemantic.materialBaseForSurfaceClass(
                        0, SodiumHdrSemantic.SURFACE_CLASS_STONE) == 1
                        && SodiumHdrSemantic.materialBaseForSurfaceClass(
                        0, SodiumHdrSemantic.SURFACE_CLASS_WOOD) == 5
                        && SodiumHdrSemantic.materialBaseForSurfaceClass(
                        0, SodiumHdrSemantic.SURFACE_CLASS_POROUS) == 7,
                "remesh-time L8 surface classes did not override arbitrary Sodium bases");

        int exactEmission = SodiumHdrShaderPatcher.packMaterialBits(
                5, SodiumHdrShaderPatcher.encodeVertexSemantic(15, true));
        require(exactEmission == 253 && ((exactEmission >> 3) & 15) == 15,
                "L8 compact material policy changed exact HDR emission");
    }

    private static void testFresnelAndGgx() {
        float normalFresnel = SurfaceMaterialPolicy.schlickFresnel(0.04f, 1.0f);
        float middleFresnel = SurfaceMaterialPolicy.schlickFresnel(0.04f, 0.5f);
        float grazingFresnel = SurfaceMaterialPolicy.schlickFresnel(0.04f, 0.0f);
        require(close(normalFresnel, 0.04f)
                        && middleFresnel > normalFresnel
                        && grazingFresnel > middleFresnel
                        && close(grazingFresnel, 1.0f),
                "Schlick Fresnel is not monotonic from normal to grazing angles");

        float smoothPeak = SurfaceMaterialPolicy.ggxDistribution(1.0f, 0.10f);
        float roughPeak = SurfaceMaterialPolicy.ggxDistribution(1.0f, 0.70f);
        require(Float.isFinite(smoothPeak) && Float.isFinite(roughPeak)
                        && smoothPeak > roughPeak && roughPeak >= 0.0f,
                "GGX peak is non-finite, negative, or ignores roughness");
    }

    private static void testWetnessAndAbsorption() {
        float dryStone = SurfaceMaterialPolicy.wetRoughness(
                SurfaceMaterialPolicy.STONE, 0.0f, 0.5f);
        float wetStone = SurfaceMaterialPolicy.wetRoughness(
                SurfaceMaterialPolicy.STONE, 1.0f, 0.5f);
        float wetWood = SurfaceMaterialPolicy.wetRoughness(
                SurfaceMaterialPolicy.WOOD, 1.0f, 0.5f);
        float wetSoil = SurfaceMaterialPolicy.wetRoughness(
                SurfaceMaterialPolicy.DIELECTRIC, 1.0f, 0.5f);
        float wetPorous = SurfaceMaterialPolicy.wetRoughness(
                SurfaceMaterialPolicy.POROUS, 1.0f, 0.5f);
        require(close(dryStone, 0.70f)
                        && close(wetStone, 0.28f)
                        && close(wetWood, 0.42f)
                        && close(wetSoil, 0.50f)
                        && close(wetPorous, 0.72f)
                        && wetStone < wetWood && wetWood < wetSoil && wetSoil < wetPorous,
                "rain roughness profiles no longer distinguish stone, wood, soil, and porous blocks");
        float darkStone = SurfaceMaterialPolicy.wetRoughness(
                SurfaceMaterialPolicy.STONE, 1.0f, 0.0f);
        float brightStone = SurfaceMaterialPolicy.wetRoughness(
                SurfaceMaterialPolicy.STONE, 1.0f, 1.0f);
        require(brightStone < wetStone && wetStone < darkStone
                        && darkStone - brightStone <= 0.10001f,
                "albedo-derived micro-roughness is missing or unbounded");
        require(close(SurfaceMaterialPolicy.wetSpecularScale(
                        SurfaceMaterialPolicy.STONE, 1.0f), 0.78f)
                        && close(SurfaceMaterialPolicy.wetSpecularScale(
                        SurfaceMaterialPolicy.POROUS, 1.0f), 0.10f),
                "wet specular energy no longer suppresses porous glazing");
        require(close(SurfaceMaterialPolicy.wetAlbedoScale(
                        SurfaceMaterialPolicy.STONE, 0.0f), 1.0f)
                        && close(SurfaceMaterialPolicy.wetAlbedoScale(
                        SurfaceMaterialPolicy.WOOD, 1.0f), 0.82f)
                        && close(SurfaceMaterialPolicy.wetAlbedoScale(
                        SurfaceMaterialPolicy.POROUS, 1.0f), 0.92f),
                "material-aware wet albedo darkening changed");
        require(close(SurfaceMaterialPolicy.wetDielectricF0(0.04f, 0.0f), 0.04f)
                        && close(SurfaceMaterialPolicy.wetDielectricF0(0.04f, 1.0f), 0.025f),
                "wet dielectric F0 no longer follows the conservative water-film target");

        float shallow = SurfaceMaterialPolicy.beerLambert(0.36f, 0.5f);
        float deep = SurfaceMaterialPolicy.beerLambert(0.36f, 5.0f);
        require(shallow <= 1.0f && shallow > deep && deep >= 0.0f,
                "Beer-Lambert absorption is not energy-conserving and depth-monotonic");
    }

    private static boolean close(final float actual, final float expected) {
        return Math.abs(actual - expected) <= 0.00001f;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
