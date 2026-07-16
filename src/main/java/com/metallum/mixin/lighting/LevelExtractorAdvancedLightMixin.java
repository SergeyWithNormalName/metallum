package com.metallum.mixin.lighting;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.BoundedDynamicLightCollector;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.MinecraftLightPolicy;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
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
        }
        this.metallum$dynamicLights = null;
        this.metallum$dynamicLightDeltaTracker = null;
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
            registry.openWorld(next, metallum$dimensionId(next));
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
            registry.reloadWorld(this.level, metallum$dimensionId(this.level));
        } else {
            AdvancedLightRegistry.global().closeWorld(this.level);
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
        if (this.level == null || !AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        registry.observeHook(AdvancedLightRegistry.Hook.DYNAMIC_ENTITY);
        LightWorldToken token = registry.openWorld(this.level, metallum$dimensionId(this.level));
        this.metallum$dynamicLights = new BoundedDynamicLightCollector(
                token,
                AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS
        );
        this.metallum$dynamicLightDeltaTracker = deltaTracker;
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
        this.metallum$dynamicLights = null;
        this.metallum$dynamicLightDeltaTracker = null;
        if (collector == null) {
            return;
        }
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

    @Unique
    private static String metallum$dimensionId(final ClientLevel level) {
        return level.dimension().identifier().toString();
    }
}
