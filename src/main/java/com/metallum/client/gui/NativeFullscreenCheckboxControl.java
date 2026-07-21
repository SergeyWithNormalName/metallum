package com.metallum.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.options.FullscreenMode;
import net.caffeinemc.mods.sodium.client.gui.options.control.AbstractOptionList;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.options.control.StatefulControlElement;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * A two-state control for Sodium's fullscreen option on macOS.
 *
 * <p>Sodium stores fullscreen as an enum so it can support exclusive and borderless modes on
 * other platforms. Metallum only exposes the native AppKit mode, whose state is equivalent to
 * {@link FullscreenMode#OFF} or {@link FullscreenMode#BORDERLESS}.</p>
 */
public final class NativeFullscreenCheckboxControl implements Control {
    private final EnumOption<FullscreenMode> option;

    public NativeFullscreenCheckboxControl(final EnumOption<FullscreenMode> option) {
        this.option = option;
    }

    @Override
    public Option getOption() {
        return this.option;
    }

    @Override
    public ControlElement createElement(
            final Screen screen,
            final AbstractOptionList list,
            final Dim2i dim,
            final ColorTheme theme
    ) {
        return new Element(list, this.option, dim, theme);
    }

    @Override
    public int getMaxWidth() {
        return 30;
    }

    private static final class Element extends StatefulControlElement {
        private static final int DISABLED_COLOR = 0xFFAAAAAA;

        private final EnumOption<FullscreenMode> option;

        private Element(
                final AbstractOptionList list,
                final EnumOption<FullscreenMode> option,
                final Dim2i dim,
                final ColorTheme theme
        ) {
            super(list, dim, theme);
            this.option = option;
        }

        @Override
        public EnumOption<FullscreenMode> getOption() {
            return this.option;
        }

        @Override
        public void extractRenderState(
                final GuiGraphicsExtractor graphics,
                final int mouseX,
                final int mouseY,
                final float delta
        ) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            if (this.option.shouldHideControl() || this.isResetOverlayActive()) {
                return;
            }

            int boxX = this.getLimitX() - 16;
            int boxY = this.getCenterY() - 5;
            int boxLimitX = boxX + 10;
            int boxLimitY = boxY + 10;
            boolean enabled = this.option.isEnabled();
            boolean checked = this.option.getValidatedValue() != FullscreenMode.OFF;
            int color = enabled ? (checked ? this.theme.theme : 0xFFFFFFFF) : DISABLED_COLOR;

            if (checked) {
                this.drawRect(graphics, boxX + 2, boxY + 2, boxLimitX - 2, boxLimitY - 2, color);
            }

            if (enabled) {
                this.drawBorder(graphics, boxX, boxY, boxLimitX, boxLimitY, color);
            } else {
                drawDisabledBorder(graphics, boxX, boxY, boxLimitX, boxLimitY, color);
            }

            if (this.isHovered()) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }

        @Override
        public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
            if (super.mouseClicked(event, doubleClick)) {
                return true;
            }

            if (this.isResetOverlayActive()
                    || !this.option.isEnabled()
                    || event.button() != 0
                    || !this.isMouseOver(event.x(), event.y())) {
                return false;
            }

            this.toggleControl();
            return true;
        }

        @Override
        public boolean keyPressed(final KeyEvent event) {
            if (!this.isFocused() || !event.isSelection()) {
                return false;
            }

            this.toggleControl();
            return true;
        }

        private void toggleControl() {
            this.playClickSound();
            FullscreenMode current = this.option.getValidatedValue();
            this.option.modifyValue(current == FullscreenMode.OFF
                    ? FullscreenMode.BORDERLESS
                    : FullscreenMode.OFF);
        }

        private static void drawDisabledBorder(
                final GuiGraphicsExtractor graphics,
                final int x,
                final int y,
                final int limitX,
                final int limitY,
                final int color
        ) {
            final int dashLength = 3;
            graphics.fill(x, y, x + dashLength, y + 1, color);
            graphics.fill(x, y, x + 1, y + dashLength, color);
            graphics.fill(limitX - dashLength, y, limitX, y + 1, color);
            graphics.fill(limitX - 1, y, limitX, y + dashLength, color);
            graphics.fill(x, limitY - 1, x + dashLength, limitY, color);
            graphics.fill(x, limitY - dashLength, x + 1, limitY, color);
            graphics.fill(limitX - dashLength, limitY - 1, limitX, limitY, color);
            graphics.fill(limitX - 1, limitY - dashLength, limitX, limitY, color);
        }
    }
}
