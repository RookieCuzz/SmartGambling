package me.arthed.smartgambling.games.common.machine;

import me.arthed.smartgambling.games.common.machine.OpenInterface;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public interface Machine {
  public void open(Player var1, OpenInterface var2);

  public void close(Player var1, Inventory var2);

  public void inventoryClick(InventoryClickEvent var1);

  public ItemStack getMachineItem();

  public double[] getMachineEntityOffset();
}
