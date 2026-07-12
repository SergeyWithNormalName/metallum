package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Set;

@Mixin(PostChain.class)
abstract class PostChainMixin {
    private static final Set<String> METALLUM_PROCESS_COLOR_CHAINS = Set.of(
            "blur", "invert", "spider", "creeper"
    );

    @Shadow @Final
    private Map<Identifier, RenderTarget> persistentTargets;

    @Unique
    private boolean metallum$transparencyChain;

    @Unique
    private boolean metallum$processColorChain;

    @Unique
    private GpuFormat metallum$processInputFormat;

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
            PostChainMixin mixin = (PostChainMixin) (Object) chain;
            boolean minecraftChain = identifier.getNamespace().equals("minecraft");
            mixin.metallum$transparencyChain = minecraftChain
                    && identifier.getPath().equals("transparency");
            mixin.metallum$processColorChain = minecraftChain
                    && METALLUM_PROCESS_COLOR_CHAINS.contains(identifier.getPath());
        }
    }

    @Inject(method = "process", at = @At("HEAD"))
    private void metallum$rememberProcessInputFormat(
            final RenderTarget main,
            final GraphicsResourceAllocator allocator,
            final CallbackInfo ci
    ) {
        this.metallum$processInputFormat = this.metallum$processColorChain
                ? main.getColorTexture().getFormat()
                : null;
    }

    @Inject(method = "process", at = @At("RETURN"))
    private void metallum$forgetProcessInputFormat(
            final RenderTarget main,
            final GraphicsResourceAllocator allocator,
            final CallbackInfo ci
    ) {
        this.metallum$processInputFormat = null;
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
        if (original != GpuFormat.RGBA8_UNORM) {
            return original;
        }
        if (this.metallum$processInputFormat != null) {
            return this.metallum$processInputFormat;
        }
        return HdrSceneState.isRequested() && this.metallum$transparencyChain
                ? GpuFormat.RGBA16_FLOAT
                : original;
    }

    @Inject(method = "getOrCreatePersistentTarget", at = @At("HEAD"))
    private void metallum$evictPersistentTargetWithWrongFormat(
            final Identifier identifier,
            final RenderTargetDescriptor descriptor,
            final CallbackInfoReturnable<RenderTarget> cir
    ) {
        RenderTarget target = this.persistentTargets.get(identifier);
        if (target == null) {
            return;
        }

        GpuTexture colorTexture = target.getColorTexture();
        if (colorTexture == null || colorTexture.getFormat() != descriptor.format()) {
            this.persistentTargets.remove(identifier);
            target.destroyBuffers();
        }
    }
}
