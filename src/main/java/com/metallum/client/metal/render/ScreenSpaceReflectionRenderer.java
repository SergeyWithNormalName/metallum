package com.metallum.client.metal.render;

import com.metallum.client.lighting.reflection.WaterReflectionConfig;
import com.metallum.client.lighting.shader.AdvancedDirectLightingShaderPatcher;
import com.metallum.client.lighting.shader.PlanarReflectionBindingAbi;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * Encapsulates GPU resources and opaque scene acquisition for Screen-Space Reflections (SSR).
 *
 * <p>Confined strictly to the Render Thread. Responsible for capturing opaque scene color and
 * depth buffers between opaque world rendering and translucent water rasterization, and binding
 * them to the Metal render command encoder.</p>
 */
public final class ScreenSpaceReflectionRenderer {
    public static final String PROPERTY_CAPTURE_ONLY = "metallum.ssr.capture_only";

    private static TextureTarget target;
    private static int targetWidth;
    private static int targetHeight;
    private static GpuFormat targetFormat;
    private static boolean capturedThisFrame;
    private static boolean activeThisFrame;

    private static MetalGpuTexture fallbackDepthTexture;
    private static MetalGpuSampler linearClampSampler;
    private static MetalGpuSampler pointClampSampler;

    private ScreenSpaceReflectionRenderer() {
    }

    public static boolean shouldCapture() {
        return WaterReflectionConfig.isScreenSpaceActive()
                || Boolean.getBoolean(PROPERTY_CAPTURE_ONLY);
    }

    public static void beginFrame() {
        capturedThisFrame = false;
        activeThisFrame = false;
    }

    public static void captureOpaqueScene(@Nullable final RenderTarget source) {
        if (!shouldCapture() || capturedThisFrame || source == null) {
            return;
        }

        GpuTexture colorSource = source.getColorTexture();
        GpuTexture depthSource = source.getDepthTexture();
        if (colorSource == null || depthSource == null) {
            return;
        }

        int width = colorSource.getWidth(0);
        int height = colorSource.getHeight(0);
        if (width <= 0 || height <= 0) {
            return;
        }

        GpuFormat format = colorSource.getFormat();
        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return;
        }
        TextureTarget currentTarget = getOrCreateTarget(device, width, height, format);

        // Blit copy color and depth from source to SSR snapshot target
        device.commandEncoder.copyTextureToTexture(
                colorSource, currentTarget.getColorTexture(), 0, 0, 0, 0, 0, width, height
        );
        device.commandEncoder.copyTextureToTexture(
                depthSource, currentTarget.getDepthTexture(), 0, 0, 0, 0, 0, width, height
        );

        capturedThisFrame = true;
        activeThisFrame = true;
    }

    private static TextureTarget getOrCreateTarget(
            final MetalDevice device,
            final int width,
            final int height,
            final GpuFormat format
    ) {
        if (target == null || targetWidth != width || targetHeight != height || targetFormat != format) {
            if (target != null) {
                target.destroyBuffers();
            }
            target = device.createTrackedTextureTarget(
                    "Metallum SSR opaque scene snapshot",
                    width,
                    height,
                    true,
                    format
            );
            targetWidth = width;
            targetHeight = height;
            targetFormat = format;
        }
        return target;
    }

    private static void ensureStaticResources(final MetalDevice device) {
        if (linearClampSampler == null) {
            linearClampSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,
                    FilterMode.LINEAR,
                    1,
                    OptionalDouble.empty()
            );
        }
        if (pointClampSampler == null) {
            pointClampSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    OptionalDouble.empty()
            );
        }
        if (fallbackDepthTexture == null || fallbackDepthTexture.isClosed()) {
            fallbackDepthTexture = (MetalGpuTexture) device.createTexture(
                    "Metallum SSR fallback depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.D32_FLOAT,
                    1,
                    1,
                    1,
                    1
            );
            device.commandEncoder.clearDepthTexture(fallbackDepthTexture, 0.0);
        }
    }

    /**
     * Binds SSR color and depth textures to the active render encoder during the translucent pass.
     * When SSR capture is active, binds color to slot 11 and depth to slot 9. When inactive, binds
     * fallback depth to slot 9 so shader validation succeeds.
     */
    public static void bind(final MTLRenderCommandEncoder encoder) {
        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return;
        }
        ensureStaticResources(device);

        if (isCaptureActive() && target != null) {
            MetalGpuTexture color = (MetalGpuTexture) target.getColorTexture();
            MetalGpuTexture depth = (MetalGpuTexture) target.getDepthTexture();
            if (color != null && !color.isClosed() && depth != null && !depth.isClosed()) {
                encoder.setTextureAndSampler(
                        color.nativeHandle(),
                        linearClampSampler.nativeHandle(),
                        PlanarReflectionBindingAbi.TEXTURE_SLOT,
                        MetalCompiledRenderPipeline.STAGE_FRAGMENT
                );
                encoder.setTextureAndSampler(
                        depth.nativeHandle(),
                        pointClampSampler.nativeHandle(),
                        AdvancedDirectLightingShaderPatcher.REFLECTION_DEPTH_SLOT,
                        MetalCompiledRenderPipeline.STAGE_FRAGMENT
                );
                return;
            }
        }

        if (fallbackDepthTexture != null && !fallbackDepthTexture.isClosed()) {
            encoder.setTextureAndSampler(
                    fallbackDepthTexture.nativeHandle(),
                    pointClampSampler.nativeHandle(),
                    AdvancedDirectLightingShaderPatcher.REFLECTION_DEPTH_SLOT,
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT
            );
        }
    }

    public static boolean isCaptureActive() {
        return activeThisFrame && target != null;
    }

    public static @Nullable GpuTexture capturedColorTexture() {
        return target != null ? target.getColorTexture() : null;
    }

    public static @Nullable GpuTexture capturedDepthTexture() {
        return target != null ? target.getDepthTexture() : null;
    }

    public static @Nullable GpuTextureView capturedColorView() {
        return target != null ? target.getColorTextureView() : null;
    }

    public static @Nullable GpuTextureView capturedDepthView() {
        return target != null ? target.getDepthTextureView() : null;
    }

    public static @Nullable TextureTarget target() {
        return target;
    }

    public static void destroy() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
            targetWidth = 0;
            targetHeight = 0;
            targetFormat = null;
        }
        if (fallbackDepthTexture != null) {
            fallbackDepthTexture.close();
            fallbackDepthTexture = null;
        }
        if (linearClampSampler != null) {
            linearClampSampler.close();
            linearClampSampler = null;
        }
        if (pointClampSampler != null) {
            pointClampSampler.close();
            pointClampSampler = null;
        }
        capturedThisFrame = false;
        activeThisFrame = false;
    }
}
