package me.arthed.smartgambling.runtime;

import java.util.Map;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.configuration.file.YamlConfiguration;

/** A fully parsed runtime plus validated bindings for already-persisted machines. */
public record PreparedRuntime(
        RuntimeState state,
        YamlConfiguration mainConfiguration,
        Map<MachineData, Machine> machineBindings
) {
    public PreparedRuntime {
        machineBindings = Map.copyOf(machineBindings);
    }
}
