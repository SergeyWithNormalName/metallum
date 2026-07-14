package com.metallum.client.metal.render;

import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.hdr.HdrConfig;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.hdr.HdrOutputMode;
import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.SceneLinearClearColor;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    private static final int COLOR_LOAD = 0;
    private static final int COLOR_CLEAR = 1;
    private static final int COLOR_DONT_CARE = 2;
    private static final long MAX_DYNAMIC_BACKING_POOL_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_DYNAMIC_BACKINGS_PER_SIZE = 64;
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];
    private final MemorySegment[] submitSemaphores = new MemorySegment[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    private final MetalTransientMemory transientMemory;
    private final MetalResourceBindingPacket resourceBindingPacket = new MetalResourceBindingPacket();
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MemorySegment fence;
    @Nullable
    private final MetalJavaWorkloadTelemetry workloadTelemetry;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment renderColorAttachment = MemorySegment.NULL;
    private MemorySegment renderSemanticAttachment = MemorySegment.NULL;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    private final DynamicBackingPool<MemorySegment> dynamicBackingPool;
    private MetalGpuTimingStage gpuTimingStage = MetalGpuTimingStage.NONE;
    private final PendingUiSeedState<PendingUiSeed> pendingUiSeeds = new PendingUiSeedState<>();

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.dynamicBackingPool = new DynamicBackingPool<>(
                MAX_DYNAMIC_BACKING_POOL_BYTES,
                MAX_DYNAMIC_BACKINGS_PER_SIZE,
                MetalNativeBridge::metallum_release_object
        );
        this.workloadTelemetry = MetalGpuTiming.createJavaWorkloadTelemetry();
        this.transientMemory = new MetalTransientMemory(device, this, this.workloadTelemetry);
        fence = MetalNativeBridge.metallum_create_fence(device.metalDeviceHandle());
        if (MetalNativeBridge.isNullHandle(fence)) {
            throw new IllegalStateException("Failed to allocate MTLFence");
        }
        for (int slot = 0; slot < MAX_SUBMITS_IN_FLIGHT; slot++) {
            submitSemaphores[slot] = MetalNativeBridge.metallum_create_semaphore();
            if (MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                throw new IllegalStateException("Failed to allocate submit semaphore");
            }
        }
    }

    MTLCommandBuffer commandBuffer() {
        if (commandBuffer != null) {
            return commandBuffer;
        }
        return commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
            return blitEncoder;
        }
        endEncoder();
        MTLBlitCommandEncoder encoder = commandBuffer().makeBlitCommandEncoder();
        encoder.waitForFence(fence);
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.updateFence(fence);
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
        }
        renderColorAttachment = MemorySegment.NULL;
        renderSemanticAttachment = MemorySegment.NULL;
        renderDepthAttachment = MemorySegment.NULL;
    }

    @Override
    public @NonNull TransientMemory transientMemory() {
        return transientMemory;
    }

    MetalResourceBindingPacket resourceBindingPacket() {
        return this.resourceBindingPacket;
    }

    @Override
    public void submit() {
        if (commandBuffer == null) {
            return;
        }

        submitRenderPass();
        materializePendingUiSeed();
        endEncoder();
        recordJavaWorkload();

        int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
        MemorySegment completedSemaphore = submitSemaphores[slot];
        commandBuffer.commitWithSignal(completedSemaphore);

        InFlight toClose = inFlight[slot];
        inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer, completedSemaphore);
        commandBuffer = null;
        gpuTimingStage = MetalGpuTimingStage.NONE;
        currentSubmitIndex++;

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        if (toClose != null) {
            toClose.buffer.close();
        }

        transientMemory.rotate();
        destroyQueue.rotate();
    }

    private void recordJavaWorkload() {
        if (this.workloadTelemetry == null) {
            return;
        }
        MetalJavaWorkloadTelemetry.Snapshot snapshot = this.workloadTelemetry.snapshot();
        this.commandBuffer.recordJavaWorkload(
                snapshot.cpuToSharedBytes(),
                snapshot.cpuToSharedOperations(),
                snapshot.cpuTransientRequestedBytes(),
                snapshot.cpuTransientReservedBytes(),
                snapshot.gpuTransientRequestedBytes(),
                snapshot.gpuTransientReservedBytes()
        );
        this.workloadTelemetry.reset();
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView colorTextureView,
            @Nullable final MetalGpuTextureView depthTextureView,
            final boolean semanticOutput,
            final int viewportWidth,
            final int viewportHeight,
            final boolean clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        MemorySegment colorAttachment = colorTextureView.nativeHandle();
        PendingUiSeed seed = this.pendingUiSeeds.peek();
        boolean fusePendingSeed = seed != null
                && currentEncoder == null
                && seed.canFuse(
                        colorTextureView,
                        viewportWidth,
                        viewportHeight,
                        clearColorEnabled,
                        semanticOutput,
                        currentSubmitIndex
                );
        if (seed != null && !fusePendingSeed) {
            materializePendingUiSeed();
            seed = null;
        }
        MetalDevice.SemanticAttachment semanticAttachment = semanticOutput
                ? this.device.prepareHdrSemanticAttachment((MetalGpuTexture) colorTextureView.texture())
                : null;
        MemorySegment semanticHandle = semanticAttachment == null ? MemorySegment.NULL : semanticAttachment.texture();
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        MetalGpuTexture depthTexture = depthTextureView == null
                ? null
                : (MetalGpuTexture) depthTextureView.texture();
        MTLPixelFormat depthFormat = depthTexture == null
                ? MTLPixelFormat.Invalid
                : depthTexture.mtlPixelFormat();
        MTLPixelFormat stencilFormat = depthTexture == null
                ? MTLPixelFormat.Invalid
                : depthTexture.mtlStencilPixelFormat();
        // The semantic mask accumulates contributions from every scene target
        // in the current submitted frame. An offscreen color/depth clear (for
        // example Fabulous translucent terrain) must not erase opaque markers
        // already written by the main target.
        boolean clearSemantic = semanticAttachment != null && semanticAttachment.clear();
        if (currentEncoder instanceof MTLRenderCommandEncoder enc
                && MetalPipelineSupport.sameHandle(renderColorAttachment, colorAttachment)
                && MetalPipelineSupport.sameHandle(renderSemanticAttachment, semanticHandle)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment)
                && !clearSemantic) {
            if (clearColorEnabled || clearDepthEnabled) {
                enc.clearDraw(
                        colorAttachment,
                        depthAttachment,
                        viewportWidth,
                        viewportHeight,
                        clearColorEnabled,
                        clearColorRed,
                        clearColorGreen,
                        clearColorBlue,
                        clearColorAlpha,
                        clearDepthEnabled,
                        clearDepthValue
                );
            }
            return enc;
        }

        endEncoder();
        MTLCommandBuffer activeCommandBuffer = commandBuffer();
        MTLRenderCommandEncoder encoder = activeCommandBuffer.makeRenderCommandEncoder(
                colorAttachment,
                semanticHandle,
                depthAttachment,
                viewportWidth,
                viewportHeight,
                fusePendingSeed ? COLOR_DONT_CARE : clearColorEnabled ? COLOR_CLEAR : COLOR_LOAD,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearSemantic ? 1 : 0,
                clearDepthEnabled ? 1 : 0,
                clearDepthValue,
                this.gpuTimingStage.nativeId()
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        renderColorAttachment = colorAttachment;
        renderSemanticAttachment = semanticHandle;
        renderDepthAttachment = depthAttachment;
        if (fusePendingSeed && seed != null) {
            if (encoder.encodePreparedHdrUiBackdrop(
                    activeCommandBuffer,
                    seed.source.nativeHandle(),
                    seed.destination.nativeHandle(),
                    depthFormat,
                    stencilFormat
            )) {
                this.pendingUiSeeds.consume(seed);
                return encoder;
            }

            // A failed prepared-state validation must never expose the
            // dontCare attachment. Close the empty pass, materialize through
            // the standalone safety path, then reopen the GUI pass with LOAD.
            endEncoder();
            materializePendingUiSeed();
            encoder = activeCommandBuffer.makeRenderCommandEncoder(
                    colorAttachment,
                    semanticHandle,
                    depthAttachment,
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled ? COLOR_CLEAR : COLOR_LOAD,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearSemantic ? 1 : 0,
                    clearDepthEnabled ? 1 : 0,
                    clearDepthValue,
                    this.gpuTimingStage.nativeId()
            );
            encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
            currentEncoder = encoder;
            renderColorAttachment = colorAttachment;
            renderSemanticAttachment = semanticHandle;
            renderDepthAttachment = depthAttachment;
        }
        return encoder;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = descriptor.colorAttachments().getFirst();
        GpuTextureView colorTexture = colorAttachment.textureView();
        Optional<Vector4fc> colorClear = colorAttachment.clearValue();
        MetalGpuTexture colorTex = (MetalGpuTexture) colorTexture.texture();
        Vector4fc pendingColor = pendingColorClears.get(colorTex);
        if (pendingColor != null && isFullTextureView(colorTexture) && colorClear.isEmpty()) {
            pendingColorClears.remove(colorTex);
            colorClear = Optional.of(pendingColor);
        } else if (pendingColor != null && colorClear.isEmpty()) {
            flushPendingClear(colorTex);
        } else {
            pendingColorClears.remove(colorTex);
        }
        colorTex.markContentsDirty();

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        GpuTextureView depthTexture = depthAttachment == null ? null : depthAttachment.textureView();
        OptionalDouble depthClear = depthAttachment == null ? OptionalDouble.empty() : depthAttachment.clearValue();
        if (depthAttachment != null) {
            MetalGpuTexture metalDepth = (MetalGpuTexture) depthTexture.texture();
            Double pendingDepth = pendingDepthClears.get(metalDepth);
            if (pendingDepth != null && isFullTextureView(depthTexture) && depthClear.isEmpty()) {
                pendingDepthClears.remove(metalDepth);
                depthClear = OptionalDouble.of(pendingDepth);
            } else if (pendingDepth != null && depthClear.isEmpty()) {
                flushPendingClear(metalDepth);
            } else {
                pendingDepthClears.remove(metalDepth);
            }
            metalDepth.markContentsDirty();
        }

        assert descriptor.renderArea != null;
        RenderPass.RenderArea renderArea = descriptor.renderArea;
        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                colorTexture,
                depthTexture,
                renderArea,
                colorClear.orElse(null),
                depthClear.isPresent(),
                depthClear.orElse(0.0)
        );
        currentRenderPass = renderPass;
        renderPass.pushDebugGroup(descriptor.label());
        return renderPass;
    }

    @Override
    public void submitRenderPass() {
        if (currentRenderPass != null) {
            currentRenderPass.materializePendingClear();
            currentRenderPass.popDebugGroup();
            currentRenderPass = null;
        }
    }

    MTLCommandBuffer.PresentResult presentTextureToDrawable(
            final MemorySegment drawable,
            final GpuTextureView textureView,
            final HdrOutputMode outputMode,
            final HdrConfig hdrConfig,
            final EdrCapabilities edrCapabilities
    ) {
        MetalGpuTexture source = (MetalGpuTexture) textureView.texture();
        prepareTextureForRead(source);
        submitRenderPass();
        materializePendingUiSeed();
        endEncoder();
        MTLCommandBuffer commandBuffer = commandBuffer();
        MetalDevice.HdrSceneInputs sceneInputs = this.device.consumeHdrSceneInputs(source);
        return commandBuffer.encodePresentTextureToDrawable(
                drawable,
                source.nativeHandle(),
                sceneInputs.scene(),
                sceneInputs.depth(),
                sceneInputs.semantic(),
                sceneInputs.ui(),
                fence,
                sceneInputs.spatialHdrPrecomposed(),
                outputMode.nativeValue(),
                HdrSceneState.sourceEncoding().nativeValue(source.getFormat() == GpuFormat.RGBA16_FLOAT),
                hdrConfig.diagnosticPattern(),
                Math.min(edrCapabilities.currentHeadroom(), HdrConfig.OUTPUT_HEADROOM),
                hdrConfig.hdrStrength(),
                hdrConfig.bloomStrength()
        );
    }

    int encodeHdrUiBackdrop(
            final MetalGpuTexture source,
            final MetalGpuTexture destination,
            final MemorySegment sceneDepthTexture,
            final MemorySegment semanticTexture,
            final boolean hdrPrecomposeEnabled,
            final boolean perceptualScalingEnabled,
            final float currentHeadroom,
            final HdrConfig hdrConfig
    ) {
        materializePendingUiSeed();
        if (source == destination || source.isClosed() || destination.isClosed()) {
            return 0;
        }

        submitRenderPass();
        flushPendingClear(source);
        pendingColorClears.remove(destination);
        pendingDepthClears.remove(destination);
        destination.markContentsDirty();
        endEncoder();
        int sourceEncoding = HdrSceneState.sourceEncoding().nativeValue(
                source.getFormat() == GpuFormat.RGBA16_FLOAT
        );
        boolean deferSpatialHdrUiSeed = MetalFxSpatialScaling.isActive() && hdrPrecomposeEnabled;
        int result = commandBuffer().encodeHdrUiBackdrop(
                source.nativeHandle(),
                destination.nativeHandle(),
                sceneDepthTexture,
                semanticTexture,
                fence,
                sourceEncoding,
                MetalFxSpatialScaling.isActive(),
                hdrPrecomposeEnabled,
                perceptualScalingEnabled,
                deferSpatialHdrUiSeed,
                currentHeadroom,
                hdrConfig.hdrStrength(),
                hdrConfig.bloomStrength()
        );
        if (result == 2 && deferSpatialHdrUiSeed) {
            this.pendingUiSeeds.arm(new PendingUiSeed(
                    source,
                    destination,
                    sceneDepthTexture,
                    semanticTexture,
                    sourceEncoding,
                    perceptualScalingEnabled,
                    currentHeadroom,
                    hdrConfig.hdrStrength(),
                    hdrConfig.bloomStrength(),
                    currentSubmitIndex
            ));
        }
        return result;
    }

    boolean materializePendingUiSeedForRead(final MetalGpuTexture texture) {
        PendingUiSeed seed = this.pendingUiSeeds.peek();
        if (seed != null && seed.destination == texture) {
            materializePendingUiSeed();
            return true;
        }
        return false;
    }

    void discardPendingUiSeedForTexture(final MetalGpuTexture texture) {
        PendingUiSeed seed = this.pendingUiSeeds.peek();
        if (seed != null && (seed.source == texture || seed.destination == texture)) {
            // Re-run the ordinary backdrop before a live destination can be
            // destroyed or replaced. This also invalidates the native record.
            materializePendingUiSeed();
        }
    }

    void materializePendingUiSeed() {
        PendingUiSeed seed = this.pendingUiSeeds.peek();
        if (seed == null) {
            return;
        }
        if (seed.submitIndex != currentSubmitIndex
                || seed.source.isClosed()
                || seed.destination.isClosed()) {
            this.pendingUiSeeds.consume(seed);
            throw new IllegalStateException("Deferred HDR UI seed escaped its source submit or texture lifetime");
        }

        endEncoder();
        MTLCommandBuffer activeCommandBuffer = commandBuffer();
        int result = activeCommandBuffer.materializePreparedHdrUiBackdrop(
                seed.source.nativeHandle(),
                seed.destination.nativeHandle(),
                fence
        );
        if (result != 1) {
            // Prepared-state loss is recoverable: rebuild the existing,
            // proven standalone result=2 path with deferral disabled.
            result = activeCommandBuffer.encodeHdrUiBackdrop(
                    seed.source.nativeHandle(),
                    seed.destination.nativeHandle(),
                    seed.sceneDepthTexture,
                    seed.semanticTexture,
                    fence,
                    seed.sourceEncoding,
                    true,
                    true,
                    seed.perceptualScalingEnabled,
                    false,
                    seed.currentHeadroom,
                    seed.hdrStrength,
                    seed.bloomStrength
            );
        }
        if (result <= 0) {
            throw new IllegalStateException("Failed to materialize deferred HDR UI seed: " + result);
        }
        this.pendingUiSeeds.consume(seed);
    }

    boolean encodeSpatialScreenshot(
            final MetalGpuTexture rawScene,
            final MetalGpuTexture ui,
            final MetalGpuTexture destination,
            final float currentHeadroom
    ) {
        if (rawScene == ui
                || rawScene == destination
                || ui == destination
                || rawScene.isClosed()
                || ui.isClosed()
                || destination.isClosed()) {
            return false;
        }
        submitRenderPass();
        prepareTextureForRead(rawScene);
        prepareTextureForRead(ui);
        pendingColorClears.remove(destination);
        pendingDepthClears.remove(destination);
        destination.markContentsDirty();
        endEncoder();
        int sourceEncoding = HdrSceneState.sourceEncoding().nativeValue(
                rawScene.getFormat() == GpuFormat.RGBA16_FLOAT
        );
        return commandBuffer().encodeSpatialScreenshot(
                rawScene.nativeHandle(),
                ui.nativeHandle(),
                destination.nativeHandle(),
                fence,
                sourceEncoding,
                currentHeadroom
        ) == 1;
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        materializePendingUiSeedForRead(color);
        pendingColorClears.put(color, new Vector4f(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        materializePendingUiSeedForRead(color);
        pendingColorClears.put(color, new Vector4f(clearColor));
        pendingDepthClears.put(depth, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
            final @NonNull GpuTexture colorTexture,
            final @NonNull Vector4fc clearColor,
            final @NonNull GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight
    ) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        materializePendingUiSeedForRead(color);
        Vector4fc clearColorCopy = new Vector4f(clearColor);
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, clearDepth);
            return;
        }
        color.markContentsDirty();
        depth.markContentsDirty();
        submitRenderPass();
        endEncoder();
        SceneLinearClearColor.Rgb linearClear = decodeClearRgbIfNeeded(color, clearColorCopy);
        commandBuffer().clearColorDepthTexturesRegion(
                color.nativeHandle(),
                linearClear != null ? linearClear.red() : clearColorCopy.x(),
                linearClear != null ? linearClear.green() : clearColorCopy.y(),
                linearClear != null ? linearClear.blue() : clearColorCopy.z(),
                clearColorCopy.w(),
                depth.nativeHandle(),
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                fence
        );
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put((MetalGpuTexture) depthTexture, clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination.buffer();
        int length = data.remaining();

        if (buffer.isDynamic()) {
            orphanWrite(buffer, destination.offset(), data);
            return;
        }

        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        MetalGpuBuffer stagingBuffer = (MetalGpuBuffer) staging.buffer();

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                stagingBuffer.nativeHandle(),
                staging.offset(),
                buffer.nativeHandle(),
                destination.offset(),
                length
        );
    }

    private void orphanWrite(final MetalGpuBuffer buffer, final long offset, final ByteBuffer data) {
        long size = buffer.allocationSize();
        int dataLength = data.remaining();
        long writeEnd = Math.addExact(offset, dataLength);
        if (offset < 0L || writeEnd > buffer.size()) {
            throw new IndexOutOfBoundsException(
                    "Dynamic buffer write is outside the logical buffer: offset=" + offset
                            + ", length=" + dataLength + ", size=" + buffer.size()
            );
        }
        MemorySegment old = buffer.nativeHandle();
        MemorySegment fresh = acquireDynamicBacking(size, buffer.resourceOptions());
        ByteBuffer freshStorage = MetalNativeBridge.nativeByteBufferView(
                MetalNativeBridge.metallum_get_buffer_contents(fresh), size).order(ByteOrder.nativeOrder());

        copyPreservedDynamicRanges(buffer.currentStorage(), freshStorage, offset, dataLength, buffer.size());

        ByteBuffer dst = freshStorage.duplicate().order(ByteOrder.nativeOrder());
        dst.position(Math.toIntExact(offset));
        dst.put(data.duplicate());
        buffer.swapBacking(fresh, freshStorage);
        if (this.workloadTelemetry != null) {
            // Prefix preservation + payload + suffix preservation write every
            // logical byte exactly once; backing alignment padding is untouched.
            this.workloadTelemetry.recordCpuToShared(buffer.size());
        }
        recycleDynamicBacking(old, size);
    }

    private MemorySegment acquireDynamicBacking(final long size, final long resourceOptions) {
        MemorySegment pooled = this.dynamicBackingPool.take(size);
        if (pooled != null) {
            return pooled;
        }
        MemorySegment handle = MetalNativeBridge.metallum_create_buffer(device.metalDeviceHandle(), size, resourceOptions);
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Failed to create dynamic backing buffer");
        }
        return handle;
    }

    private void recycleDynamicBacking(final MemorySegment handle, final long size) {
        queueForDestroy(() -> this.dynamicBackingPool.offer(handle, size));
    }

    static void copyPreservedDynamicRanges(
            final ByteBuffer previous,
            final ByteBuffer fresh,
            final long writeOffset,
            final int writeLength,
            final long logicalSize
    ) {
        long writeEnd = Math.addExact(writeOffset, writeLength);
        if (writeOffset < 0L || writeLength < 0 || writeEnd > logicalSize
                || logicalSize > previous.capacity() || logicalSize > fresh.capacity()) {
            throw new IndexOutOfBoundsException("Invalid dynamic buffer preservation range");
        }
        copyDynamicRange(previous, fresh, 0L, writeOffset);
        copyDynamicRange(previous, fresh, writeEnd, logicalSize);
    }

    private static void copyDynamicRange(
            final ByteBuffer source,
            final ByteBuffer destination,
            final long start,
            final long end
    ) {
        if (end <= start) {
            return;
        }
        int intStart = Math.toIntExact(start);
        int intEnd = Math.toIntExact(end);
        ByteBuffer sourceRange = source.duplicate();
        sourceRange.position(intStart);
        sourceRange.limit(intEnd);
        ByteBuffer destinationRange = destination.duplicate();
        destinationRange.position(intStart);
        destinationRange.put(sourceRange);
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        MetalGpuBuffer targetBuffer = (MetalGpuBuffer) target.buffer();
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                sourceBuffer.nativeHandle(),
                source.offset(),
                targetBuffer.nativeHandle(),
                target.offset(),
                source.length()
        );
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int pixelSize = metalDst.pixelSize();
        int rowBytes = width * pixelSize;
        int bytesPerImage = rowBytes * height;
        GpuBufferSlice slice = transientMemory.uploadStaging(source.duplicate().limit(bytesPerImage), pixelSize, GpuBuffer.USAGE_COPY_SRC);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                slice.offset(),
                metalDst.nativeHandle(),
                mipLevel,
                depthOrLayer,
                destX,
                destY,
                width,
                height,
                rowBytes,
                bytesPerImage
        );
    }

    @Override
    public void copyBufferToTexture(
            final @NonNull GpuBufferSlice source,
            final int sourceX,
            final int sourceY,
            final int sourceWidth,
            final int sourceHeight,
            final @NonNull GpuTexture destination,
            final int destinationX,
            final int destinationY,
            final int copyWidth,
            final int copyHeight,
            final int mipLevel,
            final int arrayLayer
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int texelSize = destination.getFormat().blockSize();
        long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
        long rowBytes = (long) sourceWidth * texelSize;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) source.buffer()).nativeHandle(),
                source.offset() + skipBytes,
                metalDst.nativeHandle(),
                mipLevel,
                arrayLayer,
                destinationX,
                destinationY,
                copyWidth,
                copyHeight,
                rowBytes,
                rowBytes * sourceHeight
        );
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
            final @NonNull GpuTexture source,
            final @NonNull GpuBuffer destination,
            final long offset,
            final @NonNull Runnable callback,
            final int mipLevel,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        MetalGpuTexture texture = (MetalGpuTexture) source;
        prepareTextureForRead(texture);
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination;
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToBuffer(
                texture.nativeHandle(),
                buffer.nativeHandle(),
                offset,
                mipLevel,
                0,
                x,
                y,
                width,
                height,
                rowBytes,
                bytesPerImage
        );
        queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(
            final @NonNull GpuTexture source,
            final @NonNull GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        MetalGpuTexture srcTexture = (MetalGpuTexture) source;
        MetalGpuTexture dstTexture = (MetalGpuTexture) destination;
        prepareTextureForRead(srcTexture);
        flushPendingClearForWrite(dstTexture);
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToTexture(
                srcTexture.nativeHandle(),
                dstTexture.nativeHandle(),
                mipLevel,
                sourceX,
                sourceY,
                destX,
                destY,
                width,
                height
        );
    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, currentSubmitIndex);
    }

    void queueForDestroy(final Runnable destroyAction) {
        destroyQueue.add(destroyAction);
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        if (submitIndex == currentSubmitIndex) {
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }
        for (InFlight f : inFlight) {
            if (f != null && f.index == submitIndex) {
                return MetalNativeBridge.metallum_semaphore_wait(f.completedSemaphore, Math.max(timeoutMs, 0L)) == 0;
            }
        }
        return true;
    }

    void close() {
        submitRenderPass();
        materializePendingUiSeed();
        endEncoder();
        for (int slot = 0; slot < inFlight.length; slot++) {
            InFlight f = inFlight[slot];
            if (f != null) {
                f.buffer.close();
                inFlight[slot] = null;
            }
        }
        for (int slot = 0; slot < submitSemaphores.length; slot++) {
            if (!MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                MetalNativeBridge.metallum_release_object(submitSemaphores[slot]);
                submitSemaphores[slot] = MemorySegment.NULL;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        transientMemory.close();
        resourceBindingPacket.close();
        device.queueResourceRelease(fence);
        destroyQueue.close();
        dynamicBackingPool.drain();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit >= MAX_SUBMITS_IN_FLIGHT) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    void waitForPreviouslySubmittedGpuWork() {
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit >= MAX_SUBMITS_IN_FLIGHT) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    long currentSubmitIndex() {
        return this.currentSubmitIndex;
    }

    void setGpuTimingStage(final MetalGpuTimingStage stage) {
        MetalGpuTimingStage next = stage == null ? MetalGpuTimingStage.NONE : stage;
        if (this.gpuTimingStage == next) {
            return;
        }
        submitRenderPass();
        endEncoder();
        this.gpuTimingStage = next;
    }

    MetalGpuTimingStage gpuTimingStage() {
        return this.gpuTimingStage;
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    private void flushPendingClearForWrite(final MetalGpuTexture texture) {
        materializePendingUiSeedForRead(texture);
        flushPendingClear(texture);
        texture.markContentsDirty();
    }

    boolean prepareTextureForRead(final MetalGpuTexture texture) {
        boolean restartedEncoder = materializePendingUiSeedForRead(texture);
        flushPendingClear(texture);
        return restartedEncoder;
    }

    void flushPendingClear(final MetalGpuTexture texture) {
        materializePendingUiSeedForRead(texture);
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        SceneLinearClearColor.Rgb linearClear = decodeClearRgbIfNeeded(texture, colorClear);
        if (texture.clearIsRedundant(colorClear, depthClear, linearClear != null)) {
            return;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorClear != null ? texture.nativeHandle() : null,
                null,
                depthClear != null ? texture.nativeHandle() : null,
                1.0, 1.0,
                colorClear != null ? 1 : 0,
                linearClear != null ? linearClear.red() : colorClear != null ? colorClear.x() : 0.0F,
                linearClear != null ? linearClear.green() : colorClear != null ? colorClear.y() : 0.0F,
                linearClear != null ? linearClear.blue() : colorClear != null ? colorClear.z() : 0.0F,
                colorClear != null ? colorClear.w() : 0.0F,
                0,
                depthClear != null ? 1 : 0,
                depthClear != null ? depthClear : 1.0,
                this.gpuTimingStage.nativeId()
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        texture.recordMaterializedClear(colorClear, depthClear, linearClear != null);
    }

    private static SceneLinearClearColor.Rgb decodeClearRgbIfNeeded(
            final MetalGpuTexture texture,
            @Nullable final Vector4fc clearColor
    ) {
        if (clearColor == null || !SceneLinearClearColor.shouldDecode(
                texture.getFormat() == GpuFormat.RGBA16_FLOAT,
                texture.hasSceneColorClearRole()
        )) {
            return null;
        }
        return SceneLinearClearColor.extendedSrgbToLinear(
                clearColor.x(),
                clearColor.y(),
                clearColor.z()
        );
    }

    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.mipLevels() >= textureView.texture().getMipLevels()
                && textureView.texture().getDepthOrLayers() == 1;
    }

    private static boolean isFullTextureRegion(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        return x == 0
                && y == 0
                && width == color.getWidth(0)
                && height == color.getHeight(0)
                && width == depth.getWidth(0)
                && height == depth.getHeight(0);
    }

    static boolean canFusePendingUiSeed(
            final boolean exactDestination,
            final boolean fullTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final int destinationWidth,
            final int destinationHeight,
            final boolean clearColorEnabled,
            final boolean semanticOutput,
            final long pendingSubmitIndex,
            final long activeSubmitIndex
    ) {
        return pendingSubmitIndex == activeSubmitIndex
                && exactDestination
                && fullTextureView
                && viewportWidth == destinationWidth
                && viewportHeight == destinationHeight
                && !clearColorEnabled
                && !semanticOutput;
    }

    static final class PendingUiSeedState<T> {
        @Nullable
        private T value;

        void arm(final T next) {
            if (next == null || this.value != null) {
                throw new IllegalStateException("Pending UI seed must be resolved before re-arming");
            }
            this.value = next;
        }

        @Nullable
        T peek() {
            return this.value;
        }

        boolean consume(final T expected) {
            if (expected == null || this.value != expected) {
                return false;
            }
            this.value = null;
            return true;
        }

        boolean isPending() {
            return this.value != null;
        }
    }

    private record PendingUiSeed(
            MetalGpuTexture source,
            MetalGpuTexture destination,
            MemorySegment sceneDepthTexture,
            MemorySegment semanticTexture,
            int sourceEncoding,
            boolean perceptualScalingEnabled,
            float currentHeadroom,
            float hdrStrength,
            float bloomStrength,
            long submitIndex
    ) {
        boolean canFuse(
                final MetalGpuTextureView colorTextureView,
                final int viewportWidth,
                final int viewportHeight,
                final boolean clearColorEnabled,
                final boolean semanticOutput,
                final long activeSubmitIndex
        ) {
            return canFusePendingUiSeed(
                    colorTextureView.texture() == destination,
                    isFullTextureView(colorTextureView),
                    viewportWidth,
                    viewportHeight,
                    destination.getWidth(0),
                    destination.getHeight(0),
                    clearColorEnabled,
                    semanticOutput,
                    submitIndex,
                    activeSubmitIndex
            );
        }
    }

    private record InFlight(long index, MTLCommandBuffer buffer, MemorySegment completedSemaphore) {
    }
}
