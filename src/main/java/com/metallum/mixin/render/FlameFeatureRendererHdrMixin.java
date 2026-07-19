package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSemanticState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla submits a burning entity's block-atlas flame with a lit cutout
 * pipeline. That leaves the {@code EMISSIVE} shader define unset, so the
 * existing HDR entity-emission contract cannot see it. Keep SDR rendering
 * untouched, but select Minecraft's own alpha-cutout emissive entity pipeline
 * whenever semantic HDR is active.
 */
@Mixin(FlameFeatureRenderer.class)
abstract class FlameFeatureRendererHdrMixin {
    @Redirect(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entityCutoutCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"
            ),
            require = 1
    )
    private RenderType metallum$selectHdrFlamePipeline(final Identifier texture) {
        return HdrSemanticState.isRequested()
                ? RenderTypes.entityTranslucentEmissive(texture)
                : RenderTypes.entityCutoutCull(texture);
    }
}
