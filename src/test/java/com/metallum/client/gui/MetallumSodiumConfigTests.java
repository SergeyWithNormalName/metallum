package com.metallum.client.gui;

import com.metallum.client.lighting.reflection.WaterReflectionConfig;
import com.metallum.client.lighting.reflection.WaterReflectionMode;
import com.metallum.client.metalfx.MetalFxUpscalingMode;
import com.metallum.client.renderer.GlobalIlluminationMode;
import com.metallum.client.renderer.GraphicsPreset;
import com.metallum.client.renderer.RendererConfig;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl;
import net.caffeinemc.mods.sodium.client.config.structure.BooleanOption;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.config.structure.IntegerOption;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionGroup;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;

/** Verifies the restructured Sodium 0.9.1 config structure exposed by Metallum. */
public final class MetallumSodiumConfigTests {
    private MetallumSodiumConfigTests() {
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        ConfigBuilderImpl builder = new ConfigBuilderImpl(
                ignored -> new ConfigManager.ModMetadata("Metallum", "test"),
                "metallum"
        );
        new MetallumSodiumConfig().registerConfigLate(builder);
        Collection<ModOptions> built = builder.build();
        require(built.size() == 1, "Metallum registered an unexpected Sodium config count");

        ModOptions options = built.iterator().next();
        require(options.configId().equals("metallum") && options.pages().size() == 1,
                "Metallum Sodium page was not registered under its own config");
        require(options.pages().getFirst() instanceof OptionPage,
                "Metallum registered an external page instead of its option page");
        OptionPage page = (OptionPage) options.pages().getFirst();
        require(!page.groups().isEmpty(), "Metallum Sodium page has no option groups");
        require(page.groups().size() == 6, "Metallum Sodium page should have exactly 6 structured groups");

        Field idField = Option.class.getDeclaredField("id");
        idField.setAccessible(true);

        // Group 1: Summary / Quick Settings
        OptionGroup summary = page.groups().get(0);
        require(summary.options().size() == 5,
                "Metallum Summary group must expose Preset, Style, Upscaling, HDR, and Resolution Overlay");

        Option graphicsPreset = findOption(page, idField, "graphics_preset");
        require(graphicsPreset instanceof EnumOption, "graphics_preset must be an EnumOption");
        require(((EnumOption<?>) graphicsPreset).getApplyHook() != null,
                "graphics_preset must have apply hook for resynchronizing coupled bindings");

        Option visualStyle = findOption(page, idField, "visual_style");
        require(visualStyle instanceof EnumOption,
                "Metallum visual_style Sodium option is missing or has the wrong type");
        require(visualStyle.getFlags() == null || !visualStyle.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum visual_style option must NOT require a game restart");

        Option metalfxUpscaling = findOption(page, idField, "metalfx_upscaling");
        require(metalfxUpscaling instanceof EnumOption,
                "Metallum metalfx_upscaling Sodium option is missing or has the wrong type");
        require(MetalFxUpscalingMode.values().length == 4,
                "MetalFxUpscalingMode must expose exactly 4 modes: OFF, SPATIAL, TEMPORAL, TEMPORAL_FI");

        Option hdrOption = findOption(page, idField, "hdr_enabled");
        require(hdrOption instanceof BooleanOption,
                "Metallum HDR-enabled Sodium option is missing or has the wrong type");
        require(hdrOption.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum HDR-enabled option must require a full game restart");

        Option resolutionOverlay = findOption(page, idField, "metalfx_resolution_overlay");
        require(resolutionOverlay instanceof BooleanOption,
                "Metallum metalfx_resolution_overlay Sodium option is missing or has the wrong type");

        // Group 2: Lighting & GI
        Option option = findOption(page, idField, "improved_lighting");
        Identifier id = (Identifier) idField.get(option);
        require(option instanceof BooleanOption
                        && id.equals(Identifier.fromNamespaceAndPath(
                        "metallum", "improved_lighting"
                )),
                "Metallum improved-lighting Sodium option is missing or has the wrong type/id");
        require(option.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum improved-lighting option must require a full game restart");

        Option lightingPreset = findOption(page, idField, "lighting_preset");
        require(lightingPreset instanceof EnumOption,
                "Metallum lighting-preset Sodium option is missing or has the wrong type");
        require(lightingPreset.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum lighting-preset option must require a full game restart");

        Option globalIllumination = findOption(page, idField, "global_illumination");
        requireRestartBoolean(globalIllumination, "production global illumination");
        require(((BooleanOption) option).getApplyHook() != null
                        && ((BooleanOption) globalIllumination).getApplyHook() != null,
                "Advanced/GI coupling must resynchronize Sodium values after Apply");

        Option giTransportDebug = findOption(page, idField, "gi_g4_debug_hud");
        requireRestartBoolean(giTransportDebug, "G4 transport debug HUD");
        require(((BooleanOption) giTransportDebug).getApplyHook() != null,
                "GI/G4 mutual exclusion must resynchronize Sodium values after Apply");

        RendererConfig dynamic = MetallumSodiumConfig.applyGlobalIlluminationSelection(
                RendererConfig.defaults(),
                true
        );
        require(dynamic.globalIllumination() == GlobalIlluminationMode.DYNAMIC
                        && dynamic.improvedLighting(),
                "Enabling production GI must also enable its Advanced Lighting prerequisite");
        RendererConfig advancedDisabled = MetallumSodiumConfig.applyImprovedLightingSelection(
                dynamic,
                false
        );
        require(advancedDisabled.globalIllumination() == GlobalIlluminationMode.OFF
                        && !advancedDisabled.improvedLighting(),
                "Disabling Advanced Lighting must fail closed to GI_OFF");

        // Group 3: Shadows
        Option shadows = findOption(page, idField, "experimental_shadows");
        requireRestartBoolean(shadows, "experimental shadows");

        // Group 4: Reflections (Merged into Metallum)
        Option reflectionWaterMode = findOption(page, idField, "water_reflection_mode");
        require(reflectionWaterMode instanceof EnumOption,
                "water_reflection_mode option is missing or has the wrong type");

        Option cloudReflections = findOption(page, idField, "cloud_reflections");
        require(cloudReflections instanceof BooleanOption,
                "cloud-reflection Sodium option is missing or has the wrong type");

        requireRestartBoolean(
                findOption(page, idField, "water_reflection_face_aware_appearance"),
                "face-aware TOP/SIDE/BOTTOM appearance"
        );
        requireRestartBoolean(
                findOption(page, idField, "water_reflection_first_surface_integration"),
                "first-surface-biased local integration"
        );
        requireRestartBoolean(
                findOption(page, idField, "water_reflection_representation_confidence"),
                "representation confidence"
        );

        // Group 5: Post-Processing & HDR
        require(findOption(page, idField, "source_encoding") instanceof EnumOption,
                "source_encoding must be an EnumOption");
        require(findOption(page, idField, "hdr_strength") instanceof IntegerOption,
                "hdr_strength must be an IntegerOption");
        require(findOption(page, idField, "bloom_strength") instanceof IntegerOption,
                "bloom_strength must be an IntegerOption");
        require(findOption(page, idField, "god_ray_intensity") instanceof IntegerOption,
                "god_ray_intensity must be an IntegerOption");

        // Group 6: Advanced & Diagnostics
        Option voxelChecksum = findOption(page, idField, "voxel_debug_checksum");
        require(voxelChecksum instanceof BooleanOption,
                "Metallum L5 checksum Sodium option is missing or has the wrong type");
        require(voxelChecksum.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum L5 checksum option must require a full game restart");
        require(findOption(page, idField, "voxel_preview_mode") instanceof EnumOption,
                "Metallum L5 preview mode is missing or has the wrong type");
        require(findOption(page, idField, "voxel_preview_level") instanceof IntegerOption,
                "Metallum L5 preview level is missing or has the wrong type");
        require(findOption(page, idField, "voxel_preview_slice") instanceof IntegerOption,
                "Metallum L5 preview slice is missing or has the wrong type");

        // Test GraphicsPreset application and detection
        GraphicsPreset.apply(GraphicsPreset.PERFORMANCE);
        require(GraphicsPreset.detect() == GraphicsPreset.PERFORMANCE,
                "Performance preset must detect after apply");
        GraphicsPreset.apply(GraphicsPreset.BALANCED);
        require(GraphicsPreset.detect() == GraphicsPreset.BALANCED,
                "Balanced preset must detect after apply");
        GraphicsPreset.apply(GraphicsPreset.ULTRA);
        require(GraphicsPreset.detect() == GraphicsPreset.ULTRA,
                "Ultra preset must detect after apply");

        // Custom override detection: tweaking an option moves preset to CUSTOM
        WaterReflectionConfig.setMode(WaterReflectionMode.OFF);
        require(GraphicsPreset.detect() == GraphicsPreset.CUSTOM,
                "Custom preset must detect when child option deviates from Ultra");

        // Restore balanced
        GraphicsPreset.apply(GraphicsPreset.BALANCED);

        // Verify blocked Sodium options filter set
        require(SodiumOptionFilter.isBlocked(
                Identifier.fromNamespaceAndPath("sodium", "performance.use_no_error_context")),
                "use_no_error_context must be blocked");
        require(SodiumOptionFilter.isBlocked(
                Identifier.fromNamespaceAndPath("sodium", "general.fullscreen_resolution")),
                "fullscreen_resolution must be blocked");
        require(SodiumOptionFilter.isBlocked(
                Identifier.fromNamespaceAndPath("sodium", "general.graphics_api")),
                "graphics_api must be blocked");
        require(SodiumOptionFilter.isBlocked(
                Identifier.fromNamespaceAndPath("sodium", "quality.pixel_filtering_mode")),
                "pixel_filtering_mode must be blocked");

        testCoupledApplyResynchronization();

        System.out.println("Metallum Sodium config registration tests passed");
    }

    private static void testCoupledApplyResynchronization() {
        boolean[] persisted = {false, false, false};
        ConfigBuilderImpl builder = new ConfigBuilderImpl(
                ignored -> new ConfigManager.ModMetadata("Coupled test", "test"),
                "coupled_test"
        );
        builder.registerOwnModOptions()
                .setName("Coupled test")
                .addPage(builder.createOptionPage()
                        .setName(Component.literal("Coupled test"))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.literal("Coupled test"))
                                .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(
                                                "coupled_test", "advanced"
                                        ))
                                        .setStorageHandler(() -> { })
                                        .setName(Component.literal("Advanced"))
                                        .setTooltip(Component.literal("Advanced tooltip"))
                                        .setDefaultValue(false)
                                        .setBinding(value -> {
                                             persisted[0] = value;
                                             if (!value) {
                                                 persisted[1] = false;
                                             }
                                         }, () -> persisted[0])
                                         .setApplyHook(
                                                 MetallumSodiumConfig::resynchronizeCoupledSodiumBindings
                                         ))
                                 .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(
                                                 "coupled_test", "gi"
                                         ))
                                         .setStorageHandler(() -> { })
                                         .setName(Component.literal("GI"))
                                         .setTooltip(Component.literal("GI tooltip"))
                                         .setDefaultValue(false)
                                         .setBinding(value -> {
                                             persisted[1] = value;
                                             if (value) {
                                                 persisted[0] = true;
                                                 persisted[2] = false;
                                             }
                                         }, () -> persisted[1])
                                         .setApplyHook(
                                                 MetallumSodiumConfig::resynchronizeCoupledSodiumBindings
                                         ))
                                 .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(
                                                 "coupled_test", "debug"
                                         ))
                                         .setStorageHandler(() -> { })
                                         .setName(Component.literal("Debug"))
                                         .setTooltip(Component.literal("Debug tooltip"))
                                         .setDefaultValue(false)
                                         .setBinding(value -> {
                                             persisted[2] = value;
                                             if (value) {
                                                 persisted[1] = false;
                                             }
                                         }, () -> persisted[2])
                                         .setApplyHook(
                                                 MetallumSodiumConfig::resynchronizeCoupledSodiumBindings
                                         ))));

        Config config = new Config(new ArrayList<>(builder.build()));
        BooleanOption advanced = (BooleanOption) config.getOption(
                Identifier.fromNamespaceAndPath("coupled_test", "advanced")
        );
        BooleanOption gi = (BooleanOption) config.getOption(
                Identifier.fromNamespaceAndPath("coupled_test", "gi")
        );
        BooleanOption debug = (BooleanOption) config.getOption(
                Identifier.fromNamespaceAndPath("coupled_test", "debug")
        );

        gi.modifyValue(true);
        config.applyAllOptions();
        require(persisted[0] && persisted[1] && !persisted[2]
                        && Boolean.TRUE.equals(advanced.getAppliedValue())
                        && Boolean.TRUE.equals(gi.getAppliedValue())
                        && Boolean.FALSE.equals(debug.getAppliedValue())
                        && !config.anyOptionChanged(),
                "GI Apply left Sodium controls stale after enabling its prerequisite");

        debug.modifyValue(true);
        config.applyAllOptions();
        require(persisted[0] && !persisted[1] && persisted[2]
                        && Boolean.TRUE.equals(advanced.getAppliedValue())
                        && Boolean.FALSE.equals(gi.getAppliedValue())
                        && Boolean.TRUE.equals(debug.getAppliedValue())
                        && !config.anyOptionChanged(),
                "G4 Apply left the mutually exclusive GI control stale");

        gi.modifyValue(true);
        config.applyAllOptions();
        advanced.modifyValue(false);
        config.applyAllOptions();
        require(!persisted[0] && !persisted[1] && !persisted[2]
                        && Boolean.FALSE.equals(advanced.getAppliedValue())
                        && Boolean.FALSE.equals(gi.getAppliedValue())
                        && Boolean.FALSE.equals(debug.getAppliedValue())
                        && !config.anyOptionChanged(),
                "Advanced Apply left the dependent GI control stale after disabling it");
    }

    private static Option findOption(
            final OptionPage page,
            final Field idField,
            final String path
    ) throws IllegalAccessException {
        Identifier expected = Identifier.fromNamespaceAndPath("metallum", path);
        for (OptionGroup group : page.groups()) {
            for (Option candidate : group.options()) {
                if (expected.equals(idField.get(candidate))) {
                    return candidate;
                }
            }
        }
        throw new AssertionError("Missing Metallum Sodium option " + expected);
    }

    private static void requireRestartBoolean(final Option option, final String name) {
        require(option instanceof BooleanOption,
                "Metallum " + name + " option is missing or has the wrong type");
        require(option.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum " + name + " must require a full game restart");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
