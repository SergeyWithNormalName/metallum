package com.metallum.client.gi.debug;

import com.metallum.client.gi.transport.GiTransportRuntime;

import java.nio.file.Files;
import java.nio.file.Path;

/** Persistence and presentation contracts for the restart-gated G4 Sodium debug option. */
public final class GiTransportDebugSettingsTests {
    private GiTransportDebugSettingsTests() {
    }

    public static void main(final String[] arguments) throws Exception {
        Path directory = Files.createTempDirectory("metallum-g4-debug-");
        Path settings = directory.resolve("debug.properties");
        try {
            require(!GiTransportDebugSettings.load(settings),
                    "missing G4 debug settings did not default to disabled");
            require(GiTransportDebugSettings.save(settings, true)
                            && GiTransportDebugSettings.load(settings),
                    "G4 debug enabled state did not round-trip");
            require(GiTransportDebugSettings.save(settings, false)
                            && !GiTransportDebugSettings.load(settings),
                    "G4 debug disabled state did not round-trip");

            require(GiTransportDebugHud.lines(snapshot(false, false,
                            GiTransportRuntime.AdmissionState.WAITING)).size() == 1,
                    "G4 pre-restart HUD must remain a single explicit status line");
            require(GiTransportDebugHud.lines(snapshot(true, true,
                            GiTransportRuntime.AdmissionState.READY)).size() == 5,
                    "G4 READY HUD lost immutable population diagnostics");
            require(GiTransportDebugHud.lines(snapshot(true, false,
                            GiTransportRuntime.AdmissionState.INVALID)).size() == 3,
                    "G4 INVALID HUD lost its fail-closed reason");
        } finally {
            Files.deleteIfExists(settings);
            Files.deleteIfExists(directory);
        }
        System.out.println("G4 Sodium debug settings tests passed");
    }

    private static GiTransportRuntime.DebugSnapshot snapshot(
            final boolean requested,
            final boolean sourceReady,
            final GiTransportRuntime.AdmissionState state
    ) {
        return new GiTransportRuntime.DebugSnapshot(
                requested, sourceReady, state, "test reason", true,
                1L, 12L, 3L, 2_916_480L, -32, -64, -96
        );
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
