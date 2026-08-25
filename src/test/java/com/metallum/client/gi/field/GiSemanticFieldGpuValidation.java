package com.metallum.client.gi.field;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual source/bundled Metal validation for the structurally-off G2 semantic field. */
public final class GiSemanticFieldGpuValidation {
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);

    private GiSemanticFieldGpuValidation() {
    }

    public static void main(final String[] args) throws Exception {
        int expectedMode = args.length == 1 && "bundled".equals(args[0]) ? 1 : 2;
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        require(!MetalNativeBridge.isNullHandle(device), "Metal device is unavailable");
        MemorySegment layer = MemorySegment.NULL;
        MTLCommandQueue queue = null;
        try {
            layer = MetalNativeBridge.metallum_create_metal_layer(device, 1.0);
            require(!MetalNativeBridge.isNullHandle(layer), "Metal layer creation failed");
            queue = MTLCommandQueue.create(device, layer);
            validateField(device, queue, expectedMode);
            validateReleaseWhileInFlight(device, queue);
            System.out.println("G2 Java/FFM/private-3D semantic Metal validation passed ("
                    + (expectedMode == 1 ? "bundled" : "source") + ")");
        } finally {
            if (queue != null) queue.close();
            if (!MetalNativeBridge.isNullHandle(layer)) MetalNativeBridge.metallum_release_object(layer);
            MetalNativeBridge.metallum_release_device_caches(device);
            MetalNativeBridge.metallum_release_object(device);
        }
    }

    private static void validateField(
            final MemorySegment device, final MTLCommandQueue queue, final int expectedMode
    ) throws Exception {
        try (GiSemanticFieldGpuResources resources = GiSemanticFieldGpuResources.create(
                device, queue.nativeHandle(), 101L,
                MetalNativeBridge::metallum_gi_semantic_release_context_v1)) {
            require(resources != null, "Native G2 semantic context creation failed");
            GiSemanticFieldGpuResources.Stats initial = resources.stats();
            require(!initial.ready() && !initial.buildInFlight()
                            && initial.worldGeneration() == 101L
                            && initial.clipmapGeneration() == 0L
                            && initial.paletteGeneration() == 0L
                            && initial.contentGeneration() == 0L
                            && initial.shaderLibraryMode() == expectedMode,
                    "G2 initial lifecycle or shader-library mode differs");
            require(initial.persistentBytes() > 0L
                            && initial.persistentBytes() < GiSemanticFieldGpuResources.MEMORY_BUDGET_BYTES,
                    "Actual private G2 textures exceed the 24 MiB budget");
            System.out.printf(Locale.ROOT,
                    "G2 private textures: actual=%d budget=%d bytes%n",
                    initial.persistentBytes(), GiSemanticFieldGpuResources.MEMORY_BUDGET_BYTES);

            GiSemanticGpuSnapshot first = patternedSnapshot(101L, 1L, 1L, 1L);
            require(resources.uploadOnce(first) == GiSemanticFieldGpuResources.STATUS_OK,
                    "G2 private semantic upload was rejected");
            require(resources.awaitReady(10_000L), "G2 private semantic upload did not complete");
            GiSemanticFieldGpuResources.Stats ready = resources.stats();
            require(ready.ready() && !ready.buildInFlight()
                            && ready.clipmapGeneration() == 1L
                            && ready.paletteGeneration() == 1L
                            && ready.contentGeneration() == 1L
                            && ready.uploadCount() == 1L
                            && ready.mipDispatchCount() == 15L
                            && Arrays.equals(ready.sourceDigest(), first.sourceDigest()),
                    "G2 upload/mip completion provenance differs");

            require(invalidCaptureStatus(resources.nativeContextForValidation(), 0, 1, 16)
                            == GiSemanticFieldGpuResources.STATUS_INVALID,
                    "G2 invalid capture request was not rejected");
            GiSemanticFieldGpuResources.Capture mip1 = resources.captureSliceOnce(0, 1, 0);
            require(mip1 != null && mip1.info().edge() == 16 && mip1.info().mip() == 1,
                    "G2 valid capture was consumed by the preceding invalid request");
            validateMip1(mip1, first);
            require(resources.captureSliceOnce(0, 1, 0) == null,
                    "G2 diagnostic capture was not one-shot");

            require(resources.uploadOnce(first) == GiSemanticFieldGpuResources.STATUS_STALE,
                    "G2 duplicate content generation survived stale rejection");
            require(resources.reset(101L, 2L, 2L, 2L) == GiSemanticFieldGpuResources.STATUS_OK,
                    "G2 clipmap/palette/content reset failed");
            require(resources.uploadOnce(patternedSnapshot(100L, 2L, 2L, 3L))
                            == GiSemanticFieldGpuResources.STATUS_STALE,
                    "G2 stale world generation survived reset");
            require(resources.uploadOnce(patternedSnapshot(101L, 1L, 2L, 3L))
                            == GiSemanticFieldGpuResources.STATUS_STALE,
                    "G2 stale clipmap generation survived reset");
            require(resources.uploadOnce(patternedSnapshot(101L, 2L, 1L, 3L))
                            == GiSemanticFieldGpuResources.STATUS_STALE,
                    "G2 stale palette generation survived reset");

            GiSemanticGpuSnapshot replacement = patternedSnapshot(101L, 2L, 2L, 3L);
            require(resources.uploadOnce(replacement) == GiSemanticFieldGpuResources.STATUS_OK
                            && resources.awaitReady(10_000L),
                    "G2 replacement upload failed");
            GiSemanticFieldGpuResources.Capture mip2 = resources.captureSliceOnce(0, 2, 0);
            require(mip2 != null && mip2.info().edge() == 8,
                    "G2 partial-coverage mip2 capture failed");
            validatePartialCoverageMip2(mip2);

            require(resources.reset(101L, 2L, 2L, 4L) == GiSemanticFieldGpuResources.STATUS_OK,
                    "G2 terminal-mip content reset failed");
            require(resources.uploadOnce(patternedSnapshot(101L, 2L, 2L, 5L))
                            == GiSemanticFieldGpuResources.STATUS_OK
                            && resources.awaitReady(10_000L),
                    "G2 terminal-mip replacement upload failed");
            require(invalidCaptureStatus(resources.nativeContextForValidation(), 0, 5, 1)
                            == GiSemanticFieldGpuResources.STATUS_INVALID,
                    "G2 invalid 1^3 slice was not rejected");
            GiSemanticFieldGpuResources.Capture mip5 = resources.captureSliceOnce(0, 5, 0);
            require(mip5 != null && mip5.info().edge() == 1
                            && Byte.toUnsignedInt(mip5.stateRgbaUint8()[1])
                            == GiSemanticFieldGpuResources.VALIDITY_FALLBACK
                            && Short.toUnsignedInt(mip5.paletteIdsUint16()[0])
                            == GiSemanticFieldGpuResources.PALETTE_FALLBACK
                            && isConservativeFallbackMaterial(mip5.materialRgbaUnorm16(), 0)
                            && allZero(mip5.emissionRgbaFloat16())
                            && allZero(mip5.faceWeights0RgbaUnorm8())
                            && allZero(mip5.faceWeights1RgUnorm8()),
                    "G2 terminal 1^3 mip lost recursive fallback sanitization");

            GiSemanticFieldGpuResources.Stats complete = resources.stats();
            require(complete.uploadCount() == 3L && complete.mipDispatchCount() == 45L
                            && complete.captureCount() == 3L && complete.resetCount() == 2L
                            && complete.rejectedCount() >= 4L
                            && complete.peakUploadStagingBytes() > 0L
                            && complete.peakCaptureReadbackBytes() > 0L
                            && complete.conservativeEndToEndBytes()
                            <= GiSemanticFieldGpuResources.MEMORY_BUDGET_BYTES,
                    "G2 bounded allocation/lifecycle accounting differs");
            System.out.printf(Locale.ROOT,
                    "G2 peak account: persistent=%d staging=%d capture=%d native=%d end-to-end=%d bytes%n",
                    complete.persistentBytes(), complete.peakUploadStagingBytes(),
                    complete.peakCaptureReadbackBytes(), complete.peakAccountedBytes(),
                    complete.conservativeEndToEndBytes());
            validateWrongThread(resources);
        }
    }

    private static void validatePartialCoverageMip2(final GiSemanticFieldGpuResources.Capture capture) {
        int cell = 2;
        int base = cell * 4;
        float intensity = Float.float16ToFloat(capture.emissionRgbaFloat16()[base + 3]);
        require(Byte.toUnsignedInt(capture.stateRgbaUint8()[base + 1])
                        == GiSemanticFieldGpuResources.VALIDITY_CONTENT
                        && Math.abs(Byte.toUnsignedInt(capture.knownCoverageUnorm8()[cell]) / 255.0F
                        - 0.9375F) < 0.01F
                        && Math.abs(Byte.toUnsignedInt(capture.faceWeights0RgbaUnorm8()[base]) - 17) <= 1
                        && Math.abs(intensity - (1.0F / 15.0F)) < 0.005F,
                "G2 recursive partial coverage was squared instead of carrying conditional means");
    }

    private static void validateMip1(
            final GiSemanticFieldGpuResources.Capture capture, final GiSemanticGpuSnapshot source
    ) {
        require(capture.info().worldGeneration() == source.worldGeneration()
                        && capture.info().clipmapGeneration() == source.clipmapGeneration()
                        && capture.info().paletteGeneration() == source.paletteGeneration()
                        && capture.info().contentGeneration() == source.contentGeneration()
                        && capture.info().axis() == 2 && capture.info().slice() == 0
                        && Arrays.equals(capture.info().sourceDigest(), source.sourceDigest()),
                "G2 raw slice provenance differs");
        byte[] state = capture.stateRgbaUint8();
        short[] palette = capture.paletteIdsUint16();
        short[] material = capture.materialRgbaUnorm16();
        short[] emission = capture.emissionRgbaFloat16();
        byte[] faces0 = capture.faceWeights0RgbaUnorm8();
        byte[] faces1 = capture.faceWeights1RgUnorm8();

        require(Byte.toUnsignedInt(state[1]) == GiSemanticFieldGpuResources.VALIDITY_FALLBACK
                        && Byte.toUnsignedInt(state[0]) == GiSemanticFieldGpuResources.MEDIUM_MASK_ALL
                        && Short.toUnsignedInt(palette[0]) == GiSemanticFieldGpuResources.PALETTE_FALLBACK
                        && isConservativeFallbackMaterial(material, 0)
                        && emission[0] == 0 && emission[1] == 0
                        && emission[2] == 0 && emission[3] == 0
                        && faces0[0] == 0 && faces0[1] == 0 && faces0[2] == 0 && faces0[3] == 0
                        && faces1[0] == 0 && faces1[1] == 0,
                "G2 fallback parent did not recursively sanitize material/emission/faces");

        int mixed = 1;
        int mixedBase = mixed * 4;
        require(Byte.toUnsignedInt(state[mixedBase + 1]) == GiSemanticFieldGpuResources.VALIDITY_CONTENT
                        && (Byte.toUnsignedInt(state[mixedBase + 2])
                        & com.metallum.client.gi.semantic.GiSemanticProvenance.PALETTE_FALLBACK) != 0
                        && Short.toUnsignedInt(palette[mixed]) == GiSemanticFieldGpuResources.PALETTE_FALLBACK
                        && Math.abs(Float.float16ToFloat(emission[mixedBase]) - 0.75F) < 0.005F
                        && Math.abs(Float.float16ToFloat(emission[mixedBase + 3]) - 0.5F) < 0.005F
                        && Byte.toUnsignedInt(faces0[mixedBase]) == 32
                        && Byte.toUnsignedInt(faces1[mixed * 2 + 1]) == 192,
                "G2 mixed-palette content reduction differs");

        int empty = 2;
        int unknown = 3;
        require(Byte.toUnsignedInt(state[empty * 4 + 1]) == GiSemanticFieldGpuResources.VALIDITY_EMPTY
                        && Short.toUnsignedInt(palette[empty]) == GiSemanticFieldGpuResources.PALETTE_AIR
                        && Byte.toUnsignedInt(state[unknown * 4 + 1]) == GiSemanticFieldGpuResources.VALIDITY_UNKNOWN
                        && Short.toUnsignedInt(palette[unknown]) == GiSemanticFieldGpuResources.PALETTE_UNKNOWN
                        && Byte.toUnsignedInt(capture.knownCoverageUnorm8()[unknown]) == 0,
                "G2 empty/unknown mip states aliased");
    }

    private static int invalidCaptureStatus(
            final MemorySegment context, final int cascade, final int mip, final int slice
    ) {
        int edge = GiSemanticGpuSnapshot.EDGE >> mip;
        int cells = edge * edge;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment request = arena.allocate(GiSemanticFieldGpuResources.CAPTURE_REQUEST_BYTES, 4L);
            request.fill((byte) 0);
            request.set(LE_INT, 0L, GiSemanticFieldGpuResources.ABI_VERSION);
            request.set(LE_INT, 4L, GiSemanticFieldGpuResources.CAPTURE_REQUEST_BYTES);
            request.set(LE_INT, 8L, cascade);
            request.set(LE_INT, 12L, mip);
            request.set(LE_INT, 16L, slice);
            return MetalNativeBridge.metallum_gi_semantic_capture_slice_once_v1(
                    context, request,
                    arena.allocate(GiSemanticFieldGpuResources.CAPTURE_INFO_BYTES, 8L),
                    arena.allocate((long) cells * 8L, 2L),
                    arena.allocate((long) cells * 8L, 2L),
                    arena.allocate((long) cells * 4L),
                    arena.allocate((long) cells * 2L),
                    arena.allocate((long) cells * 4L),
                    arena.allocate((long) cells * 2L, 2L),
                    arena.allocate(cells));
        }
    }

    private static void validateWrongThread(final GiSemanticFieldGpuResources resources) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger nativeStatus = new AtomicInteger();
        MemorySegment nativeContext = resources.nativeContextForValidation();
        Thread wrong = new Thread(() -> {
            nativeStatus.set(MetalNativeBridge.metallum_gi_semantic_await_ready_v1(nativeContext, 1L));
            try {
                resources.stats();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "G2-wrong-render-thread");
        wrong.start();
        wrong.join();
        require(nativeStatus.get() == GiSemanticFieldGpuResources.STATUS_WRONG_THREAD
                        && failure.get() instanceof IllegalStateException,
                "G2 native/Java owners did not reject wrong-thread Metal access");
    }

    private static void validateReleaseWhileInFlight(
            final MemorySegment device, final MTLCommandQueue queue
    ) {
        GiSemanticFieldGpuResources resources = GiSemanticFieldGpuResources.create(
                device, queue.nativeHandle(), 201L,
                MetalNativeBridge::metallum_gi_semantic_release_context_v1);
        require(resources != null, "G2 lifetime-test context creation failed");
        require(resources.uploadOnce(patternedSnapshot(201L, 1L, 1L, 1L))
                        == GiSemanticFieldGpuResources.STATUS_OK,
                "G2 lifetime-test upload failed");
        resources.close();
        MTLCommandBuffer fence = queue.makeCommandBuffer("G2 release lifetime fence");
        try {
            fence.commit();
            require(fence.waitUntilCompleted(10_000L),
                    "G2 release lifetime fence did not complete");
        } finally {
            fence.close();
        }
    }

    private static GiSemanticGpuSnapshot patternedSnapshot(
            final long world, final long clipmap, final long paletteGeneration, final long content
    ) {
        int cells = GiSemanticGpuSnapshot.CELL_COUNT;
        int[] origins = {-32, -32, -32, -64, -64, -64, -128, -128, -128};
        byte[] digest = new byte[GiSemanticGpuSnapshot.DIGEST_BYTES];
        for (int index = 0; index < digest.length; index++) digest[index] = (byte) (index * 7 + content);
        short[] material = new short[cells * 4];
        short[] emission = new short[cells * 4];
        byte[] faces0 = new byte[cells * 4];
        byte[] faces1 = new byte[cells * 2];
        byte[] state = new byte[cells * 4];
        short[] palette = new short[cells];
        byte[] coverage = new byte[cells];
        for (int cell = 0; cell < cells; cell++) {
            state[cell * 4 + 1] = (byte) GiSemanticFieldGpuResources.VALIDITY_EMPTY;
            state[cell * 4 + 2] = (byte) com.metallum.client.gi.semantic.GiSemanticProvenance.AUTHORITATIVE_EMPTY;
            palette[cell] = (short) GiSemanticFieldGpuResources.PALETTE_AIR;
            coverage[cell] = (byte) 0xff;
        }

        int ordinal = 0;
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    int cell = cell(x, y, z);
                    if (ordinal < 6) {
                        setContent(material, emission, faces0, faces1, state, palette, coverage,
                                cell, 5, 1 << (ordinal % 4));
                    } else if (ordinal == 6) {
                        setFallback(material, emission, faces0, faces1, state, palette, coverage, cell);
                    } else {
                        setUnknown(material, emission, faces0, faces1, state, palette, coverage, cell);
                    }
                    ordinal++;
                }
            }
        }
        ordinal = 0;
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 2; x < 4; x++) {
                    setContent(material, emission, faces0, faces1, state, palette, coverage,
                            cell(x, y, z), ordinal++ == 7 ? 6 : 5, 1);
                }
            }
        }
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 6; x < 8; x++) {
                    setUnknown(material, emission, faces0, faces1, state, palette, coverage, cell(x, y, z));
                }
            }
        }
        ordinal = 0;
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 8; x < 10; x++) {
                    int cell = cell(x, y, z);
                    if (ordinal++ < 4) {
                        setContent(material, emission, faces0, faces1, state, palette, coverage,
                                cell, 5, 1);
                        emission[cell * 4 + 3] = Float.floatToFloat16(1.0F);
                        Arrays.fill(faces0, cell * 4, cell * 4 + 4, (byte) 0xff);
                        faces1[cell * 2] = (byte) 0xff;
                        faces1[cell * 2 + 1] = (byte) 0xff;
                    } else {
                        setUnknown(material, emission, faces0, faces1, state, palette, coverage, cell);
                    }
                }
            }
        }
        return new GiSemanticGpuSnapshot(world, clipmap, paletteGeneration, content, origins, digest,
                material, emission, faces0, faces1, state, palette, coverage);
    }

    private static void setContent(
            final short[] material, final short[] emission, final byte[] faces0, final byte[] faces1,
            final byte[] state, final short[] palette, final byte[] coverage,
            final int cell, final int paletteId, final int medium
    ) {
        int base = cell * 4;
        material[base] = (short) 0x8000;
        material[base + 1] = (short) 0x4000;
        material[base + 2] = (short) 0x2000;
        material[base + 3] = (short) 0xffff;
        emission[base] = Float.floatToFloat16(0.75F);
        emission[base + 1] = Float.floatToFloat16(0.5F);
        emission[base + 2] = Float.floatToFloat16(0.25F);
        emission[base + 3] = Float.floatToFloat16(0.5F);
        faces0[base] = 32;
        faces0[base + 1] = 64;
        faces0[base + 2] = 96;
        faces0[base + 3] = (byte) 128;
        faces1[cell * 2] = (byte) 160;
        faces1[cell * 2 + 1] = (byte) 192;
        state[base] = (byte) medium;
        state[base + 1] = (byte) GiSemanticFieldGpuResources.VALIDITY_CONTENT;
        state[base + 2] = (byte) com.metallum.client.gi.semantic.GiSemanticProvenance.ACCEPTED_QUAD;
        palette[cell] = (short) paletteId;
        coverage[cell] = (byte) 0xff;
    }

    private static void setFallback(
            final short[] material, final short[] emission, final byte[] faces0, final byte[] faces1,
            final byte[] state, final short[] palette, final byte[] coverage, final int cell
    ) {
        int base = cell * 4;
        material[base + 3] = (short) 0xffff;
        Arrays.fill(emission, base, base + 4, Float.floatToFloat16(1.0F));
        Arrays.fill(faces0, base, base + 4, (byte) 0xff);
        faces1[cell * 2] = (byte) 0xff;
        faces1[cell * 2 + 1] = (byte) 0xff;
        state[base] = 16;
        state[base + 1] = (byte) GiSemanticFieldGpuResources.VALIDITY_FALLBACK;
        state[base + 2] = (byte) com.metallum.client.gi.semantic.GiSemanticProvenance.MODDED_FALLBACK;
        palette[cell] = (short) GiSemanticFieldGpuResources.PALETTE_FALLBACK;
        coverage[cell] = (byte) 0xff;
    }

    private static void setUnknown(
            final short[] material, final short[] emission, final byte[] faces0, final byte[] faces1,
            final byte[] state, final short[] palette, final byte[] coverage, final int cell
    ) {
        int base = cell * 4;
        material[base] = (short) 0xffff;
        material[base + 3] = (short) 0xffff;
        Arrays.fill(emission, base, base + 4, Float.floatToFloat16(1.0F));
        Arrays.fill(faces0, base, base + 4, (byte) 0xff);
        faces1[cell * 2] = (byte) 0xff;
        faces1[cell * 2 + 1] = (byte) 0xff;
        state[base] = 16;
        state[base + 1] = (byte) GiSemanticFieldGpuResources.VALIDITY_UNKNOWN;
        state[base + 2] = (byte) com.metallum.client.gi.semantic.GiSemanticProvenance.BIOME_TINTED;
        palette[cell] = (short) GiSemanticFieldGpuResources.PALETTE_UNKNOWN;
        coverage[cell] = 0;
    }

    private static int cell(final int x, final int y, final int z) {
        return x + y * GiSemanticGpuSnapshot.EDGE
                + z * GiSemanticGpuSnapshot.EDGE * GiSemanticGpuSnapshot.EDGE;
    }

    private static boolean allZero(final short[] values) {
        for (short value : values) if (value != 0) return false;
        return true;
    }

    private static boolean allZero(final byte[] values) {
        for (byte value : values) if (value != 0) return false;
        return true;
    }

    private static boolean isConservativeFallbackMaterial(final short[] material, final int cell) {
        int base = cell * 4;
        return material[base] == 0 && material[base + 1] == 0 && material[base + 2] == 0
                && Short.toUnsignedInt(material[base + 3]) == 0xffff;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
