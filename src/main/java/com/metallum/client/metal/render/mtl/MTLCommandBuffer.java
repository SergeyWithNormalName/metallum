package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {
    public enum PresentResult {
        FAILED,
        NO_DRAWABLE,
        PRESENTED;

        private static PresentResult fromNative(final int value) {
            return switch (value) {
                case 1 -> PRESENTED;
                case 0 -> NO_DRAWABLE;
                default -> FAILED;
            };
        }
    }

    private MemorySegment handle;

    MTLCommandBuffer(final MemorySegment handle) {
        this.handle = handle;
    }

    public MTLBlitCommandEncoder makeBlitCommandEncoder() {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(handle());
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLBlitCommandEncoder");
        }
        return new MTLBlitCommandEncoder(encoder);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final MemorySegment semanticTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearSemanticEnabled,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoder(
                handle(),
                colorTexture,
                semanticTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearSemanticEnabled,
                clearDepthEnabled,
                clearDepth
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MemorySegment globalFence
    ) {
        MetalNativeBridge.MTLCommandBuffer_clearColorDepthTexturesRegion(
                handle(),
                colorTexture,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence
        );
    }

    public PresentResult encodePresentTextureToDrawable(
            final MemorySegment layer,
            final MemorySegment sourceTexture,
            final MemorySegment sceneTexture,
            final MemorySegment sceneDepthTexture,
            final MemorySegment semanticTexture,
            final MemorySegment uiTexture,
            final MemorySegment globalFence,
            final int outputMode,
            final int sourceEncoding,
            final boolean diagnosticPattern,
            final float currentHeadroom,
            final float hdrStrength,
            final float bloomStrength
    ) {
        return PresentResult.fromNative(MetalNativeBridge.MTLCommandBuffer_encodePresentTextureToDrawable(
                handle(),
                layer,
                sourceTexture,
                sceneTexture,
                sceneDepthTexture,
                semanticTexture,
                uiTexture,
                globalFence,
                outputMode,
                sourceEncoding,
                diagnosticPattern ? 1 : 0,
                currentHeadroom,
                hdrStrength,
                bloomStrength
        ));
    }

    public boolean encodeHdrUiBackdrop(
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final MemorySegment globalFence,
            final int sourceEncoding
    ) {
        return MetalNativeBridge.MTLCommandBuffer_encodeHdrUiBackdrop(
                handle(),
                sourceTexture,
                destinationTexture,
                globalFence,
                sourceEncoding
        );
    }

    public void commit() {
        MetalNativeBridge.MTLCommandBuffer_commit(handle());
    }

    public void commitWithSignal(final MemorySegment semaphore) {
        MetalNativeBridge.MTLCommandBuffer_commitWithSignal(handle(), semaphore);
    }

    public boolean isCompleted() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return true;
        }
        return MetalNativeBridge.MTLCommandBuffer_isCompleted(handle()) == 1;
    }

    public boolean waitUntilCompleted(final long timeoutMs) {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return true;
        }
        return MetalNativeBridge.MTLCommandBuffer_waitUntilCompleted(handle(), Math.max(timeoutMs, 0L)) == 0;
    }

    public void pushDebugGroup(final String label) {
        MetalNativeBridge.MTLCommandBuffer_pushDebugGroup(handle(), label);
    }

    public void popDebugGroup() {
        MetalNativeBridge.MTLCommandBuffer_popDebugGroup(handle());
    }

    public void close() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return;
        }
        MetalNativeBridge.metallum_release_object(handle);
        handle = MemorySegment.NULL;
    }

    private MemorySegment handle() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }
}
