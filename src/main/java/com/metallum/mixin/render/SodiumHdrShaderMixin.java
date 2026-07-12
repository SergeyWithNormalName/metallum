package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.hdr.SodiumHdrShaderPatcher;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(ShaderManager.class)
abstract class SodiumHdrShaderMixin {
    private static final AtomicBoolean METALLUM_PATCH_FAILURE_LOGGED = new AtomicBoolean();

    @Inject(method = "getShader", at = @At("RETURN"), cancellable = true)
    private void metallum$addSodiumEmissionSignal(
            final Identifier identifier,
            final ShaderType shaderType,
            final CallbackInfoReturnable<String> cir
    ) {
        if (!identifier.getNamespace().equals("sodium")
                || !identifier.getPath().equals("blocks/block_layer_opaque")) {
            return;
        }

        String source = cir.getReturnValue();
        if (source == null) {
            return;
        }
        String patched = switch (shaderType) {
            case VERTEX -> SodiumHdrShaderPatcher.patchVertexSource(source);
            case FRAGMENT -> SodiumHdrShaderPatcher.patchFragmentSource(source);
        };
        if (!SodiumHdrShaderPatcher.isPatched(patched)) {
            if (METALLUM_PATCH_FAILURE_LOGGED.compareAndSet(false, true)) {
                Metallum.LOGGER.warn("Could not add semantic HDR emission markers to Sodium shaders; using visual fallback");
            }
            return;
        }
        cir.setReturnValue(patched);
    }
}
