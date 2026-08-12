package me.arthed.smartgambling.economy;

import java.util.UUID;

public record PlaceResult(Status status, WagerHandle wager, UUID transactionId, String detail) {
    public enum Status {
        ACCEPTED,
        ALREADY_ACCEPTED,
        REJECTED,
        UNKNOWN,
        PLAYER_FROZEN,
        STORAGE_FAILURE
    }

    public boolean accepted() {
        return status == Status.ACCEPTED || status == Status.ALREADY_ACCEPTED;
    }

    public boolean durable() {
        return status != Status.STORAGE_FAILURE;
    }
}
