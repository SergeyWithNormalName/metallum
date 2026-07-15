package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.sodium.SodiumRelightOracle;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.SodiumShadeMode;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Captures exact vanilla block-quad relight inputs without changing full meshing. */
@Mixin(value = BlockRenderer.class, remap = false)
abstract class BlockRendererRelightOracleMixin {
    @WrapMethod(
            method = "renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
            remap = false,
            require = 1,
            allow = 1
    )
    private void metallum$withRelightBlockModelScope(
            final BlockStateModel model,
            final BlockState state,
            final BlockPos pos,
            final BlockPos origin,
            final Operation<Void> original
    ) {
        SodiumRelightOracle.Scope scope;
        try {
            scope = SodiumRelightOracle.openBlockModelScope();
        } catch (RuntimeException exception) {
            metallum$rejectCapture("block model scope open failed", exception);
            original.call(model, state, pos, origin);
            return;
        }

        try {
            original.call(model, state, pos, origin);
        } finally {
            try {
                scope.close();
            } catch (RuntimeException exception) {
                metallum$rejectCapture("block model scope close failed", exception);
            }
        }
    }

    @WrapOperation(
            method = "processQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;shadeQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;Lnet/caffeinemc/mods/sodium/client/model/light/LightMode;ZLnet/caffeinemc/mods/sodium/client/render/model/SodiumShadeMode;)V"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private void metallum$captureBeforeShade(
            final BlockRenderer renderer,
            final MutableQuadViewImpl quad,
            final LightMode lightMode,
            final boolean emissive,
            final SodiumShadeMode shadeMode,
            final Operation<Void> original
    ) {
        try {
            SodiumRelightOracle.beforeShade(renderer, quad, lightMode, emissive, shadeMode);
        } catch (RuntimeException exception) {
            metallum$rejectCapture("quad shade capture failed", exception);
        }
        original.call(renderer, quad, lightMode, emissive, shadeMode);
    }

    @WrapOperation(
            method = "processQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;bufferQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;[FLnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private void metallum$captureBeforeBuffer(
            final BlockRenderer renderer,
            final MutableQuadViewImpl quad,
            final float[] brightness,
            final Material material,
            final Operation<Void> original
    ) {
        try {
            SodiumRelightOracle.bufferQuad(renderer, quad, material);
        } catch (RuntimeException exception) {
            metallum$rejectCapture("quad buffer capture failed", exception);
        }
        original.call(renderer, quad, brightness, material);
    }

    private static void metallum$rejectCapture(final String reason, final RuntimeException exception) {
        try {
            SodiumRelightOracle.rejectCurrentTask(reason, exception);
        } catch (RuntimeException ignored) {
            // The diagnostic oracle must never replace or hide a renderer failure.
        }
    }
}
