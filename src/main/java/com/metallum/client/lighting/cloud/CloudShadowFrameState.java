package com.metallum.client.lighting.cloud;

import com.metallum.client.lighting.EnvironmentDescriptor;
import net.minecraft.client.CloudStatus;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Immutable per-frame cloud shadow state extracted directly from Minecraft 26.2 environment.
 */
public record CloudShadowFrameState(
        CloudShadowMode mode,
        float cloudHeight,
        float cloudThickness,
        float cloudOpacity,
        float cloudOffsetX,
        float cloudOffsetZ,
        float gridWidth,
        float gridHeight,
        float toLightX,
        float toLightY,
        float toLightZ,
        float shadowStrength,
        long patternGeneration,
        boolean enabled
) {
    public static final CloudShadowFrameState DISABLED = new CloudShadowFrameState(
            CloudShadowMode.NONE,
            0.0f,
            CloudShadowPolicy.CLOUD_THICKNESS_BLOCKS,
            0.0f,
            0.0f,
            0.0f,
            3072.0f,
            3072.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0L,
            false
    );

    public CloudShadowFrameState {
        Objects.requireNonNull(mode, "mode");
    }

    public static CloudShadowFrameState disabled() {
        return DISABLED;
    }

    /**
     * Extracts an immutable CloudShadowFrameState from the current frame's Minecraft level and environment state.
     */
    public static CloudShadowFrameState extract(
            final CloudStatus cloudStatus,
            final float cloudHeight,
            final int cloudColorArgb,
            final long gameTime,
            final float partialTick,
            final EnvironmentDescriptor environment,
            final CloudShadowSource source
    ) {
        float opacity = ARGB.alpha(cloudColorArgb) / 255.0f;
        boolean sourceAvailable = source != null && source.isAvailable();
        CloudShadowMode mode = CloudShadowMode.fromMinecraft(cloudStatus, cloudHeight, opacity, sourceAvailable);

        if (mode == CloudShadowMode.NONE || environment == null || !environment.sunShadowEligible()) {
            return DISABLED;
        }

        int width = source.width();
        int height = source.height();
        float gridW = source.gridWidthBlocks();
        float gridH = source.gridHeightBlocks();

        float offsetX = CloudShadowPolicy.computeCloudOffsetX(gameTime, partialTick, width);
        float offsetZ = CloudShadowPolicy.computeCloudOffsetZ();

        float strength = (mode == CloudShadowMode.VOLUMETRIC)
                ? CloudShadowPolicy.VOLUMETRIC_SHADOW_MAX_ATTENUATION
                : CloudShadowPolicy.FLAT_SHADOW_MAX_ATTENUATION;

        return new CloudShadowFrameState(
                mode,
                cloudHeight,
                CloudShadowPolicy.CLOUD_THICKNESS_BLOCKS,
                opacity,
                offsetX,
                offsetZ,
                gridW,
                gridH,
                environment.toLightX(),
                environment.toLightY(),
                environment.toLightZ(),
                strength,
                source.generation(),
                true
        );
    }
}
