// Decompiled with: FernFlower
// Class Version: 17
package me.arthed.smartgambling.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.blackjack.PlayingCard;
import me.arthed.smartgambling.games.common.inventories.ConfirmGameInventory;
import me.arthed.smartgambling.games.common.inventories.MoneyInventory;
import me.arthed.smartgambling.games.common.inventories.SubInventory;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.animation.ItemAnimation;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.games.common.sound.SoundElement;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.games.slots.SlotMachine;
import me.arthed.smartgambling.games.slots.objects.SlotItem;
import me.arthed.smartgambling.games.slots.objects.rewards.ExactMatchReward;
import me.arthed.smartgambling.games.slots.objects.rewards.Reward;
import me.arthed.smartgambling.games.slots.objects.rewards.RowReward;
import me.arthed.smartgambling.handlers.PlaceholderMessages;
import me.arthed.smartgambling.creation.CreationGuideSettings;
import me.arthed.smartgambling.utils.MachineTypeIds;
import me.arthed.smartgambling.integrations.CraftEngineItemResolver;
import me.arthed.smartgambling.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public class ConfigManager {
    private static final int MIN_CHEST_INVENTORY_SIZE = 9;
    private static final int MAX_CHEST_INVENTORY_SIZE = 54;

    public HashMap<String, String> messages;
    public List<String> helpMenu;
    private volatile PlaceholderMessages placeholderMessages = PlaceholderMessages.empty();
    private volatile CreationGuideSettings creationGuideSettings = CreationGuideSettings.DEFAULTS;

    static int requireInventorySize(ConfigurationSection config, String path) {
        Objects.requireNonNull(config, "config");
        if (!config.isInt(path)) {
            throw validation(path, "must be an integer inventory size");
        }
        int size = config.getInt(path);
        if (size < MIN_CHEST_INVENTORY_SIZE || size > MAX_CHEST_INVENTORY_SIZE || size % 9 != 0) {
            throw validation(path, "must be a multiple of 9 between 9 and 54 (was " + size + ")");
        }
        return size;
    }

    static int requireSlot(ConfigurationSection config, String path, int inventorySize) {
        requireValidInventorySizeArgument(inventorySize);
        if (!config.isInt(path)) {
            throw validation(path, "must be an integer slot");
        }
        int slot = config.getInt(path);
        validateSlot(slot, path, inventorySize);
        return slot;
    }

    static List<Integer> requireSlots(
            ConfigurationSection config,
            String path,
            int inventorySize,
            boolean required
    ) {
        requireValidInventorySizeArgument(inventorySize);
        if (!config.contains(path)) {
            if (required) {
                throw validation(path, "is required and must contain at least one slot");
            }
            return List.of();
        }
        if (!config.isList(path)) {
            throw validation(path, "must be a list of integer slots");
        }
        List<?> raw = config.getList(path);
        List<Integer> slots = validateSlotValues(raw, path, inventorySize);
        if (required && slots.isEmpty()) {
            throw validation(path, "must contain at least one slot");
        }
        return slots;
    }

    static List<List<Integer>> requireDisplaySlots(
            ConfigurationSection config,
            String path,
            int inventorySize
    ) {
        requireValidInventorySizeArgument(inventorySize);
        if (!config.isList(path)) {
            throw validation(path, "is required and must be a list of slot rows");
        }
        List<?> rawRows = config.getList(path);
        if (rawRows == null || rawRows.isEmpty()) {
            throw validation(path, "must contain at least one slot row");
        }
        List<List<Integer>> rows = new ArrayList<>(rawRows.size());
        for (int rowIndex = 0; rowIndex < rawRows.size(); rowIndex++) {
            Object rawRow = rawRows.get(rowIndex);
            if (!(rawRow instanceof List<?> row)) {
                throw validation(path + '[' + rowIndex + ']', "must be a list of integer slots");
            }
            List<Integer> validated = validateSlotValues(
                    row,
                    path + '[' + rowIndex + ']',
                    inventorySize
            );
            if (validated.size() < 3) {
                throw validation(path + '[' + rowIndex + ']', "must contain at least three slots");
            }
            rows.add(validated);
        }
        return List.copyOf(rows);
    }

    static int requirePositiveInt(ConfigurationSection config, String path) {
        if (!config.isInt(path)) {
            throw validation(path, "must be a positive integer");
        }
        int value = config.getInt(path);
        if (value <= 0) {
            throw validation(path, "must be positive (was " + value + ")");
        }
        return value;
    }

    static int requireNonNegativeInt(ConfigurationSection config, String path) {
        if (!config.isInt(path)) {
            throw validation(path, "must be a non-negative integer");
        }
        int value = config.getInt(path);
        if (value < 0) {
            throw validation(path, "must not be negative (was " + value + ")");
        }
        return value;
    }

    static double requirePositiveFiniteDouble(ConfigurationSection config, String path) {
        if (!config.isDouble(path) && !config.isInt(path) && !config.isLong(path)) {
            throw validation(path, "must be a positive finite number");
        }
        double value = config.getDouble(path);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw validation(path, "must be positive and finite (was " + value + ")");
        }
        return value;
    }

    static float requirePositiveFiniteFloat(ConfigurationSection config, String path) {
        double value = requirePositiveFiniteDouble(config, path);
        if (value > Float.MAX_VALUE) {
            throw validation(path, "must not exceed " + Float.MAX_VALUE + " (was " + value + ")");
        }
        float result = (float) value;
        if (!Float.isFinite(result) || result <= 0.0F) {
            throw validation(path, "cannot be represented as a positive finite float");
        }
        return result;
    }

    static int addPositiveWeight(int total, int weight, String path) {
        if (weight <= 0) {
            throw validation(path, "must be positive (was " + weight + ")");
        }
        try {
            return Math.addExact(total, weight);
        } catch (ArithmeticException exception) {
            throw validation(path, "makes the cumulative weight exceed the integer limit", exception);
        }
    }

    private static List<Integer> configuredSlots(
            ConfigurationSection config,
            String basePath,
            int inventorySize,
            boolean required
    ) {
        String slotsPath = basePath + ".slots";
        String slotPath = basePath + ".slot";
        boolean hasSlots = config.contains(slotsPath);
        boolean hasSlot = config.contains(slotPath);
        if (hasSlots && hasSlot) {
            throw validation(basePath, "must use either 'slot' or 'slots', not both");
        }
        if (hasSlots) {
            return requireSlots(config, slotsPath, inventorySize, required);
        }
        if (hasSlot) {
            return List.of(requireSlot(config, slotPath, inventorySize));
        }
        if (required) {
            throw validation(basePath, "must define 'slot' or a non-empty 'slots' list");
        }
        return List.of();
    }

    private static ConfigurationSection requireSection(ConfigurationSection config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            throw validation(path, "must be a configuration section");
        }
        return section;
    }

    private static List<Integer> validateSlotValues(List<?> raw, String path, int inventorySize) {
        if (raw == null) {
            throw validation(path, "must be a list of integer slots");
        }
        List<Integer> slots = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Object value = raw.get(index);
            if (!(value instanceof Number number)) {
                throw validation(path + '[' + index + ']', "must be an integer slot");
            }
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                    || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
                throw validation(path + '[' + index + ']', "must be an integer slot (was " + value + ")");
            }
            int slot = (int) numeric;
            validateSlot(slot, path + '[' + index + ']', inventorySize);
            slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private static void validateSlot(int slot, String path, int inventorySize) {
        if (slot < 0 || slot >= inventorySize) {
            throw validation(
                    path,
                    "must be between 0 and " + (inventorySize - 1) + " (was " + slot + ')'
            );
        }
    }

    private static void requireValidInventorySizeArgument(int inventorySize) {
        if (inventorySize < MIN_CHEST_INVENTORY_SIZE
                || inventorySize > MAX_CHEST_INVENTORY_SIZE
                || inventorySize % 9 != 0) {
            throw new IllegalArgumentException("inventorySize must be a multiple of 9 between 9 and 54");
        }
    }

    private static void requirePopulatedSlots(Inventory inventory, List<Integer> slots, String path) {
        for (int index = 0; index < slots.size(); index++) {
            int slot = slots.get(index);
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                throw validation(
                        path + '[' + index + ']',
                        "slot " + slot + " must contain an item configured under the matching Items section"
                );
            }
        }
    }

    private static IllegalArgumentException validation(String path, String message) {
        return new IllegalArgumentException("Invalid configuration at '" + path + "': " + message);
    }

    private static IllegalArgumentException validation(String path, String message, Throwable cause) {
        return new IllegalArgumentException("Invalid configuration at '" + path + "': " + message, cause);
    }

    public void load() {
        this.load(SmartGambling.getInstance().getConfig());
    }

    /** Parses a complete runtime from the supplied main configuration. */
    public void load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        YamlConfiguration bundledDefaults = loadBundledMainConfiguration();
        config.setDefaults(bundledDefaults);
        CreationGuideSettings loadedCreationGuide = CreationGuideSettings.from(config);
        HashMap<String, String> loadedMessages = new HashMap<>();
        String prefix = Objects.requireNonNull(config.getString("Messages.prefix"), "Messages.prefix");
        var defaultMessageSection = Objects.requireNonNull(
                bundledDefaults.getConfigurationSection("Messages"),
                "Bundled Messages configuration section"
        );
        java.util.LinkedHashSet<String> messageKeys = new java.util.LinkedHashSet<>(
                defaultMessageSection.getKeys(false));
        var configuredMessageSection = config.getConfigurationSection("Messages");
        if (configuredMessageSection != null) {
            messageKeys.addAll(configuredMessageSection.getKeys(false));
        }

        for(String key : messageKeys) {
            if (!key.equals("prefix")) {
                String rawMessage = Objects.requireNonNull(
                        config.getString("Messages." + key),
                        "Messages." + key
                );
                loadedMessages.put(
                        key,
                        ChatColor.translateAlternateColorCodes('&', rawMessage.replace("%prefix%", prefix))
                );
            }
        }
        this.messages = loadedMessages;
        this.creationGuideSettings = loadedCreationGuide;

        List<String> loadedHelpMenu = config.getStringList("helpMenu");

        for(int i = 0; i < loadedHelpMenu.size(); ++i) {
            loadedHelpMenu.set(i, ChatColor.translateAlternateColorCodes('&', loadedHelpMenu.get(i)));
        }
        this.helpMenu = loadedHelpMenu;

        SmartGambling.getInstance().customSounds = new HashMap();

        var soundsSection = Objects.requireNonNull(config.getConfigurationSection("Sounds"), "Sounds section");
        for(String key : soundsSection.getKeys(false)) {
            List<SoundElement> elements = new ArrayList();

            for(String elementRaw : config.getStringList("Sounds." + key)) {
                String[] propertiesRaw = elementRaw.split(" ");
                if (propertiesRaw.length != 4) {
                    throw new IllegalArgumentException(
                            "Invalid custom sound entry at Sounds." + key + ": '" + elementRaw + "'"
                    );
                }
                try {
                    Sound sound = Sound.valueOf(propertiesRaw[0]);
                    float volume = Float.parseFloat(propertiesRaw[1]);
                    float pitch = Float.parseFloat(propertiesRaw[2]);
                    int delay = Integer.parseInt(propertiesRaw[3]);
                    elements.add(new SoundElement(sound, volume, pitch, delay));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            "Invalid custom sound entry at Sounds." + key + ": '" + elementRaw + "'",
                            exception
                    );
                }
            }

            SmartGambling.getInstance().customSounds.put(key, new CustomSound(elements));
        }

        SmartGambling.getInstance().chairItem = this.loadItem(config, "Chair");
        SmartGambling.getInstance().chairOffset = new double[]{config.getDouble("Chair.Offset.x"), config.getDouble("Chair.Offset.y"), config.getDouble("Chair.Offset.z")};
        SmartGambling.getInstance().machineTypes.clear();
        this.loadMoneyInventory();
        this.loadConfirmInventory();
        this.loadSlotMachines();
        this.loadJackpotMachine();
        this.loadCrashMachine();
        this.loadBlackJack();
        this.loadPlaceholdersConfig();
    }

    private YamlConfiguration loadBundledMainConfiguration() {
        try (var stream = SmartGambling.getInstance().getResource("config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("插件 JAR 中缺少 config.yml");
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return defaults;
        } catch (InvalidConfigurationException | IOException exception) {
            throw new IllegalStateException("无法读取插件 JAR 中的默认 config.yml", exception);
        }
    }

    public CreationGuideSettings getCreationGuideSettings() {
        return this.creationGuideSettings;
    }

    public void applyCreationGuideSettings(CreationGuideSettings settings) {
        this.creationGuideSettings = Objects.requireNonNull(settings, "settings");
    }

    public void loadPlaceholdersConfig() {
        File confirmInventoryFile = new File(SmartGambling.getInstance().getDataFolder() + "/placeholders.yml");
        if (!confirmInventoryFile.exists()) {
            SmartGambling.getInstance().saveResource("placeholders.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(confirmInventoryFile);
        } catch (InvalidConfigurationException | IOException var4) {
            throw new IllegalStateException("Could not load placeholders.yml", var4);
        }

        PlaceholderMessages loaded = new PlaceholderMessages(
                this.placeholderMessage(config, "blackjack.noPlayers"),
                this.placeholderMessage(config, "blackjack.choosingBet"),
                this.placeholderMessage(config, "blackjack.waitingForOpponent"),
                this.placeholderMessage(config, "blackjack.playing"),
                this.placeholderMessage(config, "jackpot.cooldown"),
                this.placeholderMessage(config, "jackpot.active"),
                this.placeholderMessage(config, "jackpot.finish"),
                this.placeholderMessage(config, "crash.cooldown"),
                this.placeholderMessage(config, "crash.betting"),
                this.placeholderMessage(config, "crash.crashing")
        );
        this.placeholderMessages = loaded;
    }

    public PlaceholderMessages getPlaceholderMessages() {
        return this.placeholderMessages;
    }

    public void applyPlaceholderMessages(PlaceholderMessages messages) {
        this.placeholderMessages = Objects.requireNonNull(messages, "messages");
    }

    private String placeholderMessage(FileConfiguration config, String path) {
        String value = Objects.requireNonNull(config.getString(path), path);
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public void loadConfirmInventory() {
        File confirmInventoryFile = new File(SmartGambling.getInstance().getDataFolder() + "/machines/confirmInventory.yml");
        if (!confirmInventoryFile.exists()) {
            SmartGambling.getInstance().saveResource("machines/confirmInventory.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(confirmInventoryFile);
        } catch (InvalidConfigurationException | IOException var9) {
            throw new IllegalStateException("Could not load machines/confirmInventory.yml", var9);
        }

        int guiSize = requireInventorySize(config, "GUI.size");
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, guiSize);
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.title")));
        int confirmButton = requireSlot(config, "GUI.confirmButton", guiSize);
        int declineButton = requireSlot(config, "GUI.declineButton", guiSize);
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
        requirePopulatedSlots(baseInventory, List.of(confirmButton), "GUI.confirmButton");
        requirePopulatedSlots(baseInventory, List.of(declineButton), "GUI.declineButton");
        SmartGambling.getInstance().confirmGameInventory = new ConfirmGameInventory(baseInventory, inventoryTitle, new InventoryAnimations(animations, dependentAnimations), confirmButton, declineButton);
    }

    public void loadBlackJack() {
        File blackjackMachineFile = new File(SmartGambling.getInstance().getDataFolder() + "/machines/blackjack/blackjack.yml");
        if (!blackjackMachineFile.exists()) {
            SmartGambling.getInstance().saveResource("machines/blackjack/blackjack.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(blackjackMachineFile);
        } catch (InvalidConfigurationException | IOException var25) {
            throw new IllegalStateException("Could not load machines/blackjack/blackjack.yml", var25);
        }

        int guiSize = requireInventorySize(config, "GUI.size");
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, guiSize);
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.title")));
        List<Integer> cardSlots = requireSlots(config, "GUI.cardSlots", guiSize, true);
        List<Integer> opponentCardSlots = requireSlots(config, "GUI.opponentCardSlots", guiSize, true);
        List<Integer> placeholderSlots = requireSlots(config, "GUI.placeholderSlots", guiSize, true);
        Button hitButton = new Button(new HashSet(requireSlots(config, "GUI.hitButton", guiSize, true)));
        Button standButton = new Button(new HashSet(requireSlots(config, "GUI.standButton", guiSize, true)));
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
        requirePopulatedSlots(baseInventory, List.copyOf(hitButton.getSlots()), "GUI.hitButton");
        requirePopulatedSlots(baseInventory, List.copyOf(standButton.getSlots()), "GUI.standButton");
        ItemStack tableItem = this.loadItem(config, "Table");
        double[] entityOffset = new double[]{config.getDouble("Table.Offset.x"), config.getDouble("Table.Offset.y"), config.getDouble("Table.Offset.z")};
        double[] chair1Offset = new double[]{config.getDouble("Table.ChairOffset1.x"), config.getDouble("Table.ChairOffset1.y"), config.getDouble("Table.ChairOffset1.z")};
        double[] chair2Offset = new double[]{config.getDouble("Table.ChairOffset2.x"), config.getDouble("Table.ChairOffset2.y"), config.getDouble("Table.ChairOffset2.z")};
        NavigableMap<Integer, PlayingCard> cards = new TreeMap();
        int totalChance = 0;
        ConfigurationSection cardsSection = requireSection(config, "Cards");
        if (cardsSection.getKeys(false).isEmpty()) {
            throw validation("Cards", "must contain at least one card");
        }

        for(String key : cardsSection.getKeys(false)) {
            String cardPath = "Cards." + key;
            List<String> craftEngineItems = config.getStringList(cardPath + ".craftEngineItems");
            if (craftEngineItems.isEmpty()) {
                throw new IllegalArgumentException("Missing CraftEngine card IDs at '" + cardPath + ".craftEngineItems'.");
            }
            ItemStack[] items = new ItemStack[craftEngineItems.size()];

            for(int i = 0; i < items.length; ++i) {
                items[i] = this.loadCraftEngineItem(config, cardPath, craftEngineItems.get(i), i);
            }

            int chance = requirePositiveInt(config, cardPath + ".chance");
            int value = requirePositiveInt(config, cardPath + ".value");
            totalChance = addPositiveWeight(totalChance, chance, cardPath + ".chance");
            cards.put(totalChance, new PlayingCard(value, items));
        }

        ItemStack cardBack = this.loadItem(config, "CardBack");
        String inventoryTitleStand = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleStand")));
        String inventoryTitleLost = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleLost")));
        String inventoryTitleWin = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleWin")));
        String inventoryTitleDraw = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleDraw")));
        SmartGambling.getInstance().blackJack = new BlackJack(tableItem, entityOffset, chair1Offset, chair2Offset, baseInventory, new InventoryAnimations(animations, dependentAnimations), inventoryTitle, inventoryTitleStand, inventoryTitleLost, inventoryTitleWin, inventoryTitleDraw, hitButton, standButton, cardBack, cardSlots, opponentCardSlots, placeholderSlots, cards, totalChance);
        SmartGambling.getInstance().registerMachineType(
                MachineTypeIds.BLACKJACK,
                SmartGambling.getInstance().blackJack
        );
    }

    public void loadCrashMachine() {
        File jackpotMachineFile = new File(SmartGambling.getInstance().getDataFolder() + "/machines/crash/crash.yml");
        if (!jackpotMachineFile.exists()) {
            SmartGambling.getInstance().saveResource("machines/crash/crash.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(jackpotMachineFile);
        } catch (InvalidConfigurationException | IOException var33) {
            throw new IllegalStateException("Could not load machines/crash/crash.yml", var33);
        }

        int betGuiSize = requireInventorySize(config, "BetGUI.size");
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, betGuiSize);
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.title")));
        String inventoryTitleAfterBet = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.titleAfterBet")));
        ItemStack basePlayerHead = this.loadItem(config, "BetGUI.SpecialItems.PlayerHeads");
        List<Integer> playerHeadSlots = requireSlots(
                config,
                "BetGUI.SpecialItems.PlayerHeads.slots",
                betGuiSize,
                true
        );
        String[] names = new String[]{"NextPage", "PreviousPage", "Bet", "RemoveBet"};
        List<HashMap<List<Integer>, ItemStack>> maps = new ArrayList();

        for(int i = 0; i < 4; ++i) {
            maps.add(new HashMap());
        }

        for(int i = 0; i < 4; ++i) {
            String sectionPath = "BetGUI.SpecialItems." + names[i];
            ConfigurationSection specialSection = requireSection(config, sectionPath);
            if (specialSection.getKeys(false).isEmpty()) {
                throw validation(sectionPath, "must contain at least one configured item");
            }
            for(String key : specialSection.getKeys(false)) {
                String itemPath = sectionPath + "." + key;
                ItemStack item = this.loadItem(config, itemPath);
                List<Integer> slots = configuredSlots(config, itemPath, betGuiSize, true);
                ((HashMap)maps.get(i)).put(slots, item);
            }
        }
        List<Integer> closeButtonSlots = requireSlots(config, "BetGUI.closeButton", betGuiSize, true);

        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "BetGUI");
        requirePopulatedSlots(baseInventory, closeButtonSlots, "BetGUI.closeButton");
        String gameInventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.title")));
        String gameInventoryTitleAfterStop = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.titleAfterStop")));
        String gameInventoryTitleAfterCrash = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.titleAfterCrash")));
        int gameInventorySize = requireInventorySize(config, "GameGUI.size");
        Inventory baseGameInventory = Bukkit.createInventory((InventoryHolder)null, gameInventorySize, gameInventoryTitle);
        Inventory crashedGameInventory = Bukkit.createInventory((InventoryHolder)null, gameInventorySize, gameInventoryTitleAfterStop);
        Inventory endGameInventory = Bukkit.createInventory((InventoryHolder)null, gameInventorySize, gameInventoryTitleAfterCrash);
        ItemStack crashedPlayerHead = this.loadItem(config, "GameGUI.SpecialItems.PlayerHeads");
        List<Integer> gamePlayerHeadSlots = requireSlots(
                config,
                "GameGUI.SpecialItems.PlayerHeads.slots",
                gameInventorySize,
                true
        );
        ItemStack valueItem = this.loadItem(config, "GameGUI.SpecialItems.Crash");
        ItemStack crashedButton = this.loadItem(config, "GameGUI.SpecialItems.Crashed");
        List<Integer> valueSlots = configuredSlots(
                config,
                "GameGUI.SpecialItems.Crash",
                gameInventorySize,
                true
        );
        List<Integer> crashedSlots = configuredSlots(
                config,
                "GameGUI.SpecialItems.Crashed",
                gameInventorySize,
                true
        );
        if (!valueSlots.equals(crashedSlots)) {
            throw validation(
                    "GameGUI.SpecialItems.Crashed.slots",
                    "must match GameGUI.SpecialItems.Crash slots because both states share the same button positions"
            );
        }

        List<ItemAnimation> gameAnimations = new ArrayList();
        List<ItemAnimation> gameDependentAnimations = new ArrayList();
        this.loadAllItems(config, baseGameInventory, gameAnimations, gameDependentAnimations, "GameGUI");
        NavigableMap<Integer, Double> chances = new TreeMap();
        List<Double> chanceLimits = new ArrayList();
        int totalChance = 0;
        double previousLimit = 0.0D;
        ConfigurationSection chancesSection = requireSection(config, "Chances");
        if (chancesSection.getKeys(false).isEmpty()) {
            throw validation("Chances", "must contain at least one crash limit");
        }

        for(String key : chancesSection.getKeys(false)) {
            String chancePath = "Chances." + key;
            double value;
            try {
                value = Double.parseDouble(key.replace(',', '.'));
            } catch (NumberFormatException exception) {
                throw validation(chancePath, "limit key must be a finite positive number", exception);
            }
            int weight = requirePositiveInt(config, chancePath);
            if (!Double.isFinite(value) || value <= previousLimit) {
                throw validation(chancePath, "limit must be finite, positive, and strictly ascending");
            }
            chanceLimits.add(value);
            totalChance = addPositiveWeight(totalChance, weight, chancePath);
            chances.put(totalChance, value);
            previousLimit = value;
        }

        int gameDuration = requirePositiveInt(config, "gameDuration");
        int timeBetweenGames = requireNonNegativeInt(config, "timeBetweenGames");
        int timeAddedOnBet = requireNonNegativeInt(config, "timeAddedOnBet");

        ItemStack machineItem = this.loadItem(config, "Machine");
        double[] entityOffset = new double[]{config.getDouble("Machine.Offset.x"), config.getDouble("Machine.Offset.y"), config.getDouble("Machine.Offset.z")};
        SmartGambling.getInstance().crashMachine = new CrashMachine(machineItem, entityOffset, baseInventory, new InventoryAnimations(animations, dependentAnimations), inventoryTitle, inventoryTitleAfterBet, playerHeadSlots, basePlayerHead, crashedPlayerHead, valueItem, crashedButton, valueSlots, (HashMap)maps.get(0), (HashMap)maps.get(1), (HashMap)maps.get(2), (HashMap)maps.get(3), new Button(new HashSet(closeButtonSlots)), baseGameInventory, crashedGameInventory, new InventoryAnimations(gameAnimations, gameDependentAnimations), gameInventoryTitle, endGameInventory, gamePlayerHeadSlots, gameDuration, timeBetweenGames, timeAddedOnBet, chances, totalChance, chanceLimits, true, gameInventorySize, gameInventoryTitle, gameInventoryTitleAfterStop, gameInventoryTitleAfterCrash);
        SmartGambling.getInstance().registerMachineType(
                MachineTypeIds.CRASH,
                SmartGambling.getInstance().crashMachine
        );
    }

    public void loadJackpotMachine() {
        File jackpotMachineFile = new File(SmartGambling.getInstance().getDataFolder() + "/machines/jackpot/jackpot.yml");
        if (!jackpotMachineFile.exists()) {
            SmartGambling.getInstance().saveResource("machines/jackpot/jackpot.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(jackpotMachineFile);
        } catch (InvalidConfigurationException | IOException var19) {
            throw new IllegalStateException("Could not load machines/jackpot/jackpot.yml", var19);
        }

        int betGuiSize = requireInventorySize(config, "BetGUI.size");
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, betGuiSize);
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.title")));
        String inventoryTitleAfterBet = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.titleAfterBet")));
        ItemStack basePlayerHead = this.loadItem(config, "BetGUI.SpecialItems.PlayerHeads");
        List<Integer> playerHeadSlots = requireSlots(
                config,
                "BetGUI.SpecialItems.PlayerHeads.slots",
                betGuiSize,
                true
        );
        String[] names = new String[]{"NextPage", "PreviousPage", "Bet", "RemoveBet"};
        List<HashMap<List<Integer>, ItemStack>> maps = new ArrayList();

        for(int i = 0; i < 4; ++i) {
            maps.add(new HashMap());
        }

        for(int i = 0; i < 4; ++i) {
            String sectionPath = "BetGUI.SpecialItems." + names[i];
            ConfigurationSection specialSection = requireSection(config, sectionPath);
            if (specialSection.getKeys(false).isEmpty()) {
                throw validation(sectionPath, "must contain at least one configured item");
            }
            for(String key : specialSection.getKeys(false)) {
                String itemPath = sectionPath + "." + key;
                ItemStack item = this.loadItem(config, itemPath);
                List<Integer> slots = configuredSlots(config, itemPath, betGuiSize, true);
                ((HashMap)maps.get(i)).put(slots, item);
            }
        }
        List<Integer> closeButtonSlots = requireSlots(config, "BetGUI.closeButton", betGuiSize, true);

        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "BetGUI");
        requirePopulatedSlots(baseInventory, closeButtonSlots, "BetGUI.closeButton");
        String gameInventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.title")));
        int gameGuiSize = requireInventorySize(config, "GameGUI.size");
        Inventory baseGameInventory = Bukkit.createInventory((InventoryHolder)null, gameGuiSize, gameInventoryTitle);
        List<ItemAnimation> gameAnimations = new ArrayList();
        List<ItemAnimation> gameDependentAnimations = new ArrayList();
        this.loadAllItems(config, baseGameInventory, gameAnimations, gameDependentAnimations, "GameGUI");
        List<Integer> gamePlayerHeadSlots = requireSlots(config, "GameGUI.headSlots", gameGuiSize, true);
        int winningHeadSlot = requireSlot(config, "GameGUI.winningHeadSlot", gameGuiSize);
        if (!gamePlayerHeadSlots.contains(winningHeadSlot)) {
            throw validation("GameGUI.winningHeadSlot", "must also be present in GameGUI.headSlots");
        }
        int gameDuration = requirePositiveInt(config, "gameDuration");
        int timeBetweenGames = requireNonNegativeInt(config, "timeBetweenGames");
        int timeAddedOnBet = requireNonNegativeInt(config, "timeAddedOnBet");
        int animationDuration = requirePositiveInt(config, "animationDuration");
        ItemStack machineItem = this.loadItem(config, "Machine");
        double[] entityOffset = new double[]{config.getDouble("Machine.Offset.x"), config.getDouble("Machine.Offset.y"), config.getDouble("Machine.Offset.z")};
        SmartGambling.getInstance().jackpotMachine = new JackpotMachine(machineItem, entityOffset, baseInventory, new InventoryAnimations(animations, dependentAnimations), inventoryTitle, inventoryTitleAfterBet, playerHeadSlots, basePlayerHead, (HashMap)maps.get(0), (HashMap)maps.get(1), (HashMap)maps.get(2), (HashMap)maps.get(3), new Button(new HashSet(closeButtonSlots)), baseGameInventory, gameInventoryTitle, new InventoryAnimations(gameAnimations, gameDependentAnimations), gamePlayerHeadSlots, winningHeadSlot, gameDuration, timeBetweenGames, timeAddedOnBet, animationDuration);
        SmartGambling.getInstance().registerMachineType(
                MachineTypeIds.LOTTERY,
                SmartGambling.getInstance().jackpotMachine
        );
    }

    public void loadMoneyInventory() {
        File moneyInventoryFile = new File(SmartGambling.getInstance().getDataFolder() + "/machines/moneyInventory.yml");
        if (!moneyInventoryFile.exists()) {
            SmartGambling.getInstance().saveResource("machines/moneyInventory.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(moneyInventoryFile);
        } catch (InvalidConfigurationException | IOException var11) {
            throw new IllegalStateException("Could not load machines/moneyInventory.yml", var11);
        }

        int guiSize = requireInventorySize(config, "GUI.size");
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, guiSize);
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.title")));
        List<Integer> moneyButtonsValue = new ArrayList();
        List<Button> moneyButtons = new ArrayList();

        ConfigurationSection moneyButtonSection = requireSection(config, "GUI.moneyButtons");
        if (moneyButtonSection.getKeys(false).isEmpty()) {
            throw validation("GUI.moneyButtons", "must contain at least one positive amount");
        }
        for(String key : moneyButtonSection.getKeys(false)) {
            String buttonPath = "GUI.moneyButtons." + key;
            int amount;
            try {
                amount = Integer.parseInt(key);
            } catch (NumberFormatException exception) {
                throw validation(buttonPath, "amount key must be a positive integer", exception);
            }
            if (amount <= 0) {
                throw validation(buttonPath, "amount key must be positive (was " + amount + ")");
            }
            List<Integer> slots = requireSlots(config, buttonPath, guiSize, true);
            moneyButtons.add(new Button(new HashSet(slots)));
            moneyButtonsValue.add(amount);
        }

        Button customAmountButton = new Button(new HashSet(
                requireSlots(config, "GUI.customAmountButton", guiSize, true)
        ));
        Button backButton = new Button(new HashSet(
                requireSlots(config, "GUI.backButton", guiSize, false)
        ));
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
        for (int index = 0; index < moneyButtons.size(); index++) {
            requirePopulatedSlots(
                    baseInventory,
                    List.copyOf(moneyButtons.get(index).getSlots()),
                    "GUI.moneyButtons." + moneyButtonsValue.get(index)
            );
        }
        requirePopulatedSlots(baseInventory, List.copyOf(customAmountButton.getSlots()), "GUI.customAmountButton");
        requirePopulatedSlots(baseInventory, List.copyOf(backButton.getSlots()), "GUI.backButton");
        SmartGambling.getInstance().moneyInventory = new MoneyInventory(baseInventory, inventoryTitle, new InventoryAnimations(animations, dependentAnimations), moneyButtonsValue, moneyButtons, customAmountButton, backButton);
    }

    public void loadSlotMachines() {
        File machineTypesFolder = new File(SmartGambling.getInstance().getDataFolder() + "/machines/slots");
        if (!machineTypesFolder.exists()) {
            machineTypesFolder.mkdir();
            SmartGambling.getInstance().saveResource("machines/slots/slotExample.yml", false);
        }

        SmartGambling.getInstance().machineTypes = new HashMap<>();

        for(File machineType : (File[])Objects.requireNonNull(machineTypesFolder.listFiles())) {
            if (machineType.getName().toLowerCase().endsWith(".yml")) {
                FileConfiguration machineTypeConfig = new YamlConfiguration();

                try {
                    machineTypeConfig.load(machineType);
                } catch (InvalidConfigurationException | IOException var9) {
                    throw new IllegalStateException(
                            "Could not load slot machine config '" + machineType.getName() + "'.",
                            var9
                    );
                }

                try {
                    String id = MachineTypeIds.fromSlotFileName(machineType.getName());
                    SmartGambling.getInstance().registerMachineType(
                            id,
                            this.loadSlotMachineType(machineTypeConfig, id)
                    );
                } catch (Exception var8) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Error loading slot machine \"" + machineType.getName() + "\".");
                    throw new IllegalStateException("Could not load slot machine config '" + machineType.getName() + "'.", var8);
                }
            }
        }

    }

    private SlotMachine loadSlotMachineType(FileConfiguration config, String name) {
        int guiSize = requireInventorySize(config, "GUI.size");
        List<List<Integer>> displaySlots = requireDisplaySlots(config, "GUI.displaySlots", guiSize);
        List<Integer> spinButtonSlots = requireSlots(config, "GUI.spinButton", guiSize, true);
        Button spinButton = new Button(new HashSet(spinButtonSlots));
        List<Integer> moneyButtonSlots = requireSlots(config, "GUI.moneyButton", guiSize, false);
        Button moneyButton = new Button(new HashSet(moneyButtonSlots));
        Button rewardsGuiButton = new Button(new HashSet(
                requireSlots(config, "GUI.rewardsGuiButton", guiSize, false)
        ));
        List<Integer> closeButtonSlots = requireSlots(config, "GUI.closeButton", guiSize, false);
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, guiSize);
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.title")));
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
        requirePopulatedSlots(baseInventory, spinButtonSlots, "GUI.spinButton");
        requirePopulatedSlots(baseInventory, moneyButtonSlots, "GUI.moneyButton");
        requirePopulatedSlots(baseInventory, List.copyOf(rewardsGuiButton.getSlots()), "GUI.rewardsGuiButton");
        requirePopulatedSlots(baseInventory, closeButtonSlots, "GUI.closeButton");
        NavigableMap<Integer, SlotItem> itemsWeighed = new TreeMap();
        HashMap<String, SlotItem> itemDictionary = new HashMap();
        int totalWeight = 0;
        ConfigurationSection slotItemsSection = requireSection(config, "Items");
        if (slotItemsSection.getKeys(false).isEmpty()) {
            throw validation("Items", "must contain at least one weighted slot item");
        }

        for(String key : slotItemsSection.getKeys(false)) {
            String chancePath = "Items." + key + ".chance";
            ItemStack item = this.loadItem(config, "Items." + key);
            SlotItem slotItem = new SlotItem(item);
            int chance = requirePositiveInt(config, chancePath);
            totalWeight = addPositiveWeight(totalWeight, chance, chancePath);
            itemsWeighed.put(totalWeight, slotItem);
            itemDictionary.put(key, slotItem);
        }

        for(String key : slotItemsSection.getKeys(false)) {
            if (config.contains("Items." + key + ".equivalent")) {
                for(String item : config.getStringList("Items." + key + ".equivalent")) {
                    if (!itemDictionary.containsKey(item)) {
                        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Cannot find item named : " + item + " (Items." + key + ".equivalent)");
                        throw new NullPointerException();
                    }

                    ((SlotItem)itemDictionary.get(key)).equivalents.add((SlotItem)itemDictionary.get(item));
                }
            }
        }

        ConfigurationSection categoriesSection = requireSection(config, "Categories");
        for(String key : categoriesSection.getKeys(false)) {
            String categoryPath = "Categories." + key;
            List<String> itemsName = config.getStringList(categoryPath);
            if (itemsName.isEmpty()) {
                throw validation(categoryPath, "must contain at least one weighted item name");
            }
            SlotItem category = new SlotItem((ItemStack)null);

            for(String itemName : itemsName) {
                if (!itemDictionary.containsKey(itemName)) {
                    throw validation(categoryPath, "references unknown weighted item '" + itemName + "'");
                }

                category.equivalents.add((SlotItem)itemDictionary.get(itemName));
            }

            itemDictionary.put(key, category);
        }

        List<Reward> rewards = new ArrayList();

        ConfigurationSection rewardsSection = requireSection(config, "Rewards");
        for(String key : rewardsSection.getKeys(false)) {
            String rewardPath = "Rewards." + key;
            String requirementsPath = rewardPath + ".Requirements";
            int startingLine = -1;
            String startingLinePath = requirementsPath + ".startingLine";
            if (config.contains(startingLinePath)) {
                startingLine = requireNonNegativeInt(config, startingLinePath);
                if (startingLine >= displaySlots.size()) {
                    throw validation(startingLinePath, "must be smaller than the number of GUI.displaySlots rows");
                }
            }

            CustomSound sound = (CustomSound)SmartGambling.getInstance().customSounds.get(config.getString(rewardPath + ".Reward.sound"));
            Reward reward;
            String typePath = requirementsPath + ".type";
            String type = config.getString(typePath);
            if (type == null || (!type.equalsIgnoreCase("row") && !type.equalsIgnoreCase("exact"))) {
                throw validation(typePath, "must be either 'row' or 'exact'");
            }
            int requiredLength;
            if (type.equalsIgnoreCase("row")) {
                String itemPath = requirementsPath + ".item";
                String itemName = config.getString(itemPath);
                if (itemName == null || !itemDictionary.containsKey(itemName)) {
                    throw validation(itemPath, "must reference a configured weighted item or category");
                }
                String amountPath = requirementsPath + ".amount";
                requiredLength = requirePositiveInt(config, amountPath);
                reward = new RowReward(itemDictionary.get(itemName), requiredLength, startingLine, sound);
            } else {
                String itemsPath = requirementsPath + ".items";
                List<String> itemsName = config.getStringList(itemsPath);
                if (itemsName.isEmpty()) {
                    throw validation(itemsPath, "must contain at least one configured item or category");
                }
                requiredLength = itemsName.size();
                SlotItem[] items = new SlotItem[itemsName.size()];

                for(int i = 0; i < itemsName.size(); ++i) {
                    if (!itemDictionary.containsKey(itemsName.get(i))) {
                        throw validation(itemsPath + '[' + i + ']', "references unknown item '" + itemsName.get(i) + "'");
                    }

                    items[i] = (SlotItem)itemDictionary.get(itemsName.get(i));
                }

                reward =  new ExactMatchReward(items, startingLine, sound);
            }

            if (requiredLength > displaySlots.size()
                    || startingLine >= 0 && startingLine + requiredLength > displaySlots.size()) {
                throw validation(
                        requirementsPath,
                        "requires " + requiredLength + " result row(s), outside GUI.displaySlots"
                );
            }

            reward.moneyMultiplier = 1.0F;
            String multiplierPath = rewardPath + ".Reward.moneyMultiplier";
            if (config.contains(multiplierPath)) {
                reward.moneyMultiplier = requirePositiveFiniteFloat(config, multiplierPath);
            }

            if (config.contains(rewardPath + ".Reward.commands")) {
                reward.winningCommands = config.getStringList(rewardPath + ".Reward.commands");
            }

            rewards.add(reward);
        }

        int animationDuration = 80;
        if (config.contains("GUI.animationDuration")) {
            animationDuration = requireNonNegativeInt(config, "GUI.animationDuration");
        }

        int animationStartingSpeed = 4;
        if (config.contains("GUI.animationSpeed")) {
            animationStartingSpeed = Math.max(4, requirePositiveInt(config, "GUI.animationSpeed"));
        }

        int defaultBet = requirePositiveInt(config, "defaultBet");
        SubInventory rewardsGui = this.loadSubInventory(config, "RewardsGUI");
        ItemStack slotMachineItem = this.loadItem(config, "Machine");
        double[] entityOffset = new double[]{config.getDouble("Machine.Offset.x"), config.getDouble("Machine.Offset.y"), config.getDouble("Machine.Offset.z")};

        return new SlotMachine(name, slotMachineItem, entityOffset, inventoryTitle, baseInventory, displaySlots, spinButton, moneyButton, rewardsGuiButton, new Button(new HashSet(closeButtonSlots)), rewardsGui, itemsWeighed, totalWeight, rewards, new InventoryAnimations(animations, dependentAnimations), animationDuration, defaultBet, animationStartingSpeed);
    }

    public SubInventory loadSubInventory(FileConfiguration config, String path) {
        int inventorySize = requireInventorySize(config, path + ".size");
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, inventorySize);
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString(path + ".title")));
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        Button backButton = new Button(new HashSet(
                requireSlots(config, path + ".backButton", inventorySize, false)
        ));
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, path);
        requirePopulatedSlots(baseInventory, List.copyOf(backButton.getSlots()), path + ".backButton");
        return new SubInventory(baseInventory, inventoryTitle, new InventoryAnimations(animations, dependentAnimations), backButton);
    }

    public void loadAllItems(FileConfiguration config, Inventory baseInventory, List<ItemAnimation> animations, List<ItemAnimation> dependentAnimations, String path) {
        ConfigurationSection configSection = config.getConfigurationSection(path + ".Items");
        if (configSection != null) {
            for(String key : configSection.getKeys(false)) {
                String itemPath = path + ".Items." + key;
                ItemStack item = this.loadItem(config, itemPath);
                if (!config.contains(itemPath + ".slots") && !config.contains(itemPath + ".slot")) {
                    throw validation(itemPath, "must define 'slot' or 'slots' (an explicit empty slots list is allowed)");
                }
                List<Integer> slots = configuredSlots(config, itemPath, baseInventory.getSize(), false);
                for(int slot : slots) {
                    baseInventory.setItem(slot, item);
                }

                String animationPath = itemPath + ".animation";
                if (config.contains(animationPath)) {
                    if (slots.isEmpty()) {
                        throw validation(animationPath, "cannot animate an empty slot list");
                    }

                    List<Material> materials = new ArrayList();
                    String materialsPath = animationPath + ".materials";
                    List<String> configuredMaterials = config.getStringList(materialsPath);
                    for(int index = 0; index < configuredMaterials.size(); index++) {
                        String material = configuredMaterials.get(index);
                        try {
                            materials.add(Material.valueOf(material));
                        } catch (IllegalArgumentException exception) {
                            throw validation(materialsPath + '[' + index + ']', "unknown Bukkit material '" + material + "'", exception);
                        }
                    }
                    if (materials.isEmpty()) {
                        throw validation(materialsPath, "must contain at least one Bukkit material");
                    }

                    int vary = 1;
                    String varyPath = animationPath + ".vary";
                    if (config.contains(varyPath)) {
                        vary = requirePositiveInt(config, varyPath);
                    }
                    int delay = requirePositiveInt(config, animationPath + ".delay");
                    if (vary > materials.size()) {
                        throw validation(varyPath, "cannot exceed the number of animation materials");
                    }

                    if (config.contains(animationPath + ".dependent") && config.getBoolean(animationPath + ".dependent")) {
                        dependentAnimations.add(new ItemAnimation(materials, slots, delay, vary));
                    } else {
                        animations.add(new ItemAnimation(materials, slots, delay, vary));
                    }
                }
            }

        }
    }

    public ItemStack loadItem(FileConfiguration config, String path) {
        this.rejectLegacyCustomModelData(config, path);
        boolean hasCraftEngineItem = config.contains(path + ".craftEngineItem");
        boolean hasMaterial = config.contains(path + ".material");
        if (hasCraftEngineItem == hasMaterial) {
            throw new IllegalArgumentException(
                    "Item '" + path + "' must define exactly one of 'craftEngineItem' or 'material'."
            );
        }

        ItemStack item;
        if (hasCraftEngineItem) {
            String id = config.getString(path + ".craftEngineItem");
            item = CraftEngineItemResolver.build(id, path + ".craftEngineItem");
        } else {
            String materialName = config.getString(path + ".material");
            try {
                item = new ItemStack(Material.valueOf(Objects.requireNonNull(materialName).toUpperCase()));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalArgumentException("Invalid Bukkit material '" + materialName + "' at '" + path + ".material'.", exception);
            }
        }

        return this.applyItemProperties(config, path, item);
    }

    private ItemStack loadCraftEngineItem(FileConfiguration config, String path, String id, int index) {
        this.rejectLegacyCustomModelData(config, path);
        if (config.contains(path + ".material") || config.contains(path + ".craftEngineItem")) {
            throw new IllegalArgumentException(
                    "Card item '" + path + "' must use only 'craftEngineItems', without 'material' or 'craftEngineItem'."
            );
        }
        ItemStack item = CraftEngineItemResolver.build(id, path + ".craftEngineItems[" + index + "]");
        return this.applyItemProperties(config, path, item);
    }

    private void rejectLegacyCustomModelData(FileConfiguration config, String path) {
        if (config.contains(path + ".customModelData") || config.contains(path + ".customModelDataList")) {
            throw new IllegalArgumentException(
                    "Legacy CustomModelData is no longer supported at '" + path + "'. "
                            + "Use 'craftEngineItem' or 'craftEngineItems' with a namespaced CraftEngine ID."
            );
        }
    }

    private ItemStack applyItemProperties(FileConfiguration config, String path, ItemStack item) {
        if (config.contains(path + ".amount")) {
            item.setAmount(requirePositiveInt(config, path + ".amount"));
        }

        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) {
            throw new IllegalStateException("Item at '" + path + "' has no item metadata.");
        }

        if (config.contains(path + ".name")) {
            itemMeta.setDisplayName(ColorUtils.replaceAllColorCodes((String)Objects.requireNonNull(config.getString(path + ".name"))));
        }

        if (config.contains(path + ".lore")) {
            List<String> lore = config.getStringList(path + ".lore");

            for(int i = 0; i < lore.size(); ++i) {
                lore.set(i, ColorUtils.replaceAllColorCodes((String)lore.get(i)));
            }

            itemMeta.setLore(lore);
        }

        if (item.getType().equals(Material.PLAYER_HEAD) && config.contains(path + ".owner")) {
            SkullMeta skullMeta = (SkullMeta)itemMeta;
            skullMeta.setOwnerProfile(Bukkit.getServer().createPlayerProfile(config.getString(path + ".owner")));
        }

        item.setItemMeta(itemMeta);
        return item;
    }
}
