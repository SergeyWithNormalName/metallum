package com.metallum.client.metalfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/** Fail-isolated HUD overlay showing real-time 3D render resolution telemetry. */
public final class DrsResolutionOverlayHud {
    private DrsResolutionOverlayHud() {
    }

    public static void render(final GuiGraphicsExtractor graphics) {
        if (!MetalFxUpscaling.isResolutionOverlayEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null || minecraft.font == null) {
            return;
        }

        int displayWidth = minecraft.getWindow().getWidth();
        int displayHeight = minecraft.getWindow().getHeight();
        MetalFxUpscaling.Dimensions dims = MetalFxUpscaling.effectiveDimensions(displayWidth, displayHeight);

        String label;
        if (MetalFxUpscaling.isActive()) {
            boolean dynamic = MetallumDrsController.isEnabled();
            label = dynamic
                    ? String.format(
                            Locale.ROOT,
                            "DRS Render: %dx%d (%.0f%%) → %dx%d [%s, GPU %.1f ms]",
                            dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name(),
                            MetallumDrsController.emaGpuTimeMs()
                    )
                    : String.format(
                            Locale.ROOT,
                            "MetalFX Render: %dx%d (%.0f%%) → %dx%d [%s]",
                            dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name()
                    );
        } else {
            label = String.format(
                    Locale.ROOT,
                    "Render: %dx%d (100%% Native)",
                    displayWidth,
                    displayHeight
            );
        }

        int textWidth = minecraft.font.width(label);
        int x = 10;
        int y = 10;
        graphics.fill(x - 4, y - 4, x + textWidth + 4, y + 12, 0xd0000000);
        graphics.text(minecraft.font, label, x, y, 0xff00ff88, true);
    }
}
