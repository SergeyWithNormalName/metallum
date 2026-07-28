package com.metallum.mixin.render;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.sugar.Local;
import com.metallum.client.hdr.EmissiveTextureRegistry;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/** Adds selected OptiFine-style emissive sidecars after the normal block-atlas sources are known. */
@Mixin(SpriteSourceList.class)
abstract class SpriteSourceListEmissiveMixin implements EmissiveSpriteSourceListAccess {
    @Unique
    private boolean metallum$blockAtlas;

    @Inject(method = "load", at = @At("RETURN"))
    private static void metallum$markBlocksAtlas(
            final ResourceManager resourceManager,
            final Identifier atlasId,
            final CallbackInfoReturnable<SpriteSourceList> cir
    ) {
        if (AtlasIds.BLOCKS.equals(atlasId)) {
            ((EmissiveSpriteSourceListAccess) cir.getReturnValue()).metallum$markBlockAtlas();
        }
    }

    @Inject(
            method = "list",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableList$Builder;addAll(Ljava/lang/Iterable;)Lcom/google/common/collect/ImmutableList$Builder;",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void metallum$appendEmissiveSprites(
            final ResourceManager resourceManager,
            final CallbackInfoReturnable<List<SpriteSource.Loader>> cir,
            final @Local(ordinal = 0) Map<Identifier, SpriteSource.DiscardableLoader> sprites,
            final @Local(ordinal = 0) SpriteSource.Output output,
            final @Local(ordinal = 0) ImmutableList.Builder<SpriteSource.Loader> result
    ) {
        if (this.metallum$blockAtlas) {
            EmissiveTextureRegistry.appendBlockAtlasOverlays(resourceManager, sprites, output);
        }
    }

    @Override
    public void metallum$markBlockAtlas() {
        this.metallum$blockAtlas = true;
    }

    @Override
    public boolean metallum$isBlockAtlas() {
        return this.metallum$blockAtlas;
    }
}
