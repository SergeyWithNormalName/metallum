package com.metallum.client.metal.render;

import com.metallum.client.hdr.HdrPipelinePolicy;
import com.metallum.client.hdr.HdrShaderFlavor;
import com.metallum.client.hdr.SceneLinearPreflightGate;
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
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;
    private final boolean semanticOutput;
    private final HdrPipelinePolicy.Role hdrRole;
    private final Map<HdrShaderFlavor, ShaderFunctions> shaderFunctions;

    private static final java.util.concurrent.atomic.AtomicInteger compilationCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final Set<String> UNKNOWN_FP16_PIPELINES_LOGGED = ConcurrentHashMap.newKeySet();

    private final MemorySegment depthStencilState;
    private final MetalDevice device;
    private final RenderPipeline info;
    private final boolean isValid;
    private boolean closed = false;
    private long legacyColorFormatsLogged;
    private long sceneRasterColorFormatsLogged;
    private long scenePostColorFormatsLogged;

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

        this.resources = legacy.resources();
        this.resourcesByName = this.resources.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding)
        );

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : this.resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(this.resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;
        this.semanticOutput = legacy.semanticOutput();

        Map<HdrShaderFlavor, ShaderFunctions> compiledFunctions = new java.util.EnumMap<>(HdrShaderFlavor.class);
        boolean allFunctionsValid = true;
        for (Map.Entry<HdrShaderFlavor, ShaderVariantSource> entry : variants.entrySet()) {
            ShaderVariantSource variant = entry.getValue();
            ShaderFunctions functions = new ShaderFunctions(
                    device.getOrCompileFunction(variant.vertexMsl(), variant.vertexEntryPoint()),
                    device.getOrCompileFunction(variant.fragmentMsl(), variant.fragmentEntryPoint()),
                    variant.semanticOutput()
            );
            if (entry.getKey() != HdrShaderFlavor.LEGACY && !functions.isValid()) {
                SceneLinearPreflightGate.rejectSceneVariant(
                        "Metal rejected " + entry.getKey() + " functions for " + info.getLocation()
                );
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
        HdrShaderFlavor initialFlavor = selectFlavor(colorFormat);
        ShaderFunctions initialFunctions = this.shaderFunctions.get(initialFlavor);

        MemorySegment withoutDepth = MemorySegment.NULL;
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
                compileAndCache(
                        new PipelineKey(
                                initialFlavor,
                                colorFormat,
                                MTLPixelFormat.Depth32Float,
                                MTLPixelFormat.Invalid
                        ),
                        initialFunctions,
                        vertexDescriptor
                );

                warmSceneLinearPipelines(vertexDescriptor);
            }
        }
        this.isValid = allFunctionsValid && !MetalNativeBridge.isNullHandle(withoutDepth);
    }

    private void warmSceneLinearPipelines(final MTLVertexDescriptor vertexDescriptor) {
        if (!SceneLinearPreflightGate.shouldCompileSceneVariants() || !this.hdrRole.supportsSceneLinearFlavor()) {
            return;
        }

        HdrShaderFlavor sceneFlavor = this.hdrRole.sceneLinearFlavor();
        ShaderFunctions sceneFunctions = this.shaderFunctions.get(sceneFlavor);
        if (sceneFunctions == null || !sceneFunctions.isValid()) {
            return;
        }

        for (MTLPixelFormat depthFormat : List.of(MTLPixelFormat.Invalid, MTLPixelFormat.Depth32Float)) {
            PipelineKey key = new PipelineKey(
                    sceneFlavor,
                    MTLPixelFormat.RGBA16Float,
                    depthFormat,
                    MTLPixelFormat.Invalid
            );
            if (MetalNativeBridge.isNullHandle(compileAndCache(key, sceneFunctions, vertexDescriptor))) {
                SceneLinearPreflightGate.rejectSceneVariant(
                        "Metal rejected the prewarmed " + key + " pipeline state for " + this.info.getLocation()
                );
                return;
            }
        }
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
                functions.semanticOutput() ? "RGBA8Unorm" : "Invalid"
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
                functions.semanticOutput()
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
            final boolean semanticOutput
    ) {
        if (MetalNativeBridge.isNullHandle(vertexFunction) || MetalNativeBridge.isNullHandle(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        ColorTargetState colorTarget = info.getColorTargetState();
        Optional<BlendFunction> blendFunction = colorTarget == null ? Optional.empty() : colorTarget.blendFunction();
        long writeMask = colorTarget == null ? MTLColorWriteMask.All.value : MTLColorWriteMask.from(colorTarget.writeMask());

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setAttachmentFormats(
                    colorFormat,
                    semanticOutput ? MTLPixelFormat.RGBA8Unorm : MTLPixelFormat.Invalid,
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
                pipelineDesc.disableBlending(1, MTLColorWriteMask.All.value);
            }

            return MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );
        }
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

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
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
            final MTLPixelFormat stencilFormat
    ) {
        if (this.closed) {
            throw new IllegalStateException("Pipeline has been closed: " + this.info.getLocation());
        }
        HdrShaderFlavor flavor = selectFlavor(colorFormat);
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

    private HdrShaderFlavor selectFlavor(final MTLPixelFormat colorFormat) {
        boolean rgba16Float = colorFormat == MTLPixelFormat.RGBA16Float;
        HdrShaderFlavor flavor = HdrPipelinePolicy.selectFlavor(
                this.hdrRole,
                SceneLinearPreflightGate.isActive(),
                rgba16Float
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
            case SCENE_RASTER_LINEAR -> this.sceneRasterColorFormatsLogged;
            case SCENE_POST_LINEAR -> this.scenePostColorFormatsLogged;
        };
        if ((loggedColorFormats & colorFormatBit) != 0L) {
            return;
        }

        switch (flavor) {
            case LEGACY -> this.legacyColorFormatsLogged |= colorFormatBit;
            case SCENE_RASTER_LINEAR -> this.sceneRasterColorFormatsLogged |= colorFormatBit;
            case SCENE_POST_LINEAR -> this.scenePostColorFormatsLogged |= colorFormatBit;
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

    boolean semanticOutput() {
        return this.semanticOutput;
    }

    boolean sceneColorRole() {
        return this.hdrRole.supportsSceneLinearFlavor();
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

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
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
