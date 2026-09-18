package com.metallum.client.gi.live;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Focused CPU oracle for the exact Swift G6 header/stats ABI. */
public final class GiLiveAbiTests {
    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT =
            ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.LITTLE_ENDIAN);

    private GiLiveAbiTests() {
    }

    public static void main(final String[] args) {
        testJavaLayouts();
        testDebugProbeJavaLayout();
        testHeaderEncodingAndEpochStamp();
        testExplicitRetainedBasisHeader();
        testNativeRetainedHandoffSourceGuard();
        testPublicationOrdering();
        testStatsDecodingAndInvariants();
        if (args.length == 1 && args[0].equals("native")) {
            GiLiveGpuResources.validateNativeAbi();
            testBridgeDestinationBounds();
            testNativeDebugProbeAbi();
        }
        System.out.println("GiLiveAbiTests: PASS");
    }

    private static void testJavaLayouts() {
        GiLiveLayout.validateJavaLayouts();
        require(GiLiveLayout.HEADER_LAYOUT.byteSize() == 192L, "header size drifted");
        require(GiLiveLayout.STATS_LAYOUT.byteSize() == 216L, "stats size drifted");
        require(GiLiveLayout.CELLS_BYTES == 524_288L, "cascade packet size drifted");
        require(GiLiveLayout.JAVA_PACKET_BYTES == 1_573_656L,
                "preallocated Java packet accounting drifted");
    }

    private static void testDebugProbeJavaLayout() {
        GiLiveDebugProbeLayout.validateJavaLayouts();
        require(GiLiveDebugProbeLayout.REQUEST_BYTES == 120
                        && GiLiveDebugProbeLayout.RESULT_BYTES == 800
                        && GiLiveDebugProbeLayout.MAX_SAMPLES == 7
                        && GiLiveDebugProbeLayout.READBACK_BYTES == 7_168,
                "G6 debug-probe packet bounds drifted");
    }

    private static void testNativeDebugProbeAbi() {
        require(MetalNativeBridge.metallum_gi_live_debug_probe_abi_version_v1()
                        == GiLiveDebugProbeLayout.ABI_VERSION,
                "native G6 debug-probe ABI version mismatch");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment layout = arena.allocate(GiLiveDebugProbeLayout.LAYOUT_BYTES, Long.BYTES);
            require(MetalNativeBridge.metallum_gi_live_debug_probe_layout_v1(
                            layout, layout.byteSize()) == GiLiveDebugProbeLayout.STATUS_OK,
                    "native G6 debug-probe layout query failed");
            int[] expected = {
                    GiLiveDebugProbeLayout.ABI_VERSION,
                    GiLiveDebugProbeLayout.LAYOUT_BYTES,
                    GiLiveDebugProbeLayout.REQUEST_BYTES,
                    GiLiveDebugProbeLayout.RESULT_BYTES,
                    GiLiveDebugProbeLayout.MAX_SAMPLES,
                    GiLiveDebugProbeLayout.STATUS_OK,
                    GiLiveDebugProbeLayout.STATUS_INVALID,
                    GiLiveDebugProbeLayout.STATUS_BUSY,
                    GiLiveDebugProbeLayout.STATUS_STALE,
                    GiLiveDebugProbeLayout.STATUS_WRONG_THREAD,
                    GiLiveDebugProbeLayout.STATUS_REJECTED,
                    GiLiveDebugProbeLayout.READBACK_BYTES,
                    0, 0, 0, 0
            };
            for (int index = 0; index < expected.length; index++) {
                require(layout.get(LE_INT, (long) index * Integer.BYTES) == expected[index],
                        "native G6 debug-probe layout word " + index + " drifted");
            }
        }
    }

    private static void testHeaderEncodingAndEpochStamp() {
        GiLiveEpoch epoch = epoch(7L, 6L);
        long stamp = GiLiveGpuResources.epochSourceStamp(epoch);
        require(stamp != 0L, "epoch stamp must never be zero");
        require(stamp == GiLiveGpuResources.epochSourceStamp(epoch),
                "epoch stamp is not deterministic");
        require(stamp != GiLiveGpuResources.epochSourceStamp(epoch(7L, 7L)),
                "palette identity is absent from the epoch stamp");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment first = arena.allocate(GiLiveLayout.HEADER_BYTES, Long.BYTES);
            MemorySegment third = arena.allocate(GiLiveLayout.HEADER_BYTES, Long.BYTES);
            GiLiveGpuResources.writeHeader(
                    first, epoch, 0, 123, 456, stamp, 999L,
                    GiLiveLayout.ResetKind.SCROLL
            );
            GiLiveGpuResources.writeHeader(
                    third, epoch, 2, 321, 654, stamp, 999L,
                    GiLiveLayout.ResetKind.SCROLL
            );

            require(first.get(LE_INT, 0L) == GiLiveLayout.ABI_VERSION
                            && first.get(LE_INT, 4L) == 192,
                    "header prefix differs from Swift");
            require(first.get(LE_LONG, 8L) == epoch.worldGeneration(),
                    "world generation offset differs");
            require(first.get(LE_LONG, 24L) == epoch.paletteGeneration(),
                    "palette generation offset differs");
            require(first.get(LE_INT, 64L) == -64
                            && first.get(LE_INT, 80L) == -64
                            && first.get(LE_INT, 96L) == -64,
                    "cascade origin offsets differ");
            require(first.get(LE_INT, 100L) == 0 && third.get(LE_INT, 100L) == 2,
                    "cascade index offset differs");
            require(first.get(LE_INT, 116L) == 123
                            && first.get(LE_INT, 120L) == 456,
                    "semantic counters differ");
            require(Float.floatToRawIntBits(first.get(LE_FLOAT, 124L))
                            == Float.floatToRawIntBits(GiLiveLayout.FORM_WEIGHT_NORMALIZATION),
                    "normalization differs");
            require(first.get(LE_INT, 140L) == 0, "alignment padding is not zero");
            require(first.get(LE_LONG, 144L) == stamp
                            && third.get(LE_LONG, 144L) == stamp,
                    "the three cascades do not share an epoch stamp");
            require(first.get(LE_LONG, 152L) == epoch.version()
                            && first.get(LE_LONG, 160L) == 999L,
                    "publication generation/tick offsets differ");
            require(first.get(LE_INT, 168L) == 2
                            && first.get(LE_INT, 172L) == 0
                            && first.get(LE_LONG, 176L) == 0L
                            && first.get(LE_LONG, 184L) == 0L,
                    "reset/reserved tail differs");
        }
    }

    private static void testStatsDecodingAndInvariants() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = validStatsPacket(arena);
            GiLiveGpuResources.Stats stats = GiLiveGpuResources.decodeStats(packet);
            require(stats.allCascadesReady() && !stats.buildInFlight(),
                    "ready/build state decoded incorrectly");
            require(stats.allocatedBytes() == 65_836L
                            && stats.transportDispatches() == 6L,
                    "memory/dispatch counters decoded incorrectly");
            require(stats.invalidations() == 6L && stats.bindCount() == 5L,
                    "reset/bind counters decoded incorrectly");
            require(stats.lastBindVisibleMask() == GiLiveLayout.READY_MASK_ALL,
                    "bind-time visible mask decoded incorrectly");
            require(stats.receiverVisibleMask() == GiLiveLayout.READY_MASK_ALL,
                    "current receiver visible mask decoded incorrectly");

            packet.set(LE_INT, GiLiveLayout.STATS_RECEIVER_MASK_STATE_OFFSET, 8);
            expectIllegalArgument(() -> GiLiveGpuResources.decodeStats(packet),
                    "out-of-range bind-time visible mask was accepted");
            packet.set(LE_INT, GiLiveLayout.STATS_RECEIVER_MASK_STATE_OFFSET,
                    GiLiveLayout.READY_MASK_ALL
                            | (GiLiveLayout.READY_MASK_ALL
                            << GiLiveLayout.STATS_LAST_BIND_VISIBLE_MASK_SHIFT));
            packet.set(LE_LONG, GiLiveLayout.STATS_ALLOCATED_BYTES_OFFSET, 1L);
            expectIllegalArgument(() -> GiLiveGpuResources.decodeStats(packet),
                    "inconsistent memory accounting was accepted");
        }
    }

    private static void testExplicitRetainedBasisHeader() {
        GiLiveEpoch epoch = epoch(9L, 7L);
        long stamp = GiLiveGpuResources.epochSourceStamp(epoch);
        int handoffFlags = GiLiveLayout.FLAG_INCREMENTAL_BRICKS
                | GiLiveLayout.FLAG_PREPARE_CASCADE
                | GiLiveLayout.FLAG_PRESERVE_EXACT
                | GiLiveLayout.FLAG_RETAIN_CAPTURED_BASIS;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment header = arena.allocate(GiLiveLayout.HEADER_BYTES, Long.BYTES);
            GiLiveGpuResources.writeHeader(
                    header, epoch, 1, 0, 0, stamp, 1_001L,
                    GiLiveLayout.ResetKind.SOURCE,
                    handoffFlags, 0, 0L, 1L << 21
            );
            require(header.get(LE_INT, GiLiveLayout.HEADER_FLAGS_OFFSET) == handoffFlags
                            && GiLiveLayout.FLAG_RETAIN_CAPTURED_BASIS == 16
                            && GiLiveLayout.KNOWN_FLAGS == 31,
                    "Java retained-basis flag differs from the native header contract");
            expectIllegalArgument(() -> GiLiveGpuResources.writeHeader(
                            header, epoch, 1, 0, 0, stamp, 1_001L,
                            GiLiveLayout.ResetKind.SOURCE,
                            handoffFlags & ~GiLiveLayout.FLAG_PRESERVE_EXACT,
                            0, 0L, 1L << 21),
                    "retained-basis handoff without preserve-exact was accepted");
            expectIllegalArgument(() -> GiLiveGpuResources.writeHeader(
                            header, epoch, 1, 0, 0, stamp, 1_001L,
                            GiLiveLayout.ResetKind.SOURCE,
                            handoffFlags, 1, 1L, 1L << 21),
                    "retained-basis handoff was accepted as a transport batch");

            int incrementalFlags = GiLiveLayout.FLAG_INCREMENTAL_BRICKS;
            long maximumBatchMask = (1L << GiLiveLayout.MAX_BRICKS_PER_SUBMIT) - 1L;
            GiLiveGpuResources.writeHeader(
                    header, epoch, 1, 0, 0, stamp, 1_001L,
                    GiLiveLayout.ResetKind.SOURCE,
                    incrementalFlags, GiLiveLayout.MAX_BRICKS_PER_SUBMIT,
                    maximumBatchMask, 0L
            );
            require(header.get(LE_INT, GiLiveLayout.HEADER_RESERVED_32_OFFSET)
                            == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                            && header.get(LE_LONG, GiLiveLayout.HEADER_RESERVED_0_OFFSET)
                            == maximumBatchMask,
                    "maximum G6 batch was not encoded exactly");
            expectIllegalArgument(() -> GiLiveGpuResources.writeHeader(
                            header, epoch, 1, 0, 0, stamp, 1_001L,
                            GiLiveLayout.ResetKind.SOURCE,
                            incrementalFlags, GiLiveLayout.MAX_BRICKS_PER_SUBMIT + 1,
                            (maximumBatchMask << 1) | 1L, 0L),
                    "oversized G6 batch was accepted by the Java header contract");
        }
    }

    private static void testNativeRetainedHandoffSourceGuard() {
        final String source;
        final String transport;
        try {
            source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
            transport = Files.readString(Path.of("src/main/metal/MetallumGiTransport.metal"));
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not read native G6 handoff source", failure);
        }
        int liveKernel = transport.indexOf("kernel void metallum_gi_live_jacobi_sh_v1");
        int liveCoverageWrite = transport.indexOf(
                "1.0h, 0.0h, 0.0h), atlasPosition);", liveKernel
        );
        require(source.contains("metallumGiLiveFlagRetainCapturedBasisV1: UInt32 = 1 << 4")
                        && source.contains("header.reserved32 <= 16")
                        && source.contains("if !retainCapturedBasis {")
                        && source.indexOf("if inFlightMask != 0 {")
                        < source.indexOf("if let current = currentHeader {")
                        && source.contains(
                        "capturedBasisNonRemapWorkAdmitted")
                        && source.contains("capturedBasisWorkAdmitted = true")
                        && source.contains(
                        "if !remapOnly { capturedBasisNonRemapWorkAdmitted = true }")
                        && source.contains("admittedScrollRemapMask |= cascadeBit")
                        && source.contains("retainedExactOrigin(cascade: cascade)")
                        && source.contains("setRetainedExactOrigin(remappedOrigin")
                        && source.contains("setRetainedReceiverOrigin(")
                        && source.contains(
                        "retainedExactBrickMasks[cascade] = exactBrickMasks[cascade]")
                        && source.contains(
                        "retainedReceiverBrickMasks[cascade] = receiverBrickMasks[cascade]")
                        && source.contains(
                        "receiverBrickMasks[cascade] = preserving ? retainedReceiver : 0")
                        && source.contains(
                        "let sampleable = carrierSafe ? receiverBrickMasks[cascade] : 0")
                        && source.contains(
                        "let confidence = makeTexture(.rg8Unorm, depth: metallumGiLiveAtlasDepthV1")
                        && source.contains(
                        "let scratchConfidence = makeTexture(.rg8Unorm, depth: Self.edge")
                        && source.contains(
                        "let confidence = makeTexture(.r8Unorm, \"Metallum G4 frozen path confidence\")")
                        && liveKernel >= 0 && liveCoverageWrite > liveKernel
                        && !source.contains("exactBrickMasks.contains(where:"),
                "native G6 handoff/coverage contract is not an explicit retained snapshot");
    }

    private static void testPublicationOrdering() {
        GiLiveEpoch first = epoch(7L, 6L);
        GiLiveEpoch next = epoch(8L, 7L);
        GiLiveGpuResources.requirePublicationTransition(null, 0L, first, 100L);
        GiLiveGpuResources.requirePublicationTransition(first, 100L, first, 100L);
        GiLiveGpuResources.requirePublicationTransition(first, 100L, next, 101L);
        expectIllegalArgument(
                () -> GiLiveGpuResources.requirePublicationTransition(first, 100L, first, 101L),
                "source tick changed without a new field generation"
        );
        expectIllegalArgument(
                () -> GiLiveGpuResources.requirePublicationTransition(next, 101L, first, 102L),
                "field-generation rollback was accepted"
        );
        expectIllegalArgument(
                () -> GiLiveGpuResources.requirePublicationTransition(first, 100L, next, 99L),
                "same-world source-tick rollback was accepted"
        );
    }

    private static void testBridgeDestinationBounds() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tiny = arena.allocate(Integer.BYTES, Integer.BYTES);
            expectIllegalArgument(
                    () -> MetalNativeBridge.metallum_gi_live_layout_v1(tiny, 160L),
                    "layout bridge accepted a byte count larger than its segment"
            );
            expectIllegalArgument(
                    () -> MetalNativeBridge.metallum_gi_live_get_stats_v1(
                            MemorySegment.NULL, tiny, 216L
                    ),
                    "stats bridge accepted a byte count larger than its segment"
            );
        }
    }

    private static MemorySegment validStatsPacket(final Arena arena) {
        MemorySegment packet = arena.allocate(GiLiveLayout.STATS_BYTES, Long.BYTES);
        packet.set(LE_INT, GiLiveLayout.STATS_READY_MASK_OFFSET,
                GiLiveLayout.READY_MASK_ALL);
        packet.set(LE_INT, GiLiveLayout.STATS_SHADER_LIBRARY_MODE_OFFSET, 1);
        packet.set(LE_INT, GiLiveLayout.STATS_RECEIVER_MASK_STATE_OFFSET,
                GiLiveLayout.READY_MASK_ALL
                        | (GiLiveLayout.READY_MASK_ALL
                        << GiLiveLayout.STATS_LAST_BIND_VISIBLE_MASK_SHIFT));
        packet.set(LE_LONG, GiLiveLayout.STATS_WORLD_GENERATION_OFFSET, 1L);
        packet.set(LE_LONG, GiLiveLayout.STATS_CLIPMAP_GENERATION_OFFSET, 2L);
        packet.set(LE_LONG, GiLiveLayout.STATS_CONTENT_GENERATION_OFFSET, 3L);
        packet.set(LE_LONG, GiLiveLayout.STATS_STATIC_SOURCE_EPOCH_OFFSET, 4L);
        packet.set(LE_LONG, GiLiveLayout.STATS_DYNAMIC_SOURCE_EPOCH_OFFSET, 5L);
        packet.set(LE_LONG, GiLiveLayout.STATS_ENVIRONMENT_EPOCH_OFFSET, 6L);
        packet.set(LE_LONG, GiLiveLayout.STATS_FIELD_GENERATION_OFFSET, 7L);
        packet.set(LE_LONG, GiLiveLayout.STATS_SOURCE_TICK_OFFSET, 8L);
        packet.set(LE_LONG, GiLiveLayout.STATS_RESIDENT_BYTES_OFFSET, 100L);
        packet.set(LE_LONG, GiLiveLayout.STATS_STAGING_BYTES_OFFSET, 200L);
        packet.set(LE_LONG, GiLiveLayout.STATS_ALLOCATED_BYTES_OFFSET, 65_836L);
        packet.set(LE_LONG, GiLiveLayout.STATS_CASCADE_BUILDS_0_OFFSET, 1L);
        packet.set(LE_LONG, GiLiveLayout.STATS_CASCADE_BUILDS_1_OFFSET, 2L);
        packet.set(LE_LONG, GiLiveLayout.STATS_CASCADE_BUILDS_2_OFFSET, 3L);
        packet.set(LE_LONG, GiLiveLayout.STATS_TRANSPORT_DISPATCHES_OFFSET, 6L);
        packet.set(LE_LONG, GiLiveLayout.STATS_INVALIDATIONS_OFFSET, 6L);
        packet.set(LE_LONG, GiLiveLayout.STATS_BIND_COUNT_OFFSET, 5L);
        packet.set(LE_LONG, GiLiveLayout.STATS_ZERO_BINDINGS_OFFSET, 2L);
        packet.set(LE_LONG, GiLiveLayout.STATS_FIELD_BINDINGS_OFFSET, 3L);
        packet.set(LE_INT, GiLiveLayout.STATS_RESET_WORLD_OFFSET, 1);
        packet.set(LE_INT, GiLiveLayout.STATS_RESET_TELEPORT_OFFSET, 1);
        packet.set(LE_INT, GiLiveLayout.STATS_RESET_SCROLL_OFFSET, 1);
        packet.set(LE_INT, GiLiveLayout.STATS_RESET_SOURCE_OFFSET, 1);
        packet.set(LE_INT, GiLiveLayout.STATS_RESET_EXPLICIT_OFFSET, 1);
        packet.set(LE_INT, GiLiveLayout.STATS_RESET_DEVICE_OFFSET, 1);
        return packet;
    }

    private static GiLiveEpoch epoch(final long version, final long palette) {
        return new GiLiveEpoch(
                version, "test:g6", 1L, 2L, 3L, 4L, palette, 8L,
                9L, 10L, 11L,
                new GiLiveEpoch.Origin(-64, -32, -16),
                new GiLiveEpoch.Origin(-128, -64, -32),
                new GiLiveEpoch.Origin(-256, -128, -64)
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

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
