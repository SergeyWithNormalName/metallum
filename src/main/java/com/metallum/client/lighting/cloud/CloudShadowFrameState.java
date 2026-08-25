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
        float cloudFogEnd,
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
        float skyRed,
        float skyGreen,
        float skyBlue,
        float horizonRed,
        float horizonGreen,
        float horizonBlue,
        float horizonStrength,
        long patternGeneration,
        boolean directShadowEnabled,
        boolean skyReflectionEnabled,
        boolean enabled
) {
    public static final CloudShadowFrameState DISABLED = new CloudShadowFrameState(
            CloudShadowMode.NONE,
            0.0f,
            CloudShadowPolicy.CLOUD_THICKNESS_BLOCKS,
            0.0f,
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
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0L,
            false,
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
            final int skyColorArgb,
            final int horizonColorArgb,
            final int cloudRangeChunks,
            final long gameTime,
            final float partialTick,
            final EnvironmentDescriptor environment,
            final CloudShadowSource source
    ) {
        if (environment == null || environment.profile() != EnvironmentDescriptor.Profile.CELESTIAL) {
            return DISABLED;
        }

        float opacity = ARGB.alpha(cloudColorArgb) / 255.0f;
        boolean sourceAvailable = source != null && source.isAvailable();
        CloudShadowMode mode = CloudShadowMode.fromMinecraft(cloudStatus, cloudHeight, opacity, sourceAvailable);
        boolean cloudsEnabled = mode != CloudShadowMode.NONE;

        LinearColor cloudColor = LinearColor.fromSrgb(
                ARGB.red(cloudColorArgb) / 255.0f,
                ARGB.green(cloudColorArgb) / 255.0f,
                ARGB.blue(cloudColorArgb) / 255.0f
        );
        LinearColor skyColor = LinearColor.fromSrgb(
                ARGB.red(skyColorArgb) / 255.0f,
                ARGB.green(skyColorArgb) / 255.0f,
                ARGB.blue(skyColorArgb) / 255.0f
        );
        LinearColor horizonColor = LinearColor.fromSrgb(
                ARGB.red(horizonColorArgb) / 255.0f,
                ARGB.green(horizonColorArgb) / 255.0f,
                ARGB.blue(horizonColorArgb) / 255.0f
        );

        int width = sourceAvailable ? source.width() : 256;
        float gridW = sourceAvailable ? source.gridWidthBlocks() : 3072.0f;
        float gridH = sourceAvailable ? source.gridHeightBlocks() : 3072.0f;

        float offsetX = CloudShadowPolicy.computeCloudOffsetX(gameTime, partialTick, width);
        float offsetZ = CloudShadowPolicy.computeCloudOffsetZ();

        return new CloudShadowFrameState(
                mode,
                Float.isFinite(cloudHeight) ? cloudHeight : 0.0f,
                CloudShadowPolicy.CLOUD_THICKNESS_BLOCKS,
                cloudsEnabled ? opacity : 0.0f,
                Math.max(cloudRangeChunks * 16.0f, 16.0f),
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
                skyColor.red(),
                skyColor.green(),
                skyColor.blue(),
                horizonColor.red(),
                horizonColor.green(),
                horizonColor.blue(),
                ARGB.alpha(horizonColorArgb) / 255.0f,
                sourceAvailable ? source.generation() : 0L,
                cloudsEnabled && environment.sunShadowEligible(),
                true,
                cloudsEnabled
        );
    }
}
