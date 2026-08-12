package me.arthed.smartgambling.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteEconomyServiceTest {
    private static final Logger LOGGER = Logger.getLogger("ledger-test");

    @TempDir
    Path temporary;

    @Test
    void placeIsDurableAndIdempotentByWagerKey() {
        FakeGateway gateway = new FakeGateway();
        try (SQLiteEconomyService service = service(gateway)) {
            WagerKey key = key(UUID.randomUUID(), "spin-1");
            PlaceResult first = service.place(key, Money.of(100));
            PlaceResult replay = service.place(key, Money.of(100));

            assertEquals(PlaceResult.Status.ACCEPTED, first.status());
            assertEquals(PlaceResult.Status.ALREADY_ACCEPTED, replay.status());
            assertEquals(first.wager(), replay.wager());
            assertEquals(1, gateway.withdrawCalls);
            assertEquals(1, service.activeWagerCount());
        }
    }

    @Test
    void knownDebitRejectionDoesNotCreateAnActiveObligation() {
        FakeGateway gateway = new FakeGateway();
        gateway.withdrawals.add(EconomyGateway.GatewayResult.rejected("no funds"));
        try (SQLiteEconomyService service = service(gateway)) {
            PlaceResult result = service.place(key(UUID.randomUUID(), "rejected"), Money.of(10));
            assertEquals(PlaceResult.Status.REJECTED, result.status());
            assertEquals(0, service.activeWagerCount());
            assertFalse(service.hasUnresolvedFunds());
        }
    }

    @Test
    void unknownDebitFreezesOnlyTheAffectedPlayerAndIsNeverRetried() {
        UUID uncertainPlayer = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        FakeGateway gateway = new FakeGateway();
        gateway.withdrawals.add(EconomyGateway.GatewayResult.unknown("timeout"));
        gateway.withdrawals.add(EconomyGateway.GatewayResult.applied());
        try (SQLiteEconomyService service = service(gateway)) {
            PlaceResult uncertain = service.place(key(uncertainPlayer, "unknown"), Money.of(25));
            PlaceResult other = service.place(key(otherPlayer, "safe"), Money.of(25));

            assertEquals(PlaceResult.Status.UNKNOWN, uncertain.status());
            assertEquals(PlaceResult.Status.ACCEPTED, other.status());
            assertTrue(service.isPlayerFrozen(uncertainPlayer));
            assertFalse(service.isPlayerFrozen(otherPlayer));
            service.retryReady(100);
            assertEquals(2, gateway.withdrawCalls);
            assertEquals(0, gateway.depositCalls);
        }
    }

    @Test
    void rejectedCreditBecomesReadyAndRetriesSafely() {
        FakeGateway gateway = new FakeGateway();
        gateway.deposits.add(EconomyGateway.GatewayResult.rejected("provider offline"));
        gateway.deposits.add(EconomyGateway.GatewayResult.applied());
        try (SQLiteEconomyService service = service(gateway)) {
            PlaceResult place = service.place(key(UUID.randomUUID(), "payout"), Money.of(40));
            TxResult settlement = service.resolve("settle-1", place.wager(),
                    WagerResolution.payout(Money.of(75)));

            assertEquals(TxResult.Status.READY, settlement.status());
            assertTrue(service.hasUnresolvedFunds());
            EconomyService.RetryReport retry = service.retryReady(10);
            assertEquals(1, retry.applied());
            assertFalse(service.hasUnresolvedFunds());
            assertEquals(2, gateway.depositCalls);
        }
    }

    @Test
    void lockAndResolveBatchesAreAtomicAndIdempotent() {
        FakeGateway gateway = new FakeGateway();
        try (SQLiteEconomyService service = service(gateway)) {
            PlaceResult one = service.place(key(UUID.randomUUID(), "one"), Money.of(10));
            PlaceResult two = service.place(key(UUID.randomUUID(), "two"), Money.of(10));
            List<WagerHandle> wagers = List.of(one.wager(), two.wager());

            assertEquals(TxResult.Status.DURABLE, service.lockAll("round-lock", wagers).status());
            assertEquals(TxResult.Status.ALREADY_APPLIED, service.lockAll("round-lock", wagers).status());
            TxResult resolved = service.resolveAll("round-result", List.of(
                    new EconomyService.Resolution(one.wager(), WagerResolution.loss()),
                    new EconomyService.Resolution(two.wager(), WagerResolution.payout(Money.of(20)))
            ));
            assertEquals(TxResult.Status.DURABLE, resolved.status());
            assertEquals(TxResult.Status.ALREADY_APPLIED, service.resolveAll("round-result", List.of(
                    new EconomyService.Resolution(one.wager(), WagerResolution.loss()),
                    new EconomyService.Resolution(two.wager(), WagerResolution.payout(Money.of(20)))
            )).status());
            assertFalse(service.hasUnresolvedFunds());
            assertEquals(1, gateway.depositCalls);
        }
    }

    @Test
    void replayedResolutionAggregatesUnknownThenReadyThenApplied() {
        FakeGateway gateway = new FakeGateway();
        gateway.deposits.add(EconomyGateway.GatewayResult.rejected("retry later"));
        gateway.deposits.add(EconomyGateway.GatewayResult.unknown("lost provider receipt"));
        try (SQLiteEconomyService service = service(gateway)) {
            PlaceResult readyWager = service.place(key(UUID.randomUUID(), "aggregate-ready"), Money.of(10));
            PlaceResult lossWager = service.place(key(UUID.randomUUID(), "aggregate-loss"), Money.of(10));
            List<EconomyService.Resolution> readyBatch = List.of(
                    new EconomyService.Resolution(readyWager.wager(), WagerResolution.payout(Money.of(20))),
                    new EconomyService.Resolution(lossWager.wager(), WagerResolution.loss())
            );

            assertEquals(TxResult.Status.READY, service.resolveAll("aggregate-ready", readyBatch).status());
            assertEquals(TxResult.Status.READY, service.resolveAll("aggregate-ready", readyBatch).status());

            PlaceResult unknownWager = service.place(key(UUID.randomUUID(), "aggregate-unknown"), Money.of(10));
            List<EconomyService.Resolution> unknownBatch = List.of(
                    new EconomyService.Resolution(unknownWager.wager(), WagerResolution.payout(Money.of(30)))
            );
            assertEquals(TxResult.Status.UNKNOWN,
                    service.resolveAll("aggregate-unknown", unknownBatch).status());
            assertEquals(TxResult.Status.UNKNOWN,
                    service.resolveAll("aggregate-unknown", unknownBatch).status());

            PlaceResult appliedWager = service.place(key(UUID.randomUUID(), "aggregate-applied"), Money.of(10));
            List<EconomyService.Resolution> appliedBatch = List.of(
                    new EconomyService.Resolution(appliedWager.wager(), WagerResolution.loss())
            );
            assertEquals(TxResult.Status.DURABLE,
                    service.resolveAll("aggregate-applied", appliedBatch).status());
            assertEquals(TxResult.Status.ALREADY_APPLIED,
                    service.resolveAll("aggregate-applied", appliedBatch).status());
            assertEquals(2, gateway.depositCalls);
        }
    }

    @Test
    void replayedPreparedOrCallingCreditIsReportedAsUnknown() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.deposits.add(EconomyGateway.GatewayResult.rejected("retry later"));
        Path database = temporary.resolve(SQLiteEconomyService.DATABASE_FILE_NAME);
        try (SQLiteEconomyService service = new SQLiteEconomyService(database, gateway, LOGGER)) {
            PlaceResult wager = service.place(key(UUID.randomUUID(), "transient-replay"), Money.of(10));
            EconomyService.Resolution resolution = new EconomyService.Resolution(
                    wager.wager(),
                    WagerResolution.payout(Money.of(20))
            );
            assertEquals(TxResult.Status.READY,
                    service.resolveAll("transient-replay", List.of(resolution)).status());

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE transactions SET state=? WHERE operation_id='transient-replay'")) {
                update.setString(1, "PREPARED");
                update.executeUpdate();
                assertEquals(TxResult.Status.UNKNOWN,
                        service.resolveAll("transient-replay", List.of(resolution)).status());

                update.setString(1, "CALLING");
                update.executeUpdate();
                assertEquals(TxResult.Status.UNKNOWN,
                        service.resolveAll("transient-replay", List.of(resolution)).status());
            }
            assertEquals(1, gateway.depositCalls);
        }
    }

    @Test
    void unknownCreditRequiresReconciliationBeforeASafeRetry() {
        UUID player = UUID.randomUUID();
        FakeGateway gateway = new FakeGateway();
        gateway.deposits.add(EconomyGateway.GatewayResult.unknown("lost response"));
        gateway.deposits.add(EconomyGateway.GatewayResult.applied());
        try (SQLiteEconomyService service = service(gateway)) {
            PlaceResult place = service.place(key(player, "unknown-credit"), Money.of(10));
            assertEquals(TxResult.Status.UNKNOWN,
                    service.resolve("unknown-payout", place.wager(), WagerResolution.payout(Money.of(15))).status());
            assertTrue(service.isPlayerFrozen(player));

            EconomyService.LedgerTransaction unknown = service.list(new EconomyService.LedgerQuery(
                    player, EconomyService.TransactionState.UNKNOWN, 20, 0)).get(0);
            EconomyService.ReconcileResult reconciled = service.resolveUnknown(
                    unknown.id(), EconomyService.UnknownDecision.NOT_APPLIED, "CONSOLE", "checked provider log");
            assertEquals(EconomyService.ReconcileResult.Status.RESOLVED, reconciled.status());
            assertFalse(service.isPlayerFrozen(player));
            assertFalse(service.hasUnresolvedFunds());
            assertEquals(2, gateway.depositCalls);
        }
    }

    @Test
    void restartRefundsAnOpenWager() {
        Path database = temporary.resolve("restart.db");
        FakeGateway firstGateway = new FakeGateway();
        try (SQLiteEconomyService first = new SQLiteEconomyService(database, firstGateway, LOGGER)) {
            assertTrue(first.place(key(UUID.randomUUID(), "interrupted"), Money.of(30)).accepted());
        }

        FakeGateway recoveredGateway = new FakeGateway();
        try (SQLiteEconomyService recovered = new SQLiteEconomyService(database, recoveredGateway, LOGGER)) {
            recovered.recover();
            assertEquals(1, recoveredGateway.depositCalls);
            assertFalse(recovered.hasUnresolvedFunds());
        }
    }

    @Test
    void restartTurnsCallingIntoUnknownWithoutCallingProvider() throws Exception {
        UUID player = UUID.randomUUID();
        Path database = temporary.resolve("calling.db");
        try (SQLiteEconomyService first = new SQLiteEconomyService(database, new FakeGateway(), LOGGER)) {
            PlaceResult place = first.place(key(player, "calling"), Money.of(5));
            assertTrue(place.accepted());
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (PreparedStatement tx = connection.prepareStatement(
                    "UPDATE transactions SET state='CALLING' WHERE direction='DEBIT'");
                 PreparedStatement wager = connection.prepareStatement(
                         "UPDATE wagers SET state='PREPARING'")) {
                tx.executeUpdate();
                wager.executeUpdate();
            }
        }

        FakeGateway gateway = new FakeGateway();
        try (SQLiteEconomyService recovered = new SQLiteEconomyService(database, gateway, LOGGER)) {
            recovered.recover();
            assertTrue(recovered.isPlayerFrozen(player));
            assertEquals(0, gateway.withdrawCalls);
            assertEquals(0, gateway.depositCalls);
        }
    }

    @Test
    void legacyYamlMigrationIsAtomicIdempotentAndPreservesUncertain() throws Exception {
        UUID readyPlayer = UUID.randomUUID();
        UUID uncertainPlayer = UUID.randomUUID();
        Path legacy = temporary.resolve("pending-payouts.yml");
        Files.writeString(legacy, """
                credits:
                  %s:
                    ready: 12.50
                  %s:
                    uncertain: 8
                """.formatted(readyPlayer, uncertainPlayer));
        FakeGateway gateway = new FakeGateway();
        try (SQLiteEconomyService service = service(gateway)) {
            EconomyService.MigrationReport result = service.migratePendingPayouts(legacy);
            assertEquals(EconomyService.MigrationReport.Status.IMPORTED, result.status());
            assertEquals(1, result.readyImported());
            assertEquals(1, result.uncertainImported());
            assertEquals(0, gateway.depositCalls);
            assertTrue(service.isPlayerFrozen(readyPlayer));
            assertTrue(service.isPlayerFrozen(uncertainPlayer));

            EconomyService.RetryReport retry = service.retryReady(10);
            assertEquals(1, retry.applied());
            assertEquals(1, gateway.depositCalls);
            assertFalse(service.isPlayerFrozen(readyPlayer));

            Path backup;
            try (var files = Files.list(temporary)) {
                backup = files.filter(path -> path.getFileName().toString().contains(".migrated-"))
                        .findFirst().orElseThrow();
            }
            Files.copy(backup, legacy);
            assertEquals(EconomyService.MigrationReport.Status.ALREADY_IMPORTED,
                    service.migratePendingPayouts(legacy).status());
            assertEquals(1, gateway.depositCalls);
        }
    }

    @Test
    void moneyIsExactUntilTheGatewayBoundary() {
        Money money = Money.of(new BigDecimal("12.50"));
        assertEquals(new BigDecimal("12.5"), money.decimal());
        assertEquals(Money.of(new BigDecimal("25.00")), money.multiply(BigDecimal.valueOf(2), 2));
    }

    private SQLiteEconomyService service(FakeGateway gateway) {
        SQLiteEconomyService service = new SQLiteEconomyService(
                temporary.resolve(SQLiteEconomyService.DATABASE_FILE_NAME), gateway, LOGGER
        );
        service.recover();
        return service;
    }

    private static WagerKey key(UUID player, String nonce) {
        return new WagerKey("test", "machine", "round", player, nonce);
    }

    private static final class FakeGateway implements EconomyGateway {
        private final Queue<GatewayResult> withdrawals = new ArrayDeque<>();
        private final Queue<GatewayResult> deposits = new ArrayDeque<>();
        private int withdrawCalls;
        private int depositCalls;
        private boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public GatewayResult withdraw(UUID playerId, Money amount) {
            withdrawCalls++;
            GatewayResult result = withdrawals.poll();
            return result == null ? GatewayResult.applied() : result;
        }

        @Override
        public GatewayResult deposit(UUID playerId, Money amount) {
            depositCalls++;
            GatewayResult result = deposits.poll();
            return result == null ? GatewayResult.applied() : result;
        }
    }
}
