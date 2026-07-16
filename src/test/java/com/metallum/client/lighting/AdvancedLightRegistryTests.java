package com.metallum.client.lighting;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
        testPinnedDynamicExtractionHookDescriptor();
        testBlockOverridesDoNotStartStaticRegistryWork();
        testDisabledCollectionPathIsZeroWork();
        testReadinessCoverage();
        testRegistryFailureAdmissionGate();
        testStaleRegistryFailureCannotPoisonResetAdmission();
        System.out.println("Advanced light registry L3 race/bounds/property tests passed");
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
                        && candidate.droppedLightCount() == 4096 - AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                "static section cap/overflow mismatch");
        require(registry.publishAccepted(candidate), "exact static candidate was not accepted");
        LightFrameSnapshot snapshot = registry.snapshotForFrame(0.0, 0.0, 0.0, 4096);
        require(snapshot.staticLightCount() == AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                "accepted static section lost retained lights");
        LightRegistryTelemetry telemetry = registry.telemetry();
        require(telemetry.staticSectionsScanned() == 1L
                        && telemetry.staticStatesScanned() == 4096L
                        && telemetry.sectionLightOverflows() == candidate.droppedLightCount(),
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

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
