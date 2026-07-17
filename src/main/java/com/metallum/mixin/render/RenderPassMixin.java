package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.MetallumMaterialState;
import com.metallum.client.metal.render.SunShadowRenderer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(RenderPass.class)
abstract class RenderPassMixin {
    @Shadow @Final
    private RenderPassBackend backend;

    @Shadow @Final
    private List<RenderPassDescriptor.Attachment<Optional<org.joml.Vector4fc>>> colorAttachments;

    @Inject(
            method = "setPipeline",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$allowFp16SceneAttachment(final RenderPipeline pipeline, final CallbackInfo ci) {
        if (SunShadowRenderer.isRendering()) {
            this.backend.setPipeline(pipeline);
            ci.cancel();
            return;
        }
        if (!HdrSceneState.isRequested() && !MetallumMaterialState.requiresFp16Scene()) {
            return;
        }

        ColorTargetState[] states = pipeline.getColorTargetStates();
        if (states.length != this.colorAttachments.size()) {
            return;
        }

        boolean hasSceneFormatUpgrade = false;
        for (int index = 0; index < states.length; index++) {
            RenderPassDescriptor.Attachment<?> attachment = this.colorAttachments.get(index);
            if (attachment == null) {
                continue;
            }
            ColorTargetState state = states[index];
            if (state == null || attachment.textureView() == null || attachment.textureView().texture() == null) {
                return;
            }

            GpuFormat expected = state.format();
            GpuFormat actual = attachment.textureView().texture().getFormat();
            if (expected == actual) {
                continue;
            }
            if (expected == GpuFormat.RGBA8_UNORM && actual == GpuFormat.RGBA16_FLOAT) {
                hasSceneFormatUpgrade = true;
                continue;
            }
            // Preserve vanilla validation for every unrelated mismatch,
            // including a future Java-visible semantic MRT attachment.
            return;
        }

        if (hasSceneFormatUpgrade) {
            this.backend.setPipeline(pipeline);
            ci.cancel();
        }
    }
}
