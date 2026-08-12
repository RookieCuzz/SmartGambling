package me.arthed.smartgambling.handlers;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.utils.MathUtils;

/**
 * Immutable view consumed by placeholder request threads.
 *
 * <p>{@link #capture(SmartGambling, PlaceholderMessages)} must be called from
 * the Bukkit primary thread. Optional integrations then only read the copied
 * values and never traverse the plugin's mutable gameplay maps.</p>
 */
public record PlaceholderSnapshot(
        PlaceholderMessages messages,
        JackpotState jackpot,
        Map<UUID, BlackjackState> blackjackTables,
        Map<UUID, CrashState> crashMachines
) {
    private static final String BLACKJACK_PREFIX = "blackjack_status_";
    private static final String CRASH_PREFIX = "crash_status_";

    public PlaceholderSnapshot {
        messages = Objects.requireNonNull(messages, "messages");
        blackjackTables = Map.copyOf(Objects.requireNonNull(blackjackTables, "blackjackTables"));
        crashMachines = Map.copyOf(Objects.requireNonNull(crashMachines, "crashMachines"));
    }

    public static PlaceholderSnapshot empty() {
        return new PlaceholderSnapshot(PlaceholderMessages.empty(), null, Map.of(), Map.of());
    }

    public static PlaceholderSnapshot capture(
            SmartGambling plugin,
            PlaceholderMessages messages
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(messages, "messages");

        JackpotState jackpotState = captureJackpot(plugin.jackpotMachine);
        Map<UUID, BlackjackState> blackjack = new HashMap<>();
        Map<UUID, CrashState> crash = new HashMap<>();

        for (Map.Entry<UUID, MachineData> entry : plugin.uuidMachines.entrySet()) {
            UUID machineId = entry.getKey();
            MachineData machineData = entry.getValue();
            if (machineId == null || machineData == null) {
                continue;
            }
            if (machineData instanceof MachineDataBlackjack table) {
                synchronized (table) {
                    blackjack.put(
                            machineId,
                            new BlackjackState(
                                    table.player1 != null,
                                    table.player2 != null,
                                    table.inUse,
                                    table.bet
                            )
                    );
                }
            } else if (machineData.machineType instanceof CrashMachine crashMachine) {
                crash.put(
                        machineId,
                        new CrashState(
                                crashMachine.crashing,
                                crashMachine.timeLeft,
                                MathUtils.roundDecimals(crashMachine.value),
                                crashMachine.bets.size(),
                                crashMachine.crashedAt.size()
                        )
                );
            }
        }
        return new PlaceholderSnapshot(messages, jackpotState, blackjack, crash);
    }

    /** Resolves one PlaceholderAPI parameter without touching live game state. */
    public String resolve(String parameters) {
        if (parameters == null) {
            return null;
        }
        String parameter = parameters.trim();
        if (parameter.equalsIgnoreCase("jackpot_status")) {
            return this.resolveJackpot();
        }

        String normalized = parameter.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(BLACKJACK_PREFIX)) {
            UUID machineId = parseUuid(parameter.substring(BLACKJACK_PREFIX.length()));
            return machineId == null ? null : this.resolveBlackjack(this.blackjackTables.get(machineId));
        }
        if (normalized.startsWith(CRASH_PREFIX)) {
            UUID machineId = parseUuid(parameter.substring(CRASH_PREFIX.length()));
            return machineId == null ? null : this.resolveCrash(this.crashMachines.get(machineId));
        }
        return null;
    }

    private String resolveJackpot() {
        if (this.jackpot == null) {
            return null;
        }
        String template;
        if (!this.jackpot.spinning()) {
            template = this.messages.jackpotActive();
        } else if (this.jackpot.choosingWinner()) {
            template = this.messages.jackpotFinish();
        } else {
            template = this.messages.jackpotCooldown();
        }
        return template
                .replace("%amount%", Integer.toString(this.jackpot.totalBets()))
                .replace("%player_count%", Integer.toString(this.jackpot.playerCount()))
                .replace("%time%", Integer.toString(this.jackpot.timeLeft()));
    }

    private String resolveBlackjack(BlackjackState blackjack) {
        if (blackjack == null) {
            return null;
        }
        if (!blackjack.player1Present()) {
            return blackjack.inUse()
                    ? this.messages.blackjackChoosingBet()
                    : this.messages.blackjackNoPlayers();
        }
        String bet = Integer.toString(blackjack.bet());
        return (blackjack.player2Present()
                ? this.messages.blackjackPlaying()
                : this.messages.blackjackWaitingForOpponent())
                .replace("%bet%", bet);
    }

    private String resolveCrash(CrashState crash) {
        if (crash == null) {
            return null;
        }
        if (!crash.crashing()) {
            return this.messages.crashBetting()
                    .replace("%time%", Integer.toString(crash.timeLeft()))
                    .replace("%player_count%", Integer.toString(crash.playerCount()));
        }
        if (crash.timeLeft() != 0) {
            return this.messages.crashCooldown()
                    .replace("%time%", Integer.toString(crash.timeLeft()))
                    .replace("%player_count%", Integer.toString(crash.playerCount()));
        }
        return this.messages.crashCrashing()
                .replace("%value%", Double.toString(crash.value()))
                .replace("%player_count%", Integer.toString(crash.playerCount()))
                .replace("%player_crashed_count%", Integer.toString(crash.cashedOutCount()));
    }

    private static JackpotState captureJackpot(JackpotMachine jackpotMachine) {
        if (jackpotMachine == null) {
            return null;
        }
        boolean choosingWinner = jackpotMachine.spinning
                && (jackpotMachine.timerTask == null || jackpotMachine.timerTask.isCancelled());
        return new JackpotState(
                jackpotMachine.spinning,
                choosingWinner,
                jackpotMachine.totalBets,
                jackpotMachine.bets.size(),
                jackpotMachine.timeLeft
        );
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record JackpotState(
            boolean spinning,
            boolean choosingWinner,
            int totalBets,
            int playerCount,
            int timeLeft
    ) {
    }

    public record BlackjackState(
            boolean player1Present,
            boolean player2Present,
            boolean inUse,
            int bet
    ) {
    }

    public record CrashState(
            boolean crashing,
            int timeLeft,
            double value,
            int playerCount,
            int cashedOutCount
    ) {
    }
}
