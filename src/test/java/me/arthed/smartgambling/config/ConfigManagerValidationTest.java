package me.arthed.smartgambling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigManagerValidationTest {
    @Test
    void inventorySizeMustBeAChestMultipleWithinPaperLimits() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("GUI.size", 54);
        assertEquals(54, ConfigManager.requireInventorySize(config, "GUI.size"));

        for (int invalid : List.of(0, 8, 10, 55, 63)) {
            config.set("GUI.size", invalid);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigManager.requireInventorySize(config, "GUI.size")
            );
            assertTrue(error.getMessage().contains("'GUI.size'"));
        }
    }

    @Test
    void requiredSlotListsRejectEmptyWrongTypeAndOutOfRangeValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("GUI.spinButton", List.of(0, 53));
        assertEquals(
                List.of(0, 53),
                ConfigManager.requireSlots(config, "GUI.spinButton", 54, true)
        );

        config.set("GUI.spinButton", List.of());
        assertPathFailure(() -> ConfigManager.requireSlots(config, "GUI.spinButton", 54, true),
                "GUI.spinButton");
        config.set("GUI.spinButton", "49");
        assertPathFailure(() -> ConfigManager.requireSlots(config, "GUI.spinButton", 54, true),
                "GUI.spinButton");
        config.set("GUI.spinButton", List.of(54));
        assertPathFailure(() -> ConfigManager.requireSlots(config, "GUI.spinButton", 54, true),
                "GUI.spinButton[0]");
        config.set("GUI.spinButton", List.of(-1));
        assertPathFailure(() -> ConfigManager.requireSlots(config, "GUI.spinButton", 54, true),
                "GUI.spinButton[0]");
    }

    @Test
    void optionalSlotListsMayBeAbsentOrEmptyButStillValidateEntries() {
        YamlConfiguration config = new YamlConfiguration();
        assertEquals(List.of(), ConfigManager.requireSlots(config, "GUI.backButton", 45, false));

        config.set("GUI.backButton", List.of());
        assertEquals(List.of(), ConfigManager.requireSlots(config, "GUI.backButton", 45, false));

        config.set("GUI.backButton", List.of(45));
        assertPathFailure(() -> ConfigManager.requireSlots(config, "GUI.backButton", 45, false),
                "GUI.backButton[0]");
    }

    @Test
    void displayRowsRequireThreeInRangeIntegerSlots() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("GUI.displaySlots", List.of(List.of(12, 21, 30), List.of(13, 22, 31)));
        assertEquals(
                List.of(List.of(12, 21, 30), List.of(13, 22, 31)),
                ConfigManager.requireDisplaySlots(config, "GUI.displaySlots", 54)
        );

        config.set("GUI.displaySlots", List.of(List.of(12, 21)));
        assertPathFailure(() -> ConfigManager.requireDisplaySlots(config, "GUI.displaySlots", 54),
                "GUI.displaySlots[0]");
        config.set("GUI.displaySlots", List.of(List.of(12, 21, 999)));
        assertPathFailure(() -> ConfigManager.requireDisplaySlots(config, "GUI.displaySlots", 54),
                "GUI.displaySlots[0][2]");
        config.set("GUI.displaySlots", List.of("12,21,30"));
        assertPathFailure(() -> ConfigManager.requireDisplaySlots(config, "GUI.displaySlots", 54),
                "GUI.displaySlots[0]");
    }

    @Test
    void positiveValuesAndCumulativeWeightsFailClosedWithTheirPath() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("Items.seven.chance", 7);
        config.set("Rewards.win.multiplier", 2.5D);
        assertEquals(7, ConfigManager.requirePositiveInt(config, "Items.seven.chance"));
        assertEquals(2.5D,
                ConfigManager.requirePositiveFiniteDouble(config, "Rewards.win.multiplier"));
        assertEquals(2.5F,
                ConfigManager.requirePositiveFiniteFloat(config, "Rewards.win.multiplier"));
        assertEquals(12, ConfigManager.addPositiveWeight(5, 7, "Items.seven.chance"));

        config.set("Items.seven.chance", 0);
        assertPathFailure(() -> ConfigManager.requirePositiveInt(config, "Items.seven.chance"),
                "Items.seven.chance");
        config.set("Rewards.win.multiplier", Double.POSITIVE_INFINITY);
        assertPathFailure(
                () -> ConfigManager.requirePositiveFiniteDouble(config, "Rewards.win.multiplier"),
                "Rewards.win.multiplier"
        );
        config.set("Rewards.win.multiplier", 1.0E100D);
        assertPathFailure(
                () -> ConfigManager.requirePositiveFiniteFloat(config, "Rewards.win.multiplier"),
                "Rewards.win.multiplier"
        );
        assertPathFailure(
                () -> ConfigManager.addPositiveWeight(Integer.MAX_VALUE, 1, "Items.seven.chance"),
                "Items.seven.chance"
        );
    }

    @Test
    void pokerCardsAndActionsCannotShareInventorySlots() {
        ConfigManager.requireDisjointSlots(Map.of(
                "GUI.ownCardSlots", List.of(3, 5),
                "GUI.foldButton", List.of(36),
                "GUI.allInButton", List.of(43, 44)
        ));

        Map<String, List<Integer>> overlapping = new LinkedHashMap<>();
        overlapping.put("GUI.ownCardSlots", List.of(3, 5));
        overlapping.put("GUI.communityCardSlots", List.of(5, 11, 12));
        assertPathFailure(
                () -> ConfigManager.requireDisjointSlots(overlapping),
                "GUI.communityCardSlots"
        );
    }

    private static void assertPathFailure(Runnable action, String path) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(error.getMessage().contains("'" + path + "'"), error.getMessage());
    }
}
