package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.hdr.HdrConfig;
import com.metallum.client.hdr.HdrOutputMode;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private final MetalDevice device;
    private final MemorySegment metalLayer;
    private final HdrConfig hdrConfig;
    private GpuSurface.Configuration configuration;
    private MetalCommandEncoder pendingPresentEncoder;
    private EdrCapabilities edrCapabilities = EdrCapabilities.SDR;
    private HdrOutputMode outputMode = HdrOutputMode.SDR;
    private boolean monitorFailureLogged;
    private boolean forceSdrUntilReconfigure;

    MetalSurface(final MetalDevice device, final MemorySegment metalLayer) {
        this.device = device;
        this.metalLayer = metalLayer;
        this.hdrConfig = device.hdrConfig();
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        if (config.width() <= 0 || config.height() <= 0) {
            throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
        }

        this.configuration = config;
        this.forceSdrUntilReconfigure = false;
        this.refreshEdrCapabilities();
        HdrOutputMode desiredMode = this.hdrConfig.mode().resolve(this.edrCapabilities);
        if (desiredMode != this.outputMode) {
            this.device.waitForPreviouslySubmittedGpuWork();
        }
        if (!this.configureNative(desiredMode)) {
            if (desiredMode != HdrOutputMode.SDR && this.configureNative(HdrOutputMode.SDR)) {
                desiredMode = HdrOutputMode.SDR;
                this.forceSdrUntilReconfigure = true;
                Metallum.LOGGER.warn("Metal rejected the requested HDR layer configuration; using SDR");
            } else {
                throw new SurfaceException("Failed to configure CAMetalLayer for " + desiredMode);
            }
        }
        this.setOutputMode(desiredMode);
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
    }

    @Override
    public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
        if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
            throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
        }

        this.refreshOutputModeIfNeeded();
        metalEncoder.presentTextureToDrawable(
                metalLayer,
                textureView,
                this.outputMode,
                this.hdrConfig,
                this.edrCapabilities
        );
        this.pendingPresentEncoder = metalEncoder;
    }

    @Override
    public void present() {
        pendingPresentEncoder.submit();
    }

    @Override
    public void close() {
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }

    private void refreshOutputModeIfNeeded() {
        this.refreshEdrCapabilities();
        HdrOutputMode desiredMode = this.forceSdrUntilReconfigure
                ? HdrOutputMode.SDR
                : this.hdrConfig.mode().resolve(this.edrCapabilities);
        if (desiredMode == this.outputMode || this.configuration == null) {
            return;
        }

        this.device.waitForPreviouslySubmittedGpuWork();
        if (this.configureNative(desiredMode)) {
            this.setOutputMode(desiredMode);
            return;
        }

        if (desiredMode != HdrOutputMode.SDR) {
            this.forceSdrUntilReconfigure = true;
        }
        Metallum.LOGGER.error("Failed to switch Metal output to {}; keeping {}", desiredMode, this.outputMode);
    }

    private void refreshEdrCapabilities() {
        try {
            this.edrCapabilities = this.device.queryEdrCapabilities();
        } catch (RuntimeException exception) {
            this.edrCapabilities = EdrCapabilities.SDR;
            if (!this.monitorFailureLogged) {
                this.monitorFailureLogged = true;
                Metallum.LOGGER.warn("Failed to query EDR display capabilities; using SDR", exception);
            }
        }
    }

    private boolean configureNative(final HdrOutputMode mode) {
        return MetalNativeBridge.metallum_configure_layer(
                this.metalLayer,
                this.configuration.width(),
                this.configuration.height(),
                this.configuration.presentMode() == GpuSurface.PresentMode.MAILBOX ? 1 : 0,
                mode.nativeValue(),
                8.0f
        );
    }

    private void setOutputMode(final HdrOutputMode mode) {
        if (mode == this.outputMode) {
            return;
        }
        this.outputMode = mode;
        Metallum.LOGGER.info(
                "Metal output: {} (current EDR headroom {}, potential {})",
                mode,
                this.edrCapabilities.currentHeadroom(),
                this.edrCapabilities.potentialHeadroom()
        );
    }
}
