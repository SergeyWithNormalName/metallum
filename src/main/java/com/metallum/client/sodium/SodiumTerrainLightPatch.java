package com.metallum.client.sodium;

import com.metallum.Metallum;
import com.metallum.client.benchmark.TorchEpochTelemetry;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Independent opt-in gate for compact light uploads; failure preserves the full sidecar path. */
public final class SodiumTerrainLightPatch {
    public static final String ENVIRONMENT_VARIABLE = "METALLUM_SODIUM_LIGHT_PATCH";

    private static final boolean CONFIGURED = parseEnabled(System.getenv(ENVIRONMENT_VARIABLE));

    private static boolean runtimeFailed;
    private static long fallbackCount;
    private static String lastFailure = "";

    private SodiumTerrainLightPatch() {
    }

    public static synchronized boolean isRuntimeActive() {
        return CONFIGURED && !runtimeFailed && SodiumLightSidecar.isRuntimeActive();
    }

    public static synchronized void fail(final String reason, @Nullable final Throwable exception) {
        if (runtimeFailed) {
            return;
        }
        runtimeFailed = true;
        fallbackCount++;
        lastFailure = reason;
        SodiumTerrainStaticShadow.releaseAll();
        try {
            TorchEpochTelemetry.recordCompactLightPatchFallback();
        } catch (Throwable telemetryFailure) {
            if (exception != null) {
                exception.addSuppressed(telemetryFailure);
            }
        }
        if (exception == null) {
            Metallum.LOGGER.error(
                    "Compact Sodium terrain light patches disabled; full uploads remain active: {}",
                    reason
            );
        } else {
            Metallum.LOGGER.error(
                    "Compact Sodium terrain light patches disabled; full uploads remain active: {}",
                    reason,
                    exception
            );
        }
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(
                CONFIGURED,
                CONFIGURED && !runtimeFailed && SodiumLightSidecar.isRuntimeActive(),
                fallbackCount,
                lastFailure,
                SodiumTerrainStaticShadow.snapshot()
        );
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
            long fallbackCount,
            String lastFailure,
            SodiumTerrainStaticShadow.Snapshot shadow
    ) {
    }
}
