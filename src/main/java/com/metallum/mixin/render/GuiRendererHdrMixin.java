package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrUiRenderTarget;
import com.metallum.client.metal.render.MetalGpuTiming;
import com.metallum.client.metal.render.MetalGpuTimingStage;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
abstract class GuiRendererHdrMixin {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/CubeMap;render(FF)V"
            ),
            require = 1
    )
    private void metallum$prepareSdrPanoramaTarget(final CallbackInfo ci) {
        GameRenderer renderer = Minecraft.getInstance().gameRenderer;
        HdrUiRenderTarget.begin(renderer.mainRenderTarget());
    }

    @Redirect(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;",
                    ordinal = 0
            ),
            require = 1
    )
    private RenderTarget metallum$drawGuiIntoSdrTarget(final GameRenderer renderer) {
        RenderTarget target = HdrUiRenderTarget.begin(renderer.mainRenderTarget());
        MetalGpuTiming.begin(MetalGpuTimingStage.UI);
        return target;
    }

    @Redirect(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"
            ),
            require = 1
    )
    private void metallum$blurSdrUiBackdrop(final GameRenderer renderer) {
        RenderTarget uiTarget = HdrUiRenderTarget.activeTarget();
        if (uiTarget == null) {
            renderer.processBlurEffect();
            return;
        }

        if (!HdrUiRenderTarget.shouldProcessSdrBackdropBlur()) {
            // Match vanilla's menu radius and six-pass sequence, but keep the
            // intermediate image in FP16. Native code first composes the HDR
            // world with every GUI draw before the blur marker, then resolves
            // one blurred result back into matching HDR and SDR-seed targets.
            float radius = Minecraft.getInstance().options.menuBackgroundBlurriness().get();
            HdrUiRenderTarget.processCoherentBackdropBlur(radius);
            return;
        }

        PostChain blur = Minecraft.getInstance().getShaderManager().getPostChain(
                GameRendererAccessor.metallum$getBlurPostChainId(),
                LevelTargetBundle.MAIN_TARGETS
        );
        if (blur != null) {
            HdrUiRenderTarget.ensureBackdropMaterialized();
            FrameGraphBuilder frame = new FrameGraphBuilder();
            PostChain.TargetBundle targets = PostChain.TargetBundle.of(
                    PostChain.MAIN_TARGET_ID,
                    frame.importExternal("main", uiTarget)
            );
            blur.addToFrame(frame, uiTarget.width, uiTarget.height, targets);
            frame.execute(((GameRendererAccessor) renderer).metallum$getResourcePool());
            // The UI texture now contains a deliberately blurred copy of the
            // world. A sharp HDR scene delta must not be composited back over
            // that backdrop during presentation.
            HdrUiRenderTarget.markBackdropBlurred();
        }
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void metallum$publishSdrUiTarget(final CallbackInfo ci) {
        MetalGpuTiming.end();
        HdrUiRenderTarget.finish();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void metallum$destroySdrUiTarget(final CallbackInfo ci) {
        HdrUiRenderTarget.destroy();
    }
}
