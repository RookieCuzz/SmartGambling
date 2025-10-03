package me.arthed.smartgambling.games.jackpot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.utils.DisplayUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

public class JackpotMachine implements Machine {
    private final ItemStack machineItem;
    private final double[] entityOffset;
    private final Inventory baseInventory;
    private final InventoryAnimations animations;
    private final String inventoryTitle;
    private final String inventoryTitleAfterBet;
    private final List<Integer> playerHeadSlots;
    private final ItemStack basePlayerHead;
    private final HashMap<List<Integer>, ItemStack> nextPageItems;
    private final HashMap<List<Integer>, ItemStack> previousPageItems;
    private final HashMap<List<Integer>, ItemStack> betItems;
    private final HashMap<List<Integer>, ItemStack> removeBetItems;
    private final Button closeButton;
    private final HashSet<Player> activePlayers;
    private final Inventory baseGameInventory;
    private final InventoryAnimations gameAnimations;
    private final String gameInventoryTitle;
    private final List<Integer> gamePlayerHeadSlots;
    private final int winningHeadSlot;
    private final int gameDuration;
    private final int timeBetweenGames;
    private final int timeAddedOnBet;
    private final int animationDuration;
    public int timeLeft;
    public final HashMap<Player, Integer> bets;
    private NavigableMap<Integer, ItemStack> weighedBets;
    public int totalBets = 0;
    public boolean spinning;
    public BukkitTask timerTask;
    private int animationSpeed;
    private boolean openingInventory;

    public JackpotMachine(ItemStack machineItem, double[] entityOffset, Inventory baseInventory, InventoryAnimations animations, String inventoryTitle, String inventoryTitleAfterBet, List<Integer> playerHeadSlots, ItemStack basePlayerHead, HashMap<List<Integer>, ItemStack> nextPageItems, HashMap<List<Integer>, ItemStack> previousPageItems, HashMap<List<Integer>, ItemStack> betItems, HashMap<List<Integer>, ItemStack> removeBetItems, Button closeButton, Inventory baseGameInventory, String gameInventoryTitle, InventoryAnimations gameAnimations, List<Integer> gamePlayerHeadSlots, int winningHeadSlot, int gameDuration, int timeBetweenGames, int timeAddedOnBet, int animationDuration) {
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.baseInventory = baseInventory;
        this.animations = animations;
        this.inventoryTitle = inventoryTitle;
        this.inventoryTitleAfterBet = inventoryTitleAfterBet;
        this.playerHeadSlots = playerHeadSlots;
        this.basePlayerHead = basePlayerHead;
        this.nextPageItems = nextPageItems;
        this.previousPageItems = previousPageItems;
        this.betItems = betItems;
        this.removeBetItems = removeBetItems;
        this.closeButton = closeButton;
        this.baseGameInventory = baseGameInventory;
        this.gameAnimations = gameAnimations;
        this.gameInventoryTitle = gameInventoryTitle;
        this.gamePlayerHeadSlots = gamePlayerHeadSlots;
        this.winningHeadSlot = winningHeadSlot;
        this.gameDuration = gameDuration;
        this.timeBetweenGames = timeBetweenGames;
        this.timeAddedOnBet = timeAddedOnBet;
        this.animationDuration = animationDuration;
        this.bets = new HashMap();
        this.activePlayers = new HashSet();
        this.timeLeft = this.gameDuration;
        this.startTimer();
    }

    private void startTimer() {
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

            if (this.timeLeft <= 0) {
                Bukkit.getScheduler().runTask(SmartGambling.getInstance(), this::startGame);
            } else if (this.timeLeft <= 60 && (this.timeLeft <= 5 || this.timeLeft <= 15 && this.timeLeft % 5 == 0 || this.timeLeft % 30 == 0)) {
                for(Player player : this.bets.keySet()) {
                    player.sendMessage(String.format((String)configManager.messages.get("jackpotReminder"), this.timeLeft));
                }
            }

        }, 20L, 20L);
    }

    public void open(Player player, OpenInterface openInterface) {
        if (this.spinning) {
            ConfigManager configManager = SmartGambling.getInstance().configManager;
            String timeUnit = this.timeLeft > 60 ? (String)configManager.messages.get("minutes") : (String)configManager.messages.get("seconds");
            String time = "" + (this.timeLeft > 60 ? this.timeLeft / 60 : this.timeLeft);
            if (this.timeLeft == 0) {
                player.sendMessage(String.format((String)configManager.messages.get("jackpotAlreadyStarted"), time + " " + timeUnit));
            } else {
                player.sendMessage(String.format((String)configManager.messages.get("jackpotNextGame"), time + " " + timeUnit));
            }

        } else if (!SmartGambling.getInstance().openMachines.containsKey(player)) {
            int bet = openInterface.betAmount;
            if (this.bets.containsKey(player) && this.bets.get(player) > 0) {
                bet = this.bets.get(player);
            }

            openInterface.betAmount = 0;
            Inventory playerInventory;
            if (bet > 0) {
                playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), this.inventoryTitleAfterBet.replace("%bet%", "" + bet).replace("%chance%", "" + bet * 100 / (bet + this.totalBets)));
            } else {
                playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), this.inventoryTitle);
            }

            playerInventory.setContents(this.baseInventory.getContents());
            openInterface.inventory = playerInventory;
            this.animations.startAnimations(playerInventory);
            if (!this.bets.containsKey(player) && bet > 0) {
                this.placeBet(player, bet);
            }

            this.addBetsToInventory(playerInventory);
            if (bet == 0) {
                this.addCustomItem(this.betItems, playerInventory, player);
            } else {
                this.addCustomItem(this.removeBetItems, playerInventory, player);
            }

            player.openInventory(playerInventory);
            SmartGambling.getInstance().openMachines.put(player, openInterface);
            if (!this.activePlayers.contains(player)) {
                this.activePlayers.add(player);
            }

        }
    }

    public void close(Player player, Inventory inventory) {
        if (!this.activePlayers.contains(player)) {
            SmartGambling.getInstance().openMachines.remove(player);
        } else if (!this.spinning) {
            if (this.openingInventory) {
                if (inventory != null) {
                    this.animations.stopAnimations(inventory);
                }

            } else {
                if (inventory != null) {
                    this.animations.stopAnimations(inventory);
                }

                SmartGambling.getInstance().openMachines.remove(player);
                this.activePlayers.remove(player);
            }
        } else {
            if (inventory != null && inventory.equals(this.baseInventory)) {
                Bukkit.getScheduler().runTask(SmartGambling.getInstance(), () -> player.openInventory(inventory));
            } else {
                this.animations.stopAnimations(inventory);
            }

        }
    }

    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!this.spinning) {
            if (this.closeButton.isClicked(event.getSlot())) {
                event.getWhoClicked().closeInventory();
            } else {
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
            if (this.totalBets == 0) {
                this.restartGame();
            } else {
                this.timeLeft = 0;
                int bets = 0;
                this.weighedBets = new TreeMap();
                Set<Player> players = new HashSet(this.activePlayers);
                this.openingInventory = true;

                for(Player player : players) {
                    player.openInventory(this.baseGameInventory);
                    ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).inventory = this.baseGameInventory;
                }

                this.openingInventory = false;
                this.activePlayers.addAll(players);
                this.spinning = true;

                for(Entry<Player, Integer> entry : this.bets.entrySet()) {
                    bets += entry.getValue();
                    this.weighedBets.put(bets, this.getPlayerHead((Player)entry.getKey()));
                }

                this.gameAnimations.startAnimations(this.baseGameInventory);
                this.animationSpeed = 6;
                this.spin();

                for(int j = 0; j < 3; ++j) {
                    Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> --this.animationSpeed, (long)j * 4L);
                }

                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    for(int j = 0; j < 3; ++j) {
                        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> ++this.animationSpeed, (long)j * 20L);
                    }

                }, (long)this.animationDuration);
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> this.animationSpeed = 0, (long)(this.animationDuration + 60));
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    ItemStack winningHead = this.baseGameInventory.getItem(this.winningHeadSlot);
                    OfflinePlayer winner = ((SkullMeta)winningHead.getItemMeta()).getOwningPlayer();
                    float amountWon = (float)this.totalBets;
                    SmartGambling.deposit(winner, (double)amountWon);
                    double balance = SmartGambling.getBalance(winner);
                    if (winner instanceof Player) {
                        Player player = (Player)winner;
                        player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("jackpotWinOwn"), amountWon, balance));
                        player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("wonMoney"), amountWon, balance));
                        ((CustomSound)SmartGambling.getInstance().customSounds.get("lotteryWin")).play(player);
                    }

                    for(Player player : this.activePlayers) {
                        if (!player.equals(winner)) {
                            player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("jackpotWin"), winner.getName(), amountWon));
                        }
                    }

                    this.bets.clear();
                    this.totalBets = 0;
                    this.gameAnimations.stopAnimations(this.baseGameInventory);
                    Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), this::restartGame, 100L);
                }, (long)(this.animationDuration + 80));
            }
        }
    }

    private void restartGame() {
        this.bets.clear();
        this.spinning = false;
        Set<Player> players = new HashSet(this.activePlayers);
        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            for(Player player : players) {
                SmartGambling.getInstance().openMachines.remove(player);
                player.closeInventory();
            }

        }, 10L);
        this.activePlayers.clear();
        this.spinning = true;
        this.timeLeft = this.timeBetweenGames;
        this.timerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(SmartGambling.getInstance(), () -> --this.timeLeft, 20L, 20L);

        for(int i : this.gamePlayerHeadSlots) {
            this.baseGameInventory.setItem(i, (ItemStack)null);
        }

        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            Bukkit.getScheduler().cancelTask(this.timerTask.getTaskId());
            this.spinning = false;
            this.timeLeft = this.gameDuration;
            this.startTimer();
        }, (long)this.timeBetweenGames * 20L);
    }

    private void spin() {
        ItemStack nextPlayer = this.getRandomPlayer();

        for(int i = this.gamePlayerHeadSlots.size() - 1; i > 0; --i) {
            this.baseGameInventory.setItem(this.gamePlayerHeadSlots.get(i), this.baseGameInventory.getItem(this.gamePlayerHeadSlots.get(i - 1)));
        }

        this.baseGameInventory.setItem(this.gamePlayerHeadSlots.get(0), nextPlayer);

        for(Player player : this.activePlayers) {
            player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.1F, 0.5F);
        }

        if (this.animationSpeed > 0) {
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), this::spin, (long)this.animationSpeed);
        }

    }

    private ItemStack getRandomPlayer() {
        int number = 0;
        if (this.totalBets > 0) {
            number = SmartGambling.getInstance().random.nextInt(this.totalBets);
        }

        return (ItemStack)this.weighedBets.higherEntry(number).getValue();
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

    private int getChance(int bet) {
        return bet * 100 / this.totalBets;
    }

    public void placeBet(Player player, int amount) {
        if (SmartGambling.getBalance(player) < (double)amount) {
            DisplayUtils.displayActionBar(player, String.format((String)SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"), amount, SmartGambling.getBalance(player)));
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
        } else {
            this.bets.put(player, amount);
            this.totalBets += amount;
            SmartGambling.withdraw(player, (double)amount);
            player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), amount, SmartGambling.getBalance(player)));
            if (this.timeLeft < 30) {
                this.timeLeft += this.timeAddedOnBet;
                if (this.timeLeft > this.gameDuration) {
                    this.timeLeft = this.gameDuration;
                }
            }

            this.updatePlayerBets();
        }
    }

    public void removeBet(Player player) {
        int bet = this.bets.get(player);
        this.totalBets -= bet;
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

    public void addBetsToInventory(Inventory inventory) {
        int i = 0;

        for(Player opponent : this.bets.keySet()) {
            int bet = this.bets.get(opponent);
            ItemStack playerItem = this.getPlayerHead(opponent);
            inventory.setItem(this.playerHeadSlots.get(i), playerItem);
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

    private String replacePlaceholders(String string, Player player, int bet) {
        return string.replace("%name%", player.getName()).replace("%bet%", "" + bet).replace("%chance%", "" + this.getChance(bet));
    }
}