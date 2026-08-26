package com.metallum.client.gi.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ensures G3 stays detached from view/receiver/G4 implementation paths except its opaque handoff. */
public final class GiDirectSourceSourceChainTests {
    private GiDirectSourceSourceChainTests() {
    }

    public static void main(final String[] args) throws IOException {
        Path root = Path.of("src/main/java/com/metallum/client/gi/source");
        List<String> forbidden = List.of(
                "directlightfrustum", "snapshotforframe", "publishdynamicframe",
                "lightmap", "brightness", "camera", "screen", "depth", "history",
                "receiver", "albedo", "reflectance"
        );
        try (var paths = Files.walk(root)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source).toLowerCase(java.util.Locale.ROOT);
                for (String forbiddenToken : forbidden) {
                    if (content.contains(forbiddenToken)) {
                        throw new AssertionError(source + " retains forbidden G3 source-chain token " + forbiddenToken);
                    }
                }
                if (content.contains("com.metallum.client.gi.transport")
                        || content.contains("gisemantictransportfieldview")) {
                    throw new AssertionError(source
                            + " depends on G4 implementation instead of exposing an opaque source owner");
                }
            }
        }
    }
}
