package me.arthed.smartgambling.listeners;

import java.util.HashMap;
import java.util.List;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.blackjack.OpenBlackjack;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.utils.DisplayUtils;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

public class EntityListener implements Listener {
    private final SmartGambling smartGambling = SmartGambling.getInstance();

    @EventHandler(
            priority = EventPriority.HIGH
    )
    public void onEntityInteract(PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getCustomName() != null && event.getRightClicked().getCustomName().equalsIgnoreCase("SMARTGAMBLING_MACHINE")) {
            event.setCancelled(true);
            if (!event.getHand().equals(EquipmentSlot.OFF_HAND)) {
                HashMap<Chunk, List<MachineData>> worldData = (HashMap)this.smartGambling.machines.get(event.getRightClicked().getWorld());
                if (worldData != null) {
                    List<MachineData> machines = (List)worldData.get(event.getRightClicked().getLocation().getChunk());
                    if (machines != null) {
                        for(MachineData machineData : machines) {
                            for(Entity entity : machineData.entities) {
                                if (entity.equals(event.getRightClicked())) {
                                    if (SmartGambling.getInstance().inputMoneyRoutine.playersInRoutine.contains(event.getPlayer())) {
                                        return;
                                    }

                                    OpenMachine openMachine;
                                    if (machineData instanceof MachineDataBlackjack) {
                                        if (machineData.inUse && ((MachineDataBlackjack)machineData).player2 != null) {
                                            DisplayUtils.displayActionBar(event.getPlayer(), (String)this.smartGambling.configManager.messages.get("machineAlreadyInUse"));
                                            return;
                                        }

                                        openMachine = new OpenBlackjack(machineData.machineType, machineData);
                                    } else {
                                        if (machineData.inUse) {
                                            DisplayUtils.displayActionBar(event.getPlayer(), (String)this.smartGambling.configManager.messages.get("machineAlreadyInUse"));
                                            return;
                                        }

                                        openMachine = new OpenMachine(machineData.machineType, machineData);
                                    }

                                    machineData.machineType.open(event.getPlayer(), openMachine);
                                    event.getPlayer().swingMainHand();
                                    return;
                                }
                            }
                        }

                        event.getRightClicked().remove();
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityDismount(EntityDismountEvent event) {
        if (event.getDismounted().getType().equals(EntityType.ARMOR_STAND) && event.getDismounted().getCustomName() != null && event.getDismounted().getCustomName().equals("SMARTGAMBLING_MACHINE")) {
            OpenMachine openMachine = (OpenMachine)SmartGambling.getInstance().openMachines.get((Player)event.getEntity());
            if (openMachine instanceof OpenBlackjack) {
                openMachine.machineType.close((Player)event.getEntity(), (Inventory)null);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDamageByEntityEvent event) {
        if (event.getEntity().getCustomName() != null && event.getEntity().getCustomName().equalsIgnoreCase("SMARTGAMBLING_MACHINE")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerQuitEvent event) {
        OpenInterface openInterface = (OpenInterface)SmartGambling.getInstance().openMachines.get(event.getPlayer());
        if (openInterface != null) {
            openInterface.machineType.close(event.getPlayer(), (Inventory)null);
        }

    }
}