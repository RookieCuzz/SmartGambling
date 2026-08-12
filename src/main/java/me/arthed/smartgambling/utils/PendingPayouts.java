package me.arthed.smartgambling.utils;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Durable fallback ledger for credits rejected temporarily by an economy provider.
 *
 * <p>A wager is only forgotten after Vault accepted its credit or the amount was
 * written to this ledger. Entries are retried on startup, once per minute, and
 * during a clean shutdown.</p>
 */
public final class PendingPayouts {
    private static final String ROOT = "credits";
    private static final Map<UUID, CreditAccount> CREDITS = new LinkedHashMap<>();
    private static JavaPlugin plugin;
    private static File file;
    private static BukkitTask retryTask;

    private PendingPayouts() {
    }

    public static synchronized void initialize(JavaPlugin owner) {
        plugin = owner;
        file = new File(owner.getDataFolder(), "pending-payouts.yml");
        CREDITS.clear();
        load();
        retryAll();
        retryTask = Bukkit.getScheduler().runTaskTimer(owner, PendingPayouts::retryAll, 1200L, 1200L);
    }

    public static synchronized boolean enqueue(OfflinePlayer player, double amount, String reason) {
        if (plugin == null || file == null || player == null || !EconomyTransactions.isValidAmount(amount)) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        CreditAccount account = CREDITS.computeIfAbsent(playerId, ignored -> new CreditAccount());
        double previous = account.ready;
        double updated = previous + amount;
        if (!EconomyTransactions.isValidAmount(updated)) {
            plugin.getLogger().severe("Could not queue an invalid pending payout total for " + playerId);
            return false;
        }
        account.ready = updated;
        if (!save()) {
            account.ready = previous;
            if (!account.hasAmounts()) {
                CREDITS.remove(playerId);
            }
            return false;
        }
        plugin.getLogger().warning(
                "Queued a pending payout of " + amount + " for " + playerId + " (" + reason + ")"
        );
        return true;
    }

    public static synchronized void retryAll() {
        if (plugin == null || CREDITS.isEmpty() || SmartGambling.getEconomy() == null) {
            return;
        }
        for (UUID playerId : new LinkedHashMap<>(CREDITS).keySet()) {
            CreditAccount account = CREDITS.get(playerId);
            if (account == null || !EconomyTransactions.isValidAmount(account.ready)) {
                continue;
            }
            double amount = account.ready;
            account.ready = 0.0D;
            account.uncertain += amount;
            if (!save()) {
                account.uncertain -= amount;
                account.ready = amount;
                continue;
            }

            // The durable PAYING marker is written before touching Vault. If the
            // process dies or Vault throws with an unknown outcome, startup will
            // require reconciliation instead of silently paying twice.
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            try {
                EconomyResponse response = SmartGambling.getEconomy().depositPlayer(player, amount);
                if (response == null) {
                    plugin.getLogger().severe(
                            "Vault returned no result for pending payout " + playerId
                                    + "; leaving it in uncertain state for manual reconciliation."
                    );
                } else if (response.transactionSuccess()) {
                    account.uncertain -= amount;
                    if (!account.hasAmounts()) {
                        CREDITS.remove(playerId);
                    }
                    plugin.getLogger().info("Paid pending SmartGambling credit of " + amount + " to " + playerId);
                    if (!save()) {
                        CreditAccount restored = CREDITS.computeIfAbsent(playerId, ignored -> new CreditAccount());
                        restored.uncertain += amount;
                        plugin.getLogger().severe(
                                "The payout succeeded but its ledger receipt could not be saved. "
                                        + "Do not retry it automatically; reconcile " + file.getAbsolutePath()
                        );
                    }
                } else {
                    account.uncertain -= amount;
                    account.ready += amount;
                    if (!save()) {
                        account.ready -= amount;
                        account.uncertain += amount;
                        plugin.getLogger().severe(
                                "Could not record a rejected pending payout; it is now marked uncertain for "
                                        + playerId
                        );
                    }
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Vault threw while paying " + playerId
                                + "; the amount remains uncertain and will not be retried automatically.",
                        exception
                );
            }
        }
    }

    public static synchronized void shutdown() {
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
        retryAll();
        save();
    }

    private static void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read pending payout ledger " + file.getAbsolutePath(), exception);
        }
        ConfigurationSection credits = configuration.getConfigurationSection(ROOT);
        if (credits == null) {
            return;
        }
        for (String key : credits.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid player UUID in pending payout ledger: " + key, exception);
            }
            CreditAccount account = new CreditAccount();
            if (credits.isConfigurationSection(key)) {
                ConfigurationSection accountSection = credits.getConfigurationSection(key);
                account.ready = accountSection == null ? 0.0D : accountSection.getDouble("ready");
                account.uncertain = accountSection == null ? 0.0D : accountSection.getDouble("uncertain");
            } else {
                // Backward compatibility with the first ledger format where the
                // UUID directly mapped to a ready amount.
                account.ready = credits.getDouble(key);
            }
            if ((!isZero(account.ready) && !EconomyTransactions.isValidAmount(account.ready))
                    || (!isZero(account.uncertain) && !EconomyTransactions.isValidAmount(account.uncertain))) {
                throw new IllegalStateException(
                        "Invalid pending payout account for " + playerId
                                + ": ready=" + account.ready + ", uncertain=" + account.uncertain
                );
            }
            if (account.hasAmounts()) {
                CREDITS.put(playerId, account);
            }
            if (EconomyTransactions.isValidAmount(account.uncertain)) {
                plugin.getLogger().severe(
                        "Pending payout for " + playerId + " has an uncertain Vault outcome ("
                                + account.uncertain + "). Check the economy ledger and resolve it manually; "
                                + "SmartGambling will not auto-pay this amount twice."
                );
            }
        }
    }

    private static boolean save() {
        if (file == null) {
            return false;
        }
        try {
            YamlConfiguration configuration = new YamlConfiguration();
            for (Map.Entry<UUID, CreditAccount> entry : CREDITS.entrySet()) {
                CreditAccount account = entry.getValue();
                if (EconomyTransactions.isValidAmount(account.ready)) {
                    configuration.set(ROOT + "." + entry.getKey() + ".ready", account.ready);
                }
                if (EconomyTransactions.isValidAmount(account.uncertain)) {
                    configuration.set(ROOT + "." + entry.getKey() + ".uncertain", account.uncertain);
                }
            }
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            configuration.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception exception) {
            if (plugin != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not save pending payout ledger", exception);
            }
            return false;
        }
    }

    private static boolean isZero(double amount) {
        return Math.abs(amount) < 0.0000001D;
    }

    private static final class CreditAccount {
        private double ready;
        private double uncertain;

        private boolean hasAmounts() {
            return EconomyTransactions.isValidAmount(this.ready)
                    || EconomyTransactions.isValidAmount(this.uncertain);
        }
    }
}
