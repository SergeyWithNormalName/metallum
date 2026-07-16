package com.metallum.client.renderer;

import java.util.Objects;

/** Pure, versioned sizing contract shared by L3 generation planning and native admission. */
public final class AdvancedLightingLayout {
    public static final int ABI_VERSION = 1;
    public static final int TILE_SIZE = 64;
    public static final int DEPTH_SLICES = 6;
    public static final int MAX_GPU_CANDIDATE_LIGHTS = 4096;
    public static final int MAX_LIGHTS_PER_CLUSTER = 256;
    public static final int CLUSTER_MEMBERSHIP_WORDS =
            MAX_GPU_CANDIDATE_LIGHTS / Integer.SIZE;
    public static final int GPU_LIGHT_STRIDE = 48;
    public static final int CLUSTER_HEADER_STRIDE = 8;
    public static final int CLUSTER_SCRATCH_STRIDE =
            CLUSTER_MEMBERSHIP_WORDS * Integer.BYTES;
    public static final int LIGHT_INDEX_STRIDE = Integer.BYTES;
    public static final int LIGHTING_PARAMS_BYTES = 256;
    public static final int STATISTICS_BYTES = 256;
    public static final int UPLOAD_HEADER_BYTES = 64;
    public static final int UPLOAD_RING_SLOTS = 3;
    public static final int NATIVE_BUFFER_GUARD_BYTES = 64;
    public static final int GUARDED_NATIVE_BUFFER_COUNT = 6;

    public record Budget(
            int maxLights,
            int maxLightsPerCluster,
            int indexCapacity,
            int clustersX,
            int clustersY,
            int clustersZ,
            int clusterCount,
            long uploadRingBytes,
            long gpuLightBytes,
            long clusterHeaderBytes,
            long clusterScratchBytes,
            long clusterIndexBytes,
            long totalBytes
    ) {
        public Budget {
            if (maxLights <= 0 || maxLightsPerCluster <= 0
                    || maxLightsPerCluster > MAX_LIGHTS_PER_CLUSTER
                    || indexCapacity <= 0 || clustersX <= 0 || clustersY <= 0
                    || clustersZ <= 0 || clusterCount <= 0) {
                throw new IllegalArgumentException("Advanced lighting capacities must be positive");
            }
            if (uploadRingBytes <= 0L || gpuLightBytes <= 0L || clusterHeaderBytes <= 0L
                    || clusterScratchBytes <= 0L || clusterIndexBytes <= 0L || totalBytes <= 0L) {
                throw new IllegalArgumentException("Advanced lighting byte counts must be positive");
            }
        }
    }

    private AdvancedLightingLayout() {
    }

    public static Budget forGeneration(
            final LightingPreset preset,
            final int renderWidth,
            final int renderHeight
    ) {
        Objects.requireNonNull(preset, "preset");
        if (renderWidth <= 0 || renderHeight <= 0) {
            throw new IllegalArgumentException("Render extent must be positive");
        }

        int maxLights = MAX_GPU_CANDIDATE_LIGHTS;
        int maxLightsPerCluster;
        int hardIndexCapacity;
        switch (preset) {
            case PERFORMANCE -> {
                maxLightsPerCluster = MAX_LIGHTS_PER_CLUSTER;
                hardIndexCapacity = 2_000_000;
            }
            case BALANCED -> {
                maxLightsPerCluster = MAX_LIGHTS_PER_CLUSTER;
                hardIndexCapacity = 4_000_000;
            }
            case ULTRA -> {
                maxLightsPerCluster = MAX_LIGHTS_PER_CLUSTER;
                hardIndexCapacity = 8_000_000;
            }
            default -> throw new IllegalStateException("Unhandled lighting preset " + preset);
        }

        int clustersX = divideRoundUp(renderWidth, TILE_SIZE);
        int clustersY = divideRoundUp(renderHeight, TILE_SIZE);
        int clusterCount = Math.multiplyExact(Math.multiplyExact(clustersX, clustersY), DEPTH_SLICES);
        int desiredIndices = Math.multiplyExact(clusterCount, maxLightsPerCluster);
        int indexCapacity = Math.max(maxLights, Math.min(desiredIndices, hardIndexCapacity));

        long uploadSlotBytes = Math.addExact(
                UPLOAD_HEADER_BYTES,
                Math.multiplyExact((long) maxLights, GPU_LIGHT_STRIDE)
        );
        long uploadRingBytes = Math.multiplyExact(uploadSlotBytes, UPLOAD_RING_SLOTS);
        long gpuLightBytes = Math.multiplyExact((long) maxLights, GPU_LIGHT_STRIDE);
        long clusterHeaderBytes = Math.multiplyExact((long) clusterCount, CLUSTER_HEADER_STRIDE);
        long clusterScratchBytes = Math.multiplyExact((long) clusterCount, CLUSTER_SCRATCH_STRIDE);
        long clusterIndexBytes = Math.multiplyExact((long) indexCapacity, LIGHT_INDEX_STRIDE);
        long totalBytes = Math.addExact(
                Math.addExact(uploadRingBytes, gpuLightBytes),
                Math.addExact(
                        Math.addExact(clusterHeaderBytes, clusterScratchBytes),
                        Math.addExact(clusterIndexBytes, LIGHTING_PARAMS_BYTES + STATISTICS_BYTES)
                )
        );
        totalBytes = Math.addExact(
                totalBytes,
                Math.multiplyExact(
                        (long) NATIVE_BUFFER_GUARD_BYTES,
                        GUARDED_NATIVE_BUFFER_COUNT
                )
        );
        return new Budget(
                maxLights,
                maxLightsPerCluster,
                indexCapacity,
                clustersX,
                clustersY,
                DEPTH_SLICES,
                clusterCount,
                uploadRingBytes,
                gpuLightBytes,
                clusterHeaderBytes,
                clusterScratchBytes,
                clusterIndexBytes,
                totalBytes
        );
    }

    private static int divideRoundUp(final int value, final int divisor) {
        return Math.addExact(value, divisor - 1) / divisor;
    }

    public static long nativeAllocationBytes(final long payloadBytes) {
        if (payloadBytes <= 0L) {
            throw new IllegalArgumentException("Native lighting payload must be positive");
        }
        return Math.addExact(payloadBytes, NATIVE_BUFFER_GUARD_BYTES);
    }
}
