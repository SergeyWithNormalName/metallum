package com.metallum.client.lighting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure, exact 16^3 scan shared by the Sodium adapter and property tests. */
public final class StaticLightSectionScanner {
    public static final int SECTION_SIZE = 16;
    public static final int BLOCKS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    @FunctionalInterface
    public interface Sampler {
        /** Returns null for a non-emitter. */
        LightTemplate sample(int localIndex, int localX, int localY, int localZ);
    }

    private StaticLightSectionScanner() {
    }

    public static LightSectionCandidate scan(
            final LightSectionTask task,
            final int originX,
            final int originY,
            final int originZ,
            final int maxSectionLights,
            final Sampler sampler
    ) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (maxSectionLights <= 0 || maxSectionLights > BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("maxSectionLights is outside 1..4096");
        }
        if (sampler == null) {
            throw new NullPointerException("sampler");
        }

        List<LightSectionCandidate.Entry> emitted = new ArrayList<>();
        int scanned = 0;
        for (int localIndex = 0; localIndex < BLOCKS_PER_SECTION; localIndex++) {
            int localX = localIndex & 15;
            int localZ = localIndex >>> 4 & 15;
            int localY = localIndex >>> 8 & 15;
            LightTemplate template = sampler.sample(localIndex, localX, localY, localZ);
            scanned++;
            if (template == null) {
                continue;
            }
            if (template.kind() != LightSourceKind.BLOCK) {
                throw new IllegalArgumentException("Static samplers must emit BLOCK templates");
            }
            int blockX = originX + localX;
            int blockY = originY + localY;
            int blockZ = originZ + localZ;
            long stableId = StableLightIds.block(
                    task.world().dimensionId(),
                    blockX,
                    blockY,
                    blockZ
            );
            emitted.add(new LightSectionCandidate.Entry(
                    localIndex,
                    template.materialize(stableId, task.baseEpoch())
            ));
        }

        int emittedCount = emitted.size();
        if (emitted.size() > maxSectionLights) {
            emitted = stratifiedRetain(emitted, maxSectionLights);
        }
        emitted.sort(Comparator.comparingInt(LightSectionCandidate.Entry::localIndex));
        return new LightSectionCandidate(task, emitted, scanned, emittedCount);
    }

    private static List<LightSectionCandidate.Entry> stratifiedRetain(
            final List<LightSectionCandidate.Entry> emitted,
            final int maxSectionLights
    ) {
        Map<DenseBlockLightCompactor.GroupKey, List<LightSectionCandidate.Entry>> byCell =
                new HashMap<>();
        for (LightSectionCandidate.Entry entry : emitted) {
            byCell.computeIfAbsent(
                    DenseBlockLightCompactor.groupKey(entry.light()),
                    ignored -> new ArrayList<>()
            ).add(entry);
        }
        List<List<LightSectionCandidate.Entry>> groups = new ArrayList<>(byCell.values());
        for (List<LightSectionCandidate.Entry> group : groups) {
            group.sort(Comparator.comparing(
                    LightSectionCandidate.Entry::light,
                    AdvancedLight.PRIORITY_ORDER
            ));
        }
        groups.sort((left, right) -> AdvancedLight.PRIORITY_ORDER.compare(
                left.getFirst().light(),
                right.getFirst().light()
        ));

        List<LightSectionCandidate.Entry> retained = new ArrayList<>(maxSectionLights);
        for (int round = 0; retained.size() < maxSectionLights; round++) {
            boolean offered = false;
            for (List<LightSectionCandidate.Entry> group : groups) {
                if (round < group.size()) {
                    retained.add(group.get(round));
                    offered = true;
                    if (retained.size() == maxSectionLights) {
                        break;
                    }
                }
            }
            if (!offered) {
                break;
            }
        }
        return retained;
    }
}
