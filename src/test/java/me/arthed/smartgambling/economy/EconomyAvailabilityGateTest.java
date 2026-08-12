package me.arthed.smartgambling.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EconomyAvailabilityGateTest {
    private static final Logger LOGGER = Logger.getLogger(EconomyAvailabilityGateTest.class.getName());

    @TempDir
    Path temporary;

    @Test
    void databaseOpenDoesNotRecoverUntilProviderIsReadyAndGateIsClaimed() {
        Path database = temporary.resolve("recovery-gate.db");
        UUID player = UUID.randomUUID();
        ToggleGateway firstGateway = new ToggleGateway();
        try (SQLiteEconomyService first = new SQLiteEconomyService(database, firstGateway, LOGGER)) {
            assertTrue(first.place(key(player, "interrupted"), Money.of(25)).accepted());
        }

        ToggleGateway recoveryGateway = new ToggleGateway();
        recoveryGateway.available = false;
        try (SQLiteEconomyService recovered = new SQLiteEconomyService(database, recoveryGateway, LOGGER)) {
            // Merely opening SQLite must not call Vault or alter the OPEN wager.
            assertEquals(0, recoveryGateway.depositCalls);
            assertEquals(1, recovered.activeWagerCount());

            RecoveryGate gate = new RecoveryGate();
            assertFalse(gate.runIfReady(recoveryGateway.isAvailable(), recovered::recover));
            assertEquals(0, recoveryGateway.depositCalls);
            assertEquals(1, recovered.activeWagerCount());

            recoveryGateway.available = true;
            assertTrue(gate.runIfReady(recoveryGateway.isAvailable(), recovered::recover));
            assertEquals(1, recoveryGateway.depositCalls);
            assertEquals(0, recovered.activeWagerCount());
            assertFalse(recovered.hasUnresolvedFunds());

            assertFalse(gate.runIfReady(true, recovered::recover));
            assertEquals(1, recoveryGateway.depositCalls);
        }
    }

    @Test
    void unavailableProviderLeavesReadyCreditUnattemptedAndRejectsNewWagerBeforeInsert() {
        ToggleGateway gateway = new ToggleGateway();
        UUID firstPlayer = UUID.randomUUID();
        UUID blockedPlayer = UUID.randomUUID();
        try (SQLiteEconomyService service = new SQLiteEconomyService(
                temporary.resolve("availability.db"), gateway, LOGGER
        )) {
            PlaceResult wager = service.place(key(firstPlayer, "payout"), Money.of(10));
            assertTrue(wager.accepted());

            gateway.available = false;
            assertEquals(TxResult.Status.READY, service.resolve(
                    "availability:payout",
                    wager.wager(),
                    WagerResolution.payout(Money.of(20))
            ).status());

            EconomyService.LedgerTransaction credit = credit(service, firstPlayer);
            assertEquals(EconomyService.TransactionState.READY, credit.state());
            assertEquals(0, credit.attemptCount());
            assertEquals(0, gateway.depositCalls);

            assertEquals(PlaceResult.Status.STORAGE_FAILURE,
                    service.place(key(blockedPlayer, "blocked"), Money.of(5)).status());
            assertTrue(service.find(key(blockedPlayer, "blocked")).isEmpty());
            assertTrue(service.hasUnresolvedFunds());

            EconomyService.RetryReport unavailableRetry = service.retryReady(10);
            assertEquals(0, unavailableRetry.attempted());
            assertEquals(0, gateway.depositCalls);
            assertEquals(0, credit(service, firstPlayer).attemptCount());

            gateway.available = true;
            EconomyService.RetryReport retry = service.retryReady(10);
            assertEquals(1, retry.attempted());
            assertEquals(1, retry.applied());
            assertEquals(1, gateway.depositCalls);
            assertFalse(service.hasUnresolvedFunds());
        }
    }

    private static EconomyService.LedgerTransaction credit(SQLiteEconomyService service, UUID player) {
        List<EconomyService.LedgerTransaction> transactions = service.list(
                new EconomyService.LedgerQuery(player, null, 20, 0)
        );
        return transactions.stream()
                .filter(transaction -> transaction.direction() == EconomyService.Direction.CREDIT)
                .findFirst()
                .orElseThrow();
    }

    private static WagerKey key(UUID player, String nonce) {
        return new WagerKey("test", "machine", "round", player, nonce);
    }

    private static final class ToggleGateway implements EconomyGateway {
        private boolean available = true;
        private int withdrawCalls;
        private int depositCalls;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public GatewayResult withdraw(UUID playerId, Money amount) {
            withdrawCalls++;
            return GatewayResult.applied();
        }

        @Override
        public GatewayResult deposit(UUID playerId, Money amount) {
            depositCalls++;
            return GatewayResult.applied();
        }
    }
}
