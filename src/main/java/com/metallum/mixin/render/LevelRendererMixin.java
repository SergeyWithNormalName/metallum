package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.MetallumMaterialState;
import com.metallum.client.metal.render.MetalGpuTiming;
import com.metallum.client.metal.render.MetalGpuTimingStage;
import com.metallum.client.metal.render.SunShadowRenderer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Unique
    private FramePass metallum$pendingSunShadowPass;

    @Inject(method = "addMainPass", at = @At("HEAD"))
    private void metallum$registerSunShadowPass(
            final FrameGraphBuilder frame,
            final FeatureRenderDispatcher.PreparedFrame featureFrame,
            final GpuBufferSlice terrainFog,
            final LevelRenderState levelRenderState,
            final ProfilerFiller profiler,
            final ChunkSectionsToRender chunkSectionsToRender,
            final CallbackInfo ci
    ) {
        this.metallum$pendingSunShadowPass = SunShadowRenderer.addFramePass(
                frame,
                featureFrame,
                chunkSectionsToRender
        );
    }

    @Redirect(
            method = "addMainPass",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;addPass(Ljava/lang/String;)Lcom/mojang/blaze3d/framegraph/FramePass;"
            ),
            require = 1
    )
    private FramePass metallum$linkMainAfterSunShadow(
            final FrameGraphBuilder frame,
            final String name
    ) {
        FramePass main = frame.addPass(name);
        FramePass shadow = this.metallum$pendingSunShadowPass;
        this.metallum$pendingSunShadowPass = null;
        if (shadow != null) {
            main.requires(shadow);
        }
        return main;
    }

    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IIZLorg/joml/Vector4fc;Lcom/mojang/blaze3d/GpuFormat;)V"
            ),
            index = 4,
            require = 1
    )
    private GpuFormat metallum$upgradeSceneContinuationTargets(final GpuFormat original) {
        return (HdrSceneState.isRequested() || MetallumMaterialState.requiresFp16Scene())
                && original == GpuFormat.RGBA8_UNORM
                ? GpuFormat.RGBA16_FLOAT
                : original;
    }

    @Redirect(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"
            ),
            require = 2
    )
    private void metallum$profileTerrainGroup(
            final ChunkSectionsToRender sections,
            final ChunkSectionLayerGroup group,
            final GpuSampler sampler
    ) {
        MetalGpuTimingStage stage = group == ChunkSectionLayerGroup.TRANSLUCENT
                ? MetalGpuTimingStage.TRANSLUCENT
                : MetalGpuTimingStage.WORLD_OPAQUE;
        MetalGpuTiming.begin(stage);
        try {
            sections.renderGroup(group, sampler);
        } finally {
            MetalGpuTiming.end();
        }
    }

    @Redirect(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V"
            ),
            require = 1
    )
    private void metallum$profileSolidEntities(final FeatureRenderDispatcher.PreparedFrame frame) {
        metallum$profileEntities(frame::executeSolid);
    }

    @Redirect(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent()V"
            ),
            require = 1
    )
    private void metallum$profileTranslucentEntities(final FeatureRenderDispatcher.PreparedFrame frame) {
        metallum$profileEntities(frame::executeTranslucent);
    }

    @Redirect(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeOutline()V"
            ),
            require = 1
    )
    private void metallum$profileEntityOutlines(final FeatureRenderDispatcher.PreparedFrame frame) {
        metallum$profileEntities(frame::executeOutline);
    }

    @Redirect(
            method = "lambda$addMainPass$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucentAfterTerrain()V"
            ),
            require = 1
    )
    private void metallum$profileEntitiesAfterTerrain(final FeatureRenderDispatcher.PreparedFrame frame) {
        metallum$profileEntities(frame::executeTranslucentAfterTerrain);
    }

    private static void metallum$profileEntities(final Runnable draw) {
        MetalGpuTiming.begin(MetalGpuTimingStage.ENTITIES);
        try {
            draw.run();
        } finally {
            MetalGpuTiming.end();
        }
    }
}
