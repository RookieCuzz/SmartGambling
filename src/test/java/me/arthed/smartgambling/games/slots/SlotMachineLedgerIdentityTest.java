package me.arthed.smartgambling.games.slots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import me.arthed.smartgambling.economy.Money;
import me.arthed.smartgambling.economy.WagerHandle;
import me.arthed.smartgambling.economy.WagerKey;
import org.junit.jupiter.api.Test;

class SlotMachineLedgerIdentityTest {
    @Test
    void wagerKeyIsStableForTheSameMachineSessionAndSpin() {
        UUID machineId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID playerId = UUID.fromString("20000000-0000-0000-0000-000000000002");

        WagerKey first = SlotMachine.createWagerKey(machineId, playerId, "session-17", 4L);
        WagerKey replay = SlotMachine.createWagerKey(machineId, playerId, "session-17", 4L);

        assertEquals(first, replay);
        assertEquals("slot", first.game());
        assertEquals(machineId.toString(), first.machineId());
        assertEquals("session-17:4", first.roundId());
        assertEquals(playerId, first.playerId());
        assertEquals("spin:4", first.nonce());
    }

    @Test
    void successiveSpinsCannotReuseAWagerKey() {
        UUID machineId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID playerId = UUID.fromString("20000000-0000-0000-0000-000000000002");

        WagerKey first = SlotMachine.createWagerKey(machineId, playerId, "session-17", 1L);
        WagerKey second = SlotMachine.createWagerKey(machineId, playerId, "session-17", 2L);

        assertNotEquals(first, second);
        assertThrows(
                IllegalArgumentException.class,
                () -> SlotMachine.createWagerKey(machineId, playerId, "session-17", 0L)
        );
    }

    @Test
    void resolutionOperationIdsAreStableAndOutcomeScoped() {
        UUID machineId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID playerId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        WagerKey key = SlotMachine.createWagerKey(machineId, playerId, "session-17", 1L);
        WagerHandle wager = new WagerHandle(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                key,
                playerId,
                Money.of(25L)
        );

        assertEquals(
                "slot:30000000-0000-0000-0000-000000000003:payout",
                SlotMachine.wagerOperationId(wager, "payout")
        );
        assertEquals(
                SlotMachine.wagerOperationId(wager, "refund"),
                SlotMachine.wagerOperationId(wager, "refund")
        );
        assertNotEquals(
                SlotMachine.wagerOperationId(wager, "loss"),
                SlotMachine.wagerOperationId(wager, "refund")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SlotMachine.wagerOperationId(wager, "retry-1")
        );
    }
}
