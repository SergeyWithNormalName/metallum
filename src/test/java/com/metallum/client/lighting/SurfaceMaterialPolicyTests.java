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
                        == SurfaceMaterialPolicy.DIELECTRIC,
                "ordinary terrain did not retain the dielectric fallback");
        require(SurfaceMaterialPolicy.forBlock(Blocks.IRON_BLOCK.defaultBlockState())
                        == SurfaceMaterialPolicy.METAL,
                "iron block did not resolve the metal material");
        require(SurfaceMaterialPolicy.forBlock(Blocks.GLASS.defaultBlockState())
                        == SurfaceMaterialPolicy.GLASS,
                "glass block did not resolve the transmissive CPU policy");
        require(SurfaceMaterialPolicy.forTerrain(
                        Blocks.OAK_LEAVES.defaultBlockState(), false)
                        == SurfaceMaterialPolicy.DIELECTRIC
                        && SurfaceMaterialPolicy.forTerrain(
                        Blocks.GRASS_BLOCK.defaultBlockState(), false)
                        == SurfaceMaterialPolicy.DIELECTRIC,
                "cutout foliage or grass entered the glass fallback");
        require(SurfaceMaterialPolicy.forTerrain(
                        Blocks.SLIME_BLOCK.defaultBlockState(), true)
                        == SurfaceMaterialPolicy.GLASS,
                "genuine translucent terrain lost its conservative glass fallback");
        require(SodiumHdrSemantic.SURFACE_CLASS_METAL
                        != SodiumHdrSemantic.SURFACE_CLASS_SMOOTH_DIELECTRIC
                        && SodiumHdrSemantic.SURFACE_CLASS_WATER
                        != SodiumHdrSemantic.SURFACE_CLASS_METAL
                        && SodiumHdrSemantic.SURFACE_CLASS_GLASS
                        != SodiumHdrSemantic.SURFACE_CLASS_WATER,
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
        require(metal == 130 && smooth == 132 && water == 131 && glass == 134,
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
                        1, SodiumHdrSemantic.SURFACE_CLASS_GLASS) == 6,
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
        float dry = SurfaceMaterialPolicy.wetRoughness(0.68f, 0.0f);
        float wet = SurfaceMaterialPolicy.wetRoughness(0.68f, 1.0f);
        require(wet < dry && wet >= 0.055f,
                "rain did not lower roughness within the bounded material floor");
        require(close(SurfaceMaterialPolicy.wetAlbedoScale(0.0f), 1.0f)
                        && close(SurfaceMaterialPolicy.wetAlbedoScale(1.0f), 0.84f),
                "wet albedo darkening changed");

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
