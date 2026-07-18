package com.metallum.client.lighting;

import java.util.List;

/** One immutable, already bounded and upload-ordered frame view. */
public record LightFrameSnapshot(
        int version,
        LightWorldToken world,
        long registryEpoch,
        List<AdvancedLight> lights,
        int staticLightCount,
        int dynamicLightCount,
        int droppedLightCount
) {
    public static final int CURRENT_VERSION = 1;

    public LightFrameSnapshot {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported light snapshot version");
        }
        if (registryEpoch < 0L) {
            throw new IllegalArgumentException("registryEpoch must be non-negative");
        }
        lights = List.copyOf(lights);
        if (staticLightCount < 0 || dynamicLightCount < 0 || droppedLightCount < 0) {
            throw new IllegalArgumentException("Snapshot counters must be non-negative");
        }
        if (staticLightCount + dynamicLightCount != lights.size()) {
            throw new IllegalArgumentException("Snapshot source counters do not match its list");
        }
        for (int index = 1; index < lights.size(); index++) {
            AdvancedLight previous = lights.get(index - 1);
            AdvancedLight current = lights.get(index);
            boolean previousHeld = previous.shadowSourceClass()
                    == LocalShadowSourceClass.CAMERA_HELD;
            boolean currentHeld = current.shadowSourceClass()
                    == LocalShadowSourceClass.CAMERA_HELD;
            if ((!previousHeld && currentHeld)
                    || (previousHeld == currentHeld && previous.priority() < current.priority())) {
                throw new IllegalArgumentException("Snapshot lights are not upload ordered");
            }
        }
    }

    public static LightFrameSnapshot empty() {
        return new LightFrameSnapshot(CURRENT_VERSION, null, 0L, List.of(), 0, 0, 0);
    }
}
