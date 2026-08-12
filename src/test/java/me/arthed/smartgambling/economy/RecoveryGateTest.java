package me.arthed.smartgambling.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RecoveryGateTest {
    @Test
    void waitsForReadinessAndRunsOnlyOnce() {
        RecoveryGate gate = new RecoveryGate();
        AtomicInteger calls = new AtomicInteger();

        assertFalse(gate.runIfReady(false, calls::incrementAndGet));
        assertFalse(gate.attempted());
        assertFalse(gate.completed());

        assertTrue(gate.runIfReady(true, calls::incrementAndGet));
        assertTrue(gate.attempted());
        assertTrue(gate.completed());
        assertFalse(gate.runIfReady(true, calls::incrementAndGet));
        assertEquals(1, calls.get());
    }

    @Test
    void thrownRecoveryIsClaimedAndNeverAutomaticallyReplayed() {
        RecoveryGate gate = new RecoveryGate();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> gate.runIfReady(true, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("outcome may be ambiguous");
        }));

        assertTrue(gate.attempted());
        assertFalse(gate.completed());
        assertFalse(gate.runIfReady(true, calls::incrementAndGet));
        assertEquals(1, calls.get());
    }
}
