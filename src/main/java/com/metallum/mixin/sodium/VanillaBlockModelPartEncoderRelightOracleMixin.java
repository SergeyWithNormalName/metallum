package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.sodium.SodiumRelightOracle;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.function.Predicate;

/** Marks the exact Fabric vanilla encoder path; every other block emitter is rejected. */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.renderer.VanillaBlockModelPartEncoder", remap = false)
abstract class VanillaBlockModelPartEncoderRelightOracleMixin {
    @WrapMethod(
            method = "emitQuads(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;Ljava/util/function/Predicate;)V",
            remap = false,
            require = 1,
            allow = 1
    )
    private static void metallum$withVanillaEncoderScope(
            final BlockStateModelPart modelPart,
            @Coerce final Object emitter,
            final Predicate<Direction> cullingPredicate,
            final Operation<Void> original
    ) {
        SodiumRelightOracle.Scope scope;
        try {
            scope = SodiumRelightOracle.openVanillaModelPartScope();
        } catch (RuntimeException exception) {
            metallum$rejectCapture("vanilla encoder scope open failed", exception);
            original.call(modelPart, emitter, cullingPredicate);
            return;
        }

        try {
            original.call(modelPart, emitter, cullingPredicate);
        } finally {
            try {
                scope.close();
            } catch (RuntimeException exception) {
                metallum$rejectCapture("vanilla encoder scope close failed", exception);
            }
        }
    }

    private static void metallum$rejectCapture(final String reason, final RuntimeException exception) {
        try {
            SodiumRelightOracle.rejectCurrentTask(reason, exception);
        } catch (RuntimeException ignored) {
            // The diagnostic oracle must never replace or hide a renderer failure.
        }
    }
}
