package me.arthed.smartgambling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ForcedSlotTestSettingsTest {
    @Test
    void parsesEnabledFlagAndPositiveExpiry() {
        YamlConfiguration config = configuration(true, 45);

        ForcedSlotTestSettings settings = ForcedSlotTestSettings.from(config);

        assertTrue(settings.enabled());
        assertEquals(45, settings.expiresSeconds());
        assertFalse(ForcedSlotTestSettings.DEFAULTS.enabled());
        assertEquals(120, ForcedSlotTestSettings.DEFAULTS.expiresSeconds());
    }

    @Test
    void readsBundledStyleDefaultsWhenAnExistingConfigOmitsTheSection() {
        YamlConfiguration defaults = configuration(false, 120);
        YamlConfiguration existingConfig = new YamlConfiguration();
        existingConfig.setDefaults(defaults);

        assertEquals(ForcedSlotTestSettings.DEFAULTS, ForcedSlotTestSettings.from(existingConfig));
    }

    @Test
    void rejectsMissingOrInvalidValuesWithTheirFullPaths() {
        YamlConfiguration missing = new YamlConfiguration();
        IllegalArgumentException missingError = assertThrows(
                IllegalArgumentException.class,
                () -> ForcedSlotTestSettings.from(missing)
        );
        assertTrue(missingError.getMessage().contains("Testing.forcedSlotResults.enabled"));

        YamlConfiguration wrongFlag = configuration(true, 120);
        wrongFlag.set("Testing.forcedSlotResults.enabled", "yes");
        IllegalArgumentException flagError = assertThrows(
                IllegalArgumentException.class,
                () -> ForcedSlotTestSettings.from(wrongFlag)
        );
        assertTrue(flagError.getMessage().contains("Testing.forcedSlotResults.enabled"));

        YamlConfiguration invalidExpiry = configuration(false, 0);
        IllegalArgumentException expiryError = assertThrows(
                IllegalArgumentException.class,
                () -> ForcedSlotTestSettings.from(invalidExpiry)
        );
        assertTrue(expiryError.getMessage().contains("Testing.forcedSlotResults.expiresSeconds"));
    }

    private static YamlConfiguration configuration(boolean enabled, int expiresSeconds) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("Testing.forcedSlotResults.enabled", enabled);
        config.set("Testing.forcedSlotResults.expiresSeconds", expiresSeconds);
        return config;
    }
}
