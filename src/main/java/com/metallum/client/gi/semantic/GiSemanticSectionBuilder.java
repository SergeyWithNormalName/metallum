package com.metallum.client.gi.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Worker-local deterministic reducer combining cloned state with quads actually emitted by Sodium. */
public final class GiSemanticSectionBuilder {
    private static final Comparator<GiSemanticQuadObservation> OBSERVATION_ORDER = Comparator
            .comparingInt(GiSemanticQuadObservation::localIndex)
            .thenComparingInt(GiSemanticQuadObservation::faceBit)
            .thenComparing(GiSemanticQuadObservation::canonicalMaterialKey)
            .thenComparingInt(value -> Short.toUnsignedInt(value.albedoRed()))
            .thenComparingInt(value -> Short.toUnsignedInt(value.albedoGreen()))
            .thenComparingInt(value -> Short.toUnsignedInt(value.albedoBlue()))
            .thenComparingInt(value -> Short.toUnsignedInt(value.emissionRed()))
            .thenComparingInt(value -> Short.toUnsignedInt(value.emissionGreen()))
            .thenComparingInt(value -> Short.toUnsignedInt(value.emissionBlue()))
            .thenComparingInt(value -> Short.toUnsignedInt(value.emissionIntensity()))
            .thenComparingInt(GiSemanticQuadObservation::areaWeight)
            .thenComparingInt(GiSemanticQuadObservation::provenance);

    private final GiSemanticPalette palette;
    private final GiSemanticStateSeed[] states;
    private final List<GiSemanticQuadObservation> observations = new ArrayList<>();
    private boolean built;

    public GiSemanticSectionBuilder(
            final GiSemanticPalette palette,
            final GiSemanticSectionSeed seed
    ) {
        this.palette = Objects.requireNonNull(palette, "palette");
        this.states = Objects.requireNonNull(seed, "seed").states();
    }

    public void observe(final GiSemanticQuadObservation observation) {
        if (this.built) {
            throw new IllegalStateException("G2 section builder was already consumed");
        }
        this.observations.add(Objects.requireNonNull(observation, "observation"));
    }

    public GiSemanticSectionSnapshot build() {
        if (this.built) {
            throw new IllegalStateException("G2 section builder was already consumed");
        }
        this.built = true;
        this.observations.sort(OBSERVATION_ORDER);
        Accumulators data = new Accumulators();
        for (GiSemanticStateSeed state : this.states) {
            accumulateState(data, state);
        }
        for (GiSemanticQuadObservation observation : this.observations) {
            accumulateObservation(data, observation);
        }
        return finish(data, this.observations.size());
    }

    private void accumulateState(final Accumulators data, final GiSemanticStateSeed state) {
        int blockX = state.localIndex() & 15;
        int blockZ = state.localIndex() >>> 4 & 15;
        int blockY = state.localIndex() >>> 8 & 15;
        int paletteId = this.palette.idFor(state.canonicalMaterialKey());
        GiSemanticPalette.Entry entry = this.palette.entry(paletteId);
        boolean paletteFallback = paletteId == GiSemanticPalette.FALLBACK_ID
                && !GiSemanticPalette.FALLBACK_KEY.equals(state.canonicalMaterialKey());
        for (int cascade = 0; cascade < 3; cascade++) {
            int divisor = 1 << (cascade + 1);
            int cell = GiSemanticSectionSnapshot.cascadeCellIndex(
                    cascade, blockX / divisor, blockY / divisor, blockZ / divisor
            );
            data.totalBlocks[cell]++;
            int validity = state.validity().abiId();
            if (state.validity() != GiSemanticValidity.UNKNOWN) {
                data.knownBlocks[cell]++;
            }
            if (state.validity() == GiSemanticValidity.KNOWN_FALLBACK || paletteFallback) {
                data.fallback[cell] = true;
            }
            data.content[cell] |= state.validity() == GiSemanticValidity.KNOWN_CONTENT;
            int occupancy = state.occupancyUnorm16();
            data.occupancySum[cell] += occupancy;
            data.seedMediumMask[cell] |= entry.medium().mask();
            data.provenance[cell] |= state.provenance() | entry.provenance()
                    | (paletteFallback ? GiSemanticProvenance.PALETTE_FALLBACK : 0);
            if (occupancy > 0) {
                for (int channel = 0; channel < 3; channel++) {
                    int albedo = switch (channel) {
                        case 0 -> Short.toUnsignedInt(entry.albedoRed());
                        case 1 -> Short.toUnsignedInt(entry.albedoGreen());
                        default -> Short.toUnsignedInt(entry.albedoBlue());
                    };
                    data.seedAlbedo[cell * 3 + channel] += (long) albedo * occupancy;
                }
                data.seedAlbedoWeight[cell] += occupancy;
                int emissionWeight = (int) (((long) occupancy
                        * Short.toUnsignedInt(entry.emissionIntensity()) + 0x7fffL) / 0xffffL);
                if (emissionWeight > 0) {
                    data.seedEmissionWeight[cell] += emissionWeight;
                    data.seedEmissionIntensity[cell] += (long) Short.toUnsignedInt(entry.emissionIntensity())
                            * occupancy;
                    for (int channel = 0; channel < 3; channel++) {
                        int emission = switch (channel) {
                            case 0 -> Short.toUnsignedInt(entry.emissionRed());
                            case 1 -> Short.toUnsignedInt(entry.emissionGreen());
                            default -> Short.toUnsignedInt(entry.emissionBlue());
                        };
                        data.seedEmission[cell * 3 + channel] += (long) emission * emissionWeight;
                    }
                }
                selectMaterial(data.seedMaterialWeights, cell, paletteId, occupancy);
                int mask = state.conservativeFaceMask();
                for (int face = 0; face < 6; face++) {
                    if ((mask & 1 << face) != 0) {
                        data.seedFaces[cell * 6 + face] += occupancy;
                    }
                }
            }
            if (validity < 0) {
                throw new AssertionError("unreachable");
            }
        }
    }

    private void accumulateObservation(final Accumulators data, final GiSemanticQuadObservation observation) {
        int blockX = observation.localIndex() & 15;
        int blockZ = observation.localIndex() >>> 4 & 15;
        int blockY = observation.localIndex() >>> 8 & 15;
        int paletteId = this.palette.idFor(observation.canonicalMaterialKey());
        GiSemanticPalette.Entry entry = this.palette.entry(paletteId);
        boolean paletteFallback = paletteId == GiSemanticPalette.FALLBACK_ID
                && !GiSemanticPalette.FALLBACK_KEY.equals(observation.canonicalMaterialKey());
        for (int cascade = 0; cascade < 3; cascade++) {
            int divisor = 1 << (cascade + 1);
            int cell = GiSemanticSectionSnapshot.cascadeCellIndex(
                    cascade, blockX / divisor, blockY / divisor, blockZ / divisor
            );
            int weight = observation.areaWeight();
            if (paletteFallback) {
                data.fallback[cell] = true;
            }
            data.quadAlbedoWeight[cell] += weight;
            data.quadMediumMask[cell] |= entry.medium().mask();
            data.quadFaces[cell * 6 + GiSemanticPacking.faceIndex(observation.faceBit())] += weight;
            data.provenance[cell] |= observation.provenance()
                    | entry.provenance()
                    | (paletteFallback ? GiSemanticProvenance.PALETTE_FALLBACK : 0);
            for (int channel = 0; channel < 3; channel++) {
                int albedo = switch (channel) {
                    case 0 -> Short.toUnsignedInt(observation.albedoRed());
                    case 1 -> Short.toUnsignedInt(observation.albedoGreen());
                    default -> Short.toUnsignedInt(observation.albedoBlue());
                };
                data.quadAlbedo[cell * 3 + channel] += (long) albedo * weight;
            }
            int intensity = Short.toUnsignedInt(observation.emissionIntensity());
            int emissionWeight = (int) (((long) weight * intensity + 0x7fffL) / 0xffffL);
            if (emissionWeight > 0) {
                data.quadEmissionWeight[cell] += emissionWeight;
                data.quadEmissionIntensity[cell] += (long) intensity * weight;
                data.quadEmissionArea[cell] += weight;
                for (int channel = 0; channel < 3; channel++) {
                    int emission = switch (channel) {
                        case 0 -> Short.toUnsignedInt(observation.emissionRed());
                        case 1 -> Short.toUnsignedInt(observation.emissionGreen());
                        default -> Short.toUnsignedInt(observation.emissionBlue());
                    };
                    data.quadEmission[cell * 3 + channel] += (long) emission * emissionWeight;
                }
            }
            selectMaterial(data.quadMaterialWeights, cell, paletteId, weight);
        }
    }

    private static void selectMaterial(
            final Map<Long, Long> materialWeights, final int cell, final int id, final int weight
    ) {
        long key = (long) cell << 32 | id & 0xffff_ffffL;
        materialWeights.merge(key, (long) weight, Math::addExact);
    }

    private static GiSemanticSectionSnapshot finish(final Accumulators data, final int observedQuads) {
        short[] albedo = new short[GiSemanticSectionSnapshot.CELL_COUNT * 3];
        short[] emission = new short[GiSemanticSectionSnapshot.CELL_COUNT * 4];
        byte[] occupancy = new byte[GiSemanticSectionSnapshot.CELL_COUNT];
        byte[] medium = new byte[GiSemanticSectionSnapshot.CELL_COUNT];
        byte[] validity = new byte[GiSemanticSectionSnapshot.CELL_COUNT];
        byte[] provenance = new byte[GiSemanticSectionSnapshot.CELL_COUNT];
        byte[] faces = new byte[GiSemanticSectionSnapshot.CELL_COUNT * 6];
        byte[] coverage = new byte[GiSemanticSectionSnapshot.CELL_COUNT];
        short[] materialIds = new short[GiSemanticSectionSnapshot.CELL_COUNT];
        int fallbackCells = 0;
        for (int cell = 0; cell < GiSemanticSectionSnapshot.CELL_COUNT; cell++) {
            int total = data.totalBlocks[cell];
            int known = data.knownBlocks[cell];
            coverage[cell] = (byte) ((known * 255 + total / 2) / total);
            if (known != total) {
                validity[cell] = (byte) GiSemanticValidity.UNKNOWN.abiId();
                occupancy[cell] = (byte) 0xff;
                medium[cell] = (byte) GiSemanticMedium.UNKNOWN_CONSERVATIVE.mask();
                provenance[cell] = (byte) data.provenance[cell];
                materialIds[cell] = (short) GiSemanticPalette.UNKNOWN_ID;
                continue;
            }
            if (data.fallback[cell]) {
                validity[cell] = (byte) GiSemanticValidity.KNOWN_FALLBACK.abiId();
                occupancy[cell] = (byte) 0xff;
                int sourceMedium = data.quadAlbedoWeight[cell] > 0L
                        ? data.quadMediumMask[cell] : data.seedMediumMask[cell];
                medium[cell] = (byte) (sourceMedium | GiSemanticMedium.UNKNOWN_CONSERVATIVE.mask());
                provenance[cell] = (byte) data.provenance[cell];
                materialIds[cell] = (short) GiSemanticPalette.FALLBACK_ID;
                fallbackCells++;
                continue;
            }
            long occupancyAverage = (data.occupancySum[cell] + total - 1L) / total;
            occupancy[cell] = (byte) Math.min(255L, (occupancyAverage * 255L + 0xffffL - 1L) / 0xffffL);
            if (!data.content[cell] && Byte.toUnsignedInt(occupancy[cell]) == 0
                    && data.quadAlbedoWeight[cell] == 0L) {
                validity[cell] = (byte) GiSemanticValidity.KNOWN_EMPTY.abiId();
                provenance[cell] = (byte) data.provenance[cell];
                materialIds[cell] = (short) GiSemanticPalette.AIR_ID;
                continue;
            }
            validity[cell] = (byte) GiSemanticValidity.KNOWN_CONTENT.abiId();
            boolean hasObservedGeometry = data.quadAlbedoWeight[cell] > 0L;
            medium[cell] = (byte) (hasObservedGeometry
                    ? data.quadMediumMask[cell] : data.seedMediumMask[cell]);
            provenance[cell] = (byte) data.provenance[cell];
            materialIds[cell] = (short) dominantMaterial(
                    hasObservedGeometry ? data.quadMaterialWeights : data.seedMaterialWeights, cell
            );
            long albedoWeight = hasObservedGeometry
                    ? data.quadAlbedoWeight[cell] : data.seedAlbedoWeight[cell];
            long[] albedoSource = hasObservedGeometry ? data.quadAlbedo : data.seedAlbedo;
            for (int channel = 0; channel < 3; channel++) {
                albedo[cell * 3 + channel] = (short) GiSemanticPacking.roundedAverage(
                        albedoSource[cell * 3 + channel], albedoWeight
                );
            }
            boolean observedEmission = data.quadEmissionWeight[cell] > 0L;
            long emissionWeight = observedEmission
                    ? data.quadEmissionWeight[cell] : data.seedEmissionWeight[cell];
            long[] emissionSource = observedEmission ? data.quadEmission : data.seedEmission;
            for (int channel = 0; channel < 3; channel++) {
                emission[cell * 4 + channel] = (short) GiSemanticPacking.roundedAverage(
                        emissionSource[cell * 3 + channel], emissionWeight
                );
            }
            long intensitySum = observedEmission
                    ? data.quadEmissionIntensity[cell] : data.seedEmissionIntensity[cell];
            long intensityWeight = observedEmission
                    ? data.quadEmissionArea[cell] : data.seedAlbedoWeight[cell];
            emission[cell * 4 + 3] = (short) GiSemanticPacking.roundedAverage(intensitySum, intensityWeight);
            long[] faceSource = hasObservedGeometry ? data.quadFaces : data.seedFaces;
            long maxFace = 0L;
            for (int face = 0; face < 6; face++) {
                maxFace = Math.max(maxFace, faceSource[cell * 6 + face]);
            }
            if (maxFace > 0L) {
                for (int face = 0; face < 6; face++) {
                    faces[cell * 6 + face] = (byte) Math.min(255L,
                            (faceSource[cell * 6 + face] * 255L + maxFace / 2L) / maxFace);
                }
            }
        }
        return new GiSemanticSectionSnapshot(
                albedo, emission, occupancy, medium, validity, provenance, faces, coverage,
                materialIds, observedQuads, fallbackCells
        );
    }

    private static final class Accumulators {
        private final int[] totalBlocks = new int[GiSemanticSectionSnapshot.CELL_COUNT];
        private final int[] knownBlocks = new int[GiSemanticSectionSnapshot.CELL_COUNT];
        private final boolean[] content = new boolean[GiSemanticSectionSnapshot.CELL_COUNT];
        private final boolean[] fallback = new boolean[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] occupancySum = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final int[] seedMediumMask = new int[GiSemanticSectionSnapshot.CELL_COUNT];
        private final int[] quadMediumMask = new int[GiSemanticSectionSnapshot.CELL_COUNT];
        private final int[] provenance = new int[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] seedAlbedo = new long[GiSemanticSectionSnapshot.CELL_COUNT * 3];
        private final long[] seedAlbedoWeight = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] seedEmission = new long[GiSemanticSectionSnapshot.CELL_COUNT * 3];
        private final long[] seedEmissionWeight = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] seedEmissionIntensity = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] seedFaces = new long[GiSemanticSectionSnapshot.CELL_COUNT * 6];
        private final long[] quadAlbedo = new long[GiSemanticSectionSnapshot.CELL_COUNT * 3];
        private final long[] quadAlbedoWeight = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] quadEmission = new long[GiSemanticSectionSnapshot.CELL_COUNT * 3];
        private final long[] quadEmissionWeight = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] quadEmissionIntensity = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] quadEmissionArea = new long[GiSemanticSectionSnapshot.CELL_COUNT];
        private final long[] quadFaces = new long[GiSemanticSectionSnapshot.CELL_COUNT * 6];
        private final Map<Long, Long> seedMaterialWeights = new HashMap<>();
        private final Map<Long, Long> quadMaterialWeights = new HashMap<>();
    }

    private static int dominantMaterial(final Map<Long, Long> weights, final int cell) {
        long prefix = (long) cell << 32;
        long bestWeight = -1L;
        int bestId = GiSemanticPalette.FALLBACK_ID;
        for (Map.Entry<Long, Long> entry : weights.entrySet()) {
            if ((entry.getKey() & 0xffff_ffff_0000_0000L) != prefix) continue;
            int id = (int) (long) entry.getKey();
            long weight = entry.getValue();
            if (weight > bestWeight || weight == bestWeight && id < bestId) {
                bestWeight = weight;
                bestId = id;
            }
        }
        return bestId;
    }
}
