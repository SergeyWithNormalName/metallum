package com.metallum.client.gi.debug;

import com.metallum.Metallum;
import com.metallum.client.gi.transport.GiTransportRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fail-isolated status HUD for G4 admission and immutable native counters. */
public final class GiTransportDebugHud {
    private static volatile boolean failed;
    private static volatile GiTransportRuntime.DebugSnapshot cachedSnapshot;
    private static volatile List<Component> cachedLines = List.of();

    private GiTransportDebugHud() {
    }

    public static void render(final GuiGraphicsExtractor graphics) {
        if (failed || !GiTransportDebugSettings.isEnabled()
                || GiTransportRuntime.isBenchmarkActive()) {
            return;
        }
        try {
            GiTransportRuntime.DebugSnapshot snapshot = GiTransportRuntime.debugSnapshot();
            List<Component> lines = cachedLines;
            if (!snapshot.equals(cachedSnapshot)) {
                lines = lines(snapshot);
                cachedSnapshot = snapshot;
                cachedLines = lines;
            }
            draw(graphics, lines);
        } catch (RuntimeException exception) {
            failed = true;
            Metallum.LOGGER.warn("G4 debug HUD disabled after a diagnostic-only failure", exception);
        }
    }

    static List<Component> lines(final GiTransportRuntime.DebugSnapshot snapshot) {
        List<Component> lines = new ArrayList<>(5);
        if (!snapshot.requested()) {
            lines.add(Component.translatable("metallum.debug.gi_g4.restart_required"));
            return List.copyOf(lines);
        }

        String stateKey = switch (snapshot.admissionState()) {
            case WAITING -> "waiting";
            case READY -> "ready";
            case INVALID -> "invalid";
        };
        lines.add(Component.translatable("metallum.debug.gi_g4.title",
                Component.translatable("metallum.debug.gi_g4.state." + stateKey)));
        lines.add(Component.translatable("metallum.debug.gi_g4.source",
                snapshot.sourceReady()
                        ? Component.translatable("metallum.debug.gi_g4.source.ready")
                        : Component.translatable("metallum.debug.gi_g4.source.preparing")));

        if (snapshot.admissionState() == GiTransportRuntime.AdmissionState.INVALID) {
            lines.add(Component.translatable("metallum.debug.gi_g4.reason",
                    snapshot.invalidReason()));
        } else if (snapshot.statsAvailable()) {
            lines.add(Component.translatable("metallum.debug.gi_g4.population",
                    snapshot.transportDispatches(), snapshot.validSurfaceCount(),
                    snapshot.unknownCellCount()));
            lines.add(Component.translatable("metallum.debug.gi_g4.origin",
                    snapshot.nearOriginX(), snapshot.nearOriginY(), snapshot.nearOriginZ()));
            lines.add(Component.translatable("metallum.debug.gi_g4.memory",
                    String.format(Locale.ROOT, "%.2f", snapshot.accountedBytes() / 1_048_576.0)));
        }
        return List.copyOf(lines);
    }

    private static void draw(
            final GuiGraphicsExtractor graphics,
            final List<Component> lines
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, minecraft.font.width(line));
        }
        int x = 8;
        int y = 8;
        graphics.fill(x - 3, y - 3, x + width + 3, y + lines.size() * 11,
                0xd0000000);
        for (int row = 0; row < lines.size(); row++) {
            int color = row == 0 ? 0xff7ee787 : 0xffffffff;
            graphics.text(minecraft.font, lines.get(row), x, y + row * 11,
                    color, true);
        }
    }
}
