package com.metallum.client.lighting;

import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/** Exact Sodium 0.9.1 adapter: scans only the central cloned section of a full mesh task. */
public final class SodiumStaticLightExtractor {
    private SodiumStaticLightExtractor() {
    }

    public static LightSectionCandidate scan(
            final LightSectionTask task,
            final ChunkRenderContext context
    ) {
        if (context.getOrigin().asLong() != task.sectionKey()) {
            throw new IllegalStateException("Sodium light task section does not match its clone origin");
        }
        ClonedChunkSection center = null;
        for (ClonedChunkSection section : context.getSections()) {
            if (section != null && section.getPosition().asLong() == task.sectionKey()) {
                if (center != null) {
                    throw new IllegalStateException("Sodium clone contains the central section twice");
                }
                center = section;
            }
        }
        if (center == null) {
            throw new IllegalStateException("Sodium clone does not contain its central section");
        }

        PalettedContainerRO<BlockState> states = center.getBlockData();
        BlockPos origin = context.getOrigin().origin();
        return StaticLightSectionScanner.scan(
                task,
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                (localIndex, localX, localY, localZ) -> MinecraftLightPolicy.block(
                        states.get(localX, localY, localZ),
                        origin.getX() + localX,
                        origin.getY() + localY,
                        origin.getZ() + localZ
                )
        );
    }
}
