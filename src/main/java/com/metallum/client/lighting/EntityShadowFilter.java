package com.metallum.client.lighting;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;

/** Determines whether an entity is an active, visible shadow caster for local lights. */
public final class EntityShadowFilter {
    private EntityShadowFilter() {
    }

    public static boolean isShadowCaster(final Entity entity) {
        return isShadowCaster(entity, null);
    }

    public static boolean isShadowCaster(final Entity entity, final Camera camera) {
        if (entity == null || entity.isRemoved() || entity.isInvisible()) {
            return false;
        }
        if (isFirstPersonCameraEntity(entity, camera)) {
            return false;
        }
        if (entity instanceof Player player && player.isSpectator()) {
            return false;
        }
        if (entity instanceof LivingEntity living) {
            if (living.isDeadOrDying() && living.deathTime >= 20) {
                return false;
            }
            return true;
        }
        return entity instanceof VehicleEntity;
    }

    public static boolean isFirstPersonCameraEntity(final Entity entity, final Camera camera) {
        if (entity == null) {
            return false;
        }
        if (camera != null) {
            if (!camera.isDetached() && entity == camera.entity()) {
                return true;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null && mc.options.getCameraType().isFirstPerson()) {
            Entity cameraEntity = mc.getCameraEntity();
            if (cameraEntity != null && (entity == cameraEntity || entity.getId() == cameraEntity.getId())) {
                return true;
            }
        }
        return false;
    }
}
