package me.arthed.smartgambling.games.slots.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ForcedSlotResultRegistryTest {
    private static final Instant START = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ISSUER = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void queuesImmutableMetadataUsingNormalizedMachineTypeAndInjectedClock() {
        MutableClock clock = new MutableClock(START);
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(clock);

        ForcedSlotResultRegistry.Directive directive = registry.queue(
                PLAYER,
                "  SlotExample ",
                ISSUER,
                " Admin ",
                List.of("Star", "Lemon", "Seven"),
                Duration.ofSeconds(120)
        ).directive();

        assertEquals(PLAYER, directive.playerId());
        assertEquals("slotexample", directive.machineTypeId());
        assertEquals(Optional.of(ISSUER), directive.issuerId());
        assertEquals(Optional.of("Admin"), directive.issuerName());
        assertEquals(List.of("Star", "Lemon", "Seven"), directive.symbolIds());
        assertEquals(START, directive.createdAt());
        assertEquals(START.plusSeconds(120), directive.expiresAt());
        assertThrows(UnsupportedOperationException.class, () -> directive.symbolIds().add("Cherry"));
    }

    @Test
    void replacesOnlyTheSamePlayerAndNormalizedMachineType() {
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(Clock.fixed(START, ZoneOffset.UTC));

        ForcedSlotResultRegistry.Directive first = queue(registry, PLAYER, "SlotOne", "Star").directive();
        queue(registry, PLAYER, "SlotTwo", "Lemon");
        queue(registry, OTHER_PLAYER, "SlotOne", "Seven");
        ForcedSlotResultRegistry.QueueResult replacement = queue(registry, PLAYER, " SLOTONE ", "Cherry");

        assertEquals(Optional.of(first), replacement.replaced());
        assertNotEquals(first.directiveId(), replacement.directive().directiveId());
        assertEquals(List.of("Cherry"), registry.peek(PLAYER, "slotone").orElseThrow().symbolIds());
        assertEquals(List.of("Lemon"), registry.peek(PLAYER, "slottwo").orElseThrow().symbolIds());
        assertEquals(List.of("Seven"), registry.peek(OTHER_PLAYER, "slotone").orElseThrow().symbolIds());
        assertEquals(3, registry.size());
    }

    @Test
    void claimIsExactAndOneShotWithoutConsumingOtherMachineTypes() {
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(Clock.fixed(START, ZoneOffset.UTC));
        queue(registry, PLAYER, "classic", "Star");
        queue(registry, PLAYER, "fruit", "Lemon");

        assertTrue(registry.claim(PLAYER, "missing").isEmpty());
        assertTrue(registry.claim(OTHER_PLAYER, "classic").isEmpty());
        assertEquals(List.of("Star"), registry.claim(PLAYER, " CLASSIC ").orElseThrow().symbolIds());
        assertTrue(registry.claim(PLAYER, "classic").isEmpty());
        assertTrue(registry.peek(PLAYER, "fruit").isPresent());
    }

    @Test
    void expiresAtTheDeadlineAndPurgeReportsExpiredDirectives() {
        MutableClock clock = new MutableClock(START);
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(clock);
        ForcedSlotResultRegistry.Directive first = queue(registry, PLAYER, "classic", "Star").directive();

        clock.advance(Duration.ofSeconds(119));
        assertTrue(registry.peek(PLAYER, "classic").isPresent());

        clock.advance(Duration.ofSeconds(1));
        assertEquals(List.of(first), registry.purgeExpired());
        assertTrue(registry.claim(PLAYER, "classic").isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void queueDoesNotReportAnExpiredDirectiveAsAReplacement() {
        MutableClock clock = new MutableClock(START);
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(clock);
        queue(registry, PLAYER, "classic", "Star");
        clock.advance(Duration.ofSeconds(120));

        ForcedSlotResultRegistry.QueueResult replacement = queue(registry, PLAYER, "CLASSIC", "Lemon");

        assertTrue(replacement.replaced().isEmpty());
        assertEquals(List.of("Lemon"), replacement.directive().symbolIds());
        assertEquals(1, registry.size());
    }

    @Test
    void expiryObserverSeesEachDirectiveOnceAcrossPeekQueueAndList() {
        MutableClock clock = new MutableClock(START);
        List<ForcedSlotResultRegistry.Directive> observed = new ArrayList<>();
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(clock, observed::add);

        ForcedSlotResultRegistry.Directive expiredByPeek =
                queue(registry, PLAYER, "classic", "Star").directive();
        clock.advance(Duration.ofSeconds(120));
        assertTrue(registry.peek(PLAYER, "classic").isEmpty());

        ForcedSlotResultRegistry.Directive expiredByQueue =
                queue(registry, PLAYER, "classic", "Lemon").directive();
        clock.advance(Duration.ofSeconds(120));
        ForcedSlotResultRegistry.QueueResult replacement =
                queue(registry, PLAYER, "CLASSIC", "Seven");
        assertTrue(replacement.replaced().isEmpty());
        assertEquals(replacement.directive(), registry.claim(PLAYER, "classic").orElseThrow());

        ForcedSlotResultRegistry.Directive expiredByList =
                queue(registry, PLAYER, "fruit", "Cherry").directive();
        clock.advance(Duration.ofSeconds(120));
        assertTrue(registry.list(PLAYER).isEmpty());

        assertEquals(List.of(expiredByPeek, expiredByQueue, expiredByList), observed);
        assertTrue(registry.peek(PLAYER, "classic").isEmpty());
        assertTrue(registry.purgeExpired().isEmpty());
        assertEquals(0, registry.size());
        assertEquals(3, observed.size(), "an expired directive must never notify twice");
    }

    @Test
    void expiryObserverFailureNeverBreaksRegistrySemantics() {
        MutableClock clock = new MutableClock(START);
        AtomicInteger observerCalls = new AtomicInteger();
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(clock, directive -> {
            observerCalls.incrementAndGet();
            throw new IllegalStateException("audit sink unavailable");
        });

        queue(registry, PLAYER, "classic", "Star");
        clock.advance(Duration.ofSeconds(120));
        ForcedSlotResultRegistry.QueueResult replacement =
                queue(registry, PLAYER, "classic", "Lemon");
        assertTrue(replacement.replaced().isEmpty());
        assertEquals(replacement.directive(), registry.claim(PLAYER, "classic").orElseThrow());

        queue(registry, PLAYER, "fruit", "Cherry");
        clock.advance(Duration.ofSeconds(120));
        assertTrue(registry.clear(PLAYER, "fruit").isEmpty());

        queue(registry, PLAYER, "zeta", "Seven");
        clock.advance(Duration.ofSeconds(120));
        assertEquals(0, registry.size());
        assertEquals(3, observerCalls.get());
    }

    @Test
    void listsAndClearsPerTypePerPlayerAndGlobally() {
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(Clock.fixed(START, ZoneOffset.UTC));
        queue(registry, PLAYER, "zeta", "Star");
        queue(registry, PLAYER, "alpha", "Lemon");
        queue(registry, OTHER_PLAYER, "alpha", "Seven");

        assertEquals(List.of("alpha", "zeta"), registry.list(PLAYER).stream()
                .map(ForcedSlotResultRegistry.Directive::machineTypeId)
                .toList());
        assertEquals("Star", registry.clear(PLAYER, "zeta").orElseThrow().symbolIds().get(0));
        assertTrue(registry.clear(PLAYER, "zeta").isEmpty());
        assertEquals(List.of("alpha"), registry.clearAll(PLAYER).stream()
                .map(ForcedSlotResultRegistry.Directive::machineTypeId)
                .toList());
        assertTrue(registry.list(PLAYER).isEmpty());
        assertEquals(1, registry.clearAll().size());
        assertEquals(0, registry.size());
    }

    @Test
    void validatesTtlMachineTypeAndSymbols() {
        ForcedSlotResultRegistry registry = new ForcedSlotResultRegistry(Clock.fixed(START, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class,
                () -> registry.queue(PLAYER, "classic", ISSUER, "Admin", List.of("Star"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> registry.queue(PLAYER, " ", ISSUER, "Admin", List.of("Star"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.queue(PLAYER, "classic", ISSUER, "Admin", List.of(), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.queue(PLAYER, "classic", ISSUER, "Admin", List.of(" "), Duration.ofSeconds(1)));
    }

    private static ForcedSlotResultRegistry.QueueResult queue(
            ForcedSlotResultRegistry registry,
            UUID playerId,
            String machineTypeId,
            String symbolId
    ) {
        return registry.queue(
                playerId,
                machineTypeId,
                ISSUER,
                "Admin",
                List.of(symbolId),
                Duration.ofSeconds(120)
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("Test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
