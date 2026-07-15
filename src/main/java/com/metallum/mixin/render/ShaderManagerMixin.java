package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.SceneLinearPreflightGate;
import com.metallum.client.hdr.SceneLinearShaderPatcher;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.TemporalResetEvents;
import com.mojang.blaze3d.shaders.ShaderType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
    @Inject(
            method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD")
    )
    private void metallum$preflightLinearSceneGeneration(
            final ShaderManager.Configs configs,
            final ResourceManager resourceManager,
            final ProfilerFiller profiler,
            final CallbackInfo ci
    ) {
        Map<SceneLinearPreflightGate.ShaderKey, String> sources = new HashMap<>();
        for (Map.Entry<?, String> entry : shaderSources(configs).entrySet()) {
            Object rawKey = entry.getKey();
            if (!(rawKey instanceof ShaderSourceKeyAccessor accessor)) {
                SceneLinearPreflightGate.beginCandidate(new SceneLinearPreflightGate.Evaluation(
                        false,
                        "could not inspect ShaderManager compilation-cache keys"
                ));
                return;
            }
            Identifier id = accessor.metallum$id();
            ShaderType shaderType = accessor.metallum$type();
            SceneLinearShaderPatcher.Stage stage = shaderType == ShaderType.VERTEX
                    ? SceneLinearShaderPatcher.Stage.VERTEX
                    : SceneLinearShaderPatcher.Stage.FRAGMENT;
            sources.put(
                    new SceneLinearPreflightGate.ShaderKey(id.getNamespace(), id.getPath(), stage),
                    entry.getValue()
            );
        }

        FabricLoader loader = FabricLoader.getInstance();
        SceneLinearPreflightGate.beginCandidate(SceneLinearPreflightGate.evaluate(
                sources,
                HdrSceneState.isRequested(),
                loader.isModLoaded("iris"),
                loader.isModLoaded("sodium")
        ));
    }

    @Inject(
            method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("RETURN")
    )
    private void metallum$commitLinearSceneGeneration(
            final ShaderManager.Configs configs,
            final ResourceManager resourceManager,
            final ProfilerFiller profiler,
            final CallbackInfo ci
    ) {
        SceneLinearPreflightGate.commitCandidate();
        TemporalResetEvents.signal(FrameState.HistoryResetReason.RESOURCE_PACK_SHADER_RELOAD);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<?, String> shaderSources(final ShaderManager.Configs configs) {
        return (Map) configs.shaderSources();
    }
}
