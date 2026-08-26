package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free executable contract tests for the frozen G4 near-cascade semantic view. */
public final class GiSemanticTransportFieldViewTests {
    private static final String DIMENSION = "test:g4-transport";
    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private GiSemanticTransportFieldViewTests() {
    }

    public static void main(final String[] arguments) throws InterruptedException {
        exactPackingIsDeterministicAndFailClosed();
        controllerViewTracksNegativeOriginAndEpochs();
        destinationMustMatchExactly();
        viewExposesNoMutableArrays();
        viewIsRenderThreadConfined();
        System.out.println("G4 frozen near-cascade semantic transport view tests passed");
    }

    private static void exactPackingIsDeterministicAndFailClosed() {
        GiSemanticWorldToken world = new GiSemanticWorldToken(11L, 12L, 13L, DIMENSION);
        GiSemanticPalette palette = GiSemanticPalette.build(17L, 12L, 13L, List.of());
        GiSemanticFieldAssembler assembler = new GiSemanticFieldAssembler(world, palette, 0, 0, 0);
        GiSemanticTransportFieldView view = new GiSemanticTransportFieldView(assembler);
        GiSemanticSectionTask task = new GiSemanticSectionTask(
                world, assembler.clipmapGeneration(), palette.generation(),
                GiSemanticCoordinates.sectionKey(0, 0, 0), 23L, 29L
        );
        require(assembler.apply(task, adversarialSection())
                        == GiSemanticFieldAssembler.ApplyResult.ACCEPTED,
                "G4 semantic fixture was not accepted");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment first = arena.allocate(GiSemanticTransportFieldView.PAYLOAD_BYTES, 2L);
            MemorySegment second = arena.allocate(GiSemanticTransportFieldView.PAYLOAD_BYTES, 2L);
            first.fill((byte) 0x5a);
            second.fill((byte) 0xa5);
            GiSemanticTransportFieldView.CopyResult firstResult = view.copyNearCascade(first);
            GiSemanticTransportFieldView.CopyResult secondResult = view.copyNearCascade(second);
            require(first.mismatch(second) == -1L && firstResult.equals(secondResult),
                    "repeat G4 near-cascade copies were not byte-identical");
            require(firstResult.contentStamp() == 23L
                            && firstResult.knownContentCells() == 2
                            && firstResult.unknownCells()
                            == GiSemanticTransportFieldView.CELL_COUNT - 2,
                    "G4 copy summary does not describe the accepted near cascade");

            int fixtureStart = GiFieldLayout.CELLS_PER_AXIS / 2;
            int colored = GiFieldLayout.cellIndex(
                    fixtureStart, fixtureStart, fixtureStart, GiSemanticTransportFieldView.EDGE
            );
            long coloredOffset = (long) colored * GiSemanticTransportFieldView.CELL_BYTES;
            require(unsignedShort(first, coloredOffset) == 0x1234
                            && unsignedShort(first, coloredOffset + 2L) == 0x89ab
                            && unsignedShort(first, coloredOffset + 4L) == 0xfedc
                            && unsignedShort(first, coloredOffset + 6L) == 0x7d7d,
                    "G4 material ushort4 did not preserve raw LE rho or expanded occupancy");
            int[] expectedFaces = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
            for (int face = 0; face < expectedFaces.length; face++) {
                require(unsignedByte(first, coloredOffset + 8L + face) == expectedFaces[face],
                        "G4 face order changed at index " + face);
            }
            require(unsignedByte(first, coloredOffset + 14L)
                            == GiSemanticValidity.KNOWN_CONTENT.abiId()
                            && unsignedByte(first, coloredOffset + 15L) == 0x77,
                    "G4 validity/known-coverage tail bytes changed");

            int black = GiFieldLayout.cellIndex(
                    fixtureStart + 1, fixtureStart, fixtureStart, GiSemanticTransportFieldView.EDGE
            );
            long blackOffset = (long) black * GiSemanticTransportFieldView.CELL_BYTES;
            require(unsignedShort(first, blackOffset) == 0
                            && unsignedShort(first, blackOffset + 2L) == 0
                            && unsignedShort(first, blackOffset + 4L) == 0,
                    "black accepted rho gained reflected energy");

            int acceptedUnknown = GiFieldLayout.cellIndex(
                    fixtureStart + 2, fixtureStart, fixtureStart, GiSemanticTransportFieldView.EDGE
            );
            long unknownOffset = (long) acceptedUnknown * GiSemanticTransportFieldView.CELL_BYTES;
            require(unsignedShort(first, unknownOffset) == 0
                            && unsignedShort(first, unknownOffset + 2L) == 0
                            && unsignedShort(first, unknownOffset + 4L) == 0
                            && unsignedShort(first, unknownOffset + 6L) == 0xffff,
                    "UNKNOWN assembler truth was not conservative black/opaque");
            for (int face = 0; face < 6; face++) {
                require(unsignedByte(first, unknownOffset + 8L + face) == 0,
                        "UNKNOWN assembler truth retained a transport face");
            }
            require(unsignedByte(first, unknownOffset + 14L) == GiSemanticValidity.UNKNOWN.abiId()
                            && unsignedByte(first, unknownOffset + 15L) == 0,
                    "UNKNOWN assembler truth retained known coverage");
        }
    }

    private static GiSemanticSectionSnapshot adversarialSection() {
        int cells = GiSemanticSectionSnapshot.CELL_COUNT;
        short[] albedo = new short[cells * 3];
        short[] emission = new short[cells * 4];
        byte[] occupancy = new byte[cells];
        byte[] medium = new byte[cells];
        byte[] validity = new byte[cells];
        byte[] provenance = new byte[cells];
        byte[] faces = new byte[cells * 6];
        byte[] coverage = new byte[cells];
        short[] materialIds = new short[cells];
        Arrays.fill(albedo, (short) 0x6eee);
        Arrays.fill(occupancy, (byte) 0x23);
        Arrays.fill(medium, (byte) GiSemanticMedium.UNKNOWN_CONSERVATIVE.mask());
        Arrays.fill(faces, (byte) 0x6d);
        Arrays.fill(coverage, (byte) 0xff);
        Arrays.fill(materialIds, (short) GiSemanticPalette.UNKNOWN_ID);

        int colored = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 0, 0);
        validity[colored] = (byte) GiSemanticValidity.KNOWN_CONTENT.abiId();
        medium[colored] = (byte) GiSemanticMedium.OPAQUE.mask();
        albedo[colored * 3] = (short) 0x1234;
        albedo[colored * 3 + 1] = (short) 0x89ab;
        albedo[colored * 3 + 2] = (short) 0xfedc;
        occupancy[colored] = (byte) 0x7d;
        int[] orderedFaces = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        for (int face = 0; face < orderedFaces.length; face++) {
            faces[colored * 6 + face] = (byte) orderedFaces[face];
        }
        coverage[colored] = (byte) 0x77;

        int black = GiSemanticSectionSnapshot.cascadeCellIndex(0, 1, 0, 0);
        validity[black] = (byte) GiSemanticValidity.KNOWN_CONTENT.abiId();
        medium[black] = (byte) GiSemanticMedium.OPAQUE.mask();
        albedo[black * 3] = 0;
        albedo[black * 3 + 1] = 0;
        albedo[black * 3 + 2] = 0;
        occupancy[black] = (byte) 0xff;
        coverage[black] = (byte) 0xff;

        return new GiSemanticSectionSnapshot(
                albedo, emission, occupancy, medium, validity, provenance,
                faces, coverage, materialIds, 1, 0
        );
    }

    private static void controllerViewTracksNegativeOriginAndEpochs() {
        GiSemanticController controller = new GiSemanticController();
        Object worldKey = new Object();
        GiSemanticWorldToken opened = controller.openWorld(worldKey, DIMENSION);
        require(controller.transportField(worldKey) == null,
                "G4 view was admitted before a complete palette");
        controller.advanceMaterialAtlasEpoch(List.of());
        GiSemanticTransportFieldView view = requireNonNull(
                controller.transportField(worldKey), "G4 per-world view was not created"
        );
        require(controller.activeTransportField() == view,
                "G4 sole-active-world view does not preserve identity");

        long clipmapBefore = view.clipmapGeneration();
        long contentBefore = view.contentGeneration();
        int cameraX = -5;
        int cameraY = -37;
        int cameraZ = -69;
        require(controller.updateCamera(worldKey, cameraX, cameraY, cameraZ),
                "negative camera did not rotate the G4 near cascade");
        require(view.nearOriginX() == GiFieldLayout.centeredOriginBlock(cameraX, 0)
                        && view.nearOriginY() == GiFieldLayout.centeredOriginBlock(cameraY, 0)
                        && view.nearOriginZ() == GiFieldLayout.centeredOriginBlock(cameraZ, 0),
                "G4 near origin is not floor-correct for negative coordinates");
        require(view.worldGeneration() == opened.worldGeneration()
                        && view.resourceEpoch() == opened.resourceEpoch()
                        && view.materialEpoch() > opened.materialEpoch()
                        && view.clipmapGeneration() > clipmapBefore
                        && view.paletteGeneration() > 1L
                        && view.contentGeneration() > contentBefore,
                "G4 world/clipmap/palette/content epochs did not advance coherently");

        long sectionKey = GiSemanticCoordinates.sectionKey(-1, -3, -5);
        GiSemanticSectionTask task = requireNonNull(
                controller.beginSectionTask(worldKey, DIMENSION, sectionKey),
                "negative G4 section task was not admitted"
        );
        GiSemanticSectionCandidate candidate = requireNonNull(
                controller.createAuthoritativeEmptyCandidate(task),
                "negative G4 empty candidate was not created"
        );
        require(controller.publishAccepted(candidate), "negative G4 empty candidate was rejected");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(GiSemanticTransportFieldView.PAYLOAD_BYTES, 2L);
            GiSemanticTransportFieldView.CopyResult result = view.copyNearCascade(destination);
            int cellX = Math.floorDiv(-16 - view.nearOriginX(), GiFieldLayout.cellSizeBlocks(0));
            int cellY = Math.floorDiv(-48 - view.nearOriginY(), GiFieldLayout.cellSizeBlocks(0));
            int cellZ = Math.floorDiv(-80 - view.nearOriginZ(), GiFieldLayout.cellSizeBlocks(0));
            int cell = GiFieldLayout.cellIndex(cellX, cellY, cellZ, GiSemanticTransportFieldView.EDGE);
            long offset = (long) cell * GiSemanticTransportFieldView.CELL_BYTES;
            require(unsignedByte(destination, offset + 14L)
                            == GiSemanticValidity.KNOWN_EMPTY.abiId()
                            && result.contentStamp() > 0L,
                    "negative accepted truth was not copied into the frozen near cascade");
        }
    }

    private static void destinationMustMatchExactly() {
        GiSemanticWorldToken world = new GiSemanticWorldToken(1L, 1L, 1L, DIMENSION);
        GiSemanticPalette palette = GiSemanticPalette.build(1L, 1L, 1L, List.of());
        GiSemanticTransportFieldView view = new GiSemanticTransportFieldView(
                new GiSemanticFieldAssembler(world, palette, 0, 0, 0)
        );
        try (Arena arena = Arena.ofConfined()) {
            expectIllegalArgument(
                    () -> view.copyNearCascade(arena.allocate(
                            GiSemanticTransportFieldView.PAYLOAD_BYTES - 1L
                    )),
                    "too-small G4 destination was accepted"
            );
            expectIllegalArgument(
                    () -> view.copyNearCascade(arena.allocate(
                            GiSemanticTransportFieldView.PAYLOAD_BYTES + 1L
                    )),
                    "oversized G4 destination was accepted"
            );
        }
    }

    private static void viewExposesNoMutableArrays() {
        for (Method method : GiSemanticTransportFieldView.class.getDeclaredMethods()) {
            require(!method.getReturnType().isArray(),
                    "G4 transport view exposes mutable array from " + method.getName());
        }
        for (Method method : GiSemanticTransportFieldView.CopyResult.class.getDeclaredMethods()) {
            require(!method.getReturnType().isArray(),
                    "G4 copy result exposes mutable array from " + method.getName());
        }
    }

    private static void viewIsRenderThreadConfined() throws InterruptedException {
        GiSemanticWorldToken world = new GiSemanticWorldToken(1L, 1L, 1L, DIMENSION);
        GiSemanticPalette palette = GiSemanticPalette.build(1L, 1L, 1L, List.of());
        GiSemanticTransportFieldView view = new GiSemanticTransportFieldView(
                new GiSemanticFieldAssembler(world, palette, 0, 0, 0)
        );
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                view.copyNearCascade(MemorySegment.ofArray(
                        new byte[Math.toIntExact(GiSemanticTransportFieldView.PAYLOAD_BYTES)]
                ));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "g4-wrong-render-thread");
        wrongThread.start();
        wrongThread.join();
        require(failure.get() instanceof IllegalStateException
                        && failure.get().getMessage().contains("render thread"),
                "G4 semantic view did not inherit assembler render-thread confinement");
    }

    private static int unsignedShort(final MemorySegment segment, final long offset) {
        return Short.toUnsignedInt(segment.get(LE_SHORT, offset));
    }

    private static int unsignedByte(final MemorySegment segment, final long offset) {
        return Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, offset));
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
