package com.metallum.client.gi.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ensures the CPU/domain package stays detached from view, receiver and transport paths. */
public final class GiDirectSourceSourceChainTests {
    private GiDirectSourceSourceChainTests() {
    }

    public static void main(final String[] args) throws IOException {
        Path root = Path.of("src/main/java/com/metallum/client/gi/source");
        List<String> forbidden = List.of(
                "directlightfrustum", "snapshotforframe", "publishdynamicframe",
                "lightmap", "brightness", "camera", "screen", "depth", "history",
                "receiver", "transport", "albedo", "reflectance"
        );
        try (var paths = Files.walk(root)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source).toLowerCase(java.util.Locale.ROOT);
                for (String forbiddenToken : forbidden) {
                    if (content.contains(forbiddenToken)) {
                        throw new AssertionError(source + " retains forbidden G3 source-chain token " + forbiddenToken);
                    }
                }
            }
        }
    }
}
