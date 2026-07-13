package com.metallum.mixin.benchmark;

import com.metallum.client.benchmark.MetalFxBenchmarkController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Loaded only when METALLUM_BENCHMARK=1; absent from the normal render hot path. */
@Mixin(Minecraft.class)
abstract class MinecraftBenchmarkMixin {
    @Unique
    private final MetalFxBenchmarkController metallum$benchmark = new MetalFxBenchmarkController();

    @Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("TAIL"))
    private void metallum$armBenchmark(final ClientLevel level, final CallbackInfo ci) {
        if (level != null) {
            this.metallum$benchmark.arm();
        }
    }

    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void metallum$driveBenchmarkWindow(final boolean renderLevel, final CallbackInfo ci) {
        this.metallum$benchmark.driveWindow((Minecraft) (Object) this);
    }

    @Inject(
            method = "renderFrame(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V",
                    shift = At.Shift.AFTER
            )
    )
    private void metallum$countBenchmarkFrame(final boolean renderLevel, final CallbackInfo ci) {
        this.metallum$benchmark.onPresentedFrame((Minecraft) (Object) this);
    }
}
