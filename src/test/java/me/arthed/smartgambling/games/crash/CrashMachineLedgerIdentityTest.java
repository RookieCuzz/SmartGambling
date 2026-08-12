package me.arthed.smartgambling.games.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import me.arthed.smartgambling.economy.EconomyService;
import me.arthed.smartgambling.economy.Money;
import me.arthed.smartgambling.economy.WagerHandle;
import me.arthed.smartgambling.economy.WagerKey;
import me.arthed.smartgambling.economy.WagerResolution;
import org.junit.jupiter.api.Test;

class CrashMachineLedgerIdentityTest {
    private static final UUID MACHINE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ROUND = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID PLAYER = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void wagerIdentityIsStableAndPhysicalMachineScoped() {
        WagerKey first = CrashMachine.createWagerKey(MACHINE, ROUND, PLAYER, 1L);
        WagerKey replay = CrashMachine.createWagerKey(MACHINE, ROUND, PLAYER, 1L);
        WagerKey otherMachine = CrashMachine.createWagerKey(UUID.randomUUID(), ROUND, PLAYER, 1L);

        assertEquals(first, replay);
        assertEquals("crash", first.game());
        assertEquals(MACHINE.toString(), first.machineId());
        assertEquals(ROUND.toString(), first.roundId());
        assertEquals("bet:1", first.nonce());
        assertNotEquals(first, otherMachine);
    }

    @Test
    void durableRemoveThenRebetUsesANewPlacementCycle() {
        WagerKey original = CrashMachine.createWagerKey(MACHINE, ROUND, PLAYER, 1L);
        WagerKey duplicateCallback = CrashMachine.createWagerKey(MACHINE, ROUND, PLAYER, 1L);
        WagerKey afterRefund = CrashMachine.createWagerKey(MACHINE, ROUND, PLAYER, 2L);

        assertEquals(original, duplicateCallback);
        assertNotEquals(original, afterRefund);
        assertThrows(
                IllegalArgumentException.class,
                () -> CrashMachine.createWagerKey(MACHINE, ROUND, PLAYER, 0L)
        );
    }

    @Test
    void cashoutAndExplosionPayloadsAreExactAndOutcomeScoped() {
        WagerKey firstKey = CrashMachine.createWagerKey(MACHINE, ROUND, PLAYER, 1L);
        WagerHandle first = new WagerHandle(UUID.randomUUID(), firstKey, PLAYER, Money.of(25L));
        UUID secondPlayer = UUID.randomUUID();
        WagerHandle second = new WagerHandle(
                UUID.randomUUID(),
                CrashMachine.createWagerKey(MACHINE, ROUND, secondPlayer, 1L),
                secondPlayer,
                Money.of(40L)
        );

        assertEquals(Money.of(34.25D), CrashMachine.payoutFor(first.stake(), 1.37D));
        List<EconomyService.Resolution> losses = CrashMachine.lossResolutions(List.of(first, second));
        assertEquals(2, losses.size());
        assertInstanceOf(WagerResolution.Loss.class, losses.get(0).resolution());
        assertInstanceOf(WagerResolution.Loss.class, losses.get(1).resolution());
        assertEquals(
                "crash:" + first.id() + ":payout",
                CrashMachine.wagerOperationId(first, "payout")
        );
        assertNotEquals(
                CrashMachine.wagerOperationId(first, "payout"),
                CrashMachine.wagerOperationId(first, "refund")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CrashMachine.wagerOperationId(first, "loss")
        );
    }
}
