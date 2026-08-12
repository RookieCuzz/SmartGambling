package me.arthed.smartgambling.games.jackpot;

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

class JackpotMachineLedgerIdentityTest {
    private static final UUID ROUND = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID FIRST_PLAYER = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_PLAYER = UUID.fromString("60000000-0000-0000-0000-000000000006");

    @Test
    void ticketIdentityIsStableAndGlobalMachineScoped() {
        WagerKey first = JackpotMachine.createWagerKey(ROUND, FIRST_PLAYER, 1L);
        WagerKey replay = JackpotMachine.createWagerKey(ROUND, FIRST_PLAYER, 1L);

        assertEquals(first, replay);
        assertEquals("jackpot", first.game());
        assertEquals("global", first.machineId());
        assertEquals(ROUND.toString(), first.roundId());
        assertEquals("ticket:1", first.nonce());
    }

    @Test
    void durableRemoveThenRebetUsesANewTicketCycle() {
        WagerKey original = JackpotMachine.createWagerKey(ROUND, FIRST_PLAYER, 1L);
        WagerKey duplicateCallback = JackpotMachine.createWagerKey(ROUND, FIRST_PLAYER, 1L);
        WagerKey afterRefund = JackpotMachine.createWagerKey(ROUND, FIRST_PLAYER, 2L);

        assertEquals(original, duplicateCallback);
        assertNotEquals(original, afterRefund);
        assertThrows(
                IllegalArgumentException.class,
                () -> JackpotMachine.createWagerKey(ROUND, FIRST_PLAYER, 0L)
        );
    }

    @Test
    void resultPaysTheWholePotToOneWinnerAndLosesEveryOtherTicket() {
        WagerHandle first = wager(FIRST_PLAYER, 1L, 10L);
        WagerHandle second = wager(SECOND_PLAYER, 1L, 30L);

        List<EconomyService.Resolution> result = JackpotMachine.resultResolutions(
                List.of(first, second),
                SECOND_PLAYER
        );

        assertEquals(Money.of(40L), JackpotMachine.totalStake(List.of(first, second)));
        assertInstanceOf(WagerResolution.Loss.class, result.get(0).resolution());
        WagerResolution.Payout payout = assertInstanceOf(
                WagerResolution.Payout.class,
                result.get(1).resolution()
        );
        assertEquals(Money.of(40L), payout.amount());
        assertThrows(
                IllegalArgumentException.class,
                () -> JackpotMachine.resultResolutions(List.of(first, second), UUID.randomUUID())
        );
    }

    @Test
    void refundAndRoundOperationIdsAreStableAndDistinct() {
        WagerHandle wager = wager(FIRST_PLAYER, 1L, 10L);
        List<EconomyService.Resolution> refunds = JackpotMachine.refundResolutions(List.of(wager));

        assertInstanceOf(WagerResolution.Refund.class, refunds.get(0).resolution());
        assertEquals(
                "jackpot:global:" + ROUND + ":lock",
                JackpotMachine.roundOperationId(ROUND, "lock")
        );
        assertNotEquals(
                JackpotMachine.roundOperationId(ROUND, "result"),
                JackpotMachine.roundOperationId(ROUND, "shutdown-refund")
        );
        assertEquals("jackpot:" + wager.id() + ":refund", JackpotMachine.wagerRefundOperationId(wager));
    }

    private static WagerHandle wager(UUID playerId, long ordinal, long stake) {
        WagerKey key = JackpotMachine.createWagerKey(ROUND, playerId, ordinal);
        return new WagerHandle(UUID.randomUUID(), key, playerId, Money.of(stake));
    }
}
