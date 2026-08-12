package me.arthed.smartgambling.listeners;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.blackjack.OpenBlackjack;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.utils.DisplayUtils;
import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class BlockListener implements Listener {
    private final SmartGambling smartGambling = SmartGambling.getInstance();

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onBlockInteract(PlayerInteractEvent event) {
        if ((event.getAction().equals(Action.RIGHT_CLICK_BLOCK) || event.getAction().equals(Action.LEFT_CLICK_BLOCK)) && !event.getPlayer().isSneaking() && event.getHand() != EquipmentSlot.OFF_HAND) {
            Block clickedBlock = event.getClickedBlock();
                for(MachineData machineData : this.smartGambling.uuidMachines.values()) {
                    if (machineData.blocks.length == 0 || !machineData.blocks[0].getWorld().equals(clickedBlock.getWorld())) {
                        continue;
                    }
                            for(Block block : machineData.blocks) {
                                if (clickedBlock.equals(block)) {
                                    event.setCancelled(true);
                                    if (SmartGambling.getInstance().inputMoneyRoutine.playersInRoutine.contains(event.getPlayer())) {
                                        return;
                                    }

                                    if (SmartGambling.getInstance().openMachines.containsKey(event.getPlayer())) {
                                        return;
                                    }

                                    OpenInterface openMachine;
                                    if (machineData instanceof MachineDataBlackjack) {
                                        openMachine = new OpenBlackjack(machineData.machineType, machineData);
                                    } else if (machineData.machineType instanceof JackpotMachine) {
                                        openMachine = new OpenInterface(SmartGambling.getInstance().jackpotMachine);
                                    } else {
                                        if (machineData.inUse) {
                                            DisplayUtils.displayActionBar(event.getPlayer(), (String)this.smartGambling.configManager.messages.get("machineAlreadyInUse"));
                                            return;
                                        }

                                        openMachine = new OpenMachine(machineData.machineType, machineData);
                                    }

                                    long generation = this.smartGambling.getRuntimeGeneration();
                                    Bukkit.getScheduler().runTask(this.smartGambling, () -> {
                                        if (!event.getPlayer().isOnline()
                                                || generation != this.smartGambling.getRuntimeGeneration()
                                                || this.smartGambling.uuidMachines.get(machineData.id) != machineData
                                                || machineData.entities == null
                                                || Arrays.stream(machineData.entities)
                                                .anyMatch(entity -> entity == null || !entity.isValid())) {
                                            return;
                                        }
                                        if (this.smartGambling.openMachines.containsKey(event.getPlayer())
                                                || this.smartGambling.inputMoneyRoutine.playersInRoutine.contains(event.getPlayer())) {
                                            return;
                                        }
                                        if (!(machineData instanceof MachineDataBlackjack) && machineData.inUse) {
                                            DisplayUtils.displayActionBar(
                                                    event.getPlayer(),
                                                    this.smartGambling.configManager.messages.get("machineAlreadyInUse")
                                            );
                                            return;
                                        }
                                        machineData.machineType.open(event.getPlayer(), openMachine);
                                    });
                                    event.getPlayer().swingMainHand();
                                    return;
                                }
                            }
                }
        }
    }
}
