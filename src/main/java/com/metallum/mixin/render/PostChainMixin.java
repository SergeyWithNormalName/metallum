package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.mojang.blaze3d.GpuFormat;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(PostChain.class)
abstract class PostChainMixin {
    private static final Set<String> METALLUM_SCENE_CHAINS = Set.of(
            "transparency", "blur", "invert", "spider", "creeper"
    );

    @Unique
    private boolean metallum$sceneColorChain;

    @Inject(method = "load", at = @At("RETURN"))
    private static void metallum$rememberSceneColorContract(
            final PostChainConfig config,
            final TextureManager textureManager,
            final Set<Identifier> externalTargets,
            final Identifier identifier,
            final Projection projection,
            final ProjectionMatrixBuffer projectionMatrixBuffer,
            final CallbackInfoReturnable<PostChain> cir
    ) {
        PostChain chain = cir.getReturnValue();
        if (chain != null) {
            ((PostChainMixin) (Object) chain).metallum$sceneColorChain =
                    identifier.getNamespace().equals("minecraft")
                            && METALLUM_SCENE_CHAINS.contains(identifier.getPath());
        }
    }

    @ModifyArg(
            method = "addToFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IIZLorg/joml/Vector4fc;Lcom/mojang/blaze3d/GpuFormat;)V"
            ),
            index = 4,
            require = 1
    )
    private GpuFormat metallum$preserveFp16PostChainColor(final GpuFormat original) {
        return HdrSceneState.isRequested()
                && this.metallum$sceneColorChain
                && original == GpuFormat.RGBA8_UNORM
                ? GpuFormat.RGBA16_FLOAT
                : original;
    }
}
