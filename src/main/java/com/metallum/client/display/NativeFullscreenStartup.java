package com.metallum.client.display;

import com.metallum.mixin.render.NativeFullscreenWindowAccessor;
import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

/** Converts the vanilla startup fullscreen window to AppKit fullscreen after Metal is ready. */
public final class NativeFullscreenStartup {
    private static final ThreadLocal<Boolean> ALLOW_GLFW_MODE_CHANGE = ThreadLocal.withInitial(() -> false);

    private NativeFullscreenStartup() {
    }

    public static boolean allowsGlfwModeChange() {
        return ALLOW_GLFW_MODE_CHANGE.get();
    }

    public static void normalizeInitialFullscreen(final Window window, final NativeFullscreen nativeFullscreen) {
        if (!MacosUtil.IS_MACOS || GLFW.glfwGetWindowMonitor(window.handle()) == 0L) {
            return;
        }

        NativeFullscreenWindowAccessor access = (NativeFullscreenWindowAccessor) (Object) window;
        boolean requestedFullscreen = access.metallum$getFullscreen();
        access.metallum$setFullscreen(false);
        access.metallum$setActuallyFullscreen(false);

        ALLOW_GLFW_MODE_CHANGE.set(true);
        try {
            access.metallum$applyVanillaWindowMode();
        } finally {
            ALLOW_GLFW_MODE_CHANGE.remove();
        }

        if (requestedFullscreen) {
            nativeFullscreen.setFullscreen(true);
        }
    }
}
