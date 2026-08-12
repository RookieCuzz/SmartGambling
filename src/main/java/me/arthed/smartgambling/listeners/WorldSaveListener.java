package me.arthed.smartgambling.listeners;

import java.util.ArrayList;
import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.DataManager;
import me.arthed.smartgambling.data.DataStoreException;
import me.arthed.smartgambling.data.MachineData;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldSaveEvent;

/** Flushes queued machine mutations without dropping failed dirty entries. */
public class WorldSaveListener implements Listener {
    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        save();
    }

    /** @return true only when every queued operation is durable. */
    public static boolean save() {
        SmartGambling plugin = SmartGambling.getInstance();

        if (!plugin.machinesToAdd.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage("[SmartGambling] Saving new machines...");
        }
        for (MachineData machineData : new ArrayList<>(plugin.machinesToAdd)) {
            if (machineData == null || machineData.blocks.length == 0) {
                plugin.getLogger().severe("A dirty machine add has no origin block; retaining it for inspection.");
                return false;
            }
            try {
                DataManager.addMachine(machineData.blocks[0].getChunk(), machineData);
                plugin.machinesToAdd.remove(machineData);
            } catch (RuntimeException exception) {
                DataManager.rollbackFailedAddition(machineData);
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not persist machine add " + machineData.id
                                + "; the dirty entry was retained for retry.",
                        exception
                );
                return false;
            }
        }

        if (!plugin.machinesToRemove.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage("[SmartGambling] Removing machines...");
        }
        for (MachineData machineData : new ArrayList<>(plugin.machinesToRemove)) {
            if (machineData == null || machineData.blocks.length == 0) {
                plugin.getLogger().severe("A dirty machine removal has no origin block; retaining it for inspection.");
                return false;
            }
            try {
                DataManager.removeMachine(machineData.blocks[0].getChunk(), machineData);
                plugin.machinesToRemove.remove(machineData);
            } catch (RuntimeException exception) {
                DataManager.stageFailedRemovalRollback(machineData);
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not persist machine removal " + machineData.id
                                + "; the dirty entry was retained for retry.",
                        exception
                );
                return false;
            }
        }

        if (DataManager.isDirty()) {
            try {
                DataManager.save();
            } catch (DataStoreException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not retry dirty SmartGambling data; it remains queued.",
                        exception
                );
                return false;
            }
        }
        return true;
    }
}
