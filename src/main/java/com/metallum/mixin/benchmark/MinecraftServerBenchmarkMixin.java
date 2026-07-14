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
        server.tickRateManager().setFrozen(true);
        this.metallum$simulationFrozen = true;
        Metallum.LOGGER.info("METALLUM_BENCHMARK EVENT=SERVER_TICKS_FROZEN");
    }
}
