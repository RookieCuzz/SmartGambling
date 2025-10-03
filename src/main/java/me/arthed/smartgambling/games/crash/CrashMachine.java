// Decompiled with: FernFlower
// Class Version: 17
package me.arthed.smartgambling.games.crash;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.utils.DisplayUtils;
import me.arthed.smartgambling.utils.MathUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

public class CrashMachine implements Machine {
    private final ItemStack machineItem;
    private final double[] entityOffset;
    private final Inventory baseInventory;
    private final InventoryAnimations animations;
    private final String inventoryTitle;
    private final String inventoryTitleAfterBet;
    private final List<Integer> playerHeadSlots;
    private final ItemStack basePlayerHead;
    private final ItemStack crashedPlayerHead;
    private final ItemStack crashButton;
    private final ItemStack crashedButton;
    private final List<Integer> crashButtonSlots;
    private final HashMap<List<Integer>, ItemStack> nextPageItems;
    private final HashMap<List<Integer>, ItemStack> previousPageItems;
    private final HashMap<List<Integer>, ItemStack> betItems;
    private final HashMap<List<Integer>, ItemStack> removeBetItems;
    private final Button closeButton;
    private final HashSet<Player> activePlayers;
    private final int gameInventorySize;
    private final String baseGameInventoryTitle;
    private final String crashedGameInventoryTitle;
    private final String endGameInventoryTitle;
    private final Inventory baseGameInventory;
    private final Inventory crashedGameInventory;
    private final InventoryAnimations gameAnimations;
    private final String gameInventoryTitle;
    private final Inventory endGameInventory;
    private final List<Integer> gamePlayerHeadSlots;
    private final int gameDuration;
    private final int timeBetweenGames;
    private final NavigableMap<Integer, Double> chances;
    private final int totalChances;
    private final List<Double> chanceLimits;
    public int timeLeft;
    public final HashMap<Player, Integer> bets;
    public HashMap<Player, Double> crashedAt;
    public boolean crashing;
    public BukkitTask timerTask;
    public BukkitTask increasingValue;
    public double value;
    private boolean openingInventory;

    public CrashMachine(ItemStack machineItem, double[] entityOffset, Inventory baseInventory, InventoryAnimations animations, String inventoryTitle, String inventoryTitleAfterBet, List<Integer> playerHeadSlots, ItemStack basePlayerHead, ItemStack crashedPlayerHead, ItemStack crashButton, ItemStack crashedButton, List<Integer> crashButtonSlots, HashMap<List<Integer>, ItemStack> nextPageItems, HashMap<List<Integer>, ItemStack> previousPageItems, HashMap<List<Integer>, ItemStack> betItems, HashMap<List<Integer>, ItemStack> removeBetItems, Button closeButton, Inventory baseGameInventory, Inventory crashedGameInventory, InventoryAnimations gameAnimations, String gameInventoryTitle, Inventory endGameInventory, List<Integer> gamePlayerHeadSlots, int gameDuration, int timeBetweenGames, NavigableMap<Integer, Double> chances, int totalChances, List<Double> chanceLimits, boolean original, int gameInventorySize, String baseGameInventoryTitle, String crashedGameInventoryTitle, String endGameInventoryTitle) {
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.baseInventory = baseInventory;
        this.animations = animations;
        this.inventoryTitle = inventoryTitle;
        this.inventoryTitleAfterBet = inventoryTitleAfterBet;
        this.playerHeadSlots = playerHeadSlots;
        this.basePlayerHead = basePlayerHead;
        this.crashedPlayerHead = crashedPlayerHead;
        this.crashButton = crashButton;
        this.crashedButton = crashedButton;
        this.crashButtonSlots = crashButtonSlots;
        this.nextPageItems = nextPageItems;
        this.previousPageItems = previousPageItems;
        this.betItems = betItems;
        this.removeBetItems = removeBetItems;
        this.closeButton = closeButton;
        this.gameAnimations = gameAnimations;
        this.gameInventoryTitle = gameInventoryTitle;
        this.gamePlayerHeadSlots = gamePlayerHeadSlots;
        this.gameDuration = gameDuration;
        this.timeBetweenGames = timeBetweenGames;
        this.chances = chances;
        this.chanceLimits = chanceLimits;
        this.gameInventorySize = gameInventorySize;
        this.baseGameInventoryTitle = baseGameInventoryTitle;
        this.crashedGameInventoryTitle = crashedGameInventoryTitle;
        this.endGameInventoryTitle = endGameInventoryTitle;
        this.bets = new HashMap();
        this.baseGameInventory = Bukkit.createInventory((InventoryHolder)null, this.gameInventorySize, this.baseGameInventoryTitle);
        this.baseGameInventory.setContents(baseGameInventory.getContents());
        this.crashedGameInventory = Bukkit.createInventory((InventoryHolder)null, this.gameInventorySize, this.crashedGameInventoryTitle);
        this.crashedGameInventory.setContents(crashedGameInventory.getContents());
        this.endGameInventory = Bukkit.createInventory((InventoryHolder)null, this.gameInventorySize, this.endGameInventoryTitle);
        this.endGameInventory.setContents(endGameInventory.getContents());
        this.activePlayers = new HashSet();
        this.crashedAt = new HashMap();
        this.totalChances = totalChances;
        this.crashedGameInventory.setContents(this.baseGameInventory.getContents());
        if (!original) {
            this.startTimer();
        }

    }

    public CrashMachine clone() {
        return new CrashMachine(this.machineItem, this.entityOffset, this.baseInventory, this.animations, this.inventoryTitle, this.inventoryTitleAfterBet, this.playerHeadSlots, this.basePlayerHead, this.crashedPlayerHead, this.crashButton, this.crashedButton, this.crashButtonSlots, this.nextPageItems, this.previousPageItems, this.betItems, this.removeBetItems, this.closeButton, this.baseGameInventory, this.crashedGameInventory, this.gameAnimations, this.gameInventoryTitle, this.endGameInventory, this.gamePlayerHeadSlots, this.gameDuration, this.timeBetweenGames, this.chances, this.totalChances, this.chanceLimits, false, this.gameInventorySize, this.baseGameInventoryTitle, this.crashedGameInventoryTitle, this.endGameInventoryTitle);
    }

    private void startTimer() {
        this.timeLeft = this.gameDuration;
        ConfigManager configManager = SmartGambling.getInstance().configManager;
        this.timerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(SmartGambling.getInstance(), () -> {
            --this.timeLeft;
            int seconds = this.timeLeft % 60;
            String secondsString;
            if (seconds < 10) {
                secondsString = "0" + seconds;
            } else {
                secondsString = "" + seconds;
            }

            String timer = (String)configManager.messages.get("timeLeft") + this.timeLeft / 60 + ":" + secondsString;

            for(Player player : this.activePlayers) {
                DisplayUtils.displayActionBar(player, timer);
            }

            if (this.timeLeft == 0) {
                Bukkit.getScheduler().runTask(SmartGambling.getInstance(), this::startGame);
            }

        }, 20L, 20L);
    }

    public void open(Player player, OpenInterface openInterface) {
        if (this.crashing) {
            if (this.timeLeft == 0) {
                SmartGambling.getInstance().openMachines.put(player, openInterface);
                player.openInventory(this.crashedGameInventory);
                openInterface.inventory = this.crashedGameInventory;
                this.activePlayers.add(player);
            } else {
                ConfigManager configManager = SmartGambling.getInstance().configManager;
                String timeUnit = this.timeLeft > 60 ? (String)configManager.messages.get("minutes") : (String)configManager.messages.get("seconds");
                String time = "" + (this.timeLeft > 60 ? this.timeLeft / 60 : this.timeLeft);
                player.sendMessage(String.format((String)configManager.messages.get("crashNextGame"), time + " " + timeUnit));
            }
        } else {
            int bet = openInterface.betAmount;
            if (this.bets.containsKey(player) && this.bets.get(player) > 0) {
                bet = this.bets.get(player);
            }

            openInterface.betAmount = 0;
            Inventory playerInventory;
            if (bet > 0) {
                playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), this.inventoryTitleAfterBet.replace("%bet%", "" + bet));
            } else {
                playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), this.inventoryTitle);
            }

            playerInventory.setContents(this.baseInventory.getContents());
            openInterface.inventory = playerInventory;
            this.animations.startAnimations(playerInventory);
            if (!this.bets.containsKey(player) && bet > 0) {
                this.placeBet(player, bet);
            }

            this.addBetsToInventory(playerInventory, this.playerHeadSlots);
            if (bet != 0 && this.bets.containsKey(player)) {
                this.addCustomItem(this.removeBetItems, playerInventory, player);
            } else {
                this.addCustomItem(this.betItems, playerInventory, player);
            }

            player.openInventory(playerInventory);
            SmartGambling.getInstance().openMachines.put(player, openInterface);
            if (!this.activePlayers.contains(player)) {
                this.activePlayers.add(player);
            }

        }
    }

    public void close(Player player, Inventory inventory) {
        if (inventory != null) {
            if (this.crashing) {
                if (!this.openingInventory) {
                    Bukkit.getScheduler().runTask(SmartGambling.getInstance(), () -> player.openInventory(inventory));
                }

                if (this.animations.isAnimated(inventory)) {
                    this.animations.stopAnimations(inventory);
                }

                return;
            }

            if (this.bets.containsKey(player)) {
                this.removeBet(player);
            }

            this.animations.stopAnimations(inventory);
        }

        SmartGambling.getInstance().openMachines.remove(player);
        this.activePlayers.remove(player);
    }

    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!this.crashing) {
            if (this.closeButton.isClicked(event.getSlot())) {
                event.getWhoClicked().closeInventory();
                return;
            }

            if (!this.bets.containsKey((Player)event.getWhoClicked())) {
                for(List<Integer> slots : this.betItems.keySet()) {
                    for(int i : slots) {
                        if (i == event.getSlot()) {
                            SmartGambling.getInstance().moneyInventory.open((Player)event.getWhoClicked(), (OpenInterface)SmartGambling.getInstance().openMachines.get((Player)event.getWhoClicked()));
                            return;
                        }
                    }
                }
            } else {
                for(List<Integer> slots : this.removeBetItems.keySet()) {
                    for(int i : slots) {
                        if (i == event.getSlot()) {
                            this.removeBet((Player)event.getWhoClicked());
                            return;
                        }
                    }
                }
            }
        } else if (event.getInventory().equals(this.baseGameInventory) && this.crashing && this.crashButtonSlots.contains(event.getSlot())) {
            for(int slot : this.gamePlayerHeadSlots) {
                if (this.baseGameInventory.getItem(slot) != null && this.baseGameInventory.getItem(slot).getType().equals(Material.PLAYER_HEAD)) {
                    SkullMeta meta = (SkullMeta)this.baseGameInventory.getItem(slot).getItemMeta();

                    assert meta != null;

                    if (Objects.equals(meta.getOwningPlayer(), event.getWhoClicked())) {
                        Player player = (Player)event.getWhoClicked();
                        this.crashedAt.put(player, MathUtils.roundDecimals(this.value));
                        ItemStack newHead = this.getCrashedPlayerHead(player);
                        this.baseGameInventory.setItem(slot, newHead);
                        this.crashedGameInventory.setItem(slot, newHead);
                        this.openingInventory = true;
                        player.openInventory(this.crashedGameInventory);
                        ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).inventory = this.crashedGameInventory;
                        this.openingInventory = false;
                        return;
                    }
                }
            }
        }

    }

    public ItemStack getMachineItem() {
        return this.machineItem;
    }

    public double[] getMachineEntityOffset() {
        return this.entityOffset;
    }

    private void startGame() {
        if (!this.timerTask.isCancelled()) {
            this.timerTask.cancel();
            if (this.bets.size() == 0) {
                this.restartGame();
            } else {
                this.crashing = true;
                Set<Player> players = new HashSet(this.activePlayers);
                this.openingInventory = true;

                for(Player player : players) {
                    player.closeInventory();
                    if (this.bets.containsKey(player)) {
                        player.openInventory(this.baseGameInventory);
                        ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).inventory = this.baseGameInventory;
                    } else {
                        player.openInventory(this.crashedGameInventory);
                        ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).inventory = this.crashedGameInventory;
                    }
                }

                this.openingInventory = false;
                this.activePlayers.addAll(players);
                double maxToCrashAt = this.chances.higherEntry(SmartGambling.getInstance().random.nextInt(this.totalChances)).getValue();
                double valueToCrash = (double)1.2F;

                for(int i = 0; i < this.chanceLimits.size(); ++i) {
                    if (this.chanceLimits.get(i) == maxToCrashAt) {
                        double minimum = i == 0 ? 0.0D : this.chanceLimits.get(i - 1);
                        valueToCrash = SmartGambling.getInstance().random.nextDouble(minimum, maxToCrashAt);
                        break;
                    }
                }

                this.gameAnimations.startAnimations(this.baseGameInventory);
                this.gameAnimations.startAnimations(this.crashedGameInventory);
                this.addBetsToInventory(this.baseGameInventory, this.gamePlayerHeadSlots);
                this.addBetsToInventory(this.crashedGameInventory, this.gamePlayerHeadSlots);

                for(int crashButtonSlot : this.crashButtonSlots) {
                    this.baseGameInventory.setItem(crashButtonSlot, this.crashButton);
                    this.crashedGameInventory.setItem(crashButtonSlot, this.crashedButton);
                }

                this.value = 0.0D;
                ItemStack crashItem = this.baseGameInventory.getItem(this.crashButtonSlots.get(0));
                ItemMeta crashMeta = crashItem.getItemMeta();
                ItemStack crashItem2 = this.crashedGameInventory.getItem(this.crashButtonSlots.get(0));
                ItemMeta crashMeta2 = crashItem2.getItemMeta();
                String baseName = this.crashButton.getItemMeta().getDisplayName();
                double finalValueToCrash = MathUtils.roundDecimals(valueToCrash);
                this.increasingValue = Bukkit.getScheduler().runTaskTimer(SmartGambling.getInstance(), () -> {
                    this.value += 0.01D;
                    String newName = baseName.replace("%value%", "" + MathUtils.roundDecimals(this.value));
                    crashMeta.setDisplayName(newName);
                    crashMeta2.setDisplayName(newName);

                    for(int crashButtonSlot : this.crashButtonSlots) {
                        this.baseGameInventory.getItem(crashButtonSlot).setItemMeta(crashMeta);
                        this.crashedGameInventory.getItem(crashButtonSlot).setItemMeta(crashMeta2);
                    }

                    for(Player player : this.activePlayers) {
                        player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.1F, 0.5F);
                    }

                    if (this.value >= finalValueToCrash) {
                        this.value = finalValueToCrash;
                        this.stopGame();
                    }

                }, 1L, 1L);
            }
        }
    }

    private void stopGame() {
        Bukkit.getScheduler().cancelTask(this.increasingValue.getTaskId());
        this.gameAnimations.stopAnimations(this.baseGameInventory);
        this.gameAnimations.stopAnimations(this.crashedGameInventory);

        for(Entry<Player, Double> entry : this.crashedAt.entrySet()) {
            double amountWon = (double)Math.round((double)((Integer)this.bets.get(entry.getKey())).intValue() * entry.getValue() * 100.0D) / 100.0D;
            SmartGambling.deposit((OfflinePlayer)entry.getKey(), amountWon);
            double balance = SmartGambling.getBalance((OfflinePlayer)entry.getKey());
            ((CustomSound)SmartGambling.getInstance().customSounds.get("crashWin")).play((Player)entry.getKey());
            ((Player)entry.getKey()).sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("crashWin"), amountWon, entry.getValue(), this.value));
            ((Player)entry.getKey()).sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("wonMoney"), amountWon, balance));
        }

        this.bets.clear();
        this.openingInventory = true;

        for(Player player : this.activePlayers) {
            this.endGameInventory.setContents(this.crashedGameInventory.getContents());
            player.openInventory(this.endGameInventory);
            ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).inventory = this.endGameInventory;
        }

        this.openingInventory = false;
        this.crashing = false;
        this.bets.clear();
        this.crashedAt.clear();
        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), this::restartGame, 100L);
    }

    private void restartGame() {
        for(Player player : (this.activePlayers)) {
            OpenMachine openMachine = (OpenMachine)SmartGambling.getInstance().openMachines.get(player);
            player.closeInventory();
            this.open(player, openMachine);
        }

        this.activePlayers.clear();
        this.timeLeft = this.gameDuration;

        for(int slot : this.gamePlayerHeadSlots) {
            this.baseGameInventory.setItem(slot, (ItemStack)null);
            this.crashedGameInventory.setItem(slot, (ItemStack)null);
        }

        this.startTimer();
    }

    public void addCustomItem(HashMap<List<Integer>, ItemStack> itemMap, Inventory inventory, Player player) {
        for(List<Integer> slots : itemMap.keySet()) {
            ItemStack item = ((ItemStack)itemMap.get(slots)).clone();
            if (this.bets.containsKey(player)) {
                ItemMeta meta = item.getItemMeta();
                if (meta.hasDisplayName()) {
                    meta.setDisplayName(this.replacePlaceholders(meta.getDisplayName(), player, this.bets.get(player)));
                }

                if (meta.hasLore()) {
                    List<String> lore = meta.getLore();
                    int bet = this.bets.get(player);

                    for(int i = 0; i < lore.size(); ++i) {
                        lore.set(i, this.replacePlaceholders((String)lore.get(i), player, bet));
                    }

                    meta.setLore(lore);
                }

                item.setItemMeta(meta);
            }

            for(int i : slots) {
                inventory.setItem(i, item);
            }
        }

    }

    public void removeCustomItem(HashMap<List<Integer>, ItemStack> itemMap, Inventory inventory) {
        for(List<Integer> slots : itemMap.keySet()) {
            for(int i : slots) {
                inventory.setItem(i, (ItemStack)null);
            }
        }

    }

    public void placeBet(Player player, int amount) {
        if (SmartGambling.getBalance(player) < (double)amount) {
            DisplayUtils.displayActionBar(player, String.format((String)SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"), amount, SmartGambling.getBalance(player)));
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
        } else {
            this.bets.put(player, amount);
            SmartGambling.withdraw(player, (double)amount);
            player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), amount, SmartGambling.getBalance(player)));
            this.updatePlayerBets();
        }
    }

    public void removeBet(Player player) {
        int bet = this.bets.get(player);
        this.bets.remove(player);
        SmartGambling.deposit(player, (double)bet);
        player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("moneyReceived"), bet, SmartGambling.getBalance(player)));
        this.updatePlayerBets();
        Inventory inventory = ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).inventory;
        this.removeCustomItem(this.removeBetItems, inventory);
        this.addCustomItem(this.betItems, inventory, player);
    }

    public void updatePlayerBets() {
        for(Player player : this.activePlayers) {
            this.open(player, (OpenInterface)SmartGambling.getInstance().openMachines.get(player));
        }

    }

    public void addBetsToInventory(Inventory inventory, List<Integer> headSlots) {
        int i = 0;

        for(Player opponent : this.bets.keySet()) {
            ItemStack playerItem = this.getPlayerHead(opponent);
            inventory.setItem(headSlots.get(i), playerItem);
            ++i;
        }

    }

    public ItemStack getPlayerHead(Player player) {
        int bet = this.bets.get(player);
        ItemStack playerItem = this.basePlayerHead.clone();
        SkullMeta meta = (SkullMeta)playerItem.getItemMeta();

        assert meta != null;

        meta.setOwningPlayer(player);
        meta.setDisplayName(this.replacePlaceholders(meta.getDisplayName(), player, bet));
        List<String> lore = meta.getLore();
        if (lore != null) {
            for(int j = 0; j < lore.size(); ++j) {
                lore.set(j, this.replacePlaceholders((String)lore.get(j), player, bet));
            }

            meta.setLore(lore);
        }

        playerItem.setItemMeta(meta);
        return playerItem;
    }

    public ItemStack getCrashedPlayerHead(Player player) {
        int bet = this.bets.get(player);
        ItemStack playerItem = this.crashedPlayerHead.clone();
        ItemMeta meta = playerItem.getItemMeta();

        assert meta != null;

        if (playerItem.getType().equals(Material.PLAYER_HEAD)) {
            SkullMeta skullMeta = (SkullMeta)meta;
            skullMeta.setOwningPlayer(player);
        }

        meta.setDisplayName(this.replacePlaceholders(meta.getDisplayName(), player, bet));
        List<String> lore = meta.getLore();
        if (lore != null) {
            for(int j = 0; j < lore.size(); ++j) {
                lore.set(j, this.replacePlaceholders((String)lore.get(j), player, bet));
            }

            meta.setLore(lore);
        }

        playerItem.setItemMeta(meta);
        return playerItem;
    }

    private String replacePlaceholders(String string, Player player, int bet) {
        return string.replace("%name%", player.getName()).replace("%bet%", "" + bet).replace("%crash%", "" + (this.crashedAt.containsKey(player) ? this.crashedAt.get(player) : 0.0D));
    }
}