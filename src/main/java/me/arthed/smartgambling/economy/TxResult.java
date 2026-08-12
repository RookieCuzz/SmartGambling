package me.arthed.smartgambling.economy;

import java.util.UUID;

public record TxResult(Status status, UUID transactionId, String detail) {
    public enum Status {
        DURABLE,
        ALREADY_APPLIED,
        READY,
        UNKNOWN,
        CONFLICT,
        NOT_FOUND,
        PLAYER_FROZEN,
        STORAGE_FAILURE
    }

    /** True means gameplay may discard its duplicate in-memory obligation. */
    public boolean durable() {
        return status == Status.DURABLE
                || status == Status.ALREADY_APPLIED
                || status == Status.READY
                || status == Status.UNKNOWN;
    }
}
