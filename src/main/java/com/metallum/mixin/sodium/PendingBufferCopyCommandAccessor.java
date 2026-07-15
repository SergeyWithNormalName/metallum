package com.metallum.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.arena.PendingBufferCopyCommand", remap = false)
public interface PendingBufferCopyCommandAccessor {
    @Accessor("readOffset")
    int metallum$getReadOffset();

    @Accessor("writeOffset")
    int metallum$getWriteOffset();

    @Accessor("length")
    int metallum$getLength();
}
