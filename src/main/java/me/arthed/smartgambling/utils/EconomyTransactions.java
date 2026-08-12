package me.arthed.smartgambling.utils;

import me.arthed.smartgambling.SmartGambling;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Centralizes validation and failure handling for Vault transactions. */
public final class EconomyTransactions {
    private EconomyTransactions() {
    }

    public static boolean isValidAmount(double amount) {
        return Double.isFinite(amount) && amount > 0.0D;
    }

    public static boolean withdraw(Player player, double amount, String reason) {
        if (!isValidAmount(amount)) {
            notifyFailure(player, reason, "amount must be greater than zero");
            return false;
        }
        EconomyResponse response;
        try {
            response = SmartGambling.getEconomy().withdrawPlayer(player, amount);
        } catch (RuntimeException exception) {
            notifyFailure(player, reason, exception.getMessage());
            return false;
        }
        if (response == null || !response.transactionSuccess()) {
            notifyFailure(player, reason, response == null ? "null Vault response" : response.errorMessage);
            return false;
        }
        return true;
    }

    /**
     * Credits immediately, or durably queues the credit when Vault rejects it.
     * A true result means the obligation is safe to remove from the game state.
     */
    public static boolean deposit(OfflinePlayer player, double amount, String reason) {
        if (!isValidAmount(amount)) {
            notifyFailure(player, reason, "amount must be greater than zero");
            return false;
        }
        EconomyResponse response;
        try {
            response = SmartGambling.getEconomy().depositPlayer(player, amount);
        } catch (RuntimeException exception) {
            response = null;
            logFailure(player, reason, exception.getMessage());
        }
        if (response != null && response.transactionSuccess()) {
            return true;
        }
        if (response != null) {
            logFailure(player, reason, response.errorMessage);
        }
        if (PendingPayouts.enqueue(player, amount, reason)) {
            if (player instanceof Player onlinePlayer && onlinePlayer.isOnline()) {
                onlinePlayer.sendMessage(ChatColor.YELLOW
                        + "Your payment was queued and will be retried automatically.");
            }
            return true;
        }
        notifyFailure(player, reason, "Vault rejected the credit and the pending ledger could not be saved");
        return false;
    }

    private static void notifyFailure(OfflinePlayer player, String reason, String providerError) {
        logFailure(player, reason, providerError);
        if (player instanceof Player onlinePlayer && onlinePlayer.isOnline()) {
            onlinePlayer.sendMessage(ChatColor.RED + "The economy transaction failed. Staff have been notified.");
        }
    }

    private static void logFailure(OfflinePlayer player, String reason, String providerError) {
        String details = providerError == null || providerError.isBlank() ? "unknown Vault error" : providerError;
        String playerName = player == null || player.getName() == null
                ? String.valueOf(player == null ? null : player.getUniqueId())
                : player.getName();
        SmartGambling.getInstance().getLogger().warning(
                "Economy transaction failed for " + playerName + " (" + reason + "): " + details
        );
    }
}
