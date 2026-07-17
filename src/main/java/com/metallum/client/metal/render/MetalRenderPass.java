package com.metallum.client.metal.render;

import com.metallum.client.hdr.SceneLinearClearColor;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.sodium.SodiumLightSidecarPacking;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    private final GpuTextureView colorTexture;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    @Nullable
    private Vector4fc clearColor;
    private boolean clearDepthEnabled;
    private final double clearDepthValue;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private final Object[] resourcesByBindingIndex = new Object[Long.SIZE];
    private long dirtyDescriptorMask;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;
    private boolean lastSidecarRuntimeActive;
    private int boundSidecarControlSlot = -1;
    private boolean boundSidecarControlEnabled;
    @Nullable
    private MTLRenderCommandEncoder boundRenderEncoder;
    @Nullable
    private MTLRenderCommandEncoder boundAdvancedRenderEncoder;
    private boolean advancedLightingPipeline;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final GpuTextureView colorTexture,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            @Nullable final Vector4fc clearColor,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.clearColor = clearColor;
        this.clearDepthEnabled = clearDepthEnabled;
        this.clearDepthValue = clearDepthValue;
    }

    @Override
    public void pushDebugGroup(final @NonNull Supplier<String> label) {
        pushedDebugGroups++;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().pushDebugGroup(label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        pushedDebugGroups--;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().popDebugGroup();
        }
    }

    @Override
    public void setPipeline(final @NonNull RenderPipeline pipeline) {
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        if (compiled.sceneColorRole() && !SunShadowRenderer.isRendering()) {
            ((MetalGpuTexture) this.colorTexture.texture()).markSceneColorClearRole();
        }
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            remapNamedBindings(
                    this.uniforms,
                    this.samplers,
                    compiled.resources(),
                    this.resourcesByBindingIndex
            );
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
        suppressUnsupportedMaterialDraw();
    }

    @Override
    public void bindTexture(final @NonNull String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            TextureViewAndSampler value = updateTextureBinding(this.samplers, name, textureView, sampler);
            if (commandEncoder.prepareTextureForRead((MetalGpuTexture) textureView.texture())) {
                invalidateNativeEncoderState();
            }
            MetalCompiledRenderPipeline.ResourceBinding binding = currentBinding(name);
            if (binding != null
                    && binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                this.resourcesByBindingIndex[binding.bindingIndex()] = value;
            }
            markDescriptorDirty(binding);
        } else if (textureView == null && sampler == null) {
            samplers.remove(name);
            MetalCompiledRenderPipeline.ResourceBinding binding = currentBinding(name);
            if (binding != null
                    && binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                this.resourcesByBindingIndex[binding.bindingIndex()] = null;
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void setUniform(final @NonNull String name, final GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final @NonNull String name, final @NonNull GpuBufferSlice value) {
        uniforms.put(name, value);
        MetalCompiledRenderPipeline.ResourceBinding binding = currentBinding(name);
        if (binding != null
                && binding.kind() != MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            this.resourcesByBindingIndex[binding.bindingIndex()] = value;
        }
        markDescriptorDirty(binding);
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (scissorState.enabled()
                && scissorState.x() == x
                && scissorState.y() == y
                && scissorState.width() == width
                && scissorState.height() == height) {
            return;
        }
        scissorState.enable(x, y, width, height);
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }

        if (!sameSlice(vertexBuffers[slot], vertexBuffer)) {
            vertexBuffers[slot] = vertexBuffer;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final @NonNull IndexType indexType) {
        setIndexBuffer(indexBuffer, MTLIndexType.from(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType, firstInstance);
    }

    @Override
    public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        for (int i = 0; i < drawCount; i++) {
            int firstIndex = drawParameters.get(i * 3);
            int indexCount = drawParameters.get(i * 3 + 1);
            int baseVertex = drawParameters.get(i * 3 + 2);
            if (indexCount > 0) {
                drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, baseVertex, instanceCount, indexType, firstInstance);
            }
        }
    }

    @Override
    public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan multiDrawIndexed");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MetalNativeBridge.MTLRenderCommandEncoder_multiDrawIndexed(
                enc.handle(),
                primitiveType.value,
                indexType.value,
                nativeIndexBuffer.nativeHandle(),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(firstIndexOffsets)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(indexCounts)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(vertexOffsets)),
                drawCount,
                1L,
                0L
        );
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawIndexedPrimitivesIndirect(
                primitiveType,
                indexType,
                nativeIndexBuffer.nativeHandle(),
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndexedIndirectCommand.SIZEOF
        );
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            @Nullable final IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        IndexType fallbackIndexType = defaultIndexType == null ? IndexType.SHORT : defaultIndexType;
        MTLRenderCommandEncoder enc = renderEncoder();

        for (RenderPass.Draw<T> draw : draws) {
            MTLIndexType drawIndexType = MTLIndexType.from(draw.indexType() == null ? fallbackIndexType : draw.indexType());
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            if (scissorDirty
                    || vertexBuffersDirty
                    || dirtyDescriptorMask != 0L
                    || pipelineDirty
                    || (compiledPipeline != null
                    && compiledPipeline.usesSodiumLightSidecar()
                    && this.boundRenderEncoder != enc)) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType, 0);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        if (vertexCount <= 0 || instanceCount <= 0) {
            return;
        }
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            drawTriangleFan(enc, firstVertex, vertexCount, instanceCount, firstInstance);
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, Math.max(1, instanceCount), firstInstance);
        }
    }

    @Override
    public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        if (suppressUnsupportedMaterialDraw()) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawPrimitivesIndirect(
                primitiveType,
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndirectCommand.SIZEOF
        );
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    private boolean suppressUnsupportedMaterialDraw() {
        MetalCompiledRenderPipeline pipeline = this.compiledPipeline;
        if (pipeline == null) {
            return false;
        }
        MetalGpuTexture colorAttachment = (MetalGpuTexture) this.colorTexture.texture();
        boolean suppress = pipeline.shouldSuppressUnsupportedMaterialDraw(
                this.device.isMaterialWorldPassActive(),
                colorAttachment.hasSceneColorClearRole(),
                colorAttachment.hasDisplaySdrColorRole()
        );
        if (suppress) {
            pipeline.rejectUnsupportedMaterialScenePipeline();
            this.commandEncoder.invalidateRendererGenerationFrame();
        }
        return suppress;
    }

    MTLPixelFormat colorAttachmentFormat() {
        return ((MetalGpuTexture) colorTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat stencilAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlStencilPixelFormat();
    }

    void materializePendingClear() {
        if (clearColor != null || clearDepthEnabled) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) colorTexture;
        MetalGpuTexture colorAttachment = (MetalGpuTexture) colorTexture.texture();
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        boolean clearColorNow = clearColor != null;
        boolean clearDepthNow = clearDepthEnabled;
        boolean decodeClearColor = clearColorNow && SceneLinearClearColor.shouldDecode(
                colorAttachment.getFormat() == GpuFormat.RGBA16_FLOAT,
                colorAttachment.hasSceneColorClearRole(),
                this.device.isMaterialWorldPassActive(),
                this.device.isLegacyHdrSceneLinearGenerationActive()
        );
        boolean materialSceneAttachment = colorAttachment.hasSceneColorClearRole()
                && !colorAttachment.hasDisplaySdrColorRole();
        SceneLinearClearColor.Rgb linearClear = decodeClearColor
                ? SceneLinearClearColor.extendedSrgbToLinear(clearColor.x(), clearColor.y(), clearColor.z())
                : null;
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                this.device.isLegacyHdrSemanticGenerationActive()
                        && compiledPipeline != null && compiledPipeline.semanticOutput(
                        colorAttachment.mtlPixelFormat(),
                        materialSceneAttachment
                ),
                colorTexture.getWidth(0),
                colorTexture.getHeight(0),
                clearColorNow,
                linearClear != null ? linearClear.red() : clearColorNow ? clearColor.x() : 0.0F,
                linearClear != null ? linearClear.green() : clearColorNow ? clearColor.y() : 0.0F,
                linearClear != null ? linearClear.blue() : clearColorNow ? clearColor.z() : 0.0F,
                clearColorNow ? clearColor.w() : 0.0F,
                clearDepthNow,
                clearDepthValue
        );
        clearColor = null;
        clearDepthEnabled = false;
        return encoder;
    }

    GpuBufferSlice.MappedView allocateTransient(final long size, final long alignment, @GpuBuffer.Usage final int usage) {
        return commandEncoder.transientMemory().allocateGpuMapped(size, alignment, usage);
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        int firstSlot = compiledPipeline.firstAvailableVertexBufferSlot();
        int count = compiledPipeline.vertexBufferCount();
        for (int slot = 0; slot < count; slot++) {
            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                continue;
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = (MetalGpuBuffer) vertexBuffer.buffer();
            int metalSlot = firstSlot + slot;
            enc.setBuffer(nativeVertexBuffer.nativeHandle(), vertexBuffer.offset(), metalSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }
        if (compiledPipeline.usesSodiumLightSidecar()) {
            pushSodiumLightSidecar(enc);
        }
    }

    private void pushSodiumLightSidecar(final MTLRenderCommandEncoder enc) {
        MetalDevice.SodiumLightSidecarBindings bindings = this.device.sodiumLightSidecarBindings();
        MetalGpuBuffer dataBuffer = bindings.dummyData();
        long dataOffset = 0L;
        boolean enabled = false;
        GpuBufferSlice geometry = this.vertexBuffers[0];

        if (SodiumLightSidecar.isRuntimeActive() && geometry != null) {
            try {
                long geometryOffset = geometry.offset();
                long geometryLength = geometry.length();
                long geometryEnd = Math.addExact(geometryOffset, geometryLength);
                if (geometryOffset < 0L
                        || geometryLength < SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
                        || geometryEnd > geometry.buffer().size()
                        || geometryOffset % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L) {
                    throw new IllegalStateException(
                            "terrain geometry slice has an invalid compact-vertex offset or bounds"
                    );
                }

                GpuBuffer sidecar = SodiumLightSidecar.find(geometry.buffer());
                if (!(sidecar instanceof MetalGpuBuffer nativeSidecar) || sidecar.isClosed()) {
                    throw new IllegalStateException("terrain geometry buffer has no live Metal light sidecar");
                }
                long sidecarOffset = Math.multiplyExact(
                        geometryOffset / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                        SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
                );
                long sidecarLength = Math.multiplyExact(
                        geometryLength / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                        SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
                );
                if (sidecarOffset >= sidecar.size()
                        || Math.addExact(sidecarOffset, sidecarLength) > sidecar.size()) {
                    throw new IllegalStateException("terrain light sidecar slice exceeds its companion buffer");
                }
                if (SodiumLightSidecar.isRuntimeActive()) {
                    dataBuffer = nativeSidecar;
                    dataOffset = sidecarOffset;
                    enabled = true;
                }
            } catch (RuntimeException exception) {
                SodiumLightSidecar.fail("could not bind a terrain light sidecar", exception);
            }
        }

        int dataSlot = this.compiledPipeline.sodiumLightSidecarBufferSlot();
        enc.setBuffer(
                dataBuffer.nativeHandle(),
                dataOffset,
                dataSlot,
                MetalCompiledRenderPipeline.STAGE_VERTEX
        );
        int controlSlot = dataSlot + 1;
        if (this.boundSidecarControlSlot != controlSlot
                || this.boundSidecarControlEnabled != enabled) {
            enc.setBuffer(
                    bindings.control().nativeHandle(),
                    enabled ? Integer.BYTES : 0L,
                    controlSlot,
                    MetalCompiledRenderPipeline.STAGE_VERTEX
            );
            this.boundSidecarControlSlot = controlSlot;
            this.boundSidecarControlEnabled = enabled;
        }
        this.lastSidecarRuntimeActive = SodiumLightSidecar.isRuntimeActive();
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        int triangleCount = vertexCount - 2;
        int indexCount = triangleCount * 3;
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) indexCount * fanIndexType.bytes, fanIndexType.bytes, GpuBuffer.USAGE_INDEX)) {
            if (fanIndexType == MTLIndexType.UInt16) {
                java.nio.ShortBuffer indices = mapped.data().asShortBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put((short) 0);
                    indices.put((short) (i + 1));
                    indices.put((short) (i + 2));
                }
            } else {
                java.nio.IntBuffer indices = mapped.data().asIntBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put(0);
                    indices.put(i + 1);
                    indices.put(i + 2);
                }
            }
            GpuBufferSlice slice = mapped.slice();
            encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, indexCount, fanIndexType, ((MetalGpuBuffer) slice.buffer()).nativeHandle(), slice.offset(), Math.max(1, instanceCount), firstVertex, baseInstance);
        }
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final MTLIndexType indexType,
            final int baseInstance
    ) {
        if (indexCount <= 0 || instanceCount <= 0) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan && indexCount < 3) {
            return;
        }

        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            long fanSize = Math.multiplyExact(Math.multiplyExact((long) indexCount - 2L, 3L), Integer.BYTES);
            try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped(fanSize, Integer.BYTES, GpuBuffer.USAGE_INDEX)) {
                GpuBufferSlice slice = mapped.slice();
                enc.drawIndexedPrimitivesTriangleFan(
                        nativeIndexBuffer.nativeHandle(),
                        ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                        slice.offset(),
                        indexType.value,
                        indexOffsetBytes,
                        indexCount,
                        baseVertex,
                        instanceCount,
                        baseInstance
                );
            }
        } else {
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, nativeIndexBuffer.nativeHandle(), indexOffsetBytes, instanceCount, baseVertex, baseInstance);
        }
    }

    private void bindDrawState(final MTLRenderCommandEncoder enc) {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        boolean sidecarPipeline = compiledPipeline.usesSodiumLightSidecar();
        if (sidecarPipeline && this.boundRenderEncoder != enc) {
            this.invalidateNativeEncoderState();
            this.boundRenderEncoder = enc;
        }

        if (sidecarPipeline
                && this.lastSidecarRuntimeActive != SodiumLightSidecar.isRuntimeActive()) {
            this.vertexBuffersDirty = true;
        }

        if (pipelineDirty) {
            MTLPixelFormat colorFormat = colorAttachmentFormat();
            MTLPixelFormat depthFormat = depthAttachmentFormat();
            MTLPixelFormat stencilFormat = stencilAttachmentFormat();
            boolean useDepth = depthFormat.value != MTLPixelFormat.Invalid.value;
            MetalGpuTexture colorAttachment = (MetalGpuTexture) this.colorTexture.texture();
            boolean materialSceneAttachment = colorAttachment.hasSceneColorClearRole()
                    && !colorAttachment.hasDisplaySdrColorRole();
            MemorySegment pipelineHandle = compiledPipeline.getNativePipeline(
                    colorFormat,
                    depthFormat,
                    stencilFormat,
                    materialSceneAttachment
            );
            if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            this.advancedLightingPipeline = compiledPipeline.selectsAdvancedLighting(
                    colorFormat,
                    materialSceneAttachment
            );
            pipelineDirty = false;

            if (useDepth) {
                MemorySegment depthState = compiledPipeline.getDepthStencilState();
                if (MetalNativeBridge.isNullHandle(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                enc.setDepthStencilState(depthState);
                enc.setDepthBias(
                        compiledPipeline.depthBiasConstant(),
                        compiledPipeline.depthBiasScaleFactor(),
                        0.0f
                );
                if (SunShadowRenderer.isRendering()) {
                    enc.setDepthBias(
                            SunShadowRenderer.activeRasterDepthBias(),
                            SunShadowRenderer.activeRasterSlopeBias(),
                            0.0f
                    );
                }
            }

            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            enc.setCullMode(compiledPipeline.cullMode());
            enc.setTriangleFillMode(compiledPipeline.fillMode());

            // A pipeline switch can leave dirty bits from the previous numeric layout.
            // Rebinding the complete current layout supersedes that stale state.
            dirtyDescriptorMask = compiledPipeline.allResourceMask();
        }

        if (this.advancedLightingPipeline && this.boundAdvancedRenderEncoder != enc) {
            this.device.bindAdvancedLighting(enc);
            this.boundAdvancedRenderEncoder = enc;
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (dirtyDescriptorMask != 0) {
            if (!shouldBatchResourceBindings(dirtyDescriptorMask)) {
                int bindingIndex = Long.numberOfTrailingZeros(dirtyDescriptorMask);
                MetalCompiledRenderPipeline.ResourceBinding binding = requireBinding(bindingIndex);
                pushDescriptorDirect(enc, binding);
            } else {
                MetalResourceBindingPacket packet = this.commandEncoder.resourceBindingPacket();
                packet.reset();
                long remaining = dirtyDescriptorMask;
                while (remaining != 0L) {
                    int bindingIndex = Long.numberOfTrailingZeros(remaining);
                    appendDescriptor(packet, requireBinding(bindingIndex));
                    remaining &= ~(1L << bindingIndex);
                }
                int status = enc.applyResourceBindings(packet.finish(), packet.capacityBytes());
                if (status != MetalResourceBindingPacket.STATUS_OK) {
                    throw new IllegalStateException("Native Metal resource binding batch failed: "
                            + MetalResourceBindingPacket.statusName(status));
                }
            }
        }

        dirtyDescriptorMask = 0L;
    }

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return compiledPipeline.topology();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        int areaLeft = renderArea.x();
        int areaTop = renderArea.y();
        if (!scissorState.enabled()) {
            if (renderArea.fillsTexture(colorTexture)) {
                enc.setScissorRect(0L, 0L, colorTexture.getWidth(0), colorTexture.getHeight(0));
                return;
            }
            enc.setScissorRect(areaLeft, areaTop, renderArea.width(), renderArea.height());
            return;
        }

        int areaRight = areaLeft + renderArea.width();
        int areaBottom = areaTop + renderArea.height();
        int left = Math.max(areaLeft, scissorState.x());
        int top = Math.max(areaTop, scissorState.y());
        int right = Math.min(areaRight, scissorState.x() + scissorState.width());
        int bottom = Math.min(areaBottom, scissorState.y() + scissorState.height());
        if (right <= left || bottom <= top) {
            enc.setScissorRect(0, 0, 0, 0);
        } else {
            enc.setScissorRect(left, top, right - left, bottom - top);
        }
    }

    private MetalCompiledRenderPipeline.@Nullable ResourceBinding currentBinding(final String name) {
        return this.compiledPipeline == null ? null : this.compiledPipeline.resource(name);
    }

    private void markDescriptorDirty(
            final MetalCompiledRenderPipeline.@Nullable ResourceBinding binding
    ) {
        if (binding != null) {
            dirtyDescriptorMask |= 1L << binding.bindingIndex();
        }
    }

    static boolean shouldBatchResourceBindings(final long dirtyDescriptorMask) {
        return Long.bitCount(dirtyDescriptorMask) >= 2;
    }

    private void invalidateNativeEncoderState() {
        this.pipelineDirty = true;
        this.scissorDirty = true;
        this.vertexBuffersDirty = true;
        this.boundSidecarControlSlot = -1;
        this.boundAdvancedRenderEncoder = null;
        if (this.compiledPipeline != null) {
            this.dirtyDescriptorMask |= this.compiledPipeline.allResourceMask();
        }
    }

    private void appendDescriptor(
            final MetalResourceBindingPacket packet,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding =
                    (TextureViewAndSampler) this.resourcesByBindingIndex[binding.bindingIndex()];
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            packet.addTextureSampler(
                    textureView.nativeHandle(),
                    sampler.nativeHandle(),
                    binding.bindingIndex(),
                    binding.stageMask()
            );
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            appendTexelBufferDescriptor(packet, binding);
            return;
        }

        GpuBufferSlice uniformSlice = (GpuBufferSlice) this.resourcesByBindingIndex[binding.bindingIndex()];
        if (uniformSlice == null) {
            throw new IllegalStateException("Missing uniform " + binding.name());
        }
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        packet.addUniformBuffer(
                uniformBuffer.nativeHandle(),
                uniformSlice.offset(),
                uniformSlice.length(),
                uniformBuffer.size(),
                binding.bindingIndex(),
                binding.stageMask()
        );
    }

    private void pushDescriptorDirect(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding =
                    (TextureViewAndSampler) this.resourcesByBindingIndex[binding.bindingIndex()];
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }
            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }
            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            enc.setTextureAndSampler(
                    textureView.nativeHandle(),
                    sampler.nativeHandle(),
                    binding.bindingIndex(),
                    binding.stageMask()
            );
            return;
        }
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            enc.setTexture(
                    resolveTexelTexture(binding),
                    binding.bindingIndex(),
                    binding.stageMask()
            );
            return;
        }

        GpuBufferSlice uniformSlice = requireBufferSlice(binding, "Uniform");
        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        enc.setBuffer(
                uniformBuffer.nativeHandle(),
                uniformSlice.offset(),
                binding.bindingIndex(),
                binding.stageMask()
        );
    }

    private void appendTexelBufferDescriptor(
            final MetalResourceBindingPacket packet,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        packet.addTexelTexture(resolveTexelTexture(binding), binding.bindingIndex(), binding.stageMask());
    }

    private MetalCompiledRenderPipeline.ResourceBinding requireBinding(final int bindingIndex) {
        MetalCompiledRenderPipeline.ResourceBinding binding = this.compiledPipeline.resource(bindingIndex);
        if (binding == null) {
            throw new IllegalStateException("Pipeline resource mask references missing binding " + bindingIndex);
        }
        return binding;
    }

    private GpuBufferSlice requireBufferSlice(
            final MetalCompiledRenderPipeline.ResourceBinding binding,
            final String label
    ) {
        GpuBufferSlice slice = (GpuBufferSlice) this.resourcesByBindingIndex[binding.bindingIndex()];
        if (slice == null) {
            throw new IllegalStateException("Missing " + label.toLowerCase(java.util.Locale.ROOT)
                    + " " + binding.name());
        }
        if (VALIDATION && slice.buffer().isClosed()) {
            throw new IllegalStateException(label + " " + binding.name() + " buffer has been closed");
        }
        return slice;
    }

    private MemorySegment resolveTexelTexture(
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        GpuBufferSlice texelSlice = requireBufferSlice(binding, "Texel");
        GpuFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        long pixelFormat = MTLPixelFormat.from(texelFormat).value;
        int pixelSize = texelFormat.blockSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length "
                    + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        long texelCount = texelByteLength / pixelSize;
        try {
            return texelBuffer.texelTextureView(
                    pixelFormat,
                    texelSlice.offset(),
                    texelCount,
                    texelByteLength
            );
        } catch (IllegalStateException exception) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for "
                    + binding.name(), exception);
        }
    }

    static void remapNamedBindings(
            final Map<String, ?> uniformBindings,
            final Map<String, ?> samplerBindings,
            final List<MetalCompiledRenderPipeline.ResourceBinding> pipelineBindings,
            final Object[] bindingsByIndex
    ) {
        for (MetalCompiledRenderPipeline.ResourceBinding binding : pipelineBindings) {
            Map<String, ?> source = binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE
                    ? samplerBindings
                    : uniformBindings;
            bindingsByIndex[binding.bindingIndex()] = source.get(binding.name());
        }
    }

    static TextureViewAndSampler updateTextureBinding(
            final Map<String, TextureViewAndSampler> bindings,
            final String name,
            final GpuTextureView textureView,
            final GpuSampler sampler
    ) {
        TextureViewAndSampler binding = bindings.get(name);
        if (binding == null) {
            binding = new TextureViewAndSampler(textureView, sampler);
            bindings.put(name, binding);
        } else {
            binding.update(textureView, sampler);
        }
        return binding;
    }

    static final class TextureViewAndSampler {
        private GpuTextureView textureView;
        private GpuSampler sampler;

        TextureViewAndSampler(final GpuTextureView textureView, final GpuSampler sampler) {
            this.update(textureView, sampler);
        }

        void update(final GpuTextureView textureView, final GpuSampler sampler) {
            this.textureView = textureView;
            this.sampler = sampler;
        }

        GpuTextureView textureView() {
            return this.textureView;
        }

        GpuSampler sampler() {
            return this.sampler;
        }
    }

    private static boolean sameSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }
}
