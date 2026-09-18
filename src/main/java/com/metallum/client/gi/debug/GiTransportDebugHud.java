package com.metallum.client.gi.debug;

import com.metallum.Metallum;
import com.metallum.client.gi.transport.GiTransportRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fail-isolated status HUD and captured middle slice of the frozen G4 indirect field. */
public final class GiTransportDebugHud {
    private static final int EDGE = 32;
    private static final int SLICE = EDGE / 2;
    private static final int CELL_PIXELS = 3;
    private static volatile boolean failed;
    private static volatile GiTransportRuntime.DebugSnapshot cachedSnapshot;
    private static volatile List<Component> cachedLines = List.of();
    private static volatile Object cachedCapture;
    private static volatile int[] cachedColors;

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
            int[] colors = cachedColors;
            var capture = GiTransportRuntime.debugCapture();
            if (capture != null && capture != cachedCapture) {
                colors = buildIndirectDcSlice(capture.shRedRgbaFloat16(),
                        capture.shGreenRgbaFloat16(), capture.shBlueRgbaFloat16());
                cachedCapture = capture;
                cachedColors = colors;
            }
            draw(graphics, lines, colors);
        } catch (RuntimeException exception) {
            failed = true;
            Metallum.LOGGER.warn("G4 debug HUD disabled after a diagnostic-only failure", exception);
        }
    }

    static List<Component> lines(final GiTransportRuntime.DebugSnapshot snapshot) {
        List<Component> lines = new ArrayList<>(7);
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
        lines.add(Component.translatable("metallum.debug.gi_g4.semantic_source",
                snapshot.semanticSourceReady()
                        ? Component.translatable("metallum.debug.gi_g4.source.ready")
                        : Component.translatable("metallum.debug.gi_g4.semantic_source.missing")));
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
            lines.add(Component.translatable(snapshot.captureReady()
                    ? "metallum.debug.gi_g4.capture.ready"
                    : "metallum.debug.gi_g4.capture.pending"));
        }
        return List.copyOf(lines);
    }

    private static void draw(
            final GuiGraphicsExtractor graphics,
            final List<Component> lines,
            final int[] colors
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
        if (colors == null) {
            return;
        }
        int panelY = y + lines.size() * 11 + 3;
        int panelEdge = EDGE * CELL_PIXELS;
        graphics.fill(x - 3, panelY - 14, x + panelEdge + 3, panelY + panelEdge + 3,
                0xd0000000);
        graphics.text(minecraft.font,
                Component.translatable("metallum.debug.gi_g4.slice", SLICE),
                x, panelY - 11, 0xffffffff, true);
        for (int py = 0; py < EDGE; py++) {
            for (int px = 0; px < EDGE; px++) {
                int color = colors[(EDGE - 1 - py) * EDGE + px];
                int x0 = x + px * CELL_PIXELS;
                int y0 = panelY + py * CELL_PIXELS;
                graphics.fill(x0, y0, x0 + CELL_PIXELS, y0 + CELL_PIXELS, color);
            }
        }
    }

    static int[] buildIndirectDcSlice(
            final short[] shRed,
            final short[] shGreen,
            final short[] shBlue
    ) {
        int[] colors = new int[EDGE * EDGE];
        float maximum = 0.0f;
        for (int y = 0; y < EDGE; y++) {
            for (int x = 0; x < EDGE; x++) {
                int component = ((SLICE * EDGE + y) * EDGE + x) * 4;
                float red = positiveHalf(shRed[component]);
                float green = positiveHalf(shGreen[component]);
                float blue = positiveHalf(shBlue[component]);
                maximum = Math.max(maximum, Math.max(red, Math.max(green, blue)));
            }
        }
        float scale = maximum > 0.0f ? 1.0f / maximum : 0.0f;
        for (int y = 0; y < EDGE; y++) {
            for (int x = 0; x < EDGE; x++) {
                int component = ((SLICE * EDGE + y) * EDGE + x) * 4;
                int red = displayChannel(positiveHalf(shRed[component]) * scale);
                int green = displayChannel(positiveHalf(shGreen[component]) * scale);
                int blue = displayChannel(positiveHalf(shBlue[component]) * scale);
                colors[y * EDGE + x] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
        return colors;
    }

    private static float positiveHalf(final short bits) {
        float value = Float.float16ToFloat(bits);
        return Float.isFinite(value) ? Math.max(value, 0.0f) : 0.0f;
    }

    private static int displayChannel(final float linear) {
        float encoded = (float) Math.sqrt(Math.clamp(linear, 0.0f, 1.0f));
        return Math.round(encoded * 255.0f);
    }
}
