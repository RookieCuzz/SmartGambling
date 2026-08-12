package me.arthed.smartgambling.games.common.inventories;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.inventories.SubInventory;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.utils.DisplayUtils;
import me.arthed.smartgambling.utils.EconomyTransactions;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

public class MoneyInventory
        extends SubInventory {
    private final List<Integer> moneyButtonsValue;
    private final List<Button> moneyButtons;
    private final Button customAmountButton;
    private final Set<UUID> requireMoneyBefore = new HashSet<>();

    public MoneyInventory(Inventory baseInventory, String inventoryTitle, InventoryAnimations animations, List<Integer> moneyButtonsValue, List<Button> moneyButtons, Button customAmountButton, Button backButton) {
        super(baseInventory, inventoryTitle, animations, backButton);
        this.moneyButtonsValue = moneyButtonsValue;
        this.moneyButtons = moneyButtons;
        this.customAmountButton = customAmountButton;
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        UUID playerId = player.getUniqueId();
        if (openInterface.machineType == SmartGambling.getInstance().jackpotMachine
                || openInterface.machineType == SmartGambling.getInstance().blackJack) {
            this.requireMoneyBefore.add(playerId);
        } else {
            this.requireMoneyBefore.remove(playerId);
        }
        try {
            super.open(player, openInterface);
        } catch (RuntimeException exception) {
            this.requireMoneyBefore.remove(playerId);
            throw exception;
        }
    }

    @Override
    public void close(Player player, Inventory inventory) {
        if (!SmartGambling.getInstance().inputMoneyRoutine.playersInRoutine.contains(player)) {
            this.requireMoneyBefore.remove(player.getUniqueId());
            super.close(player, inventory);
        }
    }

    @Override
    public void forceClose(Player player) {
        SmartGambling.getInstance().inputMoneyRoutine.cancelRoutine(player);
        this.requireMoneyBefore.remove(player.getUniqueId());
        super.forceClose(player);
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (this.customAmountButton.isClicked(event.getSlot())) {
            SmartGambling.getInstance().inputMoneyRoutine.startRoutine(player);
            return;
        } else {
            for (int i = 0; i < this.moneyButtons.size(); ++i) {
                if (!this.moneyButtons.get(i).isClicked(event.getSlot())) continue;
                int amount = this.moneyButtonsValue.get(i);
                OpenInterface oldInterface = this.oldInterfaces.get(player);
                if (oldInterface == null) {
                    this.forceClose(player);
                    return;
                }
                if (this.inputMoney(player, amount)) {
                    oldInterface.betAmount = amount;
                    this.close(player, event.getView().getTopInventory());
                }
                return;
            }
        }
        super.inventoryClick(event);
    }

    public void inputCustomAmount(Player player, int amount) {
        OpenInterface oldInterface = this.oldInterfaces.get(player);
        if (oldInterface == null) {
            this.forceClose(player);
            return;
        }
        if (this.inputMoney(player, amount)) {
            oldInterface.betAmount = amount;
        }
        this.close(player, null);
    }

    public boolean inputMoney(Player player, int amount) {
        if (!EconomyTransactions.isValidAmount(amount)) {
            player.sendMessage((String)SmartGambling.getInstance().configManager.messages.get("invalidMoneyAmount"));
            return false;
        }
        Economy economy;
        if (this.requireMoneyBefore.contains(player.getUniqueId())
                && (economy = SmartGambling.getEconomy()).getBalance((OfflinePlayer)player) < (double)amount) {
            DisplayUtils.displayActionBar(player, String.format(SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"), amount, economy.getBalance((OfflinePlayer)player)));
            player.playSound((Entity)player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0f, 1.0f);
            return false;
        }
        player.playSound((Entity)player, Sound.BLOCK_NOTE_BLOCK_BIT, 2.0f, 1.0f);
        return true;
    }

    public boolean hasActiveSession(Player player) {
        return this.oldInterfaces.containsKey(player);
    }
}
