package me.arthed.smartgambling.games.common.machine;

import me.arthed.smartgambling.data.MachineData;
import org.bukkit.entity.Player;

/** A two-seat machine whose challenger confirms the host's selected escrow amount. */
public interface ConfirmableWagerMachine extends Machine {
    boolean canConfirmChallenger(Player player, MachineData machineData);

    int requiredStake(MachineData machineData);
}
