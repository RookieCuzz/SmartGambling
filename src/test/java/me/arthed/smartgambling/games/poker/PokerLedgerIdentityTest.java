package me.arthed.smartgambling.games.poker;

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

class PokerLedgerIdentityTest {
    private static final UUID MACHINE = UUID.fromString("71000000-0000-0000-0000-000000000007");
    private static final UUID ROUND = UUID.fromString("81000000-0000-0000-0000-000000000008");
    private static final UUID HOST = UUID.fromString("91000000-0000-0000-0000-000000000009");
    private static final UUID CHALLENGER = UUID.fromString("a1000000-0000-0000-0000-00000000000a");
    private static final UUID HOST_NONCE = UUID.fromString("b1000000-0000-0000-0000-00000000000b");
    private static final UUID CHALLENGER_NONCE = UUID.fromString("c1000000-0000-0000-0000-00000000000c");

    @Test
    void wagerAndOperationIdentityAreStableAndTableScoped() {
        WagerKey host = Poker.createWagerKey(MACHINE, ROUND, HOST, "host", HOST_NONCE);
        WagerKey replay = Poker.createWagerKey(MACHINE, ROUND, HOST, "host", HOST_NONCE);
        WagerKey challenger = Poker.createWagerKey(
                MACHINE, ROUND, CHALLENGER, "challenger", CHALLENGER_NONCE);

        assertEquals(host, replay);
        assertEquals("poker", host.game());
        assertEquals(MACHINE.toString(), host.machineId());
        assertEquals(ROUND.toString(), host.roundId());
        assertEquals("host:" + HOST_NONCE, host.nonce());
        assertNotEquals(host, challenger);
        assertEquals(
                "poker:" + MACHINE + ':' + ROUND + ":lock",
                Poker.roundOperationId(MACHINE, ROUND, "lock")
        );
        assertNotEquals(
                Poker.roundOperationId(MACHINE, ROUND, "lock"),
                Poker.roundOperationId(MACHINE, ROUND, "settle")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Poker.createWagerKey(MACHINE, ROUND, HOST, "spectator", HOST_NONCE)
        );
    }

    @Test
    void completedSettlementPaysExactStacksAndConservesEscrow() {
        WagerHandle host = wager(HOST, "host", HOST_NONCE, 100L);
        WagerHandle challenger = wager(CHALLENGER, "challenger", CHALLENGER_NONCE, 100L);

        List<EconomyService.Resolution> resolutions =
                Poker.buildResolutions(host, challenger, false, 160L, 40L);

        WagerResolution.Payout hostPayout = assertInstanceOf(
                WagerResolution.Payout.class, resolutions.get(0).resolution());
        WagerResolution.Payout challengerPayout = assertInstanceOf(
                WagerResolution.Payout.class, resolutions.get(1).resolution());
        assertEquals(Money.of(160L), hostPayout.amount());
        assertEquals(Money.of(40L), challengerPayout.amount());
        assertThrows(
                IllegalArgumentException.class,
                () -> Poker.buildResolutions(host, challenger, false, 160L, 41L)
        );
    }

    @Test
    void zeroStackIsClosedAsLossAndTechnicalAbortRefundsBothBuyIns() {
        WagerHandle host = wager(HOST, "host", HOST_NONCE, 100L);
        WagerHandle challenger = wager(CHALLENGER, "challenger", CHALLENGER_NONCE, 100L);

        List<EconomyService.Resolution> allInLoss =
                Poker.buildResolutions(host, challenger, false, 0L, 200L);
        assertInstanceOf(WagerResolution.Loss.class, allInLoss.get(0).resolution());
        assertEquals(
                Money.of(200L),
                assertInstanceOf(WagerResolution.Payout.class, allInLoss.get(1).resolution()).amount()
        );

        List<EconomyService.Resolution> refunds =
                Poker.buildResolutions(host, challenger, true, -1L, -1L);
        assertInstanceOf(WagerResolution.Refund.class, refunds.get(0).resolution());
        assertInstanceOf(WagerResolution.Refund.class, refunds.get(1).resolution());
    }

    private static WagerHandle wager(UUID player, String seat, UUID nonce, long stake) {
        WagerKey key = Poker.createWagerKey(MACHINE, ROUND, player, seat, nonce);
        return new WagerHandle(UUID.randomUUID(), key, player, Money.of(stake));
    }
}
