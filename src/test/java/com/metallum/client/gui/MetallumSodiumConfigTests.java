package com.metallum.client.gui;

import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl;
import net.caffeinemc.mods.sodium.client.config.structure.BooleanOption;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionGroup;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.Collection;

/** Verifies the pinned Sodium 0.9.1 config structure exposed by Metallum. */
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
        OptionGroup lighting = page.groups().getFirst();
        require(lighting.options().size() == 1,
                "Metallum Lighting group must expose only the Advanced request at L2.5");

        Option option = lighting.options().getFirst();
        Field idField = Option.class.getDeclaredField("id");
        idField.setAccessible(true);
        Identifier id = (Identifier) idField.get(option);
        require(option instanceof BooleanOption
                        && id.equals(Identifier.fromNamespaceAndPath(
                        "metallum", "improved_lighting"
                )),
                "Metallum improved-lighting Sodium option is missing or has the wrong type/id");
        require(option.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum improved-lighting option must require a full game restart");

        Option hdrOption = findOption(page, idField, "hdr_enabled");
        require(hdrOption instanceof BooleanOption,
                "Metallum HDR-enabled Sodium option is missing or has the wrong type");
        require(hdrOption.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum HDR-enabled option must require a full game restart");
        Option voxelChecksum = findOption(page, idField, "voxel_debug_checksum");
        require(voxelChecksum instanceof BooleanOption,
                "Metallum L5 checksum Sodium option is missing or has the wrong type");
        require(voxelChecksum.getFlags().contains(OptionFlag.REQUIRES_GAME_RESTART.getId()),
                "Metallum L5 checksum option must require a full game restart");
        System.out.println("Metallum Sodium config registration tests passed");
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

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
