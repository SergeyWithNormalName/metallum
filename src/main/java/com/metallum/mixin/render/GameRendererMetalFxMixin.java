package com.metallum.mixin.render;

import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import com.metallum.client.metalfx.MetalFxTemporalScaling;
import com.metallum.client.metalfx.MetalFxUpscaling;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.renderer.temporal.FrameCapture;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import com.metallum.client.renderer.temporal.TemporalJitterProjection;
import com.metallum.client.renderer.temporal.TemporalResetEvents;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps world render targets low-resolution while window and GUI state stay native-resolution. */
@Mixin(GameRenderer.class)
abstract class GameRendererMetalFxMixin {
    @Shadow @Final
    private RenderTarget mainRenderTarget;
    @Shadow @Final
    private Minecraft minecraft;
    @Shadow @Final
    private GameRenderState gameRenderState;

    private final Matrix4f metallum$previousBaseProjection = new Matrix4f();
    private final Matrix4f metallum$cameraInverse = new Matrix4f();
    private final Matrix4f metallum$jitteredProjection = new Matrix4f();
    private final Matrix4f metallum$postProjectionTransform = new Matrix4f();
    private boolean metallum$hasPreviousBaseProjection;
    private Entity metallum$previousCameraEntity;
    private Object metallum$dimensionKey;
    private long metallum$dimensionIdentity;

    @Inject(method = "render", at = @At("HEAD"))
    private void metallum$applyDeferredScale(final CallbackInfo ci) {
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            // FI health can restore the user's ordinary scaler dimensions.
            // Poll before consuming the pending resize so the target and the
            // renderer generation change together in this same frame.
            device.refreshFrameInterpolationRuntimeHealthBeforeResize();
        }
        MetalFxUpscaling.updateDynamicResolution();
        int displayWidth = MetalFxUpscaling.configuredDisplayWidth(this.mainRenderTarget.width);
        int displayHeight = MetalFxUpscaling.configuredDisplayHeight(this.mainRenderTarget.height);
        if (MetalFxUpscaling.consumePendingResize()) {
            ((GameRenderer) (Object) this).resize(displayWidth, displayHeight);
        }
        if (!MetalFxUpscaling.isActive()) {
            if (device != null) {
                device.publishRendererGenerationState(displayWidth, displayHeight);
            }
            return;
        }
        MetalFxUpscaling.Dimensions dimensions = MetalFxUpscaling.effectiveDimensions(
                displayWidth,
                displayHeight
        );
        if (this.mainRenderTarget.width != dimensions.renderWidth()
                || this.mainRenderTarget.height != dimensions.renderHeight()) {
            ((GameRenderer) (Object) this).resize(displayWidth, displayHeight);
        }
        if (device != null) {
            device.publishRendererGenerationState(displayWidth, displayHeight);
        }
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void metallum$recordDisplaySize(final int width, final int height, final CallbackInfo ci) {
        MetalFxUpscaling.recordDisplaySize(width, height);
    }

    @Redirect(
            method = "resize",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;resize(II)V"
            )
    )
    private void metallum$resizeMainTarget(
            final RenderTarget target,
            final int displayWidth,
            final int displayHeight
    ) {
        MetalFxUpscaling.Dimensions dimensions = MetalFxUpscaling.effectiveDimensions(
                displayWidth,
                displayHeight
        );
        target.resize(dimensions.renderWidth(), dimensions.renderHeight());
    }

    @Redirect(
            method = "resize",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;resize(II)V"
            )
    )
    private void metallum$resizeLevelRenderer(
            final LevelRenderer renderer,
            final int displayWidth,
            final int displayHeight
    ) {
        MetalFxUpscaling.Dimensions dimensions = MetalFxUpscaling.effectiveDimensions(
                displayWidth,
                displayHeight
        );
        renderer.resize(dimensions.renderWidth(), dimensions.renderHeight());
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
                    ordinal = 0
            )
    )
    private GpuBufferSlice metallum$captureFinalWorldProjection(
            final ProjectionMatrixBuffer projectionBuffer,
            final Matrix4f finalProjection,
            final DeltaTracker deltaTracker
    ) {
        CameraRenderState camera = this.gameRenderState.levelRenderState.cameraRenderState;
        MetalDevice device = MetalDevice.getInstance();
        if (device != null && this.minecraft.level != null) {
            this.metallum$cameraInverse.set(camera.viewRotationMatrix).invert();
            Vec3 position = camera.pos;
            double deltaSeconds = deltaTracker.getRealtimeDeltaTicks() / 20.0;
            if (!camera.projectionMatrix.isFinite()
                    || !camera.viewRotationMatrix.isFinite()
                    || !finalProjection.isFinite()
                    || !this.metallum$cameraInverse.isFinite()
                    || !Double.isFinite(position.x)
                    || !Double.isFinite(position.y)
                    || !Double.isFinite(position.z)
                    || !Double.isFinite(deltaSeconds)
                    || deltaSeconds < 0.0
                    || !Float.isFinite(camera.depthFar)
                    || camera.depthFar <= 0.05f) {
                return projectionBuffer.getBuffer(finalProjection);
            }
            int displayWidth = MetalFxUpscaling.configuredDisplayWidth(this.mainRenderTarget.width);
            int displayHeight = MetalFxUpscaling.configuredDisplayHeight(this.mainRenderTarget.height);
            device.publishRendererGenerationState(displayWidth, displayHeight);
            if (this.metallum$hasPreviousBaseProjection
                    && !this.metallum$previousBaseProjection.equals(camera.projectionMatrix)) {
                TemporalResetEvents.signal(FrameState.HistoryResetReason.FOV_PROJECTION_CHANGE);
            }
            this.metallum$previousBaseProjection.set(camera.projectionMatrix);
            this.metallum$hasPreviousBaseProjection = true;

            Entity cameraEntity = this.minecraft.getCameraEntity();
            if (this.metallum$previousCameraEntity != null
                    && cameraEntity != this.metallum$previousCameraEntity) {
                TemporalResetEvents.signal(FrameState.HistoryResetReason.CAMERA_CUT);
            }
            this.metallum$previousCameraEntity = cameraEntity;

            float jitterX = 0f;
            float jitterY = 0f;
            if (MetalFxTemporalScaling.isActive()
                    || com.metallum.client.renderer.temporal.TemporalDiagnostics.configured()) {
                long nextFrameId = device.frameStateTracker().nextFrameId();
                com.metallum.client.renderer.temporal.FrameState.JitterOffset jitter =
                        com.metallum.client.renderer.temporal.JitterSequence.sample(
                                nextFrameId,
                                1.0,
                                this.mainRenderTarget.width,
                                this.mainRenderTarget.height,
                                displayWidth,
                                displayHeight
                        );
                jitterX = (float) jitter.x();
                jitterY = (float) jitter.y();
            }

            Matrix4f jitteredProjection = this.metallum$jitteredProjection;
            if (jitterX != 0f || jitterY != 0f) {
                // finalProjection is P * B, where B contains view bobbing and
                // portal effects. Applying the classic m20/m21 projection
                // jitter to P * B makes its pixel offset vary with B and scene
                // depth. Build P_jittered * B instead.
                TemporalJitterProjection.applyBeforePostProjection(
                        jitteredProjection,
                        camera.projectionMatrix,
                        finalProjection,
                        this.metallum$postProjectionTransform,
                        new FrameState.JitterOffset(jitterX, jitterY),
                        this.mainRenderTarget.width,
                        this.mainRenderTarget.height
                );
            } else {
                jitteredProjection.set(finalProjection);
            }

            Matrix4 view = matrix(camera.viewRotationMatrix);
            Matrix4 cameraMatrix = matrix(this.metallum$cameraInverse);
            Matrix4 projection = matrix(jitteredProjection);
            Matrix4 unjitteredProjection = matrix(finalProjection);
            FrameState.Transforms transforms = new FrameState.Transforms(
                    cameraMatrix,
                    view,
                    projection,
                    cameraMatrix,
                    view,
                    unjitteredProjection
            );
            Object dimensionKey = this.minecraft.level.dimension();
            if (dimensionKey != this.metallum$dimensionKey) {
                this.metallum$dimensionKey = dimensionKey;
                this.metallum$dimensionIdentity = stableIdentity(
                        this.minecraft.level.dimension().identifier().toString()
                );
            }
            device.publishFrameState(new FrameCapture(
                    transforms,
                    new FrameState.CameraPosition(position.x, position.y, position.z),
                    deltaSeconds,
                    0.05,
                    camera.depthFar,
                    Integer.toUnsignedLong(System.identityHashCode(this.minecraft.level)),
                    this.metallum$dimensionIdentity,
                    metallum$environmentDescriptor(camera, deltaTracker)
            ));
            return projectionBuffer.getBuffer(jitteredProjection);
        }
        return projectionBuffer.getBuffer(finalProjection);
    }

    private EnvironmentDescriptor metallum$environmentDescriptor(
            final CameraRenderState camera,
            final DeltaTracker deltaTracker
    ) {
        EnvironmentDescriptor.Medium medium = switch (camera.fogType) {
            case WATER -> EnvironmentDescriptor.Medium.WATER;
            case LAVA -> EnvironmentDescriptor.Medium.LAVA;
            case POWDER_SNOW -> EnvironmentDescriptor.Medium.POWDER_SNOW;
            default -> EnvironmentDescriptor.Medium.AIR;
        };
        SkyRenderState sky = this.gameRenderState.levelRenderState.skyRenderState;
        DimensionType.Skybox skybox = sky.skybox;
        float ambient = Math.max(this.minecraft.level.dimensionType().ambientLight(), 0.0f);
        if (skybox == DimensionType.Skybox.OVERWORLD) {
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            float rain = SurfaceMaterialPolicy.rainWetnessTarget(
                    this.minecraft.level.getRainLevel(partialTick));
            float thunder = this.minecraft.level.getThunderLevel(partialTick);
            // Minecraft 26.2 publishes data-driven sky intensity, tint, and ambient separately.
            // Keep the raw coefficients: EnvironmentDescriptor converts the reflected-diffuse
            // scale to the irradiance units consumed by the L4 Lambert shader.
            LightmapRenderState lightmap = this.gameRenderState.lightmapRenderState;
            Vector3fc skyLightTint = lightmap.skyLightColor;
            Vector3fc ambientLight = lightmap.ambientColor;
            MoonPhase phase = sky.moonPhase;
            float phaseBrightness = phase == null
                    ? 1.0f
                    : switch (phase) {
                        case FULL_MOON -> 1.0f;
                        case WANING_GIBBOUS, WAXING_GIBBOUS -> 0.75f;
                        case THIRD_QUARTER, FIRST_QUARTER -> 0.50f;
                        case WANING_CRESCENT, WAXING_CRESCENT -> 0.25f;
                        case NEW_MOON -> 0.08f;
                    };
            return EnvironmentDescriptor.celestial(
                    medium,
                    sky.sunAngle,
                    skyLightTint.x(),
                    skyLightTint.y(),
                    skyLightTint.z(),
                    lightmap.skyFactor,
                    ambientLight.x(),
                    ambientLight.y(),
                    ambientLight.z(),
                    rain,
                    thunder,
                    phaseBrightness
            );
        }
        if (skybox == DimensionType.Skybox.END) {
            return EnvironmentDescriptor.ambientOnly(
                    EnvironmentDescriptor.Profile.END,
                    medium,
                    0.11f + ambient * 0.35f,
                    0.075f + ambient * 0.25f,
                    0.16f + ambient * 0.45f
            );
        }
        // Do not read the remaining SkyRenderState fields here. Minecraft only resets skybox,
        // so those values may still describe the previous Overworld frame.
        float neutral = Math.max(ambient, 0.055f);
        return EnvironmentDescriptor.ambientOnly(
                EnvironmentDescriptor.Profile.AMBIENT_ONLY,
                medium,
                neutral * 1.04f,
                neutral,
                neutral * 0.94f
        );
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
            )
    )
    private void metallum$diagnoseWorldBeforeUiDepthClear(
            final CommandEncoder encoder,
            final GpuTexture depthTexture,
            final double clearDepth,
            final DeltaTracker deltaTracker
    ) {
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            device.encodeTemporalDiagnostics(depthTexture);
        }
        encoder.clearDepthTexture(depthTexture, clearDepth);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I",
                    ordinal = 0
            )
    )
    private int metallum$compareDisplayWidth(final RenderTarget target) {
        return target instanceof MainTarget && MetalFxUpscaling.isActive()
                ? MetalFxUpscaling.configuredDisplayWidth(target.width)
                : target.width;
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I",
                    ordinal = 0
            )
    )
    private int metallum$compareDisplayHeight(final RenderTarget target) {
        return target instanceof MainTarget && MetalFxUpscaling.isActive()
                ? MetalFxUpscaling.configuredDisplayHeight(target.height)
                : target.height;
    }

    private static Matrix4 matrix(final Matrix4f value) {
        return Matrix4.ofJoml(value);
    }

    private static long stableIdentity(final String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}
