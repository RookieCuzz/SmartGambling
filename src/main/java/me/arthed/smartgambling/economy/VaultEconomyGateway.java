package me.arthed.smartgambling.economy;

import java.util.Objects;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/** Main-thread Vault adapter. SQLiteEconomyService supplies the ambiguity protocol around it. */
public final class VaultEconomyGateway implements EconomyGateway {
    private final ServicesManager services;

    public VaultEconomyGateway(ServicesManager services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override
    public boolean isAvailable() {
        return currentProvider() != null;
    }

    /** Returns the live enabled Vault provider, or {@code null} while it is not ready. */
    public Economy currentProvider() {
        RegisteredServiceProvider<Economy> registration = services.getRegistration(Economy.class);
        if (registration == null || registration.getPlugin() == null
                || !registration.getPlugin().isEnabled()) {
            return null;
        }
        return registration.getProvider();
    }

    @Override
    public GatewayResult withdraw(UUID playerId, Money amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(Objects.requireNonNull(playerId, "playerId"));
        return map(requireProvider().withdrawPlayer(player, amount.vaultAmount()));
    }

    @Override
    public GatewayResult deposit(UUID playerId, Money amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(Objects.requireNonNull(playerId, "playerId"));
        return map(requireProvider().depositPlayer(player, amount.vaultAmount()));
    }

    private Economy requireProvider() {
        Economy provider = currentProvider();
        if (provider == null) {
            throw new IllegalStateException("Vault economy provider is not enabled");
        }
        return provider;
    }

    private static GatewayResult map(EconomyResponse response) {
        if (response == null) {
            return GatewayResult.unknown("Vault returned null");
        }
        return response.transactionSuccess()
                ? GatewayResult.applied()
                : GatewayResult.rejected(response.errorMessage);
    }
}
