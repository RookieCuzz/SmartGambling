package me.arthed.smartgambling.economy;

import java.util.Objects;
import java.util.UUID;

/** Stable gameplay identity used to make repeated callbacks idempotent. */
public record WagerKey(
        String game,
        String machineId,
        String roundId,
        UUID playerId,
        String nonce
) {
    public WagerKey {
        game = required(game, "game");
        machineId = required(machineId, "machineId");
        roundId = required(roundId, "roundId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        nonce = required(nonce, "nonce");
    }

    public String canonical() {
        return part(game) + part(machineId) + part(roundId) + part(playerId.toString()) + part(nonce);
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static String part(String value) {
        return value.length() + ":" + value;
    }
}
