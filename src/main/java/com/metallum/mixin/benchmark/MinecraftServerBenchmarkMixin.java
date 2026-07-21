package com.metallum.mixin.benchmark;

import com.metallum.Metallum;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Freezes the disposable benchmark world's simulation before its first game tick. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerBenchmarkMixin {
    @Unique
    private boolean metallum$simulationFrozen;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void metallum$freezeStaticRoute(
            final BooleanSupplier hasTimeLeft,
            final CallbackInfo ci
    ) {
        if (this.metallum$simulationFrozen) {
            return;
        }
        MinecraftServer server = (MinecraftServer) (Object) this;
        String dimension = System.getenv("METALLUM_BENCHMARK_DIMENSION");
        if (dimension != null && dimension.contains("nether")) {
            this.metallum$simulationFrozen = true;
            return;
        }
        server.tickRateManager().setFrozen(true);
        this.metallum$simulationFrozen = true;
        Metallum.LOGGER.info("METALLUM_BENCHMARK EVENT=SERVER_TICKS_FROZEN");
    }

    @Inject(method = "shouldRun(Lnet/minecraft/server/TickTask;)Z", at = @At("HEAD"), cancellable = true)
    private void metallum$alwaysRunBenchmarkTasks(
            final net.minecraft.server.TickTask task,
            final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir
    ) {
        if (System.getenv("METALLUM_BENCHMARK") != null) {
            cir.setReturnValue(true);
        }
    }
}
