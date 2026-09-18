package com.metallum.client.lighting.reflection;

import com.metallum.client.gi.receiver.GiReceiverCompatibility;
import com.metallum.client.radiance.CompactSectionPayload;
import com.metallum.client.radiance.Float16Compressor;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;

/** Dependency-free ownership and provenance checks for the one-shot frozen reflection domain. */
public final class FrozenReflectionFieldControllerTests {
    public static void main(final String[] args) {
        String oldRuntime = System.getProperty(VertexReflectionExperiment.RUNTIME_PROPERTY);
        try {
            GiReceiverCompatibility.setTestOverride(true);
            VertexReflectionExperiment.setOverride(true);
            System.setProperty(VertexReflectionExperiment.RUNTIME_PROPERTY, "true");
            testPreloadVerticalStorageBounds();
            testKnownEmptyIsPublishedValidity();
            testSupersededOutputDoesNotInvalidateTheLatestTask();
            testStaleOrUnavailableSectionInvalidatesTheField();
            WaterReflectionConfigTests.main(args);
            com.metallum.client.metal.render.ScreenSpaceReflectionRendererTests.main(args);
            System.out.println("FrozenReflectionFieldControllerTests passed successfully.");
        } finally {
            VertexReflectionExperiment.setOverride(null);
            GiReceiverCompatibility.setTestOverride(null);
            if (oldRuntime == null) {
                System.clearProperty(VertexReflectionExperiment.RUNTIME_PROPERTY);
            } else {
                System.setProperty(VertexReflectionExperiment.RUNTIME_PROPERTY, oldRuntime);
            }
        }
    }

    private static void testPreloadVerticalStorageBounds() {
        LevelHeightAccessor overworld = LevelHeightAccessor.create(-64, 384);
        require(FrozenReflectionVerticalBounds.isOutsideBuildHeight(overworld, -5),
                "the section below Overworld storage must be authoritative empty, not index -1");
        require(!FrozenReflectionVerticalBounds.isOutsideBuildHeight(overworld, -4),
                "the first stored Overworld section must remain addressable");
        require(!FrozenReflectionVerticalBounds.isOutsideBuildHeight(overworld, 19),
                "the last stored Overworld section must remain addressable");
        require(FrozenReflectionVerticalBounds.isOutsideBuildHeight(overworld, 20),
                "the section above Overworld storage must be authoritative empty");

        int crashCameraSection = SectionPos.blockToSectionCoord(-17);
        int crashDomainOriginSection = SectionPos.blockToSectionCoord(
                ((int) Math.floor((-17.0 - FrozenReflectionFieldController.SPAN_BLOCKS * 0.5 + 8.0) / 16.0)) << 4
        );
        require(crashCameraSection == -2 && crashDomainOriginSection == -5,
                "the reported y=-17 camera must reproduce the below-storage preload row");
        require(FrozenReflectionVerticalBounds.isOutsideBuildHeight(overworld, crashDomainOriginSection),
                "the exact reported preload row must be rejected before Sodium array access");
    }

    private static void testKnownEmptyIsPublishedValidity() {
        FrozenReflectionFieldController controller = FrozenReflectionFieldController.global();
        Object world = new Object();
        controller.openWorld(world);
        require(controller.activateAtCamera(world, 12.75, 68.0, -33.25), "first camera pose must latch the field");
        FrozenReflectionFieldController.Snapshot initial = controller.snapshot();
        require(initial.state() == FrozenReflectionFieldController.State.COLLECTING, "field must collect once");
        require(initial.expectedSections() == FrozenReflectionFieldController.EXPECTED_SECTION_COUNT
                        && initial.expectedSections() == 512,
                "128-block field must require exactly 8^3 sections");
        long firstSection = SectionPos.asLong(
                initial.originX() >> 4, initial.originY() >> 4, initial.originZ() >> 4
        );
        require(controller.retainsSectionDuringCollection(world, firstSection),
                "in-flight frozen collection must retain its exact Sodium section");
        require(!controller.activateAtCamera(world, 8000.0, 8000.0, 8000.0), "field must never scroll after latching");

        for (int z = 0; z < FrozenReflectionFieldController.SECTIONS_PER_EDGE; z++) {
            for (int y = 0; y < FrozenReflectionFieldController.SECTIONS_PER_EDGE; y++) {
                for (int x = 0; x < FrozenReflectionFieldController.SECTIONS_PER_EDGE; x++) {
                    long section = SectionPos.asLong(
                            (initial.originX() >> 4) + x,
                            (initial.originY() >> 4) + y,
                            (initial.originZ() >> 4) + z
                    );
                    FrozenReflectionSectionTask task = controller.beginSectionTask(world, section);
                    require(task != null, "expected section must receive exactly one stamped task");
                    CompactSectionPayload payload = x == 2 && y == 0 && z == 0
                            ? oddCoordinateLandmark(section, task.worldGeneration())
                            : CompactSectionPayload.empty(section, task.worldGeneration());
                    require(controller.publishAccepted(new FrozenReflectionSectionCandidate(task, payload)),
                            "accepted section must publish");
                }
            }
        }

        FrozenReflectionFieldController.SourceSnapshot source = controller.sourceSnapshotForTests();
        require(source != null, "all accepted sections must assemble one source snapshot");
        for (byte valid : source.validity()) {
            require((valid & 0xff) == 255, "transparent-but-known source cells must remain valid");
        }
        int initialLandmarkRgba = 2 * 8 * 4;
        require(Float16Compressor.unpackFloat(source.packedRgba()[initialLandmarkRgba]) > 0.9F
                        && Float16Compressor.unpackFloat(source.packedRgba()[initialLandmarkRgba + 2]) == 0.0F
                        && Float16Compressor.unpackFloat(source.packedRgba()[initialLandmarkRgba + 3]) > 0.9F,
                "2x2x2 source aggregation must retain an odd landmark without water self-radiance");
        require(controller.snapshot().state() == FrozenReflectionFieldController.State.READY_FOR_GPU_UPLOAD,
                "snapshot must wait for one native GPU build");
        FrozenReflectionFieldController.SourceSnapshot claimed = controller.claimReadySnapshotForGpuUpload();
        require(claimed != null && claimed.fieldGeneration() == source.fieldGeneration(),
                "only the exact ready snapshot may be claimed");
        controller.noteGpuReady(claimed.worldGeneration(), claimed.fieldGeneration());
        require(controller.snapshot().state() == FrozenReflectionFieldController.State.READY,
                "matching native completion must make the field ready");
        require(!controller.retainsSectionDuringCollection(world, firstSection),
                "ready frozen source must not retain Sodium sections");
        require(!controller.activateAtCamera(world,
                        initial.originX() + 64.0, initial.originY() + 64.0, initial.originZ() + 64.0),
                "camera motion inside the guard band must keep the completed domain stable");
        require(controller.activateAtCamera(world,
                        initial.originX() + FrozenReflectionFieldController.RECENTER_GUARD_BLOCKS - 0.5,
                        initial.originY() + 64.0,
                        initial.originZ() + 64.0),
                "camera leaving one guard axis must start a world-snapped replacement domain");
        FrozenReflectionFieldController.Snapshot recentered = controller.snapshot();
        require(recentered.state() == FrozenReflectionFieldController.State.COLLECTING
                        && recentered.fieldGeneration() > initial.fieldGeneration(),
                "recentered domain must have a fresh collection generation");
        require(recentered.originX() != initial.originX()
                        && recentered.originY() == initial.originY()
                        && recentered.originZ() == initial.originZ(),
                "recenter must preserve every axis which remains inside its guard band");
        int shiftedSections = Math.abs(recentered.originX() - initial.originX()) >> 4;
        int reusedSections = (8 - shiftedSections) * 8 * 8;
        require(recentered.publishedContent() + recentered.knownEmpty() == reusedSections,
                "guarded recenter must reuse every overlapping accepted section");
        require(recentered.expectedSections()
                        - recentered.publishedContent() - recentered.knownEmpty()
                        == shiftedSections * 8 * 8,
                "guarded recenter must recollect only the entering section slabs");
        for (int z = 0; z < FrozenReflectionFieldController.SECTIONS_PER_EDGE; z++) {
            for (int y = 0; y < FrozenReflectionFieldController.SECTIONS_PER_EDGE; y++) {
                for (int x = 0; x < FrozenReflectionFieldController.SECTIONS_PER_EDGE; x++) {
                    long section = SectionPos.asLong(
                            (recentered.originX() >> 4) + x,
                            (recentered.originY() >> 4) + y,
                            (recentered.originZ() >> 4) + z
                    );
                    if (!controller.needsSectionTask(world, section)) {
                        continue;
                    }
                    FrozenReflectionSectionTask task = controller.beginSectionTask(world, section);
                    require(task != null, "entering section must receive a replacement task");
                    require(controller.publishAccepted(new FrozenReflectionSectionCandidate(
                                    task,
                                    CompactSectionPayload.empty(section, task.worldGeneration())
                            )),
                            "entering section must publish into the replacement domain");
                }
            }
        }
        FrozenReflectionFieldController.SourceSnapshot replacement = controller.sourceSnapshotForTests();
        require(replacement != null, "reused overlap plus entering slabs must complete replacement");
        int landmarkWorldX = initial.originX() + 2 * 16;
        int replacementLandmarkCellX = (landmarkWorldX - recentered.originX()) / 2;
        int replacementLandmarkRgba = replacementLandmarkCellX * 4;
        require(Float16Compressor.unpackFloat(
                        replacement.packedRgba()[replacementLandmarkRgba]
                ) > 0.9F,
                "overlap reuse must preserve accepted reflection cell contents at world position");
        controller.closeWorld(world);
    }

    private static CompactSectionPayload oddCoordinateLandmark(final long sectionKey, final long worldGeneration) {
        short[] rgba = new short[CompactSectionPayload.SECTION_BLOCK_COUNT * CompactSectionPayload.SHORTS_PER_BLOCK];
        byte[] classification = new byte[CompactSectionPayload.SECTION_BLOCK_COUNT];
        int local = (1 << 8) | (1 << 4) | 1;
        rgba[local * 4] = Float16Compressor.packFloat(1.0F);
        rgba[local * 4 + 3] = Float16Compressor.packFloat(1.0F);
        classification[local] = CompactSectionPayload.CLASS_OCCUPIED;
        int water = 0;
        rgba[water * 4 + 2] = Float16Compressor.packFloat(1.0F);
        rgba[water * 4 + 3] = Float16Compressor.packFloat(0.30F);
        classification[water] = CompactSectionPayload.CLASS_WATER;
        return new CompactSectionPayload(sectionKey, worldGeneration, false, rgba, classification);
    }

    private static void testStaleOrUnavailableSectionInvalidatesTheField() {
        FrozenReflectionFieldController controller = FrozenReflectionFieldController.global();
        Object world = new Object();
        controller.openWorld(world);
        require(controller.activateAtCamera(world, 0.0, 64.0, 0.0), "new world must activate a new frozen field");
        FrozenReflectionFieldController.Snapshot snapshot = controller.snapshot();
        long section = SectionPos.asLong(snapshot.originX() >> 4, snapshot.originY() >> 4, snapshot.originZ() >> 4);
        FrozenReflectionSectionTask task = controller.beginSectionTask(world, section);
        require(task != null, "first section must be expected");
        for (int attempt = 0; attempt < 4; attempt++) {
            controller.discardCandidate(new FrozenReflectionSectionCandidate(
                    task, CompactSectionPayload.empty(section, task.worldGeneration())
            ));
            if (attempt < 3) {
                require(controller.snapshot().state() == FrozenReflectionFieldController.State.COLLECTING,
                        "a discarded latest task must receive bounded retries");
                task = controller.beginSectionTask(world, section);
                require(task != null, "retry must receive a fresh task generation");
            }
        }
        require(controller.snapshot().state() == FrozenReflectionFieldController.State.INVALID,
                "exhausted latest outputs are unavailable, not transparent world data");
        controller.closeWorld(world);
    }

    private static void testSupersededOutputDoesNotInvalidateTheLatestTask() {
        FrozenReflectionFieldController controller = FrozenReflectionFieldController.global();
        Object world = new Object();
        controller.openWorld(world);
        require(controller.activateAtCamera(world, 0.0, 64.0, 0.0), "new world must activate a frozen field");
        FrozenReflectionFieldController.Snapshot snapshot = controller.snapshot();
        long section = SectionPos.asLong(snapshot.originX() >> 4, snapshot.originY() >> 4, snapshot.originZ() >> 4);
        FrozenReflectionSectionTask older = controller.beginSectionTask(world, section);
        FrozenReflectionSectionTask latest = controller.beginSectionTask(world, section);
        require(older != null && latest != null && latest.taskGeneration() > older.taskGeneration(),
                "a replacement Sodium rebuild must carry a newer task generation");
        controller.discardCandidate(new FrozenReflectionSectionCandidate(
                older, CompactSectionPayload.empty(section, older.worldGeneration())
        ));
        require(controller.snapshot().state() == FrozenReflectionFieldController.State.COLLECTING,
                "discarding a superseded output must not reject its newer rebuild");
        require(controller.publishAccepted(new FrozenReflectionSectionCandidate(
                latest, CompactSectionPayload.empty(section, latest.worldGeneration())
        )), "the latest accepted result must remain publishable");
        controller.closeWorld(world);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
