package me.arthed.smartgambling.games.common.inventories;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.ConfirmableWagerMachine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.utils.DisplayUtils;
import me.arthed.smartgambling.utils.EconomyTransactions;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ConfirmGameInventory extends SubInventory {
    private final int confirmButton;
    private final int declineButton;
    private final Set<UUID> closingPlayers = new HashSet<>();

    public ConfirmGameInventory(
            Inventory baseInventory,
            String inventoryTitle,
            InventoryAnimations animations,
            int confirmButton,
            int declineButton
    ) {
        super(baseInventory, inventoryTitle, animations, new Button(new HashSet<>()));
        this.confirmButton = confirmButton;
        this.declineButton = declineButton;
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        if (!(openInterface instanceof OpenMachine openMachine)
                || !(openMachine.machineType instanceof ConfirmableWagerMachine table)
                || !table.canConfirmChallenger(player, openMachine.machineData)) {
            return;
        }
        int requiredStake = table.requiredStake(openMachine.machineData);

        Inventory playerInventory = Bukkit.createInventory(
                (InventoryHolder) player,
                this.baseInventory.getSize(),
                this.inventoryTitle
        );
        playerInventory.setContents(this.baseInventory.getContents());

        for (int slot : new int[]{this.confirmButton, this.declineButton}) {
            ItemStack button = playerInventory.getItem(slot);
            if (button == null || !button.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = button.getItemMeta();
            if (meta.hasDisplayName()) {
                meta.setDisplayName(meta.getDisplayName().replace("%bet%", Integer.toString(requiredStake)));
                button.setItemMeta(meta);
            }
        }

        this.closingPlayers.remove(player.getUniqueId());
        this.oldInterfaces.put(player, openInterface);
        OpenInterface newInterface = new OpenInterface(this);
        newInterface.inventory = playerInventory;
        SmartGambling.getInstance().openMachines.put(player, newInterface);
        this.animations.startAnimations(playerInventory);
        player.openInventory(playerInventory);
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
        if (current == null || current.machineType != this || current.inventory != event.getInventory()) {
            return;
        }
        OpenInterface old = this.oldInterfaces.get(player);
        if (!(old instanceof OpenMachine openMachine)
                || !(openMachine.machineType instanceof ConfirmableWagerMachine table)) {
            return;
        }

        if (event.getSlot() == this.confirmButton) {
            int tableBet;
            synchronized (openMachine.machineData) {
                if (!table.canConfirmChallenger(player, openMachine.machineData)) {
                    openMachine.betAmount = -1;
                    this.close(player, event.getInventory());
                    return;
                }
                tableBet = table.requiredStake(openMachine.machineData);
                if (!EconomyTransactions.isValidAmount(tableBet)) {
                    openMachine.betAmount = -1;
                    this.close(player, event.getInventory());
                    return;
                }
            }

            double balance = SmartGambling.getEconomy().getBalance(player);
            if (balance < tableBet) {
                DisplayUtils.displayActionBar(
                        player,
                        String.format(
                                SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"),
                                tableBet,
                                balance
                        )
                );
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
                return;
            }

            // The table performs the actual checked withdrawal. Keeping
            // the transaction there makes reservation -> withdrawal -> commit
            // one atomic state transition.
            openMachine.betAmount = tableBet;
            this.close(player, event.getInventory());
        } else if (event.getSlot() == this.declineButton) {
            openMachine.betAmount = -1;
            this.close(player, event.getInventory());
        }
    }

    @Override
    public void close(Player player, Inventory inventory) {
        if (!this.closingPlayers.add(player.getUniqueId())) {
            return;
        }
        super.close(player, inventory);
        Bukkit.getScheduler().runTaskLater(
                SmartGambling.getInstance(),
                () -> this.closingPlayers.remove(player.getUniqueId()),
                2L
        );
    }

    @Override
    public void forceClose(Player player) {
        this.closingPlayers.remove(player.getUniqueId());
        super.forceClose(player);
    }

    /** Removes a pending confirmation without returning to the table. */
    public void discard(Player player) {
        this.closingPlayers.remove(player.getUniqueId());
        this.oldInterfaces.remove(player);
        OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
        if (current != null && current.machineType == this) {
            if (current.inventory != null) {
                this.animations.stopAnimations(current.inventory);
            }
            SmartGambling.getInstance().openMachines.remove(player);
        }
    }
}
