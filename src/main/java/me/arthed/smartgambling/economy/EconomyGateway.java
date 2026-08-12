package me.arthed.smartgambling.economy;

import java.util.Objects;
import java.util.UUID;

/** Small testable boundary around Vault. Implementations must map null/throws to UNKNOWN. */
public interface EconomyGateway {
    /**
     * Returns whether calling the provider is safe right now.
     *
     * <p>Test gateways and providers without a lifecycle can keep the default.
     * Vault overrides this with a live service-registration check so a provider
     * that is registered but not enabled cannot turn a safe READY credit into
     * an ambiguous CALLING/UNKNOWN transaction.</p>
     */
    default boolean isAvailable() {
        return true;
    }

    GatewayResult withdraw(UUID playerId, Money amount);

    GatewayResult deposit(UUID playerId, Money amount);

    record GatewayResult(Status status, String detail) {
        public GatewayResult {
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
        }

        public enum Status { APPLIED, REJECTED, UNKNOWN }

        public static GatewayResult applied() {
            return new GatewayResult(Status.APPLIED, "");
        }

        public static GatewayResult rejected(String detail) {
            return new GatewayResult(Status.REJECTED, detail);
        }

        public static GatewayResult unknown(String detail) {
            return new GatewayResult(Status.UNKNOWN, detail);
        }
    }
}
