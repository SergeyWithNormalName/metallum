package com.metallum.client.gi.field;

import com.metallum.client.gi.semantic.GiSemanticCoordinates;
import com.metallum.client.gi.semantic.GiSemanticFieldAssembler;
import com.metallum.client.gi.semantic.GiSemanticFieldSnapshot;
import com.metallum.client.gi.semantic.GiSemanticMedium;
import com.metallum.client.gi.semantic.GiSemanticPalette;
import com.metallum.client.gi.semantic.GiSemanticProvenance;
import com.metallum.client.gi.semantic.GiSemanticSectionSnapshot;
import com.metallum.client.gi.semantic.GiSemanticSectionTask;
import com.metallum.client.gi.semantic.GiSemanticValidity;
import com.metallum.client.gi.semantic.GiSemanticWorldToken;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;

/** Deterministic CPU truth to G2 GPU-plane mapping and fail-closed validity tests. */
public final class GiSemanticGpuEncoderTests {
    private GiSemanticGpuEncoderTests() {
    }

    public static void main(final String[] args) {
        GiSemanticWorldToken world = new GiSemanticWorldToken(11L, 12L, 13L, "test:gpu-encoder");
        GiSemanticPalette palette = GiSemanticPalette.build(14L, 12L, 13L, List.of());
        GiSemanticFieldAssembler assembler = new GiSemanticFieldAssembler(world, palette, 0, 0, 0);
        GiSemanticSectionSnapshot section = adversarialSection();
        GiSemanticSectionTask task = new GiSemanticSectionTask(
                world, assembler.clipmapGeneration(), palette.generation(),
                GiSemanticCoordinates.sectionKey(0, 0, 0), 1L, 2L);
        require(assembler.apply(task, section) == GiSemanticFieldAssembler.ApplyResult.ACCEPTED,
                "G2 encoder fixture section was not accepted");

        GiSemanticFieldSnapshot cpu = assembler.snapshot();
        GiSemanticGpuSnapshot gpu = GiSemanticGpuEncoder.encode(cpu);
        require(gpu.worldGeneration() == world.worldGeneration()
                        && gpu.clipmapGeneration() == cpu.clipmapGeneration()
                        && gpu.paletteGeneration() == palette.generation()
                        && gpu.contentGeneration() == cpu.contentGeneration()
                        && Arrays.equals(gpu.origins(), cpu.origins()),
                "G2 encoder lost generation/origin provenance");

        int unknown = cpu.cellIndexForWorld(0, 0, 0, 0);
        int fallback = cpu.cellIndexForWorld(0, 2, 0, 0);
        int empty = cpu.cellIndexForWorld(0, 4, 0, 0);
        int content = cpu.cellIndexForWorld(0, 6, 0, 0);
        require(unknown >= 0 && fallback >= 0 && empty >= 0 && content >= 0,
                "G2 encoder fixture cells are outside the near cascade");

        Planes planes = readPlanes(gpu);
        assertSanitized(planes, unknown, GiSemanticFieldGpuResources.PALETTE_UNKNOWN, 0);
        assertSanitized(planes, fallback, GiSemanticFieldGpuResources.PALETTE_FALLBACK, 0xff);
        assertSanitizedEmpty(planes, empty);
        assertContent(planes, content);
        require(Arrays.equals(gpu.sourceDigest(), java.util.HexFormat.of().parseHex(cpu.digest())),
                "G2 encoder lost SHA-256 source provenance");
        System.out.println("G2 deterministic CPU-to-GPU semantic encoder tests passed");
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
        short[] palette = new short[cells];
        Arrays.fill(albedo, (short) 0x7fff);
        Arrays.fill(emission, (short) 0xffff);
        Arrays.fill(occupancy, (byte) 17);
        Arrays.fill(medium, (byte) GiSemanticMedium.UNKNOWN_CONSERVATIVE.mask());
        Arrays.fill(provenance, (byte) GiSemanticProvenance.MODDED_FALLBACK);
        Arrays.fill(faces, (byte) 127);
        Arrays.fill(coverage, (byte) 0xff);
        Arrays.fill(palette, (short) 123);

        int unknown = GiSemanticSectionSnapshot.cascadeCellIndex(0, 0, 0, 0);
        int fallback = GiSemanticSectionSnapshot.cascadeCellIndex(0, 1, 0, 0);
        int empty = GiSemanticSectionSnapshot.cascadeCellIndex(0, 2, 0, 0);
        int content = GiSemanticSectionSnapshot.cascadeCellIndex(0, 3, 0, 0);
        validity[unknown] = (byte) GiSemanticValidity.UNKNOWN.abiId();
        validity[fallback] = (byte) GiSemanticValidity.KNOWN_FALLBACK.abiId();
        validity[empty] = (byte) GiSemanticValidity.KNOWN_EMPTY.abiId();
        validity[content] = (byte) GiSemanticValidity.KNOWN_CONTENT.abiId();
        provenance[content] = (byte) GiSemanticProvenance.ACCEPTED_QUAD;
        palette[content] = 77;
        int contentRgb = content * 3;
        albedo[contentRgb] = (short) 0x4000;
        albedo[contentRgb + 1] = (short) 0x2000;
        albedo[contentRgb + 2] = (short) 0x1000;
        int contentEmission = content * 4;
        emission[contentEmission] = (short) 0xc000;
        emission[contentEmission + 1] = (short) 0x8000;
        emission[contentEmission + 2] = (short) 0x4000;
        emission[contentEmission + 3] = (short) 0x6000;
        return new GiSemanticSectionSnapshot(
                albedo, emission, occupancy, medium, validity, provenance,
                faces, coverage, palette, 1, 1);
    }

    private static void assertSanitized(
            final Planes planes, final int cell, final int expectedPalette, final int expectedCoverage
    ) {
        int base = cell * 4;
        require(planes.material[base] == 0 && planes.material[base + 1] == 0
                        && planes.material[base + 2] == 0
                        && Short.toUnsignedInt(planes.material[base + 3]) == 0xffff,
                "UNKNOWN/FALLBACK did not encode conservative black rho/opaque support");
        require(planes.emission[base] == 0 && planes.emission[base + 1] == 0
                        && planes.emission[base + 2] == 0 && planes.emission[base + 3] == 0,
                "UNKNOWN/FALLBACK retained adversarial emission");
        require(planes.faces0[base] == 0 && planes.faces1[cell * 2] == 0,
                "UNKNOWN/FALLBACK retained adversarial face weights");
        require(Short.toUnsignedInt(planes.palette[cell]) == expectedPalette
                        && Byte.toUnsignedInt(planes.coverage[cell]) == expectedCoverage,
                "UNKNOWN/FALLBACK reserved palette or coverage differs");
    }

    private static void assertSanitizedEmpty(final Planes planes, final int cell) {
        int base = cell * 4;
        require(planes.material[base] == 0 && planes.material[base + 3] == 0
                        && planes.emission[base] == 0 && planes.faces0[base] == 0
                        && Short.toUnsignedInt(planes.palette[cell]) == GiSemanticFieldGpuResources.PALETTE_AIR,
                "KNOWN_EMPTY did not encode authoritative zero energy/occupancy");
    }

    private static void assertContent(final Planes planes, final int cell) {
        int base = cell * 4;
        require(Short.toUnsignedInt(planes.material[base]) == 0x4000
                        && Short.toUnsignedInt(planes.material[base + 3]) == 17 * 257
                        && Math.abs(Float.float16ToFloat(planes.emission[base]) - 0.75F) < 0.002F
                        && Math.abs(Float.float16ToFloat(planes.emission[base + 3]) - 0.375F) < 0.002F
                        && Byte.toUnsignedInt(planes.faces0[base]) == 127
                        && Byte.toUnsignedInt(planes.faces1[cell * 2]) == 127
                        && Short.toUnsignedInt(planes.palette[cell]) == 77,
                "KNOWN_CONTENT exact semantic planes differ");
    }

    private static Planes readPlanes(final GiSemanticGpuSnapshot snapshot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment material = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 8L, 2L);
            MemorySegment emission = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 8L, 2L);
            MemorySegment faces0 = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 4L);
            MemorySegment faces1 = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 2L);
            MemorySegment state = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 4L);
            MemorySegment palette = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 2L, 2L);
            MemorySegment coverage = arena.allocate(GiSemanticGpuSnapshot.CELL_COUNT);
            snapshot.copyPlanesTo(material, emission, faces0, faces1, state, palette, coverage);
            return new Planes(shortArray(material), shortArray(emission), byteArray(faces0),
                    byteArray(faces1), byteArray(state), shortArray(palette), byteArray(coverage));
        }
    }

    private static short[] shortArray(final MemorySegment source) {
        short[] output = new short[Math.toIntExact(source.byteSize() / Short.BYTES)];
        MemorySegment.copy(source, ValueLayout.JAVA_SHORT, 0L,
                MemorySegment.ofArray(output), ValueLayout.JAVA_SHORT, 0L, output.length);
        return output;
    }

    private static byte[] byteArray(final MemorySegment source) {
        byte[] output = new byte[Math.toIntExact(source.byteSize())];
        MemorySegment.copy(source, 0L, MemorySegment.ofArray(output), 0L, output.length);
        return output;
    }

    private record Planes(short[] material, short[] emission, byte[] faces0, byte[] faces1,
                          byte[] state, short[] palette, byte[] coverage) {
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
