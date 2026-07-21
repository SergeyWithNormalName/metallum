package com.metallum.client.display;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.lang.foreign.MemorySegment;

public final class NativeFullscreen implements AutoCloseable {
    private final MemorySegment handle;

    public NativeFullscreen(final MemorySegment nsWindow, final MemorySegment metalLayer) {
        if (MetalNativeBridge.isNullHandle(nsWindow) || MetalNativeBridge.isNullHandle(metalLayer)) {
            throw new IllegalArgumentException("nsWindow and metalLayer handles must be valid non-null segments");
        }

        this.handle = MetalNativeBridge.metallum_fullscreen_create(nsWindow, metalLayer);
        if (MetalNativeBridge.isNullHandle(this.handle)) {
            throw new IllegalStateException("Failed to create native fullscreen coordinator");
        }
    }

    public void setFullscreen(final boolean fullscreen) {
        if (MetalNativeBridge.isNullHandle(this.handle)) return;
        MetalNativeBridge.metallum_fullscreen_set(this.handle, fullscreen ? 1 : 0);
    }

    public void toggleFullscreen() {
        if (MetalNativeBridge.isNullHandle(this.handle)) return;
        MetalNativeBridge.metallum_fullscreen_toggle(this.handle);
    }

    public FullscreenSnapshot snapshot() {
        if (MetalNativeBridge.isNullHandle(this.handle)) {
            return new FullscreenSnapshot(FullscreenSnapshot.STATE_WINDOWED, false, false, 0L);
        }
        long packed = MetalNativeBridge.metallum_fullscreen_query(this.handle);
        return FullscreenSnapshot.decode(packed);
    }

    @Override
    public void close() {
        if (!MetalNativeBridge.isNullHandle(this.handle)) {
            MetalNativeBridge.metallum_fullscreen_release(this.handle);
        }
    }
}
