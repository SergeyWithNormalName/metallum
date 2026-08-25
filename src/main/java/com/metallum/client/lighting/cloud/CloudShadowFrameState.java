package com.metallum.client.lighting.cloud;

import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.renderer.style.LinearColor;
import net.minecraft.client.CloudStatus;
import net.minecraft.util.ARGB;

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
        float cloudRed,
        float cloudGreen,
        float cloudBlue,
        long patternGeneration,
        boolean directShadowEnabled,
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
            0.0f,
            0.0f,
            0L,
            false,
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

        if (mode == CloudShadowMode.NONE || environment == null) {
            return DISABLED;
        }

        LinearColor cloudColor = LinearColor.fromSrgb(
                ARGB.red(cloudColorArgb) / 255.0f,
                ARGB.green(cloudColorArgb) / 255.0f,
                ARGB.blue(cloudColorArgb) / 255.0f
        );

        int width = source.width();
        int height = source.height();
        float gridW = source.gridWidthBlocks();
        float gridH = source.gridHeightBlocks();

        float offsetX = CloudShadowPolicy.computeCloudOffsetX(gameTime, partialTick, width);
        float offsetZ = CloudShadowPolicy.computeCloudOffsetZ();

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
                cloudColor.red(),
                cloudColor.green(),
                cloudColor.blue(),
                source.generation(),
                environment.sunShadowEligible(),
                true
        );
    }
}
