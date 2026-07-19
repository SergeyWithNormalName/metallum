package com.metallum.client.lighting;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.hdr.SodiumHdrSemantic;
import com.metallum.client.hdr.HeldItemEmission;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.temporal.FrameCapture;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-light race/property validation for the complete Java registry slice. */
public final class AdvancedLightRegistryTests {
    private static final String DIMENSION = "minecraft:overworld";

    private AdvancedLightRegistryTests() {
    }

    public static void main(final String[] args) {
        testMinecraftEmitterPolicy();
        testDenseBlockCompaction();
        testDirectLightFrustumClassification();
        testDirectFrameCompactsBackgroundGpuWork();
        testFullCandidatePoolCameraStability();
        testExactStaticScanAndSectionCap();
        testOverrideRacePermutations();
        testReplacementAndStaleDeleteOwnership();
        testWorldReloadAndUnloadLifecycle();
        testLifecycleOwnerSurvivesWorldTokenRotationPermutations();
        testLifecycleSafeCapacityOverflow();
        testDeterministicOrderingAndFrameCap();
        testRetainedAdmissionEpsilonAndInsertionPermutations();
        testRetainedAdmissionResetBoundaries();
        testDynamicCollectorBoundAndOrdering();
        testCameraHeldShadowSemantics();
        testPinnedDynamicExtractionHookDescriptor();
        testBlockOverridesDoNotStartStaticRegistryWork();
        testDisabledCollectionPathIsZeroWork();
        testReadinessCoverage();
        testRegistryFailureAdmissionGate();
        testStaleRegistryFailureCannotPoisonResetAdmission();
        System.out.println("Advanced light registry L3 race/bounds/property tests passed");
    }

    private static void testMinecraftEmitterPolicy() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int emittingBlockIds = 0;
        for (Holder.Reference<Block> reference : BuiltInRegistries.BLOCK.listElements().toList()) {
            boolean blockEmits = false;
            for (BlockState state : reference.value().getStateDefinition().getPossibleStates()) {
                int emission = MinecraftLightPolicy.effectiveEmission(state);
                LightTemplate template = MinecraftLightPolicy.block(state, 10, 20, 30);
                if (emission == 0) {
                    require(template == null,
                            "zero-emission state produced an Advanced light: " + reference.key());
                    continue;
                }
                blockEmits = true;
                float normalized = emission / 15.0F;
                float expectedIntensity = 0.15F
                        + 3.0F * normalized * (float) Math.sqrt(normalized);
                require(template != null
                                && template.x() == 10.5
                                && template.y() == 20.5
                                && template.z() == 30.5
                                && close(template.radius(), 1.5F + 0.75F * emission)
                                && close(template.intensity(), expectedIntensity)
                                && template.priority()
                                == MinecraftLightPolicy.priorityForEmission(emission),
                        "emitting state lost its data-driven level: " + reference.key()
                                + " emission=" + emission);
            }
            if (blockEmits) {
                emittingBlockIds++;
            }
        }
        require(emittingBlockIds == 109,
                "Minecraft 26.2 vanilla emitter census changed: " + emittingBlockIds);

        require(MinecraftLightPolicy.block(Blocks.SOUL_TORCH.defaultBlockState(), 0, 0, 0)
                        != null
                        && !MinecraftLightPolicy.block(
                        Blocks.SOUL_TORCH.defaultBlockState(), 0, 0, 0
                ).denseCellEligible()
                        && MinecraftLightPolicy.effectiveEmission(
                        Blocks.SOUL_TORCH.defaultBlockState()) == 10,
                "lower-emission soul torch was filtered out");
        require(HeldItemEmission.surfaceEmission(
                        Items.TORCH,
                        ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                ) == Blocks.TORCH.defaultBlockState().getLightEmission(),
                "held torch surface emission diverged from its dynamic-light block policy");
        require(HeldItemEmission.surfaceEmission(
                        Items.STONE,
                        ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                ) == 0,
                "non-emitting held block received surface emission");
        require(HeldItemEmission.surfaceEmission(
                        Items.TORCH,
                        ItemDisplayContext.GUI
                ) == 0,
                "held torch surface emission leaked into GUI rendering");
        require(MinecraftLightPolicy.block(Blocks.LAVA.defaultBlockState(), 0, 0, 0)
                        != null
                        && MinecraftLightPolicy.block(
                        Blocks.LAVA.defaultBlockState(), 0, 0, 0
                ).denseCellEligible()
                        && MinecraftLightPolicy.effectiveEmission(Blocks.LAVA.defaultBlockState())
                        == 15,
                "lava block/fluid cell did not resolve to one mergeable emitting template");
        require(MinecraftLightPolicy.block(Blocks.WATER.defaultBlockState(), 0, 0, 0)
                        == null,
                "non-emissive fluid produced an Advanced light");

        BlockState smallBud = Blocks.SMALL_AMETHYST_BUD.defaultBlockState();
        BlockState mediumBud = Blocks.MEDIUM_AMETHYST_BUD.defaultBlockState();
        BlockState largeBud = Blocks.LARGE_AMETHYST_BUD.defaultBlockState();
        BlockState cluster = Blocks.AMETHYST_CLUSTER.defaultBlockState();
        require(SodiumHdrSemantic.surfaceEmission(smallBud, smallBud.getLightEmission(), false) == 1
                        && SodiumHdrSemantic.surfaceEmission(mediumBud, mediumBud.getLightEmission(), false) == 2
                        && SodiumHdrSemantic.surfaceEmission(largeBud, largeBud.getLightEmission(), false) == 2
                        && SodiumHdrSemantic.surfaceEmission(cluster, cluster.getLightEmission(), false) == 2
                        && SodiumHdrSemantic.surfaceEmission(cluster, cluster.getLightEmission(), true) == 15,
                "amethyst surface HDR brightness should be capped without weakening exact emissives");
        LightTemplate clusterLight = MinecraftLightPolicy.block(cluster, 0, 0, 0);
        require(clusterLight != null
                        && MinecraftLightPolicy.effectiveEmission(cluster) == 5
                        && close(clusterLight.red(), 0.48F)
                        && close(clusterLight.green(), 0.12F)
                        && close(clusterLight.blue(), 1.0F),
                "amethyst surface HDR tuning must not change its violet direct light");

        float[] redstoneWall = MinecraftLightPolicy.linearColorForIdentifier(
                BuiltInRegistries.BLOCK.getKey(Blocks.REDSTONE_WALL_TORCH));
        require(close(redstoneWall[0], 1.0F)
                        && close(redstoneWall[1], 0.012F)
                        && close(redstoneWall[2], 0.003F),
                "redstone wall torch no longer shares the redstone hue");
        float[] firefly = MinecraftLightPolicy.linearColorForIdentifier(
                BuiltInRegistries.BLOCK.getKey(Blocks.FIREFLY_BUSH));
        require(!close(firefly[1], 0.08F) || !close(firefly[2], 0.004F),
                "firefly bush was misclassified as fire");
        float[] unknownMod = MinecraftLightPolicy.linearColorForIdentifier(
                Identifier.fromNamespaceAndPath("example", "soul_fire"));
        require(close(unknownMod[0], 1.0F)
                        && close(unknownMod[1], 1.0F)
                        && close(unknownMod[2], 1.0F),
                "unknown mod emitter did not receive the neutral compatibility fallback");
        float[] netherPortal = MinecraftLightPolicy.linearColorForIdentifier(
                BuiltInRegistries.BLOCK.getKey(Blocks.NETHER_PORTAL));
        require(close(netherPortal[0], 0.48F)
                        && close(netherPortal[1], 0.12F)
                        && close(netherPortal[2], 1.0F),
                "nether portal block does not emit a purple hue");
        require(MinecraftLightPolicy.priorityForEmission(1)
                        < MinecraftLightPolicy.priorityForEmission(15),
                "a dim held block can still outrank a full-brightness placed emitter");
    }

    private static void testDenseBlockCompaction() {
        List<AdvancedLight> plane = new ArrayList<>();
        long stableId = 1L;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                plane.add(lavaLike(stableId++, x, 0, z));
            }
        }
        DenseBlockLightCompactor.Result compacted = DenseBlockLightCompactor.compact(
                DIMENSION,
                plane
        );
        require(compacted.rawLightCount() == 256
                        && compacted.lights().size() == 16
                        && compacted.aggregateGroupCount() == 16
                        && compacted.mergedLightCount() == 240,
                "16x1x16 lava-like plane did not compact to one proxy per 4x4 cell");
        for (AdvancedLight member : plane) {
            require(compacted.lights().stream().anyMatch(proxy -> {
                double dx = member.x() - proxy.x();
                double dy = member.y() - proxy.y();
                double dz = member.z() - proxy.z();
                return Math.sqrt(dx * dx + dy * dy + dz * dz) + member.radius()
                        <= proxy.radius() + 1.0e-5;
            }), "dense proxy radius does not contain a member influence sphere");
        }
        float sourceIntensity = plane.getFirst().intensity();
        require(compacted.lights().stream().allMatch(proxy ->
                        proxy.intensity() > sourceIntensity
                                && proxy.intensity() < sourceIntensity * 16.0F),
                "dense proxy energy scaling is outside its source-count bound");
        double expectedPlaneEnergy = plane.stream()
                .mapToDouble(AdvancedLightRegistryTests::integratedRadialEnergy)
                .sum();
        double compactedPlaneEnergy = compacted.lights().stream()
                .mapToDouble(AdvancedLightRegistryTests::integratedRadialEnergy)
                .sum();
        require(relativeError(compactedPlaneEnergy, expectedPlaneEnergy) < 1.0e-5,
                "dense proxy did not preserve integrated radial energy");

        List<AdvancedLight> threeSources = plane.subList(0, 3);
        List<AdvancedLight> fourSources = plane.subList(0, 4);
        AdvancedLight fourProxy = DenseBlockLightCompactor.compact(
                DIMENSION,
                fourSources
        ).lights().getFirst();
        require(fourProxy.shadowEmitterFootprint().blocks().equals(List.of(
                        new ShadowEmitterFootprint.Block(0, 0, 0),
                        new ShadowEmitterFootprint.Block(1, 0, 0),
                        new ShadowEmitterFootprint.Block(2, 0, 0),
                        new ShadowEmitterFootprint.Block(3, 0, 0)
                ))
                        && fourProxy.emitsFromBlock(0, 0, 0)
                        && fourProxy.emitsFromBlock(3, 0, 0)
                        && !fourProxy.emitsFromBlock(4, 0, 0),
                "dense proxy did not retain its exact shadow-emitter footprint");
        require(DenseBlockLightCompactor.compact(DIMENSION, threeSources).lights().size() == 3
                        && DenseBlockLightCompactor.compact(DIMENSION, fourSources).lights().size()
                        == 1,
                "dense representation no longer uses the intended four-source threshold");
        double[][] transitionProbes = {
                {1.5, -1.5, 0.5},
                {-1.0, -1.5, 0.5},
                {5.0, -1.5, 0.5},
                {1.5, -5.5, 0.5}
        };
        for (double[] probe : transitionProbes) {
            double before = threeSources.stream().mapToDouble(light -> radialContribution(
                    light, probe[0], probe[1], probe[2]
            )).sum();
            double after = radialContribution(fourProxy, probe[0], probe[1], probe[2]);
            double ratio = after / before;
            require(ratio >= 0.75 && ratio <= 1.50,
                    "dense 3-to-4 transition changed radial light by " + ratio);
        }

        List<AdvancedLight> ordinaryBlocks = List.of(
                exactBlockLight(30_001L, 0.5, 0.5, 0.5),
                exactBlockLight(30_002L, 1.5, 0.5, 0.5),
                exactBlockLight(30_003L, 2.5, 0.5, 0.5),
                exactBlockLight(30_004L, 3.5, 0.5, 0.5)
        );
        require(DenseBlockLightCompactor.compact(DIMENSION, ordinaryBlocks).lights().size()
                        == ordinaryBlocks.size(),
                "ordinary block emitters were merged as if they were a dense fluid surface");
        require(ordinaryBlocks.stream().allMatch(light ->
                        light.shadowEmitterFootprint().isEmpty()),
                "ordinary block emitters unexpectedly gained an aggregate footprint");

        List<AdvancedLight> reversed = new ArrayList<>(plane);
        Collections.reverse(reversed);
        require(compacted.equals(DenseBlockLightCompactor.compact(DIMENSION, reversed)),
                "dense compaction changed with insertion order");

        AdvancedLight negative = lavaLike(10_000L, -1, -1, -1);
        DenseBlockLightCompactor.GroupKey negativeKey =
                DenseBlockLightCompactor.groupKey(negative);
        require(negativeKey.cellX() == -4
                        && negativeKey.cellY() == -4
                        && negativeKey.cellZ() == -4,
                "dense compaction did not use floor-aligned cells at negative coordinates");

        List<AdvancedLight> mixed = new ArrayList<>(plane.subList(0, 4));
        AdvancedLight differentHue = new AdvancedLight(
                20_000L,
                1L,
                LightSourceKind.BLOCK,
                0.5,
                0.5,
                0.5,
                plane.getFirst().radius(),
                0.035F,
                0.34F,
                1.0F,
                plane.getFirst().intensity(),
                plane.getFirst().priority()
        );
        mixed.add(differentHue);
        DenseBlockLightCompactor.Result mixedResult = DenseBlockLightCompactor.compact(
                DIMENSION,
                mixed
        );
        require(mixedResult.lights().size() == 2
                        && mixedResult.aggregateGroupCount() == 1
                        && mixedResult.lights().stream().anyMatch(light ->
                        light.stableId() == differentHue.stableId()),
                "dense compaction merged different photometric signatures");

        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        LightSectionTask denseTask = registry.beginSectionTask(world, DIMENSION, 77L);
        LightSectionCandidate denseCandidate = StaticLightSectionScanner.scan(
                denseTask,
                0,
                0,
                0,
                AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                (localIndex, x, y, z) -> new LightTemplate(
                        LightSourceKind.BLOCK,
                        x + 0.5,
                        y + 0.5,
                        z + 0.5,
                        12.75F,
                        1.0F,
                        0.08F,
                        0.004F,
                        3.15F,
                        240,
                        true
                )
        );
        registry.noteStaticScan(denseCandidate);
        require(denseCandidate.emittedLightCount() == 4096
                        && denseCandidate.entries().size() == 4096
                        && denseCandidate.droppedLightCount() == 0,
                "dense section lost members before aggregation");
        require(registry.publishAccepted(denseCandidate),
                "stratified dense section was not accepted");
        LightFrameSnapshot denseSnapshot = registry.snapshotForFrame(0, 0, 0, 256);
        require(denseSnapshot.lights().size() == 64,
                "full 16^3 dense section did not compact to all 64 spatial cells");
        double fullSectionEnergy = denseSnapshot.lights().stream()
                .mapToDouble(AdvancedLightRegistryTests::integratedRadialEnergy)
                .sum();
        double oneLavaEnergy = integratedRadialEnergy(lavaLike(40_000L, 0, 0, 0));
        require(relativeError(fullSectionEnergy, oneLavaEnergy * 4096.0) < 1.0e-5,
                "full dense section lost integrated energy during compaction");
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    AdvancedLight member = lavaLike(50_000L + (y << 8 | z << 4 | x), x, y, z);
                    require(denseSnapshot.lights().stream().anyMatch(proxy -> {
                        double dx = member.x() - proxy.x();
                        double dy = member.y() - proxy.y();
                        double dz = member.z() - proxy.z();
                        return Math.sqrt(dx * dx + dy * dy + dz * dz) + member.radius()
                                <= proxy.radius() + 1.0e-5;
                    }), "full dense section proxy support lost a fluid cell");
                }
            }
        }
        require(registry.telemetry().sectionLightOverflows() == 0L,
                "represented dense cells were reported as section overflow");

        long soulStableId = 99_999L;
        registry.recordBlockChange(
                world,
                DIMENSION,
                77L,
                0,
                soulStableId,
                new LightTemplate(
                        LightSourceKind.BLOCK,
                        0.5,
                        0.5,
                        0.5,
                        9.0F,
                        0.035F,
                        0.34F,
                        1.0F,
                        1.78F,
                        160
                )
        );
        LightFrameSnapshot withSoul = registry.snapshotForFrame(0, 0, 0, 128);
        require(stableIds(withSoul.lights()).contains(soulStableId),
                "dense bright emitters still displaced a nearby lower-emission source");
        require(new HashSet<>(stableIds(withSoul.lights())).equals(new HashSet<>(stableIds(
                        registry.snapshotForFrame(200, 80, -200, 128).lights()
                ))),
                "camera motion changed the compacted source set without an admission overflow");
        registry.recordBlockChange(world, DIMENSION, 77L, 0, soulStableId, null);
        require(!stableIds(registry.snapshotForFrame(0, 0, 0, 128).lights())
                        .contains(soulStableId),
                "dense-section cache retained a removed source");
    }

    private static void testDirectLightFrustumClassification() {
        DirectLightFrustum forward = directLightFrustum(Matrix4.identity());
        AdvancedLight intersectingEdge = exactTemplate(
                1,
                11.0,
                0.0,
                -10.0,
                2.0F,
                0.01F
        ).materialize(1L, 1L);
        AdvancedLight outsideGuard = exactTemplate(
                240,
                20.0,
                0.0,
                -10.0,
                1.0F,
                10.0F
        ).materialize(2L, 1L);
        AdvancedLight guardBand = exactTemplate(
                240,
                11.5,
                0.0,
                -10.0,
                1.0F,
                10.0F
        ).materialize(6L, 1L);
        AdvancedLight behindCamera = exactTemplate(
                240,
                0.0,
                0.0,
                10.0,
                1.0F,
                10.0F
        ).materialize(3L, 1L);
        AdvancedLight cameraInside = exactTemplate(
                1,
                0.0,
                0.0,
                0.0,
                2.0F,
                0.01F
        ).materialize(4L, 1L);
        AdvancedLight crossesFarPlane = exactTemplate(
                1,
                0.0,
                0.0,
                -104.0,
                5.0F,
                0.01F
        ).materialize(5L, 1L);
        require(forward.classify(intersectingEdge) == DirectLightFrustum.Tier.INTERSECTING,
                "direct-light visibility tested the emitter point instead of its influence sphere");
        require(forward.classify(outsideGuard) == DirectLightFrustum.Tier.BACKGROUND,
                "fully off-frustum influence sphere was not classified as background");
        require(forward.classify(guardBand) == DirectLightFrustum.Tier.GUARD_BAND,
                "one-block direct-light guard band was not classified separately");
        require(forward.classify(behindCamera) == DirectLightFrustum.Tier.BACKGROUND,
                "light fully behind the camera was not classified as background");
        require(forward.classify(cameraInside) == DirectLightFrustum.Tier.INTERSECTING,
                "camera-inside influence sphere was incorrectly culled");
        require(forward.classify(crossesFarPlane) == DirectLightFrustum.Tier.INTERSECTING,
                "sphere crossing the far plane was culled by its center");

        Matrix4 degenerateView = Matrix4.of(
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0
        );
        require(directLightFrustum(degenerateView).classify(outsideGuard)
                        == DirectLightFrustum.Tier.INTERSECTING,
                "invalid view data did not fail open to a protected light");
    }

    private static void testFullCandidatePoolCameraStability() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        long dimVisibleId = AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS;
        for (int index = 0; index < AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS; index++) {
            long stableId = index + 1L;
            registry.recordBlockChange(
                    world,
                    DIMENSION,
                    index / 256,
                    index & 255,
                    stableId,
                    exactTemplate(
                            stableId == dimVisibleId ? 1 : 100,
                            0.0,
                            0.0,
                            -10.0,
                            2.5F,
                            stableId == dimVisibleId ? 0.01F : 1.0F
                    )
            );
        }
        DirectLightFrustum forward = directLightFrustum(Matrix4.identity());
        DirectLightFrustum backward = directLightFrustum(yaw180View());
        LightFrameSnapshot forwardComplete = registry.snapshotForFrame(
                forward,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        LightFrameSnapshot backwardComplete = registry.snapshotForFrame(
                backward,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(forwardComplete.lights().size() == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && forwardComplete.droppedLightCount() == 0
                        && backwardComplete.lights().isEmpty()
                        && backwardComplete.droppedLightCount() == 0
                        && registry.telemetry().directVisibilityCulls()
                        == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && registry.telemetry().frameLightOverflows() == 0L,
                "background-only direct frame was uploaded or reported as overflow");

        LightFrameSnapshot legacyComplete = registry.snapshotForFrame(
                0.0,
                0.0,
                0.0,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(legacyComplete.lights().equals(forwardComplete.lights())
                        && legacyComplete.droppedLightCount() == 0,
                "direct visibility filtering leaked into the camera-independent snapshot API");

        long brightBehindId = dimVisibleId + 1L;
        registry.recordBlockChange(
                world,
                DIMENSION,
                16L,
                0,
                brightBehindId,
                exactTemplate(240, 0.0, 0.0, 10.0, 2.5F, 10.0F)
        );
        LightFrameSnapshot forwardOverflow = registry.snapshotForFrame(
                forward,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(forwardOverflow.droppedLightCount() == 0
                        && stableIds(forwardOverflow.lights()).contains(dimVisibleId)
                        && !stableIds(forwardOverflow.lights()).contains(brightBehindId)
                        && registry.telemetry().directVisibilityCulls()
                        == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS + 1L
                        && registry.telemetry().frameLightOverflows() == 0L
                        && registry.telemetry().protectedFrameLightOverflows() == 0L,
                "bright background light displaced a dim visible influence sphere");

        LightFrameSnapshot backwardOverflow = registry.snapshotForFrame(
                backward,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(backwardOverflow.lights().size() == 1
                        && backwardOverflow.droppedLightCount() == 0
                        && stableIds(backwardOverflow.lights()).contains(brightBehindId)
                        && !stableIds(backwardOverflow.lights()).contains(dimVisibleId)
                        && registry.telemetry().directVisibilityCulls()
                        == 2L * AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS + 1L
                        && registry.telemetry().frameLightOverflows() == 0L
                        && registry.telemetry().protectedFrameLightOverflows() == 0L,
                "camera rotation did not refresh direct-light visibility admission");

        registry.recordBlockChange(
                world,
                DIMENSION,
                16L,
                1,
                brightBehindId + 1L,
                exactTemplate(240, 0.0, 0.0, -10.0, 2.5F, 10.0F)
        );
        LightFrameSnapshot protectedOverflow = registry.snapshotForFrame(
                forward,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(protectedOverflow.lights().size()
                        == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && protectedOverflow.droppedLightCount() == 1
                        && registry.telemetry().frameLightOverflows() == 1L
                        && registry.telemetry().protectedFrameLightOverflows() == 1L,
                "overflow of more than 4096 potentially visible influence spheres was hidden");
    }

    private static void testDirectFrameCompactsBackgroundGpuWork() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        LightWorldToken token = registry.openWorld(world, DIMENSION);
        int staticBackgroundCount = AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS - 1;
        for (int index = 0; index < staticBackgroundCount; index++) {
            registry.recordBlockChange(
                    world,
                    DIMENSION,
                    index / 256,
                    index & 255,
                    index + 1L,
                    exactTemplate(240, 0.0, 0.0, 10.0, 1.0F, 10.0F)
            );
        }

        List<Long> eligibleIds = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            long stableId = 10_000L + index;
            eligibleIds.add(stableId);
            registry.recordBlockChange(
                    world,
                    DIMENSION,
                    16L,
                    index,
                    stableId,
                    exactTemplate(1, index - 4.0, 0.0, -10.0, 0.5F, 0.01F)
            );
        }
        long staticGuardId = 10_009L;
        eligibleIds.add(staticGuardId);
        registry.recordBlockChange(
                world,
                DIMENSION,
                16L,
                9,
                staticGuardId,
                exactTemplate(1, 11.5, 0.0, -10.0, 1.0F, 0.01F)
        );

        AdvancedLight dynamicVisible = new AdvancedLight(
                20_000L,
                token.generation(),
                LightSourceKind.ENTITY,
                0.0,
                0.0,
                -10.0,
                0.5F,
                1.0F,
                0.25F,
                0.05F,
                0.01F,
                1
        );
        AdvancedLight dynamicGuard = new AdvancedLight(
                20_001L,
                token.generation(),
                LightSourceKind.ENTITY,
                -11.5,
                0.0,
                -10.0,
                1.0F,
                1.0F,
                0.25F,
                0.05F,
                0.01F,
                1
        );
        AdvancedLight dynamicBackground = new AdvancedLight(
                20_002L,
                token.generation(),
                LightSourceKind.ENTITY,
                0.0,
                0.0,
                10.0,
                1.0F,
                1.0F,
                0.25F,
                0.05F,
                10.0F,
                240
        );
        eligibleIds.add(dynamicVisible.stableId());
        eligibleIds.add(dynamicGuard.stableId());
        registry.publishDynamicFrame(
                token,
                List.of(dynamicVisible, dynamicGuard, dynamicBackground),
                3
        );

        DirectLightFrustum forward = directLightFrustum(Matrix4.identity());
        require(forward.classify(dynamicVisible) == DirectLightFrustum.Tier.INTERSECTING
                        && forward.classify(dynamicGuard) == DirectLightFrustum.Tier.GUARD_BAND
                        && forward.classify(dynamicBackground) == DirectLightFrustum.Tier.BACKGROUND,
                "mixed direct-light compaction fixture has invalid frustum tiers");
        LightFrameSnapshot compact = registry.snapshotForFrame(
                forward,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(compact.lights().size() == 12
                        && compact.staticLightCount() == 10
                        && compact.dynamicLightCount() == 2
                        && compact.droppedLightCount() == 0
                        && new HashSet<>(stableIds(compact.lights()))
                        .equals(new HashSet<>(eligibleIds))
                        && compact.lights().stream().noneMatch(
                                light -> forward.classify(light)
                                == DirectLightFrustum.Tier.BACKGROUND
                        )
                        && registry.telemetry().directVisibilityCulls()
                        == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && registry.telemetry().frameLightOverflows() == 0L,
                "direct frame did not compact static and dynamic background GPU work");

        List<Long> firstUploadOrder = stableIds(compact.lights());
        LightFrameSnapshot backward = registry.snapshotForFrame(
                directLightFrustum(yaw180View()),
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        LightFrameSnapshot forwardAgain = registry.snapshotForFrame(
                forward,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(backward.lights().size() == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && backward.staticLightCount() == staticBackgroundCount
                        && backward.dynamicLightCount() == 1
                        && firstUploadOrder.equals(stableIds(forwardAgain.lights())),
                "camera turn did not restore compacted registry candidates and upload order");

        long cullsBeforeLegacySnapshot = registry.telemetry().directVisibilityCulls();
        LightFrameSnapshot legacy = registry.snapshotForFrame(
                0.0,
                0.0,
                0.0,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
        );
        require(legacy.lights().size() == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && legacy.droppedLightCount() == 12
                        && registry.telemetry().directVisibilityCulls()
                        == cullsBeforeLegacySnapshot,
                "legacy snapshot API unexpectedly applied direct visibility compaction");
    }

    private static void testExactStaticScanAndSectionCap() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        LightSectionTask task = registry.beginSectionTask(world, DIMENSION, 7L);
        boolean[] visited = new boolean[StaticLightSectionScanner.BLOCKS_PER_SECTION];
        AtomicInteger calls = new AtomicInteger();
        LightSectionCandidate candidate = StaticLightSectionScanner.scan(
                task,
                32,
                -16,
                48,
                AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                (localIndex, x, y, z) -> {
                    require(!visited[localIndex], "static scanner revisited local index " + localIndex);
                    visited[localIndex] = true;
                    calls.incrementAndGet();
                    return template(100 + (localIndex & 7), x, y, z);
                }
        );
        registry.noteStaticScan(candidate);
        require(calls.get() == 4096 && candidate.scannedStateCount() == 4096,
                "full section was not scanned exactly once");
        require(candidate.emittedLightCount() == 4096
                        && candidate.entries().size() == AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION
                        && candidate.droppedLightCount() == 0,
                "static section cap/overflow mismatch");
        require(registry.publishAccepted(candidate), "exact static candidate was not accepted");
        LightFrameSnapshot snapshot = registry.snapshotForFrame(0.0, 0.0, 0.0, 4096);
        require(snapshot.staticLightCount() == AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                "accepted static section lost retained lights");
        LightRegistryTelemetry telemetry = registry.telemetry();
        require(telemetry.staticSectionsScanned() == 1L
                        && telemetry.staticStatesScanned() == 4096L
                        && telemetry.sectionLightOverflows() == 0L,
                "static scan telemetry mismatch: " + telemetry);
    }

    private static void testOverrideRacePermutations() {
        Random random = new Random(0x4c334c49474854L);
        for (int iteration = 0; iteration < 512; iteration++) {
            int iterationId = iteration;
            int localIndex = random.nextInt(4096);
            int priority = 1 + random.nextInt(500);
            boolean replacementPresent = random.nextBoolean();
            for (boolean overrideBeforePublish : new boolean[]{false, true}) {
                AdvancedLightRegistry registry = new AdvancedLightRegistry();
                Object world = new Object();
                long sectionKey = 1000L + iteration;
                LightSectionTask task = registry.beginSectionTask(world, DIMENSION, sectionKey);
                LightSectionCandidate base = candidate(
                        task,
                        Map.of(localIndex, template(priority - 1, localIndex, 0, 0))
                );
                Runnable override = () -> registry.recordBlockChange(
                        world,
                        DIMENSION,
                        sectionKey,
                        localIndex,
                        StableLightIds.block(DIMENSION, iterationId, localIndex, 3),
                        replacementPresent ? template(priority, iterationId, localIndex, 3) : null
                );
                if (overrideBeforePublish) {
                    override.run();
                }
                require(registry.publishAccepted(base), "race base candidate was rejected");
                if (!overrideBeforePublish) {
                    override.run();
                }
                LightFrameSnapshot snapshot = registry.snapshotForFrame(0.0, 0.0, 0.0, 16);
                require(snapshot.lights().size() == (replacementPresent ? 1 : 0),
                        "post-task override was lost in permutation " + overrideBeforePublish);
                if (replacementPresent) {
                    require(snapshot.lights().getFirst().priority() == priority,
                            "base snapshot overwrote a newer block override");
                }
            }
        }

        // Overrides at or before baseEpoch are already represented by the cloned base and expire.
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        registry.recordBlockChange(world, DIMENSION, 5L, 2, 50L, template(50, 0, 0, 0));
        LightSectionTask task = registry.beginSectionTask(world, DIMENSION, 5L);
        require(registry.publishAccepted(candidate(task, Map.of(2, template(60, 0, 0, 0)))),
                "post-override base was rejected");
        require(registry.snapshotForFrame(0, 0, 0, 8).lights().getFirst().priority() == 60,
                "an override already incorporated by the clone survived publication");
    }

    private static void testReplacementAndStaleDeleteOwnership() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        long sectionKey = 77L;
        LightSectionTask oldTask = registry.beginSectionTask(world, DIMENSION, sectionKey);
        LightSectionTask replacementTask = registry.beginSectionTask(world, DIMENSION, sectionKey);
        LightSectionCandidate oldCandidate = candidate(oldTask, Map.of(1, template(10, 0, 0, 0)));
        LightSectionCandidate replacement = candidate(
                replacementTask,
                Map.of(1, template(99, 0, 0, 0))
        );
        require(registry.publishAccepted(replacement), "replacement was not accepted");
        require(!registry.publishAccepted(oldCandidate), "older owner replaced a newer accepted output");
        require(registry.snapshotForFrame(0, 0, 0, 8).lights().getFirst().priority() == 99,
                "stale publication changed resident state");

        require(!registry.removeSectionIfOwner(world, sectionKey, oldTask.ownerToken()),
                "old RenderSection owner deleted its replacement");
        require(registry.snapshotForFrame(0, 0, 0, 8).lights().size() == 1,
                "stale delete removed replacement lights");
        require(registry.removeSectionIfOwner(world, sectionKey, replacementTask.ownerToken()),
                "current owner could not remove its section");
        require(registry.snapshotForFrame(0, 0, 0, 8).lights().isEmpty(),
                "owned section delete left resident lights");

        require(!replacement.discard(), "published candidate could be discarded twice");
        require(!oldCandidate.discard(), "claimed stale candidate could be discarded twice");
    }

    private static void testWorldReloadAndUnloadLifecycle() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object oldWorld = new Object();
        LightSectionTask stale = registry.beginSectionTask(oldWorld, DIMENSION, 1L);
        LightSectionCandidate staleCandidate = candidate(stale, Map.of(0, template(1, 0, 0, 0)));
        LightWorldToken beforeReload = stale.world();
        LightWorldToken afterReload = registry.reloadWorld(oldWorld, DIMENSION);
        require(!beforeReload.equals(afterReload), "resource reload did not rotate world token");
        require(!registry.publishAccepted(staleCandidate), "pre-reload worker output was accepted");

        registry.recordBlockChange(oldWorld, DIMENSION, 2L, 3, 3L, template(3, 0, 0, 0));
        require(registry.removeSectionIfOwner(oldWorld, 2L, 0L),
                "override-only section did not use the zero lifecycle owner");

        Object replacementWorld = new Object();
        LightWorldToken replacement = registry.openWorld(replacementWorld, DIMENSION);
        registry.closeWorld(oldWorld);
        require(registry.beginSectionTask(replacementWorld, DIMENSION, 9L).world().equals(replacement),
                "stale old-world close closed the replacement instance");
        registry.closeWorld(replacementWorld);
        require(registry.snapshotForFrame(0, 0, 0, 8).lights().isEmpty(),
                "world close retained a snapshot");
    }

    private static void testLifecycleOwnerSurvivesWorldTokenRotationPermutations() {
        for (boolean resourceReload : new boolean[]{false, true}) {
            AdvancedLightRegistry registry = new AdvancedLightRegistry();
            Object world = new Object();
            long sectionKey = 29L;
            LightSectionTask oldTask = registry.beginSectionTask(world, DIMENSION, sectionKey);
            require(registry.publishAccepted(candidate(
                    oldTask,
                    Map.of(1, template(1, 0, 0, 0))
            )), "pre-rotation owner was not published");

            LightWorldToken rotated;
            if (resourceReload) {
                rotated = registry.reloadWorld(world, DIMENSION);
            } else {
                registry.clear();
                rotated = registry.openWorld(world, DIMENSION);
            }
            require(!rotated.equals(oldTask.world()),
                    "world lifecycle did not rotate the registry token");
            registry.recordBlockChange(
                    world,
                    DIMENSION,
                    sectionKey,
                    2,
                    2L,
                    template(2, 0, 0, 0)
            );
            require(registry.currentOwnerToken(rotated, sectionKey) == oldTask.ownerToken(),
                    "post-rotation override lost its live RenderSection owner");
            require(!registry.removeSectionIfOwner(world, sectionKey, 0L),
                    "zero owner deleted a section still owned by the live RenderSection");
            require(registry.removeSectionIfOwner(world, sectionKey, oldTask.ownerToken()),
                    "live RenderSection owner could not unload its post-rotation override");
            require(registry.snapshotForFrame(0, 0, 0, 8).lights().isEmpty()
                            && registry.telemetry().residentSections() == 0,
                    "post-rotation section unload left an override-only ghost");
        }
    }

    private static void testLifecycleSafeCapacityOverflow() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry(2);
        Object world = new Object();
        LightSectionTask first = registry.beginSectionTask(world, DIMENSION, 1L);
        LightSectionTask second = registry.beginSectionTask(world, DIMENSION, 2L);
        LightSectionTask overflow = registry.beginSectionTask(world, DIMENSION, 3L);
        require(registry.publishAccepted(candidate(first, Map.of(0, template(1, 0, 0, 0)))),
                "first bounded section was rejected");
        require(registry.publishAccepted(candidate(second, Map.of(0, template(2, 16, 0, 0)))),
                "second bounded section was rejected");
        require(!registry.publishAccepted(candidate(overflow, Map.of(0, template(3, 32, 0, 0)))),
                "capacity overflow displaced a live section");

        registry.recordBlockChange(world, DIMENSION, 1L, 0, 91L,
                template(9, 0, 0, 0));
        require(registry.removeSectionIfOwner(world, 1L, first.ownerToken()),
                "live section lost its lifecycle owner after capacity overflow");
        require(registry.removeSectionIfOwner(world, 2L, second.ownerToken()),
                "second live section could not unload after capacity overflow");
        require(registry.snapshotForFrame(0, 0, 0, 8).lights().isEmpty(),
                "capacity overflow or unload left a ghost light");
        require(registry.telemetry().residentSectionCapacityDrops() == 1L,
                "bounded registry did not report its deterministic capacity drop");

        LightSectionTask empty = registry.beginSectionTask(world, DIMENSION, 4L);
        require(registry.publishAccepted(candidate(empty, Map.of()))
                        && registry.currentOwnerToken(empty.world(), 4L) == 0L,
                "empty section consumed resident capacity or retained an owner");
    }

    private static void testDeterministicOrderingAndFrameCap() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        List<Long> ids = new ArrayList<>();
        for (long id = 1L; id <= 64L; id++) {
            ids.add(id);
        }
        ids.add(-1L);
        Collections.shuffle(ids, new Random(33L));
        for (int index = 0; index < ids.size(); index++) {
            long id = ids.get(index);
            registry.recordBlockChange(
                    world,
                    DIMENSION,
                    index / 16,
                    index & 15,
                    id,
                    template(index % 4, index, 0, 0)
            );
        }
        LightFrameSnapshot first = registry.snapshotForFrame(100, 200, 300, 17);
        LightFrameSnapshot second = registry.snapshotForFrame(100, 200, 300, 17);
        require(first.lights().equals(second.lights()),
                "same camera produced a different deterministic overflow order");
        require(first.lights().size() == 17 && first.droppedLightCount() == ids.size() - 17,
                "frame top-K cap/dropped count mismatch");
        for (int index = 1; index < first.lights().size(); index++) {
            require(FrameLightOrder.comparator(100, 200, 300).compare(
                    first.lights().get(index - 1),
                    first.lights().get(index)
            ) <= 0, "frame snapshot order is not deterministic");
        }
        List<AdvancedLight> shuffled = new ArrayList<>(first.lights());
        Collections.shuffle(shuffled, new Random(94L));
        shuffled.sort(FrameLightOrder.comparator(100, 200, 300));
        require(shuffled.equals(first.lights()), "published order is not canonical");

        AdvancedLightRegistry nearFar = new AdvancedLightRegistry();
        Object nearFarWorld = new Object();
        nearFar.recordBlockChange(
                nearFarWorld,
                DIMENSION,
                1L,
                1,
                1L,
                template(10, 1000, 0, 0)
        );
        nearFar.recordBlockChange(
                nearFarWorld,
                DIMENSION,
                2L,
                2,
                999L,
                template(10, 2, 0, 0)
        );
        require(nearFar.snapshotForFrame(0, 0, 0, 1).lights().getFirst().stableId() == 999L,
                "stable-id tie-break incorrectly kept a far light over a near light");

        AdvancedLightRegistry permuted = new AdvancedLightRegistry();
        Object permutedWorld = new Object();
        List<Long> reverse = new ArrayList<>(ids);
        Collections.reverse(reverse);
        for (int index = 0; index < reverse.size(); index++) {
            long id = reverse.get(index);
            int originalIndex = ids.indexOf(id);
            permuted.recordBlockChange(
                    permutedWorld,
                    DIMENSION,
                    originalIndex / 16,
                    originalIndex & 15,
                    id,
                    template(originalIndex % 4, originalIndex, 0, 0)
            );
        }
        require(stableIds(first.lights()).equals(stableIds(
                        permuted.snapshotForFrame(100, 200, 300, 17).lights()
                )),
                "insertion permutation changed the camera-relative top-K result");

        AdvancedLight narrowNear = new AdvancedLight(
                700L,
                1L,
                LightSourceKind.BLOCK,
                5.0,
                0.0,
                0.0,
                2.0F,
                1.0F,
                1.0F,
                1.0F,
                8.0F,
                20
        );
        AdvancedLight wideFar = new AdvancedLight(
                701L,
                1L,
                LightSourceKind.BLOCK,
                6.0,
                0.0,
                0.0,
                4.0F,
                1.0F,
                1.0F,
                1.0F,
                2.0F,
                20
        );
        require(FrameLightOrder.significance(narrowNear)
                        == FrameLightOrder.significance(wideFar)
                        && FrameLightOrder.comparator(0.0, 0.0, 0.0).compare(
                        wideFar,
                        narrowNear
                ) < 0,
                "frame relevance ignored the nearer edge of a larger influence volume");

        AdvancedLight minorMaterialGain = new AdvancedLight(
                702L,
                1L,
                LightSourceKind.BLOCK,
                5.0,
                0.0,
                0.0,
                2.0F,
                1.0F,
                1.0F,
                1.0F,
                8.8F,
                20
        );
        AdvancedLight majorMaterialGain = new AdvancedLight(
                703L,
                1L,
                LightSourceKind.BLOCK,
                5.0,
                0.0,
                0.0,
                2.0F,
                1.0F,
                1.0F,
                1.0F,
                10.0F,
                20
        );
        require(!FrameLightOrder.materiallyOutranks(
                        minorMaterialGain,
                        narrowNear,
                        0.0,
                        0.0,
                        0.0
                ) && FrameLightOrder.materiallyOutranks(
                        majorMaterialGain,
                        narrowNear,
                        0.0,
                        0.0,
                        0.0
                ),
                "material replacement margin does not reject noise and admit a material gain");
    }

    private static void testRetainedAdmissionEpsilonAndInsertionPermutations() {
        final double epsilon = 0.001;
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        addAdmissionBoundaryLights(registry, world, DIMENSION, false);

        LightFrameSnapshot seeded = registry.snapshotForFrame(-epsilon, 0.0, 0.0, 3, 2);
        require(stableIds(seeded.lights().subList(0, 2)).equals(List.of(100L, 300L)),
                "retained admission seed did not select the camera-relative boundary pair");
        LightFrameSnapshot epsilonMotion = registry.snapshotForFrame(
                epsilon,
                0.0,
                0.0,
                3,
                2
        );
        require(stableIds(epsilonMotion.lights().subList(0, 2)).equals(
                        stableIds(seeded.lights().subList(0, 2))),
                "epsilon camera motion changed the retained admission set or order");
        List<AdvancedLight> canonicalPrefix = new ArrayList<>(
                epsilonMotion.lights().subList(0, 2)
        );
        canonicalPrefix.sort(FrameLightOrder.admissionComparator());
        require(canonicalPrefix.equals(epsilonMotion.lights().subList(0, 2)),
                "retained prefix is not camera-independent priority/significance/stable-id order");

        AdvancedLightRegistry freshAtOppositeEpsilon = new AdvancedLightRegistry();
        Object freshWorld = new Object();
        addAdmissionBoundaryLights(
                freshAtOppositeEpsilon,
                freshWorld,
                DIMENSION,
                false
        );
        require(stableIds(freshAtOppositeEpsilon.snapshotForFrame(
                epsilon,
                0.0,
                0.0,
                3,
                2
        ).lights().subList(0, 2)).equals(List.of(200L, 300L)),
                "epsilon fixture does not cross a raw camera-relative admission boundary");

        AdvancedLightRegistry permuted = new AdvancedLightRegistry();
        Object permutedWorld = new Object();
        addAdmissionBoundaryLights(permuted, permutedWorld, DIMENSION, true);
        LightFrameSnapshot permutedSeed = permuted.snapshotForFrame(
                -epsilon,
                0.0,
                0.0,
                3,
                2
        );
        require(stableIds(permutedSeed.lights().subList(0, 2)).equals(
                        stableIds(seeded.lights().subList(0, 2))),
                "retained admission seed changed under insertion permutation");
        require(stableIds(permuted.snapshotForFrame(
                epsilon,
                0.0,
                0.0,
                3,
                2
        ).lights().subList(0, 2)).equals(stableIds(seeded.lights().subList(0, 2))),
                "retained epsilon result changed under insertion permutation");

        LightFrameSnapshot materialMove = registry.snapshotForFrame(10.0, 0.0, 0.0, 3, 2);
        require(stableIds(materialMove.lights().subList(0, 2)).equals(List.of(200L, 300L)),
                "material influence-distance improvement did not replace a retained light");
    }

    private static void testRetainedAdmissionResetBoundaries() {
        final double epsilon = 0.001;

        AdvancedLightRegistry cameraCut = new AdvancedLightRegistry();
        Object cutWorld = new Object();
        addAdmissionBoundaryLights(cameraCut, cutWorld, DIMENSION, false);
        require(cameraCut.snapshotForFrame(-epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 100L,
                "camera-cut fixture did not seed the left light");
        double cutCameraX = AdvancedLightRegistry.RETAINED_ADMISSION_CAMERA_CUT_DISTANCE + 10.0;
        require(cameraCut.snapshotForFrame(cutCameraX, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 200L,
                "large camera cut did not reset retained admission");

        AdvancedLightRegistry limitChange = new AdvancedLightRegistry();
        Object limitWorld = new Object();
        addAdmissionBoundaryLights(limitChange, limitWorld, DIMENSION, false);
        require(limitChange.snapshotForFrame(-epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 100L,
                "preset-limit fixture did not seed the left light");
        require(stableIds(limitChange.snapshotForFrame(
                epsilon,
                0.0,
                0.0,
                3,
                2
        ).lights().subList(0, 2)).equals(List.of(200L, 300L)),
                "admission-limit change did not reset retained selection");

        AdvancedLightRegistry reload = new AdvancedLightRegistry();
        Object reloadWorld = new Object();
        addAdmissionBoundaryLights(reload, reloadWorld, DIMENSION, false);
        require(reload.snapshotForFrame(-epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 100L,
                "reload fixture did not seed the left light");
        reload.reloadWorld(reloadWorld, DIMENSION);
        addAdmissionBoundaryLights(reload, reloadWorld, DIMENSION, false);
        require(reload.snapshotForFrame(epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 200L,
                "resource reload retained the previous admission selection");

        AdvancedLightRegistry dimension = new AdvancedLightRegistry();
        Object dimensionWorld = new Object();
        addAdmissionBoundaryLights(dimension, dimensionWorld, DIMENSION, false);
        require(dimension.snapshotForFrame(-epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 100L,
                "dimension fixture did not seed the left light");
        String nether = "minecraft:the_nether";
        addAdmissionBoundaryLights(dimension, dimensionWorld, nether, false);
        require(dimension.snapshotForFrame(epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 200L,
                "dimension transition retained the previous admission selection");

        AdvancedLightRegistry worldChange = new AdvancedLightRegistry();
        Object firstWorld = new Object();
        addAdmissionBoundaryLights(worldChange, firstWorld, DIMENSION, false);
        require(worldChange.snapshotForFrame(-epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 100L,
                "world fixture did not seed the left light");
        Object secondWorld = new Object();
        addAdmissionBoundaryLights(worldChange, secondWorld, DIMENSION, false);
        require(worldChange.snapshotForFrame(epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 200L,
                "world transition retained the previous admission selection");

        AdvancedLightRegistry failed = new AdvancedLightRegistry();
        Object failedWorld = new Object();
        addAdmissionBoundaryLights(failed, failedWorld, DIMENSION, false);
        require(failed.snapshotForFrame(-epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 100L,
                "fail-closed fixture did not seed the left light");
        failed.failClosed("retained admission reset test", null);
        require(failed.snapshotForFrameIfHealthy(epsilon, 0.0, 0.0, 3, 1) == null,
                "failed registry returned a retained snapshot");
        failed.resetAdmissionHealth();
        addAdmissionBoundaryLights(failed, failedWorld, DIMENSION, false);
        require(failed.snapshotForFrame(epsilon, 0.0, 0.0, 3, 1)
                        .lights().getFirst().stableId() == 200L,
                "fail-closed recovery retained the previous admission selection");
    }

    private static void testDynamicCollectorBoundAndOrdering() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        LightWorldToken token = registry.openWorld(world, DIMENSION);
        BoundedDynamicLightCollector collector = new BoundedDynamicLightCollector(
                token,
                AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS
        );
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < 2048; index++) {
            order.add(index);
        }
        Collections.shuffle(order, new Random(991L));
        for (int index : order) {
            collector.offer(new AdvancedLight(
                    index + 1L,
                    token.generation(),
                    LightSourceKind.ENTITY,
                    index,
                    0,
                    0,
                    4.0F,
                    1.0F,
                    0.5F,
                    0.25F,
                    1.0F,
                    index % 11
            ));
        }
        List<AdvancedLight> retained = collector.finish();
        require(retained.size() == AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS
                        && collector.dropped() == 2048 - AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS,
                "dynamic collector exceeded its bound");

        BoundedDynamicLightCollector reverseCollector = new BoundedDynamicLightCollector(
                token,
                AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS
        );
        List<Integer> reverse = new ArrayList<>(order);
        Collections.reverse(reverse);
        for (int index : reverse) {
            reverseCollector.offer(new AdvancedLight(
                    index + 1L,
                    token.generation(),
                    LightSourceKind.ENTITY,
                    index,
                    0,
                    0,
                    4.0F,
                    1.0F,
                    0.5F,
                    0.25F,
                    1.0F,
                    index % 11
            ));
        }
        require(retained.equals(reverseCollector.finish()),
                "dynamic overflow changed with insertion order");

        BoundedDynamicLightCollector visibilityCollector = new BoundedDynamicLightCollector(
                token,
                2,
                light -> light.x() < 0.0
        );
        visibilityCollector.offer(entityLight(token, 30_001L, -1.0, 1, 0.01F));
        visibilityCollector.offer(entityLight(token, 30_002L, -2.0, 2, 1.0F));
        visibilityCollector.offer(entityLight(token, 30_003L, 1.0, 240, 10.0F));
        require(stableIds(visibilityCollector.finish()).equals(List.of(30_002L, 30_001L))
                        && visibilityCollector.dropped() == 1,
                "dynamic pre-cap let a bright background source displace a visible source");

        registry.publishDynamicFrame(token, retained, collector.offered());
        LightFrameSnapshot snapshot = registry.snapshotForFrame(0, 0, 0, 73);
        require(snapshot.dynamicLightCount() == 73 && snapshot.staticLightCount() == 0,
                "dynamic frame did not enter the shared snapshot");
        List<AdvancedLight> cameraOrdered = new ArrayList<>(retained);
        cameraOrdered.sort(FrameLightOrder.comparator(0.0, 0.0, 0.0));
        require(snapshot.lights().equals(cameraOrdered.subList(0, 73)),
                "dynamic collector and frame snapshot disagree on deterministic top-K");
    }

    private static void testPinnedDynamicExtractionHookDescriptor() {
        final String extractionDescriptor =
                "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V";
        final String visibilityTarget =
                "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z";
        try {
            Method visibility = LevelExtractor.class.getDeclaredMethod(
                    "isEntityVisible",
                    Entity.class,
                    Frustum.class,
                    double.class,
                    double.class,
                    double.class
            );
            require(visibility.getReturnType() == boolean.class,
                    "pinned isEntityVisible return type drifted");
            LevelExtractor.class.getDeclaredMethod(
                    "extractVisibleEntities",
                    Camera.class,
                    Frustum.class,
                    DeltaTracker.class,
                    LevelRenderState.class
            );

            Class<?> mixin = Class.forName(
                    "com.metallum.mixin.lighting.LevelExtractorAdvancedLightMixin"
            );
            Method wrapper = null;
            WrapOperation hook = null;
            for (Method method : mixin.getDeclaredMethods()) {
                WrapOperation candidate = method.getAnnotation(WrapOperation.class);
                if (candidate != null) {
                    require(wrapper == null, "dynamic extraction mixin has multiple wrap operations");
                    wrapper = method;
                    hook = candidate;
                }
                require(!method.getName().equals("metallum$extractDynamicLight"),
                        "dynamic extraction still runs after the visibility filter");
            }
            require(wrapper != null && hook != null,
                    "dynamic extraction visibility wrapper is missing");
            require(List.of(hook.method()).contains(extractionDescriptor),
                    "dynamic extraction enclosing descriptor drifted");
            require(hook.at().length == 1 && hook.at()[0].target().equals(visibilityTarget),
                    "dynamic extraction no longer hooks the pinned visibility call");
            require(hook.require() == 1 && hook.allow() == 1,
                    "dynamic extraction hook does not fail closed on call-count drift");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("pinned dynamic extraction descriptor is unavailable", exception);
        }
    }

    private static void testCameraHeldShadowSemantics() {
        CameraHeldLightTracker tracker = new CameraHeldLightTracker();
        CameraHeldLightTracker.CameraPose firstPose = new CameraHeldLightTracker.CameraPose(
                10.0, 20.0, 30.0,
                0.0, 0.0, 1.0,
                0.0, 1.0, 0.0,
                1.0, 0.0, 0.0
        );
        CameraHeldLightTracker.CameraHeldLightAnchor main = tracker.update(
                71L, firstPose, CameraHeldLightTracker.HAND_SIDE_OFFSET, 0L
        );
        require(close(main.x(), 10.22) && close(main.y(), 19.80) && close(main.z(), 30.35),
                "first-person held anchor does not use the documented camera basis offsets");

        CameraHeldLightTracker.CameraPose movedPose = new CameraHeldLightTracker.CameraPose(
                100.0, 200.0, 300.0,
                0.0, 0.0, 1.0,
                0.0, 1.0, 0.0,
                1.0, 0.0, 0.0
        );
        CameraHeldLightTracker.CameraHeldLightAnchor midpoint = tracker.update(
                71L, movedPose, -CameraHeldLightTracker.HAND_SIDE_OFFSET,
                CameraHeldLightTracker.SIDE_SMOOTHING_NANOS / 2L
        );
        require(close(midpoint.x(), 100.0) && close(midpoint.y(), 199.80)
                        && close(midpoint.z(), 300.35),
                "camera movement was smoothed instead of only the hand-side transition");
        CameraHeldLightTracker.CameraHeldLightAnchor off = tracker.update(
                71L, movedPose, -CameraHeldLightTracker.HAND_SIDE_OFFSET,
                CameraHeldLightTracker.SIDE_SMOOTHING_NANOS * 2L
        );
        require(close(off.x(), 99.78), "off-hand side did not settle after the 100 ms blend");

        AdvancedLight block = exactBlockLight(91L, 0.0, 0.0, -10.0);
        AdvancedLight dynamic = entityLight(new LightWorldToken(1L, DIMENSION), 92L, 0.0, 1, 1.0F);
        AdvancedLight held = new AdvancedLight(
                93L, 1L, LightSourceKind.ENTITY,
                0.0, 0.0, -10.0,
                1.0F, 1.0F, 0.5F, 0.25F, 1.0F, Integer.MIN_VALUE,
                false, ShadowEmitterFootprint.empty(), LocalShadowSourceClass.CAMERA_HELD
        );
        require(block.shadowSourceClass() == LocalShadowSourceClass.STATIC_CACHE
                        && dynamic.shadowSourceClass() == LocalShadowSourceClass.ENTITY_DYNAMIC
                        && held.shadowSourceClass() == LocalShadowSourceClass.CAMERA_HELD,
                "light sources did not receive the required static/entity/camera shadow classes");
        require(FrameLightOrder.materiallyOutranks(held, block, 0.0, 0.0, 0.0)
                        && !FrameLightOrder.materiallyOutranks(block, held, 0.0, 0.0, 0.0),
                "camera-held source class is not protected by dynamic admission hysteresis");

        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        LightWorldToken token = registry.openWorld(world, DIMENSION);
        int candidateCap = AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS;
        for (int index = 0; index < candidateCap; index++) {
            registry.recordBlockChange(
                    world,
                    DIMENSION,
                    index / 256L,
                    index & 255,
                    1_000_000L + index,
                    exactTemplate(1, index, 0.0, -10.0, 1.0F, 1.0F)
            );
        }
        AdvancedLight protectedHeld = new AdvancedLight(
                2_000_000L, token.generation(), LightSourceKind.ENTITY,
                0.0, 0.0, -10.0,
                1.0F, 1.0F, 0.5F, 0.25F, 1.0F, Integer.MIN_VALUE,
                false, ShadowEmitterFootprint.empty(), LocalShadowSourceClass.CAMERA_HELD
        );
        registry.publishDynamicFrame(token, List.of(protectedHeld), 1);
        LightFrameSnapshot snapshot = registry.snapshotForFrame(
                0.0, 0.0, 0.0, candidateCap
        );
        require(snapshot.lights().size() == candidateCap
                        && snapshot.lights().getFirst().stableId() == protectedHeld.stableId()
                        && snapshot.dynamicLightCount() == 1
                        && snapshot.droppedLightCount() == 1,
                "camera-held light did not survive the full 4096-candidate top-K");
    }

    private static void testBlockOverridesDoNotStartStaticRegistryWork() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object world = new Object();
        for (int update = 0; update < 1000; update++) {
            int localIndex = update & 4095;
            registry.recordBlockChange(
                    world,
                    DIMENSION,
                    update >>> 12,
                    localIndex,
                    update + 1L,
                    (update & 1) == 0 ? template(update, update, 0, 0) : null
            );
        }
        LightRegistryTelemetry telemetry = registry.telemetry();
        require(telemetry.blockOverrides() == 1000L
                        && telemetry.staticTasksStarted() == 0L
                        && telemetry.acceptedPublications() == 0L,
                "block overrides started static registry work: " + telemetry);
    }

    private static void testDisabledCollectionPathIsZeroWork() {
        AdvancedLightingRuntime.reset();
        require(!AdvancedLightingRuntime.shouldCollect(),
                "runtime reset did not disable collection");
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        if (AdvancedLightingRuntime.shouldCollect()) {
            registry.observeHook(AdvancedLightRegistry.Hook.STATIC_TASK);
            registry.beginSectionTask(new Object(), DIMENSION, 1L);
        }
        LightRegistryTelemetry telemetry = registry.telemetry();
        require(telemetry.worldTransitions() == 0L
                        && telemetry.staticTasksStarted() == 0L
                        && telemetry.blockOverrides() == 0L
                        && telemetry.dynamicFrames() == 0L
                        && registry.readiness().observedHookMask() == 0,
                "disabled collection path mutated registry state: " + telemetry);
    }

    private static void testReadinessCoverage() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        require(registry.readiness().structuralReady()
                        && registry.readiness().healthy()
                        && !registry.readiness().runtimeHooksCovered(),
                "fresh registry readiness contract mismatch");
        for (AdvancedLightRegistry.Hook hook : AdvancedLightRegistry.Hook.values()) {
            registry.observeHook(hook);
        }
        require(registry.readiness().runtimeHooksCovered()
                        && registry.readiness().observedHookMask()
                        == registry.readiness().requiredHookMask(),
                "runtime hook coverage did not reach readiness");
    }

    private static void testRegistryFailureAdmissionGate() {
        AdvancedLightingRuntime.reset();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");
        AdvancedLightingRuntime.admitGeneration(true);

        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        registry.failClosed("synthetic registry failure", new IllegalStateException("boom"));
        require(!registry.readiness().healthy()
                        && registry.readiness().failureCount() == 1L
                        && registry.readiness().failureReason().contains("IllegalStateException"),
                "registry health did not retain its fail-closed reason");
        require(!AdvancedLightingRuntime.isActive()
                        && !AdvancedLightingRuntime.admission().ready()
                        && !AdvancedLightingRuntime.shouldCollect(),
                "registry failure did not atomically reject Advanced lighting");
        AdvancedLightingRuntime.reset();
    }

    private static void testStaleRegistryFailureCannotPoisonResetAdmission() {
        AdvancedLightingRuntime.reset();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");
        AdvancedLightingRuntime.admitGeneration(true);

        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread delayedFailure = new Thread(() -> {
            try {
                registry.failClosed("old renderer failure", null);
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "metallum-stale-registry-failure-test");

        synchronized (AdvancedLightingRuntime.class) {
            delayedFailure.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (registry.readiness().healthy()
                    && delayedFailure.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            require(!registry.readiness().healthy(),
                    "failure worker did not reach the registry/runtime handoff");

            // Re-enter the runtime monitor and fully admit the replacement before the old report.
            AdvancedLightingRuntime.reset();
            AdvancedLightingRuntime.configureRequested(true);
            AdvancedLightingRuntime.reportNativeAdmission(true, "");
            AdvancedLightingRuntime.reportShaderAdmission(true, "");
            AdvancedLightingRuntime.admitGeneration(true);
        }
        try {
            delayedFailure.join(5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("stale registry failure test was interrupted", exception);
        }
        require(!delayedFailure.isAlive() && workerFailure.get() == null,
                "failure worker did not finish cleanly: " + workerFailure.get());
        require(registry.readiness().healthy()
                        && AdvancedLightingRuntime.isActive()
                        && AdvancedLightingRuntime.admission().ready()
                        && AdvancedLightingRuntime.shouldCollect(),
                "stale registry failure report poisoned the reset renderer generation");
        AdvancedLightingRuntime.reset();
    }

    private static LightSectionCandidate candidate(
            final LightSectionTask task,
            final Map<Integer, LightTemplate> lights
    ) {
        Map<Integer, LightTemplate> copy = new HashMap<>(lights);
        return StaticLightSectionScanner.scan(
                task,
                0,
                0,
                0,
                AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                (localIndex, x, y, z) -> copy.get(localIndex)
        );
    }

    private static void addAdmissionBoundaryLights(
            final AdvancedLightRegistry registry,
            final Object world,
            final String dimension,
            final boolean reverse
    ) {
        List<Long> ids = reverse
                ? List.of(300L, 200L, 100L)
                : List.of(100L, 200L, 300L);
        for (long stableId : ids) {
            LightTemplate light = switch ((int) stableId) {
                case 100 -> exactTemplate(100, -20.0, 0.0, 0.0, 4.0F, 1.0F);
                case 200 -> exactTemplate(100, 20.0, 0.0, 0.0, 4.0F, 1.0F);
                case 300 -> exactTemplate(100, 0.0, 20.0, 0.0, 4.0F, 1.0F);
                default -> throw new AssertionError("Unexpected admission fixture id " + stableId);
            };
            registry.recordBlockChange(
                    world,
                    dimension,
                    stableId,
                    (int) (stableId / 100L),
                    stableId,
                    light
            );
        }
    }

    private static DirectLightFrustum directLightFrustum(final Matrix4 view) {
        Matrix4 projection = perspective90Projection(0.1, 100.0);
        FrameState.Transforms transforms = new FrameState.Transforms(
                Matrix4.identity(),
                view,
                projection,
                Matrix4.identity(),
                view,
                projection
        );
        return DirectLightFrustum.from(new FrameCapture(
                transforms,
                FrameState.CameraPosition.ORIGIN,
                1.0 / 60.0,
                0.1,
                100.0,
                1L,
                1L
        ));
    }

    private static Matrix4 perspective90Projection(
            final double nearPlane,
            final double farPlane
    ) {
        double depthScale = -(farPlane + nearPlane) / (farPlane - nearPlane);
        double depthTranslation = -(2.0 * farPlane * nearPlane) / (farPlane - nearPlane);
        return Matrix4.of(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, depthScale, -1.0,
                0.0, 0.0, depthTranslation, 0.0
        );
    }

    private static Matrix4 yaw180View() {
        return Matrix4.of(
                -1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, -1.0, 0.0,
                0.0, 0.0, 0.0, 1.0
        );
    }

    private static LightTemplate exactTemplate(
            final int priority,
            final double x,
            final double y,
            final double z,
            final float radius,
            final float intensity
    ) {
        return new LightTemplate(
                LightSourceKind.BLOCK,
                x,
                y,
                z,
                radius,
                1.0F,
                0.25F,
                0.05F,
                intensity,
                priority
        );
    }

    private static AdvancedLight lavaLike(
            final long stableId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        return new AdvancedLight(
                stableId,
                1L,
                LightSourceKind.BLOCK,
                blockX + 0.5,
                blockY + 0.5,
                blockZ + 0.5,
                12.75F,
                1.0F,
                0.08F,
                0.004F,
                3.15F,
                240,
                true
        );
    }

    private static AdvancedLight entityLight(
            final LightWorldToken token,
            final long stableId,
            final double x,
            final int priority,
            final float intensity
    ) {
        return new AdvancedLight(
                stableId,
                token.generation(),
                LightSourceKind.ENTITY,
                x,
                0.0,
                -10.0,
                2.0F,
                1.0F,
                0.25F,
                0.05F,
                intensity,
                priority
        );
    }

    private static AdvancedLight exactBlockLight(
            final long stableId,
            final double x,
            final double y,
            final double z
    ) {
        return new AdvancedLight(
                stableId,
                1L,
                LightSourceKind.BLOCK,
                x,
                y,
                z,
                12.75F,
                1.0F,
                0.08F,
                0.004F,
                3.15F,
                240
        );
    }

    private static double radialContribution(
            final AdvancedLight light,
            final double x,
            final double y,
            final double z
    ) {
        double dx = light.x() - x;
        double dy = light.y() - y;
        double dz = light.z() - z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double range = Math.max(1.0 - distance / light.radius(), 0.0);
        return light.intensity() * range * range;
    }

    private static double integratedRadialEnergy(final AdvancedLight light) {
        return light.intensity() * light.radius() * light.radius() * light.radius();
    }

    private static double relativeError(final double actual, final double expected) {
        return Math.abs(actual - expected) / Math.max(Math.abs(expected), 1.0e-12);
    }

    private static LightTemplate template(
            final int priority,
            final double x,
            final double y,
            final double z
    ) {
        return new LightTemplate(
                LightSourceKind.BLOCK,
                x + 0.5,
                y + 0.5,
                z + 0.5,
                8.0F,
                1.0F,
                0.25F,
                0.05F,
                2.0F,
                priority
        );
    }

    private static List<Long> stableIds(final List<AdvancedLight> lights) {
        return lights.stream().map(AdvancedLight::stableId).toList();
    }

    private static boolean close(final float actual, final float expected) {
        return Math.abs(actual - expected) <= 1.0e-6F;
    }

    private static boolean close(final double actual, final double expected) {
        return Math.abs(actual - expected) <= 1.0e-9;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
