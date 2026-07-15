package com.metallum.client.sodium;

import com.metallum.Metallum;
import com.metallum.client.benchmark.TorchEpochTelemetry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Runtime gate and identity registry for Metal-only Sodium terrain light companions. */
public final class SodiumLightSidecar {
    public static final String ENVIRONMENT_VARIABLE = "METALLUM_SODIUM_LIGHT_SIDECAR";
    private static final int COMPANION_USAGE = GpuBuffer.USAGE_COPY_DST
            | GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_VERTEX
            | GpuBuffer.USAGE_INDEX;
    private static final boolean CONFIGURED = parseEnabled(System.getenv(ENVIRONMENT_VARIABLE));
    private static final Map<GpuBuffer, GpuBuffer> COMPANIONS = new IdentityHashMap<>();
    private static final Set<String> PATCHED_PIPELINES = new HashSet<>();

    private static boolean runtimeFailed;
    private static long liveGeometryBytes;
    private static long liveSidecarBytes;
    private static long peakSidecarBytes;
    private static long fallbackCount;
    private static String lastFailure = "";

    private SodiumLightSidecar() {
    }

    public static boolean isConfigured() {
        return CONFIGURED;
    }

    public static synchronized boolean isRuntimeActive() {
        return CONFIGURED && !runtimeFailed;
    }

    public static boolean isMetalBackend() {
        return "Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
    }

    public static GpuBuffer createCompanion(final GpuBuffer geometryBuffer) {
        if (!isRuntimeActive()) {
            throw new IllegalStateException("Sodium light sidecar is not active");
        }
        long sidecarBytes = expectedSidecarBytes(geometryBuffer.size());
        return RenderSystem.getDevice().createBuffer(
                (Supplier<String>) () -> "Metallum Sodium light sidecar",
                COMPANION_USAGE,
                sidecarBytes
        );
    }

    public static synchronized void attach(final GpuBuffer geometryBuffer, final GpuBuffer sidecarBuffer) {
        validatePair(geometryBuffer, sidecarBuffer);
        GpuBuffer previous = COMPANIONS.get(geometryBuffer);
        if (previous == sidecarBuffer) {
            return;
        }
        if (previous != null) {
            throw new IllegalStateException("Geometry buffer already has a Sodium light sidecar");
        }
        if (COMPANIONS.containsValue(sidecarBuffer)) {
            throw new IllegalStateException("Sodium light sidecar is already attached to another geometry buffer");
        }

        long nextGeometryBytes = Math.addExact(liveGeometryBytes, usableGeometryBytes(geometryBuffer.size()));
        long nextSidecarBytes = Math.addExact(liveSidecarBytes, sidecarBuffer.size());
        COMPANIONS.put(geometryBuffer, sidecarBuffer);
        liveGeometryBytes = nextGeometryBytes;
        liveSidecarBytes = nextSidecarBytes;
        peakSidecarBytes = Math.max(peakSidecarBytes, liveSidecarBytes);
    }

    public static synchronized void replace(
            final GpuBuffer oldGeometryBuffer,
            final GpuBuffer oldSidecarBuffer,
            final GpuBuffer newGeometryBuffer,
            final GpuBuffer newSidecarBuffer
    ) {
        validatePair(newGeometryBuffer, newSidecarBuffer);
        if (oldGeometryBuffer == newGeometryBuffer || oldSidecarBuffer == newSidecarBuffer) {
            throw new IllegalArgumentException("Sodium light sidecar transfer must change both buffers");
        }

        GpuBuffer registered = COMPANIONS.get(oldGeometryBuffer);
        if (registered != oldSidecarBuffer) {
            throw new IllegalStateException("Old Sodium light sidecar registry entry changed during arena transfer");
        }
        if (COMPANIONS.containsKey(newGeometryBuffer)) {
            throw new IllegalStateException("New geometry buffer already has a Sodium light sidecar");
        }
        if (COMPANIONS.containsValue(newSidecarBuffer)) {
            throw new IllegalStateException("New Sodium light sidecar is already attached");
        }

        long nextGeometryBytes = Math.addExact(
                Math.subtractExact(liveGeometryBytes, usableGeometryBytes(oldGeometryBuffer.size())),
                usableGeometryBytes(newGeometryBuffer.size())
        );
        long nextSidecarBytes = Math.addExact(
                Math.subtractExact(liveSidecarBytes, oldSidecarBuffer.size()),
                newSidecarBuffer.size()
        );
        if (nextGeometryBytes < 0L || nextSidecarBytes < 0L) {
            throw new IllegalStateException("Sodium light sidecar byte accounting became negative");
        }

        COMPANIONS.remove(oldGeometryBuffer);
        COMPANIONS.put(newGeometryBuffer, newSidecarBuffer);
        liveGeometryBytes = nextGeometryBytes;
        liveSidecarBytes = nextSidecarBytes;
        peakSidecarBytes = Math.max(peakSidecarBytes, liveSidecarBytes);
    }

    public static synchronized void detach(final GpuBuffer geometryBuffer, final GpuBuffer sidecarBuffer) {
        GpuBuffer registered = COMPANIONS.get(geometryBuffer);
        if (registered == null) {
            return;
        }
        if (registered != sidecarBuffer) {
            throw new IllegalStateException("Sodium light sidecar registry ownership mismatch");
        }

        long nextGeometryBytes = Math.subtractExact(
                liveGeometryBytes,
                usableGeometryBytes(geometryBuffer.size())
        );
        long nextSidecarBytes = Math.subtractExact(liveSidecarBytes, sidecarBuffer.size());
        if (nextGeometryBytes < 0L || nextSidecarBytes < 0L) {
            throw new IllegalStateException("Sodium light sidecar byte accounting became negative");
        }
        COMPANIONS.remove(geometryBuffer);
        liveGeometryBytes = nextGeometryBytes;
        liveSidecarBytes = nextSidecarBytes;
    }

    @Nullable
    public static synchronized GpuBuffer find(final GpuBuffer geometryBuffer) {
        return isRuntimeActive() ? COMPANIONS.get(geometryBuffer) : null;
    }

    public static synchronized void notePatchedPipeline(final String pipeline) {
        PATCHED_PIPELINES.add(pipeline);
    }

    public static synchronized void fail(final String reason, @Nullable final Throwable exception) {
        if (runtimeFailed) {
            return;
        }
        runtimeFailed = true;
        fallbackCount++;
        lastFailure = reason;
        Throwable reportedException = exception;
        try {
            TorchEpochTelemetry.recordSidecarFallback();
        } catch (Throwable telemetryFailure) {
            if (reportedException == null) {
                reportedException = telemetryFailure;
            } else {
                reportedException.addSuppressed(telemetryFailure);
            }
        }
        if (reportedException == null) {
            Metallum.LOGGER.error("Sodium light sidecar disabled; legacy packed lighting remains active: {}", reason);
        } else {
            Metallum.LOGGER.error(
                    "Sodium light sidecar disabled; legacy packed lighting remains active: {}",
                    reason,
                    reportedException
            );
        }
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(
                CONFIGURED,
                CONFIGURED && !runtimeFailed,
                COMPANIONS.size(),
                liveGeometryBytes,
                liveSidecarBytes,
                peakSidecarBytes,
                PATCHED_PIPELINES.size(),
                fallbackCount,
                lastFailure
        );
    }

    public static synchronized void releaseAll() {
        for (GpuBuffer sidecar : COMPANIONS.values()) {
            try {
                sidecar.close();
            } catch (RuntimeException exception) {
                Metallum.LOGGER.error("Could not release a Sodium light sidecar", exception);
            }
        }
        COMPANIONS.clear();
        liveGeometryBytes = 0L;
        liveSidecarBytes = 0L;
    }

    public static long expectedSidecarBytes(final long geometryBytes) {
        long usableBytes = usableGeometryBytes(geometryBytes);
        return Math.multiplyExact(
                usableBytes / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE
        );
    }

    /** Sodium rounds a reused arena buffer down to a whole number of stride-sized vertices. */
    public static long usableGeometryBytes(final long geometryBytes) {
        if (geometryBytes < SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE) {
            throw new IllegalArgumentException("Sodium geometry buffer is smaller than one vertex: " + geometryBytes);
        }
        return geometryBytes / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
                * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
    }

    private static void validatePair(final GpuBuffer geometryBuffer, final GpuBuffer sidecarBuffer) {
        long expected = expectedSidecarBytes(geometryBuffer.size());
        if (sidecarBuffer.size() != expected) {
            throw new IllegalArgumentException(
                    "Sodium light sidecar size mismatch: expected=" + expected
                            + ", actual=" + sidecarBuffer.size()
            );
        }
    }

    private static boolean parseEnabled(@Nullable final String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    public record Snapshot(
            boolean configured,
            boolean runtimeActive,
            int companionCount,
            long liveGeometryBytes,
            long liveSidecarBytes,
            long peakSidecarBytes,
            int patchedPipelineCount,
            long fallbackCount,
            String lastFailure
    ) {
    }
}
