package com.metallum.mixin.lighting;

import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.LightTemplate;
import com.metallum.client.lighting.MinecraftLightPolicy;
import com.metallum.client.lighting.StableLightIds;
import com.metallum.client.voxel.VoxelClipmapController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the already-committed block state without scheduling any additional mesh work. */
@Mixin(ClientLevel.class)
abstract class ClientLevelAdvancedLightMixin {
    @Inject(
            method = "setBlocksDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD")
    )
    private void metallum$recordAdvancedLightBlockChange(
            final BlockPos pos,
            final BlockState oldState,
            final BlockState newState,
            final CallbackInfo ci
    ) {
        if (!AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        registry.observeHook(AdvancedLightRegistry.Hook.BLOCK_CHANGE);
        try {
            ClientLevel level = (ClientLevel) (Object) this;
            String dimension = level.dimension().identifier().toString();
            LightTemplate replacement = MinecraftLightPolicy.block(
                    newState,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
            int localIndex = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
            registry.recordBlockChange(
                    level,
                    dimension,
                    SectionPos.asLong(
                            SectionPos.blockToSectionCoord(pos.getX()),
                            SectionPos.blockToSectionCoord(pos.getY()),
                            SectionPos.blockToSectionCoord(pos.getZ())
                    ),
                    localIndex,
                    StableLightIds.block(dimension, pos.getX(), pos.getY(), pos.getZ()),
                    replacement
            );
        } catch (Throwable failure) {
            registry.failClosed("block light update failed", failure);
        }
        // L5 deliberately records only a revision/invalidation here. The next accepted Sodium
        // geometry result supplies the worker-owned shape snapshot; no live-world voxel scan or
        // L3/L4 admission failure is allowed on this hot path.
        try {
            ClientLevel level = (ClientLevel) (Object) this;
            VoxelClipmapController.global().markBlockDirty(
                    level,
                    level.dimension().identifier().toString(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
        } catch (RuntimeException ignored) {
            // A producer-only L5 queue fault must remain isolated from established direct lights.
        }
    }
}
