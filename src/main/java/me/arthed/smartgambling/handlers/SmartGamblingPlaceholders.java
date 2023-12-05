package me.arthed.smartgambling.handlers;

import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.utils.MathUtils;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public class SmartGamblingPlaceholders extends PlaceholderExpansion {
    private final SmartGambling plugin;
    public static String blackjackStatusNoPlayers;
    public static String blackjackStatusChoosingBet;
    public static String blackjackStatusWaitingOpponent;
    public static String blackjackStatusPlaying;
    public static String jackpotCooldown;
    public static String jackpotActive;
    public static String jackpotFinish;
    public static String crashCooldown;
    public static String crashStarting;
    public static String crashCrashing;

    public SmartGamblingPlaceholders(SmartGambling plugin) {
        this.plugin = plugin;
    }

    public String getAuthor() {
        return "Styro";
    }

    public String getIdentifier() {
        return "sg";
    }

    public String getVersion() {
        return "1.0.0";
    }

    public boolean persist() {
        return true;
    }

    public String onRequest(OfflinePlayer player, String params) {
        if (params.equalsIgnoreCase("jackpot_status")) {
            if (this.plugin.jackpotMachine.spinning) {
                return this.plugin.jackpotMachine.timerTask.isCancelled() ? jackpotFinish.replace("%amount%", "" + this.plugin.jackpotMachine.totalBets).replace("%player_count%", "" + this.plugin.jackpotMachine.bets.size()).replace("%time%", "" + this.plugin.jackpotMachine.timeLeft) : jackpotCooldown.replace("%time%", "" + this.plugin.jackpotMachine.timeLeft);
            } else {
                return jackpotActive.replace("%amount%", "" + this.plugin.jackpotMachine.totalBets).replace("%player_count%", "" + this.plugin.jackpotMachine.bets.size()).replace("%time%", "" + this.plugin.jackpotMachine.timeLeft);
            }
        } else {
            if (params.startsWith("blackjack_status_")) {
                UUID tableUUID = UUID.fromString(params.substring(17));
                MachineData machineData = (MachineData)SmartGambling.getInstance().uuidMachines.get(tableUUID);
                if (machineData instanceof MachineDataBlackjack) {
                    MachineDataBlackjack blackjackMachine = (MachineDataBlackjack)machineData;
                    if (blackjackMachine.player1 == null) {
                        if (blackjackMachine.inUse) {
                            return blackjackStatusChoosingBet;
                        }

                        return blackjackStatusNoPlayers;
                    }

                    if (blackjackMachine.player2 == null) {
                        return blackjackStatusWaitingOpponent.replace("%bet%", "" + blackjackMachine.bet);
                    }

                    return blackjackStatusPlaying.replace("%bet%", "" + blackjackMachine.bet);
                }
            } else if (params.startsWith("crash_status_")) {
                UUID tableUUID = UUID.fromString(params.substring(13));
                MachineData machineData = (MachineData)SmartGambling.getInstance().uuidMachines.get(tableUUID);
                Machine var6 = machineData.machineType;
                if (var6 instanceof CrashMachine) {
                    CrashMachine crashMachine = (CrashMachine)var6;
                    if (crashMachine.crashing) {
                        if (crashMachine.timeLeft == 0) {
                            return crashCrashing.replace("%value%", "" + MathUtils.roundDecimals(crashMachine.value)).replace("%player_count%", "" + crashMachine.bets.size()).replace("%player_crashed_count%", "" + crashMachine.crashedAt.size());
                        }

                        return crashCooldown.replace("%time%", "" + crashMachine.timeLeft).replace("%player_count%", "" + crashMachine.bets.size());
                    }

                    return crashStarting.replace("%time%", "" + crashMachine.timeLeft).replace("%player_count%", "" + crashMachine.bets.size());
                }
            }

            return null;
        }
    }
}