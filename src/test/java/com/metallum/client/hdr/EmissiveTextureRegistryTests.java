package com.metallum.client.hdr;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Properties;

/** Dependency-free contracts for OptiFine-style terrain emissive discovery and built-in masks. */
public final class EmissiveTextureRegistryTests {
    private EmissiveTextureRegistryTests() {
    }

    public static void main(final String[] args) {
        testSuffixSelection();
        testResourcePriority();
        testBuiltInEmissionStateGate();
        testUniversalVanillaFallback();
        testSpriteLayouts();
        testUvProjection();
        testBuiltinMasks();
        testSoftGeneratedMaskPixels();
        testPartialOverlayQuadSemantics();
        System.out.println("PASS emissive terrain texture registry contracts");
    }

    private static void testSuffixSelection() {
        require(EmissiveTextureRegistry.DEFAULT_SUFFIX.equals(
                EmissiveTextureRegistry.suffixFromProperties(null)
        ), "missing emissive.properties did not use _e");

        Properties custom = new Properties();
        custom.setProperty("suffix.emissive", "_glow");
        require("_glow".equals(EmissiveTextureRegistry.suffixFromProperties(custom)),
                "custom OptiFine emissive suffix was ignored");

        Properties unsafe = new Properties();
        unsafe.setProperty("suffix.emissive", "../escape");
        require(EmissiveTextureRegistry.DEFAULT_SUFFIX.equals(
                EmissiveTextureRegistry.suffixFromProperties(unsafe)
        ), "unsafe suffix was accepted");
    }

    private static void testResourcePriority() {
        require(EmissiveTextureRegistry.hasAtLeastBasePriority("pack", "pack", 2, 0),
                "same-pack sidecar was rejected");
        require(EmissiveTextureRegistry.hasAtLeastBasePriority("base", "overlay", 2, 3),
                "higher-priority sidecar was rejected");
        require(!EmissiveTextureRegistry.hasAtLeastBasePriority("base", "overlay", 3, 2),
                "lower-priority sidecar was applied to a replacement base texture");
    }

    private static void testUvProjection() {
        require(close(EmissiveTextureRegistry.remapCoordinate(0.25f, 0.25f, 0.75f, 0.1f, 0.3f), 0.1f),
                "base sprite lower UV did not map to overlay lower UV");
        require(close(EmissiveTextureRegistry.remapCoordinate(0.50f, 0.25f, 0.75f, 0.1f, 0.3f), 0.2f),
                "base sprite center UV did not preserve its local coordinate");
        require(close(EmissiveTextureRegistry.remapCoordinate(0.75f, 0.25f, 0.75f, 0.1f, 0.3f), 0.3f),
                "base sprite upper UV did not map to overlay upper UV");
        require(close(EmissiveTextureRegistry.remapCoordinate(Float.NaN, 0.0f, 1.0f, 0.2f, 0.4f), 0.2f),
                "invalid UV did not fail closed to overlay start");
    }

    private static void testSpriteLayouts() {
        List<Integer> still = List.of(0);
        List<Integer> animated = List.of(0, 1, 2);
        require(EmissiveTextureRegistry.compatibleLayout(16, 16, false, still, 16, 16, false, still),
                "matching static sprite layout was rejected");
        require(EmissiveTextureRegistry.compatibleLayout(16, 48, true, animated, 16, 48, true, animated),
                "matching animated sprite layout was rejected");
        require(!EmissiveTextureRegistry.compatibleLayout(16, 16, false, still, 32, 16, false, still),
                "dimension-mismatched sidecar was accepted");
        require(!EmissiveTextureRegistry.compatibleLayout(16, 48, true, animated, 16, 48, true, List.of(0, 2)),
                "animation-frame-mismatched sidecar was accepted");
        require(!EmissiveTextureRegistry.compatibleLayout(16, 16, false, still, 16, 16, true, animated),
                "static base accepted an animated sidecar");
    }

    private static void testBuiltInEmissionStateGate() {
        require(!EmissiveTextureRegistry.allowsOverlay(true, 0),
                "an unlit state was allowed to use a generated vanilla emissive mask");
        require(EmissiveTextureRegistry.allowsOverlay(true, 1),
                "a lit state was rejected from its generated vanilla emissive mask");
        require(EmissiveTextureRegistry.allowsOverlay(false, 0),
                "a standard resource-pack sidecar was incorrectly tied to Minecraft block light");
    }

    private static void testUniversalVanillaFallback() {
        Identifier torch = Identifier.withDefaultNamespace("block/torch");
        Identifier furnaceFrontOn = Identifier.withDefaultNamespace("block/furnace_front_on");
        Identifier modLamp = Identifier.fromNamespaceAndPath("examplemod", "block/lamp");
        require(EmissiveTextureRegistry.usesGeneratedVanillaMask(torch, "vanilla")
                        && EmissiveTextureRegistry.usesGeneratedVanillaMask(furnaceFrontOn, "vanilla"),
                "an untouched vanilla light-bearing sprite has no generated fallback mask");
        require(!EmissiveTextureRegistry.usesGeneratedVanillaMask(torch, "resourcepack")
                        && !EmissiveTextureRegistry.usesGeneratedVanillaMask(modLamp, "vanilla"),
                "a resource-pack or mod texture was given a guessed vanilla mask");
        require(EmissiveTextureRegistry.autoMaskMatches(0xffffe0a0),
                "bright automatic-emission pixels are not selected");
        require(EmissiveTextureRegistry.autoMaskMatches(0xffbd5412),
                "saturated warm automatic-emission pixels are not selected");
        require(!EmissiveTextureRegistry.autoMaskMatches(0xff4a463d),
                "dark non-emissive automatic-emission pixels are selected");
    }

    private static void testBuiltinMasks() {
        Identifier redstoneOre = Identifier.withDefaultNamespace("block/redstone_ore");
        Identifier glowBerries = Identifier.withDefaultNamespace("block/cave_vines_lit");
        Identifier amethyst = Identifier.withDefaultNamespace("block/amethyst_cluster");
        Identifier sculk = Identifier.withDefaultNamespace("block/sculk_sensor_tendril_active");
        Identifier magma = Identifier.withDefaultNamespace("block/magma");
        Identifier cryingObsidian = Identifier.withDefaultNamespace("block/crying_obsidian");
        require(EmissiveTextureRegistry.hasBuiltinMask(redstoneOre)
                        && EmissiveTextureRegistry.hasBuiltinMask(glowBerries)
                        && EmissiveTextureRegistry.hasBuiltinMask(amethyst)
                        && EmissiveTextureRegistry.hasBuiltinMask(sculk)
                        && EmissiveTextureRegistry.hasBuiltinMask(magma)
                        && EmissiveTextureRegistry.hasBuiltinMask(cryingObsidian),
                "required vanilla partial-emission masks are missing");
        require(EmissiveTextureRegistry.builtinMaskMatches(redstoneOre, 0xffc05050),
                "redstone vein pixels are not selected");
        require(!EmissiveTextureRegistry.builtinMaskMatches(redstoneOre, 0xff707070),
                "ordinary redstone-ore stone pixels are selected");
        require(EmissiveTextureRegistry.builtinMaskMatches(glowBerries, 0xffc08020),
                "glow berry pixels are not selected");
        require(!EmissiveTextureRegistry.builtinMaskMatches(glowBerries, 0xff609030),
                "cave-vine leaf pixels are selected");
        require(!EmissiveTextureRegistry.builtinMaskMatches(amethyst, 0x00d090f0),
                "fully transparent pixels are selected by a built-in mask");
        require(EmissiveTextureRegistry.builtinMaskMatches(magma, 0xffe08020),
                "magma crack pixels are not selected");
        require(!EmissiveTextureRegistry.builtinMaskMatches(magma, 0xff5d1209),
                "magma stone pixels are selected");
        require(EmissiveTextureRegistry.builtinMaskMatches(cryingObsidian, 0xff406fe0),
                "crying-obsidian tear pixels are not selected");
    }

    private static void testPartialOverlayQuadSemantics() {
        require(SodiumHdrSemantic.terrainQuadSurfaceEmission(null, 9, false, true, false) == 0,
                "base quad of a partial overlay pair must not receive HDR emission");
        require(!SodiumHdrSemantic.isExactTerrainQuad(false, true, false),
                "base quad of a partial overlay pair must not be exact-emissive");

        require(SodiumHdrSemantic.terrainQuadSurfaceEmission(null, 9, true, true, true) == 15,
                "partial overlay quad must receive exact HDR emission");
        require(SodiumHdrSemantic.isExactTerrainQuad(true, true, true),
                "partial overlay quad must be exact-emissive while the pair is active");

        require(SodiumHdrSemantic.terrainQuadSurfaceEmission(null, 9, false, false, false) == 9,
                "ordinary terrain quad must retain its block-light emission");
        require(SodiumHdrSemantic.terrainQuadSurfaceEmission(null, 0, true, false, false) == 15,
                "existing exact-emissive mod quad must retain exact HDR emission");
    }

    private static void testSoftGeneratedMaskPixels() {
        int darkRed = 0xff401410;
        int direct = EmissiveTextureRegistry.generatedMaskPixel(darkRed, true);
        int dim = EmissiveTextureRegistry.generatedMaskPixel(darkRed, false);
        require(direct >>> 24 == 0xff && dim >>> 24 == 0xff,
                "soft generated emissive pixels lost their source alpha");
        require(peak(direct) >= 128 && peak(dim) >= 18 && peak(dim) < peak(direct),
                "dark opaque texels were not kept below selected emissive detail");
        require(red(direct) > green(direct) && red(direct) > blue(direct),
                "soft generated emissive pixel did not retain its original hue");
        int black = EmissiveTextureRegistry.generatedMaskPixel(0xff000000, false);
        require(peak(black) == 18 && red(black) == green(black) && green(black) == blue(black),
                "opaque black texel did not receive a dim neutral emissive value");
        int saturatedRed = EmissiveTextureRegistry.generatedMaskPixel(0xffff0000, true);
        require(peak(saturatedRed) == 176 && red(saturatedRed) > green(saturatedRed),
                "saturated highlight did not stay below a white emissive highlight");
        require(EmissiveTextureRegistry.generatedMaskPixel(0xffffffff, true) == 0xffffffff,
                "white emissive highlight was unexpectedly dimmed");
        require(EmissiveTextureRegistry.generatedMaskPixel(0x00102030, true) == 0,
                "transparent source pixel leaked into the generated overlay");
    }

    private static int peak(final int argb) {
        return Math.max(argb >>> 16 & 0xff, Math.max(argb >>> 8 & 0xff, argb & 0xff));
    }

    private static int red(final int argb) {
        return argb >>> 16 & 0xff;
    }

    private static int green(final int argb) {
        return argb >>> 8 & 0xff;
    }

    private static int blue(final int argb) {
        return argb & 0xff;
    }

    private static boolean close(final float actual, final float expected) {
        return Math.abs(actual - expected) < 0.0001f;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
