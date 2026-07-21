package com.metallum.mixin.render;

import com.metallum.client.renderer.temporal.EntityAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin implements EntityAccessor {
    @Unique
    private boolean metallum$explicitTeleport;

    @Override
    public boolean metallum$isExplicitTeleport() {
        return this.metallum$explicitTeleport;
    }

    @Override
    public void metallum$setExplicitTeleport(boolean explicitTeleport) {
        this.metallum$explicitTeleport = explicitTeleport;
    }

    @Inject(method = "teleport", at = @At("HEAD"), require = 0)
    private void metallum$onTeleport(CallbackInfoReturnable<Entity> cir) {
        this.metallum$explicitTeleport = true;
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at = @At("HEAD"), require = 0)
    private void metallum$onTeleportTo(CallbackInfoReturnable<Boolean> cir) {
        this.metallum$explicitTeleport = true;
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), require = 0)
    private void metallum$onTeleportToPos(CallbackInfo ci) {
        this.metallum$explicitTeleport = true;
    }

    @Inject(method = "teleportRelative", at = @At("HEAD"), require = 0)
    private void metallum$onTeleportRelative(CallbackInfo ci) {
        this.metallum$explicitTeleport = true;
    }
}
