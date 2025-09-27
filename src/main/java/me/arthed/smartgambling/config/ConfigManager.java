// Decompiled with: FernFlower
// Class Version: 17
package me.arthed.smartgambling.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import me.arthed.smartgambling.handlers.SmartGamblingPlaceholders;
import me.arthed.smartgambling.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;


public class ConfigManager {
    public HashMap<String, String> messages;
    public List<String> helpMenu;

    public void load() {
        FileConfiguration config = SmartGambling.getInstance().getConfig();
        this.messages = new HashMap();
        String prefix = config.getString("Messages.prefix");

        assert prefix != null;

        for(String key : config.getConfigurationSection("Messages").getKeys(false)) {
            if (!Objects.equals(key, prefix)) {
                this.messages.put(key, ChatColor.translateAlternateColorCodes('&', config.getString("Messages." + key).replace("%prefix%", prefix)));
            }
        }

        this.helpMenu = config.getStringList("helpMenu");

        for(int i = 0; i < this.helpMenu.size(); ++i) {
            this.helpMenu.set(i, ChatColor.translateAlternateColorCodes('&', (String)this.helpMenu.get(i)));
        }

        SmartGambling.getInstance().customSounds = new HashMap();

        for(String key : config.getConfigurationSection("Sounds").getKeys(false)) {
            List<SoundElement> elements = new ArrayList();

            for(String elementRaw : config.getStringList("Sounds." + key)) {
                String[] propertiesRaw = elementRaw.split(" ");
                Sound sound = null;

                try {
                    sound = Sound.valueOf(propertiesRaw[0]);
                } catch (IllegalArgumentException var15) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Error loading custom sound " + key + " in config.yml! Theres no sound named: " + propertiesRaw[0]);
                    var15.printStackTrace();
                }

                float volume = Float.parseFloat(propertiesRaw[1]);
                float pitch = Float.parseFloat(propertiesRaw[2]);
                int delay = Integer.parseInt(propertiesRaw[3]);
                elements.add(new SoundElement(sound, volume, pitch, delay));
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

    public void loadPlaceholdersConfig() {
        File confirmInventoryFile = new File(SmartGambling.getInstance().getDataFolder() + "/placeholders.yml");
        if (!confirmInventoryFile.exists()) {
            SmartGambling.getInstance().saveResource("placeholders.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(confirmInventoryFile);
        } catch (InvalidConfigurationException | IOException var4) {
            var4.printStackTrace();
            return;
        }

        SmartGamblingPlaceholders.blackjackStatusNoPlayers = ChatColor.translateAlternateColorCodes('&', config.getString("blackjack.noPlayers"));
        SmartGamblingPlaceholders.blackjackStatusChoosingBet = ChatColor.translateAlternateColorCodes('&', config.getString("blackjack.choosingBet"));
        SmartGamblingPlaceholders.blackjackStatusWaitingOpponent = ChatColor.translateAlternateColorCodes('&', config.getString("blackjack.waitingForOpponent"));
        SmartGamblingPlaceholders.blackjackStatusPlaying = ChatColor.translateAlternateColorCodes('&', config.getString("blackjack.playing"));
        SmartGamblingPlaceholders.jackpotCooldown = ChatColor.translateAlternateColorCodes('&', config.getString("jackpot.cooldown"));
        SmartGamblingPlaceholders.jackpotActive = ChatColor.translateAlternateColorCodes('&', config.getString("jackpot.active"));
        SmartGamblingPlaceholders.jackpotFinish = ChatColor.translateAlternateColorCodes('&', config.getString("jackpot.finish"));
        SmartGamblingPlaceholders.crashCooldown = ChatColor.translateAlternateColorCodes('&', config.getString("crash.cooldown"));
        SmartGamblingPlaceholders.crashStarting = ChatColor.translateAlternateColorCodes('&', config.getString("crash.betting"));
        SmartGamblingPlaceholders.crashCrashing = ChatColor.translateAlternateColorCodes('&', config.getString("crash.crashing"));
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
            var9.printStackTrace();
            return;
        }

        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt("GUI.size"));
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.title")));
        int confirmButton = config.getInt("GUI.confirmButton");
        int declineButton = config.getInt("GUI.declineButton");
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
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
            var25.printStackTrace();
            return;
        }

        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt("GUI.size"));
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.title")));
        List<Integer> cardSlots = config.getIntegerList("GUI.cardSlots");
        List<Integer> opponentCardSlots = config.getIntegerList("GUI.opponentCardSlots");
        List<Integer> placeholderSlots = config.getIntegerList("GUI.placeholderSlots");
        Button hitButton = new Button(new HashSet(config.getIntegerList("GUI.hitButton")));
        Button standButton = new Button(new HashSet(config.getIntegerList("GUI.standButton")));
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
        ItemStack tableItem = this.loadItem(config, "Table");
        double[] entityOffset = new double[]{config.getDouble("Table.Offset.x"), config.getDouble("Table.Offset.y"), config.getDouble("Table.Offset.z")};
        double[] chair1Offset = new double[]{config.getDouble("Table.ChairOffset1.x"), config.getDouble("Table.ChairOffset1.y"), config.getDouble("Table.ChairOffset1.z")};
        double[] chair2Offset = new double[]{config.getDouble("Table.ChairOffset2.x"), config.getDouble("Table.ChairOffset2.y"), config.getDouble("Table.ChairOffset2.z")};
        NavigableMap<Integer, PlayingCard> cards = new TreeMap();
        int totalChance = 0;

        for(String key : config.getConfigurationSection("Cards").getKeys(false)) {
            ItemStack item = this.loadItem(config, "Cards." + key);
            List<Integer> customModelDataList = config.getIntegerList("Cards." + key + ".customModelDataList");
            ItemStack[] items = new ItemStack[customModelDataList.size()];

            for(int i = 0; i < items.length; ++i) {
                items[i] = item.clone();
                ItemMeta meta = items[i].getItemMeta();
                meta.setCustomModelData(customModelDataList.get(i));
                items[i].setItemMeta(meta);
            }

            totalChance += config.getInt("Cards." + key + ".chance");
            cards.put(totalChance, new PlayingCard(config.getInt("Cards." + key + ".value"), items));
        }

        ItemStack cardBack = this.loadItem(config, "CardBack");
        String inventoryTitleStand = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleStand")));
        String inventoryTitleLost = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleLost")));
        String inventoryTitleWin = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleWin")));
        String inventoryTitleDraw = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.titleDraw")));
        SmartGambling.getInstance().blackJack = new BlackJack(tableItem, entityOffset, chair1Offset, chair2Offset, baseInventory, new InventoryAnimations(animations, dependentAnimations), inventoryTitle, inventoryTitleStand, inventoryTitleLost, inventoryTitleWin, inventoryTitleDraw, hitButton, standButton, cardBack, cardSlots, opponentCardSlots, placeholderSlots, cards, totalChance);
        SmartGambling.getInstance().machineTypes.put("blackjack".hashCode(), SmartGambling.getInstance().blackJack);
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
            var33.printStackTrace();
            return;
        }

        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt("BetGUI.size"));
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.title")));
        String inventoryTitleAfterBet = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.titleAfterBet")));
        ItemStack basePlayerHead = this.loadItem(config, "BetGUI.SpecialItems.PlayerHeads");
        List<Integer> playerHeadSlots = config.getIntegerList("BetGUI.SpecialItems.PlayerHeads.slots");
        String[] names = new String[]{"NextPage", "PreviousPage", "Bet", "RemoveBet"};
        List<HashMap<List<Integer>, ItemStack>> maps = new ArrayList();

        for(int i = 0; i < 4; ++i) {
            maps.add(new HashMap());
        }

        for(int i = 0; i < 4; ++i) {
            for(String key : config.getConfigurationSection("BetGUI.SpecialItems." + names[i]).getKeys(false)) {
                ItemStack item = this.loadItem(config, "BetGUI.SpecialItems." + names[i] + "." + key);
                List<Integer> slots;
                if (config.contains("BetGUI.SpecialItems." + names[i] + "." + key + ".slots")) {
                    slots = config.getIntegerList("BetGUI.SpecialItems." + names[i] + "." + key + ".slots");
                } else {
                    slots = Arrays.asList(config.getInt("BetGUI.SpecialItems." + names[i] + "." + key + ".slot"));
                }

                ((HashMap)maps.get(i)).put(slots, item);
            }
        }

        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "BetGUI");
        String gameInventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.title")));
        String gameInventoryTitleAfterStop = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.titleAfterStop")));
        String gameInventoryTitleAfterCrash = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.titleAfterCrash")));
        int gameInventorySize = config.getInt("GameGUI.size");
        Inventory baseGameInventory = Bukkit.createInventory((InventoryHolder)null, gameInventorySize, gameInventoryTitle);
        Inventory crashedGameInventory = Bukkit.createInventory((InventoryHolder)null, gameInventorySize, gameInventoryTitleAfterStop);
        Inventory endGameInventory = Bukkit.createInventory((InventoryHolder)null, gameInventorySize, gameInventoryTitleAfterCrash);
        ItemStack crashedPlayerHead = this.loadItem(config, "GameGUI.SpecialItems.PlayerHeads");
        List<Integer> gamePlayerHeadSlots = config.getIntegerList("GameGUI.SpecialItems.PlayerHeads.slots");
        ItemStack valueItem = this.loadItem(config, "GameGUI.SpecialItems.Crash");
        ItemStack crashedButton = this.loadItem(config, "GameGUI.SpecialItems.Crashed");
        List<Integer> valueSlots;
        if (config.contains("GameGUI.SpecialItems.Crash.slots")) {
            valueSlots = config.getIntegerList("GameGUI.SpecialItems.Crash.slots");
        } else {
            valueSlots = Collections.singletonList(config.getInt("GameGUI.SpecialItems.Crash.slot"));
        }

        List<ItemAnimation> gameAnimations = new ArrayList();
        List<ItemAnimation> gameDependentAnimations = new ArrayList();
        this.loadAllItems(config, baseGameInventory, gameAnimations, gameDependentAnimations, "GameGUI");
        NavigableMap<Integer, Double> chances = new TreeMap();
        List<Double> chanceLimits = new ArrayList();
        int totalChance = 0;

        for(String key : config.getConfigurationSection("Chances").getKeys(false)) {
            double value = Double.parseDouble(key.replace(',', '.'));
            chanceLimits.add(value);
            totalChance += config.getInt("Chances." + key);
            chances.put(totalChance, value);
        }

        ItemStack machineItem = this.loadItem(config, "Machine");
        double[] entityOffset = new double[]{config.getDouble("Machine.Offset.x"), config.getDouble("Machine.Offset.y"), config.getDouble("Machine.Offset.z")};
        SmartGambling.getInstance().crashMachine = new CrashMachine(machineItem, entityOffset, baseInventory, new InventoryAnimations(animations, dependentAnimations), inventoryTitle, inventoryTitleAfterBet, playerHeadSlots, basePlayerHead, crashedPlayerHead, valueItem, crashedButton, valueSlots, (HashMap)maps.get(0), (HashMap)maps.get(1), (HashMap)maps.get(2), (HashMap)maps.get(3), new Button(new HashSet(config.getIntegerList("BetGUI.closeButton"))), baseGameInventory, crashedGameInventory, new InventoryAnimations(gameAnimations, gameDependentAnimations), gameInventoryTitle, endGameInventory, gamePlayerHeadSlots, config.getInt("gameDuration"), config.getInt("timeBetweenGames"), chances, totalChance, chanceLimits, true, gameInventorySize, gameInventoryTitle, gameInventoryTitleAfterStop, gameInventoryTitleAfterCrash);
        SmartGambling.getInstance().machineTypes.put("crash".hashCode(), SmartGambling.getInstance().crashMachine);
    }

    public void loadJackpotMachine() {
        if (SmartGambling.getInstance().jackpotMachine != null) {
            SmartGambling.getInstance().jackpotMachine.timerTask.cancel();
        }

        File jackpotMachineFile = new File(SmartGambling.getInstance().getDataFolder() + "/machines/jackpot/jackpot.yml");
        if (!jackpotMachineFile.exists()) {
            SmartGambling.getInstance().saveResource("machines/jackpot/jackpot.yml", false);
        }

        FileConfiguration config = new YamlConfiguration();

        try {
            config.load(jackpotMachineFile);
        } catch (InvalidConfigurationException | IOException var19) {
            var19.printStackTrace();
            return;
        }

        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt("BetGUI.size"));
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.title")));
        String inventoryTitleAfterBet = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("BetGUI.titleAfterBet")));
        ItemStack basePlayerHead = this.loadItem(config, "BetGUI.SpecialItems.PlayerHeads");
        List<Integer> playerHeadSlots = config.getIntegerList("BetGUI.SpecialItems.PlayerHeads.slots");
        String[] names = new String[]{"NextPage", "PreviousPage", "Bet", "RemoveBet"};
        List<HashMap<List<Integer>, ItemStack>> maps = new ArrayList();

        for(int i = 0; i < 4; ++i) {
            maps.add(new HashMap());
        }

        for(int i = 0; i < 4; ++i) {
            for(String key : config.getConfigurationSection("BetGUI.SpecialItems." + names[i]).getKeys(false)) {
                ItemStack item = this.loadItem(config, "BetGUI.SpecialItems." + names[i] + "." + key);
                List<Integer> slots;
                if (config.contains("BetGUI.SpecialItems." + names[i] + "." + key + ".slots")) {
                    slots = config.getIntegerList("BetGUI.SpecialItems." + names[i] + "." + key + ".slots");
                } else {
                    slots = Arrays.asList(config.getInt("BetGUI.SpecialItems." + names[i] + "." + key + ".slot"));
                }

                ((HashMap)maps.get(i)).put(slots, item);
            }
        }

        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "BetGUI");
        String gameInventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GameGUI.title")));
        Inventory baseGameInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt("GameGUI.size"), gameInventoryTitle);
        List<ItemAnimation> gameAnimations = new ArrayList();
        List<ItemAnimation> gameDependentAnimations = new ArrayList();
        this.loadAllItems(config, baseGameInventory, gameAnimations, gameDependentAnimations, "GameGUI");
        List<Integer> gamePlayerHeadSlots = config.getIntegerList("GameGUI.headSlots");
        ItemStack machineItem = this.loadItem(config, "Machine");
        double[] entityOffset = new double[]{config.getDouble("Machine.Offset.x"), config.getDouble("Machine.Offset.y"), config.getDouble("Machine.Offset.z")};
        SmartGambling.getInstance().jackpotMachine = new JackpotMachine(machineItem, entityOffset, baseInventory, new InventoryAnimations(animations, dependentAnimations), inventoryTitle, inventoryTitleAfterBet, playerHeadSlots, basePlayerHead, (HashMap)maps.get(0), (HashMap)maps.get(1), (HashMap)maps.get(2), (HashMap)maps.get(3), new Button(new HashSet(config.getIntegerList("BetGUI.closeButton"))), baseGameInventory, gameInventoryTitle, new InventoryAnimations(gameAnimations, gameDependentAnimations), gamePlayerHeadSlots, config.getInt("GameGUI.winningHeadSlot"), config.getInt("gameDuration"), config.getInt("timeBetweenGames"), config.getInt("timeAddedOnBet"), config.getInt("animationDuration"));
        SmartGambling.getInstance().machineTypes.put("lottery".hashCode(), SmartGambling.getInstance().jackpotMachine);
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
            var11.printStackTrace();
            return;
        }

        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt("GUI.size"));
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString("GUI.title")));
        List<Integer> moneyButtonsValue = new ArrayList();
        List<Button> moneyButtons = new ArrayList();

        for(String key : config.getConfigurationSection("GUI.moneyButtons").getKeys(false)) {
            int amount = Integer.parseInt(key);
            List<Integer> slots = config.getIntegerList("GUI.moneyButtons." + key);
            moneyButtons.add(new Button(new HashSet(slots)));
            moneyButtonsValue.add(amount);
        }

        Button customAmountButton = new Button(new HashSet(config.getIntegerList("GUI.customAmountButton")));
        Button backButton = new Button(new HashSet(config.getIntegerList("GUI.backButton")));
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
        SmartGambling.getInstance().moneyInventory = new MoneyInventory(baseInventory, inventoryTitle, new InventoryAnimations(animations, dependentAnimations), moneyButtonsValue, moneyButtons, customAmountButton, backButton);
    }

    public void loadSlotMachines() {
        File machineTypesFolder = new File(SmartGambling.getInstance().getDataFolder() + "/machines/slots");
        if (!machineTypesFolder.exists()) {
            machineTypesFolder.mkdir();
            SmartGambling.getInstance().saveResource("machines/slots/slotExample.yml", false);
        }

        SmartGambling.getInstance().machineTypes = new HashMap();

        for(File machineType : (File[])Objects.requireNonNull(machineTypesFolder.listFiles())) {
            if (machineType.getName().toLowerCase().endsWith(".yml")) {
                FileConfiguration machineTypeConfig = new YamlConfiguration();

                try {
                    machineTypeConfig.load(machineType);
                } catch (InvalidConfigurationException | IOException var9) {
                    var9.printStackTrace();
                    continue;
                }

                try {
                    String name = machineType.getName().replace(".yml", "");
                    SmartGambling.getInstance().machineTypes.put(name.hashCode(), this.loadSlotMachineType(machineTypeConfig, name));
                } catch (Exception var8) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Error loading slot machine \"" + machineType.getName() + "\".");
                    var8.printStackTrace();
                }
            }
        }

    }

    private SlotMachine loadSlotMachineType(FileConfiguration config, String name) {
        List<List<Integer>> displaySlots = (List<List<Integer>>) config.getList("GUI.displaySlots");
        List<Integer> spinButtonSlots = config.getIntegerList("GUI.spinButton");
        Button spinButton = new Button(new HashSet(spinButtonSlots));
        List<Integer> moneyButtonSlots = config.getIntegerList("GUI.moneyButton");
        Button moneyButton = new Button(new HashSet(moneyButtonSlots));
        Button rewardsGuiButton = new Button(new HashSet(config.getIntegerList("GUI.rewardsGuiButton")));
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt("GUI.size"));
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', "%%你好");
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, "GUI");
        NavigableMap<Integer, SlotItem> itemsWeighed = new TreeMap();
        HashMap<String, SlotItem> itemDictionary = new HashMap();
        int totalWeight = 0;

        for(String key : config.getConfigurationSection("Items").getKeys(false)) {
            ItemStack item = this.loadItem(config, "Items." + key);
            SlotItem slotItem = new SlotItem(item);
            totalWeight += config.getInt("Items." + key + ".chance");
            itemsWeighed.put(totalWeight, slotItem);
            itemDictionary.put(key, slotItem);
        }

        for(String key : config.getConfigurationSection("Items").getKeys(false)) {
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

        for(String key : config.getConfigurationSection("Categories").getKeys(false)) {
            List<String> itemsName = config.getStringList("Categories." + key);
            SlotItem category = new SlotItem((ItemStack)null);

            for(String itemName : itemsName) {
                if (!itemDictionary.containsKey(itemName)) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Cannot find item named : " + itemName + " (Categories." + key + ")");
                    throw new NullPointerException();
                }

                category.equivalents.add((SlotItem)itemDictionary.get(itemName));
            }

            itemDictionary.put(key, category);
        }

        List<Reward> rewards = new ArrayList();

        for(String key : config.getConfigurationSection("Rewards").getKeys(false)) {
            int startingLine = -1;
            if (config.contains("Rewards." + key + ".Requirements.startingLine")) {
                startingLine = config.getInt("Rewards." + key + ".Requirements.startingLine");
            }

            CustomSound sound = (CustomSound)SmartGambling.getInstance().customSounds.get(config.getString("Rewards." + key + ".Reward.sound"));
            Reward reward;
            if (!config.getString("Rewards." + key + ".Requirements.type").equalsIgnoreCase("exact")) {
                SlotItem item = null;
                if (!itemDictionary.containsKey(config.getString("Rewards." + key + ".Requirements.item"))) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Cannot find item named : " + config.getString("Rewards." + key + ".Requirements.item") + " (Rewards." + key + ".Requirements.item)");
                    throw new NullPointerException();
                }

                item = (SlotItem)itemDictionary.get(config.getString("Rewards." + key + ".Requirements.item"));
                reward = new RowReward(item, config.getInt("Rewards." + key + ".Requirements.amount"), startingLine, sound);
            } else {
                List<String> itemsName = config.getStringList("Rewards." + key + ".Requirements.items");
                SlotItem[] items = new SlotItem[itemsName.size()];

                for(int i = 0; i < itemsName.size(); ++i) {
                    if (!itemDictionary.containsKey(itemsName.get(i))) {
                        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Cannot find item named : " + (String)itemsName.get(i) + " (Rewards." + key + ".Requirements.items)");
                        throw new NullPointerException();
                    }

                    items[i] = (SlotItem)itemDictionary.get(itemsName.get(i));
                }

                reward =  new ExactMatchReward(items, startingLine, sound);
            }

            reward.moneyMultiplier = 1.0F;
            if (config.contains("Rewards." + key + ".Reward.moneyMultiplier")) {
                reward.moneyMultiplier = (float)config.getDouble("Rewards." + key + ".Reward.moneyMultiplier");
            }

            if (config.contains("Rewards." + key + ".Reward.commands")) {
                reward.winningCommands = config.getStringList("Rewards." + key + ".Reward.commands");
            }

            rewards.add(reward);
        }

        int animationDuration = 80;
        if (config.contains("GUI.animationDuration")) {
            animationDuration = config.getInt("GUI.animationDuration");
        }

        int animationStartingSpeed = 4;
        if (config.contains("GUI.animationSpeed") && config.getInt("GUI.animationSpeed") > 4) {
            animationStartingSpeed = config.getInt("GUI.animationSpeed");
        }

        int defaultBet = config.getInt("defaultBet");
        SubInventory rewardsGui = this.loadSubInventory(config, "RewardsGUI");
        ItemStack slotMachineItem = this.loadItem(config, "Machine");
        double[] entityOffset = new double[]{config.getDouble("Machine.Offset.x"), config.getDouble("Machine.Offset.y"), config.getDouble("Machine.Offset.z")};

        assert displaySlots != null;

        return new SlotMachine(name, slotMachineItem, entityOffset, inventoryTitle, baseInventory, displaySlots, spinButton, moneyButton, rewardsGuiButton, new Button(new HashSet(config.getIntegerList("GUI.closeButton"))), rewardsGui, itemsWeighed, totalWeight, rewards, new InventoryAnimations(animations, dependentAnimations), animationDuration, defaultBet, animationStartingSpeed);
    }

    public SubInventory loadSubInventory(FileConfiguration config, String path) {
        Inventory baseInventory = Bukkit.createInventory((InventoryHolder)null, config.getInt(path + ".size"));
        String inventoryTitle = ChatColor.translateAlternateColorCodes('&', (String)Objects.requireNonNull(config.getString(path + ".title")));
        List<ItemAnimation> animations = new ArrayList();
        List<ItemAnimation> dependentAnimations = new ArrayList();
        Button backButton = new Button(new HashSet(config.getIntegerList(path + ".backButton")));
        this.loadAllItems(config, baseInventory, animations, dependentAnimations, path);
        return new SubInventory(baseInventory, inventoryTitle, new InventoryAnimations(animations, dependentAnimations), backButton);
    }

    public void loadAllItems(FileConfiguration config, Inventory baseInventory, List<ItemAnimation> animations, List<ItemAnimation> dependentAnimations, String path) {
        ConfigurationSection configSection = config.getConfigurationSection(path + ".Items");
        if (configSection != null) {
            for(String key : configSection.getKeys(false)) {
                ItemStack item = this.loadItem(config, path + ".Items." + key);
                List<Integer> slots = null;
                if (config.contains(path + ".Items." + key + ".slots")) {
                    slots = config.getIntegerList(path + ".Items." + key + ".slots");

                    for(int i : slots) {
                        baseInventory.setItem(i, item);
                    }
                } else {
                    baseInventory.setItem(config.getInt(path + ".Items." + key + ".slot"), item);
                }

                if (config.contains(path + ".Items." + key + ".animation")) {
                    if (slots == null) {
                        slots = Arrays.asList(config.getInt(path + ".Items." + key + ".slot"));
                    }

                    List<Material> materials = new ArrayList();

                    for(String material : config.getStringList(path + ".Items." + key + ".animation.materials")) {
                        materials.add(Material.valueOf(material));
                    }

                    int vary = 1;
                    if (config.contains(path + ".Items." + key + ".animation.vary")) {
                        vary = config.getInt(path + ".Items." + key + ".animation.vary");
                    }

                    if (config.contains(path + ".Items." + key + ".animation.dependent") && config.getBoolean(path + ".Items." + key + ".animation.dependent")) {
                        dependentAnimations.add(new ItemAnimation(materials, slots, config.getInt(path + ".Items." + key + ".animation.delay"), vary));
                    } else {
                        animations.add(new ItemAnimation(materials, slots, config.getInt(path + ".Items." + key + ".animation.delay"), vary));
                    }
                }
            }

        }
    }

    public ItemStack loadItem(FileConfiguration config, String path) {
        Material material = Material.STONE;

        try {
            material = Material.valueOf(((String)Objects.requireNonNull(config.getString(path + ".material"))).toUpperCase());
        } catch (IllegalArgumentException var8) {
            var8.printStackTrace();
        }

        ItemStack item = new ItemStack(material);
        if (config.contains(path + ".amount")) {
            item.setAmount(config.getInt(path + ".amount"));
        }

        ItemMeta itemMeta = item.getItemMeta();

        assert itemMeta != null;

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

        if (config.contains(path + ".customModelData")) {
            itemMeta.setCustomModelData(config.getInt(path + ".customModelData"));
        }

        if (material.equals(Material.PLAYER_HEAD) && config.contains(path + ".owner")) {
            SkullMeta skullMeta = (SkullMeta)itemMeta;
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(config.getString(path + ".owner")));
        }

        item.setItemMeta(itemMeta);
        return item;
    }
}