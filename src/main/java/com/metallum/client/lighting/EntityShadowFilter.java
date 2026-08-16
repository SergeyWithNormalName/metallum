package com.metallum.client.lighting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;

/** Determines whether an entity is an active, visible shadow caster for local lights. */
public final class EntityShadowFilter {
    private EntityShadowFilter() {
    }

    public static boolean isShadowCaster(final Entity entity) {
        if (entity == null || entity.isRemoved() || entity.isInvisible()) {
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
}
