package com.metallum.client.voxel;

import com.metallum.Metallum;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Fail-isolated HUD renderer for a downsampled XY slice of the acknowledged L5 mirror. */
public final class VoxelPreviewHud {
    private static final int MAX_PANEL_EDGE = 128;
    private static volatile boolean failed;

    private VoxelPreviewHud() {
    }

    public static void render(final GuiGraphicsExtractor graphics) {
        VoxelPreviewSettings.State settings = VoxelPreviewSettings.get();
        if (failed || settings.mode() == VoxelPreviewMode.OFF) {
            return;
        }
        try {
            renderChecked(graphics, settings);
        } catch (RuntimeException exception) {
            failed = true;
            Metallum.LOGGER.warn("L5 preview HUD disabled after a diagnostic-only failure", exception);
        }
    }

    private static void renderChecked(
            final GuiGraphicsExtractor graphics,
            final VoxelPreviewSettings.State settings
    ) {
        VoxelClipmapSnapshot clipmap = VoxelClipmapController.global().snapshot();
        int requestedLevel = settings.level();
        if (clipmap == null || clipmap.levels().isEmpty()) {
            drawStatus(graphics, "L5 preview: waiting for Advanced clipmap", 0);
            return;
        }
        int levelIndex = Math.min(requestedLevel, clipmap.levels().size() - 1);
        VoxelPreviewMirror.Snapshot mirror = VoxelPreviewMirror.global().snapshot(
                clipmap, levelIndex
        );
        if (mirror == null) {
            drawStatus(graphics, "L5 preview: waiting for acknowledged uploads", 0);
            return;
        }

        VoxelClipmapSnapshot.Level level = mirror.level();
        int logicalEdge = level.logicalEdge();
        int slice = Math.min(settings.slice(), logicalEdge - 1);
        int panelEdge = Math.min(MAX_PANEL_EDGE,
                Math.max(32, Math.min(graphics.guiWidth() - 16, graphics.guiHeight() - 40)));
        int x0 = graphics.guiWidth() - panelEdge - 8;
        int y0 = 20;
        graphics.fill(x0 - 2, y0 - 14, x0 + panelEdge + 2, y0 + panelEdge + 2,
                0xd0000000);

        for (int py = 0; py < panelEdge; py++) {
            int logicalY = (panelEdge - 1 - py) * logicalEdge / panelEdge;
            int runStart = 0;
            int runColor = sample(mirror, settings.mode(), logicalY, 0, slice);
            for (int px = 1; px <= panelEdge; px++) {
                int color = px == panelEdge ? Integer.MIN_VALUE : sample(
                        mirror, settings.mode(), logicalY, px * logicalEdge / panelEdge, slice
                );
                if (color != runColor) {
                    graphics.fill(x0 + runStart, y0 + py, x0 + px, y0 + py + 1, runColor);
                    runStart = px;
                    runColor = color;
                }
            }
        }
        Minecraft minecraft = Minecraft.getInstance();
        graphics.text(minecraft.font, Component.literal("L5 " + settings.mode().name()
                        + "  L" + levelIndex + "  Z=" + slice + '/' + (logicalEdge - 1)),
                x0, y0 - 11, 0xffffffff, true);
    }

    private static int sample(
            final VoxelPreviewMirror.Snapshot snapshot,
            final VoxelPreviewMode mode,
            final int logicalY,
            final int logicalX,
            final int logicalZ
    ) {
        VoxelClipmapSnapshot.Level level = snapshot.level();
        int brickX = logicalX / VoxelBrickPatch.LOGICAL_EDGE;
        int brickY = logicalY / VoxelBrickPatch.LOGICAL_EDGE;
        int brickZ = logicalZ / VoxelBrickPatch.LOGICAL_EDGE;
        VoxelPreviewMirror.Brick brick = snapshot.bricks().get(new VoxelPreviewMirror.Key(
                level.level(), Math.toIntExact(level.originBrickX() + brickX),
                Math.toIntExact(level.originBrickY() + brickY),
                Math.toIntExact(level.originBrickZ() + brickZ)
        ));
        if (brick == null || brick.contentStamp() == 0) {
            return ((logicalX >> 2) ^ (logicalY >> 2)) % 2 == 0
                    ? 0xff5f165f : 0xff2b092b;
        }
        int localX = logicalX & 31;
        int localY = logicalY & 31;
        int localZ = logicalZ & 31;
        boolean occupied = (brick.occupancy()[localZ * 32 + localY] & (1 << localX)) != 0;
        if (mode == VoxelPreviewMode.OCCUPANCY) {
            return occupied ? 0xffffffff : 0xff090909;
        }
        if (!occupied) {
            return 0xff090909;
        }
        int baseEdge = 32 / level.subdivision();
        int opticalIndex = ((localZ / level.subdivision()) * baseEdge
                + localY / level.subdivision()) * baseEdge + localX / level.subdivision();
        VoxelMaterialDescriptor material = VoxelMaterialDescriptor.fromPackedUnsignedByte(
                Byte.toUnsignedInt(brick.optical()[opticalIndex])
        );
        if (mode == VoxelPreviewMode.TRANSMITTANCE) {
            int value = material.quantizedTransmittance() * 255
                    / VoxelMaterialDescriptor.TRANSMITTANCE_MAX;
            return 0xff000000 | value << 16 | value << 8 | value;
        }
        return switch (material.materialClass()) {
            case AIR -> 0xff707070;
            case OPAQUE -> 0xffe0e0e0;
            case CUTOUT -> 0xffffc52f;
            case GLASS -> 0xff50dcff;
            case FOLIAGE -> 0xff50dc50;
            case WATER -> 0xff3c78ff;
            case TRANSLUCENT -> 0xffc878ff;
            case UNKNOWN_CONSERVATIVE -> 0xffff00ff;
        };
    }

    private static void drawStatus(
            final GuiGraphicsExtractor graphics,
            final String message,
            final int row
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.font.width(message);
        int x = graphics.guiWidth() - width - 8;
        int y = 8 + row * 11;
        graphics.fill(x - 2, y - 2, x + width + 2, y + 10, 0xd0000000);
        graphics.text(minecraft.font, message, x, y, 0xffffffff, true);
    }
}
