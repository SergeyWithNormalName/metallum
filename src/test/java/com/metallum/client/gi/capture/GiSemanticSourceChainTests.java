package com.metallum.client.gi.capture;

import com.metallum.client.gi.receiver.GiReceiverCompatibility;
import com.metallum.client.gi.semantic.GiSemanticMedium;
import com.metallum.client.gi.semantic.GiSemanticPalette;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import com.metallum.mixin.MetallumMixinConfigPlugin;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Version-lock, structural-off, atlas-color, and accepted-source chain checks for G2. */
public final class GiSemanticSourceChainTests {
    private GiSemanticSourceChainTests() {
    }

    public static void main(final String[] args) throws Exception {
        validateStructuralGate();
        validatePinnedSodiumMethods();
        validateAcceptedSourceChain();
        validateResourceReloadLifecycle();
        validateNoPrelitInputs();
        validateAtlasColorCopy();
        validateEmissiveSeedChromaticity();
        System.out.println("G2 Sodium accepted-output source-chain tests passed");
    }

    private static void validatePinnedSodiumMethods() {
        require(hasDeclaredMethod(
                        net.caffeinemc.mods.sodium.client.world.LevelSlice.class, "prepare", 3),
                "Pinned Sodium LevelSlice.prepare source boundary changed");
        require(hasDeclaredMethod(
                        net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask.class,
                        "execute", 2),
                "Pinned Sodium meshing execute boundary changed");
        require(hasDeclaredMethod(
                        net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer.class,
                        "bufferQuad", 3),
                "Pinned Sodium block buffered-quad boundary changed");
        require(hasDeclaredMethod(
                        net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer.class,
                        "writeQuad", 7),
                "Pinned Sodium fluid writeQuad boundary changed");
        require(hasDeclaredMethod(
                        net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager.class,
                        "uploadResults", 2),
                "Pinned Sodium accepted upload boundary changed");
    }

    private static void validateStructuralGate() throws Exception {
        String plugin = source("src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java");
        String fabricMetadata = source("src/main/resources/fabric.mod.json");
        require(fabricMetadata.contains(
                        "\"sodium\": \"=" + GiReceiverCompatibility.SODIUM_VERSION + "\""
                ),
                "Fabric metadata Sodium dependency drifted from the exact GI carrier gate");
        require(plugin.contains("METALLUM_GI_G2_CAPTURE"), "G2 explicit environment gate is absent");
        require(plugin.contains("MINECRAFT_EXACT_VERSION = \"26.2\"")
                        && plugin.contains("SODIUM_EXACT_VERSION = \"0.9.1+mc26.2\"")
                        && plugin.contains("FABRIC_RENDERER_API_EXACT_VERSION = \"14.0.1+eec4cc519c\"")
                        && plugin.contains("MIXIN_EXTRAS_EXACT_VERSION = \"0.5.4\""),
                "G2 exact third-party version lock changed");
        require(plugin.contains("GI_G2_CAPTURE_MIXINS.contains(mixinClassName)"),
                "G2 mixins are not controlled as one structural set");
        require(plugin.contains("GiTransportDebugSettings.isEnabled()"),
                "Sodium G4 debug does not structurally enable its required G2 mixins");
        Method persistedDebugGate = MetallumMixinConfigPlugin.class.getDeclaredMethod(
                "persistedGiTransportDebugRequested", boolean.class, boolean.class
        );
        persistedDebugGate.setAccessible(true);
        require(!(boolean) persistedDebugGate.invoke(null, true, true)
                        && (boolean) persistedDebugGate.invoke(null, false, true)
                        && !(boolean) persistedDebugGate.invoke(null, true, false)
                        && !(boolean) persistedDebugGate.invoke(null, false, false),
                "Persisted G4 debug is not isolated from benchmark G2 capture");
        require(plugin.contains(
                        "this.productionGiEnabled && persistedGiTransportDebugEnabled"
                ) && plugin.contains("GiTransportDebugSettings.setEnabled(false)"),
                "Production GI no longer clears a stale persisted G4 debug request");
        require(plugin.contains("RendererConfig.loadForStartupGate()")
                        && plugin.contains("rendererConfig.globalIllumination().isDynamic()")
                        && plugin.contains("this.productionGiEnabled"),
                "Production GI does not structurally enable its startup Sodium mixins");
        int giVersions = plugin.indexOf("private static boolean hasExactGiCaptureVersions()");
        int nextMethod = plugin.indexOf("private static boolean hasExactVersion(", giVersions);
        require(giVersions >= 0 && nextMethod > giVersions
                        && !plugin.substring(giVersions, nextMethod)
                        .contains("FABRIC_RENDERER_API_MOD_ID"),
                "G2 is incorrectly locked to an unrelated Fabric Renderer API build");
        String blockHdr = source("src/main/java/com/metallum/mixin/sodium/BlockRendererHdrMixin.java");
        String fluidHdr = source("src/main/java/com/metallum/mixin/sodium/DefaultFluidRendererHdrMixin.java");
        require(!blockHdr.contains("GiSemantic") && !fluidHdr.contains("GiSemantic"),
                "Always-applied HDR hot paths contain G2 callbacks");
    }

    private static void validateAcceptedSourceChain() throws Exception {
        String manager = source("src/main/java/com/metallum/mixin/gi/GiSemanticRenderSectionManagerMixin.java");
        String scope = source("src/main/java/com/metallum/mixin/gi/GiSemanticMeshingScopeMixin.java");
        String output = source("src/main/java/com/metallum/mixin/gi/GiSemanticOutputMixin.java");
        String upload = source("src/main/java/com/metallum/mixin/gi/GiSemanticUploadMixin.java");
        require(manager.contains("createRebuildTask") && manager.contains("metallum$setGiSemanticTask")
                        && manager.contains("metallum$setEmptyGiSemanticTask"),
                "G2 task/authoritative-empty reservation is no longer stamped at task creation");
        require(scope.contains("@WrapMethod") && scope.contains("ChunkBuilderMeshingTask")
                        && scope.contains("scope.finish(context.cache.getWorldSlice())")
                        && scope.contains("metallum$isFastRelightOutput"),
                "G2 worker scope is not around the exact full-mesh execute path");
        int reservation = scope.indexOf("controller.reserve(task)");
        require(reservation >= 0
                        && reservation < scope.indexOf("original.call(context, cancellationToken)", reservation),
                "G2 candidate budget is not reserved before worker capture begins");
        require(output.contains("BuiltSectionInfo.EMPTY") && output.contains("destroy()V")
                        && output.contains("discardGiSemanticCandidate"),
                "G2 empty/candidate destruction ownership changed");
        require(upload.contains("uploadResults") && upload.contains("at = @At(\"RETURN\")")
                        && upload.contains("output.section.isDisposed()")
                        && upload.contains("catch (Throwable ignored)"),
                "G2 accepted upload publication is no longer fail-closed");
    }

    private static void validateResourceReloadLifecycle() throws Exception {
        String reload = source("src/main/java/com/metallum/mixin/gi/GiSemanticLevelExtractorMixin.java");
        String atlas = source("src/main/java/com/metallum/mixin/gi/GiSemanticAtlasMixin.java");
        String manager = source(
                "src/main/java/com/metallum/mixin/gi/GiSemanticRenderSectionManagerMixin.java"
        );
        require(reload.contains("@Mixin(Minecraft.class)")
                        && reload.contains("reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)"
                                + "Ljava/util/concurrent/CompletableFuture;")
                        && reload.contains("PackRepository;reload()V")
                        && reload.contains("shift = At.Shift.BEFORE")
                        && reload.contains("require = 1")
                        && reload.contains("allow = 1")
                        && reload.contains("beginResourceReload("),
                "G2 resource invalidation is not before the actual reload entrypoint");
        require(!reload.contains("LevelExtractor.class")
                        && !reload.contains("onResourceManagerReload"),
                "G2 still invalidates its just-published palette from a late reload callback");
        require(atlas.contains("method = \"upload\"")
                        && atlas.contains("at = @At(\"TAIL\")")
                        && atlas.contains("advanceMaterialAtlasEpoch(GiSemanticPaletteFactory.seeds())"),
                "G2 block-atlas TAIL no longer publishes the complete post-reload palette");
        require(manager.contains("getCameraEntity()")
                        && manager.contains("openWorld(")
                        && manager.contains("camera.getX()")
                        && manager.contains("camera.getY()")
                        && manager.contains("camera.getZ()")
                        && manager.contains("camera.level() == level")
                        && manager.contains("Double.isFinite(camera.getX())")
                        && manager.contains("Double.isFinite(camera.getY())")
                        && manager.contains("Double.isFinite(camera.getZ())"),
                "G2 manager open no longer seeds the pre-frame camera clipmap");
    }

    private static void validateNoPrelitInputs() throws Exception {
        String capture = source("src/main/java/com/metallum/client/gi/capture/GiSemanticCaptureScope.java")
                + source("src/main/java/com/metallum/client/gi/capture/GiSemanticQuadSample.java")
                + source("src/main/java/com/metallum/mixin/gi/GiSemanticBlockRendererMixin.java")
                + source("src/main/java/com/metallum/mixin/gi/GiSemanticFluidRendererMixin.java");
        require(!capture.contains("getBrightness(")
                        && !capture.contains("getRawBrightness(")
                        && !capture.contains("QuadLightData")
                        && !capture.contains("getLight("),
                "G2 source chain reads pre-lit vanilla/Sodium lighting");
        require(!capture.contains("exact ? emission : 0"),
                "Non-emissive-flag light sources lost their state emission");
        String blockObserver = source(
                "src/main/java/com/metallum/mixin/gi/GiSemanticBlockRendererMixin.java"
        );
        int pendingFlush = blockObserver.indexOf("if (this.metallum$giPendingBase != null)");
        int nextOverlayLookup = blockObserver.indexOf(
                "EmissiveTextureRegistry.overlayFor(sprite, state.getLightEmission())",
                pendingFlush
        );
        require(pendingFlush >= 0 && nextOverlayLookup > pendingFlush
                        && blockObserver.substring(pendingFlush, nextOverlayLookup)
                        .contains("metallum$flushPendingBase()"),
                "A non-matching quad can overwrite the prior pending emissive base");
        require(!source("src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionCandidate.java")
                        .contains("TextureAtlasSprite"),
                "G2 candidate retains an atlas sprite");
    }

    private static void validateAtlasColorCopy() {
        try (NativeImage asymmetric = new NativeImage(1, 1, false)) {
            asymmetric.setPixel(0, 0, ARGB.color(255, 240, 16, 32));
            GiSemanticSpriteColorAtlas.LinearRgb copied = GiSemanticSpriteColorAtlas.average(asymmetric);
            require(copied.red() > copied.blue() * 8.0F && copied.blue() > copied.green(),
                    "NativeImage ARGB red/blue decoding changed");
        }
        try (NativeImage gray = new NativeImage(1, 1, false)) {
            gray.setPixel(0, 0, ARGB.color(255, 128, 128, 128));
            GiSemanticSpriteColorAtlas.LinearRgb copied = GiSemanticSpriteColorAtlas.average(gray);
            require(copied.red() > 0.20F && copied.red() < 0.23F,
                    "White vertex tint no longer preserves gray sprite albedo");
        }
        NativeImage closed = new NativeImage(1, 1, false);
        closed.close();
        GiSemanticSpriteColorAtlas.LinearRgb fallback = GiSemanticSpriteColorAtlas.average(closed);
        require(fallback.red() == 0.0F && fallback.green() == 0.0F && fallback.blue() == 0.0F,
                "Malformed/closed atlas image did not fail closed");
    }

    private static void validateEmissiveSeedChromaticity() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockState torchState = Blocks.TORCH.defaultBlockState();
        GiSemanticPalette.Seed torch = GiSemanticPaletteFactory.seed(
                torchState, SurfaceMaterialPolicy.forTerrain(torchState, false),
                GiSemanticMedium.OPAQUE
        );
        require(torch.emissionRed() > torch.emissionGreen() * 3.0F
                        && torch.emissionGreen() > torch.emissionBlue() * 3.0F
                        && torch.emissionIntensity() > 0.0F,
                "seed-only torch emission became neutral instead of warm scene-linear RGB");

        BlockState lavaState = Blocks.LAVA.defaultBlockState();
        GiSemanticPalette.Seed lava = GiSemanticPaletteFactory.seed(
                lavaState, SurfaceMaterialPolicy.forTerrain(lavaState, true),
                GiSemanticMedium.WATER
        );
        require(lava.emissionRed() > lava.emissionGreen() * 8.0F
                        && lava.emissionGreen() > lava.emissionBlue() * 8.0F
                        && lava.emissionIntensity() == 1.0F,
                "seed-only lava emission lost its orange-red source chromaticity");
    }

    private static String source(final String relative) throws Exception {
        return Files.readString(Path.of(System.getProperty("user.dir")).resolve(relative));
    }

    private static boolean hasDeclaredMethod(
            final Class<?> owner,
            final String name,
            final int parameterCount
    ) {
        for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return true;
            }
        }
        return false;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
