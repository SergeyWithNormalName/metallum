package com.metallum.mixin.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Gives every in-world screen the same blur and light backdrop as the pause menu. */
@Mixin(Screen.class)
abstract class ScreenInWorldBlurMixin {
    private static final Identifier INWORLD_MENU_BACKGROUND = Identifier.withDefaultNamespace(
            "textures/gui/inworld_menu_background.png"
    );

    @Redirect(
            method = "extractBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;extractTransparentBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"
            ),
            require = 1
    )
    private void metallum$blurInWorldBackground(
            final Screen screen,
            final GuiGraphicsExtractor graphics
    ) {
        if (Minecraft.getInstance().options.getMenuBackgroundBlurriness() >= 1.0f) {
            graphics.blurBeforeThisStratum();
        }
        Screen.extractMenuBackgroundTexture(
                graphics,
                INWORLD_MENU_BACKGROUND,
                0,
                0,
                0.0f,
                0.0f,
                screen.width,
                screen.height
        );
    }
}
