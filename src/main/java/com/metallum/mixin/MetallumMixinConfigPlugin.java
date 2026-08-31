package com.metallum.mixin;

import com.metallum.Metallum;
import com.metallum.client.gi.debug.GiTransportDebugSettings;
import com.metallum.client.gi.receiver.GiReceiverCompatibility;
import com.metallum.client.lighting.reflection.VertexReflectionExperiment;
import com.metallum.client.renderer.RendererConfig;
import com.metallum.client.sodium.SodiumShadowCompatibility;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MetallumMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String PREFERRED_GRAPHICS_API_MIXIN = "com.metallum.mixin.render.PreferredGraphicsApiMixin";
    private static final String BENCHMARK_MIXIN_PREFIX = "com.metallum.mixin.benchmark.";
    private static final String BENCHMARK_SODIUM_MIXIN_PREFIX = "com.metallum.mixin.benchmark.sodium.";
    private static final String SODIUM_RELIGHT_ORACLE_ENV = "METALLUM_SODIUM_RELIGHT_ORACLE";
    private static final String SODIUM_RELIGHT_FAST_PATH_ENV = "METALLUM_SODIUM_RELIGHT_FAST_PATH";
    private static final String SODIUM_LIGHT_PATCH_ENV = "METALLUM_SODIUM_LIGHT_PATCH";
    private static final String GI_G2_CAPTURE_ENV = "METALLUM_GI_G2_CAPTURE";
    private static final String GI_G3_INJECT_ENV = "METALLUM_GI_G3_INJECT";
    private static final String GI_G4_TRANSPORT_ENV = "METALLUM_GI_G4_TRANSPORT";
    private static final String GI_G5_RECEIVER_ENV = "METALLUM_GI_G5_RECEIVER";
    private static final String MINECRAFT_MOD_ID = "minecraft";
    private static final String MINECRAFT_EXACT_VERSION = "26.2";
    private static final String SODIUM_MOD_ID = "sodium";
    private static final String SODIUM_EXACT_VERSION = "0.9.1+mc26.2";
    private static final String FABRIC_RENDERER_API_MOD_ID = "fabric-renderer-api-v1";
    private static final String FABRIC_RENDERER_API_EXACT_VERSION = "14.0.1+eec4cc519c";
    private static final String MIXIN_EXTRAS_MOD_ID = "mixinextras";
    private static final String MIXIN_EXTRAS_EXACT_VERSION = "0.5.4";
    private static final Set<String> SODIUM_RELIGHT_ORACLE_MIXINS = Set.of(
            "com.metallum.mixin.sodium.BlockRendererRelightOracleMixin",
            "com.metallum.mixin.sodium.ChunkBuildOutputRelightLifecycleMixin",
            "com.metallum.mixin.sodium.ChunkBuilderMeshingTaskRelightAccessMixin",
            "com.metallum.mixin.sodium.ChunkBuilderMeshingTaskRelightOracleMixin",
            "com.metallum.mixin.sodium.ClientChunkCacheRelightCauseMixin",
            "com.metallum.mixin.sodium.ClientPacketListenerRelightCauseMixin",
            "com.metallum.mixin.sodium.RenderRegionManagerRelightLifecycleMixin",
            "com.metallum.mixin.sodium.RenderSectionManagerRelightCauseMixin",
            "com.metallum.mixin.sodium.RenderSectionRelightLifecycleMixin",
            "com.metallum.mixin.sodium.RenderSectionRelightTrackerMixin",
            "com.metallum.mixin.sodium.SodiumRelightBlockContextAccess",
            "com.metallum.mixin.sodium.VanillaBlockModelPartEncoderRelightOracleMixin"
    );
    private static final Set<String> SODIUM_RELIGHT_FAST_MIXINS = Set.of(
            "com.metallum.mixin.sodium.ChunkBuildOutputRelightFastMixin",
            "com.metallum.mixin.sodium.RenderSectionManagerRelightOutputGuardMixin"
    );
    private static final Set<String> SODIUM_LIGHT_SIDECAR_MIXINS = Set.of(
            "com.metallum.mixin.sodium.GlBufferArenaLightSidecarMixin",
            "com.metallum.mixin.sodium.GlBufferSegmentTerrainAccessMixin",
            "com.metallum.mixin.sodium.PendingBufferCopyCommandAccessor",
            "com.metallum.mixin.sodium.RenderRegionDeviceResourcesLightSidecarMixin",
            "com.metallum.mixin.sodium.RenderRegionManagerTerrainRefreshMixin",
            "com.metallum.mixin.sodium.RenderSectionTerrainBaselineMixin",
            "com.metallum.mixin.sodium.SectionRenderDataStorageTerrainAccessMixin"
    );
    private static final Set<String> SODIUM_SHADOW_MIXINS = Set.of(
            "com.metallum.mixin.render.QuadParticleFeatureRendererShadowMixin",
            "com.metallum.mixin.sodium.DefaultChunkRendererShadowMixin",
            "com.metallum.mixin.sodium.RenderRegionShadowBatchMixin",
            "com.metallum.mixin.sodium.RenderSectionManagerShadowAccess",
            "com.metallum.mixin.sodium.SodiumWorldRendererShadowMixin",
            "com.metallum.mixin.sodium.TerrainRenderPassShadowMixin",
            "com.metallum.mixin.sodium.UniformBufferManagerShadowMixin"
    );
    private static final Set<String> GI_G2_CAPTURE_MIXINS = Set.of(
            "com.metallum.mixin.gi.GiSemanticAtlasMixin",
            "com.metallum.mixin.gi.GiSemanticBlockRendererMixin",
            "com.metallum.mixin.gi.GiSemanticClientLevelMixin",
            "com.metallum.mixin.gi.GiSemanticFluidRendererMixin",
            "com.metallum.mixin.gi.GiSemanticLevelExtractorMixin",
            "com.metallum.mixin.gi.GiSemanticMeshingScopeMixin",
            "com.metallum.mixin.gi.GiSemanticOutputMixin",
            "com.metallum.mixin.gi.GiSemanticRenderSectionManagerMixin",
            "com.metallum.mixin.gi.GiSemanticRenderSectionMixin",
            "com.metallum.mixin.gi.GiSemanticSpriteContentsAccessor",
            "com.metallum.mixin.gi.GiSemanticTaskMixin",
            "com.metallum.mixin.gi.GiSemanticUploadMixin"
    );
    private static final Set<String> COMPACT_POSITION_CARRIER_MIXINS = Set.of(
            "com.metallum.mixin.sodium.BSPWorkspaceG5CarrierMixin",
            "com.metallum.mixin.sodium.BuiltSectionInfoBuilderG5CarrierMixin",
            "com.metallum.mixin.sodium.BuiltSectionInfoG5CarrierMixin",
            "com.metallum.mixin.sodium.ChunkBuildBuffersG5CarrierMixin",
            "com.metallum.mixin.sodium.InnerPartitionBSPNodeSemanticMixin",
            "com.metallum.mixin.sodium.RenderRegionManagerG5CarrierMixin",
            "com.metallum.mixin.sodium.UpdatedQuadsListG5CarrierMixin"
    );
    private static final String PREFERRED_GRAPHICS_BACKEND_OPTION = "preferredGraphicsBackend";
    private static final String DEFAULT_GRAPHICS_BACKEND = "\"default\"";

    private boolean isMacOs;
    private boolean isDefaultGraphicsApi;
    private boolean benchmarkEnabled;
    private boolean sodiumLightSidecarEnabled;
    private boolean sodiumRelightOracleEnabled;
    private boolean sodiumRelightFastPathEnabled;
    private boolean sodiumShadowCompatible;
    private boolean giG2CaptureEnabled;
    private boolean productionGiEnabled;
    private boolean compactPositionCarrierCompatible;

    @Override
    public void onLoad(String mixinPackage) {
        String osName = System.getProperty("os.name", "");
        this.isMacOs = osName.toLowerCase(Locale.ROOT).contains("mac");
        this.isDefaultGraphicsApi = isDefaultGraphicsApiSelected();
        this.benchmarkEnabled = "1".equals(System.getenv("METALLUM_BENCHMARK"));
        this.sodiumLightSidecarEnabled = isEnabled(System.getenv("METALLUM_SODIUM_LIGHT_SIDECAR"));
        this.sodiumShadowCompatible = SodiumShadowCompatibility.supportsInstalledRenderer();
        RendererConfig rendererConfig = RendererConfig.loadForStartupGate();
        boolean productionGiRequested = rendererConfig.globalIllumination().isDynamic();
        this.productionGiEnabled = productionGiRequested && rendererConfig.improvedLighting();
        if (productionGiRequested && !this.productionGiEnabled) {
            Metallum.LOGGER.warn(
                    "Dynamic global illumination requires Advanced Lighting; keeping GI structurally off"
            );
        }
        boolean persistedGiTransportDebugEnabled = GiTransportDebugSettings.isEnabled();
        if (this.productionGiEnabled && persistedGiTransportDebugEnabled) {
            // A stale debug opt-in must not make the production receiver request invalid.
            GiTransportDebugSettings.setEnabled(false);
            persistedGiTransportDebugEnabled = false;
            Metallum.LOGGER.info(
                    "Disabled the standalone G4 debug route because production GI is enabled"
            );
        }
        boolean giTransportDebugEnabled = persistedGiTransportDebugRequested(
                this.benchmarkEnabled,
                persistedGiTransportDebugEnabled
        );
        boolean exactRelightVersions = hasExactRelightOracleVersions();
        boolean exactGiCaptureVersions = hasExactGiCaptureVersions();
        this.compactPositionCarrierCompatible = exactGiCaptureVersions
                && (this.productionGiEnabled
                || isEnabled(System.getenv(GI_G5_RECEIVER_ENV))
                || VertexReflectionExperiment.isLayoutEnabled());
        boolean relightOracleRequested = "1".equals(System.getenv(SODIUM_RELIGHT_ORACLE_ENV));
        this.sodiumRelightFastPathEnabled = !relightOracleRequested
                && "1".equals(System.getenv(SODIUM_RELIGHT_FAST_PATH_ENV))
                && this.sodiumLightSidecarEnabled
                && isEnabled(System.getenv(SODIUM_LIGHT_PATCH_ENV))
                && exactRelightVersions;
        this.sodiumRelightOracleEnabled = (relightOracleRequested
                || this.sodiumRelightFastPathEnabled)
                && exactRelightVersions;
        boolean giG2CaptureRequested = isEnabled(System.getenv(GI_G2_CAPTURE_ENV))
                || isEnabled(System.getenv(GI_G3_INJECT_ENV))
                || isEnabled(System.getenv(GI_G4_TRANSPORT_ENV))
                || isEnabled(System.getenv(GI_G5_RECEIVER_ENV))
                || this.productionGiEnabled
                || giTransportDebugEnabled;
        this.giG2CaptureEnabled = giG2CaptureRequested && exactGiCaptureVersions;
        Metallum.LOGGER.info(
                "[GI_G2] mixin gate requested={} exact_versions={} enabled={}",
                giG2CaptureRequested, exactGiCaptureVersions, this.giG2CaptureEnabled
        );
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.isMacOs) {
            return false;
        }
        if (GI_G2_CAPTURE_MIXINS.contains(mixinClassName)) {
            return this.giG2CaptureEnabled && this.isDefaultGraphicsApi;
        }
        if (SODIUM_RELIGHT_ORACLE_MIXINS.contains(mixinClassName)) {
            return this.sodiumRelightOracleEnabled && this.isDefaultGraphicsApi;
        }
        if (SODIUM_RELIGHT_FAST_MIXINS.contains(mixinClassName)) {
            return this.sodiumRelightFastPathEnabled && this.isDefaultGraphicsApi;
        }
        if (mixinClassName.startsWith(BENCHMARK_SODIUM_MIXIN_PREFIX)) {
            return this.benchmarkEnabled
                    && this.isDefaultGraphicsApi
                    && FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (mixinClassName.startsWith(BENCHMARK_MIXIN_PREFIX)) {
            return this.benchmarkEnabled && this.isDefaultGraphicsApi;
        }
        if (SODIUM_LIGHT_SIDECAR_MIXINS.contains(mixinClassName)) {
            return this.sodiumLightSidecarEnabled
                    && this.isDefaultGraphicsApi
                    && FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (SODIUM_SHADOW_MIXINS.contains(mixinClassName)) {
            return this.sodiumShadowCompatible && FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (COMPACT_POSITION_CARRIER_MIXINS.contains(mixinClassName)) {
            return this.compactPositionCarrierCompatible && this.isDefaultGraphicsApi;
        }
        if (mixinClassName.contains(".mixin.sodium.")) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        return PREFERRED_GRAPHICS_API_MIXIN.equals(mixinClassName) || this.isDefaultGraphicsApi;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean isDefaultGraphicsApiSelected() {
        Path optionsFile = FabricLoader.getInstance().getGameDir().resolve("options.txt");
        try {
            for (String line : Files.readAllLines(optionsFile)) {
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                if (PREFERRED_GRAPHICS_BACKEND_OPTION.equals(line.substring(0, separator))) {
                    String value = line.substring(separator + 1).toLowerCase(Locale.ROOT);
                    return DEFAULT_GRAPHICS_BACKEND.equals(value);
                }
            }
        } catch (IOException ignored) {
        }

        return true;
    }

    private static boolean isEnabled(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    /** Persisted interactive G4 debug must not activate G2 capture in benchmark processes. */
    private static boolean persistedGiTransportDebugRequested(
            final boolean benchmarkEnabled,
            final boolean persistedEnabled
    ) {
        return !benchmarkEnabled && persistedEnabled;
    }

    /**
     * The diagnostic oracle targets exact third-party bytecode and therefore
     * deliberately refuses compatible-looking or newer versions.
     */
    private static boolean hasExactRelightOracleVersions() {
        FabricLoader loader = FabricLoader.getInstance();
        return hasExactVersion(loader, MINECRAFT_MOD_ID, MINECRAFT_EXACT_VERSION)
                && hasExactVersion(loader, SODIUM_MOD_ID, SODIUM_EXACT_VERSION)
                && hasExactVersion(
                        loader,
                        FABRIC_RENDERER_API_MOD_ID,
                        FABRIC_RENDERER_API_EXACT_VERSION
                )
                && hasExactVersion(loader, MIXIN_EXTRAS_MOD_ID, MIXIN_EXTRAS_EXACT_VERSION);
    }

    /** G2 targets Minecraft, Sodium and MixinExtras; Fabric Renderer API is not in its chain. */
    private static boolean hasExactGiCaptureVersions() {
        return GiReceiverCompatibility.supportsInstalledCompactPositionCarrier();
    }

    private static boolean hasExactVersion(
            final FabricLoader loader,
            final String modId,
            final String expectedVersion
    ) {
        try {
            return loader.getModContainer(modId)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .filter(expectedVersion::equals)
                    .isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
