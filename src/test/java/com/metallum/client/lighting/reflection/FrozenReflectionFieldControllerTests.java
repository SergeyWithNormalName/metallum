package com.metallum.client.lighting.reflection;

import com.metallum.client.radiance.CompactSectionPayload;
import net.minecraft.core.SectionPos;

/** Dependency-free ownership and provenance checks for the one-shot frozen reflection domain. */
public final class FrozenReflectionFieldControllerTests {
    public static void main(final String[] args) {
        String oldRuntime = System.getProperty(VertexReflectionExperiment.RUNTIME_PROPERTY);
        try {
            VertexReflectionExperiment.setOverride(true);
            System.setProperty(VertexReflectionExperiment.RUNTIME_PROPERTY, "true");
            testKnownEmptyIsPublishedValidity();
            testSupersededOutputDoesNotInvalidateTheLatestTask();
            testStaleOrUnavailableSectionInvalidatesTheField();
            System.out.println("FrozenReflectionFieldControllerTests passed successfully.");
        } finally {
            VertexReflectionExperiment.setOverride(null);
            if (oldRuntime == null) {
                System.clearProperty(VertexReflectionExperiment.RUNTIME_PROPERTY);
            } else {
                System.setProperty(VertexReflectionExperiment.RUNTIME_PROPERTY, oldRuntime);
            }
        }
    }

    private static void testKnownEmptyIsPublishedValidity() {
        FrozenReflectionFieldController controller = FrozenReflectionFieldController.global();
        Object world = new Object();
        controller.openWorld(world);
        require(controller.activateAtCamera(world, 12.75, 68.0, -33.25), "first camera pose must latch the field");
        FrozenReflectionFieldController.Snapshot initial = controller.snapshot();
        require(initial.state() == FrozenReflectionFieldController.State.COLLECTING, "field must collect once");
        require(initial.expectedSections() == 512, "fixed 128-block field must require exactly 8^3 sections");
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
                    require(controller.publishAccepted(new FrozenReflectionSectionCandidate(
                            task, CompactSectionPayload.empty(section, task.worldGeneration())
                    )), "accepted transparent section must publish");
                }
            }
        }

        FrozenReflectionFieldController.SourceSnapshot source = controller.sourceSnapshotForTests();
        require(source != null, "all accepted sections must assemble one source snapshot");
        for (byte valid : source.validity()) {
            require((valid & 0xff) == 255, "transparent-but-known source cells must remain valid");
        }
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
        controller.closeWorld(world);
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
