package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticController;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
abstract class GiSemanticLevelExtractorMixin {
    @Inject(
            method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/repository/PackRepository;reload()V",
                    shift = At.Shift.BEFORE
            ),
            require = 1,
            allow = 1
    )
    private void metallum$beginGiResourceReload(
            final boolean recovery,
            @Nullable final GameLoadCookie cookie,
            final CallbackInfoReturnable<CompletableFuture<Void>> cir
    ) {
        ClientLevel current = ((Minecraft) (Object) this).level;
        if (current != null) {
            try {
                GiSemanticController.global().beginResourceReload(
                        current, current.dimension().identifier().toString()
                );
            } catch (RuntimeException ignored) {
            }
        }
    }
}
