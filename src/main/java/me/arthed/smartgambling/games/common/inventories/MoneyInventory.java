package me.arthed.smartgambling.games.common.inventories;

import java.util.List;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.inventories.SubInventory;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.utils.DisplayUtils;
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
    private boolean requireMoneyBefore;

    public MoneyInventory(Inventory baseInventory, String inventoryTitle, InventoryAnimations animations, List<Integer> moneyButtonsValue, List<Button> moneyButtons, Button customAmountButton, Button backButton) {
        super(baseInventory, inventoryTitle, animations, backButton);
        this.moneyButtonsValue = moneyButtonsValue;
        this.moneyButtons = moneyButtons;
        this.customAmountButton = customAmountButton;
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        super.open(player, openInterface);
        if (openInterface.machineType.equals(SmartGambling.getInstance().jackpotMachine) || openInterface.machineType.equals(SmartGambling.getInstance().blackJack)) {
            this.requireMoneyBefore = true;
        }
    }

    @Override
    public void close(Player player, Inventory inventory) {
        if (!SmartGambling.getInstance().inputMoneyRoutine.playersInRoutine.contains(player)) {
            super.close(player, inventory);
        }
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        event.getWhoClicked().sendMessage(this.moneyButtonsValue.toString());
        if (this.customAmountButton.isClicked(event.getSlot())) {
            SmartGambling.getInstance().inputMoneyRoutine.startRoutine((Player)event.getWhoClicked());
        } else {
            for (int i = 0; i < this.moneyButtons.size(); ++i) {
                if (!this.moneyButtons.get(i).isClicked(event.getSlot())) continue;
                int amount = this.moneyButtonsValue.get(i);
                if (this.inputMoney((Player)event.getWhoClicked(), amount)) {
                    ((OpenInterface)this.oldInterfaces.get((Object)((Player)event.getWhoClicked()))).betAmount = amount;
                    System.out.println();
                    this.close((Player)event.getWhoClicked(), event.getInventory());
                }
                return;
            }
        }
        super.inventoryClick(event);
    }

    public void inputCustomAmount(Player player, int amount) {
        if (this.inputMoney(player, amount)) {
            ((OpenInterface)this.oldInterfaces.get((Object)player)).betAmount = amount;
        }
        this.close(player, null);
    }

    public boolean inputMoney(Player player, int amount) {
        if (this.requireMoneyBefore && SmartGambling.getBalance((OfflinePlayer)player) < (double)amount) {
            DisplayUtils.displayActionBar(player, String.format(SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"), amount, SmartGambling.getBalance((OfflinePlayer)player)));
            player.playSound((Entity)player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0f, 1.0f);
            return false;
        }
        player.playSound((Entity)player, Sound.BLOCK_NOTE_BLOCK_BIT, 2.0f, 1.0f);
        return true;
    }
}