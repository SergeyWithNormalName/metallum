package com.metallum.mixin.render;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Window.class)
public interface NativeFullscreenWindowAccessor {
    @Accessor("fullscreen")
    boolean metallum$getFullscreen();

    @Accessor("fullscreen")
    void metallum$setFullscreen(boolean fullscreen);

    @Accessor("actuallyFullscreen")
    void metallum$setActuallyFullscreen(boolean actuallyFullscreen);

    @Invoker("setMode")
    void metallum$applyVanillaWindowMode();
}
