package me.arthed.smartgambling.economy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * SQLite WAL-backed financial journal.
 *
 * <p>No SQLite transaction is held while calling the external economy. A
 * durable CALLING receipt is committed first; a crash in that state is
 * deliberately recovered as UNKNOWN and never automatically replayed.</p>
 */
public final class SQLiteEconomyService implements EconomyService {
    public static final String DATABASE_FILE_NAME = "economy-ledger.db";
    private static final int SCHEMA_VERSION = 1;
    private static final String WAGER_PREPARING = "PREPARING";
    private static final String WAGER_OPEN = "OPEN";
    private static final String WAGER_LOCKED = "LOCKED";
    private static final String WAGER_PAYOUT_LOCKED = "PAYOUT_LOCKED";
    private static final String WAGER_SETTLING = "SETTLING";
    private static final String WAGER_CLOSED = "CLOSED";
    private static final String WAGER_UNKNOWN = "UNKNOWN";
    private static final String PENDING_MIGRATION_KEY = "migration.pending-payouts.yml";

    private final Path database;
    private final EconomyGateway gateway;
    private final Logger logger;
    private final Connection connection;
    private volatile boolean closed;
    private volatile boolean storageHealthy = true;

    public SQLiteEconomyService(Path database, EconomyGateway gateway, Logger logger) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.logger = Objects.requireNonNull(logger, "logger");
        try {
            Path parent = this.database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.database);
            configureConnection();
            initializeSchema();
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Could not initialize economy ledger " + this.database, exception);
        }
    }

    public static SQLiteEconomyService inDataFolder(Path dataFolder, EconomyGateway gateway, Logger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        return new SQLiteEconomyService(dataFolder.resolve(DATABASE_FILE_NAME), gateway, logger);
    }

    @Override
    public synchronized PlaceResult place(WagerKey key, Money stake) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(stake, "stake");
        if (!readyForWrites()) {
            return new PlaceResult(PlaceResult.Status.STORAGE_FAILURE, null, null, "ledger is unavailable");
        }
        if (!gatewayAvailable()) {
            return new PlaceResult(PlaceResult.Status.STORAGE_FAILURE, null, null,
                    "economy provider is not enabled");
        }
        Optional<WagerRow> existing;
        try {
            existing = findWagerRow(key);
            if (existing.isPresent()) {
                WagerRow row = existing.get();
                WagerHandle handle = row.handle();
                UUID transactionId = stakeTransactionId(row.id());
                if (WAGER_UNKNOWN.equals(row.state())) {
                    return new PlaceResult(PlaceResult.Status.UNKNOWN, handle, transactionId,
                            "the original debit has an unknown provider outcome");
                }
                if (WAGER_CLOSED.equals(row.state())) {
                    return new PlaceResult(PlaceResult.Status.REJECTED, handle, transactionId,
                            "the deterministic wager key belongs to a closed wager");
                }
                if (WAGER_PREPARING.equals(row.state())
                        || WAGER_OPEN.equals(row.state())
                        || WAGER_LOCKED.equals(row.state())
                        || WAGER_PAYOUT_LOCKED.equals(row.state())
                        || WAGER_SETTLING.equals(row.state())) {
                    return new PlaceResult(PlaceResult.Status.ALREADY_ACCEPTED, handle, transactionId,
                            "wager key was already journaled and remains active");
                }
                return new PlaceResult(PlaceResult.Status.REJECTED, handle, transactionId,
                        "the deterministic wager key belongs to terminal wager state " + row.state());
            }
            if (isPlayerFrozen(key.playerId())) {
                return new PlaceResult(PlaceResult.Status.PLAYER_FROZEN, null, null,
                        "player has an unresolved economy transaction");
            }
        } catch (SQLException exception) {
            failStorage("Could not inspect wager before debit", exception);
            return new PlaceResult(PlaceResult.Status.STORAGE_FAILURE, null, null, exception.getMessage());
        }

        UUID wagerId = deterministicUuid("wager", key.canonical());
        UUID transactionId = stakeTransactionId(wagerId);
        WagerHandle handle = new WagerHandle(wagerId, key, key.playerId(), stake);
        long now = now();
        try {
            inTransaction(() -> {
                try (PreparedStatement wager = connection.prepareStatement("""
                        INSERT INTO wagers (
                            id, wager_key, game, machine_id, round_id, player_uuid, nonce,
                            stake, state, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                     PreparedStatement transaction = connection.prepareStatement("""
                        INSERT INTO transactions (
                            id, operation_id, wager_id, player_uuid, direction, purpose,
                            amount, state, attempt_count, detail, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'DEBIT', 'STAKE', ?, 'PREPARED', 0, '', ?, ?)
                        """)) {
                    wager.setString(1, wagerId.toString());
                    wager.setString(2, key.canonical());
                    wager.setString(3, key.game());
                    wager.setString(4, key.machineId());
                    wager.setString(5, key.roundId());
                    wager.setString(6, key.playerId().toString());
                    wager.setString(7, key.nonce());
                    setMoney(wager, 8, stake);
                    wager.setString(9, WAGER_PREPARING);
                    wager.setLong(10, now);
                    wager.setLong(11, now);
                    wager.executeUpdate();

                    transaction.setString(1, transactionId.toString());
                    transaction.setString(2, "place:" + key.canonical());
                    transaction.setString(3, wagerId.toString());
                    transaction.setString(4, key.playerId().toString());
                    setMoney(transaction, 5, stake);
                    transaction.setLong(6, now);
                    transaction.setLong(7, now);
                    transaction.executeUpdate();
                }
                return null;
            });
            transitionToCalling(transactionId, TransactionState.PREPARED, null);
        } catch (SQLException exception) {
            failStorage("Could not durably prepare wager debit", exception);
            return new PlaceResult(PlaceResult.Status.STORAGE_FAILURE, handle, transactionId, exception.getMessage());
        }

        EconomyGateway.GatewayResult provider = callGateway(false, key.playerId(), stake);
        try {
            if (provider.status() == EconomyGateway.GatewayResult.Status.APPLIED) {
                finalizeDebit(transactionId, wagerId, TransactionState.APPLIED, WAGER_OPEN, provider.detail());
                return new PlaceResult(PlaceResult.Status.ACCEPTED, handle, transactionId, provider.detail());
            }
            if (provider.status() == EconomyGateway.GatewayResult.Status.REJECTED) {
                finalizeDebit(transactionId, wagerId, TransactionState.REJECTED, WAGER_CLOSED, provider.detail());
                return new PlaceResult(PlaceResult.Status.REJECTED, handle, transactionId, provider.detail());
            }
            finalizeDebit(transactionId, wagerId, TransactionState.UNKNOWN, WAGER_UNKNOWN, provider.detail());
            return new PlaceResult(PlaceResult.Status.UNKNOWN, handle, transactionId, provider.detail());
        } catch (SQLException exception) {
            failStorage("Vault returned but its debit receipt could not be stored", exception);
            return new PlaceResult(PlaceResult.Status.STORAGE_FAILURE, handle, transactionId,
                    "provider outcome could not be journaled: " + exception.getMessage());
        }
    }

    @Override
    public synchronized TxResult lockAll(String operationId, Collection<WagerHandle> wagers) {
        String operation = requireOperation(operationId);
        List<WagerHandle> batch = uniqueWagers(wagers);
        if (batch.isEmpty()) {
            return new TxResult(TxResult.Status.CONFLICT, null, "lock batch is empty");
        }
        UUID groupId = deterministicUuid("lock-batch", operation);
        if (!readyForWrites()) {
            return new TxResult(TxResult.Status.STORAGE_FAILURE, groupId, "ledger is unavailable");
        }
        try {
            ExistingOperation existing = inspectExistingOperation(operation, batch, null, Purpose.LOCK);
            if (existing == ExistingOperation.MATCH) {
                return new TxResult(TxResult.Status.ALREADY_APPLIED, groupId, "lock operation already applied");
            }
            if (existing == ExistingOperation.CONFLICT) {
                return new TxResult(TxResult.Status.CONFLICT, groupId, "operation id has different contents");
            }
            for (WagerHandle wager : batch) {
                if (isPlayerFrozen(wager.playerId())) {
                    return new TxResult(TxResult.Status.PLAYER_FROZEN, groupId,
                            "one of the players has an unresolved economy transaction");
                }
            }
            inTransaction(() -> {
                long timestamp = now();
                for (WagerHandle wager : batch) {
                    requireMatchingWager(wager, WAGER_OPEN);
                    int changed;
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE wagers SET state='LOCKED', updated_at=? WHERE id=? AND state='OPEN'")) {
                        update.setLong(1, timestamp);
                        update.setString(2, wager.id().toString());
                        changed = update.executeUpdate();
                    }
                    if (changed != 1) {
                        throw new ConflictSQLException("wager is no longer OPEN: " + wager.id());
                    }
                    insertInternalTransaction(operation, wager, Purpose.LOCK, timestamp);
                }
                return null;
            });
            return new TxResult(TxResult.Status.DURABLE, groupId, "all wagers locked atomically");
        } catch (ConflictSQLException exception) {
            return new TxResult(TxResult.Status.CONFLICT, groupId, exception.getMessage());
        } catch (SQLException exception) {
            failStorage("Could not lock wager batch", exception);
            return new TxResult(TxResult.Status.STORAGE_FAILURE, groupId, exception.getMessage());
        }
    }

    @Override
    public synchronized TxResult resolve(String operationId, WagerHandle wager, WagerResolution resolution) {
        return resolveAll(operationId, List.of(new Resolution(wager, resolution)));
    }

    @Override
    public synchronized TxResult resolveAll(String operationId, Collection<Resolution> resolutions) {
        String operation = requireOperation(operationId);
        List<Resolution> batch = uniqueResolutions(resolutions);
        UUID groupId = deterministicUuid("resolve-batch", operation);
        if (batch.isEmpty()) {
            return new TxResult(TxResult.Status.CONFLICT, groupId, "resolution batch is empty");
        }
        if (!readyForWrites()) {
            return new TxResult(TxResult.Status.STORAGE_FAILURE, groupId, "ledger is unavailable");
        }
        try {
            ExistingOperation existing = inspectResolutionOperation(operation, batch);
            if (existing == ExistingOperation.MATCH) {
                TxResult.Status replayStatus = resolutionReplayStatus(operation);
                return new TxResult(replayStatus, groupId,
                        "resolution operation was already journaled with aggregate state " + replayStatus);
            }
            if (existing == ExistingOperation.CONFLICT) {
                return new TxResult(TxResult.Status.CONFLICT, groupId,
                        "operation id has different resolution contents");
            }

            List<UUID> readyCredits = new ArrayList<>();
            inTransaction(() -> {
                long timestamp = now();
                for (Resolution entry : batch) {
                    WagerHandle wager = entry.wager();
                    requireMatchingWager(wager, WAGER_OPEN, WAGER_LOCKED);
                    WagerResolution resolution = entry.resolution();
                    if (resolution instanceof WagerResolution.Loss) {
                        updateResolvedWager(wager.id(), WAGER_CLOSED, "LOSS", null, timestamp);
                        insertInternalTransaction(operation, wager, Purpose.LOSS, timestamp);
                    } else {
                        Purpose purpose;
                        Money credit;
                        if (resolution instanceof WagerResolution.Refund) {
                            purpose = Purpose.REFUND;
                            credit = wager.stake();
                        } else {
                            purpose = Purpose.PAYOUT;
                            credit = ((WagerResolution.Payout) resolution).amount();
                        }
                        updateResolvedWager(wager.id(), WAGER_PAYOUT_LOCKED, purpose.name(), credit, timestamp);
                        UUID creditId = insertReadyCredit(operation, wager.id(), wager.playerId(), purpose, credit,
                                timestamp);
                        readyCredits.add(creditId);
                    }
                }
                return null;
            });

            TxResult.Status status = TxResult.Status.DURABLE;
            for (UUID creditId : readyCredits) {
                TransactionState result = processReadyCredit(creditId);
                if (result == TransactionState.UNKNOWN) {
                    status = TxResult.Status.UNKNOWN;
                } else if (result == TransactionState.READY && status != TxResult.Status.UNKNOWN) {
                    status = TxResult.Status.READY;
                }
            }
            return new TxResult(status, groupId, "all outcomes were journaled atomically");
        } catch (ConflictSQLException exception) {
            return new TxResult(TxResult.Status.CONFLICT, groupId, exception.getMessage());
        } catch (SQLException exception) {
            failStorage("Could not resolve wager batch", exception);
            return new TxResult(TxResult.Status.STORAGE_FAILURE, groupId, exception.getMessage());
        }
    }

    @Override
    public synchronized boolean isPlayerFrozen(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (closed || !storageHealthy) {
            return true;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM transactions
                WHERE player_uuid=?
                  AND (state='UNKNOWN' OR (
                      direction='CREDIT' AND state IN ('PREPARED','CALLING','READY')
                  ))
                UNION ALL
                SELECT 1 FROM wagers WHERE player_uuid=? AND state='UNKNOWN'
                LIMIT 1
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            failStorage("Could not inspect player financial hold", exception);
            return true;
        }
    }

    @Override
    public synchronized boolean hasUnresolvedFunds() {
        return unresolvedCount() > 0L;
    }

    @Override
    public synchronized long unresolvedCount() {
        if (closed) {
            return Long.MAX_VALUE;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                SELECT COUNT(*) FROM (
                    SELECT 'w:' || id AS key FROM wagers
                    WHERE state IN ('PREPARING','OPEN','LOCKED','PAYOUT_LOCKED','SETTLING','UNKNOWN')
                    UNION ALL
                    SELECT 't:' || id AS key FROM transactions
                    WHERE wager_id IS NULL AND state IN ('PREPARED','CALLING','READY','UNKNOWN')
                )
                """)) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (SQLException exception) {
            failStorage("Could not count unresolved funds", exception);
            return Long.MAX_VALUE;
        }
    }

    @Override
    public synchronized long activeWagerCount() {
        return count("SELECT COUNT(*) FROM wagers WHERE state IN ('OPEN','LOCKED')");
    }

    @Override
    public synchronized Optional<WagerHandle> find(WagerKey key) {
        Objects.requireNonNull(key, "key");
        if (closed) {
            return Optional.empty();
        }
        try {
            return findWagerRow(key).map(WagerRow::handle);
        } catch (SQLException exception) {
            failStorage("Could not find wager", exception);
            return Optional.empty();
        }
    }

    @Override
    public synchronized List<LedgerTransaction> list(LedgerQuery query) {
        Objects.requireNonNull(query, "query");
        if (closed) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM transactions WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        if (query.playerId() != null) {
            sql.append(" AND player_uuid=?");
            parameters.add(query.playerId().toString());
        }
        if (query.state() != null) {
            sql.append(" AND state=?");
            parameters.add(query.state().name());
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        parameters.add(query.limit());
        parameters.add(query.offset());
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<LedgerTransaction> transactions = new ArrayList<>();
                while (result.next()) {
                    transactions.add(readLedgerTransaction(result));
                }
                return List.copyOf(transactions);
            }
        } catch (SQLException exception) {
            failStorage("Could not list ledger transactions", exception);
            return List.of();
        }
    }

    @Override
    public synchronized Optional<LedgerTransaction> transaction(UUID id) {
        Objects.requireNonNull(id, "id");
        if (closed) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readLedgerTransaction(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            failStorage("Could not inspect ledger transaction", exception);
            return Optional.empty();
        }
    }

    @Override
    public synchronized ReconcileResult resolveUnknown(
            UUID transactionId,
            UnknownDecision decision,
            String actor,
            String note
    ) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(decision, "decision");
        actor = requireText(actor, "actor");
        note = requireText(note, "note");
        if (!readyForWrites()) {
            return new ReconcileResult(ReconcileResult.Status.STORAGE_FAILURE, transactionId,
                    "ledger is unavailable");
        }
        try {
            Optional<TxRow> optional = findTransactionRow(transactionId);
            if (optional.isEmpty()) {
                return new ReconcileResult(ReconcileResult.Status.NOT_FOUND, transactionId,
                        "transaction does not exist");
            }
            TxRow original = optional.get();
            if (original.resolvedDecision() != null) {
                ReconcileResult.Status status = original.resolvedDecision().equals(decision.name())
                        ? ReconcileResult.Status.ALREADY_RESOLVED
                        : ReconcileResult.Status.CONFLICT;
                return new ReconcileResult(status, transactionId,
                        "transaction was already reconciled as " + original.resolvedDecision());
            }
            if (original.state() != TransactionState.UNKNOWN || original.direction() == Direction.INTERNAL) {
                return new ReconcileResult(ReconcileResult.Status.CONFLICT, transactionId,
                        "only UNKNOWN Vault transactions can be reconciled");
            }

            List<UUID> creditsToTry = new ArrayList<>(1);
            String finalActor = actor;
            String finalNote = note;
            inTransaction(() -> {
                long timestamp = now();
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE transactions
                        SET state=?, resolved_decision=?, resolved_by=?, resolution_note=?, resolved_at=?, updated_at=?
                        WHERE id=? AND state='UNKNOWN' AND resolved_decision IS NULL
                        """)) {
                    TransactionState terminal = decision == UnknownDecision.APPLIED
                            ? TransactionState.APPLIED : TransactionState.REJECTED;
                    update.setString(1, terminal.name());
                    update.setString(2, decision.name());
                    update.setString(3, finalActor);
                    update.setString(4, finalNote);
                    update.setLong(5, timestamp);
                    update.setLong(6, timestamp);
                    update.setString(7, transactionId.toString());
                    if (update.executeUpdate() != 1) {
                        throw new ConflictSQLException("transaction changed while being reconciled");
                    }
                }

                if (original.direction() == Direction.DEBIT) {
                    if (original.wagerId() == null) {
                        throw new ConflictSQLException("debit has no wager");
                    }
                    if (decision == UnknownDecision.NOT_APPLIED) {
                        updateWagerState(original.wagerId(), WAGER_CLOSED, timestamp);
                    } else {
                        WagerRow wager = requireWager(original.wagerId());
                        updateResolvedWager(wager.id(), WAGER_PAYOUT_LOCKED, Purpose.REFUND.name(),
                                wager.stake(), timestamp);
                        String operation = "reconcile-refund:" + transactionId;
                        creditsToTry.add(insertReadyCredit(operation, wager.id(), wager.playerId(),
                                Purpose.REFUND, wager.stake(), timestamp));
                    }
                } else if (original.direction() == Direction.CREDIT) {
                    if (decision == UnknownDecision.APPLIED) {
                        if (original.wagerId() != null) {
                            updateWagerState(original.wagerId(), WAGER_CLOSED, timestamp);
                        }
                    } else {
                        if (original.wagerId() != null) {
                            updateResolvedWager(
                                    original.wagerId(),
                                    WAGER_PAYOUT_LOCKED,
                                    original.purpose().name(),
                                    original.amount(),
                                    timestamp
                            );
                        }
                        String operation = "reconcile-retry:" + transactionId;
                        creditsToTry.add(insertReadyCredit(operation, original.wagerId(), original.playerId(),
                                original.purpose(), original.amount(), timestamp));
                    }
                }
                return null;
            });

            for (UUID creditId : creditsToTry) {
                processReadyCredit(creditId);
            }
            return new ReconcileResult(ReconcileResult.Status.RESOLVED, transactionId,
                    creditsToTry.isEmpty() ? "reconciliation recorded" : "reconciliation recorded; safe credit attempted");
        } catch (ConflictSQLException exception) {
            return new ReconcileResult(ReconcileResult.Status.CONFLICT, transactionId, exception.getMessage());
        } catch (SQLException exception) {
            failStorage("Could not reconcile unknown transaction", exception);
            return new ReconcileResult(ReconcileResult.Status.STORAGE_FAILURE, transactionId,
                    exception.getMessage());
        }
    }

    @Override
    public synchronized RecoveryReport recover() {
        if (!readyForWrites()) {
            return new RecoveryReport(0, 0, 0, 0, 0, 0);
        }
        int[] counts = new int[4];
        try {
            inTransaction(() -> {
                long timestamp = now();
                List<TxRow> prepared = transactionRowsByState(TransactionState.PREPARED);
                for (TxRow transaction : prepared) {
                    if (transaction.direction() == Direction.CREDIT) {
                        updateTransactionState(transaction.id(), TransactionState.READY,
                                "recovered before provider call", timestamp);
                    } else {
                        updateTransactionState(transaction.id(), TransactionState.REJECTED,
                                "recovered before provider call", timestamp);
                        if (transaction.direction() == Direction.DEBIT && transaction.wagerId() != null) {
                            updateWagerState(transaction.wagerId(), WAGER_CLOSED, timestamp);
                        }
                    }
                    counts[0]++;
                }

                for (TxRow transaction : transactionRowsByState(TransactionState.CALLING)) {
                    updateTransactionState(transaction.id(), TransactionState.UNKNOWN,
                            "process stopped while provider outcome was unknown", timestamp);
                    if (transaction.wagerId() != null) {
                        updateWagerState(transaction.wagerId(), WAGER_UNKNOWN, timestamp);
                    }
                    counts[1]++;
                }

                List<WagerRow> interrupted = wagerRowsByState(WAGER_OPEN, WAGER_LOCKED);
                for (WagerRow wager : interrupted) {
                    updateResolvedWager(wager.id(), WAGER_PAYOUT_LOCKED, Purpose.REFUND.name(),
                            wager.stake(), timestamp);
                    ensureReadyCredit("recovery-refund:" + wager.id(), wager, Purpose.REFUND,
                            wager.stake(), timestamp);
                    counts[2]++;
                }

                for (WagerRow wager : wagerRowsByState(WAGER_PAYOUT_LOCKED)) {
                    Money payout = wager.payout() == null ? wager.stake() : wager.payout();
                    Purpose purpose = parsePurpose(wager.resolutionType(), Purpose.REFUND);
                    if (!hasCreditForWager(wager.id())) {
                        ensureReadyCredit("recovery-payout:" + wager.id(), wager, purpose, payout, timestamp);
                        counts[3]++;
                    }
                }
                return null;
            });
            RetryReport retry = retryReady(1000);
            int remaining = safeInt(count("SELECT COUNT(*) FROM transactions WHERE state='READY'"));
            return new RecoveryReport(counts[0], counts[1], counts[2], counts[3], retry.applied(), remaining);
        } catch (SQLException exception) {
            failStorage("Could not recover economy ledger", exception);
            return new RecoveryReport(counts[0], counts[1], counts[2], counts[3], 0, 0);
        }
    }

    @Override
    public synchronized RetryReport retryReady(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (!readyForWrites()) {
            return new RetryReport(0, 0, 0, 0, 1);
        }
        if (!gatewayAvailable()) {
            return new RetryReport(0, 0, 0, 0, 0);
        }
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM transactions WHERE state='READY' AND direction='CREDIT' ORDER BY created_at LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(UUID.fromString(result.getString(1)));
                }
            }
        } catch (SQLException exception) {
            failStorage("Could not list retryable credits", exception);
            return new RetryReport(0, 0, 0, 0, 1);
        }

        int applied = 0;
        int rejected = 0;
        int unknown = 0;
        int storageFailures = 0;
        for (UUID id : ids) {
            try {
                TransactionState state = processReadyCredit(id);
                if (state == TransactionState.APPLIED) applied++;
                else if (state == TransactionState.READY) rejected++;
                else if (state == TransactionState.UNKNOWN) unknown++;
            } catch (SQLException exception) {
                failStorage("Could not retry pending credit " + id, exception);
                storageFailures++;
                break;
            }
        }
        return new RetryReport(ids.size(), applied, rejected, unknown, storageFailures);
    }

    @Override
    public synchronized MigrationReport migratePendingPayouts(Path yamlFile) {
        Objects.requireNonNull(yamlFile, "yamlFile");
        if (!Files.exists(yamlFile)) {
            return new MigrationReport(MigrationReport.Status.NOT_PRESENT, 0, 0, "legacy ledger does not exist");
        }
        if (!readyForWrites()) {
            return new MigrationReport(MigrationReport.Status.STORAGE_FAILURE, 0, 0, "ledger is unavailable");
        }
        try {
            byte[] bytes = Files.readAllBytes(yamlFile);
            String checksum = sha256(bytes);
            Optional<String> migratedChecksum = meta(PENDING_MIGRATION_KEY);
            if (migratedChecksum.isPresent()) {
                String detail = migratedChecksum.get().equals(checksum)
                        ? "legacy ledger was already imported"
                        : "a legacy ledger was already imported; changed file was not imported again";
                return new MigrationReport(MigrationReport.Status.ALREADY_IMPORTED, 0, 0, detail);
            }

            LegacyCredits credits = parseLegacyYaml(new String(bytes, StandardCharsets.UTF_8));
            long timestamp = now();
            inTransaction(() -> {
                for (Map.Entry<UUID, Money> entry : credits.ready().entrySet()) {
                    insertStandaloneCredit(
                            deterministicUuid("legacy-ready", checksum + ':' + entry.getKey()),
                            "legacy-ready:" + checksum + ':' + entry.getKey(), entry.getKey(),
                            Purpose.LEGACY_PENDING, entry.getValue(), TransactionState.READY,
                            "migrated from pending-payouts.yml", timestamp
                    );
                }
                for (Map.Entry<UUID, Money> entry : credits.uncertain().entrySet()) {
                    insertStandaloneCredit(
                            deterministicUuid("legacy-uncertain", checksum + ':' + entry.getKey()),
                            "legacy-uncertain:" + checksum + ':' + entry.getKey(), entry.getKey(),
                            Purpose.LEGACY_PENDING, entry.getValue(), TransactionState.UNKNOWN,
                            "migrated uncertain Vault outcome; manual reconciliation required", timestamp
                    );
                }
                putMeta(PENDING_MIGRATION_KEY, checksum);
                return null;
            });

            String renameDetail = "";
            Path backup = yamlFile.resolveSibling(yamlFile.getFileName() + ".migrated-"
                    + Instant.ofEpochMilli(timestamp).toString().replace(':', '-') + ".bak");
            try {
                Files.move(yamlFile, backup, StandardCopyOption.ATOMIC_MOVE);
                renameDetail = "; backup=" + backup.getFileName();
            } catch (IOException first) {
                try {
                    Files.move(yamlFile, backup);
                    renameDetail = "; backup=" + backup.getFileName();
                } catch (IOException second) {
                    logger.log(Level.WARNING,
                            "Legacy pending ledger was imported, but could not be renamed. The checksum prevents re-import.",
                            second);
                    renameDetail = "; source rename failed but checksum prevents duplicate import";
                }
            }
            return new MigrationReport(MigrationReport.Status.IMPORTED, credits.ready().size(),
                    credits.uncertain().size(), "legacy ledger imported" + renameDetail);
        } catch (IllegalArgumentException exception) {
            logger.log(Level.SEVERE, "Legacy pending payout migration was rejected", exception);
            return new MigrationReport(MigrationReport.Status.INVALID, 0, 0, exception.getMessage());
        } catch (SQLException | IOException exception) {
            failStorage("Could not migrate legacy pending payout ledger", exception);
            return new MigrationReport(MigrationReport.Status.STORAGE_FAILURE, 0, 0, exception.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Could not checkpoint economy ledger WAL", exception);
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Could not close economy ledger", exception);
        }
    }

    private void configureConnection() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite refused WAL journal mode");
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("SQLite quick_check failed");
                }
            }
        }
    }

    private void initializeSchema() throws SQLException {
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS meta (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS wagers (
                            id TEXT PRIMARY KEY,
                            wager_key TEXT NOT NULL UNIQUE,
                            game TEXT NOT NULL,
                            machine_id TEXT NOT NULL,
                            round_id TEXT NOT NULL,
                            player_uuid TEXT NOT NULL,
                            nonce TEXT NOT NULL,
                            stake TEXT NOT NULL CHECK(length(stake) > 0),
                            state TEXT NOT NULL CHECK(state IN (
                                'PREPARING','OPEN','LOCKED','PAYOUT_LOCKED','SETTLING','CLOSED','UNKNOWN'
                            )),
                            resolution_type TEXT CHECK(resolution_type IS NULL OR resolution_type IN (
                                'LOSS','REFUND','PAYOUT'
                            )),
                            payout TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS transactions (
                            id TEXT PRIMARY KEY,
                            operation_id TEXT NOT NULL,
                            wager_id TEXT REFERENCES wagers(id) ON DELETE RESTRICT,
                            player_uuid TEXT NOT NULL,
                            direction TEXT NOT NULL CHECK(direction IN ('DEBIT','CREDIT','INTERNAL')),
                            purpose TEXT NOT NULL CHECK(purpose IN (
                                'STAKE','LOCK','LOSS','REFUND','PAYOUT','LEGACY_PENDING'
                            )),
                            amount TEXT NOT NULL CHECK(length(amount) > 0),
                            state TEXT NOT NULL CHECK(state IN (
                                'PREPARED','CALLING','APPLIED','READY','REJECTED','UNKNOWN'
                            )),
                            attempt_count INTEGER NOT NULL DEFAULT 0,
                            detail TEXT NOT NULL DEFAULT '',
                            resolved_decision TEXT CHECK(resolved_decision IS NULL OR resolved_decision IN (
                                'APPLIED','NOT_APPLIED'
                            )),
                            resolved_by TEXT,
                            resolution_note TEXT,
                            resolved_at INTEGER,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            UNIQUE(operation_id, wager_id, purpose)
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_wagers_player_state ON wagers(player_uuid, state)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_player_state ON transactions(player_uuid, state)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_ready ON transactions(state, direction, created_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_operation ON transactions(operation_id)");
            }
            Optional<String> current = meta("schema.version");
            if (current.isEmpty()) {
                putMeta("schema.version", Integer.toString(SCHEMA_VERSION));
            } else if (Integer.parseInt(current.get()) != SCHEMA_VERSION) {
                throw new SQLException("Unsupported economy ledger schema version " + current.get());
            }
            return null;
        });
    }

    private void transitionToCalling(
            UUID transactionId,
            TransactionState expected,
            UUID creditWagerId
    ) throws SQLException {
        long timestamp = now();
        TransactionState actual = expected;
        if (expected == TransactionState.READY) {
            inTransaction(() -> {
                try (PreparedStatement prepare = connection.prepareStatement("""
                        UPDATE transactions SET state='PREPARED', attempt_count=attempt_count+1, updated_at=?
                        WHERE id=? AND state='READY'
                        """)) {
                    prepare.setLong(1, timestamp);
                    prepare.setString(2, transactionId.toString());
                    if (prepare.executeUpdate() != 1) {
                        throw new ConflictSQLException("credit is no longer READY");
                    }
                }
                return null;
            });
            actual = TransactionState.PREPARED;
        }
        TransactionState durableExpected = actual;
        inTransaction(() -> {
            try (PreparedStatement calling = connection.prepareStatement("""
                    UPDATE transactions SET state='CALLING', attempt_count=attempt_count+?, updated_at=?
                    WHERE id=? AND state=?
                    """)) {
                calling.setInt(1, expected == TransactionState.READY ? 0 : 1);
                calling.setLong(2, timestamp);
                calling.setString(3, transactionId.toString());
                calling.setString(4, durableExpected.name());
                if (calling.executeUpdate() != 1) {
                    throw new ConflictSQLException("transaction is no longer " + durableExpected);
                }
            }
            if (creditWagerId != null) {
                transitionWagerState(
                        creditWagerId,
                        WAGER_SETTLING,
                        timestamp,
                        WAGER_PAYOUT_LOCKED
                );
            }
            return null;
        });
    }

    private void finalizeDebit(
            UUID transactionId,
            UUID wagerId,
            TransactionState transactionState,
            String wagerState,
            String detail
    ) throws SQLException {
        inTransaction(() -> {
            long timestamp = now();
            try (PreparedStatement transaction = connection.prepareStatement("""
                    UPDATE transactions SET state=?, detail=?, updated_at=? WHERE id=? AND state='CALLING'
                    """);
                 PreparedStatement wager = connection.prepareStatement("""
                    UPDATE wagers SET state=?, updated_at=? WHERE id=? AND state='PREPARING'
                    """)) {
                transaction.setString(1, transactionState.name());
                transaction.setString(2, safeDetail(detail));
                transaction.setLong(3, timestamp);
                transaction.setString(4, transactionId.toString());
                if (transaction.executeUpdate() != 1) {
                    throw new ConflictSQLException("debit transaction receipt was already finalized");
                }
                wager.setString(1, wagerState);
                wager.setLong(2, timestamp);
                wager.setString(3, wagerId.toString());
                if (wager.executeUpdate() != 1) {
                    throw new ConflictSQLException("wager debit state was already finalized");
                }
            }
            return null;
        });
    }

    private TransactionState processReadyCredit(UUID transactionId) throws SQLException {
        Optional<TxRow> existing = findTransactionRow(transactionId);
        if (existing.isEmpty()) {
            throw new ConflictSQLException("credit transaction does not exist: " + transactionId);
        }
        TxRow transaction = existing.get();
        if (transaction.state() == TransactionState.APPLIED
                || transaction.state() == TransactionState.UNKNOWN) {
            return transaction.state();
        }
        if (transaction.direction() != Direction.CREDIT || transaction.state() != TransactionState.READY) {
            throw new ConflictSQLException("transaction is not a retryable credit: " + transactionId);
        }

        // Do not cross READY -> CALLING until the provider owner is enabled.
        // A provider becoming unavailable after this check remains genuinely
        // ambiguous and is intentionally recorded as UNKNOWN by callGateway.
        if (!gatewayAvailable()) {
            return TransactionState.READY;
        }

        // READY -> PREPARED and PREPARED -> CALLING are separate durable commits.
        transitionToCalling(transactionId, TransactionState.READY, transaction.wagerId());
        EconomyGateway.GatewayResult provider = callGateway(true, transaction.playerId(), transaction.amount());
        inTransaction(() -> {
            long timestamp = now();
            if (provider.status() == EconomyGateway.GatewayResult.Status.APPLIED) {
                updateTransactionState(transactionId, TransactionState.APPLIED, provider.detail(), timestamp,
                        TransactionState.CALLING);
                if (transaction.wagerId() != null) {
                    transitionWagerState(transaction.wagerId(), WAGER_CLOSED, timestamp, WAGER_SETTLING);
                }
            } else if (provider.status() == EconomyGateway.GatewayResult.Status.REJECTED) {
                updateTransactionState(transactionId, TransactionState.READY, provider.detail(), timestamp,
                        TransactionState.CALLING);
                if (transaction.wagerId() != null) {
                    transitionWagerState(transaction.wagerId(), WAGER_PAYOUT_LOCKED, timestamp, WAGER_SETTLING);
                }
            } else {
                updateTransactionState(transactionId, TransactionState.UNKNOWN, provider.detail(), timestamp,
                        TransactionState.CALLING);
                if (transaction.wagerId() != null) {
                    transitionWagerState(transaction.wagerId(), WAGER_UNKNOWN, timestamp, WAGER_SETTLING);
                }
            }
            return null;
        });
        return switch (provider.status()) {
            case APPLIED -> TransactionState.APPLIED;
            case REJECTED -> TransactionState.READY;
            case UNKNOWN -> TransactionState.UNKNOWN;
        };
    }

    private EconomyGateway.GatewayResult callGateway(boolean deposit, UUID playerId, Money amount) {
        try {
            EconomyGateway.GatewayResult result = deposit
                    ? gateway.deposit(playerId, amount)
                    : gateway.withdraw(playerId, amount);
            return result == null ? EconomyGateway.GatewayResult.unknown("economy gateway returned null") : result;
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Economy provider threw with an unknown transaction outcome", exception);
            return EconomyGateway.GatewayResult.unknown(exception.toString());
        }
    }

    private boolean gatewayAvailable() {
        try {
            return gateway.isAvailable();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not inspect economy provider readiness", exception);
            return false;
        }
    }

    private UUID insertReadyCredit(
            String operation,
            UUID wagerId,
            UUID playerId,
            Purpose purpose,
            Money amount,
            long timestamp
    ) throws SQLException {
        UUID id = deterministicUuid("credit", operation + ':' + (wagerId == null ? playerId : wagerId)
                + ':' + purpose + ':' + amount.decimal().toPlainString());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    id, operation_id, wager_id, player_uuid, direction, purpose,
                    amount, state, attempt_count, detail, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'CREDIT', ?, ?, 'READY', 0, '', ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, operation);
            if (wagerId == null) statement.setNull(3, java.sql.Types.VARCHAR);
            else statement.setString(3, wagerId.toString());
            statement.setString(4, playerId.toString());
            statement.setString(5, purpose.name());
            setMoney(statement, 6, amount);
            statement.setLong(7, timestamp);
            statement.setLong(8, timestamp);
            statement.executeUpdate();
        }
        return id;
    }

    private void ensureReadyCredit(
            String operation,
            WagerRow wager,
            Purpose purpose,
            Money amount,
            long timestamp
    ) throws SQLException {
        try {
            insertReadyCredit(operation, wager.id(), wager.playerId(), purpose, amount, timestamp);
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
        }
    }

    private void insertStandaloneCredit(
            UUID id,
            String operation,
            UUID playerId,
            Purpose purpose,
            Money amount,
            TransactionState state,
            String detail,
            long timestamp
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    id, operation_id, wager_id, player_uuid, direction, purpose,
                    amount, state, attempt_count, detail, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 'CREDIT', ?, ?, ?, 0, ?, ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, operation);
            statement.setString(3, playerId.toString());
            statement.setString(4, purpose.name());
            setMoney(statement, 5, amount);
            statement.setString(6, state.name());
            statement.setString(7, safeDetail(detail));
            statement.setLong(8, timestamp);
            statement.setLong(9, timestamp);
            statement.executeUpdate();
        }
    }

    private void insertInternalTransaction(
            String operation,
            WagerHandle wager,
            Purpose purpose,
            long timestamp
    ) throws SQLException {
        UUID id = operationTransactionId(operation, wager.id(), purpose);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    id, operation_id, wager_id, player_uuid, direction, purpose,
                    amount, state, attempt_count, detail, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'INTERNAL', ?, ?, 'APPLIED', 0, '', ?, ?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, operation);
            statement.setString(3, wager.id().toString());
            statement.setString(4, wager.playerId().toString());
            statement.setString(5, purpose.name());
            setMoney(statement, 6, wager.stake());
            statement.setLong(7, timestamp);
            statement.setLong(8, timestamp);
            statement.executeUpdate();
        }
    }

    private void updateResolvedWager(
            UUID wagerId,
            String state,
            String resolution,
            Money payout,
            long timestamp
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wagers
                SET state=?, resolution_type=?, payout=?, updated_at=?
                WHERE id=? AND state IN ('OPEN','LOCKED','UNKNOWN')
                """)) {
            statement.setString(1, state);
            statement.setString(2, resolution);
            if (payout == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                setMoney(statement, 3, payout);
            }
            statement.setLong(4, timestamp);
            statement.setString(5, wagerId.toString());
            if (statement.executeUpdate() != 1) {
                throw new ConflictSQLException("wager cannot be resolved from its current state: " + wagerId);
            }
        }
    }

    private void updateWagerState(UUID wagerId, String state, long timestamp) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE wagers SET state=?, updated_at=? WHERE id=?")) {
            statement.setString(1, state);
            statement.setLong(2, timestamp);
            statement.setString(3, wagerId.toString());
            if (statement.executeUpdate() != 1) {
                throw new ConflictSQLException("wager does not exist: " + wagerId);
            }
        }
    }

    private void transitionWagerState(
            UUID wagerId,
            String state,
            long timestamp,
            String expected
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE wagers SET state=?, updated_at=? WHERE id=? AND state=?")) {
            statement.setString(1, state);
            statement.setLong(2, timestamp);
            statement.setString(3, wagerId.toString());
            statement.setString(4, expected);
            if (statement.executeUpdate() != 1) {
                throw new ConflictSQLException(
                        "wager state changed unexpectedly: " + wagerId + " (expected " + expected + ')'
                );
            }
        }
    }

    private void updateTransactionState(
            UUID id,
            TransactionState state,
            String detail,
            long timestamp,
            TransactionState expected
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions SET state=?, detail=?, updated_at=? WHERE id=? AND state=?
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, safeDetail(detail));
            statement.setLong(3, timestamp);
            statement.setString(4, id.toString());
            statement.setString(5, expected.name());
            if (statement.executeUpdate() != 1) {
                throw new ConflictSQLException("transaction state changed unexpectedly: " + id);
            }
        }
    }

    private void updateTransactionState(
            UUID id,
            TransactionState state,
            String detail,
            long timestamp
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET state=?, detail=?, updated_at=? WHERE id=?")) {
            statement.setString(1, state.name());
            statement.setString(2, safeDetail(detail));
            statement.setLong(3, timestamp);
            statement.setString(4, id.toString());
            if (statement.executeUpdate() != 1) {
                throw new ConflictSQLException("transaction does not exist: " + id);
            }
        }
    }

    private WagerRow requireMatchingWager(WagerHandle handle, String... allowedStates) throws SQLException {
        WagerRow row = requireWager(handle.id());
        if (!row.key().equals(handle.key()) || !row.playerId().equals(handle.playerId())
                || row.stake().compareTo(handle.stake()) != 0) {
            throw new ConflictSQLException("wager handle does not match durable ledger row: " + handle.id());
        }
        Set<String> allowed = Set.of(allowedStates);
        if (!allowed.contains(row.state())) {
            throw new ConflictSQLException("wager " + handle.id() + " is " + row.state());
        }
        return row;
    }

    private WagerRow requireWager(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM wagers WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictSQLException("wager does not exist: " + id);
                }
                return readWager(result);
            }
        }
    }

    private Optional<WagerRow> findWagerRow(WagerKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM wagers WHERE wager_key=?")) {
            statement.setString(1, key.canonical());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readWager(result)) : Optional.empty();
            }
        }
    }

    private Optional<TxRow> findTransactionRow(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTransaction(result)) : Optional.empty();
            }
        }
    }

    private List<TxRow> transactionRowsByState(TransactionState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE state=? ORDER BY created_at")) {
            statement.setString(1, state.name());
            try (ResultSet result = statement.executeQuery()) {
                List<TxRow> rows = new ArrayList<>();
                while (result.next()) rows.add(readTransaction(result));
                return rows;
            }
        }
    }

    private List<WagerRow> wagerRowsByState(String... states) throws SQLException {
        if (states.length == 0) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(states.length, "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM wagers WHERE state IN (" + placeholders + ") ORDER BY created_at")) {
            for (int i = 0; i < states.length; i++) statement.setString(i + 1, states[i]);
            try (ResultSet result = statement.executeQuery()) {
                List<WagerRow> rows = new ArrayList<>();
                while (result.next()) rows.add(readWager(result));
                return rows;
            }
        }
    }

    private boolean hasCreditForWager(UUID wagerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM transactions
                WHERE wager_id=? AND direction='CREDIT'
                  AND state IN ('PREPARED','CALLING','APPLIED','READY','UNKNOWN')
                LIMIT 1
                """)) {
            statement.setString(1, wagerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private ExistingOperation inspectExistingOperation(
            String operation,
            List<WagerHandle> wagers,
            Map<UUID, Money> expectedAmounts,
            Purpose purpose
    ) throws SQLException {
        Map<UUID, TxRow> rows = transactionsForOperation(operation);
        if (rows.isEmpty()) return ExistingOperation.NONE;
        if (rows.size() != wagers.size()) return ExistingOperation.CONFLICT;
        for (WagerHandle wager : wagers) {
            TxRow row = rows.get(wager.id());
            Money expected = expectedAmounts == null ? wager.stake() : expectedAmounts.get(wager.id());
            if (row == null || row.purpose() != purpose || row.amount().compareTo(expected) != 0) {
                return ExistingOperation.CONFLICT;
            }
        }
        return ExistingOperation.MATCH;
    }

    private ExistingOperation inspectResolutionOperation(String operation, List<Resolution> resolutions)
            throws SQLException {
        Map<UUID, TxRow> rows = transactionsForOperation(operation);
        if (rows.isEmpty()) return ExistingOperation.NONE;
        if (rows.size() != resolutions.size()) return ExistingOperation.CONFLICT;
        for (Resolution resolution : resolutions) {
            TxRow row = rows.get(resolution.wager().id());
            Purpose expectedPurpose = resolutionPurpose(resolution.resolution());
            Money expectedAmount = resolutionAmount(resolution);
            if (row == null || row.purpose() != expectedPurpose
                    || row.amount().compareTo(expectedAmount) != 0) {
                return ExistingOperation.CONFLICT;
            }
        }
        return ExistingOperation.MATCH;
    }

    private TxResult.Status resolutionReplayStatus(String operation) throws SQLException {
        TxResult.Status aggregate = TxResult.Status.ALREADY_APPLIED;
        for (TxRow row : transactionsForOperation(operation).values()) {
            if (row.state() == TransactionState.UNKNOWN
                    || row.state() == TransactionState.PREPARED
                    || row.state() == TransactionState.CALLING) {
                return TxResult.Status.UNKNOWN;
            }
            if (row.state() == TransactionState.READY) {
                aggregate = TxResult.Status.READY;
            }
        }
        return aggregate;
    }

    private Map<UUID, TxRow> transactionsForOperation(String operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE operation_id=?")) {
            statement.setString(1, operation);
            try (ResultSet result = statement.executeQuery()) {
                Map<UUID, TxRow> rows = new HashMap<>();
                while (result.next()) {
                    TxRow row = readTransaction(result);
                    if (row.wagerId() == null || rows.put(row.wagerId(), row) != null) {
                        throw new ConflictSQLException("operation id is already used by an incompatible transaction");
                    }
                }
                return rows;
            }
        }
    }

    private static Purpose resolutionPurpose(WagerResolution resolution) {
        if (resolution instanceof WagerResolution.Loss) return Purpose.LOSS;
        if (resolution instanceof WagerResolution.Refund) return Purpose.REFUND;
        return Purpose.PAYOUT;
    }

    private static Money resolutionAmount(Resolution resolution) {
        if (resolution.resolution() instanceof WagerResolution.Payout payout) return payout.amount();
        return resolution.wager().stake();
    }

    private WagerRow readWager(ResultSet result) throws SQLException {
        UUID playerId = UUID.fromString(result.getString("player_uuid"));
        WagerKey key = new WagerKey(
                result.getString("game"),
                result.getString("machine_id"),
                result.getString("round_id"),
                playerId,
                result.getString("nonce")
        );
        String payoutText = result.getString("payout");
        Money payout = payoutText == null ? null : parseMoney(payoutText, "wagers.payout");
        return new WagerRow(
                UUID.fromString(result.getString("id")),
                key,
                playerId,
                parseMoney(result.getString("stake"), "wagers.stake"),
                result.getString("state"),
                result.getString("resolution_type"),
                payout
        );
    }

    private TxRow readTransaction(ResultSet result) throws SQLException {
        String wagerId = result.getString("wager_id");
        return new TxRow(
                UUID.fromString(result.getString("id")),
                result.getString("operation_id"),
                wagerId == null ? null : UUID.fromString(wagerId),
                UUID.fromString(result.getString("player_uuid")),
                Direction.valueOf(result.getString("direction")),
                Purpose.valueOf(result.getString("purpose")),
                parseMoney(result.getString("amount"), "transactions.amount"),
                TransactionState.valueOf(result.getString("state")),
                result.getInt("attempt_count"),
                result.getString("detail"),
                result.getString("resolved_decision"),
                result.getLong("created_at"),
                result.getLong("updated_at")
        );
    }

    private LedgerTransaction readLedgerTransaction(ResultSet result) throws SQLException {
        TxRow row = readTransaction(result);
        return new LedgerTransaction(row.id(), row.operationId(), row.wagerId(), row.playerId(),
                row.direction(), row.purpose(), row.amount(), row.state(), row.attemptCount(),
                row.detail(), row.createdAt(), row.updatedAt());
    }

    private LegacyCredits parseLegacyYaml(String yamlText) {
        Object loaded = new Yaml().load(yamlText);
        if (loaded == null) return new LegacyCredits(Map.of(), Map.of());
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("pending-payouts.yml root must be a mapping");
        }
        Object rawCredits = root.get("credits");
        if (rawCredits == null) return new LegacyCredits(Map.of(), Map.of());
        if (!(rawCredits instanceof Map<?, ?> creditMap)) {
            throw new IllegalArgumentException("credits must be a mapping");
        }
        Map<UUID, Money> ready = new LinkedHashMap<>();
        Map<UUID, Money> uncertain = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : creditMap.entrySet()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(String.valueOf(entry.getKey()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid player UUID in legacy ledger: " + entry.getKey(), exception);
            }
            Object account = entry.getValue();
            if (account instanceof Map<?, ?> amounts) {
                addLegacyAmount(ready, playerId, amounts.get("ready"), "ready");
                addLegacyAmount(uncertain, playerId, amounts.get("uncertain"), "uncertain");
            } else {
                addLegacyAmount(ready, playerId, account, "legacy ready");
            }
        }
        return new LegacyCredits(Map.copyOf(ready), Map.copyOf(uncertain));
    }

    private void addLegacyAmount(Map<UUID, Money> target, UUID playerId, Object raw, String field) {
        if (raw == null) return;
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + field + " amount for " + playerId + ": " + raw,
                    exception);
        }
        if (decimal.signum() == 0) return;
        Money money = Money.of(decimal);
        target.merge(playerId, money, Money::add);
    }

    private Optional<String> meta(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO meta(key, value) VALUES(?, ?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private long count(String sql) {
        if (closed) return Long.MAX_VALUE;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (SQLException exception) {
            failStorage("Could not query economy ledger", exception);
            return Long.MAX_VALUE;
        }
    }

    private <T> T inTransaction(SqlSupplier<T> action) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = action.get();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                exception.addSuppressed(rollback);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean readyForWrites() {
        return !closed && storageHealthy;
    }

    private void failStorage(String message, Exception exception) {
        storageHealthy = false;
        logger.log(Level.SEVERE, message + "; new wagers are blocked until the ledger is reopened", exception);
    }

    private static void setMoney(PreparedStatement statement, int index, Money money) throws SQLException {
        statement.setString(index, money.decimal().toPlainString());
    }

    private static Money parseMoney(String value, String column) throws SQLException {
        try {
            return Money.of(new BigDecimal(value));
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid canonical money in " + column + ": " + value, exception);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String requireOperation(String operationId) {
        return requireText(operationId, "operationId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }

    private static String safeDetail(String detail) {
        if (detail == null) return "";
        return detail.length() <= 2000 ? detail : detail.substring(0, 2000);
    }

    private static UUID deterministicUuid(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + '\0' + value).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID stakeTransactionId(UUID wagerId) {
        return deterministicUuid("stake", wagerId.toString());
    }

    private static UUID operationTransactionId(String operation, UUID wagerId, Purpose purpose) {
        return deterministicUuid("operation", operation + ':' + wagerId + ':' + purpose);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) builder.append(String.format(Locale.ROOT, "%02x", value));
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM has no SHA-256 provider", exception);
        }
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static Purpose parsePurpose(String raw, Purpose fallback) {
        if (raw == null) return fallback;
        try {
            return Purpose.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        return exception.getErrorCode() == 19 || (exception.getMessage() != null
                && exception.getMessage().toLowerCase(Locale.ROOT).contains("constraint"));
    }

    private static List<WagerHandle> uniqueWagers(Collection<WagerHandle> wagers) {
        Objects.requireNonNull(wagers, "wagers");
        List<WagerHandle> result = new ArrayList<>(wagers.size());
        Set<UUID> ids = new HashSet<>();
        for (WagerHandle wager : wagers) {
            Objects.requireNonNull(wager, "wager");
            if (!ids.add(wager.id())) throw new IllegalArgumentException("duplicate wager " + wager.id());
            result.add(wager);
        }
        return List.copyOf(result);
    }

    private static List<Resolution> uniqueResolutions(Collection<Resolution> resolutions) {
        Objects.requireNonNull(resolutions, "resolutions");
        List<Resolution> result = new ArrayList<>(resolutions.size());
        Set<UUID> ids = new HashSet<>();
        for (Resolution resolution : resolutions) {
            Objects.requireNonNull(resolution, "resolution");
            if (!ids.add(resolution.wager().id())) {
                throw new IllegalArgumentException("duplicate wager " + resolution.wager().id());
            }
            result.add(resolution);
        }
        return List.copyOf(result);
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private enum ExistingOperation { NONE, MATCH, CONFLICT }

    private record WagerRow(
            UUID id,
            WagerKey key,
            UUID playerId,
            Money stake,
            String state,
            String resolutionType,
            Money payout
    ) {
        WagerHandle handle() {
            return new WagerHandle(id, key, playerId, stake);
        }
    }

    private record TxRow(
            UUID id,
            String operationId,
            UUID wagerId,
            UUID playerId,
            Direction direction,
            Purpose purpose,
            Money amount,
            TransactionState state,
            int attemptCount,
            String detail,
            String resolvedDecision,
            long createdAt,
            long updatedAt
    ) {}

    private record LegacyCredits(Map<UUID, Money> ready, Map<UUID, Money> uncertain) {}

    private static final class ConflictSQLException extends SQLException {
        ConflictSQLException(String message) {
            super(message);
        }
    }
}
