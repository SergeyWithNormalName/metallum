package com.metallum.client.benchmark;

import com.metallum.Metallum;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metalfx.SpatialScalingMode;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Environment-gated, deterministic 5K benchmark driver.
 *
 * <p>The mixin which owns this controller is not applied unless
 * {@code METALLUM_BENCHMARK=1}, so normal clients have no transformed hot path.
 */
public final class MetalFxBenchmarkController {
    private static final int WINDOW_TRANSITION_TIMEOUT_FRAMES = 240;
    private static final int SURVIVAL_GUARD_REFRESH_FRAMES = 120;
    private static final int WINDOWED_WIDTH = 1280;
    private static final int WINDOWED_HEIGHT = 720;

    private enum Stage {
        IDLE,
        SELECT_MONITOR,
        EXIT_FULLSCREEN,
        MOVE_WINDOWED,
        WAIT_MOVED,
        ENTER_FULLSCREEN,
        WAIT_FRAMEBUFFER,
        RUNNING,
        STOPPING
    }

    private final String monitorName;
    private final int targetWidth;
    private final int targetHeight;
    private final int warmupFrames;
    private final int measureFrames;
    private final boolean useCurrentWindow;
    private final boolean captureScreenshots;
    private final List<SpatialScalingMode> sequence;
    private final String configurationError;

    private Stage stage = Stage.IDLE;
    private int stageFrames;
    private int segmentIndex;
    private int segmentFrame;
    private int expectedFramebufferWidth;
    private int expectedFramebufferHeight;
    private long targetMonitor;
    private VideoMode targetVideoMode;
    private Optional<VideoMode> originalFullscreenMode = Optional.empty();
    private final AtomicBoolean survivalGuardTaskPending = new AtomicBoolean();
    private UUID guardedPlayerId;
    private volatile boolean survivalGuardApplied;
    private volatile String survivalGuardFailure;
    private int survivalGuardRefreshCountdown;
    private boolean originalInvulnerable;
    private float originalHealth;
    private int originalFoodLevel;
    private float originalSaturation;
    private boolean originalClientStateCaptured;
    private boolean armed;

    public MetalFxBenchmarkController() {
        String error = null;
        this.monitorName = env("METALLUM_BENCHMARK_MONITOR", "PHL");
        this.targetWidth = positiveInt("METALLUM_BENCHMARK_WIDTH", 5120);
        this.targetHeight = positiveInt("METALLUM_BENCHMARK_HEIGHT", 2880);
        this.warmupFrames = positiveInt("METALLUM_BENCHMARK_WARMUP_FRAMES", 1800);
        this.measureFrames = positiveInt("METALLUM_BENCHMARK_MEASURE_FRAMES", 3000);
        this.useCurrentWindow = "1".equals(System.getenv("METALLUM_BENCHMARK_CURRENT_WINDOW"));
        this.captureScreenshots = "1".equals(System.getenv("METALLUM_BENCHMARK_SCREENSHOTS"));
        this.expectedFramebufferWidth = this.targetWidth;
        this.expectedFramebufferHeight = this.targetHeight;

        List<SpatialScalingMode> parsed = new ArrayList<>();
        try {
            for (String value : env("METALLUM_BENCHMARK_SEQUENCE", "OFF").split(",")) {
                SpatialScalingMode mode = SpatialScalingMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
                if (!mode.concrete()) {
                    throw new IllegalArgumentException("AUTO is not a benchmark preset");
                }
                parsed.add(mode);
            }
            if (parsed.isEmpty()) {
                error = "benchmark sequence is empty";
            }
        } catch (IllegalArgumentException exception) {
            error = "invalid METALLUM_BENCHMARK_SEQUENCE";
        }
        this.sequence = List.copyOf(parsed);
        this.configurationError = error;
    }

    public void arm() {
        if (this.armed) {
            return;
        }
        this.armed = true;
        this.stage = Stage.SELECT_MONITOR;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=ARMED scope={} target={}x{} warmup={} measure={} sequence={}",
                this.useCurrentWindow ? "current-window" : this.monitorName,
                this.targetWidth,
                this.targetHeight,
                this.warmupFrames,
                this.measureFrames,
                this.sequence
        );
    }

    public void driveWindow(final Minecraft minecraft) {
        if (!this.armed || this.stage == Stage.IDLE || this.stage == Stage.STOPPING) {
            return;
        }
        if (!minecraft.isGameLoadFinished() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (!this.originalClientStateCaptured) {
            Window window = minecraft.getWindow();
            this.originalFullscreenMode = window.getPreferredFullscreenVideoMode();
            this.originalClientStateCaptured = true;
        }
        // Automated runs intentionally have no input. Keep Minecraft's AFK
        // limiter from replacing the requested uncapped framerate with 30 FPS.
        minecraft.getFramerateLimitTracker().onInputReceived();
        maintainSurvivalGuard(minecraft);
        if (this.survivalGuardFailure != null) {
            fail(minecraft, this.survivalGuardFailure);
            return;
        }
        if (this.stage == Stage.RUNNING) {
            return;
        }
        if (this.configurationError != null) {
            fail(minecraft, this.configurationError);
            return;
        }

        this.stageFrames++;
        Window window = minecraft.getWindow();
        switch (this.stage) {
            case SELECT_MONITOR -> {
                if (this.useCurrentWindow) {
                    selectCurrentWindow(minecraft, window);
                } else {
                    selectMonitor(minecraft, window);
                }
            }
            case EXIT_FULLSCREEN -> exitFullscreen(minecraft, window);
            case MOVE_WINDOWED -> moveWindow(minecraft, window);
            case WAIT_MOVED -> waitForMove(minecraft, window);
            case ENTER_FULLSCREEN -> enterFullscreen(minecraft, window);
            case WAIT_FRAMEBUFFER -> waitForFramebuffer(minecraft, window);
            default -> {
            }
        }
    }

    public void onPresentedFrame(final Minecraft minecraft) {
        if (this.stage != Stage.RUNNING) {
            return;
        }
        if (!isTargetFramebuffer(minecraft.getWindow())) {
            fail(minecraft, "benchmark framebuffer changed during measurement");
            return;
        }

        this.segmentFrame++;
        if (this.segmentFrame == this.warmupFrames) {
            if (this.captureScreenshots) {
                Screenshot.grab(minecraft, false);
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED index={} mode={}",
                        this.segmentIndex + 1,
                        this.sequence.get(this.segmentIndex)
                );
            }
            logSegmentEvent("MEASURE_START");
        }
        if (this.segmentFrame < this.warmupFrames + this.measureFrames) {
            return;
        }

        logSegmentEvent("MEASURE_END");
        this.segmentIndex++;
        if (this.segmentIndex >= this.sequence.size()) {
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=COMPLETE segments={} measured_frames={} framebuffer={}x{}",
                    this.sequence.size(),
                    this.sequence.size() * this.measureFrames,
                    this.expectedFramebufferWidth,
                    this.expectedFramebufferHeight
            );
            finish(minecraft);
            return;
        }
        startSegment();
    }

    private void selectMonitor(final Minecraft minecraft, final Window window) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) {
            // GLFW may temporarily fail to materialize the monitor list on
            // macOS even though the primary display remains queryable. The
            // built-in Retina benchmark targets that primary display, so use
            // it as a safe fallback instead of failing a valid run.
            long primaryMonitor = GLFW.glfwGetPrimaryMonitor();
            if (primaryMonitor != 0L && selectMonitorCandidate(window, primaryMonitor)) {
                return;
            }
            if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
                fail(minecraft, "GLFW returned no monitors before timeout");
            }
            return;
        }

        for (int index = 0; index < monitors.limit(); index++) {
            if (selectMonitorCandidate(window, monitors.get(index))) {
                return;
            }
        }
        fail(minecraft, "requested external monitor or exact video mode was not found");
    }

    private boolean selectMonitorCandidate(final Window window, final long monitor) {
        String name = GLFW.glfwGetMonitorName(monitor);
        GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(monitor);
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=MONITOR handle={} name={} modes={}",
                monitor,
                name,
                summarizeModes(modes)
        );
        if (name == null
                || !name.toLowerCase(Locale.ROOT).contains(this.monitorName.toLowerCase(Locale.ROOT))) {
            return false;
        }
        VideoMode best = bestExactMode(monitor, modes);
        if (best == null) {
            return false;
        }
        this.targetMonitor = monitor;
        this.targetVideoMode = best;
        transition(GLFW.glfwGetWindowMonitor(window.handle()) == 0L
                ? Stage.MOVE_WINDOWED
                : Stage.EXIT_FULLSCREEN);
        return true;
    }

    private void selectCurrentWindow(final Minecraft minecraft, final Window window) {
        int[] framebufferWidth = new int[1];
        int[] framebufferHeight = new int[1];
        GLFW.glfwGetFramebufferSize(window.handle(), framebufferWidth, framebufferHeight);
        boolean ready = framebufferWidth[0] > 0
                && framebufferHeight[0] > 0
                && window.getWidth() == framebufferWidth[0]
                && window.getHeight() == framebufferHeight[0]
                && this.survivalGuardApplied;
        if (!ready) {
            if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
                fail(minecraft, "current benchmark window did not become stable before timeout");
            }
            return;
        }
        this.targetMonitor = GLFW.glfwGetWindowMonitor(window.handle());
        this.expectedFramebufferWidth = framebufferWidth[0];
        this.expectedFramebufferHeight = framebufferHeight[0];
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=WINDOW_READY monitor=current framebuffer={}x{} window={}x{} screen={}x{}",
                framebufferWidth[0],
                framebufferHeight[0],
                window.getWidth(),
                window.getHeight(),
                window.getScreenWidth(),
                window.getScreenHeight()
        );
        this.stage = Stage.RUNNING;
        this.segmentIndex = 0;
        startSegment();
    }

    private VideoMode bestExactMode(final long monitor, final GLFWVidMode.Buffer modes) {
        if (modes == null) {
            return null;
        }
        float[] scaleX = new float[1];
        float[] scaleY = new float[1];
        GLFW.glfwGetMonitorContentScale(monitor, scaleX, scaleY);
        if (!(scaleX[0] > 0.0f) || !(scaleY[0] > 0.0f)) {
            return null;
        }
        VideoMode best = null;
        for (int index = 0; index < modes.limit(); index++) {
            GLFWVidMode mode = modes.get(index);
            int framebufferWidth = Math.round(mode.width() * scaleX[0]);
            int framebufferHeight = Math.round(mode.height() * scaleY[0]);
            boolean scaledMatch = framebufferWidth == this.targetWidth
                    && framebufferHeight == this.targetHeight;
            // GLFW reports the built-in Retina display modes in backing
            // pixels, while some external HiDPI displays expose logical mode
            // dimensions that still need the monitor content scale. Accept
            // either representation and verify the actual framebuffer after
            // entering fullscreen.
            boolean backingPixelMatch = mode.width() == this.targetWidth
                    && mode.height() == this.targetHeight;
            if (!scaledMatch && !backingPixelMatch) {
                continue;
            }
            VideoMode candidate = new VideoMode(
                    mode.width(),
                    mode.height(),
                    mode.redBits(),
                    mode.greenBits(),
                    mode.blueBits(),
                    mode.refreshRate()
            );
            if (best == null || candidate.getRefreshRate() > best.getRefreshRate()) {
                best = candidate;
            }
        }
        return best;
    }

    private static String summarizeModes(final GLFWVidMode.Buffer modes) {
        if (modes == null) {
            return "[]";
        }
        List<String> summaries = new ArrayList<>();
        for (int index = 0; index < modes.limit(); index++) {
            GLFWVidMode mode = modes.get(index);
            summaries.add(mode.width() + "x" + mode.height() + "@" + mode.refreshRate());
        }
        return summaries.toString();
    }

    private void exitFullscreen(final Minecraft minecraft, final Window window) {
        if (GLFW.glfwGetWindowMonitor(window.handle()) == 0L) {
            transition(Stage.MOVE_WINDOWED);
            return;
        }
        if (window.isFullscreen()) {
            window.toggleFullScreen();
            window.updateFullscreenIfChanged();
        } else {
            fail(minecraft, "GLFW window is fullscreen while Minecraft window state is windowed");
            return;
        }
        if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
            fail(minecraft, "timed out leaving fullscreen");
        }
    }

    private void moveWindow(final Minecraft minecraft, final Window window) {
        int[] x = new int[1];
        int[] y = new int[1];
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetMonitorWorkarea(this.targetMonitor, x, y, width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            fail(minecraft, "target monitor has an invalid work area");
            return;
        }
        GLFW.glfwRestoreWindow(window.handle());
        GLFW.glfwSetWindowSize(window.handle(), Math.min(WINDOWED_WIDTH, width[0]), Math.min(WINDOWED_HEIGHT, height[0]));
        GLFW.glfwSetWindowPos(window.handle(), x[0] + 32, y[0] + 32);
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=MOVE_WINDOW monitor={} workarea={},{} {}x{}",
                this.targetVideoMode,
                x[0],
                y[0],
                width[0],
                height[0]
        );
        transition(Stage.WAIT_MOVED);
    }

    private void waitForMove(final Minecraft minecraft, final Window window) {
        Monitor bestMonitor = window.findBestMonitor();
        if (bestMonitor != null && bestMonitor.monitor() == this.targetMonitor) {
            transition(Stage.ENTER_FULLSCREEN);
            return;
        }
        if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
            fail(minecraft, "timed out moving the window to the external monitor");
        }
    }

    private void enterFullscreen(final Minecraft minecraft, final Window window) {
        Monitor bestMonitor = window.findBestMonitor();
        if (bestMonitor == null || bestMonitor.monitor() != this.targetMonitor) {
            fail(minecraft, "external monitor stopped being the window's best monitor");
            return;
        }
        window.setPreferredFullscreenVideoMode(Optional.of(this.targetVideoMode));
        if (!window.isFullscreen()) {
            window.toggleFullScreen();
        }
        window.updateFullscreenIfChanged();
        transition(Stage.WAIT_FRAMEBUFFER);
    }

    private void waitForFramebuffer(final Minecraft minecraft, final Window window) {
        if (isTargetFramebuffer(window) && this.survivalGuardApplied) {
            int[] framebufferWidth = new int[1];
            int[] framebufferHeight = new int[1];
            GLFW.glfwGetFramebufferSize(window.handle(), framebufferWidth, framebufferHeight);
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=WINDOW_READY monitor={} video_mode={} framebuffer={}x{} window={}x{} screen={}x{}",
                    GLFW.glfwGetMonitorName(this.targetMonitor),
                    this.targetVideoMode,
                    framebufferWidth[0],
                    framebufferHeight[0],
                    window.getWidth(),
                    window.getHeight(),
                    window.getScreenWidth(),
                    window.getScreenHeight()
            );
            this.stage = Stage.RUNNING;
            this.segmentIndex = 0;
            startSegment();
            return;
        }
        if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
            fail(minecraft, "timed out waiting for exact external 5K framebuffer");
        }
    }

    private boolean isTargetFramebuffer(final Window window) {
        if (!this.useCurrentWindow && GLFW.glfwGetWindowMonitor(window.handle()) != this.targetMonitor) {
            return false;
        }
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window.handle(), width, height);
        return width[0] == this.expectedFramebufferWidth
                && height[0] == this.expectedFramebufferHeight
                && window.getWidth() == this.expectedFramebufferWidth
                && window.getHeight() == this.expectedFramebufferHeight;
    }

    private void startSegment() {
        SpatialScalingMode mode = this.sequence.get(this.segmentIndex);
        MetalFxSpatialScaling.setBenchmarkOverride(mode);
        this.segmentFrame = 0;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=SEGMENT_START index={} total={} mode={} warmup={} measure={}",
                this.segmentIndex + 1,
                this.sequence.size(),
                mode,
                this.warmupFrames,
                this.measureFrames
        );
    }

    private void logSegmentEvent(final String event) {
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT={} index={} mode={} presented_frame={}",
                event,
                this.segmentIndex + 1,
                this.sequence.get(this.segmentIndex),
                this.segmentFrame
        );
    }

    private void fail(final Minecraft minecraft, final String reason) {
        Metallum.LOGGER.error("METALLUM_BENCHMARK EVENT=FAIL reason={}", reason);
        finish(minecraft);
    }

    private void finish(final Minecraft minecraft) {
        if (this.stage == Stage.STOPPING) {
            return;
        }
        this.stage = Stage.STOPPING;
        restoreSurvivalGuard(minecraft);
        minecraft.getWindow().setPreferredFullscreenVideoMode(this.originalFullscreenMode);
        MetalFxSpatialScaling.clearBenchmarkOverride();
        minecraft.stop();
    }

    private void maintainSurvivalGuard(final Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            this.survivalGuardFailure = "benchmark requires an integrated singleplayer server";
            return;
        }
        UUID playerId = minecraft.player.getUUID();
        if (this.guardedPlayerId != null && !this.guardedPlayerId.equals(playerId)) {
            this.survivalGuardFailure = "benchmark player identity changed";
            return;
        }
        this.guardedPlayerId = playerId;
        if (this.survivalGuardApplied && this.survivalGuardRefreshCountdown-- > 0) {
            return;
        }
        if (!this.survivalGuardTaskPending.compareAndSet(false, true)) {
            return;
        }
        this.survivalGuardRefreshCountdown = SURVIVAL_GUARD_REFRESH_FRAMES;
        server.executeIfPossible(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    this.survivalGuardFailure = "benchmark server player is unavailable";
                    return;
                }
                if (!this.survivalGuardApplied) {
                    this.originalInvulnerable = player.isInvulnerable();
                    this.originalHealth = player.getHealth();
                    this.originalFoodLevel = player.getFoodData().getFoodLevel();
                    this.originalSaturation = player.getFoodData().getSaturationLevel();
                    this.survivalGuardApplied = true;
                    Metallum.LOGGER.info(
                            "METALLUM_BENCHMARK EVENT=SURVIVAL_GUARD_APPLIED player={} original_health={}",
                            playerId,
                            this.originalHealth
                    );
                }
                player.setInvulnerable(true);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0f);
            } catch (RuntimeException exception) {
                this.survivalGuardFailure = "benchmark survival guard failed: " + exception.getClass().getSimpleName();
                Metallum.LOGGER.error("METALLUM_BENCHMARK survival guard failed", exception);
            } finally {
                this.survivalGuardTaskPending.set(false);
            }
        });
    }

    private void restoreSurvivalGuard(final Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        UUID playerId = this.guardedPlayerId;
        if (server == null || playerId == null) {
            return;
        }
        server.executeBlocking(() -> {
            // executeBlocking is ordered after any already queued guard task.
            // Checking inside the server task closes the shutdown race where
            // the initial guard was applied just after finish() began.
            if (!this.survivalGuardApplied) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }
            player.setInvulnerable(this.originalInvulnerable);
            player.setHealth(Math.min(this.originalHealth, player.getMaxHealth()));
            player.getFoodData().setFoodLevel(this.originalFoodLevel);
            player.getFoodData().setSaturation(this.originalSaturation);
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=SURVIVAL_GUARD_RESTORED player={} health={}",
                    playerId,
                    this.originalHealth
            );
        });
        this.survivalGuardApplied = false;
        this.survivalGuardTaskPending.set(false);
    }

    private void transition(final Stage next) {
        this.stage = next;
        this.stageFrames = 0;
    }

    private static int positiveInt(final String name, final int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String env(final String name, final String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
