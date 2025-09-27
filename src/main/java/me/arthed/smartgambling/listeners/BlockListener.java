package me.arthed.smartgambling.listeners;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.blackjack.OpenBlackjack;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.utils.DisplayUtils;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class BlockListener implements Listener {
    private final SmartGambling smartGambling = SmartGambling.getInstance();

    @EventHandler(
            priority = EventPriority.HIGH
    )
    public void onBlockInteract(PlayerInteractEvent event) {
        if ((event.getAction().equals(Action.RIGHT_CLICK_BLOCK) || event.getAction().equals(Action.LEFT_CLICK_BLOCK)) && !event.getPlayer().isSneaking() && !event.getHand().equals(EquipmentSlot.OFF_HAND)) {
            Block clickedBlock = event.getClickedBlock();
            Bukkit.getScheduler().runTaskAsynchronously(this.smartGambling, () -> {
                HashMap<Chunk, List<MachineData>> worldData = (HashMap)this.smartGambling.machines.get(clickedBlock.getWorld());
                if (worldData != null) {
                    List<MachineData> machines = (List)worldData.get(clickedBlock.getChunk());
                    if (machines == null) {
                        machines = new ArrayList();
                    }

                    Chunk[] chunks = new Chunk[]{clickedBlock.getLocation().add(0.0D, 0.0D, 2.0D).getChunk(), clickedBlock.getLocation().add(0.0D, 0.0D, -2.0D).getChunk(), clickedBlock.getLocation().add(2.0D, 0.0D, 0.0D).getChunk(), clickedBlock.getLocation().add(-2.0D, 0.0D, 0.0D).getChunk()};

                    for(Chunk chunk : chunks) {
                        if (!chunk.equals(clickedBlock.getChunk())) {
                            List<MachineData> machines2 = (List)worldData.get(chunk);
                            if (machines2 != null) {
                                machines.addAll(machines2);
                            }
                        }
                    }

                    if (machines.size() != 0) {
                        for(MachineData machineData : machines) {
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
                                        if (machineData.inUse) {
                                            DisplayUtils.displayActionBar(event.getPlayer(), (String)this.smartGambling.configManager.messages.get("blackjackAlreadyInUse"));
                                            return;
                                        }

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

                                    Bukkit.getScheduler().runTask(this.smartGambling, () -> machineData.machineType.open(event.getPlayer(), openMachine));
                                    event.getPlayer().swingMainHand();
                                    return;
                                }
                            }
                        }

                    }
                }
            });
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (this.smartGambling.selectBlocksRoutine.playersInRoutine.containsKey(event.getPlayer())) {
            this.smartGambling.selectBlocksRoutine.blockBreak(event);
        }

    }
}