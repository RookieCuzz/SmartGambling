package me.arthed.smartgambling.games.common.machine;

import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;

public class OpenMachine
        extends OpenInterface {
    public MachineData machineData;

    public OpenMachine(Machine machineType, MachineData machineData) {
        super(machineType);
        this.machineData = machineData;
    }
}
