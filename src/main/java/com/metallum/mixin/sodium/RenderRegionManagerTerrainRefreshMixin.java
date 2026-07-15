package com.metallum.mixin.sodium;

import com.metallum.client.benchmark.TorchEpochTelemetry;
import com.metallum.client.metal.render.SodiumLightLegacyPatchBatch;
import com.metallum.client.sodium.SodiumBufferSegmentAccess;
import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.sodium.SodiumLightSidecarArena;
import com.metallum.client.sodium.SodiumLightSidecarPacking;
import com.metallum.client.sodium.SodiumSectionRenderDataAccess;
import com.metallum.client.sodium.SodiumRelightFastOutputSlot;
import com.metallum.client.sodium.SodiumRelightFastPath;
import com.metallum.client.sodium.SodiumTerrainInPlaceRefresh;
import com.metallum.client.sodium.SodiumTerrainLightPatch;
import com.metallum.client.sodium.SodiumTerrainMeshLayout;
import com.metallum.client.sodium.SodiumTerrainUploadBaseline;
import com.metallum.client.sodium.SodiumTerrainUploadBaselineAccess;
import com.mojang.blaze3d.buffers.GpuBuffer;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.gpu.arena.staging.StagingBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@Mixin(value = RenderRegionManager.class, remap = false)
abstract class RenderRegionManagerTerrainRefreshMixin {
    @Shadow
    @Final
    private StagingBuffer stagingBuffer;

    @Unique
    @Nullable
    private IdentityHashMap<ChunkBuildOutput, SodiumTerrainUploadBaseline> metallum$pendingBaselines;

    @Unique
    @Nullable
    private IdentityHashMap<ChunkBuildOutput, SodiumTerrainInPlaceRefresh> metallum$successfulRefreshes;

    @Inject(
            method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("HEAD")
    )
    private void metallum$beginTerrainRefreshBatch(
            final Collection<BuilderTaskOutput> results,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        this.metallum$pendingBaselines = null;
        this.metallum$successfulRefreshes = null;
        for (BuilderTaskOutput result : results) {
            if (result instanceof ChunkBuildOutput output
                    && output instanceof SodiumRelightFastOutputSlot marker
                    && marker.metallum$isFastRelightOutput()) {
                SodiumRelightFastPath.recordAcceptedOutput(output);
            }
        }
    }

    @Inject(method = "createMeshUploadQueues", at = @At("RETURN"))
    private void metallum$reuseResidentTerrainAllocations(
            final Collection<BuilderTaskOutput> acceptedResults,
            final CallbackInfoReturnable<Reference2ReferenceMap.FastEntrySet<
                    RenderRegion,
                    List<BuilderTaskOutput>
                    >> cir
    ) {
        if (!SodiumLightSidecar.isRuntimeActive()) {
            return;
        }

        IdentityHashMap<ChunkBuildOutput, SodiumTerrainUploadBaseline> baselines = new IdentityHashMap<>();
        try {
            for (BuilderTaskOutput result : acceptedResults) {
                if (result instanceof ChunkBuildOutput output) {
                    baselines.put(output, metallum$captureBaseline(output));
                }
            }
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("could not fingerprint a compact terrain output", throwable);
            return;
        }
        this.metallum$pendingBaselines = baselines;

        List<SodiumTerrainInPlaceRefresh> refreshes = new ArrayList<>();
        IdentityHashMap<ChunkBuildOutput, Boolean> reusable = new IdentityHashMap<>();
        try {
            for (Reference2ReferenceMap.Entry<RenderRegion, List<BuilderTaskOutput>> entry : cir.getReturnValue()) {
                for (BuilderTaskOutput result : entry.getValue()) {
                    if (!(result instanceof ChunkBuildOutput output)) {
                        continue;
                    }
                    SodiumTerrainInPlaceRefresh refresh = this.metallum$prepareRefresh(
                            entry.getKey(),
                            output,
                            baselines.get(output)
                    );
                    if (refresh != null) {
                        refreshes.add(refresh);
                        reusable.put(output, Boolean.TRUE);
                    }
                }
            }
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("could not prepare resident terrain refreshes", throwable);
            return;
        }
        if (refreshes.isEmpty()) {
            return;
        }

        try {
            metallum$validateNoOverlappingRanges(refreshes);
            long sidecarBytes = 0L;
            long meshCommands = 0L;
            List<SodiumLightLegacyPatchBatch.Patch> lightPatches = new ArrayList<>();
            long[] geometryBytesByRefresh = new long[refreshes.size()];
            long[] meshCommandsByRefresh = new long[refreshes.size()];
            for (int refreshIndex = 0; refreshIndex < refreshes.size(); refreshIndex++) {
                SodiumTerrainInPlaceRefresh refresh = refreshes.get(refreshIndex);
                for (SodiumTerrainInPlaceRefresh.Mesh mesh : refresh.meshes()) {
                    GlBufferSegment allocation = mesh.allocation();
                    geometryBytesByRefresh[refreshIndex] = Math.addExact(
                            geometryBytesByRefresh[refreshIndex],
                            mesh.geometry().remaining()
                    );
                    SodiumLightSidecarArena sidecarArena = (SodiumLightSidecarArena) mesh.arena();
                    if (refresh.lightOnly()) {
                        lightPatches.add(sidecarArena.metallum$enqueueInPlaceTerrainLightRefresh(
                                mesh.geometry(),
                                allocation.getOffset(),
                                allocation.getLength()
                        ));
                        sidecarBytes = Math.addExact(
                                sidecarBytes,
                                Math.multiplyExact(
                                        allocation.getLength(),
                                        SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
                                )
                        );
                    } else {
                        sidecarBytes = Math.addExact(
                                sidecarBytes,
                                sidecarArena.metallum$enqueueInPlaceTerrainRefresh(
                                        mesh.geometry(),
                                        allocation.getOffset(),
                                        allocation.getLength()
                                )
                        );
                    }
                    meshCommandsByRefresh[refreshIndex] = Math.addExact(
                            meshCommandsByRefresh[refreshIndex],
                            1L
                    );
                    meshCommands = Math.addExact(meshCommands, 1L);
                }
            }
            this.stagingBuffer.flush();

            if (!lightPatches.isEmpty()) {
                SodiumLightLegacyPatchBatch.Status patchStatus;
                try {
                    patchStatus = SodiumLightLegacyPatchBatch.encode(lightPatches);
                } catch (Throwable throwable) {
                    SodiumTerrainLightPatch.fail("Metal legacy-light patch batch threw", throwable);
                    return;
                }
                if (patchStatus != SodiumLightLegacyPatchBatch.Status.ENCODED) {
                    SodiumTerrainLightPatch.fail(
                            "Metal legacy-light patch batch returned " + patchStatus,
                            null
                    );
                    return;
                }
                TorchEpochTelemetry.recordNativeLightPatch(1L, lightPatches.size());
            }

            for (Reference2ReferenceMap.Entry<RenderRegion, List<BuilderTaskOutput>> entry : cir.getReturnValue()) {
                entry.getValue().removeIf(reusable::containsKey);
            }
            IdentityHashMap<ChunkBuildOutput, SodiumTerrainInPlaceRefresh> successful = new IdentityHashMap<>();
            for (SodiumTerrainInPlaceRefresh refresh : refreshes) {
                successful.put(refresh.output(), refresh);
            }
            this.metallum$successfulRefreshes = successful;
            for (int refreshIndex = 0; refreshIndex < refreshes.size(); refreshIndex++) {
                RenderSection section = refreshes.get(refreshIndex).output().section;
                TorchEpochTelemetry.recordInPlaceGeometryRefresh(
                        SectionPos.asLong(
                                section.getChunkX(),
                                section.getChunkY(),
                                section.getChunkZ()
                        ),
                        geometryBytesByRefresh[refreshIndex],
                        meshCommandsByRefresh[refreshIndex]
                );
                if (refreshes.get(refreshIndex).lightOnly()) {
                    TorchEpochTelemetry.recordCompactLightPatchOutput(
                            SectionPos.asLong(
                                    section.getChunkX(),
                                    section.getChunkY(),
                                    section.getChunkZ()
                            ),
                            geometryBytesByRefresh[refreshIndex],
                            meshCommandsByRefresh[refreshIndex]
                    );
                }
            }
            TorchEpochTelemetry.recordSidecarUpload(sidecarBytes, meshCommands);
        } catch (Throwable throwable) {
            SodiumLightSidecar.fail("could not refresh resident terrain allocations in place", throwable);
        }
    }

    @Inject(
            method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("RETURN")
    )
    private void metallum$commitTerrainBaselines(
            final Collection<BuilderTaskOutput> results,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        IdentityHashMap<ChunkBuildOutput, SodiumTerrainUploadBaseline> baselines = this.metallum$pendingBaselines;
        IdentityHashMap<ChunkBuildOutput, SodiumTerrainInPlaceRefresh> successful =
                this.metallum$successfulRefreshes;
        this.metallum$pendingBaselines = null;
        this.metallum$successfulRefreshes = null;
        if (baselines == null) {
            return;
        }
        for (Map.Entry<ChunkBuildOutput, SodiumTerrainUploadBaseline> entry : baselines.entrySet()) {
            RenderSection section = entry.getKey().section;
            SodiumTerrainUploadBaseline baseline = entry.getValue();
            SodiumTerrainInPlaceRefresh refresh = successful == null ? null : successful.get(entry.getKey());
            boolean fastOutput = entry.getKey() instanceof SodiumRelightFastOutputSlot marker
                    && marker.metallum$isFastRelightOutput();
            if (refresh != null) {
                if (refresh.lightOnly()) {
                    if (fastOutput) {
                        // A synthetic relight output deliberately keeps the exact resident
                        // plan/baseline generation from which it was reconstructed.
                        baseline = refresh.residentBaseline();
                        SodiumRelightFastPath.recordCompactCommit();
                    } else if (SodiumTerrainLightPatch.isRuntimeActive()) {
                        // An ordinary Sodium remesh publishes a new recipe state at this
                        // output's info/generation. Re-capture the already verified static
                        // geometry so its baseline advances atomically with that state;
                        // retaining the old baseline here would permanently disable O6f.
                        try {
                            baseline = refresh.captureStaticGeometry(baseline);
                        } catch (Throwable throwable) {
                            SodiumTerrainLightPatch.fail(
                                    "could not advance exact resident terrain geometry",
                                    throwable
                            );
                        }
                    }
                } else if (SodiumTerrainLightPatch.isRuntimeActive()) {
                    try {
                        baseline = refresh.captureStaticGeometry(baseline);
                    } catch (Throwable throwable) {
                        SodiumTerrainLightPatch.fail("could not retain exact resident terrain geometry", throwable);
                    }
                }
            }
            if (fastOutput && (refresh == null || !refresh.lightOnly())) {
                SodiumRelightFastPath.recordFullUploadCommit();
            }
            if (!section.isDisposed()) {
                ((SodiumTerrainUploadBaselineAccess) section).metallum$setTerrainUploadBaseline(baseline);
            } else {
                baseline.close();
            }
        }
    }

    @Unique
    private SodiumTerrainUploadBaseline metallum$captureBaseline(final ChunkBuildOutput output) {
        TerrainRenderPass[] passes = DefaultTerrainRenderPasses.ALL;
        SodiumTerrainMeshLayout[] layouts = new SodiumTerrainMeshLayout[passes.length];
        for (int index = 0; index < passes.length; index++) {
            BuiltSectionMeshParts mesh = output.getMesh(passes[index]);
            if (mesh != null) {
                layouts[index] = SodiumTerrainMeshLayout.capture(
                        mesh.getVertexData().getLength(),
                        mesh.getVertexSegments()
                );
            }
        }
        return new SodiumTerrainUploadBaseline(output.info, layouts, output.submitTime);
    }

    @Unique
    @Nullable
    private SodiumTerrainInPlaceRefresh metallum$prepareRefresh(
            final RenderRegion region,
            final ChunkBuildOutput output,
            final SodiumTerrainUploadBaseline nextBaseline
    ) {
        RenderSection section = output.section;
        if (section.isDisposed() || output.containsNewIndexData()) {
            return null;
        }
        SodiumTerrainUploadBaseline resident = ((SodiumTerrainUploadBaselineAccess) section)
                .metallum$getTerrainUploadBaseline();
        if (resident == null || !resident.matchesUploadLayout(nextBaseline)) {
            return null;
        }

        RenderRegion.DeviceResources resources = region.getResources();
        if (resources == null) {
            return null;
        }
        GlBufferArena geometryArena = resources.getGeometryArena();
        GpuBuffer geometryBuffer = geometryArena.getBufferObject();
        GpuBuffer sidecar = SodiumLightSidecar.find(geometryBuffer);
        if (sidecar == null) {
            return null;
        }

        int sectionIndex = section.getSectionIndex();
        TerrainRenderPass[] passes = DefaultTerrainRenderPasses.ALL;
        List<SodiumTerrainInPlaceRefresh.Mesh> meshes = new ArrayList<>();
        ByteBuffer[] geometryByPass = new ByteBuffer[passes.length];
        for (int index = 0; index < passes.length; index++) {
            SodiumTerrainMeshLayout layout = nextBaseline.mesh(index);
            BuiltSectionMeshParts mesh = output.getMesh(passes[index]);
            if (layout == null) {
                if (mesh != null) {
                    return null;
                }
                continue;
            }
            if (mesh == null) {
                return null;
            }

            SectionRenderDataStorage storage = region.getStorage(passes[index]);
            if (storage == null) {
                return null;
            }
            GlBufferSegment allocation = ((SodiumSectionRenderDataAccess) storage)
                    .metallum$getVertexAllocation(sectionIndex);
            if (allocation == null) {
                return null;
            }
            SodiumBufferSegmentAccess segment = (SodiumBufferSegmentAccess) allocation;
            long allocationOffset = allocation.getOffset();
            long allocationLength = allocation.getLength();
            if (allocationOffset < 0L
                    || allocationLength <= 0L
                    || segment.metallum$isFree()
                    || segment.metallum$getArena() != geometryArena
                    || allocationLength != layout.vertexCount()) {
                return null;
            }

            ByteBuffer geometry = mesh.getVertexData().getDirectBuffer().duplicate();
            long geometryOffset = Math.multiplyExact(
                    allocationOffset,
                    SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
            );
            long sidecarOffset = Math.multiplyExact(
                    allocationOffset,
                    SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
            );
            long expectedSidecarBytes = Math.multiplyExact(
                    allocationLength,
                    SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
            );
            if (geometry.remaining() != layout.geometryBytes()
                    || Math.addExact(geometryOffset, geometry.remaining()) > geometryBuffer.size()
                    || Math.addExact(sidecarOffset, expectedSidecarBytes) > sidecar.size()) {
                return null;
            }
            geometryByPass[index] = geometry.duplicate();
            meshes.add(new SodiumTerrainInPlaceRefresh.Mesh(geometryArena, allocation, geometry));
        }
        if (meshes.isEmpty()) {
            return null;
        }
        boolean lightOnly = false;
        if (SodiumTerrainLightPatch.isRuntimeActive()) {
            try {
                lightOnly = resident.matchesStaticGeometry(geometryByPass);
            } catch (Throwable throwable) {
                SodiumTerrainLightPatch.fail("could not compare exact resident terrain geometry", throwable);
            }
        }
        return new SodiumTerrainInPlaceRefresh(
                output,
                meshes,
                resident,
                geometryByPass,
                lightOnly
        );
    }

    @Unique
    private static void metallum$validateNoOverlappingRanges(
            final List<SodiumTerrainInPlaceRefresh> refreshes
    ) {
        List<SodiumTerrainInPlaceRefresh.Mesh> meshes = new ArrayList<>();
        for (SodiumTerrainInPlaceRefresh refresh : refreshes) {
            for (SodiumTerrainInPlaceRefresh.Mesh mesh : refresh.meshes()) {
                long start = mesh.allocation().getOffset();
                long end = Math.addExact(start, mesh.allocation().getLength());
                for (SodiumTerrainInPlaceRefresh.Mesh previous : meshes) {
                    if (previous.arena() != mesh.arena()) {
                        continue;
                    }
                    long previousStart = previous.allocation().getOffset();
                    long previousEnd = Math.addExact(previousStart, previous.allocation().getLength());
                    if (start < previousEnd && previousStart < end) {
                        throw new IllegalStateException("resident terrain refresh ranges overlap");
                    }
                }
                meshes.add(mesh);
            }
        }
    }
}
