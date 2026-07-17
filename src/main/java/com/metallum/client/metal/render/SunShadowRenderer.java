package com.metallum.client.metal.render;

import com.metallum.client.lighting.SunShadowFrame;
import com.metallum.client.sodium.SodiumShadowCompatibility;
import com.metallum.client.renderer.temporal.FrameState.CameraPosition;
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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Registers and executes the L4 shadow pass before Minecraft's ordinary main world pass. */
public final class SunShadowRenderer {
    @Nullable
    private static RenderTarget activeTarget;
    @Nullable
    private static Matrix4f activeTerrainProjection;
    @Nullable
    private static Matrix4f activeTerrainCasterProjection;
    @Nullable
    private static CameraPosition activeCameraPosition;
    @Nullable
    private static Vector3f activeTerrainToLightWorld;
    @Nullable
    private static Runnable activeTerrainCleanup;
    private static long activeCascadeToken;
    private static float activeRasterDepthBias;
    private static float activeRasterSlopeBias;

    private SunShadowRenderer() {
    }

    public static @Nullable FramePass addFramePass(
            final FrameGraphBuilder frameGraph,
            final FeatureRenderDispatcher.PreparedFrame featureFrame,
            final ChunkSectionsToRender terrain
    ) {
        if (!SodiumShadowCompatibility.supportsInstalledRenderer()) {
            return null;
        }
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

    /** Sodium keeps terrain matrices in its own per-frame uniform buffer. */
    public static @Nullable Matrix4fc activeTerrainProjection() {
        return activeTerrainProjection;
    }

    /** Camera-relative world projection used to collect off-camera terrain casters. */
    public static @Nullable Matrix4fc activeTerrainCasterProjection() {
        return activeTerrainCasterProjection;
    }

    public static @Nullable CameraPosition activeCameraPosition() {
        return activeCameraPosition;
    }

    public static @Nullable Vector3fc activeTerrainToLightWorld() {
        return activeTerrainToLightWorld;
    }

    /** Identifies one cascade so Sodium uploads once for its SOLID+CUTOUT pair. */
    public static long activeCascadeToken() {
        return activeCascadeToken;
    }

    /** Lets an exact-version renderer bridge restore its mutable terrain draw caches. */
    public static void registerTerrainCleanup(final Runnable cleanup) {
        if (!isRendering()) {
            throw new IllegalStateException("Terrain cleanup registered outside a shadow pass");
        }
        if (activeTerrainCleanup == null) {
            activeTerrainCleanup = cleanup;
        }
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
                Matrix4f shadowFromView = frame.shadowFromView(cascade);
                Matrix4f shadowFromWorldRelative = frame.shadowFromWorldRelative(cascade);
                activeTarget = target;
                activeTerrainProjection = shadowFromView;
                activeTerrainCasterProjection = shadowFromWorldRelative;
                activeCameraPosition = frame.cameraPosition();
                activeTerrainToLightWorld = frame.toLightWorld();
                activeCascadeToken = frame.submitIndex() * 8L + cascade + 1L;
                activeRasterDepthBias = frame.budget().rasterDepthBias();
                activeRasterSlopeBias = frame.budget().rasterSlopeBias();
                try {
                    RenderSystem.getDevice()
                            .createCommandEncoder()
                            .clearColorAndDepthTextures(
                                    target.getColorTexture(),
                                    new Vector4f(),
                                    target.getDepthTexture(),
                                    0.0
                            );
                    RenderSystem.setProjectionMatrix(
                            resources.projectionBuffer().getBuffer(shadowFromView),
                            ProjectionType.ORTHOGRAPHIC
                    );
                    try {
                        terrain.renderGroup(
                                ChunkSectionLayerGroup.OPAQUE,
                                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                        );
                    } finally {
                        cleanupTerrainDrawState();
                    }
                    RenderSystem.outputColorTextureOverride = target.getColorTextureView();
                    RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
                    featureFrame.executeSolid();
                } finally {
                    cleanupTerrainDrawState();
                    RenderSystem.outputColorTextureOverride = previousColorOverride;
                    RenderSystem.outputDepthTextureOverride = previousDepthOverride;
                    activeTarget = null;
                    activeTerrainProjection = null;
                    activeTerrainCasterProjection = null;
                    activeCameraPosition = null;
                    activeTerrainToLightWorld = null;
                    activeCascadeToken = 0L;
                }
            }
            resources.markRendered(frame.submitIndex());
            device.completeSunShadowFrame(frame.submitIndex());
        } catch (RuntimeException failure) {
            device.failSunShadowFrame(failure);
        } finally {
            cleanupTerrainDrawState();
            activeTarget = null;
            activeTerrainProjection = null;
            activeTerrainCasterProjection = null;
            activeCameraPosition = null;
            activeTerrainToLightWorld = null;
            activeCascadeToken = 0L;
            activeRasterDepthBias = 0.0f;
            activeRasterSlopeBias = 0.0f;
            RenderSystem.outputColorTextureOverride = previousColorOverride;
            RenderSystem.outputDepthTextureOverride = previousDepthOverride;
            RenderSystem.restoreProjectionMatrix();
            MetalGpuTiming.end();
        }
    }

    private static void cleanupTerrainDrawState() {
        Runnable cleanup = activeTerrainCleanup;
        activeTerrainCleanup = null;
        if (cleanup != null) {
            cleanup.run();
        }
    }
}
