package me.arthed.smartgambling.games.slots.testing;

import me.arthed.smartgambling.utils.MachineTypeIds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-memory, one-shot forced slot results used by the explicitly enabled test mode.
 *
 * <p>All compound operations are synchronized so replacing, claiming and clearing a
 * directive are atomic even if a command is issued off the server thread. Returned
 * directives are immutable snapshots and never expose the registry's map.</p>
 */
public final class ForcedSlotResultRegistry {
    private static final Consumer<Directive> NO_OP_EXPIRY_OBSERVER = directive -> { };
    private static final Comparator<Directive> DIRECTIVE_ORDER = Comparator
            .comparing(Directive::machineTypeId)
            .thenComparing(Directive::createdAt)
            .thenComparing(Directive::directiveId);

    private final Clock clock;
    private final Consumer<Directive> expiryObserver;
    private final Map<Key, Directive> directives = new HashMap<>();

    public ForcedSlotResultRegistry() {
        this(Clock.systemUTC(), NO_OP_EXPIRY_OBSERVER);
    }

    public ForcedSlotResultRegistry(Clock clock) {
        this(clock, NO_OP_EXPIRY_OBSERVER);
    }

    public ForcedSlotResultRegistry(Clock clock, Consumer<Directive> expiryObserver) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expiryObserver = Objects.requireNonNull(expiryObserver, "expiryObserver");
    }

    /**
     * Queues a directive, atomically replacing a still-live directive for the same
     * player and normalized machine type.
     */
    public synchronized QueueResult queue(
            UUID playerId,
            String machineTypeId,
            UUID issuerId,
            String issuerName,
            List<String> symbolIds,
            Duration timeToLive
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }

        Key key = new Key(playerId, machineTypeId);
        Instant now = clock.instant();
        Directive previous = liveDirective(key, now);
        Directive queued = new Directive(
                UUID.randomUUID(),
                playerId,
                key.machineTypeId(),
                Optional.ofNullable(issuerId),
                optionalNonBlank(issuerName),
                symbolIds,
                now,
                now.plus(timeToLive)
        );
        directives.put(key, queued);
        return new QueueResult(queued, Optional.ofNullable(previous));
    }

    /** Returns the live directive without consuming it. */
    public synchronized Optional<Directive> peek(UUID playerId, String machineTypeId) {
        Key key = new Key(playerId, machineTypeId);
        return Optional.ofNullable(liveDirective(key, clock.instant()));
    }

    /** Returns a stable, machine-type-sorted snapshot of one player's live directives. */
    public synchronized List<Directive> list(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        List<Directive> result = new ArrayList<>();
        Iterator<Map.Entry<Key, Directive>> iterator = directives.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Directive> entry = iterator.next();
            if (entry.getValue().isExpiredAt(now)) {
                iterator.remove();
                notifyExpired(entry.getValue());
            } else if (entry.getKey().playerId().equals(playerId)) {
                result.add(entry.getValue());
            }
        }
        result.sort(DIRECTIVE_ORDER);
        return List.copyOf(result);
    }

    /**
     * Atomically consumes and returns a live directive for exactly this player and
     * machine type. A missing, mismatched or expired directive is not claimed.
     */
    public synchronized Optional<Directive> claim(UUID playerId, String machineTypeId) {
        Key key = new Key(playerId, machineTypeId);
        Directive directive = liveDirective(key, clock.instant());
        if (directive == null) {
            return Optional.empty();
        }
        directives.remove(key);
        return Optional.of(directive);
    }

    /** Clears one live directive without affecting the player's other machine types. */
    public synchronized Optional<Directive> clear(UUID playerId, String machineTypeId) {
        Key key = new Key(playerId, machineTypeId);
        Directive directive = liveDirective(key, clock.instant());
        if (directive == null) {
            return Optional.empty();
        }
        directives.remove(key);
        return Optional.of(directive);
    }

    /** Clears and returns all live directives belonging to one player. */
    public synchronized List<Directive> clearAll(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        List<Directive> removed = new ArrayList<>();
        Iterator<Map.Entry<Key, Directive>> iterator = directives.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Directive> entry = iterator.next();
            if (entry.getValue().isExpiredAt(now)) {
                iterator.remove();
                notifyExpired(entry.getValue());
            } else if (entry.getKey().playerId().equals(playerId)) {
                iterator.remove();
                removed.add(entry.getValue());
            }
        }
        removed.sort(DIRECTIVE_ORDER);
        return List.copyOf(removed);
    }

    /** Clears and returns every live directive, for reload/disable shutdown paths. */
    public synchronized List<Directive> clearAll() {
        Instant now = clock.instant();
        List<Directive> removed = new ArrayList<>();
        Iterator<Directive> iterator = directives.values().iterator();
        while (iterator.hasNext()) {
            Directive directive = iterator.next();
            iterator.remove();
            if (directive.isExpiredAt(now)) {
                notifyExpired(directive);
            } else {
                removed.add(directive);
            }
        }
        removed.sort(DIRECTIVE_ORDER);
        return List.copyOf(removed);
    }

    /** Removes expired entries and returns them so callers can produce an audit log. */
    public synchronized List<Directive> purgeExpired() {
        Instant now = clock.instant();
        List<Directive> expired = new ArrayList<>();
        Iterator<Directive> iterator = directives.values().iterator();
        while (iterator.hasNext()) {
            Directive directive = iterator.next();
            if (directive.isExpiredAt(now)) {
                iterator.remove();
                notifyExpired(directive);
                expired.add(directive);
            }
        }
        expired.sort(DIRECTIVE_ORDER);
        return List.copyOf(expired);
    }

    /** Number of live directives currently held. */
    public synchronized int size() {
        purgeExpired();
        return directives.size();
    }

    private Directive liveDirective(Key key, Instant now) {
        Directive directive = directives.get(key);
        if (directive != null && directive.isExpiredAt(now)) {
            directives.remove(key);
            notifyExpired(directive);
            return null;
        }
        return directive;
    }

    private void notifyExpired(Directive directive) {
        try {
            expiryObserver.accept(directive);
        } catch (RuntimeException ignored) {
            // Expiry auditing is best-effort and must never break one-shot
            // queue, claim, clear or lifecycle cleanup semantics.
        }
    }

    private static Optional<String> optionalNonBlank(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    private static List<String> immutableSymbolIds(List<String> symbolIds) {
        Objects.requireNonNull(symbolIds, "symbolIds");
        if (symbolIds.isEmpty()) {
            throw new IllegalArgumentException("symbolIds cannot be empty");
        }
        List<String> copy = new ArrayList<>(symbolIds.size());
        for (String symbolId : symbolIds) {
            Objects.requireNonNull(symbolId, "symbolIds cannot contain null");
            String trimmed = symbolId.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("symbolIds cannot contain blank values");
            }
            copy.add(trimmed);
        }
        return List.copyOf(copy);
    }

    private record Key(UUID playerId, String machineTypeId) {
        private Key {
            Objects.requireNonNull(playerId, "playerId");
            machineTypeId = MachineTypeIds.normalize(machineTypeId);
        }
    }

    /** Immutable snapshot of one pending test directive. */
    public record Directive(
            UUID directiveId,
            UUID playerId,
            String machineTypeId,
            Optional<UUID> issuerId,
            Optional<String> issuerName,
            List<String> symbolIds,
            Instant createdAt,
            Instant expiresAt
    ) {
        public Directive {
            Objects.requireNonNull(directiveId, "directiveId");
            Objects.requireNonNull(playerId, "playerId");
            machineTypeId = MachineTypeIds.normalize(machineTypeId);
            issuerId = Objects.requireNonNull(issuerId, "issuerId");
            issuerName = Objects.requireNonNull(issuerName, "issuerName")
                    .map(String::trim)
                    .filter(value -> !value.isEmpty());
            symbolIds = immutableSymbolIds(symbolIds);
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("expiresAt must be after createdAt");
            }
        }

        public boolean isExpiredAt(Instant instant) {
            Objects.requireNonNull(instant, "instant");
            return !instant.isBefore(expiresAt);
        }
    }

    /** Result of queueing, including any still-live directive that was replaced. */
    public record QueueResult(Directive directive, Optional<Directive> replaced) {
        public QueueResult {
            Objects.requireNonNull(directive, "directive");
            replaced = Objects.requireNonNull(replaced, "replaced");
        }
    }
}
