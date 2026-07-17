package com.metallum.mixin.lighting;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.BoundedDynamicLightCollector;
import com.metallum.client.lighting.BoundedEntityShadowProxyCollector;
import com.metallum.client.lighting.DirectLightFrustum;
import com.metallum.client.lighting.EntityShadowProxy;
import com.metallum.client.lighting.EntityShadowProxyRegistry;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.MinecraftLightPolicy;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.voxel.VoxelClipmapController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
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
                AdvancedLight light = MinecraftLightPolicy.entity(entity, partialTick, collector.world());
                collector.offer(light);
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
        if (proxyCollector != null) {
            try {
                proxyCollector.offer(EntityShadowProxy.fromEntity(entity));
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
}
