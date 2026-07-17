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
            boolean staticRefresh = resources.requiresStaticRefresh(frame.submitIndex());
            SunShadowFrame staticFrame = resources.staticFrameForSubmit(frame.submitIndex());
            if (staticFrame == null) {
                throw new IllegalStateException("Sun-shadow cache lost its static frame");
            }
            for (int cascade = 0; cascade < frame.cascadeCount(); cascade++) {
                if (staticRefresh) {
                    RenderTarget target = resources.staticTarget(cascade);
                    activateTerrain(staticFrame, target, cascade);
                    try {
                        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                                target.getColorTexture(), new Vector4f(), target.getDepthTexture(), 0.0
                        );
                        RenderSystem.setProjectionMatrix(
                                resources.projectionBuffer().getBuffer(
                                        staticFrame.shadowFromView(cascade)
                                ), ProjectionType.ORTHOGRAPHIC
                        );
                        try {
                            terrain.renderGroup(
                                    ChunkSectionLayerGroup.OPAQUE,
                                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                            );
                        } finally {
                            cleanupTerrainDrawState();
                        }
                    } finally {
                        deactivateTarget();
                    }
                }
                RenderTarget working = resources.target(cascade);
                copyStaticCascade(resources.staticTarget(cascade), working);
                activateDynamic(frame, working);
                try {
                    RenderSystem.setProjectionMatrix(
                            resources.projectionBuffer().getBuffer(frame.shadowFromView(cascade)),
                            ProjectionType.ORTHOGRAPHIC
                    );
                    RenderSystem.outputColorTextureOverride = working.getColorTextureView();
                    RenderSystem.outputDepthTextureOverride = working.getDepthTextureView();
                    featureFrame.executeSolid();
                } finally {
                    deactivateTarget();
                }
            }
            resources.markRendered(frame.submitIndex());
            device.completeSunShadowFrame(frame.submitIndex());
        } catch (RuntimeException failure) {
            device.failSunShadowFrame(failure);
        } finally {
            deactivateTarget();
            activeRasterDepthBias = 0.0f;
            activeRasterSlopeBias = 0.0f;
            RenderSystem.outputColorTextureOverride = previousColorOverride;
            RenderSystem.outputDepthTextureOverride = previousDepthOverride;
            RenderSystem.restoreProjectionMatrix();
            MetalGpuTiming.end();
        }
    }

    private static void activateTerrain(
            final SunShadowFrame frame,
            final RenderTarget target,
            final int cascade
    ) {
        activeTarget = target;
        activeTerrainProjection = frame.shadowFromView(cascade);
        activeTerrainCasterProjection = frame.shadowFromWorldRelative(cascade);
        activeCameraPosition = frame.cameraPosition();
        activeTerrainToLightWorld = frame.toLightWorld();
        activeCascadeToken = frame.submitIndex() * 8L + cascade + 1L;
        activeRasterDepthBias = frame.reverseZRasterDepthBias();
        activeRasterSlopeBias = frame.reverseZRasterSlopeBias();
    }

    private static void activateDynamic(final SunShadowFrame frame, final RenderTarget target) {
        activeTarget = target;
        activeTerrainProjection = null;
        activeTerrainCasterProjection = null;
        activeCameraPosition = frame.cameraPosition();
        activeTerrainToLightWorld = frame.toLightWorld();
        activeCascadeToken = 0L;
        activeRasterDepthBias = frame.reverseZRasterDepthBias();
        activeRasterSlopeBias = frame.reverseZRasterSlopeBias();
    }

    private static void copyStaticCascade(final RenderTarget source, final RenderTarget destination) {
        int width = source.getColorTexture().getWidth(0);
        int height = source.getColorTexture().getHeight(0);
        if (width != destination.getColorTexture().getWidth(0)
                || height != destination.getColorTexture().getHeight(0)
                || source.getDepthTexture().getWidth(0) != destination.getDepthTexture().getWidth(0)
                || source.getDepthTexture().getHeight(0) != destination.getDepthTexture().getHeight(0)) {
            throw new IllegalStateException("Static and working shadow cascades have mismatched extents");
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                source.getColorTexture(), destination.getColorTexture(), 0, 0, 0, 0, 0, width, height
        );
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                source.getDepthTexture(), destination.getDepthTexture(), 0, 0, 0, 0, 0, width, height
        );
    }

    private static void deactivateTarget() {
        cleanupTerrainDrawState();
        activeTarget = null;
        activeTerrainProjection = null;
        activeTerrainCasterProjection = null;
        activeCameraPosition = null;
        activeTerrainToLightWorld = null;
        activeCascadeToken = 0L;
    }

    private static void cleanupTerrainDrawState() {
        Runnable cleanup = activeTerrainCleanup;
        activeTerrainCleanup = null;
        if (cleanup != null) {
            cleanup.run();
        }
    }
}
