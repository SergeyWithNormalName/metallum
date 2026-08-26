package com.metallum.client.gi.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Static dependency and hot-path allocation guard for the field-only G4 Java package. */
public final class GiTransportSourceChainTests {
    private GiTransportSourceChainTests() {
    }

    public static void main(final String[] arguments) throws IOException {
        Path root = Path.of("src/main/java/com/metallum/client/gi/transport");
        List<String> requiredFiles = List.of(
                "GiTransportLayout.java", "GiTransportRuntime.java", "GiTransportEpoch.java",
                "GiTransportGpuResources.java", "GiTransportCoordinator.java"
        );
        for (String required : requiredFiles) {
            if (!Files.isRegularFile(root.resolve(required))) {
                throw new AssertionError("Missing bounded G4 Java core file " + required);
            }
        }

        List<String> forbidden = List.of(
                "lightmap", "brightness", "camera", "screen", "depth", "history",
                "receiver", "terrain", "scenecolor", "fragment", "bounce1", "secondbounce",
                "gisemanticfieldsnapshot", "gisemanticgpuencoder", "gisemanticdirectfieldview"
        );
        try (var paths = Files.walk(root)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source).toLowerCase(Locale.ROOT);
                for (String token : forbidden) {
                    if (content.contains(token)) {
                        throw new AssertionError(source + " retains forbidden G4 source token " + token);
                    }
                }
                if (content.contains("com.metallum.client.metal.render.metaldevice")
                        || content.contains("com.metallum.client.metal.render.metalcommandencoder")) {
                    throw new AssertionError(source + " depends on renderer/image wiring");
                }
            }
        }

        String coordinator = Files.readString(root.resolve("GiTransportCoordinator.java"));
        if (coordinator.contains("Arena") || coordinator.contains("allocate(")) {
            throw new AssertionError("G4 frame coordinator allocates native memory");
        }
        if (!coordinator.contains("BuildState.SUBMITTED")
                || !coordinator.contains("resolveSubmittedState")
                || !coordinator.contains("this.buildState == BuildState.READY")
                || !coordinator.contains("STATUS_STALE")
                || !coordinator.contains("this.resources.reportStale()")) {
            throw new AssertionError(
                    "G4 coordinator does not confirm and observe one immutable frozen epoch"
            );
        }

        String resources = Files.readString(root.resolve("GiTransportGpuResources.java"));
        require(resources.contains("this.header = arena.allocate(GiTransportLayout.HEADER_BYTES")
                        && resources.contains("this.cells = arena.allocate(GiTransportLayout.CELLS_BYTES")
                        && resources.contains("this.stats = arena.allocate(GiTransportLayout.STATS_BYTES"),
                "G4 context does not own the three fixed Java FFM packets");
        require(resources.contains("try (Arena captureArena = Arena.ofConfined())")
                        && occurrences(resources, "captureArena.allocate(") == 5,
                "G4 capture does not isolate exactly five temporary compact destinations");
        require(resources.contains("metallum_gi_transport_encode_frozen_v1")
                        && resources.contains("metallum_gi_transport_capture_volume_once_v1")
                        && resources.contains("this.deferredRelease.accept(stale)"),
                "G4 native encode/capture/deferred-retirement chain is incomplete");

        String directCoordinator = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
        ));
        String directResources = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceGpuResources.java"
        ));
        require(directCoordinator.contains("public static final class TransportSource")
                        && directCoordinator.contains("private TransportSource(")
                        && directCoordinator.contains("record TransportSourceIdentity")
                        && directCoordinator.contains("queue.completed() != GiDirectSourceLayout.TOTAL_BRICKS")
                        && directCoordinator.contains("queue.pending() != 0")
                        && directCoordinator.contains("queue.discarded() != 0L")
                        && directCoordinator.contains("!stats.ready() || stats.buildInFlight()")
                        && directCoordinator.contains("transportSourceIdentityStillCurrent")
                        && directResources.contains("transportContextHandle()"),
                "G3 did not expose a fully converged opaque source boundary for G4");
        require(!directCoordinator.contains("com.metallum.client.gi.transport")
                        && !directResources.contains("com.metallum.client.gi.transport"),
                "G3 source implementation depends on G4 instead of an opaque handoff");

        System.out.println("G4 Java source-chain contract tests passed");
    }

    private static int occurrences(final String source, final String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
