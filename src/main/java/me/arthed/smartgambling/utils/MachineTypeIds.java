package me.arthed.smartgambling.utils;

import java.util.Locale;
import java.util.Objects;

/** Canonical identifiers used by the configured machine-type registry. */
public final class MachineTypeIds {
    public static final String BLACKJACK = "blackjack";
    public static final String CRASH = "crash";
    public static final String LOTTERY = "lottery";

    private MachineTypeIds() {
    }

    /**
     * Normalizes command, configuration and persisted identifiers identically.
     * Locale.ROOT is intentional: server locale must never affect registry keys.
     */
    public static String normalize(String rawId) {
        Objects.requireNonNull(rawId, "rawId");
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Machine type ID cannot be blank");
        }
        return normalized;
    }

    /** Returns the canonical slot ID derived from a .yml file name. */
    public static String fromSlotFileName(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.length() <= 4
                || !fileName.regionMatches(true, fileName.length() - 4, ".yml", 0, 4)) {
            throw new IllegalArgumentException("Slot machine file must end in .yml: " + fileName);
        }
        return normalize(fileName.substring(0, fileName.length() - 4));
    }

    /** Chinese label for player-facing guides; canonical IDs remain unchanged. */
    public static String displayName(String rawId) {
        String id = normalize(rawId);
        return switch (id) {
            case BLACKJACK -> "二十一点";
            case CRASH -> "Crash 爆点";
            case LOTTERY -> "累积奖池";
            case "slotmachine", "slotexample" -> "老虎机";
            default -> id;
        };
    }
}
