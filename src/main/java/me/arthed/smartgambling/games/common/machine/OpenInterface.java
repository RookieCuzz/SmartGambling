package me.arthed.smartgambling.games.common.machine;

import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.inventory.Inventory;

public class OpenInterface {
    public Machine machineType;
    public Inventory inventory;
    public int betAmount;

    public OpenInterface(Machine machineType) {
        this.machineType = machineType;
    }
}
