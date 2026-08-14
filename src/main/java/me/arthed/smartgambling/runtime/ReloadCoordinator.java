package me.arthed.smartgambling.runtime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.games.slots.SlotMachine;
import me.arthed.smartgambling.utils.MachineTypeIds;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Two-phase, main-thread configuration reload. Machine data is deliberately not re-read. */
public final class ReloadCoordinator {
    private final SmartGambling plugin;

    public ReloadCoordinator(SmartGambling plugin) {
        this.plugin = plugin;
    }

    public PreparedRuntime prepare() {
        RuntimeState current = RuntimeState.capture(plugin);
        YamlConfiguration candidateConfig = new YamlConfiguration();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            candidateConfig.load(configFile);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not parse " + configFile.getAbsolutePath(), exception);
        }

        RuntimeState candidate;
        try {
            plugin.configManager.load(candidateConfig);
            candidate = RuntimeState.capture(plugin);
        } finally {
            current.apply(plugin);
        }

        Map<MachineData, Machine> bindings = new IdentityHashMap<>();
        for (MachineData machineData : plugin.uuidMachines.values()) {
            Machine oldType = machineData.machineType;
            Machine replacement;
            if (oldType instanceof SlotMachine slot) {
                replacement = candidate.machineTypes().get(MachineTypeIds.normalize(slot.name));
                if (!(replacement instanceof SlotMachine)) {
                    throw new IllegalStateException("Reload removed configured slot type '" + slot.name + "'");
                }
            } else if (oldType instanceof BlackJack) {
                replacement = candidate.blackJack();
            } else if (oldType instanceof JackpotMachine) {
                replacement = candidate.jackpotMachine();
            } else if (oldType instanceof CrashMachine) {
                CrashMachine crash = candidate.crashMachine().clone();
                crash.bindMachineId(machineData.id);
                replacement = crash;
            } else {
                throw new IllegalStateException("Unsupported persisted machine type " + oldType.getClass().getName());
            }
            bindings.put(machineData, replacement);
        }
        return new PreparedRuntime(candidate, candidateConfig, bindings);
    }

    public void commit(PreparedRuntime prepared) {
        // Starting schedulers is the only fallible part of publishing a parsed
        // runtime. Do it while the old runtime is still completely untouched.
        // Candidate tasks are not reachable by commands/listeners until the
        // reference swap below, and are cancelled if any activation fails.
        List<CrashMachine> activatedCrashes = new ArrayList<>();
        JackpotMachine candidateJackpot = prepared.state().jackpotMachine();
        try {
            for (Machine replacement : prepared.machineBindings().values()) {
                if (replacement instanceof CrashMachine crash) {
                    activatedCrashes.add(crash);
                    crash.activate();
                }
            }
            candidateJackpot.activate();
        } catch (RuntimeException exception) {
            for (CrashMachine crash : activatedCrashes) {
                crash.shutdownAndRefund();
            }
            candidateJackpot.shutdownAndRefund();
            throw new IllegalStateException(
                    "Could not activate the prepared runtime; the old runtime remains active",
                    exception
            );
        }

        // From here on the operation consists only of main-thread reference
        // assignments. Closing no-funds GUIs and stopping the old idle tasks is
        // intentionally delayed until every candidate scheduler is proven live.
        plugin.shutdownGamesAndRefund();
        prepared.state().apply(plugin);
        if (plugin.selectBlocksRoutine != null) {
            plugin.selectBlocksRoutine.applySettings(prepared.state().creationGuideSettings());
        }
        for (Map.Entry<MachineData, Machine> entry : prepared.machineBindings().entrySet()) {
            entry.getKey().machineType = entry.getValue();
        }
        for (MachineData machineData : prepared.machineBindings().keySet()) {
            try {
                machineData.refreshEntityPresentation();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Reload committed, but machine " + machineData.id
                                + " could not refresh its entity presentation",
                        exception
                );
            }
        }
        plugin.advanceRuntimeGeneration();
        plugin.refreshForcedSlotTestMode();
    }
}
