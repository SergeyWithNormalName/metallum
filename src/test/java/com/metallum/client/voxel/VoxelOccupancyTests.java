package com.metallum.client.voxel;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.SectionPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executor;

/** Dependency-light L5 numeric/property tests; deliberately runnable as a JavaExec main class. */
public final class VoxelOccupancyTests {
    private static final VoxelMaterialDescriptor OPAQUE = VoxelMaterialDescriptor.defaults(
            VoxelMaterialClass.OPAQUE
    );

    private VoxelOccupancyTests() {
    }

    public static void main(final String[] args) {
        testSubdivisionAndMaterialPacking();
        testFullAndSlabMasks();
        testStairFenceAndPaneMasks();
        testCoverageAwareOpticsAndDeterminism();
        testDebugSliceVisualization();
        testPreviewSettingsAndAcknowledgedMirror();
        testClipmapPresetSizing();
        testCameraScrollAndToroidalAddressing();
        testAsyncPackingOracleAtAllSubdivisions();
        testAsyncPackingStateMachineAndRaces();
        testControllerRetryPreservesTelemetryDeltas();
        testClipmapControllerLifecycleAndRetry();
        testDirtyQueueCoalescingBoundsAgeAndActualDrain();
        testReadyPublicationFairness();
        testVanillaPartialBlockMaterialClassification();
        System.out.println("L5 voxel occupancy pure-Java tests passed");
    }

    private static void testSubdivisionAndMaterialPacking() {
        require(VoxelSubdivision.ONE.cellCount() == 1
                        && VoxelSubdivision.TWO.cellCount() == 8
                        && VoxelSubdivision.FOUR.cellCount() == 64,
                "subdivision cell counts changed");
        require(VoxelSubdivision.ONE.fullMask() == 1L
                        && VoxelSubdivision.TWO.fullMask() == 0xffL
                        && VoxelSubdivision.FOUR.fullMask() == -1L,
                "subdivision full masks changed");
        require(VoxelSubdivision.FOUR.cellIndex(3, 2, 1) == 3 + 4 * (2 + 4),
                "voxel mask order is not X-fastest");
        expect(IndexOutOfBoundsException.class, () -> VoxelSubdivision.TWO.cellIndex(2, 0, 0));

        VoxelMaterialDescriptor glass = new VoxelMaterialDescriptor(VoxelMaterialClass.GLASS, 0.75f);
        int packed = glass.packedUnsignedByte();
        require(packed == 119 && glass.quantizedTransmittance() == 23,
                "glass material packing changed");
        VoxelMaterialDescriptor decoded = VoxelMaterialDescriptor.fromPackedUnsignedByte(packed);
        require(decoded.materialClass() == VoxelMaterialClass.GLASS
                        && decoded.quantizedTransmittance() == 23
                        && decoded.packedUnsignedByte() == packed,
                "packed material did not round-trip");
        VoxelMaterialDescriptor unknown = VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.UNKNOWN_CONSERVATIVE
        );
        require(unknown.packedUnsignedByte() == 0xe0
                        && unknown.opacity() == 1.0f
                        && VoxelMaterialDescriptor.fromPackedUnsignedByte(0xe0).materialClass()
                        == VoxelMaterialClass.UNKNOWN_CONSERVATIVE,
                "unknown material fallback must remain opaque and ABI-stable");
        expect(IllegalArgumentException.class, () -> VoxelMaterialDescriptor.fromPackedUnsignedByte(0x1ff));
    }

    private static void testPreviewSettingsAndAcknowledgedMirror() {
        Path settings = null;
        try {
            settings = Files.createTempFile("metallum-l5-preview-", ".properties");
            VoxelPreviewSettings.State configured = new VoxelPreviewSettings.State(
                    VoxelPreviewMode.MATERIAL, 2, 127
            );
            require(VoxelPreviewSettings.save(settings, configured)
                            && VoxelPreviewSettings.load(settings).equals(configured),
                    "L5 preview settings did not round-trip");
            Files.writeString(settings, "mode=broken\nlevel=0\nslice=0\n");
            require(VoxelPreviewSettings.load(settings).equals(
                            VoxelPreviewSettings.State.defaults()),
                    "malformed L5 preview settings did not fail closed");
        } catch (java.io.IOException exception) {
            throw new AssertionError("L5 preview settings test failed", exception);
        } finally {
            if (settings != null) {
                try {
                    Files.deleteIfExists(settings);
                } catch (java.io.IOException ignored) {
                }
            }
        }

        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        occupancy[0] = 1;
        byte[] optical = new byte[32 * 32 * 32];
        optical[0] = (byte) OPAQUE.packedUnsignedByte();
        VoxelBrickPatch patch = new VoxelBrickPatch(
                0, 0, 0, 0, -2, 3, 4, 7, 11, 13,
                occupancy, optical
        );
        VoxelWorldToken world = new VoxelWorldToken(11, "test");
        VoxelUploadBatch batch = new VoxelUploadBatch(
                1, world, 13, 0, List.of(patch), 0, 0, 0, 0, 0, 0
        );
        VoxelPreviewMirror mirror = VoxelPreviewMirror.global();
        mirror.acknowledge(batch);
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(world, 13, List.of(
                new VoxelClipmapSnapshot.Level(0, 1, 32, -2, 3, 4, 1)
        ));
        VoxelPreviewMirror.Snapshot preview = mirror.snapshot(clipmap, 0);
        require(preview != null && preview.bricks().get(
                        new VoxelPreviewMirror.Key(0, -2, 3, 4)
                ).occupancy()[0] == 1,
                "acknowledged L5 preview mirror lost its toroidal logical brick");
        VoxelClipmapSnapshot stale = new VoxelClipmapSnapshot(
                new VoxelWorldToken(12, "test"), 13, clipmap.levels()
        );
        require(mirror.snapshot(stale, 0) == null,
                "L5 preview mirror exposed data to another world generation");
    }

    private static void testFullAndSlabMasks() {
        for (VoxelSubdivision subdivision : VoxelSubdivision.values()) {
            VoxelShapeEncoder.EncodedShape full = VoxelShapeEncoder.encode(
                    Shapes.block(), subdivision, OPAQUE
            );
            require(full.occupancyMask() == subdivision.fullMask(),
                    "full block lost occupancy at " + subdivision);
            require(full.occupiedCellCount() == subdivision.cellCount(),
                    "full block cell count changed at " + subdivision);
            for (int index = 0; index < subdivision.cellCount(); index++) {
                require(full.coverageByte(index) == 255 && full.opticalByte(index) == 255,
                        "full opaque block lost full coverage at cell " + index);
            }
        }

        VoxelShapeEncoder.EncodedShape slab = VoxelShapeEncoder.encode(
                Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0), VoxelSubdivision.TWO, OPAQUE
        );
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                require(slab.occupied(x, 0, z), "bottom slab omitted lower subcell");
                require(!slab.occupied(x, 1, z), "bottom slab filled upper subcell");
            }
        }
        require(slab.occupiedCellCount() == 4, "bottom slab reference mask changed");
    }

    private static void testVanillaPartialBlockMaterialClassification() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        VoxelMaterialDescriptor glass = SodiumVoxelSectionExtractor.materialFor(
                net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()
        );
        VoxelMaterialDescriptor slab = SodiumVoxelSectionExtractor.materialFor(
                net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState()
        );
        VoxelMaterialDescriptor stairs = SodiumVoxelSectionExtractor.materialFor(
                net.minecraft.world.level.block.Blocks.STONE_STAIRS.defaultBlockState()
        );
        VoxelMaterialDescriptor fence = SodiumVoxelSectionExtractor.materialFor(
                net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState()
        );
        require(slab.materialClass() == VoxelMaterialClass.CUTOUT,
                "a stone slab was classified as " + slab.materialClass());
        require(stairs.materialClass() == VoxelMaterialClass.CUTOUT,
                "stone stairs were classified as " + stairs.materialClass());
        require(fence.materialClass() == VoxelMaterialClass.CUTOUT,
                "an oak fence was classified as " + fence.materialClass());
        require(glass.materialClass() == VoxelMaterialClass.GLASS,
                "glass lost its transparent material classification");
    }

    private static void testStairFenceAndPaneMasks() {
        VoxelShape stair = Shapes.or(
                Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
                Shapes.box(0.0, 0.5, 0.0, 0.5, 1.0, 1.0)
        );
        VoxelShapeEncoder.EncodedShape encodedStair = VoxelShapeEncoder.encode(
                stair, VoxelSubdivision.TWO, OPAQUE
        );
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                require(encodedStair.occupied(x, 0, z), "stair lost its lower step");
                require(encodedStair.occupied(x, 1, z) == (x == 0),
                        "stair upper reference mask changed");
            }
        }
        require(encodedStair.occupiedCellCount() == 6, "stair reference mask cell count changed");

        VoxelShapeEncoder.EncodedShape fence = VoxelShapeEncoder.encode(
                Shapes.box(0.375, 0.0, 0.375, 0.625, 1.0, 0.625), VoxelSubdivision.FOUR, OPAQUE
        );
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    boolean expected = (x == 1 || x == 2) && (z == 1 || z == 2);
                    require(fence.occupied(x, y, z) == expected,
                            "fence reference mask changed at " + x + ',' + y + ',' + z);
                }
            }
        }
        require(fence.occupiedCellCount() == 16, "fence occupied cell count changed");

        VoxelShapeEncoder.EncodedShape pane = VoxelShapeEncoder.encode(
                Shapes.box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625), VoxelSubdivision.FOUR, OPAQUE
        );
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    boolean expected = z == 1 || z == 2;
                    require(pane.occupied(x, y, z) == expected,
                            "pane reference mask changed at " + x + ',' + y + ',' + z);
                }
            }
        }
        require(pane.occupiedCellCount() == 32, "pane occupied cell count changed");
    }

    private static void testCoverageAwareOpticsAndDeterminism() {
        VoxelShape thinPane = Shapes.box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625);
        VoxelMaterialDescriptor glass = VoxelMaterialDescriptor.defaults(VoxelMaterialClass.GLASS);
        VoxelShapeEncoder.EncodedShape first = VoxelShapeEncoder.encode(
                thinPane, VoxelSubdivision.FOUR, glass
        );
        VoxelShapeEncoder.EncodedShape second = VoxelShapeEncoder.encode(
                thinPane, VoxelSubdivision.FOUR, glass
        );
        require(first.occupancyMask() == second.occupancyMask()
                        && java.util.Arrays.equals(first.coverageBytes(), second.coverageBytes())
                        && java.util.Arrays.equals(first.opticalBytes(), second.opticalBytes()),
                "shape encoding is not deterministic");
        int cell = VoxelSubdivision.FOUR.cellIndex(0, 0, 1);
        // The pane is centred on the z=0.5 cell boundary: each occupied z cell receives
        // half of its 1/8-block thickness, i.e. one quarter of one 4x cell by volume.
        require(first.coverageByte(cell) == 64,
                "boundary-split pane coverage did not quantize conservatively");
        require(first.opticalByte(cell) > 0 && first.opticalByte(cell) < 255,
                "glass pane became transparent-air or fully opaque");
        require(first.opticalByte(cell) < first.coverageByte(cell),
                "glass optical byte ignored material transmittance");
    }

    private static void testDebugSliceVisualization() {
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        occupancy[16 * VoxelBrickPatch.LOGICAL_EDGE + 8] = 1 << 12;
        int baseEdge = VoxelBrickPatch.LOGICAL_EDGE / VoxelSubdivision.FOUR.scale();
        byte[] optical = new byte[baseEdge * baseEdge * baseEdge];
        Arrays.fill(optical, (byte) VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.AIR
        ).packedUnsignedByte());
        int opticalIndex = (4 * baseEdge + 2) * baseEdge + 3;
        optical[opticalIndex] = (byte) VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.GLASS
        ).packedUnsignedByte();
        VoxelBrickPatch patch = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 1, 1L, 1L, occupancy, optical
        );
        byte[] ppm = VoxelDebugVisualization.renderPpm(patch, VoxelSubdivision.FOUR, 16);
        byte[] marker = "\n255\n".getBytes(StandardCharsets.US_ASCII);
        int pixels = indexAfter(ppm, marker);
        int occupiedPixel = pixels + ((31 - 8) * 32 + 12) * 3;
        require(ppm[occupiedPixel] != 0
                        && Byte.toUnsignedInt(ppm[occupiedPixel + 1])
                        > Byte.toUnsignedInt(ppm[occupiedPixel])
                        && Byte.toUnsignedInt(ppm[occupiedPixel + 2])
                        > Byte.toUnsignedInt(ppm[occupiedPixel]),
                "L5 debug slice did not render occupied glass with its diagnostic hue");
        int emptyPixel = pixels + ((31 - 8) * 32 + 11) * 3;
        require(ppm[emptyPixel] == 0 && ppm[emptyPixel + 1] == 0 && ppm[emptyPixel + 2] == 0,
                "L5 debug slice rendered an unoccupied cell");
        expect(IndexOutOfBoundsException.class,
                () -> VoxelDebugVisualization.renderPpm(patch, VoxelSubdivision.FOUR, 32));
    }

    private static int indexAfter(final byte[] haystack, final byte[] needle) {
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return index + needle.length;
            }
        }
        throw new AssertionError("PPM header terminator missing");
    }

    private static void testClipmapPresetSizing() {
        VoxelClipmapLayout.Budget performance = VoxelClipmapLayout.forPreset(
                VoxelClipmapLayout.Preset.PERFORMANCE
        );
        require(levels(performance).equals(List.of("64@2", "128@1")),
                "Performance accidentally enabled the optional 32@4 level");
        require(performance.occupancyBytes() == 524_416L
                        && performance.opticalBytes() == 2_359_424L
                        && performance.metadataBytes() == 2_176L
                        && performance.sharedUploadRingBytes() == 886_368L
                        && performance.privatePatchRingBytes() == 886_368L
                        && performance.indirectBytes() == 144L
                        && performance.parameterBytes() == 3_072L
                        && performance.debugBytes() == 24L
                        && performance.totalDedicatedBytes() == 4_661_992L
                        && performance.totalDedicatedBytes() <= performance.hardResourceBudgetBytes(),
                "Performance resource accounting changed");

        VoxelClipmapLayout.Budget balanced = VoxelClipmapLayout.forPreset(
                VoxelClipmapLayout.Preset.BALANCED
        );
        require(levels(balanced).equals(List.of("64@4", "128@2", "256@1")),
                "Balanced level topology changed");
        require(balanced.levels().get(0).logicalEdge() == 256
                        && balanced.levels().get(0).brickBlockEdge() == 8
                        && balanced.levels().get(0).brickCountPerAxis() == 8
                        && balanced.levels().get(1).logicalEdge() == 256
                        && balanced.levels().get(1).brickBlockEdge() == 16
                        && balanced.levels().get(1).brickCountPerAxis() == 8
                        && balanced.levels().get(2).logicalEdge() == 256
                        && balanced.levels().get(2).brickBlockEdge() == 32
                        && balanced.levels().get(2).brickCountPerAxis() == 8,
                "Balanced logical-cell and world-brick layout changed");
        require(balanced.levels().getFirst().occupancyBytes() == 2L << 20,
                "64@4 occupancy is no longer 2 MiB");
        require(balanced.occupancyBytes() == 6_291_648L
                        && balanced.opticalBytes() == 19_136_704L
                        && balanced.metadataBytes() == 24_768L
                        && balanced.sharedUploadRingBytes() == 886_368L
                        && balanced.privatePatchRingBytes() == 886_368L
                        && balanced.indirectParamsDebugOverheadBytes() == 4_848L
                        && balanced.totalDedicatedBytes() == 27_230_704L
                        && balanced.hardResourceBudgetBytes() == 64L << 20,
                "Balanced resource/ring accounting changed");

        VoxelClipmapLayout.Budget ultra = VoxelClipmapLayout.forPreset(
                VoxelClipmapLayout.Preset.ULTRA
        );
        require(levels(ultra).equals(List.of("96@4", "192@2", "384@1")),
                "Ultra level topology changed");
        require(ultra.occupancyBytes() == 21_233_856L
                        && ultra.opticalBytes() == 64_585_920L
                        && ultra.metadataBytes() == 83_136L
                        && ultra.sharedUploadRingBytes() == 886_368L
                        && ultra.privatePatchRingBytes() == 886_368L
                        && ultra.indirectParamsDebugOverheadBytes() == 4_848L
                        && ultra.totalDedicatedBytes() == 87_680_496L
                        && ultra.hardResourceBudgetBytes() == 128L << 20,
                "Ultra resource/ring accounting changed");
        require(ultra.hardDrainBudget() == 8
                        && ultra.maxBricksPerSubmit() == 8
                        && ultra.ringSlots() == 3,
                "L5 hard submit budget changed");
    }

    private static void testCameraScrollAndToroidalAddressing() {
        VoxelClipmapLayout.Level level = new VoxelClipmapLayout.Level(64, VoxelSubdivision.TWO);
        VoxelClipmapLayout.Origin initial = VoxelClipmapLayout.cameraCenteredOrigin(level, 0L, 0L, 0L);
        VoxelClipmapLayout.Origin oneBlock = VoxelClipmapLayout.cameraCenteredOrigin(level, 1L, 0L, 0L);
        require(initial.equals(oneBlock) && initial.x() == -32L,
                "small camera movement unnecessarily scrolled the clipmap");
        VoxelClipmapLayout.Scroll noScroll = VoxelClipmapLayout.scroll(level, initial, oneBlock);
        require(!noScroll.fullReset() && noScroll.incomingSlabs().isEmpty(),
                "same brick-aligned origin produced work");

        VoxelClipmapLayout.Origin scrolled = VoxelClipmapLayout.cameraCenteredOrigin(level, 16L, 0L, 0L);
        VoxelClipmapLayout.Scroll oneBrick = VoxelClipmapLayout.scroll(level, initial, scrolled);
        require(!oneBrick.fullReset() && oneBrick.incomingSlabs().equals(List.of(
                        new VoxelClipmapLayout.Slab(
                                VoxelClipmapLayout.Axis.X,
                                VoxelClipmapLayout.Direction.POSITIVE,
                                32L,
                                16
                        )
                )), "one-brick scroll did not update exactly its incoming slab");
        VoxelClipmapLayout.Scroll teleport = VoxelClipmapLayout.scroll(
                level, initial, new VoxelClipmapLayout.Origin(64L, -32L, -32L)
        );
        require(teleport.fullReset() && teleport.incomingSlabs().isEmpty(),
                "one-full-level teleport did not reset generation");

        List<VoxelClipmapLayout.Level> balancedLevels = List.of(
                new VoxelClipmapLayout.Level(64, VoxelSubdivision.FOUR),
                new VoxelClipmapLayout.Level(128, VoxelSubdivision.TWO),
                new VoxelClipmapLayout.Level(256, VoxelSubdivision.ONE)
        );
        require(VoxelClipmapLayout.scrollPhaseBlocks(balancedLevels.get(0)) == 0
                        && VoxelClipmapLayout.scrollPhaseBlocks(balancedLevels.get(1)) == 8
                        && VoxelClipmapLayout.scrollPhaseBlocks(balancedLevels.get(2)) == 16,
                "nested L5 scroll phases lost their fine/medium/coarse staggering");
        for (long coordinate = -64L; coordinate <= 64L; coordinate++) {
            int scrollingLevels = 0;
            for (VoxelClipmapLayout.Level candidate : balancedLevels) {
                VoxelClipmapLayout.Origin before = VoxelClipmapLayout.cameraCenteredOrigin(
                        candidate, coordinate - 1L, 0L, 0L
                );
                VoxelClipmapLayout.Origin after = VoxelClipmapLayout.cameraCenteredOrigin(
                        candidate, coordinate, 0L, 0L
                );
                if (before.x() != after.x()) {
                    scrollingLevels++;
                }
            }
            require(scrollingLevels < balancedLevels.size(),
                    "all nested L5 levels scrolled on one axial world-grid boundary");
        }

        int negative = VoxelClipmapLayout.toroidalCellCoordinate(level, -1L, 1);
        require(negative == 127, "negative toroidal coordinate changed");
        int nearLimit = VoxelClipmapLayout.toroidalCellCoordinate(level, 30_000_000L, 0);
        int wrapped = VoxelClipmapLayout.toroidalCellCoordinate(level, 30_000_064L, 0);
        require(nearLimit == wrapped, "positive large coordinate did not wrap by clipmap span");
        long offset = VoxelClipmapLayout.toroidalCellOffset(
                level, -30_000_000L, 30_000_000L, -1L, 1, 0, 1
        );
        require(offset >= 0L && offset < level.cellCount(),
                "large signed coordinate produced an out-of-range toroidal offset");
        require(VoxelClipmapLayout.brickCoordinate(level, -1L) == -1L
                        && VoxelClipmapLayout.brickCoordinate(level, -16L) == -1L
                        && VoxelClipmapLayout.brickCoordinate(level, -17L) == -2L,
                "negative brick floor division changed");
    }

    private static void testAsyncPackingOracleAtAllSubdivisions() {
        VoxelSectionSnapshot fourSource = snapshotWithCell(0, 1L << 63, 17);
        VoxelBrickPatch four = VoxelBrickPacker.pack(packTicket(
                VoxelSubdivision.FOUR,
                List.of(contributor(0, 0, 0, fourSource)),
                1
        ));
        require(four.occupancyWords()[3 * 32 + 3] == 1 << 3
                        && Byte.toUnsignedInt(four.opticalPayload()[0]) == 17,
                "4x async brick oracle changed its fine mask or optical byte");

        byte coverageFoldedOpaque = (byte) new VoxelMaterialDescriptor(
                VoxelMaterialClass.OPAQUE, 0.5f
        ).packedUnsignedByte();
        VoxelSectionSnapshot partialOpaque = snapshotWithCell(0, 1L, coverageFoldedOpaque);
        VoxelBrickPatch exactOpaque = VoxelBrickPacker.pack(packTicket(
                VoxelSubdivision.FOUR, List.of(contributor(0, 0, 0, partialOpaque)), 41
        ));
        require(Byte.toUnsignedInt(exactOpaque.opticalPayload()[0]) == OPAQUE.packedUnsignedByte(),
                "an occupied exact 4x opaque cell retained block-average transmittance");
        byte coverageFoldedCutout = (byte) new VoxelMaterialDescriptor(
                VoxelMaterialClass.CUTOUT, 0.5f
        ).packedUnsignedByte();
        VoxelBrickPatch exactCutout = VoxelBrickPacker.pack(packTicket(
                VoxelSubdivision.FOUR,
                List.of(contributor(0, 0, 0, snapshotWithCell(0, 1L, coverageFoldedCutout))),
                42
        ));
        require(Byte.toUnsignedInt(exactCutout.opticalPayload()[0])
                        == VoxelMaterialDescriptor.defaults(
                        VoxelMaterialClass.CUTOUT
        ).packedUnsignedByte(),
                "an occupied exact 4x cutout cell retained block-average transmittance");
        VoxelBrickPatch coarseOpaque = VoxelBrickPacker.pack(packTicket(
                VoxelSubdivision.TWO, List.of(contributor(0, 0, 0, partialOpaque)), 43
        ));
        require(Byte.toUnsignedInt(coarseOpaque.opticalPayload()[0])
                        == Byte.toUnsignedInt(coverageFoldedOpaque),
                "a coarse 2x cell lost its coverage-aware opaque transmittance");
        List<VoxelBrickPacker.Contributor> coarseContributors = new ArrayList<>(8);
        for (int sectionZ = 0; sectionZ < 2; sectionZ++) {
            for (int sectionY = 0; sectionY < 2; sectionY++) {
                for (int sectionX = 0; sectionX < 2; sectionX++) {
                    coarseContributors.add(contributor(
                            sectionX, sectionY, sectionZ,
                            sectionX == 0 && sectionY == 0 && sectionZ == 0
                                    ? partialOpaque : VoxelSectionSnapshot.empty()
                    ));
                }
            }
        }
        VoxelBrickPatch coarsestOpaque = VoxelBrickPacker.pack(packTicket(
                VoxelSubdivision.ONE, coarseContributors, 44
        ));
        require(Byte.toUnsignedInt(coarsestOpaque.opticalPayload()[0])
                        == Byte.toUnsignedInt(coverageFoldedOpaque),
                "a coarse 1x cell lost its coverage-aware opaque transmittance");

        int lastBlock = (15 << 8) | (15 << 4) | 15;
        VoxelSectionSnapshot twoSource = snapshotWithCell(lastBlock, 1L << 63, 29);
        VoxelBrickPatch two = VoxelBrickPacker.pack(packTicket(
                VoxelSubdivision.TWO,
                List.of(contributor(0, 0, 0, twoSource)),
                2
        ));
        require(two.occupancyWords()[31 * 32 + 31] == 1 << 31
                        && Byte.toUnsignedInt(two.opticalPayload()[two.opticalLength() - 1]) == 29,
                "2x async brick oracle changed its coarse mask or optical byte");

        List<VoxelBrickPacker.Contributor> contributors = new ArrayList<>(8);
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    int marker = 1 + x + 2 * y + 4 * z;
                    contributors.add(contributor(
                            x, y, z, snapshotWithCell(0, 1L, marker)
                    ));
                }
            }
        }
        VoxelBrickPatch one = VoxelBrickPacker.pack(packTicket(
                VoxelSubdivision.ONE, contributors, 3
        ));
        int[] oneWords = one.occupancyWords();
        byte[] oneOptical = one.opticalPayload();
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    int logicalX = x * 16;
                    int logicalY = y * 16;
                    int logicalZ = z * 16;
                    require((oneWords[logicalZ * 32 + logicalY] & 1 << logicalX) != 0,
                            "1x eight-section oracle omitted contributor " + x + ',' + y + ',' + z);
                    int opticalIndex = (logicalZ * 32 + logicalY) * 32 + logicalX;
                    require(Byte.toUnsignedInt(oneOptical[opticalIndex])
                                    == 1 + x + 2 * y + 4 * z,
                            "1x eight-section oracle misaddressed contributor optics");
                }
            }
        }
    }

    private static void testAsyncPackingStateMachineAndRaces() {
        ManualExecutor manual = new ManualExecutor();
        VoxelClipmapLayout.Budget budget = VoxelClipmapLayout.forPreset(
                VoxelClipmapLayout.Preset.BALANCED
        );
        VoxelClipmapController controller = new VoxelClipmapController(
                budget, () -> new VoxelBrickPacker(manual)
        );
        Object world = new Object();
        controller.openWorld(world, "minecraft:overworld");
        controller.updateCamera(world, 0.0, 64.0, 0.0);

        // One pending accepted-geometry section must rotate rather than block absent bricks.
        long pendingKey = SectionPos.asLong(0, 4, 0);
        controller.beginSectionTask(world, "minecraft:overworld", pendingKey);
        controller.markBlockDirty(world, "minecraft:overworld", 0, 64, 0);
        require(controller.leaseUploadBatch(1L) == null,
                "render-thread lease unexpectedly packed a full brick synchronously");
        require(manual.size() == controller.asyncPipelineCapacity()
                        && manual.size() == 2 * budget.hardDrainBudget(),
                "Balanced initial refill was frame-throttled below its bounded async backlog");
        require(controller.asyncPendingPackJobs() == manual.size()
                        && controller.asyncPipelineDepth() <= controller.asyncPipelineCapacity(),
                "queued-worker/ready/in-flight async depth exceeded its hard cap");

        // Pending-section bricks were deferred, while unrelated explicit-absence tickets ran.
        manual.runAllOnWorker();
        VoxelUploadBatch absenceBatch = controller.leaseUploadBatch(2L);
        require(absenceBatch != null && absenceBatch.patches().stream().allMatch(patch ->
                        Arrays.stream(patch.occupancyWords()).allMatch(word -> word == 0)),
                "pending accepted section leaked into an explicit-absence async batch");
        controller.completeUploadBatch(absenceBatch.batchId());

        VoxelSectionTask firstOwner = controller.beginSectionTask(
                world, "minecraft:overworld", pendingKey
        );
        require(controller.publishAccepted(sectionCandidate(firstOwner, -1L)),
                "async race fixture could not publish accepted geometry");
        // Drop work captured before unload/load owner replacement.
        manual.runAllOnWorker();
        controller.leaseUploadBatch(3L);
        require(controller.removeSectionIfOwner(world, pendingKey, firstOwner.ownerToken()),
                "async race fixture could not unload its accepted owner");
        VoxelSectionTask replacement = controller.beginSectionTask(
                world, "minecraft:overworld", pendingKey
        );
        require(controller.publishAccepted(VoxelSectionCandidate.empty(replacement)),
                "async race fixture could not publish replacement owner");
        manual.runAllOnWorker();
        VoxelUploadBatch staleOwner = controller.leaseUploadBatch(4L);
        require(staleOwner == null || staleOwner.patches().stream().allMatch(patch ->
                        Arrays.stream(patch.occupancyWords()).allMatch(word -> word == 0)),
                "unload/load race published an old contributor-owner completion");

        long beforeScroll = controller.snapshot().clipmapGeneration();
        controller.updateCamera(world, 16.0, 64.0, 0.0);
        require(controller.snapshot().clipmapGeneration() == beforeScroll,
                "normal async scroll rotated the clipmap generation");
        controller.leaseUploadBatch(5L);
        long beforeTeleport = controller.snapshot().clipmapGeneration();
        controller.updateCamera(world, 4_096.0, 64.0, 0.0);
        require(controller.snapshot().clipmapGeneration() > beforeTeleport,
                "async teleport did not rotate its clipmap generation");
        manual.runAllOnWorker();
        VoxelUploadBatch afterTeleport = controller.leaseUploadBatch(6L);
        require(afterTeleport == null
                        || afterTeleport.clipmapGeneration()
                        == controller.snapshot().clipmapGeneration(),
                "late pre-teleport async completion reached an upload lease");
        require(controller.asyncPipelineDepth() <= controller.asyncPipelineCapacity(),
                "teleport/refill race exceeded the async pipeline cap");
        controller.closeWorld(world);

        ManualExecutor packerExecutor = new ManualExecutor();
        VoxelBrickPacker packer = new VoxelBrickPacker(packerExecutor);
        VoxelBrickPacker.Ticket ticket = packTicket(
                VoxelSubdivision.FOUR,
                List.of(contributor(0, 0, 0, VoxelSectionSnapshot.empty())),
                9
        );
        for (int index = 0; index < VoxelBrickPacker.MAX_PENDING_JOBS; index++) {
            require(packer.submit(ticket), "bounded packer rejected an in-capacity job");
        }
        require(!packer.submit(ticket)
                        && packerExecutor.size() == VoxelBrickPacker.MAX_PENDING_JOBS,
                "bounded packer accepted more than its hard pending-job cap");
        packer.close();
    }

    private static VoxelBrickPacker.Ticket packTicket(
            final VoxelSubdivision subdivision,
            final List<VoxelBrickPacker.Contributor> contributors,
            final int contentStamp
    ) {
        VoxelClipmapLayout.Level layout = new VoxelClipmapLayout.Level(64, subdivision);
        VoxelDirtyQueue.BrickKey key = new VoxelDirtyQueue.BrickKey(1L, 1L, 0, 0L, 0L, 0L);
        VoxelDirtyQueue.DirtyBrick dirty = new VoxelDirtyQueue.DirtyBrick(
                key, VoxelDirtyQueue.Priority.HIGH, 0L, 0L, 1L, 0L, 0, false
        );
        return new VoxelBrickPacker.Ticket(
                dirty, new VoxelWorldToken(1L, "minecraft:overworld"), 1L, layout,
                layout.brickCountPerAxis(), 1L, contentStamp, contributors
        );
    }

    private static VoxelBrickPacker.Contributor contributor(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final VoxelSectionSnapshot snapshot
    ) {
        return new VoxelBrickPacker.Contributor(
                SectionPos.asLong(sectionX, sectionY, sectionZ), 1L, 1L, snapshot, false
        );
    }

    private static VoxelSectionSnapshot snapshotWithCell(
            final int index,
            final long mask,
            final int optical
    ) {
        long[] occupancy = new long[VoxelSectionSnapshot.BLOCK_COUNT];
        byte[] optics = new byte[VoxelSectionSnapshot.BLOCK_COUNT];
        occupancy[index] = mask;
        optics[index] = (byte) optical;
        return new VoxelSectionSnapshot(occupancy, optics);
    }

    private static void testDirtyQueueCoalescingBoundsAgeAndActualDrain() {
        VoxelDirtyQueue queue = new VoxelDirtyQueue(3, 1, OptionalLong.of(10L), 5L);
        VoxelDirtyQueue.BrickKey old = key(1L, 1L, 0L);
        VoxelDirtyQueue.BrickKey another = key(1L, 1L, 1L);
        require(queue.offer(old, VoxelDirtyQueue.Priority.LOW, 2L).status()
                        == VoxelDirtyQueue.OfferStatus.ENQUEUED,
                "first dirty brick was not enqueued");
        require(queue.offer(old, VoxelDirtyQueue.Priority.HIGH, 7L).status()
                        == VoxelDirtyQueue.OfferStatus.COALESCED,
                "same dirty brick did not coalesce");
        require(queue.offer(another, VoxelDirtyQueue.Priority.NORMAL, 2L).status()
                        == VoxelDirtyQueue.OfferStatus.ENQUEUED,
                "second dirty brick was not enqueued");
        queue.advanceTo(5L);
        VoxelDirtyQueue.BrickKey freshCritical = key(1L, 1L, 2L);
        require(queue.offer(freshCritical, VoxelDirtyQueue.Priority.CRITICAL, 20L).status()
                        == VoxelDirtyQueue.OfferStatus.ENQUEUED,
                "critical dirty brick was not enqueued");
        VoxelDirtyQueue.Drain first = queue.drainActualCount();
        require(first.actualCount() == 1 && first.bricks().size() == 1
                        && first.bricks().getFirst().key().equals(old)
                        && first.bricks().getFirst().priority() == VoxelDirtyQueue.Priority.HIGH
                        && first.bricks().getFirst().coalescedUpdates() == 1
                        && first.bricks().getFirst().starvationPromoted(),
                "starved coalesced brick did not win deterministic drain");
        require(first.estimatedNanos() == 7L,
                "actual-count drain lost the coalesced maximum estimate");
        long originalTick = first.bricks().getFirst().enqueuedTick();
        long originalSequence = first.bricks().getFirst().enqueueSequence();
        queue.advanceTo(12L);
        require(queue.requeue(first.bricks().getFirst()).status() == VoxelDirtyQueue.OfferStatus.ENQUEUED,
                "busy retry did not return the leased dirty brick to the queue");
        VoxelDirtyQueue.Drain retried = queue.drainActualCount();
        VoxelDirtyQueue.DirtyBrick retriedBrick = retried.bricks().getFirst();
        require(retriedBrick.key().equals(old)
                        && retriedBrick.enqueuedTick() == originalTick
                        && retriedBrick.ageTicks() == 12L
                        && retriedBrick.enqueueSequence() == originalSequence
                        && retriedBrick.coalescedUpdates() == 1
                        && retriedBrick.estimatedNanos() == 7L
                        && retriedBrick.starvationPromoted(),
                "busy retry made the dirty brick young or lost its queue metadata");

        int stale = queue.discardIf(key -> key.worldGeneration() != 1L || key.clipmapGeneration() != 1L);
        require(stale == 0, "current dirty brick was treated as stale");
        require(queue.offer(key(2L, 1L, 3L), VoxelDirtyQueue.Priority.NORMAL, 1L).status()
                        == VoxelDirtyQueue.OfferStatus.ENQUEUED,
                "new-world dirty brick was not accepted");
        require(queue.discardIf(key -> key.worldGeneration() != 1L) == 1,
                "stale world dirty brick was not discarded");

        VoxelDirtyQueue bounded = new VoxelDirtyQueue(3, 3, OptionalLong.empty(), 20L);
        for (int index = 0; index < 1_000; index++) {
            bounded.offer(key(7L, 9L, index), VoxelDirtyQueue.Priority.LOW, 1L);
        }
        require(bounded.size() == 3 && bounded.telemetry().highWaterMark() == 3
                        && bounded.telemetry().rejected() == 997L
                        && bounded.telemetry().lastOverflowReason()
                        == VoxelDirtyQueue.OverflowReason.CAPACITY_REACHED_REQUIRES_FALLBACK,
                "rapid-flight dirty queue exceeded its hard capacity");
        VoxelDirtyQueue.Drain boundedDrain = bounded.drainActualCount();
        require(boundedDrain.actualCount() == 3 && boundedDrain.bricks().size() == 3,
                "actual dirty-brick dispatch count did not match drained work");
    }

    private static void testReadyPublicationFairness() {
        VoxelDirtyQueue.DirtyBrick fineNormal = readyWork(
                0, VoxelDirtyQueue.Priority.NORMAL, 9L, 0L
        );
        VoxelDirtyQueue.DirtyBrick coarseCritical = readyWork(
                2, VoxelDirtyQueue.Priority.CRITICAL, 9L, 1L
        );
        require(VoxelClipmapController.compareReadyForPublication(
                        coarseCritical, fineNormal, 10L, 5L
                ) < 0,
                "ready L2 critical update still starved behind an L0 normal update");

        VoxelDirtyQueue.DirtyBrick oldCoarse = readyWork(
                2, VoxelDirtyQueue.Priority.LOW, 0L, 2L
        );
        require(VoxelClipmapController.compareReadyForPublication(
                        oldCoarse, coarseCritical, 10L, 5L
                ) < 0,
                "starved ready coarse update did not outrank fresh fine work");
        VoxelDirtyQueue.DirtyBrick olderSequence = readyWork(
                1, VoxelDirtyQueue.Priority.LOW, 0L, 1L
        );
        require(VoxelClipmapController.compareReadyForPublication(
                        olderSequence, oldCoarse, 10L, 5L
                ) < 0,
                "starved ready updates lost deterministic FIFO order");
    }

    private static VoxelDirtyQueue.DirtyBrick readyWork(
            final int level,
            final VoxelDirtyQueue.Priority priority,
            final long enqueuedTick,
            final long sequence
    ) {
        return new VoxelDirtyQueue.DirtyBrick(
                new VoxelDirtyQueue.BrickKey(1L, 1L, level, level, 0L, 0L),
                priority, enqueuedTick, 0L, 1L, sequence, 0, false
        );
    }

    private static void testClipmapControllerLifecycleAndRetry() {
        VoxelClipmapController controller = new VoxelClipmapController(
                VoxelClipmapLayout.forPreset(VoxelClipmapLayout.Preset.PERFORMANCE)
        );
        Object world = new Object();
        VoxelWorldToken token = controller.openWorld(world, "minecraft:overworld");
        controller.updateCamera(world, 0.0, 64.0, 0.0);
        VoxelClipmapSnapshot initial = controller.snapshot();
        require(initial != null && initial.world().equals(token),
                "L5 controller did not publish its active world snapshot");

        long sectionKey = SectionPos.asLong(0, 4, 0);
        VoxelSectionTask acceptedTask = controller.beginSectionTask(
                world, "minecraft:overworld", sectionKey
        );
        VoxelSectionCandidate accepted = sectionCandidate(acceptedTask, -1L);
        controller.noteSectionCandidateEncoded(accepted);
        require(controller.publishAccepted(accepted) && !controller.publishAccepted(accepted),
                "L5 accepted geometry ownership was not single-publication");

        VoxelUploadBatch first = awaitBatch(controller, 3L);
        require(first != null && first.patches().size() <= 8
                        && first.patches().stream().anyMatch(patch -> Arrays.stream(
                        patch.occupancyWords()).anyMatch(word -> word != 0)),
                "Accepted Sodium geometry did not reach a bounded L5 upload batch");
        require(isSortedByLevel(first.patches()) && controller.retryUploadBatch(first.batchId()),
                "L5 retry did not preserve a level-compacted transient batch");
        List<VoxelBrickPatch> busyPatches = first.patches();
        VoxelUploadBatch retried = awaitBatch(controller, 4L);
        require(retried != null && controller.completeUploadBatch(retried.batchId()),
                "L5 retry did not return to an exactly-once completed lease");
        require(retried.patches().size() == busyPatches.size(),
                "busy retry changed its exact ready-patch count");
        for (int index = 0; index < busyPatches.size(); index++) {
            require(retried.patches().get(index) == busyPatches.get(index),
                    "busy retry repacked or replaced an immutable ready patch");
        }

        VoxelUploadBatch beforeGpuFailure = awaitBatch(controller, 5L);
        long beforeGpuFailureGeneration = controller.snapshot().clipmapGeneration();
        require(beforeGpuFailure != null, "L5 controller had no work to recover after a GPU failure");
        controller.recoverAfterGpuFailure();
        require(controller.snapshot().clipmapGeneration() > beforeGpuFailureGeneration
                        && !controller.retryUploadBatch(beforeGpuFailure.batchId()),
                "Asynchronous L5 GPU recovery did not retire the lost native generation");
        boolean recoveredAcceptedGeometry = false;
        for (int frame = 6; frame < 70 && !recoveredAcceptedGeometry; frame++) {
            VoxelUploadBatch recovered = awaitBatch(controller, frame);
            require(recovered != null && controller.completeUploadBatch(recovered.batchId()),
                    "L5 GPU recovery did not retain bounded refill work");
            recoveredAcceptedGeometry = recovered.patches().stream().anyMatch(patch -> Arrays.stream(
                    patch.occupancyWords()).anyMatch(word -> word != 0));
        }
        require(recoveredAcceptedGeometry,
                "L5 GPU recovery did not queue a durable refill from accepted geometry");

        long generation = controller.snapshot().clipmapGeneration();
        controller.updateCamera(world, 16.0, 64.0, 0.0);
        require(controller.snapshot().clipmapGeneration() == generation,
                "Normal L5 toroidal scroll cleared/rotated the full clipmap generation");

        VoxelSectionTask staleTask = controller.beginSectionTask(
                world, "minecraft:overworld", sectionKey
        );
        long offeredBeforeInvalidation = controller.telemetry().dirtyQueue().offered();
        controller.markBlockDirty(world, "minecraft:overworld", 0, 64, 0);
        require(controller.telemetry().dirtyQueue().offered() == offeredBeforeInvalidation,
                "block invalidation enqueued bricks before matching Sodium geometry existed");
        require(!controller.publishAccepted(sectionCandidate(staleTask, 1L)),
                "A pre-invalidation Sodium snapshot was accepted as current L5 geometry");
        require(controller.telemetry().dirtyQueue().offered() == offeredBeforeInvalidation,
                "stale Sodium publication released unavailable L5 brick work");
        VoxelSectionTask emptyTask = controller.beginSectionTask(
                world, "minecraft:overworld", sectionKey
        );
        VoxelSectionCandidate emptyCandidate = VoxelSectionCandidate.empty(emptyTask);
        controller.noteSectionCandidateEncoded(emptyCandidate);
        require(emptyCandidate.scannedStateCount() == 0
                        && controller.publishAccepted(emptyCandidate),
                "Sodium's accepted empty-section fast path did not publish a zero snapshot");
        require(controller.telemetry().dirtyQueue().offered() > offeredBeforeInvalidation,
                "matching Sodium publication did not release deferred critical L5 work");
        VoxelUploadBatch empty = awaitBatch(controller, 70L);
        require(empty != null && empty.patches().stream().anyMatch(patch ->
                        Arrays.stream(patch.occupancyWords()).allMatch(word -> word == 0)
                                && allZero(patch.opticalPayload())),
                "Resident section becoming empty left stale occupancy or optical data");
        require(controller.completeUploadBatch(empty.batchId()),
                "Accepted empty-section L5 batch did not complete");

        VoxelSectionTask replacementTask = controller.beginSectionTask(
                world, "minecraft:overworld", sectionKey
        );
        require(controller.publishAccepted(sectionCandidate(replacementTask, 1L)),
                "Post-invalidation accepted geometry did not replace the L5 snapshot");
        require(!controller.removeSectionIfOwner(world, sectionKey, replacementTask.ownerToken() + 1L)
                        && controller.removeSectionIfOwner(
                        world, sectionKey, replacementTask.ownerToken()),
                "Chunk unload did not require the exact accepted L5 owner token");
        VoxelUploadBatch unload = awaitBatch(controller, 71L);
        require(unload != null && unload.unloadClears() == 1
                        && unload.patches().stream().anyMatch(patch -> Arrays.stream(
                        patch.occupancyWords()).allMatch(word -> word == 0)),
                "Chunk unload left stale occupancy instead of an explicit empty patch");
        require(controller.completeUploadBatch(unload.batchId()),
                "Unload-clear L5 batch did not complete");

        VoxelUploadBatch beforeTeleport = awaitBatch(controller, 72L);
        require(beforeTeleport != null, "L5 controller had no bounded refill work before teleport");
        controller.updateCamera(world, 2_048.0, 64.0, 0.0);
        require(controller.snapshot().clipmapGeneration() > generation
                        && !controller.retryUploadBatch(beforeTeleport.batchId()),
                "Teleport did not retire a stale in-flight L5 generation");
        VoxelSectionTask lateTask = controller.beginSectionTask(
                world, "minecraft:overworld", sectionKey
        );
        Object nextWorld = new Object();
        controller.openWorld(nextWorld, "minecraft:the_nether");
        require(!controller.publishAccepted(sectionCandidate(lateTask, -1L)),
                "A late worker result crossed an L5 world/dimension generation");
        controller.closeWorld(world);
        require(controller.snapshot() != null, "Foreign world close removed the active L5 world");
        controller.closeWorld(nextWorld);
        require(controller.snapshot() == null, "L5 world close retained stale clipmap state");
    }

    private static void testControllerRetryPreservesTelemetryDeltas() {
        VoxelClipmapController controller = new VoxelClipmapController(
                VoxelClipmapLayout.forPreset(VoxelClipmapLayout.Preset.PERFORMANCE)
        );
        Object world = new Object();
        controller.openWorld(world, "minecraft:overworld");
        controller.updateCamera(world, 0.0, 64.0, 0.0);
        long sectionKey = SectionPos.asLong(0, 4, 0);
        VoxelSectionTask task = controller.beginSectionTask(world, "minecraft:overworld", sectionKey);
        require(controller.publishAccepted(sectionCandidate(task, -1L)),
                "telemetry retry fixture did not accept its voxel snapshot");
        VoxelUploadBatch first = awaitBatch(controller, 1L);
        require(first != null && first.coalescedDelta() > 0L,
                "first L5 packet did not contain its queue telemetry increment");
        require(controller.retryUploadBatch(first.batchId()),
                "telemetry retry fixture could not requeue its L5 batch");
        VoxelUploadBatch retry = awaitBatch(controller, 2L);
        require(retry != null
                        && retry.coalescedDelta() == first.coalescedDelta()
                        && retry.rejectedDelta() == first.rejectedDelta(),
                "busy L5 retry lost or duplicated packet telemetry increments");
    }

    private static VoxelSectionCandidate sectionCandidate(
            final VoxelSectionTask task,
            final long occupiedMask
    ) {
        long[] occupancy = new long[VoxelSectionSnapshot.BLOCK_COUNT];
        byte[] optical = new byte[VoxelSectionSnapshot.BLOCK_COUNT];
        occupancy[0] = occupiedMask;
        optical[0] = (byte) VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.OPAQUE
        ).packedUnsignedByte();
        return new VoxelSectionCandidate(
                task,
                new VoxelSectionSnapshot(occupancy, optical),
                VoxelSectionSnapshot.BLOCK_COUNT
        );
    }

    private static boolean isSortedByLevel(final List<VoxelBrickPatch> patches) {
        for (int index = 1; index < patches.size(); index++) {
            if (patches.get(index - 1).level() > patches.get(index).level()) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(final byte[] values) {
        for (byte value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<String> levels(final VoxelClipmapLayout.Budget budget) {
        return budget.levels().stream()
                .map(level -> level.spanBlocks() + "@" + level.subdivision().scale())
                .toList();
    }

    private static VoxelDirtyQueue.BrickKey key(
            final long worldGeneration,
            final long clipmapGeneration,
            final long coordinate
    ) {
        return new VoxelDirtyQueue.BrickKey(
                worldGeneration, clipmapGeneration, 0, coordinate, 0L, 0L
        );
    }

    private static VoxelUploadBatch awaitBatch(
            final VoxelClipmapController controller,
            final long frameId
    ) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        VoxelUploadBatch batch;
        do {
            batch = controller.leaseUploadBatch(frameId);
            if (batch != null) {
                return batch;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for an asynchronous L5 upload batch");
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

        @Override
        public synchronized void execute(final Runnable command) {
            this.pending.addLast(command);
        }

        private synchronized int size() {
            return this.pending.size();
        }

        private void runAllOnWorker() {
            List<Runnable> tasks;
            synchronized (this) {
                tasks = List.copyOf(this.pending);
                this.pending.clear();
            }
            Thread worker = new Thread(() -> tasks.forEach(Runnable::run), "L5-test-worker");
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while joining the L5 test worker", interrupted);
            }
        }
    }

    private static void expect(final Class<? extends Throwable> expected, final Runnable operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
