package me.arthed.smartgambling.games.blackjack;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.utils.DisplayUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class BlackJack
        implements Machine {
    public final ItemStack machineItem;
    public final double[] entityOffset;
    public final double[] chair1Offset;
    public final double[] chair2Offset;
    private final Inventory baseInventory;
    private final InventoryAnimations animations;
    private final String inventoryTitle;
    private final String inventoryTitleStand;
    private final String inventoryTitleLost;
    private final String inventoryTitleWin;
    private final String inventoryTitleDraw;
    private final Button hitButton;
    private final Button standButton;
    private final ItemStack cardBack;
    private final List<Integer> cardSlots;
    private final List<Integer> opponentCardSlots;
    private final List<Integer> placeholderSlots;
    private final NavigableMap<Integer, PlayingCard> cards;
    private final int cardsTotalChance;
    private BukkitTask waitingMessageTask;

    public BlackJack(ItemStack machineItem, double[] entityOffset, double[] char1Offset, double[] chair2Offset, Inventory baseInventory, InventoryAnimations animations, String inventoryTitle, String inventoryTitleStand, String inventoryTitleLost, String inventoryTitleWin, String inventoryTitleDraw, Button hitButton, Button standButton, ItemStack cardBack, List<Integer> cardSlots, List<Integer> opponentCardSlots, List<Integer> placeholderSlots, NavigableMap<Integer, PlayingCard> cards, int cardsTotalChance) {
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.chair1Offset = char1Offset;
        this.chair2Offset = chair2Offset;
        this.baseInventory = baseInventory;
        this.animations = animations;
        this.inventoryTitle = inventoryTitle;
        this.inventoryTitleStand = inventoryTitleStand;
        this.inventoryTitleLost = inventoryTitleLost;
        this.inventoryTitleWin = inventoryTitleWin;
        this.inventoryTitleDraw = inventoryTitleDraw;
        this.hitButton = hitButton;
        this.standButton = standButton;
        this.cardBack = cardBack;
        this.cardSlots = cardSlots;
        this.opponentCardSlots = opponentCardSlots;
        this.placeholderSlots = placeholderSlots;
        this.cards = cards;
        this.cardsTotalChance = cardsTotalChance;
    }


    @Override
    public void open(final Player player, final OpenInterface openInterface) {
        player.closeInventory();
        final OpenBlackjack openBlackjack = (OpenBlackjack)openInterface;
        final MachineDataBlackjack machineData = (MachineDataBlackjack)openBlackjack.machineData;
        SmartGambling.getInstance().openMachines.put(player, openInterface);
        if (machineData.player1 == null) {
            if (openInterface.betAmount == 0) {
                SmartGambling.getInstance().moneyInventory.open(player, openBlackjack);
                machineData.inUse = true;
                return;
            }
            if (openInterface.betAmount == -1) {
                machineData.inUse = false;
                SmartGambling.getInstance().openMachines.remove(player);
                return;
            }
            machineData.inUse = false;
            machineData.bet = openInterface.betAmount;
            machineData.player1 = player;
            SmartGambling.getEconomy().withdrawPlayer((OfflinePlayer)player, (double)openBlackjack.betAmount);
            final double balance = SmartGambling.getEconomy().getBalance((OfflinePlayer)player);
            player.sendMessage(String.format(SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), openBlackjack.betAmount, balance));
            openBlackjack.machineData.entities[0].addPassenger((Entity)player);
            player.setRotation(180.0f, 0.0f);
            this.waitingMessageTask = Bukkit.getScheduler().runTaskTimer((Plugin)SmartGambling.getInstance(), () -> DisplayUtils.displayActionBar(player, SmartGambling.getInstance().configManager.messages.get("waitingForOpponent")), 20L, 20L);
        }
        else if (player != machineData.player1) {
            if (openInterface.betAmount == 0) {
                SmartGambling.getInstance().confirmGameInventory.open(player, openBlackjack);
                machineData.inUse = true;
                return;
            }
            if (openInterface.betAmount == -1) {
                SmartGambling.getInstance().openMachines.remove(player);
                machineData.inUse = false;
                return;
            }
            machineData.inUse = true;
            machineData.player2 = player;
            SmartGambling.getEconomy().withdrawPlayer((OfflinePlayer)player, (double)openBlackjack.betAmount);
            final double balance = SmartGambling.getEconomy().getBalance((OfflinePlayer)player);
            player.sendMessage(String.format(SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), openBlackjack.betAmount, balance));
            openBlackjack.machineData.entities[1].addPassenger((Entity)player);
            player.setRotation(0.0f, 0.0f);
            this.startGame(machineData.player1, machineData.player2, machineData);
        }
    }

    @Override
    public void close(Player player, Inventory inventory) {
        OpenBlackjack openBlackjack = (OpenBlackjack)SmartGambling.getInstance().openMachines.get(player);
        if (openBlackjack == null) {
            return;
        }
        MachineDataBlackjack machineData = (MachineDataBlackjack)openBlackjack.machineData;
        if (machineData.player1 == null) {
            return;
        }
        if (!machineData.startGame && machineData.player2 == null) {
            SmartGambling.getEconomy().depositPlayer((OfflinePlayer)player, (double)openBlackjack.betAmount);
            double balance = SmartGambling.getEconomy().getBalance((OfflinePlayer)player);
            player.sendMessage(String.format(SmartGambling.getInstance().configManager.messages.get("moneyReceived"), openBlackjack.betAmount, balance));
            machineData.inUse = false;
            machineData.player1 = null;
            this.waitingMessageTask.cancel();
            openBlackjack.machineData.entities[1].removePassenger((Entity)player);
            player.teleport(player.getLocation().add(1.0, 0.0, 0.0));
        } else if (machineData.startGame && (inventory.equals(machineData.player1Inventory) || inventory.equals(machineData.player2Inventory))) {
            Bukkit.getScheduler().runTask((Plugin)SmartGambling.getInstance(), () -> player.openInventory(inventory));
            return;
        }
        SmartGambling.getInstance().openMachines.remove(player);
    }

    public void startGame(Player player1, Player player2, MachineDataBlackjack machineData) {
        this.waitingMessageTask.cancel();
        machineData.startGame = true;
        Inventory playerInventory1 = Bukkit.createInventory((InventoryHolder)player1, (int)this.baseInventory.getSize(), (String)this.inventoryTitle);
        Inventory playerInventory2 = Bukkit.createInventory((InventoryHolder)player2, (int)this.baseInventory.getSize(), (String)this.inventoryTitle);
        playerInventory1.setContents(this.baseInventory.getContents());
        playerInventory2.setContents(this.baseInventory.getContents());
        machineData.player1Inventory = playerInventory1;
        machineData.player2Inventory = playerInventory2;
        OpenBlackjack openBlackjack1 = (OpenBlackjack)SmartGambling.getInstance().openMachines.get(player1);
        OpenBlackjack openBlackjack2 = (OpenBlackjack)SmartGambling.getInstance().openMachines.get(player2);
        openBlackjack1.inventory = playerInventory1;
        openBlackjack2.inventory = playerInventory2;
        this.animations.startAnimations(playerInventory1);
        this.animations.startAnimations(playerInventory2);
        machineData.player1Cards = new ArrayList<PlayingCard>();
        machineData.player2Cards = new ArrayList<PlayingCard>();
        machineData.player1Value = 0;
        machineData.player2Value = 0;
        this.addCard(playerInventory1, machineData, true, true);
        this.addCard(playerInventory1, machineData, true, false);
        this.addCard(playerInventory2, machineData, false, true);
        this.addCard(playerInventory2, machineData, false, false);
        player1.openInventory(playerInventory1);
        player2.openInventory(playerInventory2);
    }

    public void addCard(Inventory inventory, MachineDataBlackjack machineData, boolean firstPlayer, boolean showOpponent) {
        String opponentStatus;
        List<PlayingCard> cards = firstPlayer ? machineData.player1Cards : machineData.player2Cards;
        PlayingCard card = this.getRandomCard();
        cards.add(card);
        ItemStack item = cards.get(cards.size() - 1).getRandomItem();
        inventory.setItem(this.cardSlots.get(cards.size() - 1).intValue(), item);
        Inventory opponentInventory = firstPlayer ? machineData.player2Inventory : machineData.player1Inventory;
        Player player = firstPlayer ? machineData.player1 : machineData.player2;
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.5f, 1.0f);
        if (showOpponent) {
            opponentInventory.setItem(this.opponentCardSlots.get(cards.size() - 1).intValue(), item);
        } else {
            opponentInventory.setItem(this.opponentCardSlots.get(cards.size() - 1).intValue(), this.cardBack);
        }
        if (firstPlayer) {
            opponentStatus = machineData.player2stopped ? SmartGambling.getInstance().configManager.messages.get("opponentStopped") : SmartGambling.getInstance().configManager.messages.get("opponentPlaying");
            machineData.player1Value += card.value();
        } else {
            opponentStatus = machineData.player1stopped ? SmartGambling.getInstance().configManager.messages.get("opponentStopped") : SmartGambling.getInstance().configManager.messages.get("opponentPlaying");
            machineData.player2Value += card.value();
        }
        String amount = "" + (firstPlayer ? machineData.player1Value : machineData.player2Value);
        for (int slot : this.placeholderSlots) {
            ItemStack itemPlaceholder = inventory.getItem(slot);
            if (itemPlaceholder == null) continue;
            ItemMeta metaPH = itemPlaceholder.getItemMeta();
            if (metaPH.hasDisplayName()) {
                metaPH.setDisplayName(this.baseInventory.getItem(slot).getItemMeta().getDisplayName().replace("%amount%", amount).replace("%opponent_status%", opponentStatus));
            }
            if (metaPH.hasLore()) {
                ArrayList<String> lore = new ArrayList<String>();
                for (String s : this.baseInventory.getItem(slot).getItemMeta().getLore()) {
                    lore.add(s.replace("%amount%", amount).replace("%opponent_status%", opponentStatus));
                }
                metaPH.setLore(lore);
            }
            itemPlaceholder.setItemMeta(metaPH);
        }
        if (firstPlayer) {
            if (machineData.player1Value >= 21 || cards.size() == this.cardSlots.size()) {
                this.standClick(machineData, true);
            }
        } else if (machineData.player2Value >= 21 || cards.size() == this.cardSlots.size()) {
            this.standClick(machineData, false);
        }
    }

    private PlayingCard getRandomCard() {
        int number = SmartGambling.getInstance().random.nextInt(this.cardsTotalChance);
        return this.cards.higherEntry(number).getValue();
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (this.hitButton.isClicked(event.getSlot())) {
            boolean firstPlayer;
            Player player = (Player)event.getWhoClicked();
            OpenBlackjack openBlackjack1 = (OpenBlackjack)SmartGambling.getInstance().openMachines.get(player);
            MachineDataBlackjack machineData = (MachineDataBlackjack)openBlackjack1.machineData;
            boolean bl = firstPlayer = machineData.player1 == player;
            if (firstPlayer ? machineData.player1stopped : machineData.player2stopped) {
                return;
            }
            this.addCard(event.getInventory(), machineData, firstPlayer, false);
        } else if (this.standButton.isClicked(event.getSlot())) {
            Player player = (Player)event.getWhoClicked();
            OpenBlackjack openBlackjack1 = (OpenBlackjack)SmartGambling.getInstance().openMachines.get(player);
            MachineDataBlackjack machineData = (MachineDataBlackjack)openBlackjack1.machineData;
            boolean firstPlayer = machineData.player1 == player;
            this.standClick(machineData, firstPlayer);
        }
    }

    public void standClick(MachineDataBlackjack machineData, boolean firstPlayer) {
        Inventory inventory;
        if (firstPlayer) {
            machineData.player1stopped = true;
            machineData.player1.playSound(machineData.player1.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            this.animations.stopAnimations(machineData.player1Inventory);
            inventory = Bukkit.createInventory((InventoryHolder)machineData.player1, (int)this.baseInventory.getSize(), (String)this.inventoryTitleStand);
            inventory.setContents(machineData.player1Inventory.getContents());
            this.animations.startAnimations(inventory);
            machineData.player1Inventory = inventory;
            SmartGambling.getInstance().openMachines.get((Object)machineData.player1).inventory = inventory;
            machineData.player1.openInventory(inventory);
        } else {
            machineData.player2stopped = true;
            machineData.player2.playSound(machineData.player2.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            this.animations.stopAnimations(machineData.player2Inventory);
            inventory = Bukkit.createInventory((InventoryHolder)machineData.player2, (int)this.baseInventory.getSize(), (String)this.inventoryTitleStand);
            inventory.setContents(machineData.player2Inventory.getContents());
            this.animations.startAnimations(inventory);
            machineData.player2Inventory = inventory;
            SmartGambling.getInstance().openMachines.get((Object)machineData.player2).inventory = inventory;
            machineData.player2.openInventory(inventory);
        }
        inventory = firstPlayer ? machineData.player2Inventory : machineData.player1Inventory;
        for (int slot : this.placeholderSlots) {
            ItemStack itemPlaceholder = inventory.getItem(slot);
            ItemMeta metaPH = itemPlaceholder.getItemMeta();
            if (metaPH.hasDisplayName()) {
                metaPH.setDisplayName(metaPH.getDisplayName().replace(SmartGambling.getInstance().configManager.messages.get("opponentPlaying"), SmartGambling.getInstance().configManager.messages.get("opponentStopped")));
            }
            if (metaPH.hasLore()) {
                ArrayList<String> lore = new ArrayList<String>();
                for (String s : metaPH.getLore()) {
                    lore.add(s.replace(SmartGambling.getInstance().configManager.messages.get("opponentPlaying"), SmartGambling.getInstance().configManager.messages.get("opponentStopped")));
                }
                metaPH.setLore(lore);
            }
            itemPlaceholder.setItemMeta(metaPH);
        }
        if (machineData.player1stopped && machineData.player2stopped) {
            this.finishGame(machineData);
        }
    }

    public void finishGame(MachineDataBlackjack machineData) {
        String loserTitle;
        String winnerTitle;
        Player loser;
        Player winner;
        if (!machineData.startGame) {
            return;
        }
        machineData.startGame = false;
        if (machineData.player2Value > 21 && machineData.player1Value > 21) {
            winner = machineData.player1;
            loser = machineData.player2;
            winnerTitle = this.inventoryTitleDraw;
            loserTitle = this.inventoryTitleDraw;
            ConfigManager configManager = SmartGambling.getInstance().configManager;
            winner.sendMessage(String.format(configManager.messages.get("drawBlackJackOver"), machineData.player1Value));
            loser.sendMessage(String.format(configManager.messages.get("drawBlackJackOver"), machineData.player1Value));
        } else if (machineData.player1Value == machineData.player2Value) {
            winner = machineData.player1;
            loser = machineData.player2;
            winnerTitle = this.inventoryTitleDraw;
            loserTitle = this.inventoryTitleDraw;
            ConfigManager configManager = SmartGambling.getInstance().configManager;
            winner.sendMessage(String.format(configManager.messages.get("drawBlackJack"), machineData.player1Value));
            loser.sendMessage(String.format(configManager.messages.get("drawBlackJack"), machineData.player1Value));
            SmartGambling.getEconomy().depositPlayer((OfflinePlayer)winner, (double)machineData.bet);
            double balance1 = SmartGambling.getEconomy().getBalance((OfflinePlayer)winner);
            winner.sendMessage(String.format(SmartGambling.getInstance().configManager.messages.get("moneyReceived"), machineData.bet, balance1));
            SmartGambling.getEconomy().depositPlayer((OfflinePlayer)loser, (double)machineData.bet);
            double balance2 = SmartGambling.getEconomy().getBalance((OfflinePlayer)loser);
            loser.sendMessage(String.format(SmartGambling.getInstance().configManager.messages.get("moneyReceived"), machineData.bet, balance2));
        } else {
            int opponentValue;
            int value;
            winnerTitle = this.inventoryTitleWin;
            loserTitle = this.inventoryTitleLost;
            if (machineData.player1Value > 21) {
                winner = machineData.player2;
                loser = machineData.player1;
                value = machineData.player2Value;
                opponentValue = machineData.player1Value;
            } else if (machineData.player2Value > 21) {
                winner = machineData.player1;
                loser = machineData.player2;
                value = machineData.player1Value;
                opponentValue = machineData.player2Value;
            } else if (machineData.player1Value > machineData.player2Value) {
                winner = machineData.player1;
                loser = machineData.player2;
                value = machineData.player1Value;
                opponentValue = machineData.player2Value;
            } else {
                winner = machineData.player2;
                loser = machineData.player1;
                value = machineData.player2Value;
                opponentValue = machineData.player1Value;
            }
            float amountWon = machineData.bet * 2;
            SmartGambling.getEconomy().depositPlayer((OfflinePlayer)winner, (double)amountWon);
            double balance = SmartGambling.getEconomy().getBalance((OfflinePlayer)winner);
            ConfigManager configManager = SmartGambling.getInstance().configManager;
            winner.sendMessage(String.format(configManager.messages.get("wonBlackJack"), value, opponentValue));
            loser.sendMessage(String.format(configManager.messages.get("lostBlackJack"), opponentValue, value));
            DisplayUtils.displayActionBar(winner, String.format(configManager.messages.get("wonMoneyActionBar"), Float.valueOf(amountWon), balance));
            winner.sendMessage(String.format(configManager.messages.get("wonMoney"), Float.valueOf(amountWon), balance));
            SmartGambling.getInstance().customSounds.get("blackjackWin").play(winner);
        }
        this.animations.stopAnimations(machineData.player1Inventory);
        this.animations.stopAnimations(machineData.player2Inventory);
        Inventory inventory1 = Bukkit.createInventory((InventoryHolder)winner, (int)this.baseInventory.getSize(), (String)winnerTitle);
        Inventory inventory2 = Bukkit.createInventory((InventoryHolder)loser, (int)this.baseInventory.getSize(), (String)loserTitle);
        inventory1.setContents(this.baseInventory.getContents());
        inventory2.setContents(this.baseInventory.getContents());
        this.animations.startAnimations(inventory1);
        this.animations.startAnimations(inventory2);
        SmartGambling.getInstance().openMachines.get((Object)winner).inventory = inventory1;
        SmartGambling.getInstance().openMachines.get((Object)loser).inventory = inventory2;
        if (machineData.player1 == winner) {
            for (int i = 0; i < this.cardSlots.size(); ++i) {
                inventory1.setItem(this.cardSlots.get(i).intValue(), machineData.player1Inventory.getItem(this.cardSlots.get(i).intValue()));
                inventory1.setItem(this.opponentCardSlots.get(i).intValue(), machineData.player2Inventory.getItem(this.cardSlots.get(i).intValue()));
                inventory2.setItem(this.cardSlots.get(i).intValue(), machineData.player2Inventory.getItem(this.cardSlots.get(i).intValue()));
                inventory2.setItem(this.opponentCardSlots.get(i).intValue(), machineData.player1Inventory.getItem(this.cardSlots.get(i).intValue()));
            }
            for (int slot : this.placeholderSlots) {
                inventory1.setItem(slot, machineData.player1Inventory.getItem(slot));
                inventory2.setItem(slot, machineData.player2Inventory.getItem(slot));
            }
            machineData.player1Inventory = inventory1;
            machineData.player1.openInventory(inventory1);
            SmartGambling.getInstance().openMachines.get((Object)machineData.player1).inventory = inventory1;
            machineData.player2Inventory = inventory2;
            machineData.player2.openInventory(inventory2);
            SmartGambling.getInstance().openMachines.get((Object)machineData.player2).inventory = inventory2;
        } else {
            for (int i = 0; i < this.cardSlots.size(); ++i) {
                inventory2.setItem(this.cardSlots.get(i).intValue(), machineData.player1Inventory.getItem(this.cardSlots.get(i).intValue()));
                inventory2.setItem(this.opponentCardSlots.get(i).intValue(), machineData.player2Inventory.getItem(this.cardSlots.get(i).intValue()));
                inventory1.setItem(this.cardSlots.get(i).intValue(), machineData.player2Inventory.getItem(this.cardSlots.get(i).intValue()));
                inventory1.setItem(this.opponentCardSlots.get(i).intValue(), machineData.player1Inventory.getItem(this.cardSlots.get(i).intValue()));
            }
            for (int slot : this.placeholderSlots) {
                inventory1.setItem(slot, machineData.player2Inventory.getItem(slot));
                inventory2.setItem(slot, machineData.player1Inventory.getItem(slot));
            }
            machineData.player2Inventory = inventory1;
            machineData.player2.openInventory(inventory1);
            SmartGambling.getInstance().openMachines.get((Object)machineData.player2).inventory = inventory1;
            machineData.player1Inventory = inventory2;
            machineData.player1.openInventory(inventory2);
            SmartGambling.getInstance().openMachines.get((Object)machineData.player1).inventory = inventory2;
        }
        Bukkit.getScheduler().runTaskLater((Plugin)SmartGambling.getInstance(), () -> {
            this.animations.stopAnimations(machineData.player1Inventory);
            this.animations.stopAnimations(machineData.player2Inventory);
            machineData.entities[0].removePassenger((Entity)machineData.player1);
            machineData.entities[1].removePassenger((Entity)machineData.player2);
            machineData.player1.closeInventory();
            machineData.player2.closeInventory();
            machineData.player1.teleport(machineData.player1.getLocation().add(1.0, 0.0, 0.0));
            machineData.player2.teleport(machineData.player2.getLocation().add(1.0, 0.0, 0.0));
            machineData.player1Inventory = null;
            machineData.player2Inventory = null;
            machineData.player1 = null;
            machineData.player2 = null;
            machineData.player1stopped = false;
            machineData.player2stopped = false;
            machineData.inUse = false;
        }, 80L);
    }

    @Override
    public ItemStack getMachineItem() {
        return this.machineItem;
    }

    @Override
    public double[] getMachineEntityOffset() {
        return this.entityOffset;
    }
}
