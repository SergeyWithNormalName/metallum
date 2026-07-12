package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.hdr.HdrSemanticState;
import com.metallum.client.hdr.SodiumHdrShaderPatcher;
import com.metallum.client.hdr.VanillaHdrShaderPatcher;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
abstract class HdrShaderMixin {
    private static final Set<String> METALLUM_PATCH_FAILURES_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Set<String> METALLUM_PATCH_SUCCESSES_LOGGED = ConcurrentHashMap.newKeySet();

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private void metallum$addSemanticHdrOutput(
            final Identifier identifier,
            final ShaderType shaderType,
            final CallbackInfoReturnable<String> cir
    ) {
        if (!HdrSemanticState.isRequested()) {
            return;
        }
        String source = cir.getReturnValue();
        if (source == null) {
            return;
        }

        String patched;
        boolean expectedPatch;
        boolean patchedSuccessfully;
        if (identifier.getNamespace().equals("sodium")
                && identifier.getPath().equals("blocks/block_layer_opaque")) {
            expectedPatch = true;
            patched = switch (shaderType) {
                case VERTEX -> SodiumHdrShaderPatcher.patchVertexSource(source);
                case FRAGMENT -> SodiumHdrShaderPatcher.patchFragmentSource(source);
            };
            patchedSuccessfully = SodiumHdrShaderPatcher.isPatched(patched);
        } else if (identifier.getNamespace().equals("minecraft")
                && shaderType == ShaderType.FRAGMENT
                && VanillaHdrShaderPatcher.isTarget(identifier.getPath())) {
            expectedPatch = true;
            patched = VanillaHdrShaderPatcher.patchFragmentSource(identifier.getPath(), source);
            patchedSuccessfully = VanillaHdrShaderPatcher.isPatched(patched);
        } else {
            expectedPatch = false;
            patched = source;
            patchedSuccessfully = false;
        }

        if (!expectedPatch) {
            return;
        }
        if (!patchedSuccessfully) {
            String failureKey = identifier + ":" + shaderType;
            if (METALLUM_PATCH_FAILURES_LOGGED.add(failureKey)) {
                Metallum.LOGGER.warn(
                        "Could not add semantic HDR output to {} {} shader; visual fallback remains available",
                        identifier,
                        shaderType
                );
            }
            return;
        }
        String successKey = identifier + ":" + shaderType;
        if (METALLUM_PATCH_SUCCESSES_LOGGED.add(successKey)) {
            Metallum.LOGGER.info("Semantic HDR source patch active for {} {} shader", identifier, shaderType);
        }
        cir.setReturnValue(patched);
    }
}
