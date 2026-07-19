package com.metallum.mixin.render;

import com.metallum.client.voxel.VoxelPreviewHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class GuiVoxelPreviewMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void metallum$renderVoxelPreview(
            final GuiGraphicsExtractor graphics,
            final DeltaTracker deltaTracker,
            final CallbackInfo callback
    ) {
        VoxelPreviewHud.render(graphics);
    }
}
