package me.arthed.smartgambling.listeners;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class InventoryListener implements Listener {
    private final SmartGambling smartGambling = SmartGambling.getInstance();

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OpenInterface openInterface = this.smartGambling.openMachines.get(player);
        if (openInterface == null
                || openInterface.inventory == null
                || event.getView().getTopInventory() != openInterface.inventory) {
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        openInterface.machineType.inventoryClick(event);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OpenInterface openInterface = this.smartGambling.openMachines.get(player);
        if (openInterface == null
                || openInterface.inventory == null
                || event.getView().getTopInventory() != openInterface.inventory) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        HumanEntity openSlot = event.getPlayer();
        if (openSlot instanceof Player) {
            Player player = (Player)openSlot;
            if (this.smartGambling.openMachines.containsKey(player)) {
                OpenInterface openSlot2;
                openSlot2 = (OpenInterface)this.smartGambling.openMachines.get(player);
                if (event.getInventory() == openSlot2.inventory) {
                    openSlot2.machineType.close(player, event.getInventory());
                }

            }
        }

    }
}
