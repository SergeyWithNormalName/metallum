package com.metallum.client.voxel;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Live, independently persisted settings for the optional L5 HUD. */
public final class VoxelPreviewSettings {
    private static final String FILE_NAME = "metallum-voxel-preview.properties";
    private static volatile State state;

    private VoxelPreviewSettings() {
    }

    public static State get() {
        State current = state;
        if (current == null) {
            synchronized (VoxelPreviewSettings.class) {
                current = state;
                if (current == null) {
                    current = load(configPath());
                    state = current;
                }
            }
        }
        return current;
    }

    public static void setMode(final VoxelPreviewMode mode) {
        State current = get();
        update(new State(mode, current.level(), current.slice()));
    }

    public static void setLevel(final int level) {
        State current = get();
        update(new State(current.mode(), level, current.slice()));
    }

    public static void setSlice(final int slice) {
        State current = get();
        update(new State(current.mode(), current.level(), slice));
    }

    static State load(final Path path) {
        if (!Files.isRegularFile(path)) {
            return State.defaults();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            VoxelPreviewMode mode = VoxelPreviewMode.valueOf(
                    properties.getProperty("mode", "off").strip().toUpperCase(Locale.ROOT)
            );
            int level = Integer.parseInt(properties.getProperty("level", "0").strip());
            int slice = Integer.parseInt(properties.getProperty("slice", "0").strip());
            return new State(mode, level, slice);
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn("Invalid L5 preview settings at {}; using Off: {}",
                    path, exception.getMessage());
            return State.defaults();
        }
    }

    static boolean save(final Path path, final State value) {
        Properties properties = new Properties();
        properties.setProperty("mode", value.mode().name().toLowerCase(Locale.ROOT));
        properties.setProperty("level", Integer.toString(value.level()));
        properties.setProperty("slice", Integer.toString(value.slice()));
        Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum L5 preview settings");
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save L5 preview settings at {}", path, exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void update(final State updated) {
        state = updated;
        save(configPath(), updated);
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public record State(VoxelPreviewMode mode, int level, int slice) {
        public State {
            if (mode == null || level < 0 || level > 2 || slice < 0 || slice > 383) {
                throw new IllegalArgumentException("Invalid L5 preview state");
            }
        }

        public static State defaults() {
            return new State(VoxelPreviewMode.OFF, 0, 0);
        }
    }
}
