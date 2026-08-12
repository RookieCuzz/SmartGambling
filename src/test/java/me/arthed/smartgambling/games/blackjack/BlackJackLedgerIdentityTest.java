package me.arthed.smartgambling.games.blackjack;

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

class BlackJackLedgerIdentityTest {
    private static final UUID MACHINE = UUID.fromString("70000000-0000-0000-0000-000000000007");
    private static final UUID ROUND = UUID.fromString("80000000-0000-0000-0000-000000000008");
    private static final UUID HOST = UUID.fromString("90000000-0000-0000-0000-000000000009");
    private static final UUID CHALLENGER = UUID.fromString("a0000000-0000-0000-0000-00000000000a");
    private static final UUID HOST_NONCE = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final UUID CHALLENGER_NONCE = UUID.fromString("c0000000-0000-0000-0000-00000000000c");

    @Test
    void wagerAndRoundOperationIdentityAreStableAndTableScoped() {
        WagerKey host = BlackJack.createWagerKey(MACHINE, ROUND, HOST, "host", HOST_NONCE);
        WagerKey replay = BlackJack.createWagerKey(MACHINE, ROUND, HOST, "host", HOST_NONCE);
        WagerKey challenger = BlackJack.createWagerKey(
                MACHINE,
                ROUND,
                CHALLENGER,
                "challenger",
                CHALLENGER_NONCE
        );

        assertEquals(host, replay);
        assertEquals("blackjack", host.game());
        assertEquals(MACHINE.toString(), host.machineId());
        assertEquals(ROUND.toString(), host.roundId());
        assertEquals("host:" + HOST_NONCE, host.nonce());
        assertNotEquals(host, challenger);
        assertEquals(
                "blackjack:" + MACHINE + ':' + ROUND + ":lock",
                BlackJack.roundOperationId(MACHINE, ROUND, "lock")
        );
        assertNotEquals(
                BlackJack.roundOperationId(MACHINE, ROUND, "lock"),
                BlackJack.roundOperationId(MACHINE, ROUND, "settle")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BlackJack.createWagerKey(MACHINE, ROUND, HOST, "spectator", HOST_NONCE)
        );
    }

    @Test
    void lockPayloadContainsBothEqualStakesAndRejectsMismatch() {
        WagerHandle host = wager(HOST, "host", HOST_NONCE, 25L);
        WagerHandle challenger = wager(CHALLENGER, "challenger", CHALLENGER_NONCE, 25L);

        assertEquals(List.of(host, challenger), BlackJack.lockPayload(host, challenger));
        assertThrows(
                IllegalArgumentException.class,
                () -> BlackJack.lockPayload(
                        host,
                        wager(CHALLENGER, "challenger", CHALLENGER_NONCE, 30L)
                )
        );
    }

    @Test
    void winnerReceivesTwiceStakeAndLoserIsClosedAsLoss() {
        WagerHandle host = wager(HOST, "host", HOST_NONCE, 40L);
        WagerHandle challenger = wager(CHALLENGER, "challenger", CHALLENGER_NONCE, 40L);

        List<EconomyService.Resolution> hostWins = BlackJack.buildResolutions(
                MachineDataBlackjack.SettlementKind.PLAYER1_WINS,
                host,
                challenger
        );
        WagerResolution.Payout hostPayout = assertInstanceOf(
                WagerResolution.Payout.class,
                hostWins.get(0).resolution()
        );
        assertEquals(Money.of(80L), hostPayout.amount());
        assertInstanceOf(WagerResolution.Loss.class, hostWins.get(1).resolution());

        List<EconomyService.Resolution> challengerWins = BlackJack.buildResolutions(
                MachineDataBlackjack.SettlementKind.PLAYER2_WINS,
                host,
                challenger
        );
        assertInstanceOf(WagerResolution.Loss.class, challengerWins.get(0).resolution());
        WagerResolution.Payout challengerPayout = assertInstanceOf(
                WagerResolution.Payout.class,
                challengerWins.get(1).resolution()
        );
        assertEquals(Money.of(80L), challengerPayout.amount());
    }

    @Test
    void drawRefundBatchReturnsBothOriginalStakes() {
        WagerHandle host = wager(HOST, "host", HOST_NONCE, 15L);
        WagerHandle challenger = wager(CHALLENGER, "challenger", CHALLENGER_NONCE, 15L);

        List<EconomyService.Resolution> refunds = BlackJack.buildResolutions(
                MachineDataBlackjack.SettlementKind.REFUND,
                host,
                challenger
        );

        assertEquals(2, refunds.size());
        assertEquals(host, refunds.get(0).wager());
        assertEquals(challenger, refunds.get(1).wager());
        assertInstanceOf(WagerResolution.Refund.class, refunds.get(0).resolution());
        assertInstanceOf(WagerResolution.Refund.class, refunds.get(1).resolution());
    }

    private static WagerHandle wager(UUID player, String seat, UUID nonce, long stake) {
        WagerKey key = BlackJack.createWagerKey(MACHINE, ROUND, player, seat, nonce);
        return new WagerHandle(UUID.randomUUID(), key, player, Money.of(stake));
    }
}
