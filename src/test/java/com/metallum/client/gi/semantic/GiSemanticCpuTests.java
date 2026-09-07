package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldCandidateBudget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-light executable contract tests for the CPU-only G2 semantic truth. */
public final class GiSemanticCpuTests {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String MATERIAL_A = "test:a";
    private static final String MATERIAL_B = "test:b";
    private static final String MATERIAL_CUTOUT = "test:cutout";
    private static final String MATERIAL_TRANSLUCENT = "test:translucent";

    private GiSemanticCpuTests() {
    }

    public static void main(final String[] arguments) throws InterruptedException {
        coordinatesAreFloorCorrect();
        paletteIsVersionedDeterministicAndBounded();
        reducerIsOrderIndependentAndUsesAggregateMaterialWeight();
        workerWorkspaceReuseDoesNotAliasPublishedSnapshots();
        builderRejectsUnboundedObservationRetention();
        blockScaleNearCellsDoNotMergeFloorAndAir();
        acceptedQuadOverridesSeedMediumAndMaterial();
        unknownQuadMaterialFailsClosed();
        controllerGatesReloadAndRetiresCandidateLeases();
        resourceReloadRootsAdvanceMonotonicallyAcrossReopen();
        resourceCloseOpenSeedsCameraBeforeFirstPrepareFrame();
        activeFieldCacheTracksSoleWorldLifecycle();
        publicationOrderDoesNotChangeFieldTruth();
        cameraScrollPreservesNegativeCoordinateOverlap();
        tenThousandPublicationsRemainBounded();
        assemblerIsRenderThreadConfined();
        System.out.println("G2 semantic CPU contract tests passed");
    }

    private static void activeFieldCacheTracksSoleWorldLifecycle() {
        GiSemanticController controller = new GiSemanticController();
        Object first = new Object();
        Object second = new Object();
        controller.openWorld(first, DIMENSION);
        controller.advanceMaterialAtlasEpoch(List.of());
        require(controller.activeDirectField() != null
                        && controller.activeTransportField() != null,
                "sole active G2 field cache was not published");
        controller.openWorld(second, DIMENSION);
        require(controller.activeDirectField() == null
                        && controller.activeTransportField() == null,
                "multi-world G2 state exposed an ambiguous active field");
        controller.closeWorld(second);
        require(controller.activeDirectField() != null
                        && controller.activeTransportField() != null,
                "sole active G2 field cache was not restored after close");
        controller.closeWorld(first);
        require(controller.activeDirectField() == null
                        && controller.activeTransportField() == null,
                "closed G2 world remained in the active field cache");
    }

    private static void coordinatesAreFloorCorrect() {
        require(GiSemanticCoordinates.blockToSection(-1) == -1, "-1 block did not floor to section -1");
        require(GiSemanticCoordinates.blockToSection(-16) == -1, "-16 block section changed");
        require(GiSemanticCoordinates.blockToSection(-17) == -2, "-17 block did not floor to section -2");
        long key = GiSemanticCoordinates.sectionKey(-2_000_000, -400_000, 2_000_000);
        require(GiSemanticCoordinates.sectionX(key) == -2_000_000
                        && GiSemanticCoordinates.sectionY(key) == -400_000
                        && GiSemanticCoordinates.sectionZ(key) == 2_000_000,
                "signed section key did not round-trip");
    }

    private static void paletteIsVersionedDeterministicAndBounded() {
        List<GiSemanticPalette.Seed> forward = List.of(seed(MATERIAL_B), seed(MATERIAL_A));
        List<GiSemanticPalette.Seed> reverse = List.of(seed(MATERIAL_A), seed(MATERIAL_B));
        GiSemanticPalette first = GiSemanticPalette.build(7L, 11L, 13L, forward);
        GiSemanticPalette second = GiSemanticPalette.build(7L, 11L, 13L, reverse);
        require(first.digest().equals(second.digest()), "palette digest depends on enumeration order");
        require(first.idFor(MATERIAL_A) == 3 && first.idFor(MATERIAL_B) == 4,
                "sorted palette IDs changed");
        require(first.idFor("mod:unseen") == GiSemanticPalette.FALLBACK_ID,
                "unknown palette key did not map to fallback");
        require(first.entry(GiSemanticPalette.UNKNOWN_ID).medium() == GiSemanticMedium.UNKNOWN_CONSERVATIVE
                        && first.entry(GiSemanticPalette.AIR_ID).medium() == GiSemanticMedium.AIR,
                "reserved palette entries changed");

        GiSemanticPalette overflowB = GiSemanticPalette.build(
                7L, 11L, 13L, 4, List.of(seed(MATERIAL_A), seed(MATERIAL_B))
        );
        GiSemanticPalette overflowC = GiSemanticPalette.build(
                7L, 11L, 13L, 4, List.of(seed(MATERIAL_A), seed("test:c"))
        );
        require(overflowB.idFor(MATERIAL_B) == GiSemanticPalette.FALLBACK_ID
                        && overflowB.overflowedKeys() == 1,
                "palette capacity did not deterministically overflow");
        require(!overflowB.digest().equals(overflowC.digest()),
                "palette digest aliases different overflow key sets");
        expectIllegalArgument(() -> GiSemanticPalette.build(
                1L, 1L, 1L, List.of(seed(MATERIAL_A), new GiSemanticPalette.Seed(
                        MATERIAL_A, GiSemanticMaterial.METAL, GiSemanticMedium.OPAQUE,
                        0.9F, 0.8F, 0.7F, 0.0F, 0.0F, 0.0F, 0.0F,
                        GiSemanticProvenance.MATERIAL_DERIVED
                ))
        ), "conflicting duplicate palette key was accepted");
    }

    private static void reducerIsOrderIndependentAndUsesAggregateMaterialWeight() {
        GiSemanticPalette palette = palette(1L, 1L, 1L);
        GiSemanticSectionSeed section = GiSemanticSectionSeed.acceptedEmptyBuilder()
                .set(GiSemanticStateSeed.content(
                        0, MATERIAL_A, 1.0F / GiSemanticPacking.UNORM16_MAX,
                        GiSemanticPacking.FACE_NEG_X, GiSemanticProvenance.MATERIAL_DERIVED
                )).build();
        List<GiSemanticQuadObservation> observations = new ArrayList<>(List.of(
                observation(MATERIAL_A, GiSemanticPacking.FACE_NEG_X, 60, 1.0F, 0.0F, 0.0F),
                observation(MATERIAL_A, GiSemanticPacking.FACE_POS_Y, 60, 1.0F, 0.0F, 0.0F),
                observation(MATERIAL_B, GiSemanticPacking.FACE_POS_Z, 100, 0.0F, 0.0F, 1.0F)
        ));
        GiSemanticSectionSnapshot forward = build(palette, section, observations);
        Collections.reverse(observations);
        GiSemanticSectionSnapshot reverse = build(palette, section, observations);
        require(forward.digest().equals(reverse.digest()), "quad order changed reduced semantic truth");
        int cell = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 0, 0);
        require(Short.toUnsignedInt(forward.dominantMaterialIds()[cell]) == palette.idFor(MATERIAL_A),
                "dominant material used a largest single quad instead of aggregate weight");
        byte[] faces = forward.faceWeights();
        require(Byte.toUnsignedInt(faces[cell * 6]) == 153
                        && Byte.toUnsignedInt(faces[cell * 6 + 3]) == 153
                        && Byte.toUnsignedInt(faces[cell * 6 + 5]) == 255,
                "six-direction quad weights changed");
        require(forward.validity()[cell] == (byte) GiSemanticValidity.KNOWN_CONTENT.abiId(),
                "complete cloned state did not produce known content");
    }

    private static void workerWorkspaceReuseDoesNotAliasPublishedSnapshots() {
        GiSemanticPalette palette = palette(1L, 1L, 1L);
        GiSemanticSectionSeed firstSeed = GiSemanticSectionSeed.acceptedEmptyBuilder()
                .set(GiSemanticStateSeed.content(
                        0, MATERIAL_A, 1.0F, GiSemanticPacking.FACE_POS_Y,
                        GiSemanticProvenance.MATERIAL_DERIVED
                )).build();
        GiSemanticSectionSnapshot first = build(palette, firstSeed, List.of(
                observation(MATERIAL_A, GiSemanticPacking.FACE_POS_Y, 100,
                        1.0F, 0.0F, 0.0F)
        ));
        String firstDigest = first.digest();
        int cell = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 0, 0);
        int firstRed = Short.toUnsignedInt(first.albedoRgb()[cell * 3]);

        GiSemanticSectionSeed secondSeed = GiSemanticSectionSeed.acceptedEmptyBuilder()
                .set(GiSemanticStateSeed.content(
                        0, MATERIAL_B, 1.0F, GiSemanticPacking.FACE_POS_Y,
                        GiSemanticProvenance.MATERIAL_DERIVED
                )).build();
        GiSemanticSectionSnapshot second = build(palette, secondSeed, List.of(
                observation(MATERIAL_B, GiSemanticPacking.FACE_POS_Y, 100,
                        0.0F, 0.0F, 1.0F)
        ));

        require(first.digest().equals(firstDigest)
                        && Short.toUnsignedInt(first.dominantMaterialIds()[cell])
                        == palette.idFor(MATERIAL_A)
                        && Short.toUnsignedInt(first.albedoRgb()[cell * 3]) == firstRed,
                "reused G2 worker workspace mutated a published snapshot");
        require(Short.toUnsignedInt(second.dominantMaterialIds()[cell])
                        == palette.idFor(MATERIAL_B),
                "reused G2 worker workspace retained the preceding material reduction");
    }

    private static void builderRejectsUnboundedObservationRetention() {
        GiSemanticSectionBuilder builder = new GiSemanticSectionBuilder(
                palette(1L, 1L, 1L), GiSemanticSectionSeed.acceptedEmptyBuilder().build()
        );
        GiSemanticQuadObservation repeated = observation(
                MATERIAL_A, GiSemanticPacking.FACE_POS_Y, 1,
                1.0F, 0.0F, 0.0F
        );
        for (int index = 0; index < GiSemanticSectionBuilder.MAX_OBSERVATIONS; index++) {
            builder.observe(repeated);
        }
        try {
            builder.observe(repeated);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("G2 builder retained observations beyond its fixed bound");
    }

    private static void blockScaleNearCellsDoNotMergeFloorAndAir() {
        GiSemanticPalette palette = palette(1L, 1L, 1L);
        GiSemanticSectionSeed section = GiSemanticSectionSeed.acceptedEmptyBuilder()
                .set(GiSemanticStateSeed.content(
                        0, MATERIAL_A, 1.0F, GiSemanticPacking.FACE_POS_Y,
                        GiSemanticProvenance.MATERIAL_DERIVED
                )).build();
        GiSemanticSectionSnapshot snapshot = build(palette, section, List.of());
        int floor = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 0, 0);
        int airAbove = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 1, 0);
        int adjacentAir = GiSemanticSectionSnapshot.cascadeCellIndex(0, 1, 0, 0);
        require(snapshot.validity()[floor]
                        == (byte) GiSemanticValidity.KNOWN_CONTENT.abiId()
                        && snapshot.validity()[airAbove]
                        == (byte) GiSemanticValidity.KNOWN_EMPTY.abiId()
                        && snapshot.validity()[adjacentAir]
                        == (byte) GiSemanticValidity.KNOWN_EMPTY.abiId(),
                "G2 C0 merged a one-block surface with adjacent air into a false solid volume");
        require(GiSemanticSectionSnapshot.CASCADE_CELL_EDGES[0] == 16
                        && GiSemanticSectionSnapshot.CELL_COUNT == 4_168
                        && GiSemanticSectionSnapshot.PAYLOAD_BYTES == 112_536L,
                "G2 block-scale section topology/accounting differs");
        require(GiFieldCandidateBudget.DEFAULT_MAX_BYTES >= Math.multiplyExact(
                        (long) GiFieldCandidateBudget.DEFAULT_MAX_CANDIDATES,
                        GiSemanticSectionSnapshot.PAYLOAD_BYTES
                ), "G2 default candidate byte cap cannot admit its declared concurrency");
    }

    private static void unknownQuadMaterialFailsClosed() {
        GiSemanticPalette palette = palette(1L, 1L, 1L);
        GiSemanticSectionSeed section = GiSemanticSectionSeed.acceptedEmptyBuilder()
                .set(GiSemanticStateSeed.content(
                        0, MATERIAL_A, 1.0F, GiSemanticPacking.FACE_POS_Y,
                        GiSemanticProvenance.MATERIAL_DERIVED
                )).build();
        GiSemanticSectionSnapshot snapshot = build(palette, section, List.of(
                observation("mod:missing", GiSemanticPacking.FACE_POS_Y, 100, 1.0F, 1.0F, 1.0F)
        ));
        int cell = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 0, 0);
        require(snapshot.validity()[cell] == (byte) GiSemanticValidity.KNOWN_FALLBACK.abiId()
                        && Byte.toUnsignedInt(snapshot.occupancy()[cell]) == 255,
                "unknown observed material did not become conservative fallback");
        require((Byte.toUnsignedInt(snapshot.provenance()[cell])
                        & GiSemanticProvenance.PALETTE_FALLBACK) != 0,
                "palette fallback provenance was lost");
        require(Short.toUnsignedInt(snapshot.albedoRgb()[cell * 3]) == 0
                        && Short.toUnsignedInt(snapshot.emissionRgbIntensity()[cell * 4 + 3]) == 0,
                "fallback cell leaked guessed energy");
    }

    private static void acceptedQuadOverridesSeedMediumAndMaterial() {
        GiSemanticPalette palette = GiSemanticPalette.build(
                1L, 1L, 1L, List.of(
                        seed(MATERIAL_CUTOUT, GiSemanticMedium.CUTOUT),
                        seed(MATERIAL_TRANSLUCENT, GiSemanticMedium.TRANSLUCENT)
                )
        );
        GiSemanticSectionSeed section = GiSemanticSectionSeed.acceptedEmptyBuilder()
                .set(GiSemanticStateSeed.content(
                        0, MATERIAL_CUTOUT, 1.0F, GiSemanticPacking.FACE_POS_Y,
                        GiSemanticProvenance.MATERIAL_DERIVED
                )).build();
        GiSemanticSectionSnapshot snapshot = build(palette, section, List.of(
                observation(
                        MATERIAL_TRANSLUCENT, GiSemanticPacking.FACE_POS_Y,
                        100, 0.2F, 0.3F, 0.4F
                )
        ));
        int cell = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 0, 0);
        require(Byte.toUnsignedInt(snapshot.mediumMasks()[cell]) == GiSemanticMedium.TRANSLUCENT.mask(),
                "accepted translucent quad retained cloned CUTOUT medium");
        require(Short.toUnsignedInt(snapshot.dominantMaterialIds()[cell])
                        == palette.idFor(MATERIAL_TRANSLUCENT),
                "accepted quad did not override cloned-state dominant palette ID");
    }

    private static void controllerGatesReloadAndRetiresCandidateLeases() {
        GiSemanticController controller = new GiSemanticController();
        Object world = new Object();
        long section = GiSemanticCoordinates.sectionKey(0, 0, 0);
        GiSemanticWorldToken opened = controller.openWorld(world, DIMENSION);
        require(controller.beginSectionTask(world, DIMENSION, section) == null,
                "tasks were admitted before a complete palette");
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        GiSemanticSectionTask task = requireNonNull(
                controller.beginSectionTask(world, DIMENSION, section), "palette publication did not admit task"
        );
        require(controller.paletteFor(task).idFor(MATERIAL_A) != GiSemanticPalette.FALLBACK_ID,
                "task did not capture the controller-owned complete palette");
        GiSemanticSectionCandidate candidate = requireNonNull(
                controller.createAuthoritativeEmptyCandidate(task), "candidate reservation failed"
        );
        require(controller.budgetSnapshot().activeCandidates() == 1, "candidate did not retain its lease");
        long materialBeforeReload = task.world().materialEpoch();
        GiSemanticWorldToken reloading = controller.beginResourceReload(world, DIMENSION);
        require(reloading.worldGeneration() > task.world().worldGeneration()
                        && reloading.resourceEpoch() > opened.resourceEpoch()
                        && reloading.materialEpoch() == materialBeforeReload,
                "reload HEAD changed the wrong epochs");
        require(controller.beginSectionTask(world, DIMENSION, section) == null,
                "reload window admitted a task against the old atlas");
        require(!controller.publishAccepted(candidate), "pre-reload candidate was accepted");
        require(controller.budgetSnapshot().activeCandidates() == 0
                        && controller.budgetSnapshot().activeBytes() == 0L,
                "stale publication did not retire its lease");
        long contentAtReload = requireNonNull(controller.fieldSnapshot(world), "missing reload snapshot")
                .contentGeneration();
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        GiSemanticSectionTask postReload = requireNonNull(
                controller.beginSectionTask(world, DIMENSION, section), "atlas TAIL did not readmit tasks"
        );
        GiSemanticPalette postReloadPalette = requireNonNull(
                controller.paletteFor(postReload), "post-reload task has no palette"
        );
        require(postReload.world().materialEpoch() > materialBeforeReload
                        && postReloadPalette.idFor(MATERIAL_B) != GiSemanticPalette.FALLBACK_ID,
                "atlas TAIL did not rotate and publish the complete palette");
        require(requireNonNull(controller.fieldSnapshot(world), "missing post-atlas snapshot")
                        .contentGeneration() > contentAtReload,
                "field content generation did not advance across palette reset");

        GiSemanticSectionCandidate clipmapStale = requireNonNull(
                controller.createAuthoritativeEmptyCandidate(postReload), "clipmap stale candidate missing"
        );
        require(controller.updateCamera(world, 2, 0, 0), "clipmap generation did not rotate");
        require(!controller.publishAccepted(clipmapStale), "old clipmap candidate was accepted");
        GiSemanticSectionTask beforeMaterialAdvance = requireNonNull(
                controller.beginSectionTask(world, DIMENSION, section), "current task missing before atlas advance"
        );
        GiSemanticSectionCandidate materialStale = requireNonNull(
                controller.createAuthoritativeEmptyCandidate(beforeMaterialAdvance),
                "material stale candidate missing"
        );
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        require(!controller.publishAccepted(materialStale), "old material/palette candidate was accepted");
        require(controller.budgetSnapshot().activeCandidates() == 0,
                "generation-stale candidates retained leases");
    }

    private static void resourceCloseOpenSeedsCameraBeforeFirstPrepareFrame() {
        GiSemanticController controller = new GiSemanticController();
        Object world = new Object();
        int cameraX = 512;
        int cameraY = 96;
        int cameraZ = -640;
        long cameraSection = GiSemanticCoordinates.sectionKey(
                GiSemanticCoordinates.blockToSection(cameraX),
                GiSemanticCoordinates.blockToSection(cameraY),
                GiSemanticCoordinates.blockToSection(cameraZ)
        );

        controller.openWorld(world, DIMENSION, cameraX, cameraY, cameraZ);
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        GiSemanticWorldToken reloadRoot = controller.beginResourceReload(world, DIMENSION);
        require(controller.beginSectionTask(world, DIMENSION, cameraSection) == null,
                "resource reload admitted pre-atlas work");
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        require(controller.beginSectionTask(world, DIMENSION, cameraSection) != null,
                "resource reload lost the established camera before frame preparation");

        controller.closeWorld(world);
        GiSemanticWorldToken reopened = controller.openWorld(
                world, DIMENSION, cameraX, cameraY, cameraZ
        );
        require(reopened.worldGeneration() > reloadRoot.worldGeneration()
                        && reopened.resourceEpoch() > reloadRoot.resourceEpoch(),
                "reload close/open reused its already-issued G2 root token");
        require(controller.beginSectionTask(world, DIMENSION, cameraSection) != null,
                "resource close/open stamped a camera-local section outside the initial clipmap");
        require(controller.beginSectionTask(
                        world, DIMENSION, GiSemanticCoordinates.sectionKey(0, 0, 0)
                ) == null,
                "resource close/open silently rebuilt the clipmap around the zero origin");
    }

    private static void resourceReloadRootsAdvanceMonotonicallyAcrossReopen() {
        GiSemanticController controller = new GiSemanticController();
        Object world = new Object();
        GiSemanticWorldToken opened = controller.openWorld(world, DIMENSION);
        GiSemanticWorldToken firstReload = controller.beginResourceReload(world, DIMENSION);
        GiSemanticWorldToken secondReload = controller.beginResourceReload(world, DIMENSION);

        require(firstReload.worldGeneration() > opened.worldGeneration()
                        && firstReload.resourceEpoch() > opened.resourceEpoch(),
                "first resource reload reused the opened G2 root");
        require(secondReload.worldGeneration() > firstReload.worldGeneration()
                        && secondReload.resourceEpoch() > firstReload.resourceEpoch(),
                "consecutive resource reloads did not advance the G2 root monotonically");
        require(firstReload.materialEpoch() == opened.materialEpoch()
                        && secondReload.materialEpoch() == opened.materialEpoch(),
                "resource reload unexpectedly advanced the material epoch");

        controller.closeWorld(world);
        GiSemanticWorldToken reopened = controller.openWorld(world, DIMENSION);
        require(reopened.worldGeneration() > secondReload.worldGeneration()
                        && reopened.resourceEpoch() > secondReload.resourceEpoch(),
                "reload close/open reused an already-issued G2 root token");
    }

    private static void publicationOrderDoesNotChangeFieldTruth() {
        GiSemanticController first = readyController();
        GiSemanticController second = readyController();
        Object firstWorld = WORLD_BY_CONTROLLER.remove(first);
        Object secondWorld = WORLD_BY_CONTROLLER.remove(second);
        long sectionA = GiSemanticCoordinates.sectionKey(-1, 0, 0);
        long sectionB = GiSemanticCoordinates.sectionKey(0, 0, 0);
        publishEmpty(first, firstWorld, sectionA);
        publishEmpty(first, firstWorld, sectionB);
        publishEmpty(second, secondWorld, sectionB);
        publishEmpty(second, secondWorld, sectionA);
        GiSemanticFieldSnapshot a = requireNonNull(first.fieldSnapshot(firstWorld), "first field missing");
        GiSemanticFieldSnapshot b = requireNonNull(second.fieldSnapshot(secondWorld), "second field missing");
        require(a.digest().equals(b.digest()), "accepted section order changed complete field truth");
    }

    private static final java.util.IdentityHashMap<GiSemanticController, Object> WORLD_BY_CONTROLLER =
            new java.util.IdentityHashMap<>();

    private static GiSemanticController readyController() {
        GiSemanticController controller = new GiSemanticController();
        Object world = new Object();
        controller.openWorld(world, DIMENSION);
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        WORLD_BY_CONTROLLER.put(controller, world);
        return controller;
    }

    private static void cameraScrollPreservesNegativeCoordinateOverlap() {
        GiSemanticController controller = new GiSemanticController();
        Object world = new Object();
        controller.openWorld(world, DIMENSION);
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        controller.updateCamera(world, -5, -5, -5);
        long section = GiSemanticCoordinates.sectionKey(-1, -1, -1);
        publishEmpty(controller, world, section);
        GiSemanticFieldSnapshot before = requireNonNull(controller.fieldSnapshot(world), "field missing before scroll");
        int beforeCell = before.cellIndexForWorld(0, -2, -2, -2);
        require(beforeCell >= 0 && before.validity(beforeCell) == GiSemanticValidity.KNOWN_EMPTY,
                "negative-coordinate section was not deposited");
        long generationBefore = before.contentGeneration();

        require(controller.updateCamera(world, -3, -3, -3), "first camera +2 did not advance snapped origin");
        GiSemanticFieldSnapshot middle = requireNonNull(controller.fieldSnapshot(world), "field missing mid-scroll");
        int middleCell = middle.cellIndexForWorld(0, -2, -2, -2);
        require(middleCell >= 0 && middle.validity(middleCell) == GiSemanticValidity.KNOWN_EMPTY,
                "first camera +2 discarded overlapping negative-coordinate truth");
        require(controller.updateCamera(world, -1, -1, -1), "second camera +2 did not advance snapped origin");
        GiSemanticFieldSnapshot after = requireNonNull(controller.fieldSnapshot(world), "field missing after scroll");
        int retained = after.cellIndexForWorld(0, -2, -2, -2);
        // C0 is 32 one-block cells and its 2-block origin snap scrolls two cells at once.
        // After the second +2 m snap the newly exposed positive-x slab is [12, 14).
        int exposed = after.cellIndexForWorld(0, 12, -2, -2);
        require(retained >= 0 && after.validity(retained) == GiSemanticValidity.KNOWN_EMPTY,
                "camera scroll discarded overlapping negative-coordinate truth");
        require(exposed >= 0 && after.validity(exposed) == GiSemanticValidity.UNKNOWN,
                "newly exposed scroll slab was not invalidated");
        require(after.residentSections() == 1 && after.contentGeneration() > generationBefore,
                "scroll lost intersecting residency or failed to advance content generation");
    }

    private static void tenThousandPublicationsRemainBounded() {
        GiFieldCandidateBudget budget = new GiFieldCandidateBudget(2, GiSemanticSectionSnapshot.PAYLOAD_BYTES * 2L);
        GiSemanticController controller = new GiSemanticController(budget);
        Object world = new Object();
        long section = GiSemanticCoordinates.sectionKey(0, 0, 0);
        controller.openWorld(world, DIMENSION);
        controller.advanceMaterialAtlasEpoch(paletteSeeds());
        long initialGeneration = requireNonNull(controller.fieldSnapshot(world), "initial field missing")
                .contentGeneration();
        for (int publication = 0; publication < 10_000; publication++) {
            publishEmpty(controller, world, section);
        }
        GiSemanticController.Telemetry telemetry = controller.telemetry();
        require(telemetry.accepted() == 10_000L && telemetry.residentSectionTags() == 1,
                "10k real publications were not retired into one resident tag");
        require(telemetry.candidateBudget().activeCandidates() == 0
                        && telemetry.candidateBudget().activeBytes() == 0L
                        && telemetry.candidateBudget().peakCandidates() == 1
                        && telemetry.candidateBudget().peakBytes() == GiSemanticSectionSnapshot.PAYLOAD_BYTES,
                "10k publications leaked candidate leases or exceeded one-candidate steady state");
        require(requireNonNull(controller.fieldSnapshot(world), "final field missing").contentGeneration()
                        == initialGeneration + 1L,
                "idempotent accepted publications changed semantic field content");
    }

    private static void assemblerIsRenderThreadConfined() throws InterruptedException {
        GiSemanticWorldToken world = new GiSemanticWorldToken(1L, 1L, 1L, DIMENSION);
        GiSemanticFieldAssembler assembler = new GiSemanticFieldAssembler(
                world, palette(1L, 1L, 1L), 0, 0, 0
        );
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                assembler.snapshot();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "g2-wrong-render-thread");
        wrongThread.start();
        wrongThread.join();
        require(failure.get() instanceof IllegalStateException
                        && failure.get().getMessage().contains("render thread"),
                "field assembler did not fail closed off its owner thread");
    }

    private static void publishEmpty(
            final GiSemanticController controller, final Object world, final long section
    ) {
        GiSemanticSectionTask task = requireNonNull(
                controller.beginSectionTask(world, DIMENSION, section), "section task was not admitted"
        );
        GiSemanticSectionCandidate candidate = requireNonNull(
                controller.createAuthoritativeEmptyCandidate(task), "empty candidate was not created"
        );
        require(controller.publishAccepted(candidate), "empty candidate was rejected");
    }

    private static GiSemanticSectionSnapshot build(
            final GiSemanticPalette palette,
            final GiSemanticSectionSeed section,
            final List<GiSemanticQuadObservation> observations
    ) {
        GiSemanticSectionBuilder builder = new GiSemanticSectionBuilder(palette, section);
        observations.forEach(builder::observe);
        return builder.build();
    }

    private static GiSemanticQuadObservation observation(
            final String key, final int face, final int area,
            final float red, final float green, final float blue
    ) {
        return new GiSemanticQuadObservation(
                0, face, key,
                GiSemanticPacking.unorm16(red), GiSemanticPacking.unorm16(green), GiSemanticPacking.unorm16(blue),
                (short) 0, (short) 0, (short) 0, (short) 0,
                area, GiSemanticProvenance.RESOURCE_DERIVED
        );
    }

    private static GiSemanticPalette palette(
            final long generation, final long resourceEpoch, final long materialEpoch
    ) {
        return GiSemanticPalette.build(generation, resourceEpoch, materialEpoch, paletteSeeds());
    }

    private static List<GiSemanticPalette.Seed> paletteSeeds() {
        return List.of(seed(MATERIAL_B), seed(MATERIAL_A));
    }

    private static GiSemanticPalette.Seed seed(final String key) {
        return seed(key, GiSemanticMedium.OPAQUE);
    }

    private static GiSemanticPalette.Seed seed(final String key, final GiSemanticMedium medium) {
        return new GiSemanticPalette.Seed(
                key, GiSemanticMaterial.STONE, medium,
                0.4F, 0.5F, 0.6F, 0.0F, 0.0F, 0.0F, 0.0F,
                GiSemanticProvenance.MATERIAL_DERIVED | GiSemanticProvenance.RESOURCE_DERIVED
        );
    }

    private static void expectIllegalArgument(final Runnable action, final String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static <T> T requireNonNull(final T value, final String message) {
        if (value == null) throw new AssertionError(message);
        return value;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
