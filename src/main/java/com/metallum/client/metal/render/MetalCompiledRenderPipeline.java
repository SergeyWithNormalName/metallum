package com.metallum.client.metal.render;

import com.metallum.client.hdr.HdrPipelinePolicy;
import com.metallum.client.hdr.HdrShaderFlavor;
import com.metallum.client.hdr.MetallumMaterialPreflightGate;
import com.metallum.client.hdr.SceneLinearPreflightGate;
import com.metallum.client.lighting.shader.AdvancedLightingPreflightGate;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    record ShaderVariantSource(
            String vertexMsl,
            String fragmentMsl,
            String vertexEntryPoint,
            String fragmentEntryPoint,
            List<ResourceBinding> resources,
            boolean semanticOutput
    ) {
    }

    private record ShaderFunctions(
            MemorySegment vertex,
            MemorySegment fragment,
            boolean semanticOutput
    ) {
        boolean isValid() {
            return !MetalNativeBridge.isNullHandle(this.vertex)
                    && !MetalNativeBridge.isNullHandle(this.fragment);
        }
    }

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final ResourceBinding[] resourcesByBindingIndex;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final boolean usesSodiumLightSidecar;
    private final int sodiumLightSidecarBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;
    private final HdrPipelinePolicy.Role hdrRole;
    private final Map<HdrShaderFlavor, ShaderFunctions> shaderFunctions;

    private static final java.util.concurrent.atomic.AtomicInteger compilationCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final Set<String> UNKNOWN_FP16_PIPELINES_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Identifier MOJANG_LOGO_PIPELINE = Identifier.withDefaultNamespace("pipeline/mojang_logo");

    private final MemorySegment depthStencilState;
    private final MetalDevice device;
    private final RenderPipeline info;
    private final boolean isValid;
    private boolean closed = false;
    private long legacyColorFormatsLogged;
    private long legacyHdrSemanticColorFormatsLogged;
    private long sceneRasterColorFormatsLogged;
    private long scenePostColorFormatsLogged;
    private long materialColorFormatsLogged;
    private long advancedMaterialColorFormatsLogged;
    private long reactiveMaterialColorFormatsLogged;
    private long sunShadowColorFormatsLogged;

    private final Map<PipelineKey, OwnedPipelineHandle> pipelines = new HashMap<>();

    private record PipelineKey(
            HdrShaderFlavor flavor,
            MTLPixelFormat colorFormat,
            MTLPixelFormat depthFormat,
            MTLPixelFormat stencilFormat
    ) {
    }

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final HdrPipelinePolicy.Role hdrRole,
            final Map<HdrShaderFlavor, ShaderVariantSource> variants
    ) {
        this.device = device;
        this.info = info;
        this.hdrRole = hdrRole;

        ShaderVariantSource legacy = variants.get(HdrShaderFlavor.LEGACY);
        if (legacy == null) {
            throw new IllegalArgumentException("Pipeline is missing its legacy shader flavor: " + info.getLocation());
        }
        if (SceneLinearPreflightGate.shouldCompileSceneVariants()
                && hdrRole.supportsSceneLinearFlavor()
                && !variants.containsKey(hdrRole.sceneLinearFlavor())) {
            throw new IllegalArgumentException("Pipeline is missing its scene-linear shader flavor: " + info.getLocation());
        }
        if (MetallumMaterialPreflightGate.shouldCompileMaterialVariants()
                && hdrRole.supportsSceneLinearFlavor()
                && !variants.containsKey(HdrShaderFlavor.METALLUM)) {
            throw new IllegalArgumentException("Pipeline is missing its METALLUM material flavor: " + info.getLocation());
        }

        this.resources = legacy.resources();
        this.resourcesByName = this.resources.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding)
        );

        long resourceMask = 0L;
        for (ResourceBinding binding : this.resources) {
            if (binding.bindingIndex() < 0 || binding.bindingIndex() >= Long.SIZE) {
                throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index "
                        + binding.bindingIndex() + ", limit is " + (Long.SIZE - 1));
            }
            resourceMask |= 1L << binding.bindingIndex();
        }
        this.resourcesByBindingIndex = new ResourceBinding[Long.SIZE];
        for (ResourceBinding binding : this.resources) {
            if (this.resourcesByBindingIndex[binding.bindingIndex()] != null) {
                throw new IllegalStateException("Pipeline " + info.getLocation()
                        + " repeats binding index " + binding.bindingIndex());
            }
            this.resourcesByBindingIndex[binding.bindingIndex()] = binding;
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(this.resources);
        this.usesSodiumLightSidecar = SodiumLightSidecarMslPatcher.isPatched(legacy.vertexMsl());
        this.sodiumLightSidecarBufferSlot = this.usesSodiumLightSidecar
                ? Math.addExact(this.firstAvailableVertexBufferSlot, MetalRenderPass.MAX_VERTEX_BUFFERS)
                : -1;
        validateSodiumLightSidecarVariants(info, variants, this.usesSodiumLightSidecar,
                this.sodiumLightSidecarBufferSlot);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;
        Map<HdrShaderFlavor, ShaderFunctions> compiledFunctions = new java.util.EnumMap<>(HdrShaderFlavor.class);
        boolean allFunctionsValid = true;
        boolean sidecarSceneVariantsValid = true;
        for (Map.Entry<HdrShaderFlavor, ShaderVariantSource> entry : variants.entrySet()) {
            ShaderVariantSource variant = entry.getValue();
            ShaderFunctions functions = new ShaderFunctions(
                    device.getOrCompileFunction(variant.vertexMsl(), variant.vertexEntryPoint()),
                    device.getOrCompileFunction(variant.fragmentMsl(), variant.fragmentEntryPoint()),
                    variant.semanticOutput()
            );
            if (entry.getKey() != HdrShaderFlavor.LEGACY && !functions.isValid()) {
                if (entry.getKey() == HdrShaderFlavor.METALLUM) {
                    MetallumMaterialPreflightGate.rejectMaterialVariant(
                            "Metal rejected METALLUM functions for " + info.getLocation()
                    );
                } else if (entry.getKey() == HdrShaderFlavor.METALLUM_ADVANCED
                        || entry.getKey() == HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE) {
                    AdvancedLightingPreflightGate.rejectAdvancedVariant(
                            "Metal rejected " + entry.getKey() + " functions for " + info.getLocation()
                    );
                } else if (entry.getKey() == HdrShaderFlavor.SUN_SHADOW) {
                    AdvancedLightingPreflightGate.rejectAdvancedVariant(
                            "Metal rejected L4 shadow functions for " + info.getLocation()
                    );
                } else if (entry.getKey() == HdrShaderFlavor.LEGACY_HDR_SEMANTIC) {
                    com.metallum.Metallum.LOGGER.warn(
                            "Metal rejected Legacy HDR semantic functions for {}; output remains on the base Legacy shader",
                            info.getLocation()
                    );
                } else if (this.usesSodiumLightSidecar) {
                    sidecarSceneVariantsValid = false;
                } else {
                    SceneLinearPreflightGate.rejectSceneVariant(
                            "Metal rejected " + entry.getKey() + " functions for " + info.getLocation()
                    );
                }
                continue;
            }
            compiledFunctions.put(entry.getKey(), functions);
            allFunctionsValid &= functions.isValid();
        }
        this.shaderFunctions = Map.copyOf(compiledFunctions);

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = MetalNativeBridge.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(),
                depthCompareOp,
                depthWrite
        );

        var colorTarget = info.getColorTargetState();
        MTLPixelFormat colorFormat = colorTarget != null ? MTLPixelFormat.from(colorTarget.format()) : MTLPixelFormat.RGBA8Unorm;
        HdrShaderFlavor initialFlavor = selectFlavor(colorFormat, false);
        ShaderFunctions initialFunctions = this.shaderFunctions.get(initialFlavor);

        MemorySegment withoutDepth = MemorySegment.NULL;
        MemorySegment withDepth = MemorySegment.NULL;
        boolean sidecarScenePipelinesValid = true;
        if (allFunctionsValid && initialFunctions != null && initialFunctions.isValid()) {
            try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot)) {
                withoutDepth = compileAndCache(
                        new PipelineKey(
                                initialFlavor,
                                colorFormat,
                                MTLPixelFormat.Invalid,
                                MTLPixelFormat.Invalid
                        ),
                        initialFunctions,
                        vertexDescriptor
                );
                withDepth = compileAndCache(
                        new PipelineKey(
                                initialFlavor,
                                colorFormat,
                                MTLPixelFormat.Depth32Float,
                                MTLPixelFormat.Invalid
                        ),
                        initialFunctions,
                        vertexDescriptor
                );

                sidecarScenePipelinesValid = warmSceneLinearPipelines(vertexDescriptor)
                        && warmMaterialPipelines(vertexDescriptor);
                warmAdvancedLightingPipelines(vertexDescriptor);
            }
        }
        this.isValid = allFunctionsValid
                && !MetalNativeBridge.isNullHandle(withoutDepth)
                && (!this.usesSodiumLightSidecar || !MetalNativeBridge.isNullHandle(withDepth))
                && (!this.usesSodiumLightSidecar
                || (sidecarSceneVariantsValid && sidecarScenePipelinesValid));
    }

    private boolean warmSceneLinearPipelines(final MTLVertexDescriptor vertexDescriptor) {
        if (!SceneLinearPreflightGate.shouldCompileSceneVariants() || !this.hdrRole.supportsSceneLinearFlavor()) {
            return true;
        }

        HdrShaderFlavor sceneFlavor = this.hdrRole.sceneLinearFlavor();
        ShaderFunctions sceneFunctions = this.shaderFunctions.get(sceneFlavor);
        if (sceneFunctions == null || !sceneFunctions.isValid()) {
            return false;
        }

        for (MTLPixelFormat depthFormat : List.of(MTLPixelFormat.Invalid, MTLPixelFormat.Depth32Float)) {
            PipelineKey key = new PipelineKey(
                    sceneFlavor,
                    MTLPixelFormat.RGBA16Float,
                    depthFormat,
                    MTLPixelFormat.Invalid
            );
            if (MetalNativeBridge.isNullHandle(compileAndCache(key, sceneFunctions, vertexDescriptor))) {
                if (!this.usesSodiumLightSidecar) {
                    SceneLinearPreflightGate.rejectSceneVariant(
                            "Metal rejected the prewarmed " + key + " pipeline state for " + this.info.getLocation()
                    );
                }
                return false;
            }
        }
        return true;
    }

    private boolean warmMaterialPipelines(final MTLVertexDescriptor vertexDescriptor) {
        if (!MetallumMaterialPreflightGate.shouldCompileMaterialVariants()
                || !this.hdrRole.supportsSceneLinearFlavor()) {
            return true;
        }
        ShaderFunctions materialFunctions = this.shaderFunctions.get(HdrShaderFlavor.METALLUM);
        if (materialFunctions == null || !materialFunctions.isValid() || materialFunctions.semanticOutput()) {
            MetallumMaterialPreflightGate.rejectMaterialVariant(
                    "METALLUM functions are unavailable or retained semantic output for " + this.info.getLocation()
            );
            return false;
        }
        for (MTLPixelFormat colorFormat : List.of(MTLPixelFormat.RGBA8Unorm, MTLPixelFormat.RGBA16Float)) {
            for (MTLPixelFormat depthFormat : List.of(MTLPixelFormat.Invalid, MTLPixelFormat.Depth32Float)) {
                PipelineKey key = new PipelineKey(
                        HdrShaderFlavor.METALLUM,
                        colorFormat,
                        depthFormat,
                        MTLPixelFormat.Invalid
                );
                if (MetalNativeBridge.isNullHandle(compileAndCache(key, materialFunctions, vertexDescriptor))) {
                    MetallumMaterialPreflightGate.rejectMaterialVariant(
                            "Metal rejected the prewarmed " + key + " pipeline state for "
                                    + this.info.getLocation()
                    );
                    return false;
                }
            }
        }
        return true;
    }

    private boolean warmAdvancedLightingPipelines(final MTLVertexDescriptor vertexDescriptor) {
        if (!AdvancedLightingPreflightGate.shouldCompileAdvancedVariants()) {
            return true;
        }
        for (HdrShaderFlavor flavor : List.of(
                HdrShaderFlavor.METALLUM_ADVANCED,
                HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE
        )) {
            ShaderFunctions advancedFunctions = this.shaderFunctions.get(flavor);
            if (advancedFunctions == null) {
                continue;
            }
            boolean expectedOutput = flavor == HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE;
            if (!advancedFunctions.isValid() || advancedFunctions.semanticOutput() != expectedOutput) {
                AdvancedLightingPreflightGate.rejectAdvancedVariant(
                        flavor + " functions violate their reactive output contract for "
                                + this.info.getLocation()
                );
                return false;
            }
            for (MTLPixelFormat colorFormat : List.of(MTLPixelFormat.RGBA8Unorm, MTLPixelFormat.RGBA16Float)) {
                for (MTLPixelFormat depthFormat : List.of(MTLPixelFormat.Invalid, MTLPixelFormat.Depth32Float)) {
                    PipelineKey key = new PipelineKey(
                            flavor,
                            colorFormat,
                            depthFormat,
                            MTLPixelFormat.Invalid
                    );
                    if (MetalNativeBridge.isNullHandle(compileAndCache(key, advancedFunctions, vertexDescriptor))) {
                        AdvancedLightingPreflightGate.rejectAdvancedVariant(
                                "Metal rejected the prewarmed " + key + " pipeline state for "
                                        + this.info.getLocation()
                        );
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private MemorySegment compileAndCache(
            final PipelineKey key,
            final ShaderFunctions functions,
            final MTLVertexDescriptor vertexDescriptor
    ) {
        OwnedPipelineHandle cached = this.pipelines.get(key);
        if (cached != null) {
            return cached.handle;
        }

        int compileId = compilationCounter.incrementAndGet();
        com.metallum.Metallum.LOGGER.debug(
                "Pipeline compile call: name={}, compilationId={}, key=(flavor={}, color={}, depth={}, stencil={}), semanticAttachmentFormat={}",
                this.info.getLocation(), compileId, key.flavor(), key.colorFormat(), key.depthFormat(), key.stencilFormat(),
                functions.semanticOutput()
                        ? key.flavor() == HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE
                                ? "R8Unorm"
                                : "RGBA8Unorm"
                        : "Invalid"
        );

        MemorySegment pipeline = createPipeline(
                this.device,
                this.info,
                functions.vertex(),
                functions.fragment(),
                vertexDescriptor,
                key.colorFormat(),
                key.depthFormat(),
                key.stencilFormat(),
                functions.semanticOutput(),
                key.flavor() == HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE,
                key.flavor() == HdrShaderFlavor.SUN_SHADOW
        );
        if (MetalNativeBridge.isNullHandle(pipeline)) {
            return MemorySegment.NULL;
        }

        this.pipelines.put(
                key,
                new OwnedPipelineHandle(pipeline, this.info.getLocation().toString(), compileId)
        );
        com.metallum.Metallum.LOGGER.debug(
                "Pipeline cached: name={}, flavor={}, compilationId={}, handle=0x{}",
                this.info.getLocation(), key.flavor(), compileId, Long.toHexString(pipeline.address())
        );
        return pipeline;
    }

    private static MemorySegment createPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat,
            final boolean semanticOutput,
            final boolean reactiveOutput,
            final boolean shadowDepthOnly
    ) {
        if (MetalNativeBridge.isNullHandle(vertexFunction) || MetalNativeBridge.isNullHandle(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        ColorTargetState colorTarget = info.getColorTargetState();
        Optional<BlendFunction> blendFunction = colorTarget == null
                ? Optional.empty()
                : colorTarget.blendFunction().map(function -> resolveBlendFunctionForAttachment(
                        info.getLocation(),
                        colorFormat,
                        function
                ));
        long writeMask = shadowDepthOnly
                ? MTLColorWriteMask.None.value
                : colorTarget == null
                        ? MTLColorWriteMask.All.value
                        : MTLColorWriteMask.from(colorTarget.writeMask());

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setAttachmentFormats(
                    colorFormat,
                    semanticOutput
                            ? reactiveOutput ? MTLPixelFormat.R8Unorm : MTLPixelFormat.RGBA8Unorm
                            : MTLPixelFormat.Invalid,
                    depthFormat,
                    stencilFormat
            );

            if (blendFunction.isPresent()) {
                var function = blendFunction.get();
                pipelineDesc.setBlendState(
                        0,
                        MTLBlendFactor.from(function.color().sourceFactor()),
                        MTLBlendFactor.from(function.color().destFactor()),
                        MTLBlendOperation.from(function.color().op()),
                        MTLBlendFactor.from(function.alpha().sourceFactor()),
                        MTLBlendFactor.from(function.alpha().destFactor()),
                        MTLBlendOperation.from(function.alpha().op()),
                        writeMask
                );
            } else {
                pipelineDesc.disableBlending(0, writeMask);
            }
            if (semanticOutput) {
                if (reactiveOutput) {
                    // The depth-tested opaque surface or the final back-to-front translucent
                    // surface owns the per-pixel material weight. Diagnostic composition later
                    // performs the required max merge; blending this terrain MRT is redundant
                    // and measurably expensive on Apple tile GPUs.
                    pipelineDesc.disableBlending(1, MTLColorWriteMask.Red.value);
                } else {
                    pipelineDesc.disableBlending(1, MTLColorWriteMask.All.value);
                }
            }

            return MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );
        }
    }

    static BlendFunction resolveBlendFunctionForAttachment(
            final Identifier pipelineLocation,
            final MTLPixelFormat colorFormat,
            final BlendFunction function
    ) {
        // Mojang's loading logo intentionally uses additive blending into the
        // vanilla RGBA8 target. UNORM saturation turns the opaque white texels
        // white; an FP16 target preserves the over-range red background and
        // visibly tints the logo pink. Use ordinary straight-alpha blending
        // only for this pipeline on FP16 attachments so the loading overlay
        // retains its white logo and smooth fade without affecting other
        // additive effects.
        if (colorFormat == MTLPixelFormat.RGBA16Float
                && MOJANG_LOGO_PIPELINE.equals(pipelineLocation)
                && BlendFunction.LIGHTNING.equals(function)) {
            return BlendFunction.TRANSLUCENT;
        }
        return function;
    }

    @Override
    public boolean isValid() {
        return this.isValid;
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    @Nullable
    ResourceBinding resource(final int bindingIndex) {
        if (bindingIndex < 0 || bindingIndex >= this.resourcesByBindingIndex.length) {
            return null;
        }
        return this.resourcesByBindingIndex[bindingIndex];
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    boolean usesSodiumLightSidecar() {
        return this.usesSodiumLightSidecar;
    }

    int sodiumLightSidecarBufferSlot() {
        if (!this.usesSodiumLightSidecar) {
            throw new IllegalStateException("Pipeline does not use the Sodium light sidecar: " + this.info.getLocation());
        }
        return this.sodiumLightSidecarBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MemorySegment getDepthStencilState() {
        return this.depthStencilState;
    }

    public synchronized MemorySegment getNativePipeline(
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat,
            final boolean materialSceneAttachment
    ) {
        if (this.closed) {
            throw new IllegalStateException("Pipeline has been closed: " + this.info.getLocation());
        }
        HdrShaderFlavor flavor = selectFlavor(colorFormat, materialSceneAttachment);
        ShaderFunctions functions = this.shaderFunctions.get(flavor);
        if (functions == null || !functions.isValid()) {
            throw new IllegalStateException(
                    "Shader flavor " + flavor + " is unavailable for pipeline " + this.info.getLocation()
            );
        }
        logFlavorSelectionOnce(flavor, colorFormat);

        PipelineKey key = new PipelineKey(flavor, colorFormat, depthFormat, stencilFormat);
        OwnedPipelineHandle owned = this.pipelines.get(key);
        if (owned != null) {
            return owned.handle;
        }

        MemorySegment pipeline;
        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(this.info, this.firstAvailableVertexBufferSlot)) {
            pipeline = compileAndCache(key, functions, vertexDescriptor);
        }

        if (MetalNativeBridge.isNullHandle(pipeline)) {
            throw new IllegalStateException(
                    String.format("Failed to compile native pipeline variant: name=%s, color=%s, depth=%s, stencil=%s",
                            this.info.getLocation(), colorFormat, depthFormat, stencilFormat)
            );
        }
        return pipeline;
    }

    private HdrShaderFlavor selectFlavor(
            final MTLPixelFormat colorFormat,
            final boolean materialSceneAttachment
    ) {
        if (SunShadowRenderer.isRendering()
                && this.shaderFunctions.containsKey(HdrShaderFlavor.SUN_SHADOW)) {
            return HdrShaderFlavor.SUN_SHADOW;
        }
        if (materialSceneAttachment && this.device.isMaterialWorldPassActive()) {
            if (this.hdrRole.supportsSceneLinearFlavor()) {
                return selectMaterialWorldFlavor(
                        this.device.isAdvancedLightingWorldPassActive(),
                        this.shaderFunctions.containsKey(HdrShaderFlavor.METALLUM_ADVANCED),
                        this.device.isL8ReactiveWorldPassActive(),
                        this.shaderFunctions.containsKey(HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE)
                );
            }
        }
        boolean rgba16Float = colorFormat == MTLPixelFormat.RGBA16Float;
        HdrShaderFlavor flavor = selectLegacyGenerationFlavor(
                this.hdrRole,
                this.device.isLegacyHdrSemanticGenerationActive(),
                materialSceneAttachment,
                SceneLinearPreflightGate.isActive(),
                rgba16Float,
                this.shaderFunctions.containsKey(HdrShaderFlavor.LEGACY_HDR_SEMANTIC)
        );
        if (rgba16Float
                && SceneLinearPreflightGate.isActive()
                && this.hdrRole == HdrPipelinePolicy.Role.UNKNOWN
                && UNKNOWN_FP16_PIPELINES_LOGGED.add(this.info.getLocation().toString())) {
            com.metallum.Metallum.LOGGER.warn(
                    "Unclassified pipeline {} is rendering to an FP16 attachment; Phase A keeps the LEGACY shader flavor",
                    this.info.getLocation()
            );
        }
        return flavor;
    }

    static HdrShaderFlavor selectMaterialWorldFlavor(
            final boolean advancedFrameActive,
            final boolean advancedVariantAvailable
    ) {
        return selectMaterialWorldFlavor(
                advancedFrameActive, advancedVariantAvailable, false, false
        );
    }

    static HdrShaderFlavor selectMaterialWorldFlavor(
            final boolean advancedFrameActive,
            final boolean advancedVariantAvailable,
            final boolean reactiveFrameActive,
            final boolean reactiveVariantAvailable
    ) {
        if (advancedFrameActive && advancedVariantAvailable
                && reactiveFrameActive && reactiveVariantAvailable) {
            return HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE;
        }
        return advancedFrameActive && advancedVariantAvailable
                ? HdrShaderFlavor.METALLUM_ADVANCED
                : HdrShaderFlavor.METALLUM;
    }

    boolean selectsAdvancedLighting(
            final MTLPixelFormat colorFormat,
            final boolean materialSceneAttachment
    ) {
        HdrShaderFlavor flavor = selectFlavor(colorFormat, materialSceneAttachment);
        return flavor == HdrShaderFlavor.METALLUM_ADVANCED
                || flavor == HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE;
    }

    static HdrShaderFlavor selectLegacyGenerationFlavor(
            final HdrPipelinePolicy.Role role,
            final boolean legacyHdrGeneration,
            final boolean sceneColorAttachment,
            final boolean sceneLinearActive,
            final boolean rgba16Float,
            final boolean semanticVariantAvailable
    ) {
        if (!legacyHdrGeneration || !sceneColorAttachment) {
            return HdrShaderFlavor.LEGACY;
        }
        if (sceneLinearActive && rgba16Float) {
            return HdrPipelinePolicy.selectFlavor(role, true, true);
        }
        return role == HdrPipelinePolicy.Role.SCENE_RASTER && semanticVariantAvailable
                ? HdrShaderFlavor.LEGACY_HDR_SEMANTIC
                : HdrShaderFlavor.LEGACY;
    }

    private void logFlavorSelectionOnce(
            final HdrShaderFlavor flavor,
            final MTLPixelFormat colorFormat
    ) {
        int colorFormatOrdinal = colorFormat.ordinal();
        if (colorFormatOrdinal >= Long.SIZE) {
            throw new IllegalStateException("Too many Metal pixel formats for the flavor selection log mask");
        }

        long colorFormatBit = 1L << colorFormatOrdinal;
        long loggedColorFormats = switch (flavor) {
            case LEGACY -> this.legacyColorFormatsLogged;
            case LEGACY_HDR_SEMANTIC -> this.legacyHdrSemanticColorFormatsLogged;
            case SCENE_RASTER_LINEAR -> this.sceneRasterColorFormatsLogged;
            case SCENE_POST_LINEAR -> this.scenePostColorFormatsLogged;
            case METALLUM -> this.materialColorFormatsLogged;
            case METALLUM_ADVANCED -> this.advancedMaterialColorFormatsLogged;
            case METALLUM_ADVANCED_REACTIVE -> this.reactiveMaterialColorFormatsLogged;
            case SUN_SHADOW -> this.sunShadowColorFormatsLogged;
        };
        if ((loggedColorFormats & colorFormatBit) != 0L) {
            return;
        }

        switch (flavor) {
            case LEGACY -> this.legacyColorFormatsLogged |= colorFormatBit;
            case LEGACY_HDR_SEMANTIC -> this.legacyHdrSemanticColorFormatsLogged |= colorFormatBit;
            case SCENE_RASTER_LINEAR -> this.sceneRasterColorFormatsLogged |= colorFormatBit;
            case SCENE_POST_LINEAR -> this.scenePostColorFormatsLogged |= colorFormatBit;
            case METALLUM -> this.materialColorFormatsLogged |= colorFormatBit;
            case METALLUM_ADVANCED -> this.advancedMaterialColorFormatsLogged |= colorFormatBit;
            case METALLUM_ADVANCED_REACTIVE -> this.reactiveMaterialColorFormatsLogged |= colorFormatBit;
            case SUN_SHADOW -> this.sunShadowColorFormatsLogged |= colorFormatBit;
        }
        com.metallum.Metallum.LOGGER.debug(
                "HDR shader flavor selected: pipeline={}, role={}, colorAttachment={}, flavor={}",
                this.info.getLocation(), this.hdrRole, colorFormat, flavor
        );
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    boolean semanticOutput(
            final MTLPixelFormat colorFormat,
            final boolean materialSceneAttachment
    ) {
        ShaderFunctions functions = this.shaderFunctions.get(selectFlavor(colorFormat, materialSceneAttachment));
        return functions != null && functions.semanticOutput();
    }

    boolean reactiveOutput(
            final MTLPixelFormat colorFormat,
            final boolean materialSceneAttachment
    ) {
        return selectFlavor(colorFormat, materialSceneAttachment)
                == HdrShaderFlavor.METALLUM_ADVANCED_REACTIVE;
    }

    boolean sceneColorRole() {
        return this.hdrRole.supportsSceneLinearFlavor();
    }

    boolean shouldSuppressUnsupportedMaterialDraw(
            final boolean materialGenerationActive,
            final boolean sceneColorAttachment,
            final boolean displaySdrAttachment
    ) {
        return shouldSuppressUnsupportedMaterialDraw(
                materialGenerationActive,
                sceneColorAttachment,
                displaySdrAttachment,
                this.hdrRole,
                this.shaderFunctions.containsKey(HdrShaderFlavor.METALLUM),
                MetallumMaterialPreflightGate.isActive()
        );
    }

    void rejectUnsupportedMaterialScenePipeline() {
        MetallumMaterialPreflightGate.rejectMaterialVariant(
                "unsupported scene pipeline role " + this.info.getLocation()
        );
    }

    static boolean shouldSuppressUnsupportedMaterialDraw(
            final boolean materialGenerationActive,
            final boolean sceneColorAttachment,
            final boolean displaySdrAttachment,
            final HdrPipelinePolicy.Role role,
            final boolean materialFlavorAvailable,
            final boolean materialCoverageActive
    ) {
        return materialGenerationActive
                && sceneColorAttachment
                && !displaySdrAttachment
                && (!materialCoverageActive
                || !role.supportsSceneLinearFlavor()
                || !materialFlavorAvailable);
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        long attrIndex = 0;

        for (int i = 0; i < bindings.length; i++) {
            VertexFormat binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            int metalSlot = firstMetalVertexBufferSlot + i;

            long stride = binding.getVertexSize();
            long stepRate = binding.getStepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(metalSlot, stride, stepFunction, stepRate > 0 ? stepRate : 1);

            for (VertexFormatElement element : binding.getElements()) {
                MTLVertexFormat format = MTLVertexFormat.from(element.format());
                if (format == MTLVertexFormat.Invalid) {
                    throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                }
                vertexDesc.setAttribute(attrIndex, format.value, element.offset(), metalSlot);
                attrIndex++;
            }
        }

        return vertexDesc;
    }

    static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    private static void validateSodiumLightSidecarVariants(
            final RenderPipeline pipeline,
            final Map<HdrShaderFlavor, ShaderVariantSource> variants,
            final boolean expectedPatched,
            final int dataBufferSlot
    ) {
        int controlBufferSlot = expectedPatched ? Math.addExact(dataBufferSlot, 1) : -1;
        if (expectedPatched && controlBufferSlot > 30) {
            throw new IllegalStateException(
                    "Sodium light sidecar exceeds Metal vertex buffer slots for pipeline " + pipeline.getLocation()
            );
        }

        String dataBinding = "[[buffer(" + dataBufferSlot + ")]]";
        String controlBinding = "[[buffer(" + controlBufferSlot + ")]]";
        for (Map.Entry<HdrShaderFlavor, ShaderVariantSource> entry : variants.entrySet()) {
            String vertexMsl = entry.getValue().vertexMsl();
            boolean patched = SodiumLightSidecarMslPatcher.isPatched(vertexMsl);
            if (patched != expectedPatched) {
                throw new IllegalStateException(
                        "Sodium light sidecar variants were not patched atomically for pipeline "
                                + pipeline.getLocation()
                );
            }
            if (expectedPatched
                    && (!vertexMsl.contains(dataBinding) || !vertexMsl.contains(controlBinding))) {
                throw new IllegalStateException(
                        "Sodium light sidecar buffer slots changed for pipeline " + pipeline.getLocation()
                                + " flavor " + entry.getKey()
                );
            }
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            com.metallum.Metallum.LOGGER.debug(
                    "Pipeline close ignored (already closed): name={}",
                    this.info.getLocation()
            );
            return;
        }

        com.metallum.Metallum.LOGGER.debug(
                "Pipeline close start: name={}",
                this.info.getLocation()
        );

        this.closed = true;

        // Snapshot handles to release and clear map immediately
        List<OwnedPipelineHandle> handlesToRelease = new java.util.ArrayList<>(this.pipelines.values());
        this.pipelines.clear();

        for (OwnedPipelineHandle owned : handlesToRelease) {
            if (owned != null) {
                owned.release();
            }
        }

        com.metallum.Metallum.LOGGER.debug(
                "Pipeline close end: name={}",
                this.info.getLocation()
        );
    }

    private static final class OwnedPipelineHandle {
        final MemorySegment handle;
        private final String pipelineName;
        private final int compilationId;
        private boolean released = false;

        OwnedPipelineHandle(MemorySegment handle, String pipelineName, int compilationId) {
            this.handle = handle;
            this.pipelineName = pipelineName;
            this.compilationId = compilationId;
        }

        synchronized void release() {
            if (this.released) {
                com.metallum.Metallum.LOGGER.warn(
                        "Pipeline duplicate release attempted: name={}, compilationId={}, handle=0x{}",
                        this.pipelineName, this.compilationId, Long.toHexString(this.handle.address())
                );
                return;
            }
            this.released = true;
            com.metallum.Metallum.LOGGER.debug(
                    "Pipeline release: name={}, compilationId={}, releasing=0x{}",
                    this.pipelineName, this.compilationId, Long.toHexString(this.handle.address())
            );
            MetalNativeBridge.metallum_release_object(this.handle);
        }
    }
}
