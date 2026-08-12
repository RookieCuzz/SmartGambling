package me.arthed.smartgambling.games.common.inventories;

import java.util.HashMap;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class SubInventory
        implements Machine {
    protected final Inventory baseInventory;
    protected final String inventoryTitle;
    protected final InventoryAnimations animations;
    private final Button backButton;
    protected final HashMap<Player, OpenInterface> oldInterfaces;

    public SubInventory(Inventory baseInventory, String inventoryTitle, InventoryAnimations animations, Button backButton) {
        this.baseInventory = baseInventory;
        this.inventoryTitle = inventoryTitle;
        this.animations = animations;
        this.backButton = backButton;
        this.oldInterfaces = new HashMap();
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        Inventory playerInventory = Bukkit.createInventory((InventoryHolder)player, (int)this.baseInventory.getSize(), (String)this.inventoryTitle);
        playerInventory.setContents(this.baseInventory.getContents());
        this.oldInterfaces.put(player, openInterface);
        OpenInterface newInterface = new OpenInterface(this);
        newInterface.inventory = playerInventory;
        SmartGambling.getInstance().openMachines.put(player, newInterface);
        this.animations.startAnimations(playerInventory);
        player.openInventory(playerInventory);
    }

    @Override
    public void close(Player player, Inventory inventory) {
        Bukkit.getScheduler().runTask((Plugin)SmartGambling.getInstance(), () -> {
            OpenInterface oldInterface = this.oldInterfaces.remove(player);
            OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
            if (current != null && current.machineType == this) {
                if (current.inventory != null) {
                    this.animations.stopAnimations(current.inventory);
                }
                SmartGambling.getInstance().openMachines.remove(player);
            }
            if (oldInterface != null) {
                if (oldInterface.betAmount == 0) {
                    oldInterface.betAmount = -1;
                }
                oldInterface.machineType.open(player, oldInterface);
            }
        });
    }

    @Override
    public void forceClose(Player player) {
        OpenInterface current = SmartGambling.getInstance().openMachines.remove(player);
        if (current != null && current.machineType == this && current.inventory != null) {
            this.animations.stopAnimations(current.inventory);
        }

        OpenInterface oldInterface = this.oldInterfaces.remove(player);
        if (oldInterface != null) {
            SmartGambling.getInstance().openMachines.put(player, oldInterface);
            oldInterface.machineType.forceClose(player);
        }
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (this.backButton.isClicked(event.getSlot())) {
            this.close((Player)event.getWhoClicked(), event.getInventory());
        }
    }

    @Override
    public ItemStack getMachineItem() {
        return new ItemStack(Material.STONE);
    }

    @Override
    public double[] getMachineEntityOffset() {
        return new double[0];
    }
}
