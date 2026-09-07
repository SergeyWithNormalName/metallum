package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.lighting.reflection.CloudReflectionConfig;
import com.metallum.client.lighting.water.WaterCausticsPolicy;
import com.metallum.client.renderer.MetallumRenderContext;
import com.metallum.client.renderer.temporal.FrameState;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import com.metallum.client.renderer.temporal.FrameState.CameraPosition;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Orchestrates the secondary Planar Reflection render pass for water surfaces.
 * Renders sky and opaque terrain with a mirrored camera around the water surface plane.
 */
public final class PlanarReflectionRenderer {
    @Nullable
    private static PlanarReflectionGpuResources resources;
    @Nullable
    private static RenderTarget activeTarget;
    @Nullable
    private static Matrix4f activeTerrainProjection;
    @Nullable
    private static Matrix4f activeTerrainModelView;
    @Nullable
    private static Matrix4f activeTerrainVisibilityProjection;
    @Nullable
    private static CameraPosition activeCameraPosition;
    @Nullable
    private static Runnable activeTerrainCleanup;
    private static long activeTerrainToken;
    private static double activeWaterSurfaceY = 64.0;
    private static boolean activePassRendered;
    private static int framesSinceReflectionUpdate = Integer.MAX_VALUE;
    @Nullable
    private static RenderTarget lastRenderedTarget;
    private static PlanarReflectionConfig.CaptureMode lastRenderedMode =
            PlanarReflectionConfig.CaptureMode.DISABLED;
    private static boolean activationLogged;
    private static boolean failureLogged;
    private static boolean voxelConflictLogged;
    private static boolean activeCloudDraw;
    private static boolean lastCloudReflectionsEnabled = true;

    private PlanarReflectionRenderer() {
    }

    public static @Nullable FramePass addFramePass(
            final FrameGraphBuilder frameGraph,
            final ChunkSectionsToRender terrain,
            final LevelRenderState levelRenderState,
            final GpuBufferSlice terrainFog
    ) {
        boolean cloudReflectionsEnabled = CloudReflectionConfig.isEnabled();
        PlanarReflectionConfig.CaptureMode captureMode = PlanarReflectionConfig.captureMode();
        if (cloudReflectionsEnabled != lastCloudReflectionsEnabled) {
            // Do not reuse a target containing the composition selected before the live toggle.
            activePassRendered = false;
            framesSinceReflectionUpdate = Integer.MAX_VALUE;
            lastCloudReflectionsEnabled = cloudReflectionsEnabled;
        }
        if (captureMode == PlanarReflectionConfig.CaptureMode.DISABLED) {
            activePassRendered = false;
            return null;
        }
        if (captureMode == PlanarReflectionConfig.CaptureMode.CLOUDS_ONLY) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.options.cloudStatus().get() == CloudStatus.OFF
                    || !Float.isFinite(levelRenderState.cloudHeight)) {
                activePassRendered = false;
                return null;
            }
            if (!voxelConflictLogged) {
                voxelConflictLogged = true;
                Metallum.LOGGER.info(
                        "Voxel water reflection active: using cloud-only reflected capture; "
                                + "reflected terrain remains disabled"
                );
            }
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            activePassRendered = false;
            return null;
        }

        CameraRenderState camera = levelRenderState.cameraRenderState;
        if (camera == null || camera.pos == null) {
            activePassRendered = false;
            return null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            activePassRendered = false;
            return null;
        }

        double waterSurfaceY = resolveWaterSurfaceY(
                minecraft.level,
                camera.pos.x,
                camera.pos.y,
                camera.pos.z
        );
        activeWaterSurfaceY = waterSurfaceY;

        // Skip pass if camera is submerged or on/below the water plane
        if (camera.pos.y <= waterSurfaceY) {
            activePassRendered = false;
            return null;
        }

        if (resources == null) {
            resources = new PlanarReflectionGpuResources(device);
        }

        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        RenderTarget target = resources.getOrCreateTarget(
                mainTarget.width,
                mainTarget.height,
                PlanarReflectionConfig.resolutionScale()
        );

        if (target != lastRenderedTarget || captureMode != lastRenderedMode) {
            // A resized/recreated target contains no usable historical reflection.
            activePassRendered = false;
            framesSinceReflectionUpdate = Integer.MAX_VALUE;
        }
        if (mayReuseCachedCapture(
                captureMode,
                activePassRendered,
                framesSinceReflectionUpdate,
                PlanarReflectionConfig.updateIntervalFrames()
        )) {
            framesSinceReflectionUpdate++;
            // The cached world reflection stays valid for this one frame.  Water's normal and
            // UV perturbation still run in the main translucent pass every frame, so waves never
            // freeze; only the expensive sky/opaque terrain capture is limited to 30 Hz.
            return null;
        }

        FramePass pass = frameGraph.addPass(
                captureMode == PlanarReflectionConfig.CaptureMode.CLOUDS_ONLY
                        ? "metallum_cloud_reflection"
                        : "metallum_planar_reflection"
        );
        pass.disableCulling();
        pass.executes(() -> render(
                device,
                resources,
                target,
                terrain,
                levelRenderState,
                terrainFog,
                camera,
                waterSurfaceY,
                captureMode
        ));
        return pass;
    }

    public static boolean isRendering() {
        return activeTarget != null;
    }

    /** True only while Minecraft's cloud mesh is being drawn into the mirrored target. */
    public static boolean isRenderingCloudDraw() {
        return activeCloudDraw;
    }

    public static @Nullable RenderTarget activeTarget() {
        return activeTarget;
    }

    public static @Nullable Matrix4fc activeTerrainProjection() {
        return activeTerrainProjection;
    }

    public static @Nullable Matrix4fc activeTerrainModelView() {
        return activeTerrainModelView;
    }

    /** Camera-relative clip transform used to collect every reflected visible section. */
    public static @Nullable Matrix4fc activeTerrainVisibilityProjection() {
        return activeTerrainVisibilityProjection;
    }

    public static @Nullable CameraPosition activeCameraPosition() {
        return activeCameraPosition;
    }

    /** Identifies one reflection pass so Sodium keeps its draw batch separate from the main view. */
    public static long activeTerrainToken() {
        return activeTerrainToken;
    }

    /** Kept as a source-compatible name for the original prototype bridge. */
    public static long activeCascadeToken() {
        return activeTerrainToken;
    }

    /** Lets the exact-version Sodium bridge release its temporary reflected-frustum list. */
    public static void registerTerrainCleanup(final Runnable cleanup) {
        if (!isRendering()) {
            throw new IllegalStateException("Terrain cleanup registered outside a planar-reflection pass");
        }
        if (activeTerrainCleanup == null) {
            activeTerrainCleanup = cleanup;
        }
    }

    public static double activeWaterSurfaceY() {
        return activeWaterSurfaceY;
    }

    public static boolean isActivePassRendered() {
        return activePassRendered;
    }

    public static @Nullable PlanarReflectionGpuResources currentResources() {
        return resources;
    }

    public static void init(final MetalDevice device) {
        if (resources != null) {
            resources.close();
        }
        resources = new PlanarReflectionGpuResources(device);
    }

    public static void bind(final com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder encoder) {
        if (resources != null) {
            // The offscreen pass writes this texture.  Do not create a same-pass texture read
            // hazard when Advanced terrain pipelines are bound for that pass.
            resources.bind(encoder, isRendering() || !activePassRendered);
        }
        ScreenSpaceReflectionRenderer.bind(encoder);
    }

    private static void render(
            final MetalDevice device,
            final PlanarReflectionGpuResources resources,
            final RenderTarget target,
            final ChunkSectionsToRender terrain,
            final LevelRenderState levelRenderState,
            final GpuBufferSlice terrainFog,
            final CameraRenderState camera,
            final double waterSurfaceY,
            final PlanarReflectionConfig.CaptureMode captureMode
    ) {
        GpuTextureView previousColorOverride = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepthOverride = RenderSystem.outputDepthTextureOverride;
        RenderSystem.backupProjectionMatrix();
        MetalGpuTiming.begin(MetalGpuTimingStage.WORLD_OPAQUE);

        try (var scope = MetallumRenderContext.push(MetallumRenderContext.REFLECTION_PASS)) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);

            // Compute reflected model-view and oblique projection matrices
            // Chunk vertices are camera-relative, but their shaders consume the current
            // RenderSystem model-view matrix.  Start from that exact matrix (rather than a
            // camera-state approximation) so view bobbing and any renderer-side adjustment
            // are reflected with the terrain as well.
            Matrix4f mainModelView = RenderSystem.getModelViewMatrixCopy();
            Matrix4f reflModelView = computeReflectedModelView(
                    mainModelView,
                    camera.pos.y,
                    waterSurfaceY
            );
            Matrix4f obliqueProjection = computeObliqueProjection(
                    camera.projectionMatrix,
                    reflModelView,
                    camera.pos.y,
                    waterSurfaceY
            );

            activeTarget = target;
            activeTerrainProjection = obliqueProjection;
            activeTerrainModelView = reflModelView;
            activeTerrainVisibilityProjection = new Matrix4f(obliqueProjection).mul(reflModelView);
            activeCameraPosition = new CameraPosition(camera.pos.x, camera.pos.y, camera.pos.z);
            activeTerrainToken = device.currentSubmitIndex() * 16L + 7L;

            try {
                // FULL_PLANAR owns the whole viewport, while CLOUDS_ONLY deliberately preserves
                // transparent alpha outside actual cloud geometry. The voxel receiver can then
                // overlay real Minecraft clouds on its analytic sky without importing reflected
                // terrain or a second world-reflection architecture.
                float clearAlpha = targetClearAlpha(captureMode);
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        target.getColorTexture(),
                        new Vector4f(0.0f, 0.0f, 0.0f, clearAlpha),
                        target.getDepthTexture(),
                        targetClearDepth()
                );

                // Set both halves of the default terrain uniform set.  Merely publishing the
                // reflected matrix to Sodium is insufficient for the reusable vanilla terrain
                // draw groups used by this pass.
                Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
                modelViewStack.pushMatrix();
                modelViewStack.set(reflModelView);
                RenderSystem.setProjectionMatrix(
                        resources.projectionBuffer().getBuffer(obliqueProjection),
                        ProjectionType.PERSPECTIVE
                );

                try {
                    RenderSystem.outputColorTextureOverride = target.getColorTextureView();
                    RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();

                    if (rendersReflectedWorld(captureMode)) {
                        renderSky(minecraft, levelRenderState, terrainFog);
                        try {
                            // This enters Sodium with a reflected-camera list built from all loaded
                            // sections intersecting the mirror frustum, rather than reusing the
                            // primary camera's visibility result. Water itself remains excluded to
                            // avoid recursive blending.
                            terrain.renderGroup(
                                    ChunkSectionLayerGroup.OPAQUE,
                                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                            );
                        } finally {
                            cleanupTerrainDrawState();
                        }
                    }
                    // CloudRenderer already expresses its mesh relative to the reflected camera
                    // passed below. Reusing the terrain matrix would apply the water-plane Y
                    // translation a second time. Clouds also must retain the ordinary perspective
                    // projection: the terrain-only oblique near plane clips their mirrored image.
                    modelViewStack.set(computeReflectedCloudModelView(mainModelView));
                    RenderSystem.setProjectionMatrix(
                            resources.projectionBuffer().getBuffer(camera.projectionMatrix),
                            ProjectionType.PERSPECTIVE
                    );
                    RenderSystem.setShaderFog(terrainFog);
                    if (rendersReflectedClouds(
                            captureMode,
                            CloudReflectionConfig.isEnabled()
                    )) {
                        renderClouds(minecraft, levelRenderState, camera, waterSurfaceY);
                    }
                    activePassRendered = true;
                    lastRenderedTarget = target;
                    lastRenderedMode = captureMode;
                    framesSinceReflectionUpdate = 0;
                    if (!activationLogged) {
                        activationLogged = true;
                        String label = captureMode == PlanarReflectionConfig.CaptureMode.CLOUDS_ONLY
                                ? "Cloud-only water reflection capture active"
                                : "Live water planar reflection active";
                        Metallum.LOGGER.info(
                                "{}: target={}x{}, scale={}, waterY={}",
                                label,
                                target.getColorTexture().getWidth(0),
                                target.getColorTexture().getHeight(0),
                                PlanarReflectionConfig.resolutionScale(), waterSurfaceY
                        );
                    }
                } finally {
                    modelViewStack.popMatrix();
                }
            } finally {
                deactivateTarget();
            }
        } catch (Throwable failure) {
            activePassRendered = false;
            lastRenderedTarget = null;
            framesSinceReflectionUpdate = Integer.MAX_VALUE;
            if (!failureLogged) {
                failureLogged = true;
                Metallum.LOGGER.warn("Live water planar reflection pass failed; keeping analytic-water fallback", failure);
            }
        } finally {
            RenderSystem.outputColorTextureOverride = previousColorOverride;
            RenderSystem.outputDepthTextureOverride = previousDepthOverride;
            RenderSystem.restoreProjectionMatrix();
            MetalGpuTiming.end();
        }
    }

    private static void renderSky(
            final Minecraft minecraft,
            final LevelRenderState levelRenderState,
            final GpuBufferSlice terrainFog
    ) {
        SkyRenderState sky = levelRenderState.skyRenderState;
        if (sky == null) {
            return;
        }
        RenderSystem.setShaderFog(terrainFog);
        SkyRenderer renderer = minecraft.levelRenderer.skyRenderer();
        if (sky.skybox == DimensionType.Skybox.END) {
            renderer.renderEndSky();
            if (sky.endFlashIntensity > 1.0e-5f) {
                renderer.renderEndFlash(
                        new PoseStack(), sky.endFlashIntensity, sky.endFlashXAngle, sky.endFlashYAngle
                );
            }
            return;
        }

        PoseStack poseStack = new PoseStack();
        renderer.renderSkyDisc(sky.skyColor);
        renderer.renderSunriseAndSunset(poseStack, sky.sunAngle, sky.sunriseAndSunsetColor);
        renderer.renderSunMoonAndStars(
                poseStack,
                sky.sunAngle,
                sky.moonAngle,
                sky.starAngle,
                sky.moonPhase,
                sky.rainBrightness,
                sky.starBrightness
        );
        if (sky.shouldRenderDarkDisc) {
            renderer.renderDarkDisc();
        }
    }

    private static void renderClouds(
            final Minecraft minecraft,
            final LevelRenderState levelRenderState,
            final CameraRenderState camera,
            final double waterSurfaceY
    ) {
        CloudStatus cloudStatus = minecraft.options.cloudStatus().get();
        if (cloudStatus == CloudStatus.OFF || !Float.isFinite(levelRenderState.cloudHeight)) {
            return;
        }
        Vec3 reflectedCamera = new Vec3(
                camera.pos.x,
                2.0 * waterSurfaceY - camera.pos.y,
                camera.pos.z
        );
        var cloudRenderer = minecraft.levelRenderer.cloudRenderer();
        activeCloudDraw = true;
        try {
            cloudRenderer.render(
                    levelRenderState.cloudColor,
                    cloudStatus,
                    levelRenderState.cloudHeight,
                    minecraft.options.cloudRange().get(),
                    reflectedCamera,
                    levelRenderState.gameTime,
                    minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)
            );
            // CloudRenderer normally rotates this dynamic UBO once after the main cloud draw. The
            // reflected draw happens earlier in the same frame, so rotate here as well; otherwise
            // the main draw overwrites CloudInfo while the GPU may still consume the reflected
            // command. Mesh/UTB data remains shared read-only unless CloudRenderer rebuilds it.
            cloudRenderer.endFrame();
        } finally {
            activeCloudDraw = false;
        }
    }

    private static void deactivateTarget() {
        cleanupTerrainDrawState();
        activeCloudDraw = false;
        activeTarget = null;
        activeTerrainProjection = null;
        activeTerrainModelView = null;
        activeTerrainVisibilityProjection = null;
        activeCameraPosition = null;
        activeTerrainToken = 0L;
    }

    private static void cleanupTerrainDrawState() {
        Runnable cleanup = activeTerrainCleanup;
        activeTerrainCleanup = null;
        if (cleanup != null) {
            cleanup.run();
        }
    }

    static boolean rendersReflectedWorld(final PlanarReflectionConfig.CaptureMode captureMode) {
        return captureMode == PlanarReflectionConfig.CaptureMode.FULL_PLANAR;
    }

    static boolean rendersReflectedClouds(
            final PlanarReflectionConfig.CaptureMode captureMode,
            final boolean cloudReflectionsEnabled
    ) {
        return captureMode != PlanarReflectionConfig.CaptureMode.DISABLED
                && cloudReflectionsEnabled;
    }

    static float targetClearAlpha(final PlanarReflectionConfig.CaptureMode captureMode) {
        return captureMode == PlanarReflectionConfig.CaptureMode.CLOUDS_ONLY ? 0.0f : 1.0f;
    }

    /** Minecraft 26.2 uses reverse-Z with GREATER_THAN_OR_EQUAL depth testing. */
    static double targetClearDepth() {
        return 0.0;
    }

    static boolean mayReuseCachedCapture(
            final PlanarReflectionConfig.CaptureMode captureMode,
            final boolean captureReady,
            final int framesSinceUpdate,
            final int updateIntervalFrames
    ) {
        return captureMode == PlanarReflectionConfig.CaptureMode.FULL_PLANAR
                && captureReady
                && framesSinceUpdate + 1 < updateIntervalFrames;
    }

    /**
     * Reflects the camera's modelview matrix across the horizontal water plane at waterSurfaceY.
     * In camera-relative chunk space, this mirrors the Y axis and offsets by 2 * (waterSurfaceY - cameraY).
     */
    public static double resolveWaterSurfaceY(
            final net.minecraft.world.level.Level level,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        if (level == null) {
            return 64.0;
        }
        int blockX = (int) Math.floor(cameraX);
        int blockY = (int) Math.floor(cameraY);
        int blockZ = (int) Math.floor(cameraZ);
        int seaLevel = level.getSeaLevel();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos(blockX, blockY, blockZ);

        // 1. Scan downward from camera to find water in the same vertical column
        int minY = Math.max(level.getMinY(), seaLevel - 32);
        for (int y = blockY; y >= minY; y--) {
            pos.set(blockX, y, blockZ);
            net.minecraft.world.level.material.FluidState fluid = level.getFluidState(pos);
            if (fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                return y + fluid.getHeight(level, pos);
            }
        }

        // 2. If camera is over land (e.g. shore, mountain), scan horizontal perimeter at seaLevel
        for (int r = 1; r <= 4; r++) {
            int step = r * 8;
            for (int dx = -step; dx <= step; dx += step) {
                for (int dz = -step; dz <= step; dz += step) {
                    pos.set(blockX + dx, seaLevel, blockZ + dz);
                    net.minecraft.world.level.material.FluidState fluid = level.getFluidState(pos);
                    if (fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                        return seaLevel + fluid.getHeight(level, pos);
                    }
                }
            }
        }

        // 3. Fallback: world sea level (surface is at seaLevel + 8/9 approx 0.89)
        return (double) seaLevel + 0.8888889;
    }

    public static Matrix4f computeReflectedModelView(
            final Matrix4fc mainModelView,
            final double cameraY,
            final double waterSurfaceY
    ) {
        float deltaY = (float) (2.0 * (waterSurfaceY - cameraY));
        Matrix4f mirror = new Matrix4f(
                1.0f,  0.0f, 0.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f,
                0.0f,  0.0f, 1.0f, 0.0f,
                0.0f, deltaY, 0.0f, 1.0f
        );
        return new Matrix4f(mainModelView).mul(mirror);
    }

    /**
     * Mirrors camera-relative cloud geometry without translating it a second time.
     * CloudRenderer's CloudInfo already places its mesh relative to the reflected camera.
     */
    public static Matrix4f computeReflectedCloudModelView(final Matrix4fc mainModelView) {
        return new Matrix4f(mainModelView).scale(1.0f, -1.0f, 1.0f);
    }

    /**
     * Modifies the perspective projection matrix using Eric Lengyel's oblique near-plane
     * clipping formulation, clipping all geometry below the horizontal water plane at waterSurfaceY.
     */
    public static Matrix4f computeObliqueProjection(
            final Matrix4fc mainProjection,
            final Matrix4fc reflModelView,
            final double cameraY,
            final double waterSurfaceY
    ) {
        float planeD = (float) -(waterSurfaceY - cameraY);
        Vector4f worldPlane = new Vector4f(0.0f, 1.0f, 0.0f, planeD);

        Matrix4f invTransModelView = new Matrix4f(reflModelView).invert().transpose();
        Vector4f clipPlane = new Vector4f();
        invTransModelView.transform(worldPlane, clipPlane);

        if (clipPlane.w > 0.0f) {
            clipPlane.negate();
        }

        Matrix4f obliqueProj = new Matrix4f(mainProjection);
        Matrix4f invProj = new Matrix4f(mainProjection).invert();

        Vector4f q = new Vector4f(
                Math.signum(clipPlane.x),
                Math.signum(clipPlane.y),
                1.0f,
                1.0f
        );
        invProj.transform(q);

        float dot = clipPlane.dot(q);
        if (Math.abs(dot) > 1e-6f) {
            float scale = 1.0f / dot;
            Vector4f c = new Vector4f(clipPlane).mul(scale);

            obliqueProj.m02(c.x);
            obliqueProj.m12(c.y);
            obliqueProj.m22(c.z);
            obliqueProj.m32(c.w);
        }
        return obliqueProj;
    }

    public static void close() {
        if (resources != null) {
            resources.close();
            resources = null;
        }
        activeTarget = null;
        activeTerrainProjection = null;
        activeTerrainModelView = null;
        activeTerrainVisibilityProjection = null;
        activeCameraPosition = null;
        activeTerrainToken = 0L;
        activeTerrainCleanup = null;
        activeCloudDraw = false;
        activePassRendered = false;
        framesSinceReflectionUpdate = Integer.MAX_VALUE;
        lastRenderedTarget = null;
        lastRenderedMode = PlanarReflectionConfig.CaptureMode.DISABLED;
        lastCloudReflectionsEnabled = true;
        activationLogged = false;
        failureLogged = false;
    }
}
