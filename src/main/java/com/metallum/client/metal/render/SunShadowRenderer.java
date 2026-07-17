package com.metallum.client.metal.render;

import com.metallum.client.lighting.SunShadowFrame;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Registers and executes the L4 shadow pass before Minecraft's ordinary main world pass. */
public final class SunShadowRenderer {
    @Nullable
    private static RenderTarget activeTarget;
    private static float activeRasterDepthBias;
    private static float activeRasterSlopeBias;

    private SunShadowRenderer() {
    }

    public static @Nullable FramePass addFramePass(
            final FrameGraphBuilder frameGraph,
            final FeatureRenderDispatcher.PreparedFrame featureFrame,
            final ChunkSectionsToRender terrain
    ) {
        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return null;
        }
        SunShadowGpuResources resources = device.sunShadowResourcesForCurrentFrame();
        if (resources == null) {
            return null;
        }
        SunShadowFrame frame = resources.frameForSubmit(device.currentSubmitIndex());
        if (frame == null || !frame.needsShadowPass()) {
            return null;
        }
        FramePass pass = frameGraph.addPass("metallum_sun_shadow");
        pass.disableCulling();
        pass.executes(() -> render(device, resources, frame, featureFrame, terrain));
        return pass;
    }

    public static boolean isRendering() {
        return activeTarget != null;
    }

    public static @Nullable RenderTarget activeTarget() {
        return activeTarget;
    }

    static float activeRasterDepthBias() {
        return activeRasterDepthBias;
    }

    static float activeRasterSlopeBias() {
        return activeRasterSlopeBias;
    }

    private static void render(
            final MetalDevice device,
            final SunShadowGpuResources resources,
            final SunShadowFrame frame,
            final FeatureRenderDispatcher.PreparedFrame featureFrame,
            final ChunkSectionsToRender terrain
    ) {
        if (device.currentSubmitIndex() != frame.submitIndex()
                || resources.frameForSubmit(frame.submitIndex()) != frame) {
            device.failSunShadowFrame(
                    new IllegalStateException("Sun-shadow frame changed before execution")
            );
            return;
        }
        GpuTextureView previousColorOverride = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepthOverride = RenderSystem.outputDepthTextureOverride;
        RenderSystem.backupProjectionMatrix();
        MetalGpuTiming.begin(MetalGpuTimingStage.SUN_SHADOW);
        try {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
            for (int cascade = 0; cascade < frame.cascadeCount(); cascade++) {
                RenderTarget target = resources.target(cascade);
                activeTarget = target;
                activeRasterDepthBias = frame.budget().rasterDepthBias();
                activeRasterSlopeBias = frame.budget().rasterSlopeBias();
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .clearColorAndDepthTextures(
                                target.getColorTexture(),
                                new Vector4f(),
                                target.getDepthTexture(),
                                0.0
                        );
                RenderSystem.setProjectionMatrix(
                        resources.projectionBuffer().getBuffer(frame.shadowFromView(cascade)),
                        ProjectionType.ORTHOGRAPHIC
                );
                terrain.renderGroup(
                        ChunkSectionLayerGroup.OPAQUE,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                );
                RenderSystem.outputColorTextureOverride = target.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
                featureFrame.executeSolid();
                RenderSystem.outputColorTextureOverride = previousColorOverride;
                RenderSystem.outputDepthTextureOverride = previousDepthOverride;
                activeTarget = null;
            }
            resources.markRendered(frame.submitIndex());
            device.completeSunShadowFrame(frame.submitIndex());
        } catch (RuntimeException failure) {
            device.failSunShadowFrame(failure);
        } finally {
            activeTarget = null;
            activeRasterDepthBias = 0.0f;
            activeRasterSlopeBias = 0.0f;
            RenderSystem.outputColorTextureOverride = previousColorOverride;
            RenderSystem.outputDepthTextureOverride = previousDepthOverride;
            RenderSystem.restoreProjectionMatrix();
            MetalGpuTiming.end();
        }
    }
}
