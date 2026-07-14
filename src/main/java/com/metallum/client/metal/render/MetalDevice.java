package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.hdr.HdrConfig;
import com.metallum.client.hdr.HdrMode;
import com.metallum.client.hdr.HdrOutputMode;
import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.HdrSemanticState;
import com.metallum.client.hdr.HdrShaderFlavor;
import com.metallum.client.hdr.SceneLinearShaderPatcher;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.framegraph.NativeHdrFrameGraph;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
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
    enum HdrSceneColorState {
        NONE,
        PENDING_REDIRECT,
        SNAPSHOT,
        DIRECT_SAFE
    }

    record SemanticAttachment(MemorySegment texture, boolean clear) {
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

    private static final Pattern BLOCK_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENTS = Pattern.compile("(?m)//[^\\n]*");
    private static volatile MetalDevice INSTANCE;
    private final MemorySegment metalDeviceHandle;
    private final MemorySegment metalLayer;
    private final MemorySegment cocoaView;
    private final MemorySegment edrMonitor;
    private volatile HdrConfig hdrConfig;
    private final GpuDebugOptions debugOptions;
    private final boolean spatialScalingSupported;
    private final MetalCommandEncoder commandEncoder;
    private final DeviceInfo deviceInfo;
    public final MTLCommandQueue commandQueue;
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new IdentityHashMap<>();
    private final Map<ShaderCompilationKey, IntermediaryShaderModule> shaderCache = new HashMap<>();
    private final Map<MslFunctionKey, MemorySegment> functionCache = new HashMap<>();
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
    private long hdrSceneSubmitIndex = Long.MIN_VALUE;
    private MemorySegment hdrUiHandle = MemorySegment.NULL;
    private long hdrUiSubmitIndex = Long.MIN_VALUE;
    private boolean hdrUiSuppressSceneEnhancement;
    private boolean spatialSceneAvailable;
    private long spatialSceneSubmitIndex = Long.MIN_VALUE;
    private long spatialHdrPrecomposedSubmitIndex = Long.MIN_VALUE;
    private boolean spatialHdrPrecomposeLogged;
    private boolean nativeHdrPrecomposeLogged;
    private boolean spatialPerceptualDirectLogged;

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
        // These policies are process-global only because Minecraft constructs
        // one render backend. Clear stale state before any fallible native
        // initialization so another backend can never inherit Metal policy.
        HdrSemanticState.reset();
        HdrSceneState.reset();
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
        MetalNativeBridge.metallum_set_debug_labels_enabled(this.useLabels());
        this.spatialScalingSupported = MetalNativeBridge.MTLFXSpatialScaler_supportsDevice(metalDeviceHandle);
        this.commandQueue = MTLCommandQueue.create(metalDeviceHandle, metalLayer);
        MetalNativeBridge.metallum_init_pipelines(metalDeviceHandle);
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(deviceName);
        HdrSemanticState.configure(configuredHdrMode, initialEdrCapabilities);
        HdrSceneState.configure(this.hdrConfig, initialEdrCapabilities);
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
        return this.createTexture(this.resolveDebugLabel(label), usage, format, width, height, depthOrLayers, mipLevels);
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
        return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
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
        this.waitForSubmittedGpuWork();
        this.commandEncoder.close();
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

    public boolean supportsSpatialScaling() {
        return this.spatialScalingSupported;
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
        HdrOutputMode previousOutputMode = this.hdrOutputMode;
        this.hdrOutputMode = outputMode;
        this.hdrCurrentHeadroom = Float.isFinite(currentHeadroom)
                ? Math.clamp(currentHeadroom, 1.0f, HdrConfig.OUTPUT_HEADROOM)
                : 1.0f;
        this.hdrEnhancedActive = !this.hdrEnhancementUnavailable
                && outputMode == HdrOutputMode.ENHANCED
                && currentHeadroom > 1.001f
                && this.hdrConfig.hdrStrength() > 0.0f
                && !this.hdrConfig.diagnosticPattern();
        if (this.hdrEnhancedActive && !this.hdrEnhancementActivationLogged) {
            this.hdrEnhancementActivationLogged = true;
            Metallum.LOGGER.info("Semantic HDR enhancement is active with current EDR headroom {}", currentHeadroom);
        }
        if (!this.hdrEnhancedActive) {
            this.hdrSceneAvailable = false;
            this.hdrSemanticSceneAvailable = false;
            this.resetHdrSceneColor();
        }
        MetalFxSpatialScaling.onHdrOutputModeChanged(previousOutputMode, outputMode);
    }

    HdrOutputMode availableHdrOutputMode(final HdrOutputMode requestedMode) {
        return requestedMode == HdrOutputMode.ENHANCED && this.hdrEnhancementUnavailable
                ? HdrOutputMode.EDR
                : requestedMode;
    }

    void disableHdrEnhancement() {
        this.hdrEnhancementUnavailable = true;
        this.hdrEnhancedActive = false;
        this.hdrSceneAvailable = false;
        this.hdrSemanticSceneAvailable = false;
        this.resetHdrSceneColor();
        this.hdrUiSuppressSceneEnhancement = false;
    }

    SemanticAttachment prepareHdrSemanticAttachment(final MetalGpuTexture source) {
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

    void captureHdrScene(final MetalGpuTexture source, @Nullable final MetalGpuTexture depth) {
        this.spatialSceneAvailable = MetalFxSpatialScaling.isActive() && !source.isClosed();
        this.spatialSceneSubmitIndex = this.spatialSceneAvailable
                ? this.commandEncoder.currentSubmitIndex()
                : Long.MIN_VALUE;
        this.hdrSceneAvailable = false;
        this.hdrSemanticSceneAvailable = false;
        this.resetHdrSceneColor();
        if (!this.hdrEnhancedActive || source.isClosed() || depth == null || depth.isClosed()) {
            return;
        }

        int width = source.getWidth(0);
        int height = source.getHeight(0);
        if (depth.getWidth(0) != width || depth.getHeight(0) != height) {
            return;
        }
        boolean directSpatialScene = MetalFxSpatialScaling.isActive();
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
                Metallum.LOGGER.error("Failed to allocate the HDR scene depth snapshot; continuing with EDR output", exception);
                return;
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
        this.commandEncoder.copyTextureToTexture(depth, this.hdrSceneDepthSnapshot, 0, 0, 0, 0, 0, width, height);
        this.hdrSceneAvailable = true;
        this.hdrSceneWidth = width;
        this.hdrSceneHeight = height;
        this.hdrSceneSubmitIndex = this.commandEncoder.currentSubmitIndex();
        this.hdrUiHandle = MemorySegment.NULL;
        this.hdrUiSubmitIndex = Long.MIN_VALUE;
        this.hdrUiSuppressSceneEnhancement = false;
        this.hdrSceneDepthHandle = this.hdrSceneDepthSnapshot.nativeHandle();
        this.hdrSemanticSceneAvailable = this.hdrSemanticMask != null
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
        if (!this.hdrEnhancedActive
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
        if (!this.hdrEnhancedActive
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
        boolean hdrUi = this.hdrEnhancedActive
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
            final MetalGpuTexture destination
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
        boolean precomposeHdr = this.hdrEnhancedActive
                && this.hdrSceneAvailable
                && this.hdrSceneSubmitIndex == submitIndex
                && (this.hdrDirectSceneSource == null || this.hdrDirectSceneSource == source)
                && !this.hdrConfig.diagnosticPattern()
                && !MetalNativeBridge.isNullHandle(this.hdrSceneDepthHandle);
        boolean directPerceptual = spatial
                && this.hdrOutputMode == HdrOutputMode.SDR
                && !precomposeHdr;
        MemorySegment semanticHandle = precomposeHdr
                && this.hdrSemanticSceneAvailable
                && this.hdrSemanticMask != null
                ? this.hdrSemanticMask.nativeHandle()
                : MemorySegment.NULL;
        int result = this.commandEncoder.encodeHdrUiBackdrop(
                source,
                destination,
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

    boolean prepareSpatialScreenshot(
            final MetalGpuTexture rawScene,
            final MetalGpuTexture ui,
            final MetalGpuTexture destination
    ) {
        return MetalFxSpatialScaling.isActive()
                && this.hdrEnhancedActive
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
        boolean hdrReady = this.hdrEnhancedActive
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
        boolean sceneValid = this.hdrEnhancedActive
                && this.hdrSceneAvailable
                && this.hdrSceneSubmitIndex == submitIndex
                && isHdrSceneColorConsumable(
                        this.hdrSceneColorState,
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
        MemorySegment semanticHandle = this.hdrSemanticSceneAvailable
                && this.hdrSemanticMask != null
                ? this.hdrSemanticMask.nativeHandle()
                : MemorySegment.NULL;
        return new HdrSceneInputs(
                this.hdrSceneColorHandle,
                this.hdrSceneDepthHandle,
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
    }

    static boolean isHdrSceneColorConsumable(
            final HdrSceneColorState state,
            final boolean handlePresent,
            final boolean directSourcePresent,
            final boolean directSourceMatchesPresented,
            final boolean directSourceClosed,
            final boolean directRouteActive
    ) {
        if (!handlePresent) {
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

    void queueStaticGeometryBufferRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(
                () -> MetalNativeBridge.metallum_release_static_geometry_buffer(handle)
        );
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
            // Phase A intentionally gives the optional scene flavors GLSL
            // identical to LEGACY. Distinct keys let later raster and post
            // patches evolve without mutating RGBA8 GUI shaders.
            SceneLinearShaderPatcher.Result patch = SceneLinearShaderPatcher.patch(
                    k.id().getNamespace(),
                    k.id().getPath(),
                    k.type() == ShaderType.VERTEX
                            ? SceneLinearShaderPatcher.Stage.VERTEX
                            : SceneLinearShaderPatcher.Stage.FRAGMENT,
                    k.flavor(),
                    source
            );
            if (!patch.success()) {
                throw new IllegalStateException(
                        "Failed to prepare " + k.flavor() + " shader " + k.id() + ": " + patch.failureReason()
                );
            }
            String sourceWithDefines = prepareShaderSource(patch.source(), k.defines());
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
