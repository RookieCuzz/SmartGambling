package me.arthed.smartgambling.games.blackjack;

import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenMachine;

public class OpenBlackjack
        extends OpenMachine {
    public OpenBlackjack(Machine machineType, MachineData machineData) {
        super(machineType, machineData);
    }
}
