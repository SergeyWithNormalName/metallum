package com.metallum.client.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Camera-independent spatial LOD for dense, photometrically identical block emitters. */
public final class DenseBlockLightCompactor {
    private static final int COARSE_CELL_EDGE = 8;
    private static final int COARSE_DENSE_THRESHOLD = 32;

    public record Result(
            List<AdvancedLight> lights,
            int rawLightCount,
            int aggregateGroupCount
    ) {
        public Result {
            lights = List.copyOf(lights);
            if (rawLightCount < lights.size() || aggregateGroupCount < 0) {
                throw new IllegalArgumentException("Invalid dense-light compaction counts");
            }
        }

        public int mergedLightCount() {
            return this.rawLightCount - this.lights.size();
        }
    }

    record GroupKey(
            int cellX,
            int cellY,
            int cellZ,
            int cellEdge,
            int radiusBits,
            int redBits,
            int greenBits,
            int blueBits,
            int intensityBits,
            int priority
    ) {
    }

    private DenseBlockLightCompactor() {
    }

    public static Result compact(
            final String dimensionId,
            final Iterable<AdvancedLight> sourceLights
    ) {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
        if (sourceLights == null) {
            throw new NullPointerException("sourceLights");
        }

        // Only raw static BLOCK emitters may enter this first pass.  Its output is
        // deliberately kept out of the existing 4^3/2^3 pass: aggregating a proxy
        // again would alter its footprint and energy a second time.
        Map<GroupKey, List<AdvancedLight>> coarseGroups = new HashMap<>();
        ArrayList<AdvancedLight> remainingEligibleLights = new ArrayList<>();
        ArrayList<AdvancedLight> compacted = new ArrayList<>();
        int rawCount = 0;
        for (AdvancedLight light : sourceLights) {
            if (light == null) {
                throw new NullPointerException("sourceLights contains null");
            }
            rawCount++;
            if (!light.denseCellEligible() || light.kind() != LightSourceKind.BLOCK) {
                compacted.add(light);
                continue;
            }
            coarseGroups.computeIfAbsent(groupKey(light, COARSE_CELL_EDGE), ignored -> new ArrayList<>())
                    .add(light);
        }

        compacted.ensureCapacity(rawCount);
        int aggregateGroups = 0;
        for (Map.Entry<GroupKey, List<AdvancedLight>> entry : coarseGroups.entrySet()) {
            GroupKey key = entry.getKey();
            List<AdvancedLight> members = entry.getValue();
            members.sort(AdvancedLight.PRIORITY_ORDER);
            if (members.size() < denseThreshold(key.cellEdge())) {
                remainingEligibleLights.addAll(members);
                continue;
            }
            compacted.add(aggregate(dimensionId, key, members));
            aggregateGroups++;
        }

        Map<GroupKey, List<AdvancedLight>> fineGroups = new HashMap<>();
        for (AdvancedLight light : remainingEligibleLights) {
            fineGroups.computeIfAbsent(groupKey(light), ignored -> new ArrayList<>()).add(light);
        }
        for (Map.Entry<GroupKey, List<AdvancedLight>> entry : fineGroups.entrySet()) {
            GroupKey key = entry.getKey();
            List<AdvancedLight> members = entry.getValue();
            members.sort(AdvancedLight.PRIORITY_ORDER);
            if (key.cellEdge() == 1 || members.size() < denseThreshold(key.cellEdge())) {
                compacted.addAll(members);
                continue;
            }
            compacted.add(aggregate(dimensionId, key, members));
            aggregateGroups++;
        }
        compacted.sort(AdvancedLight.PRIORITY_ORDER);
        return new Result(compacted, rawCount, aggregateGroups);
    }

    static GroupKey groupKey(final AdvancedLight light) {
        return groupKey(light, cellEdge(light.radius()));
    }

    static GroupKey groupKey(final AdvancedLight light, final int edge) {
        if (edge != 1 && edge != 2 && edge != 4 && edge != COARSE_CELL_EDGE) {
            throw new IllegalArgumentException("Unsupported dense-light cell edge");
        }
        int blockX = floorToInt(light.x());
        int blockY = floorToInt(light.y());
        int blockZ = floorToInt(light.z());
        int cellX = Math.floorDiv(blockX, edge) * edge;
        int cellY = Math.floorDiv(blockY, edge) * edge;
        int cellZ = Math.floorDiv(blockZ, edge) * edge;
        return new GroupKey(
                cellX,
                cellY,
                cellZ,
                edge,
                Float.floatToRawIntBits(light.radius()),
                Float.floatToRawIntBits(light.red()),
                Float.floatToRawIntBits(light.green()),
                Float.floatToRawIntBits(light.blue()),
                Float.floatToRawIntBits(light.intensity()),
                light.priority()
        );
    }

    static int cellEdge(final float radius) {
        if (radius >= 9.0F) {
            return 4;
        }
        return radius >= 3.0F ? 2 : 1;
    }

    static int denseThreshold(final int cellEdge) {
        return switch (cellEdge) {
            case COARSE_CELL_EDGE -> COARSE_DENSE_THRESHOLD;
            case 4 -> 4;
            case 2 -> 2;
            case 1 -> Integer.MAX_VALUE;
            default -> throw new IllegalArgumentException("Unsupported dense-light cell edge");
        };
    }

    private static AdvancedLight aggregate(
            final String dimensionId,
            final GroupKey key,
            final List<AdvancedLight> members
    ) {
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        long generation = members.getFirst().generation();
        for (AdvancedLight member : members) {
            x += member.x();
            y += member.y();
            z += member.z();
            if (Long.compareUnsigned(member.generation(), generation) > 0) {
                generation = member.generation();
            }
        }
        x /= members.size();
        y /= members.size();
        z /= members.size();

        double maximumOffsetSquared = 0.0;
        for (AdvancedLight member : members) {
            double dx = member.x() - x;
            double dy = member.y() - y;
            double dz = member.z() - z;
            maximumOffsetSquared = Math.max(
                    maximumOffsetSquared,
                    dx * dx + dy * dy + dz * dz
            );
        }
        AdvancedLight exemplar = members.getFirst();
        float radius = exemplar.radius() + (float) Math.sqrt(maximumOffsetSquared);
        // The shader's radial attenuation integrates proportionally to intensity * radius^3.
        // Preserve the aggregate energy while enlarging support to contain every member.
        float radiusRatio = exemplar.radius() / radius;
        float intensity = members.size() * exemplar.intensity()
                * radiusRatio * radiusRatio * radiusRatio;
        long stableId = StableLightIds.denseBlock(
                dimensionId,
                key.cellX(),
                key.cellY(),
                key.cellZ(),
                key.cellEdge(),
                exemplar.radius(),
                exemplar.red(),
                exemplar.green(),
                exemplar.blue(),
                exemplar.intensity(),
                exemplar.priority()
        );
        ShadowEmitterFootprint footprint = ShadowEmitterFootprint.of(
                members.stream().map(member -> new ShadowEmitterFootprint.Block(
                        floorToInt(member.x()),
                        floorToInt(member.y()),
                        floorToInt(member.z())
                )).toList()
        );
        return new AdvancedLight(
                stableId,
                generation,
                LightSourceKind.BLOCK,
                x,
                y,
                z,
                radius,
                exemplar.red(),
                exemplar.green(),
                exemplar.blue(),
                intensity,
                exemplar.priority(),
                true,
                footprint
        );
    }

    private static int floorToInt(final double value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Block light position is outside integer world bounds");
        }
        return (int) floor;
    }
}
