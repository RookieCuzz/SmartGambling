package me.arthed.smartgambling.listeners;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.DataManager;
import me.arthed.smartgambling.data.MachineData;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldSaveEvent;

public class WorldSaveListener implements Listener {
    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        save();
    }

    public static void save() {
        boolean update = false;
        if (SmartGambling.getInstance().machinesToAdd.size() > 0) {
            Bukkit.getConsoleSender().sendMessage("[SmartGambling] Saving new machines...");

            for(MachineData machineData : SmartGambling.getInstance().machinesToAdd) {
                if (machineData != null && machineData.blocks.length > 0) {
                    Chunk chunk = machineData.blocks[0].getChunk();
                    // ...
                    DataManager.addMachine(chunk, machineData);
                }


            }

            SmartGambling.getInstance().machinesToAdd.clear();
            update = true;
        }

        if (SmartGambling.getInstance().machinesToRemove.size() > 0) {
            Bukkit.getConsoleSender().sendMessage("[SmartGambling] Removing machines...");

            for(MachineData machineData : SmartGambling.getInstance().machinesToRemove) {
                Chunk chunk = machineData.blocks[0].getChunk();
                DataManager.removeMachine(chunk, machineData);
            }

            SmartGambling.getInstance().machinesToRemove.clear();
            update = true;
        }

        if (update) {
            DataManager.save();
        }

    }
}