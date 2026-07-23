package com.metallum.client.metal.render;

import com.metallum.client.renderer.temporal.FrameStatePacketRing;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Atomic three-slot private motion allocation owned by one physical extent.
 * Temporal adds its reactive attachment; standalone Spatial + FI deliberately
 * does not allocate one.
 */
final class TemporalDiagnosticResources implements AutoCloseable {
    record Pair(MetalGpuTexture motion, MetalGpuTexture reactive, MetalGpuTexture classification) {
    }

    private final Pair[] pairs = new Pair[FrameStatePacketRing.SLOT_COUNT];

    private TemporalDiagnosticResources() {
    }

    static TemporalDiagnosticResources create(
            final MetalDevice device,
            final int width,
            final int height,
            final boolean needsReactiveMask
    ) {
        TemporalDiagnosticResources resources = new TemporalDiagnosticResources();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        boolean debugVis = System.getenv("METALLUM_DEBUG_VISUALIZATION") != null;
        try {
            for (int slot = 0; slot < resources.pairs.length; slot++) {
                MetalGpuTexture motion = new MetalGpuTexture(
                        device, usage, "Metallum temporal diagnostic motion " + slot,
                        GpuFormat.RG16_FLOAT, width, height, 1, 1
                );
                MetalGpuTexture reactive = null;
                MetalGpuTexture classification = null;
                try {
                    if (needsReactiveMask) {
                        reactive = new MetalGpuTexture(
                                device, usage, "Metallum temporal diagnostic reactive " + slot,
                                GpuFormat.R8_UNORM, width, height, 1, 1
                        );
                    }
                    if (debugVis && needsReactiveMask) {
                        classification = new MetalGpuTexture(
                                device, usage, "Metallum temporal diagnostic classification " + slot,
                                GpuFormat.R8_UNORM, width, height, 1, 1
                        );
                    }
                    resources.pairs[slot] = new Pair(motion, reactive, classification);
                } catch (RuntimeException exception) {
                    motion.close();
                    if (reactive != null) {
                        reactive.close();
                    }
                    if (classification != null) {
                        classification.close();
                    }
                    throw exception;
                }
            }
            return resources;
        } catch (RuntimeException exception) {
            resources.close();
            throw exception;
        }
    }

    Pair pair(final int slot) {
        return this.pairs[slot];
    }

    @Override
    public void close() {
        for (int slot = 0; slot < this.pairs.length; slot++) {
            Pair pair = this.pairs[slot];
            if (pair != null) {
                if (pair.classification() != null) {
                    pair.classification().close();
                }
                if (pair.reactive() != null) {
                    pair.reactive().close();
                }
                pair.motion().close();
                this.pairs[slot] = null;
            }
        }
    }
}
