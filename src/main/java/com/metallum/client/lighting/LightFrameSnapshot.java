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
        boolean reachedWorldLights = false;
        for (AdvancedLight light : lights) {
            boolean cameraHeld = light.shadowSourceClass()
                    == LocalShadowSourceClass.CAMERA_HELD;
            if (reachedWorldLights && cameraHeld) {
                throw new IllegalArgumentException(
                        "Snapshot camera-held lights are not an upload prefix"
                );
            }
            reachedWorldLights |= !cameraHeld;
        }
    }

    public static LightFrameSnapshot empty() {
        return new LightFrameSnapshot(CURRENT_VERSION, null, 0L, List.of(), 0, 0, 0);
    }
}
