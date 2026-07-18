package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.hdr.HdrConfig;
import com.metallum.client.hdr.HdrMode;
import com.metallum.client.hdr.HdrOutputMode;
import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.HdrSemanticState;
import com.metallum.client.hdr.HdrShaderFlavor;
import com.metallum.client.hdr.HdrSourceEncoding;
import com.metallum.client.hdr.LightmapHdrShaderPatcher;
import com.metallum.client.hdr.MetallumMaterialShaderPatcher;
import com.metallum.client.hdr.MetallumMaterialState;
import com.metallum.client.hdr.SceneLinearPreflightGate;
import com.metallum.client.hdr.SceneLinearShaderPatcher;
import com.metallum.client.hdr.SodiumHdrShaderPatcher;
import com.metallum.client.hdr.VanillaHdrShaderPatcher;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.DirectLightFrustum;
import com.metallum.client.lighting.EntityShadowProxyRegistry;
import com.metallum.client.lighting.EntityShadowProxySnapshot;
import com.metallum.client.lighting.LightFrameSnapshot;
import com.metallum.client.lighting.shader.AdvancedDirectLightingShaderPatcher;
import com.metallum.client.lighting.shader.AdvancedLightingBindingAbi;
import com.metallum.client.lighting.shader.SunShadowShaderPatcher;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.framegraph.NativeHdrFrameGraph;
import com.metallum.client.metal.render.framegraph.TemporalDiagnosticFrameGraph;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.RendererConfig;
import com.metallum.client.renderer.RendererFeatureMask;
import com.metallum.client.renderer.RendererGenerationConfig;
import com.metallum.client.renderer.RendererGenerationManifest;
import com.metallum.client.renderer.RendererGenerationPlanner;
import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameCapture;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.FrameStatePacketRing;
import com.metallum.client.renderer.temporal.FrameStateTracker;
import com.metallum.client.renderer.temporal.TemporalResetEvents;
import com.metallum.client.renderer.temporal.TemporalDiagnostics;
import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.voxel.VoxelClipmapController;
import com.metallum.client.voxel.VoxelClipmapLayout;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelUploadBatch;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public final class MetalDevice implements GpuDeviceBackend {
    private record RendererGenerationKey(
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            DisplayOutputMode outputMode,
            boolean spatialActive,
            long materialCoverageEpoch,
            long advancedAdmissionEpoch
    ) {
    }

    enum HdrSceneColorState {
        NONE,
        PENDING_REDIRECT,
        SNAPSHOT,
        DIRECT_SAFE
    }

    record SemanticAttachment(MemorySegment texture, boolean clear) {
    }

    record SodiumLightSidecarBindings(
            MetalGpuBuffer dummyData,
            MetalGpuBuffer control
    ) {
        void close() {
            this.control.close();
            this.dummyData.close();
        }
    }

    record HdrSceneInputs(
            MemorySegment scene,
            MemorySegment depth,
            MemorySegment semantic,
            MemorySegment ui,
            boolean spatialHdrPrecomposed
    ) {
        private static final HdrSceneInputs NONE = new HdrSceneInputs(
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                false
        );
    }

    private record RendererAdmissionLogState(
            Set<RendererGenerationConfig.RejectionReason> rejectionReasons,
            RenderContractMode renderContractMode,
            LightingModel lightingModel,
            DisplayOutputMode outputMode,
            boolean spatialActive
    ) {
        RendererAdmissionLogState {
            rejectionReasons = Set.copyOf(rejectionReasons);
        }
    }

    private static final Pattern BLOCK_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENTS = Pattern.compile("(?m)//[^\\n]*");
    static final long VOXEL_DEBUG_CHECKSUM_CADENCE_FRAMES = 120L;
    private static volatile MetalDevice INSTANCE;
    private final MemorySegment metalDeviceHandle;
    private final MemorySegment metalLayer;
    private final MemorySegment cocoaView;
    private final MemorySegment edrMonitor;
    private volatile HdrConfig hdrConfig;
    private final GpuDebugOptions debugOptions;
    private final MetalCapabilities rendererCapabilities;
    private final RendererConfig rendererConfig;
    private final RenderContractMode requestedRenderContract;
    private final boolean spatialScalingSupported;
    private final boolean temporalDiagnosticsConfigured;
    private boolean temporalDiagnosticsActive;
    private final MetalCommandEncoder commandEncoder;
    private int trackedTextureAllocationDepth;
    private final DeviceInfo deviceInfo;
    public final MTLCommandQueue commandQueue;
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new IdentityHashMap<>();
    private final Map<ShaderCompilationKey, IntermediaryShaderModule> shaderCache = new HashMap<>();
    private final Map<MslFunctionKey, MemorySegment> functionCache = new HashMap<>();
    @Nullable
    private SodiumLightSidecarBindings sodiumLightSidecarBindings;
    private ShaderSource activeShaderSource;
    @Nullable
    private MetalGpuTexture hdrSceneSnapshot;
    @Nullable
    private MetalGpuTexture hdrSceneDepthSnapshot;
    @Nullable
    private MetalGpuTexture hdrDirectSceneSource;
    private HdrSceneColorState hdrSceneColorState = HdrSceneColorState.NONE;
    private boolean hdrDirectSceneRequiresSpatialScaling;
    @Nullable
    private MetalGpuTexture hdrSemanticMask;
    private MemorySegment hdrSceneColorHandle = MemorySegment.NULL;
    private MemorySegment hdrSceneDepthHandle = MemorySegment.NULL;
    private int hdrSceneWidth;
    private int hdrSceneHeight;
    private boolean hdrSemanticSceneAvailable;
    private long hdrSemanticMaskClearedSubmitIndex = Long.MIN_VALUE;
    private long hdrSemanticMaskTouchedSubmitIndex = Long.MIN_VALUE;
    private boolean hdrEnhancedActive;
    private HdrOutputMode hdrOutputMode = HdrOutputMode.SDR;
    private float hdrCurrentHeadroom = 1.0f;
    private boolean hdrEnhancementUnavailable;
    private boolean hdrEnhancementActivationLogged;
    private boolean hdrSceneAvailable;
    private boolean hdrWorldSceneAvailable;
    private long hdrSceneSubmitIndex = Long.MIN_VALUE;
    private MemorySegment hdrUiHandle = MemorySegment.NULL;
    private long hdrUiSubmitIndex = Long.MIN_VALUE;
    private boolean hdrUiSuppressSceneEnhancement;
    private boolean materialWorldPassActive;
    private boolean spatialSceneAvailable;
    private long spatialSceneSubmitIndex = Long.MIN_VALUE;
    private long spatialHdrPrecomposedSubmitIndex = Long.MIN_VALUE;
    private boolean spatialHdrPrecomposeLogged;
    private boolean nativeHdrPrecomposeLogged;
    private boolean spatialPerceptualDirectLogged;
    @Nullable
    private RendererGenerationKey publishedRendererGeneration;
    private long rendererGenerationId;
    private long renderContractGenerationId;
    private long lightingGenerationId;
    private long outputGenerationId;
    @Nullable
    private RendererAdmissionLogState loggedRendererAdmission;
    private boolean frameInterpolationAdmissionLogged;
    private final FrameStatePacketRing frameStatePackets = new FrameStatePacketRing();
    private final FrameStateTracker frameStateTracker = new FrameStateTracker();
    @Nullable
    private RendererGenerationConfig activeRendererGeneration;
    private FrameState.ResourceBytes activeRendererResourceBytes = FrameState.ResourceBytes.NONE;
    private FrameState.@Nullable Extent activeRenderExtent;
    private FrameState.@Nullable Extent activeDisplayExtent;
    @Nullable
    private TemporalDiagnosticResources temporalDiagnosticResources;
    private boolean temporalDiagnosticFailureLogged;
    @Nullable
    private AdvancedLightingGpuResources advancedLightingResources;
    @Nullable
    private SunShadowGpuResources sunShadowResources;
    @Nullable
    private VoxelOccupancyGpuResources voxelOccupancyResources;
    @Nullable
    private LocalVoxelShadowGpuResources localVoxelShadowResources;
    private boolean advancedLightingFrameReady;
    private long advancedLightingFrameSubmitIndex = Long.MIN_VALUE;
    private boolean advancedLightingTransientFallbackLogged;
    private boolean sunShadowFailureLogged;
    private boolean voxelTransientBusyLogged;
    private long observedVoxelNativeRejections;
    private boolean voxelFailureLogged;
    private long voxelRetrySuppressedRendererGeneration = Long.MIN_VALUE;
    private long voxelRetrySuppressedLightingGeneration = Long.MIN_VALUE;
    private long lastVoxelDebugChecksumSubmitIndex = Long.MIN_VALUE;
    private boolean voxelDebugChecksumRuntimeDisabled;
    private boolean voxelDebugChecksumFailureLogged;

    MetalDevice(
            final ShaderSource defaultShaderSource,
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final MemorySegment metalLayer,
            final String deviceName,
            final MemorySegment cocoaWindow,
            final MemorySegment cocoaView
    ) {
        NativeHdrFrameGraph.initialize();
        INSTANCE = this;
        this.activeShaderSource = defaultShaderSource;
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalLayer = metalLayer;
        this.cocoaView = cocoaView;
        HdrSemanticState.reset();
        HdrSceneState.reset();
        MetallumMaterialState.reset();
        AdvancedLightingRuntime.reset();
        VoxelClipmapController.global().clear();
        this.rendererConfig = RendererConfig.load();
        VoxelClipmapController.global().configurePreset(
                voxelPreset(this.rendererConfig.lightingPreset())
        );
        this.hdrConfig = HdrConfig.load();
        HdrMode configuredHdrMode = this.hdrConfig.mode();
        this.edrMonitor = MetalNativeBridge.metallum_create_edr_monitor(cocoaWindow);
        if (MetalNativeBridge.isNullHandle(this.edrMonitor)) {
            Metallum.LOGGER.warn("Failed to create EDR display monitor; HDR will use the safe SDR fallback");
        }
        EdrCapabilities initialEdrCapabilities = EdrCapabilities.SDR;
        if (!MetalNativeBridge.isNullHandle(this.edrMonitor)) {
            try {
                initialEdrCapabilities = MetalNativeBridge.metallum_EDRMonitor_query(this.edrMonitor);
            } catch (RuntimeException exception) {
                Metallum.LOGGER.warn("Failed to query initial EDR capabilities; semantic HDR shaders will remain disabled", exception);
            }
        }
        MetalCapabilities discoveredCapabilities;
        try {
            long nativeCapabilities = MetalNativeBridge.metallum_renderer_capabilities_v1(
                    metalDeviceHandle,
                    this.edrMonitor
            );
            discoveredCapabilities = MetalCapabilities.fromNativeSnapshot(
                    nativeCapabilities,
                    initialEdrCapabilities
            );
        } catch (RuntimeException exception) {
            Metallum.LOGGER.warn(
                    "Renderer capability discovery failed; future Metal 4/MetalFX paths remain unavailable",
                    exception
            );
            discoveredCapabilities = MetalCapabilities.productionMetal3(
                    initialEdrCapabilities.isHdrDisplay()
            );
        }
        this.rendererCapabilities = discoveredCapabilities;
        this.requestedRenderContract = requestedRenderContract();
        AdvancedLightingRuntime.configureRequested(
                this.requestedRenderContract == RenderContractMode.METALLUM
                        && this.rendererConfig.improvedLighting()
        );
        this.temporalDiagnosticsConfigured = TemporalDiagnostics.configured();
        this.temporalDiagnosticsActive = this.temporalDiagnosticsConfigured
                && this.rendererCapabilities.temporalProfile().diagnosticsSupported();
        if (this.temporalDiagnosticsActive) {
            TemporalDiagnosticFrameGraph.initialize();
        } else if (this.temporalDiagnosticsConfigured) {
            Metallum.LOGGER.warn(
                    "Temporal diagnostics requested but the MetalFX reactive/format/usage profile is unavailable; diagnostics are disabled"
            );
        }
        MetalNativeBridge.metallum_set_debug_labels_enabled(this.useLabels());
        this.spatialScalingSupported = this.rendererCapabilities.supports(
                MetalCapabilities.Feature.METALFX_SPATIAL
        );
        this.commandQueue = MTLCommandQueue.create(metalDeviceHandle, metalLayer);
        int nativePipelineStatus = MetalNativeBridge.metallum_init_pipelines(metalDeviceHandle);
        if (nativePipelineStatus < 0) {
            throw new IllegalStateException("Failed to initialize mandatory Metallum native pipelines");
        }
        if (nativePipelineStatus == 2) {
            Metallum.LOGGER.warn("Using the built-in Metal shader source fallback; startup may be slower");
        }
        if (AdvancedLightingRuntime.isRequested()) {
            boolean advancedNativePreflightSucceeded = false;
            try {
                AdvancedLightingGpuResources.validateNativeAbi();
                AdvancedLightingRuntime.reportNativeAdmission(true, "");
                advancedNativePreflightSucceeded = true;
            } catch (RuntimeException exception) {
                AdvancedLightingRuntime.reportNativeAdmission(
                        false,
                        "native Advanced lighting ABI/preflight failed"
                );
                Metallum.LOGGER.warn(
                        "Advanced Lighting native preflight failed; preserving METALLUM + VANILLA lighting",
                        exception
                );
            }
            if (advancedNativePreflightSucceeded) {
                try {
                    VoxelOccupancyGpuResources.validateNativeAbi();
                } catch (RuntimeException exception) {
                    Metallum.LOGGER.warn(
                            "L5 voxel ABI preflight failed; retaining the established L3/L4 admission",
                            exception
                    );
                }
            }
        }
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(deviceName);
        HdrSemanticState.configure(configuredHdrMode, initialEdrCapabilities);
        HdrSceneState.configure(this.hdrConfig, initialEdrCapabilities);
        MetallumMaterialState.configure(
                this.requestedRenderContract == RenderContractMode.METALLUM,
                configuredHdrMode.resolve(initialEdrCapabilities) != HdrOutputMode.SDR
        );
        Metallum.LOGGER.info(
                "Semantic HDR shaders: {} (configured mode {}, potential EDR headroom {})",
                HdrSemanticState.isRequested() ? "enabled" : "disabled",
                configuredHdrMode,
                initialEdrCapabilities.potentialHeadroom()
        );
        Metallum.LOGGER.info(
                "Configured HDR mode: {}, FP16 scene path: {}",
                configuredHdrMode,
                HdrSceneState.isRequested() ? "enabled" : "disabled"
        );
        Metallum.LOGGER.info("MetalFX spatial scaling support: {}", this.spatialScalingSupported ? "available" : "unavailable");
        Metallum.LOGGER.info(
                "Temporal camera-motion diagnostics: {}",
                this.temporalDiagnosticsActive ? "enabled (camera/static-depth only)" : "disabled"
        );
        Metallum.LOGGER.info(
                "Renderer generation request: contract={}, lighting={}, preset={}, frameInterpolation={} (production executor Metal 3)",
                this.requestedRenderContract,
                this.requestedRenderContract == RenderContractMode.METALLUM
                        && this.rendererConfig.improvedLighting() ? "ADVANCED" : "VANILLA",
                this.rendererConfig.lightingPreset(),
                this.rendererConfig.frameInterpolation()
        );
    }

    MetalCapabilities rendererCapabilities() {
        return this.rendererCapabilities;
    }

    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle) {
        return new MetalSurface(this, this.metalLayer);
    }

    @Override
    public @NonNull MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public @NonNull GpuSampler createSampler(
            final @NonNull AddressMode addressModeU,
            final @NonNull AddressMode addressModeV,
            final @NonNull FilterMode minFilter,
            final @NonNull FilterMode magFilter,
            final int maxAnisotropy,
            final @NonNull OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final Supplier<String> label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        String debugLabel = this.resolveDebugLabel(label);
        return new MetalGpuTexture(
                this,
                usage,
                debugLabel == null ? "" : debugLabel,
                format,
                width,
                height,
                depthOrLayers,
                mipLevels,
                shouldTrackTextureHazards(this.trackedTextureAllocationDepth)
        );
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(
                this,
                usage,
                label == null ? "" : label,
                format,
                width,
                height,
                depthOrLayers,
                mipLevels,
                shouldTrackTextureHazards(this.trackedTextureAllocationDepth)
        );
    }

    TextureTarget createTrackedTextureTarget(
            final String label,
            final int width,
            final int height,
            final boolean useDepth,
            final GpuFormat format
    ) {
        this.trackedTextureAllocationDepth++;
        try {
            return new TextureTarget(label, width, height, useDepth, format);
        } finally {
            this.trackedTextureAllocationDepth--;
        }
    }

    static boolean shouldTrackTextureHazards(final int trackedAllocationDepth) {
        return trackedAllocationDepth > 0;
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        return this.allocateBuffer(usage, usage, size, false);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        MetalGpuBuffer buffer = this.allocateBuffer(
                usage | GpuBuffer.USAGE_COPY_DST,
                usage,
                data.remaining(),
                true
        );
        this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        return buffer;
    }

    private MetalGpuBuffer allocateBuffer(
            @GpuBuffer.Usage final int effectiveUsage,
            @GpuBuffer.Usage final int originalUsage,
            final long size,
            final boolean initialData
    ) {
        return new MetalGpuBuffer(
                this,
                effectiveUsage,
                size,
                MetalGpuBuffer.shouldUsePrivateGeometryHeap(originalUsage, initialData)
        );
    }

    @Override
    public @NonNull List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    @Override
    public @NonNull CompiledRenderPipeline precompilePipeline(final @NonNull RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
        ShaderSource effectiveSource = shaderSource == null ? this.activeShaderSource : shaderSource;
        if (shaderSource != null) {
            this.activeShaderSource = shaderSource;
        }
        return this.compiledPipelines.computeIfAbsent(pipeline, p -> MetalCrossShaderCompiler.compile(this, p, effectiveSource));
    }

    @Override
    public void clearPipelineCache() {
        this.waitForSubmittedGpuWork();
        this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
        this.compiledPipelines.clear();
        this.shaderCache.values().forEach(IntermediaryShaderModule::close);
        this.shaderCache.clear();
        for (MemorySegment function : this.functionCache.values()) {
            if (!MetalNativeBridge.isNullHandle(function)) {
                MetalNativeBridge.metallum_release_object(function);
            }
        }
        this.functionCache.clear();
    }

    @Override
    public void close() {
        HdrSemanticState.reset();
        HdrSceneState.reset();
        MetallumMaterialState.reset();
        AdvancedLightingRuntime.reset();
        VoxelClipmapController.global().clear();
        if (this.hdrSceneSnapshot != null) {
            this.hdrSceneSnapshot.close();
            this.hdrSceneSnapshot = null;
        }
        if (this.hdrSceneDepthSnapshot != null) {
            this.hdrSceneDepthSnapshot.close();
            this.hdrSceneDepthSnapshot = null;
        }
        this.resetHdrSceneColor();
        this.hdrSceneDepthHandle = MemorySegment.NULL;
        if (this.hdrSemanticMask != null) {
            this.hdrSemanticMask.close();
            this.hdrSemanticMask = null;
        }
        if (this.temporalDiagnosticResources != null) {
            this.temporalDiagnosticResources.close();
            this.temporalDiagnosticResources = null;
        }
        SodiumLightSidecar.releaseAll();
        this.closeSodiumLightSidecarBindings();
        this.waitForSubmittedGpuWork();
        if (this.advancedLightingResources != null) {
            this.advancedLightingResources.close();
            this.advancedLightingResources = null;
        }
        if (this.sunShadowResources != null) {
            this.sunShadowResources.close();
            this.sunShadowResources = null;
        }
        if (this.voxelOccupancyResources != null) {
            this.voxelOccupancyResources.close();
            this.voxelOccupancyResources = null;
        }
        if (this.localVoxelShadowResources != null) {
            this.localVoxelShadowResources.close();
            this.localVoxelShadowResources = null;
        }
        this.commandEncoder.close();
        this.frameStatePackets.close();
        this.clearPipelineCache();
        try {
            MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
        } catch (Throwable ignored) {
        }
        MetalNativeBridge.metallum_release_object(this.metalLayer);
        this.commandQueue.close();
        if (!MetalNativeBridge.isNullHandle(this.edrMonitor)) {
            MetalNativeBridge.metallum_release_object(this.edrMonitor);
        }
        MetalNativeBridge.metallum_release_device_caches(this.metalDeviceHandle);
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public @NonNull DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    MemorySegment metalDeviceHandle() {
        return this.metalDeviceHandle;
    }

    synchronized SodiumLightSidecarBindings sodiumLightSidecarBindings() {
        if (this.sodiumLightSidecarBindings != null) {
            return this.sodiumLightSidecarBindings;
        }

        MetalGpuBuffer dummyData = null;
        MetalGpuBuffer control = null;
        try {
            int sharedBindingUsage = GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM;
            dummyData = new MetalGpuBuffer(this, sharedBindingUsage, Short.BYTES);
            control = new MetalGpuBuffer(this, sharedBindingUsage, Integer.BYTES * 2L);
            try (var mapped = dummyData.map(0L, Short.BYTES, false, true)) {
                mapped.data().putShort(0, (short) 0);
            }
            try (var mapped = control.map(0L, Integer.BYTES * 2L, false, true)) {
                mapped.data().putInt(0, 0);
                mapped.data().putInt(Integer.BYTES, 1);
            }
            this.sodiumLightSidecarBindings = new SodiumLightSidecarBindings(dummyData, control);
            return this.sodiumLightSidecarBindings;
        } catch (RuntimeException exception) {
            if (control != null) {
                control.close();
            }
            if (dummyData != null) {
                dummyData.close();
            }
            throw exception;
        }
    }

    private synchronized void closeSodiumLightSidecarBindings() {
        if (this.sodiumLightSidecarBindings == null) {
            return;
        }
        this.sodiumLightSidecarBindings.close();
        this.sodiumLightSidecarBindings = null;
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void waitForPreviouslySubmittedGpuWork() {
        this.commandEncoder.waitForPreviouslySubmittedGpuWork();
    }

    public void setGpuTimingStage(final MetalGpuTimingStage stage) {
        this.commandEncoder.setGpuTimingStage(stage);
    }

    MetalGpuTimingStage gpuTimingStageForTests() {
        return this.commandEncoder.gpuTimingStage();
    }

    public HdrConfig hdrConfig() {
        return this.hdrConfig;
    }

    public static MetalDevice getInstance() {
        return INSTANCE;
    }

    long currentSubmitIndex() {
        return this.commandEncoder.currentSubmitIndex();
    }

    synchronized @Nullable SunShadowGpuResources sunShadowResourcesForCurrentFrame() {
        RendererGenerationConfig generation = this.activeRendererGeneration;
        SunShadowGpuResources resources = this.sunShadowResources;
        long submitIndex = this.commandEncoder.currentSubmitIndex();
        if (generation == null
                || generation.lightingModel() != LightingModel.ADVANCED
                || resources == null
                || resources.frameForSubmit(submitIndex) == null) {
            return null;
        }
        return resources;
    }

    synchronized void completeSunShadowFrame(final long submitIndex) {
        SunShadowGpuResources resources = this.sunShadowResources;
        if (resources == null
                || submitIndex != this.commandEncoder.currentSubmitIndex()
                || submitIndex != this.advancedLightingFrameSubmitIndex
                || !resources.isReady(submitIndex)) {
            throw new IllegalStateException("Completed sun-shadow frame does not match renderer state");
        }
        this.advancedLightingFrameReady = true;
        this.sunShadowFailureLogged = false;
        if (MetalGpuTiming.isEnabled() && (submitIndex + 1L) % 300L == 0L) {
            var telemetry = resources.cacheTelemetry();
            Metallum.LOGGER.info(
                    "L6 sun cache: staticUpdates={}, staticReuses={}, dynamicUpdates={}, blockInvalidations={}, bytes={}",
                    telemetry.staticUpdates(),
                    telemetry.staticReuses(),
                    telemetry.dynamicUpdates(),
                    telemetry.blockInvalidations(),
                    telemetry.resourceBytes()
            );
        }
    }

    synchronized void failSunShadowFrame(final RuntimeException failure) {
        this.advancedLightingFrameReady = false;
        this.advancedLightingFrameSubmitIndex = Long.MIN_VALUE;
        AdvancedLightingRuntime.reportNativeAdmission(false, "L4 sun-shadow render pass failed");
        this.publishedRendererGeneration = null;
        if (!this.sunShadowFailureLogged) {
            this.sunShadowFailureLogged = true;
            Metallum.LOGGER.warn(
                    "L4 sun-shadow pass failed; preserving the METALLUM material path with Vanilla lighting",
                    failure
            );
        }
    }

    public boolean supportsSpatialScaling() {
        return this.spatialScalingSupported;
    }

    /** Publishes the immutable renderer generation after output, scale and material admission. */
    public synchronized void publishRendererGenerationState(
            final int displayWidth,
            final int displayHeight
    ) {
        int safeDisplayWidth = Math.max(displayWidth, 1);
        int safeDisplayHeight = Math.max(displayHeight, 1);
        MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.effectiveDimensions(
                safeDisplayWidth,
                safeDisplayHeight
        );
        boolean spatialActive = dimensions.renderWidth() != dimensions.displayWidth()
                || dimensions.renderHeight() != dimensions.displayHeight();
        HdrOutputMode storageCompatibleOutput = MetallumMaterialState.resolveCompatibleOutput(
                this.hdrOutputMode
        );
        MetallumMaterialState.Admission materialAdmission = MetallumMaterialState.admission();
        long materialCoverageEpoch = materialAdmission.coverageEpoch();
        AdvancedLightingRuntime.Admission advancedAdmission = AdvancedLightingRuntime.admission();
        boolean materialStorageCompatible = MetallumMaterialState.isSceneStorageCompatible(
                storageCompatibleOutput != HdrOutputMode.SDR
        );
        boolean materialGenerationAvailable = this.requestedRenderContract == RenderContractMode.METALLUM
                && materialAdmission.active()
                && materialStorageCompatible;
        DisplayOutputMode requestedOutput = resolveRendererOutputMode(
                storageCompatibleOutput
        );
        RendererGenerationKey currentKey = this.publishedRendererGeneration;
        if (currentKey != null
                && currentKey.renderWidth() == dimensions.renderWidth()
                && currentKey.renderHeight() == dimensions.renderHeight()
                && currentKey.displayWidth() == dimensions.displayWidth()
                && currentKey.displayHeight() == dimensions.displayHeight()
                && currentKey.outputMode() == requestedOutput
                && currentKey.spatialActive() == spatialActive
                && currentKey.materialCoverageEpoch() == materialCoverageEpoch
                && currentKey.advancedAdmissionEpoch() == advancedAdmission.epoch()) {
            return;
        }
        LightingModel requestedLighting = this.requestedRenderContract == RenderContractMode.METALLUM
                && this.rendererConfig.improvedLighting()
                ? LightingModel.ADVANCED
                : LightingModel.VANILLA;
        RendererFeatureMask activeFeatures = spatialActive
                ? RendererFeatureMask.of(RendererFeatureMask.SPATIAL_UPSCALING)
                : RendererFeatureMask.NONE;
        RendererGenerationPlanner.MaterialSceneStorage materialSceneStorage = resolveMainSceneStorage(
                HdrSceneState.isRequested(),
                MetallumMaterialState.requiresFp16Scene()
        );
        MetalCapabilities failClosedCapabilities = materialGenerationAvailable
                ? this.rendererCapabilities.withRuntimeFeature(
                        MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT
                )
                : this.rendererCapabilities;
        MetalCapabilities generationCapabilities = failClosedCapabilities;
        if (advancedAdmission.ready()) {
            generationCapabilities = generationCapabilities.withRuntimeFeature(
                    MetalCapabilities.Feature.ADVANCED_LIGHTING
            );
        }
        RendererGenerationPlanner.Plan plan = RendererGenerationPlanner.plan(
                this.requestedRenderContract,
                requestedLighting,
                requestedOutput,
                MetalExecutorKind.METAL3,
                this.rendererConfig.lightingPreset(),
                activeFeatures,
                requestedOutput,
                generationCapabilities,
                new RendererGenerationPlanner.Extent(dimensions.renderWidth(), dimensions.renderHeight()),
                new RendererGenerationPlanner.Extent(dimensions.displayWidth(), dimensions.displayHeight()),
                this.temporalDiagnosticsActive,
                materialSceneStorage,
                HdrSemanticState.isRequested()
        );
        if (!plan.manifest().executable()) {
            throw new IllegalStateException("Non-executable renderer generation passed admission");
        }

        TemporalDiagnosticResources nextDiagnosticResources = null;
        if (this.temporalDiagnosticsActive) {
            try {
                nextDiagnosticResources = TemporalDiagnosticResources.create(
                        this, dimensions.renderWidth(), dimensions.renderHeight()
                );
            } catch (RuntimeException exception) {
                this.temporalDiagnosticsActive = false;
                if (!this.temporalDiagnosticFailureLogged) {
                    this.temporalDiagnosticFailureLogged = true;
                    Metallum.LOGGER.warn(
                            "Temporal diagnostic resource ring creation failed; continuing with the normal renderer path",
                            exception
                    );
                }
                plan = RendererGenerationPlanner.plan(
                        this.requestedRenderContract,
                        requestedLighting,
                        requestedOutput,
                        MetalExecutorKind.METAL3,
                        this.rendererConfig.lightingPreset(),
                        activeFeatures,
                        requestedOutput,
                        generationCapabilities,
                        new RendererGenerationPlanner.Extent(dimensions.renderWidth(), dimensions.renderHeight()),
                        new RendererGenerationPlanner.Extent(dimensions.displayWidth(), dimensions.displayHeight()),
                        false,
                        materialSceneStorage,
                        HdrSemanticState.isRequested()
                );
            }
        }

        RendererGenerationConfig previousGeneration = this.activeRendererGeneration;
        RendererGenerationConfig resolved = plan.resolution().config();
        RendererGenerationManifest manifest = plan.manifest();
        FrameState.ResourceBytes resourceBytes = resourceBytes(manifest);
        long plannedLightingGeneration = this.lightingGenerationId;
        if (previousGeneration == null
                || previousGeneration.lightingModel() != resolved.lightingModel()) {
            plannedLightingGeneration = Math.addExact(plannedLightingGeneration, 1L);
        }
        AdvancedLightingGpuResources nextAdvancedResources = null;
        SunShadowGpuResources nextSunShadowResources = null;
        VoxelOccupancyGpuResources nextVoxelResources = null;
        LocalVoxelShadowGpuResources nextLocalVoxelShadowResources = null;
        if (resolved.lightingModel() == LightingModel.ADVANCED) {
            AdvancedLightingLayout.Budget lightingBudget = AdvancedLightingLayout.forGeneration(
                    resolved.lightingPreset(),
                    dimensions.renderWidth(),
                    dimensions.renderHeight()
            );
            try {
                if (this.advancedLightingResources != null
                        && this.advancedLightingResources.generation() == plannedLightingGeneration
                        && this.advancedLightingResources.budget().equals(lightingBudget)) {
                    nextAdvancedResources = this.advancedLightingResources;
                } else {
                    nextAdvancedResources = AdvancedLightingGpuResources.create(
                            this.metalDeviceHandle,
                            plannedLightingGeneration,
                            lightingBudget
                    );
                }
                SunShadowLayout.Budget shadowBudget = SunShadowLayout.forPreset(
                        resolved.lightingPreset()
                );
                if (this.sunShadowResources != null
                        && this.sunShadowResources.generation() == plannedLightingGeneration
                        && this.sunShadowResources.budget().equals(shadowBudget)) {
                    nextSunShadowResources = this.sunShadowResources;
                } else {
                    nextSunShadowResources = SunShadowGpuResources.create(
                            this,
                            plannedLightingGeneration,
                            shadowBudget
                    );
                }
                LocalVoxelShadowLayout.Budget localShadowBudget =
                        LocalVoxelShadowLayout.forPreset(resolved.lightingPreset());
                if (this.localVoxelShadowResources != null
                        && this.localVoxelShadowResources.generation()
                        == plannedLightingGeneration
                        && this.localVoxelShadowResources.budget().equals(localShadowBudget)) {
                    nextLocalVoxelShadowResources = this.localVoxelShadowResources;
                } else {
                    nextLocalVoxelShadowResources = LocalVoxelShadowGpuResources.create(
                            this,
                            plannedLightingGeneration,
                            localShadowBudget
                    );
                }
                try {
                    this.resetVoxelFailureForGeneration(
                            Math.addExact(this.rendererGenerationId, 1L),
                            plannedLightingGeneration
                    );
                    VoxelClipmapLayout.Budget voxelBudget = VoxelClipmapLayout.forPreset(
                            voxelPreset(resolved.lightingPreset())
                    );
                    VoxelClipmapSnapshot voxelSnapshot = VoxelClipmapController.global().snapshot();
                    if (this.voxelOccupancyResources != null
                            && (voxelSnapshot == null
                            ? this.voxelOccupancyResources.matchesGenerationAndBudget(
                                    plannedLightingGeneration, voxelBudget)
                            : this.voxelOccupancyResources.matches(
                                    plannedLightingGeneration, voxelBudget, voxelSnapshot))) {
                        nextVoxelResources = this.voxelOccupancyResources;
                    } else {
                        nextVoxelResources = VoxelOccupancyGpuResources.create(
                                this.metalDeviceHandle,
                                plannedLightingGeneration,
                                voxelBudget,
                                voxelSnapshot
                        );
                    }
                } catch (RuntimeException exception) {
                    if (nextVoxelResources != null
                            && nextVoxelResources != this.voxelOccupancyResources) {
                        nextVoxelResources.close();
                    }
                    nextVoxelResources = null;
                    this.suppressVoxelRetryForGeneration(
                            Math.addExact(this.rendererGenerationId, 1L),
                            plannedLightingGeneration,
                            "L5 voxel context creation failed; retaining the established L3/L4 generation",
                            exception
                    );
                }
            } catch (RuntimeException exception) {
                if (nextLocalVoxelShadowResources != null
                        && nextLocalVoxelShadowResources != this.localVoxelShadowResources) {
                    nextLocalVoxelShadowResources.close();
                }
                nextLocalVoxelShadowResources = null;
                if (nextVoxelResources != null
                        && nextVoxelResources != this.voxelOccupancyResources) {
                    nextVoxelResources.close();
                }
                nextVoxelResources = null;
                if (nextSunShadowResources != null
                        && nextSunShadowResources != this.sunShadowResources) {
                    nextSunShadowResources.close();
                }
                nextSunShadowResources = null;
                if (nextAdvancedResources != null
                        && nextAdvancedResources != this.advancedLightingResources) {
                    nextAdvancedResources.close();
                }
                nextAdvancedResources = null;
                AdvancedLightingRuntime.reportNativeAdmission(
                        false,
                        "Advanced lighting L3-L6 generation creation failed"
                );
                Metallum.LOGGER.warn(
                        "Advanced Lighting generation allocation failed; preserving METALLUM + VANILLA lighting",
                        exception
                );
                plan = RendererGenerationPlanner.plan(
                        this.requestedRenderContract,
                        requestedLighting,
                        requestedOutput,
                        MetalExecutorKind.METAL3,
                        this.rendererConfig.lightingPreset(),
                        activeFeatures,
                        requestedOutput,
                        failClosedCapabilities,
                        new RendererGenerationPlanner.Extent(
                                dimensions.renderWidth(), dimensions.renderHeight()),
                        new RendererGenerationPlanner.Extent(
                                dimensions.displayWidth(), dimensions.displayHeight()),
                        this.temporalDiagnosticsActive,
                        materialSceneStorage,
                        HdrSemanticState.isRequested()
                );
                resolved = plan.resolution().config();
                manifest = plan.manifest();
                resourceBytes = resourceBytes(manifest);
                plannedLightingGeneration = this.lightingGenerationId;
                if (previousGeneration == null
                        || previousGeneration.lightingModel() != resolved.lightingModel()) {
                    plannedLightingGeneration = Math.addExact(plannedLightingGeneration, 1L);
                }
            }
        }

        long committedAdvancedAdmissionEpoch = advancedAdmission.epoch();
        if (resolved.lightingModel() == LightingModel.ADVANCED
                && !AdvancedLightingRuntime.tryAdmitGeneration(
                        committedAdvancedAdmissionEpoch,
                        true
                )) {
            if (nextAdvancedResources != null
                    && nextAdvancedResources != this.advancedLightingResources) {
                nextAdvancedResources.close();
            }
            if (nextSunShadowResources != null
                    && nextSunShadowResources != this.sunShadowResources) {
                nextSunShadowResources.close();
            }
            if (nextVoxelResources != null
                    && nextVoxelResources != this.voxelOccupancyResources) {
                nextVoxelResources.close();
            }
            if (nextLocalVoxelShadowResources != null
                    && nextLocalVoxelShadowResources != this.localVoxelShadowResources) {
                nextLocalVoxelShadowResources.close();
            }
            nextAdvancedResources = null;
            nextSunShadowResources = null;
            nextVoxelResources = null;
            nextLocalVoxelShadowResources = null;
            plan = RendererGenerationPlanner.plan(
                    this.requestedRenderContract,
                    requestedLighting,
                    requestedOutput,
                    MetalExecutorKind.METAL3,
                    this.rendererConfig.lightingPreset(),
                    activeFeatures,
                    requestedOutput,
                    failClosedCapabilities,
                    new RendererGenerationPlanner.Extent(
                            dimensions.renderWidth(), dimensions.renderHeight()),
                    new RendererGenerationPlanner.Extent(
                            dimensions.displayWidth(), dimensions.displayHeight()),
                    this.temporalDiagnosticsActive,
                    materialSceneStorage,
                    HdrSemanticState.isRequested()
            );
            resolved = plan.resolution().config();
            manifest = plan.manifest();
            resourceBytes = resourceBytes(manifest);
            plannedLightingGeneration = this.lightingGenerationId;
            if (previousGeneration == null
                    || previousGeneration.lightingModel() != resolved.lightingModel()) {
                plannedLightingGeneration = Math.addExact(plannedLightingGeneration, 1L);
            }
            if (resolved.lightingModel() != LightingModel.VANILLA) {
                throw new IllegalStateException("Fail-closed Advanced admission did not resolve Vanilla");
            }
        }
        if (resolved.lightingModel() == LightingModel.VANILLA) {
            AdvancedLightingRuntime.admitGeneration(false);
            VoxelClipmapController.global().clear();
        }

        long nextGeneration = Math.addExact(this.rendererGenerationId, 1L);
        long nextRenderContractGeneration = this.renderContractGenerationId;
        if (previousGeneration == null
                || previousGeneration.renderContractMode() != resolved.renderContractMode()) {
            nextRenderContractGeneration = Math.addExact(nextRenderContractGeneration, 1L);
        }
        long nextLightingGeneration = this.lightingGenerationId;
        if (previousGeneration == null
                || previousGeneration.lightingModel() != resolved.lightingModel()) {
            nextLightingGeneration = Math.addExact(nextLightingGeneration, 1L);
        }
        if (nextLightingGeneration != plannedLightingGeneration) {
            if (nextAdvancedResources != null
                    && nextAdvancedResources != this.advancedLightingResources) {
                nextAdvancedResources.close();
            }
            if (nextSunShadowResources != null
                    && nextSunShadowResources != this.sunShadowResources) {
                nextSunShadowResources.close();
            }
            if (nextVoxelResources != null
                    && nextVoxelResources != this.voxelOccupancyResources) {
                nextVoxelResources.close();
            }
            if (nextLocalVoxelShadowResources != null
                    && nextLocalVoxelShadowResources != this.localVoxelShadowResources) {
                nextLocalVoxelShadowResources.close();
            }
            throw new IllegalStateException("Advanced lighting generation prediction diverged");
        }
        long nextOutputGeneration = this.outputGenerationId;
        if (previousGeneration == null || previousGeneration.outputMode() != resolved.outputMode()) {
            nextOutputGeneration = Math.addExact(nextOutputGeneration, 1L);
        }

        this.rendererGenerationId = nextGeneration;
        this.renderContractGenerationId = nextRenderContractGeneration;
        this.lightingGenerationId = nextLightingGeneration;
        this.outputGenerationId = nextOutputGeneration;
        this.activeRendererGeneration = resolved;
        MetallumMaterialState.publishGeneration(
                resolved.renderContractMode() == RenderContractMode.METALLUM
        );
        if (!usesLegacyHdrSemanticAttachment(resolved.renderContractMode(), resolved.outputMode())) {
            this.releaseLegacyHdrGenerationResources();
        }
        this.activeRendererResourceBytes = resourceBytes;
        AdvancedLightingGpuResources previousAdvancedResources = this.advancedLightingResources;
        SunShadowGpuResources previousSunShadowResources = this.sunShadowResources;
        VoxelOccupancyGpuResources previousVoxelResources = this.voxelOccupancyResources;
        LocalVoxelShadowGpuResources previousLocalVoxelShadowResources =
                this.localVoxelShadowResources;
        this.advancedLightingResources = nextAdvancedResources;
        this.sunShadowResources = nextSunShadowResources;
        this.voxelOccupancyResources = nextVoxelResources;
        this.localVoxelShadowResources = nextLocalVoxelShadowResources;
        this.resetVoxelFailureForGeneration(nextGeneration, nextLightingGeneration);
        if (previousVoxelResources != nextVoxelResources) {
            this.observedVoxelNativeRejections = 0L;
            this.resetVoxelDebugChecksumState();
        }
        this.advancedLightingFrameReady = false;
        this.advancedLightingFrameSubmitIndex = Long.MIN_VALUE;
        if (previousAdvancedResources != null
                && previousAdvancedResources != nextAdvancedResources) {
            previousAdvancedResources.close();
        }
        if (previousSunShadowResources != null
                && previousSunShadowResources != nextSunShadowResources) {
            previousSunShadowResources.close();
        }
        if (previousVoxelResources != null
                && previousVoxelResources != nextVoxelResources) {
            previousVoxelResources.close();
        }
        if (previousLocalVoxelShadowResources != null
                && previousLocalVoxelShadowResources != nextLocalVoxelShadowResources) {
            previousLocalVoxelShadowResources.close();
        }
        this.activeRenderExtent = new FrameState.Extent(
                dimensions.renderWidth(), dimensions.renderHeight()
        );
        this.activeDisplayExtent = new FrameState.Extent(
                dimensions.displayWidth(), dimensions.displayHeight()
        );
        TemporalDiagnosticResources previousDiagnostics = this.temporalDiagnosticResources;
        this.temporalDiagnosticResources = nextDiagnosticResources;
        if (previousDiagnostics != null) {
            previousDiagnostics.close();
        }
        RendererGenerationKey key = new RendererGenerationKey(
                dimensions.renderWidth(),
                dimensions.renderHeight(),
                dimensions.displayWidth(),
                dimensions.displayHeight(),
                requestedOutput,
                spatialActive,
                materialCoverageEpoch,
                committedAdvancedAdmissionEpoch
        );
        this.publishedRendererGeneration = key;
        RendererAdmissionLogState admissionLogState = new RendererAdmissionLogState(
                plan.resolution().rejectionReasons(),
                resolved.renderContractMode(),
                resolved.lightingModel(),
                resolved.outputMode(),
                spatialActive
        );
        if (!admissionLogState.equals(this.loggedRendererAdmission)) {
            this.loggedRendererAdmission = admissionLogState;
            if (plan.resolution().fellBack()) {
                Metallum.LOGGER.warn(
                        "Renderer generation request resolved with fallback {}: contract={}, lighting={}, output={}, upscale={}, interpolation=OFF",
                        plan.resolution().rejectionReasons(),
                        resolved.renderContractMode(),
                        resolved.lightingModel(),
                        resolved.outputMode(),
                        spatialActive ? "SPATIAL" : "NATIVE"
                );
            } else {
                Metallum.LOGGER.info(
                        "Renderer generation admitted: contract={}, lighting={}, output={}, upscale={}, interpolation=OFF",
                        resolved.renderContractMode(),
                        resolved.lightingModel(),
                        resolved.outputMode(),
                        spatialActive ? "SPATIAL" : "NATIVE"
                );
            }
        }
        if (this.rendererConfig.frameInterpolation() && !this.frameInterpolationAdmissionLogged) {
            this.frameInterpolationAdmissionLogged = true;
            Metallum.LOGGER.warn(
                    "Frame Interpolation was requested but remains disabled until its production admission stage"
            );
        }
    }

    /** Publishes one final world-camera snapshot into its reusable in-flight ABI slot. */
    public synchronized FrameState publishFrameState(final FrameCapture capture) {
        Objects.requireNonNull(capture, "capture");
        RendererGenerationConfig generation = this.activeRendererGeneration;
        RendererGenerationKey generationKey = this.publishedRendererGeneration;
        FrameState.Extent renderExtent = this.activeRenderExtent;
        FrameState.Extent displayExtent = this.activeDisplayExtent;
        if (generation == null || generationKey == null
                || renderExtent == null || displayExtent == null) {
            throw new IllegalStateException("Renderer generation must be admitted before FrameState publication");
        }
        if (generation.lightingModel() == LightingModel.ADVANCED
                && !AdvancedLightingRuntime.isActive()) {
            this.publishedRendererGeneration = null;
            this.publishRendererGenerationState(displayExtent.width(), displayExtent.height());
            generation = this.activeRendererGeneration;
            generationKey = this.publishedRendererGeneration;
            renderExtent = this.activeRenderExtent;
            displayExtent = this.activeDisplayExtent;
            if (generation == null || generationKey == null
                    || renderExtent == null || displayExtent == null
                    || (generation.lightingModel() == LightingModel.ADVANCED
                    && !AdvancedLightingRuntime.isActive())) {
                throw new IllegalStateException(
                        "Advanced admission was lost before a fail-closed frame could be published"
                );
            }
        }
        if (generation.lightingModel() == LightingModel.ADVANCED
                && AdvancedLightingRuntime.isActive()) {
            this.detectAsyncVoxelFailure();
            this.refreshVoxelOccupancyResources(generation);
        }
        long submitIndex = this.commandEncoder.currentSubmitIndex();
        this.advancedLightingFrameReady = false;
        this.advancedLightingFrameSubmitIndex = Long.MIN_VALUE;
        AdvancedLightingGpuResources lightingResources = this.advancedLightingResources;
        SunShadowGpuResources shadowResources = this.sunShadowResources;
        VoxelOccupancyGpuResources voxelResources = this.voxelOccupancyResources;
        LocalVoxelShadowGpuResources localShadowResources =
                this.localVoxelShadowResources;
        VoxelClipmapController voxelController = VoxelClipmapController.global();
        LightFrameSnapshot lightSnapshot = null;
        FrameState.AdvancedLightingWork advancedWork = FrameState.AdvancedLightingWork.NONE;
        if (generation.lightingModel() == LightingModel.ADVANCED
                && AdvancedLightingRuntime.isActive()
                && retainsL3L4AfterVoxelFailure(lightingResources != null, shadowResources != null)
                && localShadowResources != null) {
            lightSnapshot = AdvancedLightRegistry.global().snapshotForFrameIfHealthy(
                    DirectLightFrustum.from(capture),
                    lightingResources.budget().maxLights(),
                    advancedLightingAdmissionLimit(generation.lightingPreset())
            );
            if (lightSnapshot == null) {
                this.publishedRendererGeneration = null;
                this.publishRendererGenerationState(displayExtent.width(), displayExtent.height());
                generation = this.activeRendererGeneration;
                generationKey = this.publishedRendererGeneration;
                renderExtent = this.activeRenderExtent;
                displayExtent = this.activeDisplayExtent;
                lightingResources = null;
                if (generation == null || generationKey == null
                        || renderExtent == null || displayExtent == null
                        || generation.lightingModel() == LightingModel.ADVANCED) {
                    throw new IllegalStateException(
                            "Unhealthy Advanced registry did not resolve a fail-closed frame"
                    );
                }
            } else {
                advancedWork = advancedLightingWork(
                        lightSnapshot.lights().size(),
                        shadowResources.budget().cascadeCount(),
                        capture.environment().sunShadowEligible(),
                        localShadowResources.budget()
                );
            }
        } else if (generation.lightingModel() == LightingModel.ADVANCED) {
            AdvancedLightingRuntime.reportNativeAdmission(
                    false,
                    "active Advanced generation lost its native context"
            );
            this.publishedRendererGeneration = null;
        }
        FrameState candidate = new FrameState(
                FrameContract.temporalPreparationV1(),
                0L,
                this.rendererGenerationId,
                0L,
                this.renderContractGenerationId,
                this.lightingGenerationId,
                this.outputGenerationId,
                generation.renderContractMode(),
                generation.lightingModel(),
                generation.outputMode(),
                generation.lightingPreset(),
                generation.featureMask(),
                generation.executorKind(),
                generation.frameResourceContractVersion(),
                this.activeRendererResourceBytes,
                advancedWork,
                capture.transforms(),
                capture.transforms(),
                renderExtent,
                displayExtent,
                1.0,
                1.0,
                FrameState.JitterOffset.ZERO,
                Set.of(),
                submitIndex,
                (int) (submitIndex % FrameStatePacketRing.SLOT_COUNT),
                capture.deltaSeconds(),
                capture.nearPlane(),
                capture.farPlane(),
                capture.cameraPosition(),
                capture.cameraPosition(),
                capture.worldIdentity(),
                capture.dimensionIdentity(),
                this.hdrCurrentHeadroom,
                Math.max(
                        this.hdrCurrentHeadroom,
                        this.rendererCapabilities.displayCapabilities().potentialHeadroom()
                )
        );
        FrameState published = this.frameStateTracker.prepare(
                candidate,
                TemporalResetEvents.consume()
        );
        VoxelUploadBatch voxelBatch = null;
        VoxelOccupancyGpuResources.FrameUpload voxelUpload = null;
        boolean voxelUploadAttemptedThisFrame = false;
        if (lightSnapshot != null && voxelResources != null) {
            voxelBatch = voxelController.leaseUploadBatch(published.frameId());
            if (voxelBatch != null) {
                try {
                    voxelUpload = voxelResources.encode(voxelBatch, published);
                    published = published.withAdvancedLightingWork(withVoxelWork(
                            published.advancedLightingWork(),
                            voxelUpload.patchCount(),
                            voxelUpload.uploadBytes()
                    ));
                } catch (RuntimeException exception) {
                    voxelController.retryUploadBatch(voxelBatch.batchId());
                    voxelBatch = null;
                    Metallum.LOGGER.warn(
                            "L5 voxel batch became stale before native submission; retaining L3/L4",
                            exception
                    );
                }
            }
        }
        MemorySegment packet = this.frameStatePackets.encode(published);
        int status = MetalNativeBridge.metallum_set_frame_state_v3(packet);
        if (status != 1) {
            if (voxelBatch != null) {
                voxelController.retryUploadBatch(voxelBatch.batchId());
            }
            throw new IllegalStateException("Native FrameState v3 admission failed with status " + status);
        }
        if (lightSnapshot != null && lightingResources != null) {
            int lightingStatus;
            try {
                AdvancedLightingGpuResources.FrameUpload upload = lightingResources.encode(
                        lightSnapshot,
                        published
                );
                lightingStatus = this.commandEncoder.encodeAdvancedLighting(
                        lightingResources,
                        upload
                );
            } catch (RuntimeException exception) {
                lightingStatus = Integer.MIN_VALUE;
                Metallum.LOGGER.warn(
                        "Advanced Lighting frame upload failed; using Vanilla lighting for this frame",
                        exception
                );
            }
            if (lightingStatus == AdvancedLightingGpuResources.STATUS_OK) {
                if (voxelBatch != null && voxelUpload != null && voxelResources != null) {
                    int voxelStatus;
                    try {
                        voxelUploadAttemptedThisFrame = true;
                        voxelStatus = this.commandEncoder.encodeVoxelOccupancy(
                                voxelResources,
                                voxelUpload
                        );
                    } catch (RuntimeException exception) {
                        voxelStatus = Integer.MIN_VALUE;
                        Metallum.LOGGER.warn(
                                "L5 voxel upload failed; retaining the established L3/L4 frame",
                                exception
                        );
                    }
                    if (voxelStatus == VoxelOccupancyGpuResources.STATUS_OK) {
                        shadowResources.invalidateVoxelBatch(voxelBatch);
                        voxelController.completeUploadBatch(voxelBatch.batchId());
                        voxelBatch = null;
                        this.acknowledgeVoxelNativeRejection(voxelResources);
                        this.voxelTransientBusyLogged = false;
                    } else {
                        voxelController.retryUploadBatch(voxelBatch.batchId());
                        voxelBatch = null;
                        published = published.withAdvancedLightingWork(advancedWork);
                        int voxelFallbackStatus = MetalNativeBridge.metallum_set_frame_state_v3(
                                this.frameStatePackets.encode(published)
                        );
                        if (voxelFallbackStatus != 1) {
                            throw new IllegalStateException(
                                    "Native L5 fallback FrameState failed with status "
                                            + voxelFallbackStatus
                            );
                        }
                        if (voxelStatus == VoxelOccupancyGpuResources.STATUS_RING_SLOT_BUSY) {
                            this.acknowledgeVoxelNativeRejection(voxelResources);
                            if (!this.voxelTransientBusyLogged) {
                                this.voxelTransientBusyLogged = true;
                                Metallum.LOGGER.warn(
                                        "L5 voxel upload ring was busy; its batch was requeued without disabling L3/L4"
                                );
                            }
                        } else {
                            this.disableVoxelOccupancy(
                                    "native L5 voxel upload failed with status " + voxelStatus,
                                    null
                            );
                        }
                    }
                }
                try {
                    if (shadowResources == null || localShadowResources == null) {
                        throw new IllegalStateException("L4/L6 shadow resources disappeared");
                    }
                    shadowResources.encode(capture.environment(), published);
                    voxelResources = this.voxelOccupancyResources;
                    VoxelClipmapSnapshot localVoxelSnapshot = voxelController.snapshot();
                    EntityShadowProxySnapshot proxySnapshot = lightSnapshot.world() == null
                            ? null
                            : EntityShadowProxyRegistry.global().snapshot(lightSnapshot.world());
                    LocalVoxelShadowGpuResources.PreparedFrame localPrepared =
                            localShadowResources.encode(
                                    published,
                                    voxelResources,
                                    localVoxelSnapshot,
                                    proxySnapshot,
                                    lightSnapshot
                            );
                    localPrepared = localShadowResources.uploadPending(this.commandEncoder);
                    if (MetalGpuTiming.isReportEnabled()
                            && (submitIndex + 1L) % 300L == 0L) {
                        Metallum.LOGGER.info(
                                "L6 local shadows: active={}, descriptors={}/snapshot={}, READY={}, STALE={}, APPROXIMATE={}, BUILDING={}, FAIL_CLOSED={}, cacheCovered={}, coverageLimited={}, residents={}, pendingBuilds={}, pendingUploads={} ({} bytes), capacityBlocked={}, retryBackoff={}, uploads={} ({} bytes), proxies={}/{}, maxSteps={}",
                                localPrepared.active(),
                                localPrepared.descriptorLights(),
                                lightSnapshot.lights().size(),
                                localPrepared.readyLights(),
                                localPrepared.staleLights(),
                                localPrepared.approximateDirectLights(),
                                localPrepared.buildingLights(),
                                localPrepared.failClosedLights(),
                                localPrepared.cacheCoveredLights(),
                                localPrepared.coverageLimitedLights(),
                                localPrepared.residentPages(),
                                localPrepared.pendingBuilds(),
                                localPrepared.pendingUploads(),
                                localPrepared.pendingPayloadBytes(),
                                localPrepared.capacityBlockedLights(),
                                localPrepared.retryBackoffLights(),
                                localPrepared.cacheUploads(),
                                localPrepared.cacheUploadBytes(),
                                localPrepared.proxyCount(),
                                localShadowResources.budget().maxEntityProxies(),
                                localShadowResources.budget().maxSteps()
                        );
                    }
                    this.advancedLightingFrameSubmitIndex = submitIndex;
                    this.advancedLightingFrameReady = shadowResources.isReady(submitIndex);
                    this.advancedLightingTransientFallbackLogged = false;
                    if (shouldScheduleVoxelDebugChecksum(
                            this.rendererConfig.voxelDebugChecksum(),
                            generation.lightingModel(),
                            voxelResources != null,
                            this.advancedLightingFrameReady,
                            voxelUploadAttemptedThisFrame,
                            this.voxelDebugChecksumRuntimeDisabled,
                            submitIndex,
                            this.lastVoxelDebugChecksumSubmitIndex
                    )) {
                        int debugStatus;
                        try {
                            debugStatus = this.commandEncoder.encodeVoxelDebugChecksum(
                                    voxelResources,
                                    0,
                                    published.inFlightSlot()
                            );
                        } catch (RuntimeException exception) {
                            debugStatus = Integer.MIN_VALUE;
                            if (!this.voxelDebugChecksumFailureLogged) {
                                this.voxelDebugChecksumFailureLogged = true;
                                Metallum.LOGGER.warn(
                                        "L5 GPU checksum diagnostic failed; disabling only the diagnostic",
                                        exception
                                );
                            }
                        }
                        if (debugStatus == VoxelOccupancyGpuResources.STATUS_OK) {
                            this.lastVoxelDebugChecksumSubmitIndex = submitIndex;
                            this.voxelDebugChecksumFailureLogged = false;
                        } else if (debugStatus
                                != VoxelOccupancyGpuResources.STATUS_RING_SLOT_BUSY) {
                            this.voxelDebugChecksumRuntimeDisabled = true;
                            if (!this.voxelDebugChecksumFailureLogged) {
                                this.voxelDebugChecksumFailureLogged = true;
                                Metallum.LOGGER.warn(
                                        "L5 GPU checksum diagnostic returned status {}; disabling only the diagnostic",
                                        debugStatus
                                );
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    lightingStatus = Integer.MIN_VALUE;
                    this.advancedLightingFrameReady = false;
                    this.advancedLightingFrameSubmitIndex = Long.MIN_VALUE;
                    Metallum.LOGGER.warn(
                            "L4/L6 shadow frame preparation failed; using Vanilla lighting for this frame",
                            exception
                    );
                }
            }
            if (lightingStatus != AdvancedLightingGpuResources.STATUS_OK) {
                if (voxelBatch != null) {
                    voxelController.retryUploadBatch(voxelBatch.batchId());
                    voxelBatch = null;
                }
                this.advancedLightingFrameReady = false;
                this.advancedLightingFrameSubmitIndex = Long.MIN_VALUE;
                FrameState fallback = published.withAdvancedLightingWork(
                        FrameState.AdvancedLightingWork.NONE
                );
                int fallbackStatus = MetalNativeBridge.metallum_set_frame_state_v3(
                        this.frameStatePackets.encode(fallback)
                );
                if (fallbackStatus != 1) {
                    throw new IllegalStateException(
                            "Native Advanced-lighting fallback FrameState failed with status "
                                    + fallbackStatus
                    );
                }
                published = fallback;
                if (lightingStatus == AdvancedLightingGpuResources.STATUS_RING_SLOT_BUSY) {
                    if (!this.advancedLightingTransientFallbackLogged) {
                        this.advancedLightingTransientFallbackLogged = true;
                        Metallum.LOGGER.warn(
                                "Advanced Lighting upload ring was busy; the affected frame uses Vanilla lighting"
                        );
                    }
                } else {
                    AdvancedLightingRuntime.reportNativeAdmission(
                            false,
                            "native Advanced lighting frame upload failed with status "
                                    + lightingStatus
                    );
                    this.publishedRendererGeneration = null;
                    Metallum.LOGGER.warn(
                            "Advanced Lighting native upload rejected status {}; preserving METALLUM + VANILLA lighting",
                            lightingStatus
                    );
                }
            }
        }
        this.frameStateTracker.commit(published);
        return published;
    }

    /** Encodes the isolated diagnostic between world depth completion and the UI depth clear. */
    public synchronized void encodeTemporalDiagnostics(final GpuTexture depthTexture) {
        TemporalDiagnosticResources resources = this.temporalDiagnosticResources;
        if (!this.temporalDiagnosticsActive || resources == null) {
            return;
        }
        if (!(depthTexture instanceof MetalGpuTexture depth)
                || depth.getFormat() != GpuFormat.D32_FLOAT) {
            this.disableTemporalDiagnostics("main depth is not a Metal D32Float texture", null);
            return;
        }
        int slot = (int) (this.commandEncoder.currentSubmitIndex() % FrameStatePacketRing.SLOT_COUNT);
        int status = this.commandEncoder.encodeTemporalDiagnostics(depth, resources.pair(slot));
        if (status < 0) {
            this.disableTemporalDiagnostics("native diagnostic pass failed with status " + status, null);
        }
    }

    private void disableTemporalDiagnostics(final String reason, @Nullable final Throwable exception) {
        this.temporalDiagnosticsActive = false;
        if (this.temporalDiagnosticResources != null) {
            this.temporalDiagnosticResources.close();
            this.temporalDiagnosticResources = null;
        }
        this.publishedRendererGeneration = null;
        if (!this.temporalDiagnosticFailureLogged) {
            this.temporalDiagnosticFailureLogged = true;
            if (exception == null) {
                Metallum.LOGGER.warn("Temporal diagnostics disabled: {}", reason);
            } else {
                Metallum.LOGGER.warn("Temporal diagnostics disabled: {}", reason, exception);
            }
        }
    }

    public HdrOutputMode hdrOutputMode() {
        return this.hdrOutputMode;
    }

    public void updateHdrConfig(HdrConfig newConfig) {
        this.hdrConfig = newConfig;
        newConfig.save();
    }

    EdrCapabilities queryEdrCapabilities() {
        return MetalNativeBridge.metallum_EDRMonitor_query(this.edrMonitor);
    }

    void setHdrOutputMode(final HdrOutputMode outputMode, final float currentHeadroom) {
        HdrOutputMode compatibleOutputMode = this.availableHdrOutputMode(outputMode);
        HdrOutputMode previousOutputMode = this.hdrOutputMode;
        this.hdrOutputMode = compatibleOutputMode;
        this.hdrCurrentHeadroom = Float.isFinite(currentHeadroom)
                ? Math.clamp(currentHeadroom, 1.0f, HdrConfig.OUTPUT_HEADROOM)
                : 1.0f;
        this.hdrEnhancedActive = !this.hdrEnhancementUnavailable
                && compatibleOutputMode == HdrOutputMode.ENHANCED
                && currentHeadroom > 1.001f
                && this.hdrConfig.hdrStrength() > 0.0f
                && !this.hdrConfig.diagnosticPattern();
        if (this.hdrEnhancedActive && !this.hdrEnhancementActivationLogged) {
            this.hdrEnhancementActivationLogged = true;
            Metallum.LOGGER.info("Semantic HDR enhancement is active with current EDR headroom {}", currentHeadroom);
        }
        if (!this.hdrEnhancedActive && !this.isMaterialGenerationActive()) {
            this.hdrSceneAvailable = false;
            this.hdrSemanticSceneAvailable = false;
            this.resetHdrSceneColor();
        }
        MetalFxSpatialScaling.onHdrOutputModeChanged(previousOutputMode, compatibleOutputMode);
        int displayWidth = MetalFxSpatialScaling.configuredDisplayWidth(0);
        int displayHeight = MetalFxSpatialScaling.configuredDisplayHeight(0);
        if (displayWidth > 0 && displayHeight > 0) {
            this.publishRendererGenerationState(displayWidth, displayHeight);
        }
    }

    HdrOutputMode availableHdrOutputMode(final HdrOutputMode requestedMode) {
        return resolveAvailableHdrOutputMode(requestedMode, this.hdrEnhancementUnavailable);
    }

    static HdrOutputMode resolveAvailableHdrOutputMode(
            final HdrOutputMode requestedMode,
            final boolean hdrEnhancementUnavailable
    ) {
        HdrOutputMode storageCompatibleMode = MetallumMaterialState.resolveCompatibleOutput(
                requestedMode
        );
        return storageCompatibleMode == HdrOutputMode.ENHANCED && hdrEnhancementUnavailable
                ? HdrOutputMode.EDR
                : storageCompatibleMode;
    }

    /**
     * Resolves the scene-generation output axis independently from material
     * and lighting admission. Any HDR request remains HDR after either fails.
     */
    static DisplayOutputMode resolveRendererOutputMode(
            final HdrOutputMode outputMode
    ) {
        Objects.requireNonNull(outputMode, "outputMode");
        return outputMode != HdrOutputMode.SDR
                ? DisplayOutputMode.HDR
                : DisplayOutputMode.SDR;
    }

    static RenderContractMode requestedRenderContract() {
        String override = System.getProperty("metallum.renderer.contract");
        if (override == null || override.isBlank()) {
            return RenderContractMode.METALLUM;
        }
        if ("legacy".equalsIgnoreCase(override.strip())) {
            Metallum.LOGGER.warn(
                    "Compatibility override metallum.renderer.contract=legacy is active; forcing LEGACY + VANILLA"
            );
            return RenderContractMode.LEGACY;
        }
        Metallum.LOGGER.warn(
                "Ignoring unknown metallum.renderer.contract='{}'; requesting the automatic METALLUM contract",
                override
        );
        return RenderContractMode.METALLUM;
    }

    static RendererGenerationPlanner.MaterialSceneStorage resolveMainSceneStorage(
            final boolean legacyFp16SceneRequested,
            final boolean materialFp16SceneRequested
    ) {
        return legacyFp16SceneRequested || materialFp16SceneRequested
                ? RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
                : RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA8;
    }

    void disableHdrEnhancement() {
        this.hdrEnhancementUnavailable = true;
        this.hdrEnhancedActive = false;
        this.hdrSceneAvailable = false;
        this.hdrSemanticSceneAvailable = false;
        this.resetHdrSceneColor();
        this.hdrUiSuppressSceneEnhancement = false;
    }

    boolean isMaterialGenerationActive() {
        RendererGenerationConfig generation = this.activeRendererGeneration;
        return generation != null
                && generation.renderContractMode() == RenderContractMode.METALLUM;
    }

    boolean isMaterialWorldPassActive() {
        return this.materialWorldPassActive && this.isMaterialGenerationActive();
    }

    static boolean isAdvancedLightingWorldPassActive(
            final RendererGenerationConfig generation,
            final boolean materialWorldPassActive,
            final boolean frameReady,
            final long frameSubmitIndex,
            final long currentSubmitIndex
    ) {
        return generation != null
                && generation.renderContractMode() == RenderContractMode.METALLUM
                && generation.lightingModel() == LightingModel.ADVANCED
                && materialWorldPassActive
                && frameReady
                && frameSubmitIndex == currentSubmitIndex;
    }

    boolean isAdvancedLightingWorldPassActive() {
        return isAdvancedLightingWorldPassActive(
                this.activeRendererGeneration,
                this.materialWorldPassActive,
                this.advancedLightingFrameReady,
                this.advancedLightingFrameSubmitIndex,
                this.commandEncoder.currentSubmitIndex()
        );
    }

    void bindAdvancedLighting(final MTLRenderCommandEncoder encoder) {
        if (!this.isAdvancedLightingWorldPassActive() || this.advancedLightingResources == null) {
            throw new IllegalStateException("Advanced lighting bindings are not ready for this frame");
        }
        AdvancedLightingGpuResources.Bindings bindings = this.advancedLightingResources.bindings();
        encoder.setBuffer(
                bindings.params(), 0L, AdvancedLightingBindingAbi.PARAMS_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                bindings.lights(), 0L, AdvancedLightingBindingAbi.LIGHTS_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                bindings.headers(), 0L, AdvancedLightingBindingAbi.CLUSTER_HEADERS_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                bindings.indices(), 0L, AdvancedLightingBindingAbi.CLUSTER_INDICES_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        SunShadowGpuResources shadows = this.sunShadowResources;
        LocalVoxelShadowGpuResources localShadows = this.localVoxelShadowResources;
        if (shadows == null || localShadows == null) {
            throw new IllegalStateException("L4/L6 shadow bindings are unavailable");
        }
        int inFlightSlot = (int) (this.commandEncoder.currentSubmitIndex()
                % FrameStatePacketRing.SLOT_COUNT);
        shadows.bind(encoder, inFlightSlot);
        localShadows.bind(
                encoder,
                inFlightSlot,
                this.commandEncoder.currentSubmitIndex()
        );
    }

    void setMaterialWorldPassActive(final boolean active) {
        this.materialWorldPassActive = active;
    }

    int capturedFrameSourceEncoding(final MetalGpuTexture source) {
        return capturedFrameSourceEncoding(
                this.hdrWorldSceneAvailable
                        && this.hdrSceneAvailable
                        && this.hdrSceneSubmitIndex == this.commandEncoder.currentSubmitIndex(),
                HdrSceneState.sourceEncoding(),
                source.getFormat() == GpuFormat.RGBA16_FLOAT
        );
    }

    static int capturedFrameSourceEncoding(
            final boolean worldSceneRendered,
            final HdrSourceEncoding materialEncoding,
            final boolean fp16Source
    ) {
        return (worldSceneRendered ? materialEncoding : HdrSourceEncoding.SRGB)
                .nativeValue(fp16Source);
    }

    boolean isMaterialHdrGenerationActive() {
        RendererGenerationConfig generation = this.activeRendererGeneration;
        return generation != null
                && generation.renderContractMode() == RenderContractMode.METALLUM
                && generation.outputMode() == DisplayOutputMode.HDR;
    }

    boolean isLegacyHdrSemanticGenerationActive() {
        RendererGenerationConfig generation = this.activeRendererGeneration;
        return generation != null
                && usesLegacyHdrSemanticAttachment(
                        generation.renderContractMode(), generation.outputMode()
                );
    }

    boolean isLegacyHdrSceneLinearGenerationActive() {
        return this.isLegacyHdrSemanticGenerationActive()
                && SceneLinearPreflightGate.isActive();
    }

    synchronized long rendererGenerationIdForPresentation() {
        return this.rendererGenerationId;
    }

    static boolean usesLegacyHdrSemanticAttachment(
            final RenderContractMode renderContractMode,
            final DisplayOutputMode outputMode
    ) {
        return renderContractMode == RenderContractMode.LEGACY
                && outputMode == DisplayOutputMode.HDR;
    }

    static boolean usesLegacyHdrDepthSnapshot(
            final RenderContractMode renderContractMode,
            final boolean legacyHdrEnhancementActive
    ) {
        return renderContractMode == RenderContractMode.LEGACY && legacyHdrEnhancementActive;
    }

    private boolean isSceneRoutingActive() {
        return shouldRouteScene(
                this.hdrEnhancedActive,
                this.isMaterialGenerationActive(),
                this.hdrWorldSceneAvailable,
                this.hdrOutputMode != HdrOutputMode.SDR
        );
    }

    static boolean shouldRouteScene(
            final boolean hdrEnhancedActive,
            final boolean materialGenerationActive,
            final boolean worldSceneAvailable,
            final boolean hdrOutputActive
    ) {
        return hdrEnhancedActive
                || (materialGenerationActive && (worldSceneAvailable || hdrOutputActive));
    }

    private void releaseLegacyHdrGenerationResources() {
        this.hdrSceneAvailable = false;
        this.resetHdrSceneColor();
        this.hdrUiHandle = MemorySegment.NULL;
        this.hdrUiSubmitIndex = Long.MIN_VALUE;
        if (this.hdrSceneSnapshot != null) {
            this.hdrSceneSnapshot.close();
            this.hdrSceneSnapshot = null;
        }
        if (this.hdrSceneDepthSnapshot != null) {
            this.hdrSceneDepthSnapshot.close();
            this.hdrSceneDepthSnapshot = null;
        }
        this.hdrSceneDepthHandle = MemorySegment.NULL;
        if (this.hdrSemanticMask != null) {
            this.hdrSemanticMask.close();
            this.hdrSemanticMask = null;
        }
        this.hdrSemanticMaskClearedSubmitIndex = Long.MIN_VALUE;
        this.hdrSemanticMaskTouchedSubmitIndex = Long.MIN_VALUE;
        this.hdrSemanticSceneAvailable = false;
    }

    SemanticAttachment prepareHdrSemanticAttachment(final MetalGpuTexture source) {
        if (!this.isLegacyHdrSemanticGenerationActive()) {
            throw new IllegalStateException(
                    "Semantic HDR attachment is only valid for a resolved Legacy HDR generation"
            );
        }
        int width = source.getWidth(0);
        int height = source.getHeight(0);
        if (this.hdrSemanticMask == null
                || this.hdrSemanticMask.isClosed()
                || this.hdrSemanticMask.getWidth(0) != width
                || this.hdrSemanticMask.getHeight(0) != height) {
            MetalGpuTexture replacement = new MetalGpuTexture(
                    this,
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    "Metallum HDR semantic mask",
                    GpuFormat.RGBA8_UNORM,
                    width,
                    height,
                    1,
                    1
            );
            MetalGpuTexture previous = this.hdrSemanticMask;
            this.hdrSemanticMask = replacement;
            this.hdrSemanticMaskClearedSubmitIndex = Long.MIN_VALUE;
            this.hdrSemanticMaskTouchedSubmitIndex = Long.MIN_VALUE;
            if (previous != null) {
                previous.close();
            }
        }

        long submitIndex = this.commandEncoder.currentSubmitIndex();
        boolean clear = this.hdrSemanticMaskClearedSubmitIndex != submitIndex;
        this.hdrSemanticMaskClearedSubmitIndex = submitIndex;
        this.hdrSemanticMaskTouchedSubmitIndex = submitIndex;
        return new SemanticAttachment(this.hdrSemanticMask.nativeHandle(), clear);
    }

    void captureHdrScene(
            final MetalGpuTexture source,
            @Nullable final MetalGpuTexture depth,
            final boolean worldSceneRendered
    ) {
        this.spatialSceneAvailable = MetalFxSpatialScaling.isActive() && !source.isClosed();
        this.spatialSceneSubmitIndex = this.spatialSceneAvailable
                ? this.commandEncoder.currentSubmitIndex()
                : Long.MIN_VALUE;
        this.hdrSceneAvailable = false;
        this.hdrSemanticSceneAvailable = false;
        this.resetHdrSceneColor();
        boolean materialScene = this.isMaterialGenerationActive();
        RendererGenerationConfig generation = this.activeRendererGeneration;
        boolean legacyHdrScene = generation != null && usesLegacyHdrDepthSnapshot(
                generation.renderContractMode(), this.hdrEnhancedActive
        );
        if ((!materialScene && !legacyHdrScene) || source.isClosed()) {
            return;
        }
        this.hdrWorldSceneAvailable = worldSceneRendered;

        int width = source.getWidth(0);
        int height = source.getHeight(0);
        if (legacyHdrScene
                && (depth == null
                || depth.isClosed()
                || depth.getWidth(0) != width
                || depth.getHeight(0) != height)) {
            return;
        }
        boolean directSpatialScene = MetalFxSpatialScaling.isActive();
        if (legacyHdrScene) {
            if (this.hdrSceneDepthSnapshot == null
                    || this.hdrSceneDepthSnapshot.isClosed()
                    || this.hdrSceneDepthSnapshot.getWidth(0) != width
                    || this.hdrSceneDepthSnapshot.getHeight(0) != height
                    || this.hdrSceneDepthSnapshot.getFormat() != depth.getFormat()) {
                if (this.hdrSceneDepthSnapshot != null) {
                    this.hdrSceneDepthSnapshot.close();
                }
                try {
                    this.hdrSceneDepthSnapshot = new MetalGpuTexture(
                            this,
                            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                            "Metallum HDR scene depth snapshot",
                            depth.getFormat(),
                            width,
                            height,
                            1,
                            1
                    );
                } catch (RuntimeException exception) {
                    this.disableHdrEnhancement();
                    Metallum.LOGGER.error(
                            "Failed to allocate the HDR scene depth snapshot; continuing with EDR output",
                            exception
                    );
                    return;
                }
            }
        }

        if (directSpatialScene) {
            if (this.hdrSceneSnapshot != null) {
                this.hdrSceneSnapshot.close();
                this.hdrSceneSnapshot = null;
            }
            this.hdrDirectSceneSource = source;
            this.hdrSceneColorHandle = source.nativeHandle();
            this.hdrSceneColorState = HdrSceneColorState.DIRECT_SAFE;
            this.hdrDirectSceneRequiresSpatialScaling = true;
        } else {
            // Keep the live scene untouched while GUI redirection is being
            // prepared. The common native-HDR path samples it directly; a
            // snapshot is created lazily only if GUI must fall back to the
            // MainTarget and would otherwise overwrite the scene.
            this.hdrDirectSceneSource = source;
            this.hdrSceneColorState = HdrSceneColorState.PENDING_REDIRECT;
        }
        if (legacyHdrScene) {
            this.commandEncoder.copyTextureToTexture(
                    depth, this.hdrSceneDepthSnapshot, 0, 0, 0, 0, 0, width, height
            );
        }
        this.hdrSceneAvailable = true;
        this.hdrSceneWidth = width;
        this.hdrSceneHeight = height;
        this.hdrSceneSubmitIndex = this.commandEncoder.currentSubmitIndex();
        this.hdrUiHandle = MemorySegment.NULL;
        this.hdrUiSubmitIndex = Long.MIN_VALUE;
        this.hdrUiSuppressSceneEnhancement = false;
        this.hdrSceneDepthHandle = legacyHdrScene
                ? this.hdrSceneDepthSnapshot.nativeHandle()
                : MemorySegment.NULL;
        this.hdrSemanticSceneAvailable = legacyHdrScene
                && this.hdrSemanticMask != null
                && !this.hdrSemanticMask.isClosed()
                && this.hdrSemanticMask.getWidth(0) == width
                && this.hdrSemanticMask.getHeight(0) == height
                && this.hdrSemanticMaskTouchedSubmitIndex == this.hdrSceneSubmitIndex;
    }

    boolean materializeHdrSceneFallback(final MetalGpuTexture source) {
        if (this.hdrSceneColorState != HdrSceneColorState.PENDING_REDIRECT) {
            return true;
        }
        long submitIndex = this.commandEncoder.currentSubmitIndex();
        if (!this.isSceneRoutingActive()
                || !this.hdrSceneAvailable
                || this.hdrSceneSubmitIndex != submitIndex
                || this.hdrDirectSceneSource != source
                || source.isClosed()
                || source.getWidth(0) != this.hdrSceneWidth
                || source.getHeight(0) != this.hdrSceneHeight) {
            this.hdrSceneAvailable = false;
            this.hdrSemanticSceneAvailable = false;
            this.resetHdrSceneColor();
            return false;
        }

        int width = source.getWidth(0);
        int height = source.getHeight(0);
        try {
            if (this.hdrSceneSnapshot == null
                    || this.hdrSceneSnapshot.isClosed()
                    || this.hdrSceneSnapshot.getWidth(0) != width
                    || this.hdrSceneSnapshot.getHeight(0) != height
                    || this.hdrSceneSnapshot.getFormat() != source.getFormat()) {
                if (this.hdrSceneSnapshot != null) {
                    this.hdrSceneSnapshot.close();
                }
                this.hdrSceneSnapshot = new MetalGpuTexture(
                        this,
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        "Metallum HDR scene snapshot",
                        source.getFormat(),
                        width,
                        height,
                        1,
                        1
                );
                Metallum.LOGGER.info("Pre-GUI fallback scene capture format: {}", source.getFormat());
            }
            this.commandEncoder.copyTextureToTexture(
                    source,
                    this.hdrSceneSnapshot,
                    0,
                    0,
                    0,
                    0,
                    0,
                    width,
                    height
            );
            this.hdrDirectSceneSource = null;
            this.hdrSceneColorHandle = this.hdrSceneSnapshot.nativeHandle();
            this.hdrSceneColorState = HdrSceneColorState.SNAPSHOT;
            return true;
        } catch (RuntimeException exception) {
            this.disableHdrEnhancement();
            Metallum.LOGGER.error(
                    "Failed to preserve the HDR scene before GUI fallback; continuing with EDR output",
                    exception
            );
            return false;
        }
    }

    boolean confirmHdrUiRedirect(final MetalGpuTexture source) {
        long submitIndex = this.commandEncoder.currentSubmitIndex();
        if (this.hdrSceneColorState == HdrSceneColorState.NONE) {
            return MetalFxSpatialScaling.isActive()
                    && this.spatialSceneAvailable
                    && this.spatialSceneSubmitIndex == submitIndex
                    && !source.isClosed();
        }
        if (!this.isSceneRoutingActive()
                || !this.hdrSceneAvailable
                || this.hdrSceneSubmitIndex != submitIndex
                || this.hdrDirectSceneSource != source
                || source.isClosed()) {
            return false;
        }
        if (this.hdrSceneColorState == HdrSceneColorState.PENDING_REDIRECT) {
            this.hdrSceneColorHandle = source.nativeHandle();
            this.hdrSceneColorState = HdrSceneColorState.DIRECT_SAFE;
        }
        return this.hdrSceneColorState == HdrSceneColorState.DIRECT_SAFE;
    }

    void captureHdrUi(final MetalGpuTexture ui, final boolean suppressSceneEnhancement) {
        boolean spatialUi = MetalFxSpatialScaling.isActive()
                && this.spatialSceneAvailable
                && this.spatialSceneSubmitIndex == this.commandEncoder.currentSubmitIndex();
        boolean hdrUi = this.isSceneRoutingActive()
                && this.hdrSceneAvailable
                && ui.getWidth(0) == this.hdrSceneWidth
                && ui.getHeight(0) == this.hdrSceneHeight;
        if (ui.isClosed() || ui.getFormat() != GpuFormat.RGBA8_UNORM || (!spatialUi && !hdrUi)) {
            return;
        }
        this.commandEncoder.prepareTextureForRead(ui);
        this.hdrUiHandle = ui.nativeHandle();
        this.hdrUiSubmitIndex = this.commandEncoder.currentSubmitIndex();
        this.hdrUiSuppressSceneEnhancement = suppressSceneEnhancement;
    }

    boolean prepareHdrUiBackdrop(
            final MetalGpuTexture source,
            final MetalGpuTexture destination,
            final boolean hdrPrecomposeAllowed
    ) {
        boolean spatial = MetalFxSpatialScaling.isActive();
        boolean compatible = this.isHdrSceneReadyForUi(source)
                && !destination.isClosed()
                && source != destination
                && destination.getFormat() == GpuFormat.RGBA8_UNORM
                && (spatial
                        ? source.getWidth(0) <= destination.getWidth(0)
                            && source.getHeight(0) <= destination.getHeight(0)
                        : source.getWidth(0) == destination.getWidth(0)
                            && source.getHeight(0) == destination.getHeight(0));
        if (!compatible) {
            return false;
        }
        long submitIndex = this.commandEncoder.currentSubmitIndex();
        boolean materialHdr = this.isMaterialHdrGenerationActive();
        boolean precomposeHdr = hdrPrecomposeAllowed
                && (materialHdr || this.hdrEnhancedActive)
                && this.hdrWorldSceneAvailable
                && this.hdrSceneAvailable
                && this.hdrSceneSubmitIndex == submitIndex
                && (this.hdrDirectSceneSource == null || this.hdrDirectSceneSource == source)
                && !this.hdrConfig.diagnosticPattern()
                && (materialHdr || !MetalNativeBridge.isNullHandle(this.hdrSceneDepthHandle));
        boolean directPerceptual = spatial
                && this.hdrOutputMode == HdrOutputMode.SDR
                && !precomposeHdr;
        MemorySegment semanticHandle = precomposeHdr
                && !materialHdr
                && this.hdrSemanticSceneAvailable
                && this.hdrSemanticMask != null
                ? this.hdrSemanticMask.nativeHandle()
                : MemorySegment.NULL;
        int result = this.commandEncoder.encodeHdrUiBackdrop(
                source,
                destination,
                this.capturedFrameSourceEncoding(source),
                precomposeHdr ? this.hdrSceneDepthHandle : MemorySegment.NULL,
                semanticHandle,
                precomposeHdr,
                directPerceptual,
                this.hdrCurrentHeadroom,
                this.hdrConfig
        );
        this.spatialHdrPrecomposedSubmitIndex = result == 2 || result == 4
                ? submitIndex
                : Long.MIN_VALUE;
        if (result == 2 && !this.spatialHdrPrecomposeLogged) {
            this.spatialHdrPrecomposeLogged = true;
            Metallum.LOGGER.info(
                    "MetalFX HDR fast path is active: low-resolution HDR precompose, spatial scale, full-resolution UI composite"
            );
        }
        if (result == 3 && !this.spatialPerceptualDirectLogged) {
            this.spatialPerceptualDirectLogged = true;
            Metallum.LOGGER.info(
                    "MetalFX SDR fast path is active: low-resolution perceptual input, direct full-resolution GUI output"
            );
        }
        if (result == 4 && !this.nativeHdrPrecomposeLogged) {
            this.nativeHdrPrecomposeLogged = true;
            Metallum.LOGGER.info(
                    "Native-resolution HDR fast path is active: fused HDR reconstruction and SDR UI seed, lightweight final composite"
            );
        }
        return result > 0;
    }

    void materializeHdrUiBackdrop(final MetalGpuTexture ui) {
        if (!ui.isClosed()) {
            this.commandEncoder.prepareTextureForRead(ui);
        }
    }

    void resolvePendingUiSeedForTexture(final MetalGpuTexture texture) {
        this.commandEncoder.discardPendingUiSeedForTexture(texture);
    }

    boolean isSpatialHdrPrecomposedForCurrentSubmit() {
        return this.spatialHdrPrecomposedSubmitIndex == this.commandEncoder.currentSubmitIndex();
    }

    boolean blurHdrUiBackdrop(
            final MetalGpuTexture rawScene,
            final MetalGpuTexture ui,
            final float radius
    ) {
        return this.isSpatialHdrPrecomposedForCurrentSubmit()
                && !rawScene.isClosed()
                && !ui.isClosed()
                && rawScene.getFormat() == GpuFormat.RGBA16_FLOAT
                && ui.getFormat() == GpuFormat.RGBA8_UNORM
                && this.commandEncoder.encodeCoherentMenuBlur(
                        rawScene,
                        ui,
                        radius,
                        this.hdrCurrentHeadroom
                );
    }

    boolean prepareSpatialScreenshot(
            final MetalGpuTexture rawScene,
            final MetalGpuTexture ui,
            final MetalGpuTexture destination
    ) {
        return MetalFxSpatialScaling.isActive()
                && (this.hdrEnhancedActive || this.isMaterialHdrGenerationActive())
                && !rawScene.isClosed()
                && !ui.isClosed()
                && !destination.isClosed()
                && rawScene.getFormat() == GpuFormat.RGBA16_FLOAT
                && ui.getFormat() == GpuFormat.RGBA8_UNORM
                && destination.getFormat() == GpuFormat.RGBA8_UNORM
                && ui.getWidth(0) == destination.getWidth(0)
                && ui.getHeight(0) == destination.getHeight(0)
                && this.commandEncoder.encodeSpatialScreenshot(
                        rawScene,
                        ui,
                        destination,
                        this.hdrCurrentHeadroom
                );
    }

    boolean isHdrSceneReadyForUi(final MetalGpuTexture source) {
        boolean spatialReady = MetalFxSpatialScaling.isActive()
                && this.spatialSceneAvailable
                && this.spatialSceneSubmitIndex == this.commandEncoder.currentSubmitIndex()
                && !source.isClosed();
        boolean hdrReady = this.isSceneRoutingActive()
                && this.hdrSceneAvailable
                && this.hdrSceneSubmitIndex == this.commandEncoder.currentSubmitIndex()
                && this.hdrSceneColorState != HdrSceneColorState.NONE
                && (this.hdrDirectSceneSource == null
                    || (this.hdrDirectSceneSource == source && !this.hdrDirectSceneSource.isClosed()))
                && !source.isClosed()
                && source.getWidth(0) == this.hdrSceneWidth
                && source.getHeight(0) == this.hdrSceneHeight;
        return spatialReady || hdrReady;
    }

    HdrSceneInputs consumeHdrSceneInputs(final MetalGpuTexture presentedSource) {
        long submitIndex = this.commandEncoder.currentSubmitIndex();
        MemorySegment uiHandle = this.hdrUiSubmitIndex == submitIndex
                ? this.hdrUiHandle
                : MemorySegment.NULL;
        boolean directSourcePresent = this.hdrDirectSceneSource != null;
        boolean directRouteActive = !this.hdrDirectSceneRequiresSpatialScaling
                || MetalFxSpatialScaling.isActive();
        boolean sceneValid = this.isSceneRoutingActive()
                && this.hdrSceneAvailable
                && this.hdrSceneSubmitIndex == submitIndex
                && isHdrSceneColorConsumable(
                        this.hdrSceneColorState,
                        this.hdrWorldSceneAvailable,
                        !MetalNativeBridge.isNullHandle(this.hdrSceneColorHandle),
                        directSourcePresent,
                        this.hdrDirectSceneSource == presentedSource,
                        directSourcePresent && this.hdrDirectSceneSource.isClosed(),
                        directRouteActive
                )
                && !(this.hdrUiSubmitIndex == submitIndex && this.hdrUiSuppressSceneEnhancement);
        if (!sceneValid) {
            return MetalNativeBridge.isNullHandle(uiHandle)
                    ? HdrSceneInputs.NONE
                    : new HdrSceneInputs(
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            uiHandle,
                            false
                    );
        }
        boolean materialScene = this.isMaterialGenerationActive();
        MemorySegment semanticHandle = !materialScene
                && this.hdrSemanticSceneAvailable
                && this.hdrSemanticMask != null
                ? this.hdrSemanticMask.nativeHandle()
                : MemorySegment.NULL;
        return new HdrSceneInputs(
                this.hdrSceneColorHandle,
                materialScene ? MemorySegment.NULL : this.hdrSceneDepthHandle,
                semanticHandle,
                uiHandle,
                this.spatialHdrPrecomposedSubmitIndex == submitIndex
                        && !MetalNativeBridge.isNullHandle(uiHandle)
        );
    }

    private void resetHdrSceneColor() {
        this.hdrDirectSceneSource = null;
        this.hdrSceneColorHandle = MemorySegment.NULL;
        this.hdrSceneColorState = HdrSceneColorState.NONE;
        this.hdrDirectSceneRequiresSpatialScaling = false;
        this.hdrWorldSceneAvailable = false;
    }

    static boolean isHdrSceneColorConsumable(
            final HdrSceneColorState state,
            final boolean worldSceneAvailable,
            final boolean handlePresent,
            final boolean directSourcePresent,
            final boolean directSourceMatchesPresented,
            final boolean directSourceClosed,
            final boolean directRouteActive
    ) {
        if (!worldSceneAvailable || !handlePresent) {
            return false;
        }
        return switch (state) {
            case SNAPSHOT -> !directSourcePresent;
            case DIRECT_SAFE -> directSourcePresent
                    && directSourceMatchesPresented
                    && !directSourceClosed
                    && directRouteActive;
            case NONE, PENDING_REDIRECT -> false;
        };
    }

    void queueResourceRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(handle));
    }

    void queueResourceRelease(final MemorySegment handle, final Runnable afterRelease) {
        this.commandEncoder.queueForDestroy(() -> {
            try {
                MetalNativeBridge.metallum_release_object(handle);
            } finally {
                afterRelease.run();
            }
        });
    }

    void queueStaticGeometryBufferRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(
                () -> MetalNativeBridge.metallum_release_static_geometry_buffer(handle)
        );
    }

    void queueStaticGeometryBufferRelease(final MemorySegment handle, final Runnable afterRelease) {
        this.commandEncoder.queueForDestroy(() -> {
            try {
                MetalNativeBridge.metallum_release_static_geometry_buffer(handle);
            } finally {
                afterRelease.run();
            }
        });
    }

    MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
        return this.compiledPipelines.computeIfAbsent(pipeline, p -> MetalCrossShaderCompiler.compile(this, p, this.activeShaderSource));
    }

    IntermediaryShaderModule getOrCompileShader(
            final Identifier id,
            final ShaderType type,
            final ShaderDefines defines,
            final ShaderSource shaderSource,
            final HdrShaderFlavor flavor
    ) {
        ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines, flavor);
        return this.shaderCache.computeIfAbsent(key, k -> {
            String source = shaderSource.get(k.id(), k.type());
            if (source == null) {
                return IntermediaryShaderModule.INVALID;
            }
            String flavorSource = prepareFlavorSource(k, source);
            String sourceWithDefines = prepareShaderSource(flavorSource, k.defines());
            try (GlslCompiler glslCompiler = new GlslCompiler()) {
                return glslCompiler.createIntermediary(
                        k.id().toDebugFileName() + "_" + k.flavor().name().toLowerCase(Locale.ROOT),
                        sourceWithDefines,
                        k.type()
                );
            } catch (ShaderCompileException e) {
                throw new IllegalStateException("Failed to compile " + k.flavor() + " shader " + k.id(), e);
            }
        });
    }

    private static String prepareFlavorSource(final ShaderCompilationKey key, final String original) {
        if (key.flavor() == HdrShaderFlavor.SUN_SHADOW) {
            SunShadowShaderPatcher.Result shadow = SunShadowShaderPatcher.patch(
                    key.id().getNamespace(),
                    key.id().getPath(),
                    key.type() == ShaderType.VERTEX
                            ? MetallumMaterialShaderPatcher.Stage.VERTEX
                            : MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                    original
            );
            if (!shadow.success()) {
                throw new IllegalStateException(
                        "Failed to prepare L4 shadow shader " + key.id() + ": "
                                + shadow.failureReason()
                );
            }
            return shadow.source();
        }
        if (key.flavor() == HdrShaderFlavor.METALLUM
                || key.flavor() == HdrShaderFlavor.METALLUM_ADVANCED) {
            MetallumMaterialShaderPatcher.Result material = MetallumMaterialShaderPatcher.patch(
                    key.id().getNamespace(),
                    key.id().getPath(),
                    key.type() == ShaderType.VERTEX
                            ? MetallumMaterialShaderPatcher.Stage.VERTEX
                            : MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                    original
            );
            if (!material.success()) {
                throw new IllegalStateException(
                        "Failed to prepare METALLUM material shader " + key.id() + ": "
                                + material.failureReason()
                );
            }
            if (key.flavor() == HdrShaderFlavor.METALLUM) {
                return material.source();
            }
            AdvancedDirectLightingShaderPatcher.Result advanced =
                    AdvancedDirectLightingShaderPatcher.patch(
                            key.id().getNamespace(),
                            key.id().getPath(),
                            key.type() == ShaderType.VERTEX
                                    ? MetallumMaterialShaderPatcher.Stage.VERTEX
                                    : MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                            LightingModel.ADVANCED,
                            material.source()
                    );
            if (!advanced.success()) {
                throw new IllegalStateException(
                        "Failed to prepare METALLUM Advanced shader " + key.id() + ": "
                                + advanced.failureReason()
                );
            }
            return advanced.source();
        }

        String patched = original;
        boolean fragment = key.type() == ShaderType.FRAGMENT;
        if (HdrSceneState.isRequested()
                && fragment
                && key.id().getNamespace().equals("minecraft")
                && LightmapHdrShaderPatcher.isTarget(key.id().getPath())) {
            patched = LightmapHdrShaderPatcher.patchFragmentSource(patched);
            if (!LightmapHdrShaderPatcher.isPatched(patched)) {
                throw new IllegalStateException("Failed to prepare scene HDR lightmap shader " + key.id());
            }
        }
        boolean semanticFlavor = usesSemanticShaderFlavor(key.flavor());
        if (HdrSemanticState.isRequested() && semanticFlavor) {
            if (key.id().getNamespace().equals("sodium")
                    && key.id().getPath().equals("blocks/block_layer_opaque")) {
                patched = key.type() == ShaderType.VERTEX
                        ? SodiumHdrShaderPatcher.patchVertexSource(patched)
                        : SodiumHdrShaderPatcher.patchFragmentSource(patched);
                if (!SodiumHdrShaderPatcher.isPatched(patched)) {
                    throw new IllegalStateException("Failed to prepare semantic HDR shader " + key.id());
                }
            } else if (fragment
                    && key.id().getNamespace().equals("minecraft")
                    && VanillaHdrShaderPatcher.isTarget(key.id().getPath())) {
                patched = VanillaHdrShaderPatcher.patchFragmentSource(key.id().getPath(), patched);
                if (!VanillaHdrShaderPatcher.isPatched(patched)) {
                    throw new IllegalStateException("Failed to prepare semantic HDR shader " + key.id());
                }
            }
        }

        SceneLinearShaderPatcher.Result scene = SceneLinearShaderPatcher.patch(
                key.id().getNamespace(),
                key.id().getPath(),
                key.type() == ShaderType.VERTEX
                        ? SceneLinearShaderPatcher.Stage.VERTEX
                        : SceneLinearShaderPatcher.Stage.FRAGMENT,
                key.flavor(),
                patched
        );
        if (!scene.success()) {
            throw new IllegalStateException(
                    "Failed to prepare " + key.flavor() + " shader " + key.id() + ": "
                            + scene.failureReason()
            );
        }
        return scene.source();
    }

    static boolean usesSemanticShaderFlavor(final HdrShaderFlavor flavor) {
        return flavor == HdrShaderFlavor.LEGACY_HDR_SEMANTIC
                || flavor == HdrShaderFlavor.SCENE_RASTER_LINEAR
                || flavor == HdrShaderFlavor.SCENE_POST_LINEAR;
    }

    static int advancedLightingAdmissionLimit(final LightingPreset preset) {
        Objects.requireNonNull(preset, "preset");
        return AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS;
    }

    static VoxelClipmapLayout.Preset voxelPreset(final LightingPreset preset) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case PERFORMANCE -> VoxelClipmapLayout.Preset.PERFORMANCE;
            case BALANCED -> VoxelClipmapLayout.Preset.BALANCED;
            case ULTRA -> VoxelClipmapLayout.Preset.ULTRA;
        };
    }

    private boolean refreshVoxelOccupancyResources(
            final RendererGenerationConfig generation
    ) {
        if (isVoxelRetrySuppressed(
                this.voxelRetrySuppressedRendererGeneration,
                this.voxelRetrySuppressedLightingGeneration,
                this.rendererGenerationId,
                this.lightingGenerationId
        )) {
            return false;
        }
        VoxelClipmapLayout.Budget budget = VoxelClipmapLayout.forPreset(
                voxelPreset(generation.lightingPreset())
        );
        VoxelClipmapSnapshot snapshot = VoxelClipmapController.global().snapshot();
        VoxelOccupancyGpuResources current = this.voxelOccupancyResources;
        if (current != null && (snapshot == null
                ? current.matchesGenerationAndBudget(this.lightingGenerationId, budget)
                : current.matches(this.lightingGenerationId, budget, snapshot))) {
            return true;
        }
        try {
            VoxelOccupancyGpuResources replacement = VoxelOccupancyGpuResources.create(
                    this.metalDeviceHandle,
                    this.lightingGenerationId,
                    budget,
                    snapshot
            );
            this.voxelOccupancyResources = replacement;
            this.observedVoxelNativeRejections = 0L;
            this.voxelFailureLogged = false;
            this.resetVoxelDebugChecksumState();
            this.voxelRetrySuppressedRendererGeneration = Long.MIN_VALUE;
            this.voxelRetrySuppressedLightingGeneration = Long.MIN_VALUE;
            if (current != null) {
                current.close();
            }
            return true;
        } catch (RuntimeException exception) {
            this.suppressVoxelRetryForGeneration(
                    this.rendererGenerationId,
                    this.lightingGenerationId,
                    "L5 voxel context refresh failed; retaining the established L3/L4 generation",
                    exception
            );
            if (current != null && current == this.voxelOccupancyResources) {
                this.voxelOccupancyResources = null;
                current.close();
            }
            return false;
        }
    }

    /** L5 has no consumer before L6, so its failure must not revoke an established L3/L4 frame. */
    static boolean retainsL3L4AfterVoxelFailure(
            final boolean lightingResourcesReady,
            final boolean shadowResourcesReady
    ) {
        return lightingResourcesReady && shadowResourcesReady;
    }

    static boolean shouldScheduleVoxelDebugChecksum(
            final boolean configured,
            final LightingModel lightingModel,
            final boolean voxelResourcesReady,
            final boolean advancedFrameReady,
            final boolean voxelUploadAttemptedThisFrame,
            final boolean runtimeDisabled,
            final long submitIndex,
            final long lastChecksumSubmitIndex
    ) {
        if (submitIndex < 0L) {
            throw new IllegalArgumentException("submitIndex must be non-negative");
        }
        return configured
                && lightingModel == LightingModel.ADVANCED
                && voxelResourcesReady
                && advancedFrameReady
                && !voxelUploadAttemptedThisFrame
                && !runtimeDisabled
                && (lastChecksumSubmitIndex < 0L
                || submitIndex - lastChecksumSubmitIndex >= VOXEL_DEBUG_CHECKSUM_CADENCE_FRAMES);
    }

    private void detectAsyncVoxelFailure() {
        VoxelOccupancyGpuResources current = this.voxelOccupancyResources;
        if (current == null) {
            return;
        }
        try {
            VoxelOccupancyGpuResources.CompletedStats stats = current.readLastCompletedStats();
            if (hasUnacknowledgedVoxelNativeRejection(
                    this.observedVoxelNativeRejections, stats.rejected())) {
                this.disableVoxelOccupancy("asynchronous L5 voxel command failed", null);
                return;
            }
            this.observedVoxelNativeRejections = stats.rejected();
        } catch (RuntimeException exception) {
            this.disableVoxelOccupancy("L5 voxel health query failed", exception);
        }
    }

    private void resetVoxelDebugChecksumState() {
        this.lastVoxelDebugChecksumSubmitIndex = Long.MIN_VALUE;
        this.voxelDebugChecksumRuntimeDisabled = false;
        this.voxelDebugChecksumFailureLogged = false;
    }

    static boolean hasUnacknowledgedVoxelNativeRejection(
            final long observedRejections,
            final long currentRejections
    ) {
        if (observedRejections < 0L || currentRejections < 0L) {
            throw new IllegalArgumentException("Native L5 rejection counters must be non-negative");
        }
        return currentRejections > observedRejections;
    }

    /** A failed L5 allocation gets one retry per renderer/lighting generation, never per frame. */
    static boolean isVoxelRetrySuppressed(
            final long suppressedRendererGeneration,
            final long suppressedLightingGeneration,
            final long rendererGeneration,
            final long lightingGeneration
    ) {
        return suppressedRendererGeneration == rendererGeneration
                && suppressedLightingGeneration == lightingGeneration;
    }

    private void acknowledgeVoxelNativeRejection(final VoxelOccupancyGpuResources resources) {
        try {
            this.observedVoxelNativeRejections = resources.readLastCompletedStats().rejected();
        } catch (RuntimeException exception) {
            this.disableVoxelOccupancy("L5 voxel rejection-baseline query failed", exception);
        }
    }

    private void disableVoxelOccupancy(final String reason, @Nullable final RuntimeException failure) {
        VoxelOccupancyGpuResources stale = this.voxelOccupancyResources;
        this.voxelOccupancyResources = null;
        this.observedVoxelNativeRejections = 0L;
        this.voxelTransientBusyLogged = false;
        VoxelClipmapController.global().recoverAfterGpuFailure();
        if (stale != null) {
            stale.close();
        }
        if (!this.voxelFailureLogged) {
            this.voxelFailureLogged = true;
            if (failure == null) {
                Metallum.LOGGER.warn("{}; disabling only L5 and retaining L3/L4", reason);
            } else {
                Metallum.LOGGER.warn("{}; disabling only L5 and retaining L3/L4", reason, failure);
            }
        }
    }

    private void suppressVoxelRetryForGeneration(
            final long rendererGeneration,
            final long lightingGeneration,
            final String reason,
            @Nullable final RuntimeException failure
    ) {
        this.voxelRetrySuppressedRendererGeneration = rendererGeneration;
        this.voxelRetrySuppressedLightingGeneration = lightingGeneration;
        if (!this.voxelFailureLogged) {
            this.voxelFailureLogged = true;
            if (failure == null) {
                Metallum.LOGGER.warn("{}; L5 retry is latched until the next generation", reason);
            } else {
                Metallum.LOGGER.warn(
                        "{}; L5 retry is latched until the next generation", reason, failure
                );
            }
        }
    }

    private void resetVoxelFailureForGeneration(
            final long rendererGeneration,
            final long lightingGeneration
    ) {
        if (!isVoxelRetrySuppressed(
                this.voxelRetrySuppressedRendererGeneration,
                this.voxelRetrySuppressedLightingGeneration,
                rendererGeneration,
                lightingGeneration
        )) {
            this.voxelRetrySuppressedRendererGeneration = Long.MIN_VALUE;
            this.voxelRetrySuppressedLightingGeneration = Long.MIN_VALUE;
            this.voxelFailureLogged = false;
        }
    }

    static FrameState.AdvancedLightingWork advancedLightingWork(final int lightCount) {
        return advancedLightingWork(lightCount, 0, false);
    }

    static FrameState.AdvancedLightingWork advancedLightingWork(
            final int lightCount,
            final int cascadeCount,
            final boolean shadowPass
    ) {
        if (lightCount < 0) {
            throw new IllegalArgumentException("Light count must be non-negative");
        }
        if (cascadeCount < 0 || cascadeCount > SunShadowLayout.MAX_CASCADES
                || (shadowPass && cascadeCount < 2)) {
            throw new IllegalArgumentException("Invalid per-frame cascade declaration");
        }
        long uploadBytes = Math.addExact(
                AdvancedLightingLayout.UPLOAD_HEADER_BYTES,
                Math.multiplyExact((long) lightCount, AdvancedLightingLayout.GPU_LIGHT_STRIDE)
        );
        if (cascadeCount > 0) {
            uploadBytes = Math.addExact(uploadBytes, SunShadowLayout.PARAMS_BYTES);
        }
        return new FrameState.AdvancedLightingWork(
                lightCount,
                (lightCount == 0
                        ? AdvancedLightingGpuResources.EMPTY_PRODUCTION_PASS_COUNT
                        : AdvancedLightingGpuResources.PRODUCTION_PASS_COUNT)
                        + (shadowPass ? SunShadowGpuResources.PRODUCTION_PASS_COUNT : 0),
                AdvancedLightingGpuResources.PRODUCTION_ENCODER_COUNT
                        + (shadowPass
                        ? cascadeCount * SunShadowGpuResources.MAX_ENCODERS_PER_CASCADE
                        : 0),
                AdvancedLightingGpuResources.RESIDENT_PSO_COUNT
                        + (cascadeCount > 0 ? SunShadowGpuResources.RESIDENT_PSO_COUNT : 0),
                AdvancedLightingGpuResources.PRODUCTION_WORK_QUEUE_COUNT,
                AdvancedLightingGpuResources.productionDispatchCount(lightCount),
                uploadBytes
        );
    }

    static FrameState.AdvancedLightingWork advancedLightingWork(
            final int lightCount,
            final int cascadeCount,
            final boolean shadowPass,
            final LocalVoxelShadowLayout.Budget localShadowBudget
    ) {
        FrameState.AdvancedLightingWork base = advancedLightingWork(
                lightCount, cascadeCount, shadowPass);
        return new FrameState.AdvancedLightingWork(
                base.lightCount(),
                base.passCount(),
                base.encoderCount(),
                base.psoCount(),
                Math.addExact(
                        base.workQueueCount(),
                        LocalVoxelShadowGpuResources.PRODUCTION_WORK_QUEUE_COUNT
                ),
                base.dispatchCount(),
                Math.addExact(
                        base.uploadBytes(),
                        LocalVoxelShadowGpuResources.frameUploadBytes(localShadowBudget)
                )
        );
    }

    static FrameState.AdvancedLightingWork withVoxelWork(
            final FrameState.AdvancedLightingWork base,
            final int patchCount,
            final long uploadBytes
    ) {
        Objects.requireNonNull(base, "base");
        if (patchCount <= 0 || uploadBytes <= 0L || base.isEmpty()) {
            throw new IllegalArgumentException("Invalid L5 per-frame work declaration");
        }
        return new FrameState.AdvancedLightingWork(
                base.lightCount(),
                Math.addExact(base.passCount(), VoxelOccupancyGpuResources.PRODUCTION_PASS_COUNT),
                Math.addExact(
                        base.encoderCount(),
                        VoxelOccupancyGpuResources.PRODUCTION_ENCODER_COUNT
                ),
                Math.addExact(base.psoCount(), VoxelOccupancyGpuResources.RESIDENT_PSO_COUNT),
                Math.addExact(
                        base.workQueueCount(), VoxelOccupancyGpuResources.PRODUCTION_WORK_QUEUE_COUNT
                ),
                Math.addExact(base.dispatchCount(), patchCount),
                Math.addExact(base.uploadBytes(), uploadBytes)
        );
    }

    private static FrameState.ResourceBytes resourceBytes(
            final RendererGenerationManifest manifest
    ) {
        return new FrameState.ResourceBytes(
                manifest.resourceBytes(RendererGenerationManifest.Domain.BASE),
                manifest.resourceBytes(RendererGenerationManifest.Domain.MATERIAL_ONLY),
                manifest.resourceBytes(RendererGenerationManifest.Domain.HDR_ONLY),
                manifest.resourceBytes(RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY),
                manifest.resourceBytes(RendererGenerationManifest.Domain.UPSCALE_ONLY),
                manifest.resourceBytes(RendererGenerationManifest.Domain.INTERPOLATION_ONLY),
                manifest.resourceBytes(RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY)
        );
    }

    private static String prepareShaderSource(final String source, final ShaderDefines defines) {
        String stripped = BLOCK_COMMENTS.matcher(source).replaceAll("");
        stripped = LINE_COMMENTS.matcher(stripped).replaceAll("").stripLeading();
        return GlslPreprocessor.injectDefines(stripped, defines);
    }

    MemorySegment getOrCompileFunction(final String msl, final String entryPoint) {
        return this.functionCache.computeIfAbsent(
                new MslFunctionKey(msl, entryPoint),
                key -> MetalNativeBridge.metallum_create_shader_function(this.metalDeviceHandle, key.msl(), key.entryPoint())
        );
    }

    private record ShaderCompilationKey(
            Identifier id,
            ShaderType type,
            ShaderDefines defines,
            HdrShaderFlavor flavor
    ) {
    }

    private record MslFunctionKey(String msl, String entryPoint) {
    }

    private DeviceInfo buildDeviceInfo(final String deviceName) {
        DeviceType type = DeviceType.INTEGRATED;
        Set<String> underlyingExtensions = Set.of("CAMetalLayer", "MTLDevice");
        String osVersion = System.getProperty("os.version", "").trim();
        String driverDescription = "macOS " + osVersion;
        long maxMemoryAllocationSize = MetalNativeBridge.MTLDevice_maxMemoryAllocationSize(metalDeviceHandle);
        return new DeviceInfo(
                deviceName,
                "Apple",
                driverDescription,
                true,
                "Metal",
                1.0F,
                new DeviceLimits(16, 256, 16384, maxMemoryAllocationSize, 0, 1),
                new DeviceFeatures(false, false, true, true, true, false, true),
                underlyingExtensions,
                new HintsAndWorkarounds(false, false),
                type
        );
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }
}
