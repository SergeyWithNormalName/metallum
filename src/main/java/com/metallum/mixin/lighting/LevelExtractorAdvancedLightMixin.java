package com.metallum.mixin.lighting;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.BoundedDynamicLightCollector;
import com.metallum.client.lighting.BoundedEntityShadowProxyCollector;
import com.metallum.client.lighting.CameraHeldLightTracker;
import com.metallum.client.lighting.DirectLightFrustum;
import com.metallum.client.lighting.EntityShadowFilter;
import com.metallum.client.lighting.EntityShadowProxy;
import com.metallum.client.lighting.EntityShadowProxyRegistry;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.MinecraftLightPolicy;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.voxel.VoxelClipmapController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dynamic lights piggyback the existing entity iteration; no world rescan exists. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorAdvancedLightMixin {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Unique
    @Nullable
    private BoundedDynamicLightCollector metallum$dynamicLights;

    @Unique
    @Nullable
    private DeltaTracker metallum$dynamicLightDeltaTracker;

    @Unique
    @Nullable
    private BoundedEntityShadowProxyCollector metallum$entityShadowProxies;

    @Unique
    private final CameraHeldLightTracker metallum$cameraHeldLightTracker = new CameraHeldLightTracker();

    @Unique
    private long metallum$cameraHeldStableId;

    @Inject(
            method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V",
            at = @At("HEAD")
    )
    private void metallum$closePreviousLightWorld(
            @Nullable final ClientLevel next,
            final CallbackInfo ci
    ) {
        if (this.level != null && this.level != next) {
            AdvancedLightRegistry.global().closeWorld(this.level);
            EntityShadowProxyRegistry.global().closeWorld(this.level);
            VoxelClipmapController.global().closeWorld(this.level);
        }
        this.metallum$dynamicLights = null;
        this.metallum$dynamicLightDeltaTracker = null;
        this.metallum$entityShadowProxies = null;
        this.metallum$cameraHeldStableId = 0L;
        this.metallum$cameraHeldLightTracker.reset();
    }

    @Inject(
            method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V",
            at = @At("RETURN")
    )
    private void metallum$openNextLightWorld(
            @Nullable final ClientLevel next,
            final CallbackInfo ci
    ) {
        if (next != null && AdvancedLightingRuntime.shouldCollect()) {
            AdvancedLightRegistry registry = AdvancedLightRegistry.global();
            registry.observeHook(AdvancedLightRegistry.Hook.WORLD_LIFECYCLE);
            LightWorldToken token = registry.openWorld(next, metallum$dimensionId(next));
            EntityShadowProxyRegistry.global().openWorld(next, token);
            VoxelClipmapController.global().openWorld(next, metallum$dimensionId(next));
        }
    }

    @Inject(
            method = "onResourceManagerReload(Lnet/minecraft/server/packs/resources/ResourceManager;)V",
            at = @At("HEAD")
    )
    private void metallum$reloadAdvancedLightRegistry(
            final ResourceManager resourceManager,
            final CallbackInfo ci
    ) {
        if (this.level == null) {
            return;
        }
        if (AdvancedLightingRuntime.shouldCollect()) {
            AdvancedLightRegistry registry = AdvancedLightRegistry.global();
            registry.observeHook(AdvancedLightRegistry.Hook.RESOURCE_RELOAD);
            LightWorldToken token = registry.reloadWorld(this.level, metallum$dimensionId(this.level));
            EntityShadowProxyRegistry.global().openWorld(this.level, token);
            VoxelClipmapController.global().reloadWorld(this.level, metallum$dimensionId(this.level));
        } else {
            AdvancedLightRegistry.global().closeWorld(this.level);
            EntityShadowProxyRegistry.global().closeWorld(this.level);
            VoxelClipmapController.global().closeWorld(this.level);
        }
    }

    @Inject(
            method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("HEAD")
    )
    private void metallum$beginDynamicLightFrame(
            final Camera camera,
            final Frustum frustum,
            final DeltaTracker deltaTracker,
            final LevelRenderState output,
            final CallbackInfo ci
    ) {
        this.metallum$dynamicLights = null;
        this.metallum$dynamicLightDeltaTracker = null;
        this.metallum$entityShadowProxies = null;
        this.metallum$cameraHeldStableId = 0L;
        if (this.level == null || !AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        registry.observeHook(AdvancedLightRegistry.Hook.DYNAMIC_ENTITY);
        LightWorldToken token = registry.openWorld(this.level, metallum$dimensionId(this.level));
        this.metallum$dynamicLights = new BoundedDynamicLightCollector(
                token,
                AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS,
                light -> {
                    double radius = light.radius() + DirectLightFrustum.GUARD_BAND_BLOCKS;
                    return frustum.isVisible(new AABB(
                            light.x() - radius,
                            light.y() - radius,
                            light.z() - radius,
                            light.x() + radius,
                            light.y() + radius,
                            light.z() + radius
                    ));
                }
        );
        this.metallum$dynamicLightDeltaTracker = deltaTracker;
        this.metallum$entityShadowProxies = new BoundedEntityShadowProxyCollector(
                token,
                LocalVoxelShadowLayout.MAX_ENTITY_PROXIES,
                camera.position().x,
                camera.position().y,
                camera.position().z
        );
        this.metallum$offerCameraHeldLight(camera, deltaTracker, token);
    }

    @WrapOperation(
            method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"
            ),
            require = 1,
            allow = 1
    )
    private boolean metallum$extractDynamicLightBeforeVisibilityFilter(
            final LevelExtractor extractor,
            final Entity entity,
            final Frustum frustum,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final Operation<Boolean> original
    ) {
        BoundedDynamicLightCollector collector = this.metallum$dynamicLights;
        DeltaTracker deltaTracker = this.metallum$dynamicLightDeltaTracker;
        ClientLevel currentLevel = this.level;
        if (collector != null && deltaTracker != null && currentLevel != null) {
            try {
                float partialTick = deltaTracker.getGameTimeDeltaPartialTick(
                        !currentLevel.tickRateManager().isEntityFrozen(entity)
                );
                if (MinecraftLightPolicy.cameraHeldStableIdMatches(
                        entity, this.metallum$cameraHeldStableId, collector.world()
                )) {
                    // The local player's held item was injected before entity culling at the
                    // camera anchor, so this body-space duplicate must not compete with it.
                } else {
                    AdvancedLight light = MinecraftLightPolicy.entity(entity, partialTick, collector.world());
                    collector.offer(light);
                }
            } catch (Throwable failure) {
                this.metallum$dynamicLights = null;
                this.metallum$dynamicLightDeltaTracker = null;
                AdvancedLightRegistry.global().failClosed(
                        "dynamic entity light extraction failed",
                        failure
                );
            }
        }
        BoundedEntityShadowProxyCollector proxyCollector = this.metallum$entityShadowProxies;
        if (proxyCollector != null && EntityShadowFilter.isShadowCaster(entity)) {
            try {
                float partialTick = deltaTracker != null && currentLevel != null
                        ? deltaTracker.getGameTimeDeltaPartialTick(!currentLevel.tickRateManager().isEntityFrozen(entity))
                        : 0.0f;
                proxyCollector.offerAll(EntityShadowProxy.proxiesForEntity(
                        entity, partialTick, proxyCollector.world(), true
                ));
            } catch (Throwable ignored) {
                // Proxy extraction is isolated from the dynamic-light admission contract.
                EntityShadowProxyRegistry.global().failOpen(proxyCollector.world());
                this.metallum$entityShadowProxies = null;
            }
        }
        return original.call(extractor, entity, frustum, cameraX, cameraY, cameraZ);
    }

    @Inject(
            method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("RETURN")
    )
    private void metallum$commitDynamicLightFrame(
            final Camera camera,
            final Frustum frustum,
            final DeltaTracker deltaTracker,
            final LevelRenderState output,
            final CallbackInfo ci
    ) {
        BoundedDynamicLightCollector collector = this.metallum$dynamicLights;
        BoundedEntityShadowProxyCollector proxyCollector = this.metallum$entityShadowProxies;
        this.metallum$dynamicLights = null;
        this.metallum$dynamicLightDeltaTracker = null;
        this.metallum$entityShadowProxies = null;
        this.metallum$cameraHeldStableId = 0L;
        if (collector != null) {
            try {
                AdvancedLightRegistry.global().publishDynamicFrame(
                        collector.world(),
                        collector.finish(),
                        collector.offered()
                );
            } catch (IllegalStateException ignored) {
                // World/reload races deliberately drop the old dynamic frame.
            } catch (Throwable failure) {
                AdvancedLightRegistry.global().failClosed(
                        "dynamic light publication failed",
                        failure
                );
            }
        }
        if (proxyCollector != null) {
            try {
                EntityShadowProxyRegistry.global().publish(
                        proxyCollector.world(), proxyCollector.finish(), proxyCollector.offered()
                );
            } catch (IllegalStateException ignored) {
                // World/reload races deliberately drop the old optional proxy frame.
            } catch (Throwable ignored) {
                EntityShadowProxyRegistry.global().failOpen(proxyCollector.world());
            }
        }
    }

    @Unique
    private static String metallum$dimensionId(final ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    @Unique
    private void metallum$offerCameraHeldLight(
            final Camera camera,
            final DeltaTracker deltaTracker,
            final LightWorldToken world
    ) {
        BoundedDynamicLightCollector collector = this.metallum$dynamicLights;
        LocalPlayer player = Minecraft.getInstance().player;
        if (collector == null || player == null || camera.entity() != player) {
            return;
        }
        long attemptedStableId = 0L;
        try {
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(
                    !this.level.tickRateManager().isEntityFrozen(player)
            );
            long stableId = com.metallum.client.lighting.StableLightIds.entity(
                    world.dimensionId(), player.getUUID()
            );
            attemptedStableId = stableId;
            CameraHeldLightTracker.CameraPose pose = Minecraft.getInstance().options
                    .getCameraType().isFirstPerson()
                    ? metallum$firstPersonHeldPose(camera)
                    : metallum$thirdPersonHeldPose(player, partialTick);
            CameraHeldLightTracker.CameraHeldLightAnchor anchor = this.metallum$cameraHeldLightTracker
                    .update(
                            stableId,
                            pose,
                            MinecraftLightPolicy.selectedHandSideOffset(player),
                            System.nanoTime()
                    );
            AdvancedLight light = MinecraftLightPolicy.cameraHeld(player, partialTick, world, anchor);
            if (light != null) {
                collector.offer(light);
                this.metallum$cameraHeldStableId = light.stableId();
            }
        } catch (Throwable failure) {
            // If the local source was identified before the failure, keep it deduplicated from
            // the ordinary entity pass for this frame instead of publishing a differently
            // anchored replacement after the isolated camera-held path failed.
            this.metallum$cameraHeldStableId = attemptedStableId;
            this.metallum$cameraHeldLightTracker.reset();
            com.metallum.Metallum.LOGGER.warn(
                    "Skipping this frame's camera-held light after an isolated extraction failure",
                    failure
            );
        }
    }

    @Unique
    private static CameraHeldLightTracker.CameraPose metallum$firstPersonHeldPose(final Camera camera) {
        Vec3 position = camera.position();
        return new CameraHeldLightTracker.CameraPose(
                position.x, position.y, position.z,
                camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z(),
                camera.upVector().x(), camera.upVector().y(), camera.upVector().z(),
                -camera.leftVector().x(), -camera.leftVector().y(), -camera.leftVector().z()
        );
    }

    @Unique
    private static CameraHeldLightTracker.CameraPose metallum$thirdPersonHeldPose(
            final LocalPlayer player,
            final float partialTick
    ) {
        double x = player.xOld + (player.getX() - player.xOld) * partialTick;
        double y = player.yOld + (player.getY() - player.yOld) * partialTick
                + player.getBbHeight() * 0.55;
        double z = player.zOld + (player.getZ() - player.zOld) * partialTick;
        double yawRadians = Math.toRadians(player.getYRot(partialTick));
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        return new CameraHeldLightTracker.CameraPose(
                x, y, z,
                forwardX, 0.0, forwardZ,
                0.0, 1.0, 0.0,
                forwardZ, 0.0, -forwardX
        );
    }
}
