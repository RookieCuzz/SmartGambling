package me.arthed.smartgambling.games.poker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PokerConfigurationAssetsTest {
    private static final Path SOURCE =
            Path.of("src/main/resources/machines/poker/poker.yml");
    private static final Path OVERLAY =
            Path.of("SmartGambling-CraftEngine/smartgambling-config-overlay/machines/poker/poker.yml");
    private static final Path CE_ITEMS =
            Path.of("SmartGambling-CraftEngine/smartgambling/configuration/items.yml");
    private static final Path MODELS = Path.of(
            "SmartGambling-CraftEngine/smartgambling/resourcepack/assets/smartgambling/models/item/casino");
    private static final Path TEXTURES = Path.of(
            "SmartGambling-CraftEngine/smartgambling/resourcepack/assets/smartgambling/textures/item/casino");

    @Test
    void bundledDeckContainsEveryCardAndAllNewSuitAssets() throws IOException {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(SOURCE.toFile());
        YamlConfiguration items = YamlConfiguration.loadConfiguration(CE_ITEMS.toFile());
        Set<String> configuredCards = new HashSet<>();
        Set<String> newAssets = new HashSet<>();

        for (PokerCard.Suit suit : PokerCard.Suit.values()) {
            for (PokerCard.Rank rank : PokerCard.Rank.values()) {
                String path = "Cards." + suit.name() + '.' + rank.name();
                String itemId = config.getString(path + ".craftEngineItem");
                assertNotNull(itemId, path);
                assertTrue(configuredCards.add(rank.name() + ':' + suit.name()), path);
                if (suit == PokerCard.Suit.DIAMONDS || suit == PokerCard.Suit.CLUBS) {
                    assertTrue(itemId.startsWith("smartgambling:poker_"), path);
                    assertTrue(newAssets.add(itemId), "duplicate CE item " + itemId);
                    assertTrue(items.isConfigurationSection("items." + itemId),
                            "missing CraftEngine item " + itemId);
                    String assetName = itemId.substring("smartgambling:".length());
                    assertTrue(Files.isRegularFile(MODELS.resolve(assetName + ".json")),
                            "missing model " + assetName);
                    assertTrue(Files.isRegularFile(TEXTURES.resolve(assetName + ".png")),
                            "missing texture " + assetName);
                }
            }
        }

        assertEquals(52, configuredCards.size());
        assertEquals(26, newAssets.size());
    }

    @Test
    void serverAndCraftEnginePokerConfigurationsStayIdentical() throws IOException {
        assertEquals(normalized(SOURCE), normalized(OVERLAY));
    }

    private static String normalized(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
    }
}
