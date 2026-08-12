package me.arthed.smartgambling.economy;

import java.util.Objects;
import java.util.UUID;

public record WagerHandle(UUID id, WagerKey key, UUID playerId, Money stake) {
    public WagerHandle {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(stake, "stake");
        if (!key.playerId().equals(playerId)) {
            throw new IllegalArgumentException("Wager key belongs to a different player");
        }
    }
}
