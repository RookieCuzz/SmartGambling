package me.arthed.smartgambling.listeners;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

public class InventoryListener implements Listener {
    private final SmartGambling smartGambling = SmartGambling.getInstance();

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (this.smartGambling.openMachines.containsKey((Player)event.getWhoClicked())) {
            ((OpenInterface)this.smartGambling.openMachines.get((Player)event.getWhoClicked())).machineType.inventoryClick(event);
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
                if (event.getInventory().equals(openSlot2.inventory)) {
                    openSlot2.machineType.close(player, event.getInventory());
                }

                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    if (player.getOpenInventory() == null && this.smartGambling.openMachines.containsKey(player)) {
                        ((OpenInterface)this.smartGambling.openMachines.get(player)).machineType.close(player, (Inventory)null);
                        this.smartGambling.openMachines.remove(player);
                    }

                }, 200L);
            }
        }

    }
}