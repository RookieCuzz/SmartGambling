package me.arthed.smartgambling.data;

import java.util.List;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;

/** Stable, persisted identities for the ArmorStands owned by one machine. */
public enum EntityRole {
    MODEL,
    PRIMARY_SEAT,
    BLACKJACK_HOST_SEAT,
    BLACKJACK_CHALLENGER_SEAT;

    public static List<EntityRole> forMachine(Machine machine) {
        if (machine instanceof BlackJack) {
            // Keep this order compatible with the v2 entity array.
            return List.of(BLACKJACK_HOST_SEAT, BLACKJACK_CHALLENGER_SEAT, MODEL);
        }
        if (machine instanceof JackpotMachine || machine instanceof CrashMachine) {
            return List.of(MODEL);
        }
        return List.of(PRIMARY_SEAT, MODEL);
    }

    public static EntityRole parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
