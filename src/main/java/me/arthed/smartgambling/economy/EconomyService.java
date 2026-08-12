package me.arthed.smartgambling.economy;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface EconomyService extends AutoCloseable {
    PlaceResult place(WagerKey key, Money stake);

    TxResult lockAll(String operationId, Collection<WagerHandle> wagers);

    TxResult resolve(String operationId, WagerHandle wager, WagerResolution resolution);

    TxResult resolveAll(String operationId, Collection<Resolution> resolutions);

    boolean isPlayerFrozen(UUID playerId);

    boolean hasUnresolvedFunds();

    long unresolvedCount();

    long activeWagerCount();

    Optional<WagerHandle> find(WagerKey key);

    List<LedgerTransaction> list(LedgerQuery query);

    Optional<LedgerTransaction> transaction(UUID id);

    ReconcileResult resolveUnknown(
            UUID transactionId,
            UnknownDecision decision,
            String actor,
            String note
    );

    RecoveryReport recover();

    RetryReport retryReady(int limit);

    MigrationReport migratePendingPayouts(Path yaml);

    @Override
    void close();

    record Resolution(WagerHandle wager, WagerResolution resolution) {
        public Resolution {
            Objects.requireNonNull(wager, "wager");
            Objects.requireNonNull(resolution, "resolution");
        }
    }

    record LedgerQuery(UUID playerId, TransactionState state, int limit, int offset) {
        public LedgerQuery {
            if (limit < 1 || limit > 500 || offset < 0) {
                throw new IllegalArgumentException("limit must be 1..500 and offset must be non-negative");
            }
        }

        public static LedgerQuery recent(int limit) {
            return new LedgerQuery(null, null, limit, 0);
        }
    }

    record LedgerTransaction(
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
            long createdAt,
            long updatedAt
    ) {}

    enum Direction { DEBIT, CREDIT, INTERNAL }

    enum Purpose { STAKE, LOCK, LOSS, REFUND, PAYOUT, LEGACY_PENDING }

    enum TransactionState { PREPARED, CALLING, APPLIED, READY, REJECTED, UNKNOWN }

    enum UnknownDecision { APPLIED, NOT_APPLIED }

    record ReconcileResult(Status status, UUID transactionId, String detail) {
        public enum Status { RESOLVED, ALREADY_RESOLVED, NOT_FOUND, CONFLICT, STORAGE_FAILURE }
    }

    record RecoveryReport(
            int preparedCancelled,
            int callingMadeUnknown,
            int wagersQueuedForRefund,
            int lockedPayoutsRestored,
            int readyPaid,
            int readyRemaining
    ) {}

    record RetryReport(int attempted, int applied, int rejected, int unknown, int storageFailures) {}

    record MigrationReport(Status status, int readyImported, int uncertainImported, String detail) {
        public enum Status { NOT_PRESENT, IMPORTED, ALREADY_IMPORTED, INVALID, STORAGE_FAILURE }
    }
}
