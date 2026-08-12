package me.arthed.smartgambling.handlers;

import java.util.Objects;

/** Immutable, reloadable text used by the optional placeholder integration. */
public record PlaceholderMessages(
        String blackjackNoPlayers,
        String blackjackChoosingBet,
        String blackjackWaitingForOpponent,
        String blackjackPlaying,
        String jackpotCooldown,
        String jackpotActive,
        String jackpotFinish,
        String crashCooldown,
        String crashBetting,
        String crashCrashing
) {
    public PlaceholderMessages {
        blackjackNoPlayers = Objects.requireNonNull(blackjackNoPlayers, "blackjackNoPlayers");
        blackjackChoosingBet = Objects.requireNonNull(blackjackChoosingBet, "blackjackChoosingBet");
        blackjackWaitingForOpponent = Objects.requireNonNull(
                blackjackWaitingForOpponent,
                "blackjackWaitingForOpponent"
        );
        blackjackPlaying = Objects.requireNonNull(blackjackPlaying, "blackjackPlaying");
        jackpotCooldown = Objects.requireNonNull(jackpotCooldown, "jackpotCooldown");
        jackpotActive = Objects.requireNonNull(jackpotActive, "jackpotActive");
        jackpotFinish = Objects.requireNonNull(jackpotFinish, "jackpotFinish");
        crashCooldown = Objects.requireNonNull(crashCooldown, "crashCooldown");
        crashBetting = Objects.requireNonNull(crashBetting, "crashBetting");
        crashCrashing = Objects.requireNonNull(crashCrashing, "crashCrashing");
    }

    public static PlaceholderMessages empty() {
        return new PlaceholderMessages("", "", "", "", "", "", "", "", "", "");
    }
}
