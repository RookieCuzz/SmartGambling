package me.arthed.smartgambling.listeners;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.blackjack.OpenBlackjack;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.utils.DisplayUtils;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.entity.EntityDismountEvent;

public class EntityListener implements Listener {
    private final SmartGambling smartGambling = SmartGambling.getInstance();

    @EventHandler(
            priority = EventPriority.HIGH
    )
    public void onEntityInteract(PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getCustomName() != null && event.getRightClicked().getCustomName().equalsIgnoreCase("SMARTGAMBLING_MACHINE")) {
            event.setCancelled(true);
            if (!event.getHand().equals(EquipmentSlot.OFF_HAND)) {
                MachineData machineData = this.findMachine(event.getRightClicked());
                if (machineData == null) {
                    this.smartGambling.getLogger().warning(
                            "Ignoring an untracked SmartGambling entity at " + event.getRightClicked().getLocation()
                    );
                    return;
                }
                if (SmartGambling.getInstance().inputMoneyRoutine.playersInRoutine.contains(event.getPlayer())) {
                    return;
                }
                if (SmartGambling.getInstance().openMachines.containsKey(event.getPlayer())) {
                    return;
                }

                OpenMachine openMachine;
                if (machineData instanceof MachineDataBlackjack) {
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
            }
        }
    }

    @EventHandler
    public void onEntityDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getDismounted().getType().equals(EntityType.ARMOR_STAND)
                && event.getDismounted().getCustomName() != null
                && event.getDismounted().getCustomName().equals("SMARTGAMBLING_MACHINE")) {
            OpenInterface openInterface = SmartGambling.getInstance().openMachines.get(player);
            if (openInterface != null) {
                openInterface.machineType.forceClose(player);
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
            openInterface.machineType.forceClose(event.getPlayer());
        }
        this.smartGambling.clearForcedSlotResults(
                event.getPlayer().getUniqueId(),
                "target player disconnected"
        );

    }

    private MachineData findMachine(Entity clickedEntity) {
        for (MachineData machineData : this.smartGambling.uuidMachines.values()) {
            if (machineData.entities == null) {
                continue;
            }
            for (Entity entity : machineData.entities) {
                if (entity != null && entity.getUniqueId().equals(clickedEntity.getUniqueId())) {
                    return machineData;
                }
            }
        }
        return null;
    }
}
