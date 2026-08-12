package me.arthed.smartgambling.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteEconomyFailureInjectionTest {
    private static final Logger LOGGER = Logger.getLogger("ledger-failure-test");

    static {
        LOGGER.setLevel(Level.OFF);
        LOGGER.setUseParentHandlers(false);
    }

    @TempDir
    Path temporary;

    @Test
    void failureBeforeCallingNeverInvokesVaultAndPreparedRowsRecoverAsCancelled() throws Exception {
        Path database = database("before-calling.db");
        initialize(database);
        execute(database, """
                CREATE TRIGGER fail_transition_to_calling
                BEFORE UPDATE OF state ON transactions
                WHEN OLD.state='PREPARED' AND NEW.state='CALLING'
                BEGIN
                    SELECT RAISE(ABORT, 'injected transition failure');
                END
                """);

        ScriptedGateway firstGateway = new ScriptedGateway();
        try (SQLiteEconomyService service = service(database, firstGateway)) {
            PlaceResult result = service.place(key(UUID.randomUUID(), "before-calling"), Money.of(12));
            assertEquals(PlaceResult.Status.STORAGE_FAILURE, result.status());
            assertEquals(0, firstGateway.withdrawCalls);
            assertEquals("PREPARED", queryString(database,
                    "SELECT state FROM transactions WHERE direction='DEBIT'"));
        }

        execute(database, "DROP TRIGGER fail_transition_to_calling");
        ScriptedGateway recoveryGateway = new ScriptedGateway();
        try (SQLiteEconomyService recovered = service(database, recoveryGateway)) {
            assertEquals(0, recoveryGateway.withdrawCalls);
            assertEquals(0, recoveryGateway.depositCalls);
            assertFalse(recovered.hasUnresolvedFunds());
            assertEquals(
                    EconomyService.TransactionState.REJECTED,
                    recovered.list(EconomyService.LedgerQuery.recent(10)).get(0).state()
            );
        }
    }

    @Test
    void queryOnlyDatabaseFailsClosedBeforeVaultAndLeavesNoWager() throws Exception {
        Path database = database("query-only.db");
        UUID player = UUID.randomUUID();
        ScriptedGateway gateway = new ScriptedGateway();
        try (SQLiteEconomyService service = service(database, gateway)) {
            Connection connection = connectionOf(service);
            pragma(connection, "PRAGMA query_only=ON");

            PlaceResult result = service.place(key(player, "read-only"), Money.of(9));

            assertEquals(PlaceResult.Status.STORAGE_FAILURE, result.status());
            assertEquals(0, gateway.withdrawCalls);
            assertTrue(service.isPlayerFrozen(player));
            pragma(connection, "PRAGMA query_only=OFF");
        }
        assertEquals(0L, queryLong(database, "SELECT COUNT(*) FROM wagers"));
        assertEquals(0L, queryLong(database, "SELECT COUNT(*) FROM transactions"));

        ScriptedGateway recoveryGateway = new ScriptedGateway();
        try (SQLiteEconomyService ignored = service(database, recoveryGateway)) {
            assertEquals(0, recoveryGateway.withdrawCalls);
            assertEquals(0, recoveryGateway.depositCalls);
        }
    }

    @Test
    void nullVaultResultBecomesUnknownAndIsNeverReplayed() {
        Path database = database("null-result.db");
        UUID player = UUID.randomUUID();
        WagerKey key = key(player, "null-result");
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.withdrawals.add(() -> null);

        try (SQLiteEconomyService service = service(database, gateway)) {
            assertEquals(PlaceResult.Status.UNKNOWN, service.place(key, Money.of(15)).status());
            assertEquals(PlaceResult.Status.UNKNOWN, service.place(key, Money.of(15)).status());
            service.retryReady(10);
            assertTrue(service.isPlayerFrozen(player));
            assertEquals(1, gateway.withdrawCalls);
        }

        ScriptedGateway recoveryGateway = new ScriptedGateway();
        try (SQLiteEconomyService recovered = service(database, recoveryGateway)) {
            assertTrue(recovered.isPlayerFrozen(player));
            assertEquals(0, recoveryGateway.withdrawCalls);
            assertEquals(0, recoveryGateway.depositCalls);
        }
    }

    @Test
    void runtimeThrownAfterVaultSideEffectBecomesUnknownAndIsNeverReplayed() {
        Path database = database("throw-after-call.db");
        UUID player = UUID.randomUUID();
        WagerKey key = key(player, "throw-after-call");
        AtomicInteger providerSideEffects = new AtomicInteger();
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.withdrawals.add(() -> {
            providerSideEffects.incrementAndGet();
            throw new IllegalStateException("provider threw after applying debit");
        });

        try (SQLiteEconomyService service = service(database, gateway)) {
            assertEquals(PlaceResult.Status.UNKNOWN, service.place(key, Money.of(20)).status());
            assertEquals(PlaceResult.Status.UNKNOWN, service.place(key, Money.of(20)).status());
            service.retryReady(10);
            assertEquals(1, gateway.withdrawCalls);
            assertEquals(1, providerSideEffects.get());
        }

        ScriptedGateway recoveryGateway = new ScriptedGateway();
        try (SQLiteEconomyService recovered = service(database, recoveryGateway)) {
            assertTrue(recovered.isPlayerFrozen(player));
            assertEquals(0, recoveryGateway.withdrawCalls);
            assertEquals(0, recoveryGateway.depositCalls);
        }
        assertEquals(1, providerSideEffects.get());
    }

    @Test
    void appliedVaultCallWithReceiptWriteFailureRecoversAsUnknownWithoutReplay() throws Exception {
        Path database = database("receipt-failure.db");
        UUID player = UUID.randomUUID();
        initialize(database);
        execute(database, """
                CREATE TRIGGER fail_applied_receipt
                BEFORE UPDATE OF state ON transactions
                WHEN OLD.state='CALLING' AND NEW.state='APPLIED'
                BEGIN
                    SELECT RAISE(ABORT, 'injected receipt failure');
                END
                """);

        ScriptedGateway firstGateway = new ScriptedGateway();
        try (SQLiteEconomyService service = service(database, firstGateway)) {
            PlaceResult result = service.place(key(player, "receipt-failure"), Money.of(30));
            assertEquals(PlaceResult.Status.STORAGE_FAILURE, result.status());
            assertEquals(1, firstGateway.withdrawCalls);
            assertEquals("CALLING", queryString(database,
                    "SELECT state FROM transactions WHERE direction='DEBIT'"));
        }

        ScriptedGateway recoveryGateway = new ScriptedGateway();
        try (SQLiteEconomyService recovered = service(database, recoveryGateway)) {
            assertTrue(recovered.isPlayerFrozen(player));
            assertEquals(0, recoveryGateway.withdrawCalls);
            assertEquals(0, recoveryGateway.depositCalls);
            List<EconomyService.LedgerTransaction> unknown = recovered.list(
                    new EconomyService.LedgerQuery(
                            player,
                            EconomyService.TransactionState.UNKNOWN,
                            10,
                            0
                    )
            );
            assertEquals(1, unknown.size());
            recovered.retryReady(10);
            assertEquals(0, recoveryGateway.withdrawCalls);
            assertEquals(0, recoveryGateway.depositCalls);
        }
    }

    @Test
    void appliedCreditWithReceiptWriteFailureRecoversAsUnknownWithoutSecondDeposit() throws Exception {
        Path database = database("credit-receipt-failure.db");
        UUID player = UUID.randomUUID();
        initialize(database);
        execute(database, """
                CREATE TRIGGER fail_applied_credit_receipt
                BEFORE UPDATE OF state ON transactions
                WHEN OLD.direction='CREDIT' AND OLD.state='CALLING' AND NEW.state='APPLIED'
                BEGIN
                    SELECT RAISE(ABORT, 'injected credit receipt failure');
                END
                """);

        ScriptedGateway firstGateway = new ScriptedGateway();
        try (SQLiteEconomyService service = service(database, firstGateway)) {
            PlaceResult wager = service.place(key(player, "credit-receipt-failure"), Money.of(14));
            assertTrue(wager.accepted());
            TxResult result = service.resolve(
                    "credit-receipt-failure",
                    wager.wager(),
                    WagerResolution.payout(Money.of(28))
            );
            assertEquals(TxResult.Status.STORAGE_FAILURE, result.status());
            assertEquals(1, firstGateway.withdrawCalls);
            assertEquals(1, firstGateway.depositCalls);
            assertEquals("CALLING", queryString(database,
                    "SELECT state FROM transactions WHERE direction='CREDIT'"));
            assertEquals("SETTLING", queryString(database,
                    "SELECT state FROM wagers WHERE player_uuid='" + player + "'"));
        }

        ScriptedGateway recoveryGateway = new ScriptedGateway();
        try (SQLiteEconomyService recovered = service(database, recoveryGateway)) {
            assertTrue(recovered.isPlayerFrozen(player));
            assertEquals(0, recoveryGateway.withdrawCalls);
            assertEquals(0, recoveryGateway.depositCalls);
            assertEquals(1, recovered.list(new EconomyService.LedgerQuery(
                    player,
                    EconomyService.TransactionState.UNKNOWN,
                    10,
                    0
            )).size());
            assertEquals("UNKNOWN", queryString(database,
                    "SELECT state FROM wagers WHERE player_uuid='" + player + "'"));
            recovered.retryReady(10);
            assertEquals(0, recoveryGateway.depositCalls);
        }
    }

    @Test
    void readyPayoutFreezesOnlyItsPlayerAndSuccessfulRetryUnfreezes() throws Exception {
        Path database = database("ready-freeze.db");
        UUID player = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.deposits.add(() -> EconomyGateway.GatewayResult.rejected("provider unavailable"));
        gateway.deposits.add(EconomyGateway.GatewayResult::applied);

        try (SQLiteEconomyService service = service(database, gateway)) {
            PlaceResult wager = service.place(key(player, "ready-payout"), Money.of(10));
            assertEquals(
                    TxResult.Status.READY,
                    service.resolve("ready-payout", wager.wager(), WagerResolution.payout(Money.of(25))).status()
            );
            assertTrue(service.isPlayerFrozen(player));
            assertFalse(service.isPlayerFrozen(otherPlayer));
            assertEquals("PAYOUT_LOCKED", queryString(database,
                    "SELECT state FROM wagers WHERE player_uuid='" + player + "'"));
            assertEquals(
                    PlaceResult.Status.PLAYER_FROZEN,
                    service.place(key(player, "blocked-by-credit"), Money.of(1)).status()
            );
            assertEquals(1, gateway.withdrawCalls);

            EconomyService.RetryReport retry = service.retryReady(10);
            assertEquals(1, retry.applied());
            assertFalse(service.isPlayerFrozen(player));
            assertFalse(service.hasUnresolvedFunds());
            assertEquals("CLOSED", queryString(database,
                    "SELECT state FROM wagers WHERE player_uuid='" + player + "'"));
        }
    }

    @Test
    void reusedOperationIdWithDifferentWagerConflictsWithoutConsumingIt() {
        ScriptedGateway gateway = new ScriptedGateway();
        try (SQLiteEconomyService service = service(database("operation-conflict.db"), gateway)) {
            PlaceResult first = service.place(key(UUID.randomUUID(), "first"), Money.of(5));
            PlaceResult second = service.place(key(UUID.randomUUID(), "second"), Money.of(5));

            assertEquals(
                    TxResult.Status.DURABLE,
                    service.resolve("shared-operation", first.wager(), WagerResolution.loss()).status()
            );
            assertEquals(
                    TxResult.Status.CONFLICT,
                    service.resolve("shared-operation", second.wager(), WagerResolution.loss()).status()
            );
            assertEquals(1, service.activeWagerCount());
            assertEquals(
                    TxResult.Status.DURABLE,
                    service.resolve("second-operation", second.wager(), WagerResolution.loss()).status()
            );
        }
    }

    @Test
    void terminalWagerKeyCannotBeAcceptedAgain() {
        ScriptedGateway gateway = new ScriptedGateway();
        UUID player = UUID.randomUUID();
        WagerKey key = key(player, "terminal-key");
        try (SQLiteEconomyService service = service(database("terminal-key.db"), gateway)) {
            PlaceResult placed = service.place(key, Money.of(11));
            assertEquals(TxResult.Status.DURABLE,
                    service.resolve("terminal-loss", placed.wager(), WagerResolution.loss()).status());

            PlaceResult replay = service.place(key, Money.of(11));
            assertEquals(PlaceResult.Status.REJECTED, replay.status());
            assertEquals(1, gateway.withdrawCalls);
        }
    }

    @Test
    void unknownDebitDecisionsEitherCancelOrRefundWithoutReplayingDebit() throws Exception {
        Path database = database("unknown-debit-decisions.db");
        UUID notAppliedPlayer = UUID.randomUUID();
        UUID appliedPlayer = UUID.randomUUID();
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.withdrawals.add(() -> EconomyGateway.GatewayResult.unknown("missing response"));
        gateway.withdrawals.add(() -> EconomyGateway.GatewayResult.unknown("missing response"));

        try (SQLiteEconomyService service = service(database, gateway)) {
            assertEquals(PlaceResult.Status.UNKNOWN,
                    service.place(key(notAppliedPlayer, "not-applied"), Money.of(13)).status());
            EconomyService.LedgerTransaction firstUnknown = unknown(service, notAppliedPlayer);
            assertEquals(
                    EconomyService.ReconcileResult.Status.RESOLVED,
                    service.resolveUnknown(
                            firstUnknown.id(),
                            EconomyService.UnknownDecision.NOT_APPLIED,
                            "TEST",
                            "provider confirms no debit"
                    ).status()
            );
            assertFalse(service.isPlayerFrozen(notAppliedPlayer));
            assertEquals(0, gateway.depositCalls);
            assertEquals("CLOSED", queryString(database,
                    "SELECT state FROM wagers WHERE player_uuid='" + notAppliedPlayer + "'"));

            assertEquals(PlaceResult.Status.UNKNOWN,
                    service.place(key(appliedPlayer, "applied"), Money.of(17)).status());
            EconomyService.LedgerTransaction secondUnknown = unknown(service, appliedPlayer);
            assertEquals(
                    EconomyService.ReconcileResult.Status.RESOLVED,
                    service.resolveUnknown(
                            secondUnknown.id(),
                            EconomyService.UnknownDecision.APPLIED,
                            "TEST",
                            "provider confirms debit"
                    ).status()
            );
            assertFalse(service.isPlayerFrozen(appliedPlayer));
            assertEquals(1, gateway.depositCalls);
            assertEquals(2, gateway.withdrawCalls);
            assertEquals("CLOSED", queryString(database,
                    "SELECT state FROM wagers WHERE player_uuid='" + appliedPlayer + "'"));
        }
    }

    @Test
    void appliedUnknownCreditDecisionSettlesWithoutSecondDeposit() {
        Path database = database("unknown-credit-applied.db");
        UUID player = UUID.randomUUID();
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.deposits.add(() -> EconomyGateway.GatewayResult.unknown("receipt lost"));

        try (SQLiteEconomyService service = service(database, gateway)) {
            PlaceResult wager = service.place(key(player, "credit-applied"), Money.of(7));
            assertEquals(
                    TxResult.Status.UNKNOWN,
                    service.resolve("credit-applied", wager.wager(), WagerResolution.payout(Money.of(19))).status()
            );
            EconomyService.LedgerTransaction unknown = unknown(service, player);
            assertEquals(
                    EconomyService.ReconcileResult.Status.RESOLVED,
                    service.resolveUnknown(
                            unknown.id(),
                            EconomyService.UnknownDecision.APPLIED,
                            "TEST",
                            "provider confirms credit"
                    ).status()
            );
            assertFalse(service.isPlayerFrozen(player));
            assertFalse(service.hasUnresolvedFunds());
            assertEquals(1, gateway.depositCalls);
        }
    }

    @Test
    void schemaRejectsStatesAndDecisionsOutsideThePlannedSets() throws Exception {
        Path database = database("strict-states.db");
        ScriptedGateway gateway = new ScriptedGateway();
        try (SQLiteEconomyService service = service(database, gateway)) {
            assertTrue(service.place(
                    key(UUID.randomUUID(), "strict"),
                    Money.of(new BigDecimal("6.2500"))
            ).accepted());
        }

        String wagerSchema = normalizedSchema(database, "wagers");
        String transactionSchema = normalizedSchema(database, "transactions");
        assertTrue(wagerSchema.contains("CHECK(STATEIN('PREPARING','OPEN','LOCKED','PAYOUT_LOCKED','SETTLING','CLOSED','UNKNOWN'))"));
        assertTrue(wagerSchema.contains("CHECK(RESOLUTION_TYPEISNULLORRESOLUTION_TYPEIN('LOSS','REFUND','PAYOUT'))"));
        assertTrue(transactionSchema.contains("CHECK(STATEIN('PREPARED','CALLING','APPLIED','READY','REJECTED','UNKNOWN'))"));
        assertTrue(transactionSchema.contains("CHECK(RESOLVED_DECISIONISNULLORRESOLVED_DECISIONIN('APPLIED','NOT_APPLIED'))"));
        assertFalse(wagerSchema.contains("UNSCALED"));
        assertFalse(transactionSchema.contains("UNSCALED"));
        assertEquals("text:6.25", queryString(database, "SELECT typeof(stake) || ':' || stake FROM wagers"));
        assertEquals("text:6.25", queryString(database, "SELECT typeof(amount) || ':' || amount FROM transactions"));

        assertThrows(SQLException.class, () -> execute(database, "UPDATE wagers SET state='BROKEN'"));
        assertThrows(SQLException.class, () -> execute(database, "UPDATE transactions SET state='BROKEN'"));
        assertThrows(SQLException.class, () -> execute(database, "UPDATE wagers SET resolution_type='BROKEN'"));
        assertThrows(SQLException.class, () -> execute(database, "UPDATE transactions SET resolved_decision='BROKEN'"));
    }

    private SQLiteEconomyService service(Path database, EconomyGateway gateway) {
        SQLiteEconomyService service = new SQLiteEconomyService(database, gateway, LOGGER);
        service.recover();
        return service;
    }

    private void initialize(Path database) {
        try (SQLiteEconomyService ignored = service(database, new ScriptedGateway())) {
            // Constructor creates and validates schema=1.
        }
    }

    private Path database(String name) {
        return temporary.resolve(name);
    }

    private static WagerKey key(UUID player, String nonce) {
        return new WagerKey("failure-test", "machine", "round", player, nonce);
    }

    private static EconomyService.LedgerTransaction unknown(SQLiteEconomyService service, UUID player) {
        return service.list(new EconomyService.LedgerQuery(
                player,
                EconomyService.TransactionState.UNKNOWN,
                10,
                0
        )).get(0);
    }

    private static Connection connectionOf(SQLiteEconomyService service) throws Exception {
        Field field = SQLiteEconomyService.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (Connection) field.get(service);
    }

    private static void pragma(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void execute(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long queryLong(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.getLong(1);
        }
    }

    private static String queryString(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }

    private static String normalizedSchema(Path database, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='table' AND name='" + table + "'"
             )) {
            return result.getString(1).replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        }
    }

    @FunctionalInterface
    private interface GatewayAction {
        EconomyGateway.GatewayResult call();
    }

    private static final class ScriptedGateway implements EconomyGateway {
        private final Queue<GatewayAction> withdrawals = new ArrayDeque<>();
        private final Queue<GatewayAction> deposits = new ArrayDeque<>();
        private int withdrawCalls;
        private int depositCalls;

        @Override
        public GatewayResult withdraw(UUID playerId, Money amount) {
            withdrawCalls++;
            GatewayAction action = withdrawals.poll();
            return action == null ? GatewayResult.applied() : action.call();
        }

        @Override
        public GatewayResult deposit(UUID playerId, Money amount) {
            depositCalls++;
            GatewayAction action = deposits.poll();
            return action == null ? GatewayResult.applied() : action.call();
        }
    }
}
