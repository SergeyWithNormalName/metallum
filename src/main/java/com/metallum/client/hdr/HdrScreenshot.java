package com.metallum.client.hdr;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Converts the gamma-encoded FP16 scene target into a conventional SDR PNG source. */
public final class HdrScreenshot {
    private HdrScreenshot() {
    }

    public static boolean capture(
            final RenderTarget target,
            final int downscale,
            final Consumer<NativeImage> callback
    ) {
        GpuTexture texture = target.getColorTexture();
        if (texture == null || texture.getFormat() != GpuFormat.RGBA16_FLOAT) {
            return false;
        }

        int width = target.width;
        int height = target.height;
        if (downscale <= 0 || width % downscale != 0 || height % downscale != 0) {
            throw new IllegalArgumentException("Image size is not divisible by downscale factor");
        }
        HdrSourceEncoding sourceEncoding = HdrSceneState.sourceEncoding();

        long bufferSize = Math.multiplyExact(Math.multiplyExact((long) width, height), GpuFormat.RGBA16_FLOAT.blockSize());
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "Metallum FP16 screenshot buffer",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                bufferSize
        );
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                    texture,
                    buffer,
                    0L,
                    () -> convert(buffer, width, height, downscale, sourceEncoding, callback),
                    0
            );
        } catch (RuntimeException exception) {
            buffer.close();
            throw exception;
        }
        return true;
    }

    private static void convert(
            final GpuBuffer buffer,
            final int sourceWidth,
            final int sourceHeight,
            final int downscale,
            final HdrSourceEncoding sourceEncoding,
            final Consumer<NativeImage> callback
    ) {
        try (GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
            ByteBuffer data = mapped.data().duplicate().order(ByteOrder.nativeOrder());
            int outputWidth = sourceWidth / downscale;
            int outputHeight = sourceHeight / downscale;
            NativeImage image = new NativeImage(outputWidth, outputHeight, false);

            for (int outputY = 0; outputY < outputHeight; outputY++) {
                for (int outputX = 0; outputX < outputWidth; outputX++) {
                    float red = 0.0f;
                    float green = 0.0f;
                    float blue = 0.0f;
                    for (int y = 0; y < downscale; y++) {
                        for (int x = 0; x < downscale; x++) {
                            int sourceX = outputX * downscale + x;
                            int sourceY = outputY * downscale + y;
                            int offset = (sourceX + sourceY * sourceWidth) * 8;
                            red += Float.float16ToFloat(data.getShort(offset));
                            green += Float.float16ToFloat(data.getShort(offset + 2));
                            blue += Float.float16ToFloat(data.getShort(offset + 4));
                        }
                    }
                    float divisor = downscale * downscale;
                    red /= divisor;
                    green /= divisor;
                    blue /= divisor;

                    if (sourceEncoding == HdrSourceEncoding.LINEAR) {
                        red = linearToSrgb(red);
                        green = linearToSrgb(green);
                        blue = linearToSrgb(blue);
                    }

                    int abgr = 0xff000000
                            | (toUnorm8(blue) << 16)
                            | (toUnorm8(green) << 8)
                            | toUnorm8(red);
                    image.setPixelABGR(outputX, outputHeight - outputY - 1, abgr);
                }
            }
            callback.accept(image);
        } finally {
            buffer.close();
        }
    }

    static float linearToSrgb(final float linear) {
        float value = Math.clamp(linear, 0.0f, 1.0f);
        return value <= 0.0031308f
                ? value * 12.92f
                : 1.055f * (float) Math.pow(value, 1.0 / 2.4) - 0.055f;
    }

    private static int toUnorm8(final float value) {
        return Math.round(Math.clamp(value, 0.0f, 1.0f) * 255.0f);
    }
}
