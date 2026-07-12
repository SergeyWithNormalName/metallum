package com.metallum.client.metal.render;

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

    private static final java.util.concurrent.atomic.AtomicInteger compilationCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    private final MemorySegment depthStencilState;
    private final MetalDevice device;
    private final RenderPipeline info;
    private final MemorySegment vertexFunction;
    private final MemorySegment fragmentFunction;
    private final boolean isValid;
    private boolean closed = false;

    private final Map<PipelineKey, OwnedPipelineHandle> pipelines = new HashMap<>();

    private record PipelineKey(MTLPixelFormat colorFormat, MTLPixelFormat depthFormat, MTLPixelFormat stencilFormat) {}

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.device = device;
        this.info = info;
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;
        this.semanticOutput = fragmentMsl.contains("[[color(1)]]");

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

        this.vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        this.fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);

        if (!MetalNativeBridge.isNullHandle(this.vertexFunction) && !MetalNativeBridge.isNullHandle(this.fragmentFunction)) {
            try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot)) {
                int id1 = compilationCounter.incrementAndGet();
                com.metallum.Metallum.LOGGER.debug(
                        "Pipeline compile call: name={}, compilationId={}, key=(color={}, depth=Invalid, stencil=Invalid), semanticAttachmentFormat={}",
                        info.getLocation(), id1, colorFormat, this.semanticOutput ? "RGBA8Unorm" : "Invalid"
                );
                MemorySegment withoutDepth = createPipeline(
                        device, info, this.vertexFunction, this.fragmentFunction, vertexDescriptor,
                        colorFormat, MTLPixelFormat.Invalid, MTLPixelFormat.Invalid, this.semanticOutput
                );
                if (!MetalNativeBridge.isNullHandle(withoutDepth)) {
                    OwnedPipelineHandle ownedWithoutDepth = new OwnedPipelineHandle(withoutDepth, info.getLocation().toString(), id1);
                    this.pipelines.put(new PipelineKey(colorFormat, MTLPixelFormat.Invalid, MTLPixelFormat.Invalid), ownedWithoutDepth);
                    com.metallum.Metallum.LOGGER.debug(
                            "Pipeline cached: name={}, compilationId={}, handle=0x{}",
                            info.getLocation(), id1, Long.toHexString(withoutDepth.address())
                    );
                }

                int id2 = compilationCounter.incrementAndGet();
                com.metallum.Metallum.LOGGER.debug(
                        "Pipeline compile call: name={}, compilationId={}, key=(color={}, depth=Depth32Float, stencil=Invalid), semanticAttachmentFormat={}",
                        info.getLocation(), id2, colorFormat, this.semanticOutput ? "RGBA8Unorm" : "Invalid"
                );
                MemorySegment withDepth = createPipeline(
                        device, info, this.vertexFunction, this.fragmentFunction, vertexDescriptor,
                        colorFormat, MTLPixelFormat.Depth32Float, MTLPixelFormat.Invalid, this.semanticOutput
                );
                if (!MetalNativeBridge.isNullHandle(withDepth)) {
                    OwnedPipelineHandle ownedWithDepth = new OwnedPipelineHandle(withDepth, info.getLocation().toString(), id2);
                    this.pipelines.put(new PipelineKey(colorFormat, MTLPixelFormat.Depth32Float, MTLPixelFormat.Invalid), ownedWithDepth);
                    com.metallum.Metallum.LOGGER.debug(
                            "Pipeline cached: name={}, compilationId={}, handle=0x{}",
                            info.getLocation(), id2, Long.toHexString(withDepth.address())
                    );
                }

                this.isValid = !MetalNativeBridge.isNullHandle(withoutDepth);
            }
        } else {
            this.isValid = false;
        }
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
        PipelineKey key = new PipelineKey(colorFormat, depthFormat, stencilFormat);
        OwnedPipelineHandle owned = this.pipelines.get(key);
        if (owned != null) {
            return owned.handle;
        }

        int compileId = compilationCounter.incrementAndGet();
        com.metallum.Metallum.LOGGER.debug(
                "Pipeline compile call: name={}, compilationId={}, key=(color={}, depth={}, stencil={}), semanticAttachmentFormat={}",
                this.info.getLocation(), compileId, colorFormat, depthFormat, stencilFormat,
                this.semanticOutput ? "RGBA8Unorm" : "Invalid"
        );

        MemorySegment pipeline;
        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(this.info, this.firstAvailableVertexBufferSlot)) {
            pipeline = createPipeline(
                    this.device,
                    this.info,
                    this.vertexFunction,
                    this.fragmentFunction,
                    vertexDescriptor,
                    colorFormat,
                    depthFormat,
                    stencilFormat,
                    this.semanticOutput
            );
        }

        if (MetalNativeBridge.isNullHandle(pipeline)) {
            throw new IllegalStateException(
                    String.format("Failed to compile native pipeline variant: name=%s, compilationId=%d, color=%s, depth=%s, stencil=%s",
                            this.info.getLocation(), compileId, colorFormat, depthFormat, stencilFormat)
            );
        }

        OwnedPipelineHandle ownedHandle = new OwnedPipelineHandle(pipeline, this.info.getLocation().toString(), compileId);
        com.metallum.Metallum.LOGGER.debug(
                "Pipeline cached: name={}, compilationId={}, handle=0x{}",
                this.info.getLocation(), compileId, Long.toHexString(pipeline.address())
        );

        this.pipelines.put(key, ownedHandle);
        return pipeline;
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
