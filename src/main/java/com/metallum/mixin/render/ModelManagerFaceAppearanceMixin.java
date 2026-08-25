package com.metallum.mixin.render;

import com.metallum.client.radiance.FaceAwareRadianceAppearance;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes the immutable face palette only after Minecraft has installed the new baked models. */
@Mixin(ModelManager.class)
abstract class ModelManagerFaceAppearanceMixin {
    @Shadow
    @Final
    private BlockColors blockColors;

    @Inject(method = "apply", at = @At("TAIL"))
    private void metallum$rebuildWaterReflectionFaceAppearance(final CallbackInfo ci) {
        ModelManager manager = (ModelManager) (Object) this;
        FaceAwareRadianceAppearance.rebuild(manager.getBlockStateModelSet(), this.blockColors);
    }
}
