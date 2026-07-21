package com.metallum.mixin.sodium;

import com.metallum.client.gui.NativeFullscreenCheckboxControl;
import com.mojang.blaze3d.platform.MacosUtil;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.gui.options.FullscreenMode;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EnumOption.class, remap = false)
abstract class SodiumEnumOptionMixin {
    private static final Identifier FULLSCREEN_MODE_ID = Identifier.fromNamespaceAndPath("sodium", "general.fullscreen_mode");

    @Inject(method = "isValueAllowed", at = @At("HEAD"), cancellable = true)
    private void metallum$disallowExclusiveFullscreenOnMac(final Enum<?> value, final CallbackInfoReturnable<Boolean> cir) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        Identifier id = ((SodiumOptionAccessor) this).metallum$getId();
        if (FULLSCREEN_MODE_ID.equals(id)) {
            if (value == FullscreenMode.EXCLUSIVE) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "createControl", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void metallum$replaceFullscreenSelectorWithCheckbox(final CallbackInfoReturnable<Control> cir) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        Identifier id = ((SodiumOptionAccessor) this).metallum$getId();
        if (FULLSCREEN_MODE_ID.equals(id)) {
            cir.setReturnValue(new NativeFullscreenCheckboxControl((EnumOption<FullscreenMode>) (Object) this));
        }
    }
}
