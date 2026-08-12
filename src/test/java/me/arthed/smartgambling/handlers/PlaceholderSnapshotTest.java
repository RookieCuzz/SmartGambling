package me.arthed.smartgambling.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceholderSnapshotTest {
    private static final PlaceholderMessages MESSAGES = new PlaceholderMessages(
            "open",
            "choosing",
            "waiting:%bet%",
            "playing:%bet%",
            "cooldown:%time%",
            "active:%amount%:%player_count%:%time%",
            "finish:%amount%:%player_count%:%time%",
            "crash-cooldown:%time%:%player_count%",
            "betting:%time%:%player_count%",
            "crashing:%value%:%player_crashed_count%:%player_count%"
    );

    @Test
    void malformedAndUnknownMachineIdsAreSafe() {
        PlaceholderSnapshot snapshot = new PlaceholderSnapshot(MESSAGES, null, Map.of(), Map.of());

        assertNull(snapshot.resolve("blackjack_status_not-a-uuid"));
        assertNull(snapshot.resolve("crash_status_not-a-uuid"));
        assertNull(snapshot.resolve("blackjack_status_" + UUID.randomUUID()));
        assertNull(snapshot.resolve("crash_status_" + UUID.randomUUID()));
        assertNull(snapshot.resolve("jackpot_status"));
        assertNull(snapshot.resolve("unknown"));
        assertNull(snapshot.resolve(null));
    }

    @Test
    void blackjackStatesUseTheCopiedTableState() {
        UUID open = UUID.randomUUID();
        UUID choosing = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();
        UUID playing = UUID.randomUUID();
        Map<UUID, PlaceholderSnapshot.BlackjackState> tables = new HashMap<>();
        tables.put(open, new PlaceholderSnapshot.BlackjackState(false, false, false, 0));
        tables.put(choosing, new PlaceholderSnapshot.BlackjackState(false, false, true, 0));
        tables.put(waiting, new PlaceholderSnapshot.BlackjackState(true, false, true, 25));
        tables.put(playing, new PlaceholderSnapshot.BlackjackState(true, true, true, 40));

        PlaceholderSnapshot snapshot = new PlaceholderSnapshot(MESSAGES, null, tables, Map.of());
        tables.clear();

        assertEquals("open", snapshot.resolve("blackjack_status_" + open));
        assertEquals("choosing", snapshot.resolve("BLACKJACK_STATUS_" + choosing));
        assertEquals("waiting:25", snapshot.resolve("blackjack_status_" + waiting));
        assertEquals("playing:40", snapshot.resolve("blackjack_status_" + playing));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.blackjackTables().put(UUID.randomUUID(), tables.get(open))
        );
    }

    @Test
    void crashAndJackpotStatusesAreResolvedFromImmutableValues() {
        UUID betting = UUID.randomUUID();
        UUID cooldown = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        Map<UUID, PlaceholderSnapshot.CrashState> crash = Map.of(
                betting, new PlaceholderSnapshot.CrashState(false, 12, 0.0D, 3, 0),
                cooldown, new PlaceholderSnapshot.CrashState(true, 7, 2.0D, 4, 2),
                running, new PlaceholderSnapshot.CrashState(true, 0, 1.75D, 5, 2)
        );

        PlaceholderSnapshot active = new PlaceholderSnapshot(
                MESSAGES,
                new PlaceholderSnapshot.JackpotState(false, false, 90, 3, 8),
                Map.of(),
                crash
        );
        PlaceholderSnapshot choosing = new PlaceholderSnapshot(
                MESSAGES,
                new PlaceholderSnapshot.JackpotState(true, true, 90, 3, 0),
                Map.of(),
                Map.of()
        );
        PlaceholderSnapshot cooling = new PlaceholderSnapshot(
                MESSAGES,
                new PlaceholderSnapshot.JackpotState(true, false, 90, 3, 6),
                Map.of(),
                Map.of()
        );

        assertEquals("betting:12:3", active.resolve("crash_status_" + betting));
        assertEquals("crash-cooldown:7:4", active.resolve("crash_status_" + cooldown));
        assertEquals("crashing:1.75:2:5", active.resolve("crash_status_" + running));
        assertEquals("active:90:3:8", active.resolve("jackpot_status"));
        assertEquals("finish:90:3:0", choosing.resolve("jackpot_status"));
        assertEquals("cooldown:6", cooling.resolve("jackpot_status"));
    }
}
