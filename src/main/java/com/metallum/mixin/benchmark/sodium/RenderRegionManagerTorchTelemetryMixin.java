package com.metallum.mixin.benchmark.sodium;

import com.metallum.client.benchmark.TorchEpochTelemetry;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** Loaded only for a benchmark using Sodium; never transforms a normal client. */
@Mixin(RenderRegionManager.class)
abstract class RenderRegionManagerTorchTelemetryMixin {
    @Inject(
            method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void metallum$recordAcceptedMeshPayload(
            final Collection<BuilderTaskOutput> results,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        if (!TorchEpochTelemetry.isActive()) {
            return;
        }
        try {
            for (BuilderTaskOutput result : results) {
                if (!(result instanceof ChunkBuildOutput output)) {
                    continue;
                }
                TorchEpochTelemetry.recordBuildOutput(SectionPos.asLong(
                        output.section.getChunkX(),
                        output.section.getChunkY(),
                        output.section.getChunkZ()
                ));
                TorchEpochTelemetry.recordAcceptedMeshPayloadBytes(output.getResultSize());
                long geometryPayloadBytes = 0L;
                for (BuiltSectionMeshParts mesh : output.meshes.values()) {
                    geometryPayloadBytes = Math.addExact(
                            geometryPayloadBytes,
                            mesh.getVertexData().getLength()
                    );
                }
                TorchEpochTelemetry.recordAcceptedGeometryPayloadBytes(geometryPayloadBytes);
            }
        } catch (RuntimeException exception) {
            TorchEpochTelemetry.recordError();
        }
    }
}
