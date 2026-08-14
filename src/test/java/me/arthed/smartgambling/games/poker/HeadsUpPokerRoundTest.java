package me.arthed.smartgambling.games.poker;

import static me.arthed.smartgambling.games.poker.HeadsUpPokerRound.Action.ALL_IN;
import static me.arthed.smartgambling.games.poker.HeadsUpPokerRound.Action.CALL;
import static me.arthed.smartgambling.games.poker.HeadsUpPokerRound.Action.CHECK;
import static me.arthed.smartgambling.games.poker.HeadsUpPokerRound.Action.FOLD;
import static me.arthed.smartgambling.games.poker.HeadsUpPokerRound.Action.RAISE_TO;
import static me.arthed.smartgambling.games.poker.HeadsUpPokerRound.Seat.CHALLENGER;
import static me.arthed.smartgambling.games.poker.HeadsUpPokerRound.Seat.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeadsUpPokerRoundTest {
    @Test
    void postsHeadsUpBlindsAndUsesCorrectActionOrder() {
        HeadsUpPokerRound round = new HeadsUpPokerRound(1_000, 10, 20);

        assertEquals(990, round.stack(HOST));
        assertEquals(980, round.stack(CHALLENGER));
        assertEquals(30, round.pot());
        assertEquals(HOST, round.actor());
        assertEquals(10, round.toCall(HOST));

        assertFalse(round.act(HOST, CALL).streetComplete());
        assertTrue(round.act(CHALLENGER, CHECK).streetComplete());
        round.advanceStreet();
        assertEquals(HeadsUpPokerRound.Street.FLOP, round.street());
        assertEquals(CHALLENGER, round.actor(), "big blind acts first after the flop");
    }

    @Test
    void supportsCheckBetRaiseCallAcrossAStreet() {
        HeadsUpPokerRound round = flopRound();

        round.act(CHALLENGER, CHECK);
        round.act(HOST, RAISE_TO, 40);
        assertEquals(40, round.currentBet());
        assertEquals(80, round.minimumRaiseTo(CHALLENGER));
        round.act(CHALLENGER, RAISE_TO, 100);
        assertEquals(160, round.minimumRaiseTo(HOST));
        assertTrue(round.act(HOST, CALL).streetComplete());
        assertEquals(240, round.pot());
    }

    @Test
    void foldAwardsPotAndReturnsBothUncommittedStacks() {
        HeadsUpPokerRound round = new HeadsUpPokerRound(1_000, 10, 20);
        round.act(HOST, RAISE_TO, 80);
        assertTrue(round.act(CHALLENGER, FOLD).handComplete());

        HeadsUpPokerRound.Settlement settlement = round.settleFold();
        assertEquals(HOST, settlement.winner());
        assertEquals(1_020, settlement.hostPayout());
        assertEquals(980, settlement.challengerPayout());
        assertEquals(2_000, settlement.hostPayout() + settlement.challengerPayout());
    }

    @Test
    void allInCallRunsBoardAndConservesEscrow() {
        HeadsUpPokerRound round = new HeadsUpPokerRound(100, 5, 10);
        round.act(HOST, ALL_IN);
        HeadsUpPokerRound.ActionResult call = round.act(CHALLENGER, CALL);

        assertTrue(call.streetComplete());
        assertTrue(call.showdownReady());
        round.moveToShowdown();
        HeadsUpPokerRound.Settlement winner = round.settleShowdown(-1);
        assertEquals(0, winner.hostPayout());
        assertEquals(200, winner.challengerPayout());
    }

    @Test
    void completesAllFourBettingStreetsInHeadsUpOrder() {
        HeadsUpPokerRound round = new HeadsUpPokerRound(1_000, 10, 20);

        round.act(HOST, CALL);
        assertTrue(round.act(CHALLENGER, CHECK).streetComplete());
        for (HeadsUpPokerRound.Street expected : new HeadsUpPokerRound.Street[]{
                HeadsUpPokerRound.Street.FLOP,
                HeadsUpPokerRound.Street.TURN,
                HeadsUpPokerRound.Street.RIVER
        }) {
            round.advanceStreet();
            assertEquals(expected, round.street());
            assertEquals(CHALLENGER, round.actor());
            round.act(CHALLENGER, CHECK);
            HeadsUpPokerRound.ActionResult hostCheck = round.act(HOST, CHECK);
            assertTrue(hostCheck.streetComplete());
        }

        round.moveToShowdown();
        assertEquals(HeadsUpPokerRound.Street.SHOWDOWN, round.street());
        HeadsUpPokerRound.Settlement split = round.settleShowdown(0);
        assertEquals(2_000, split.hostPayout() + split.challengerPayout());
    }

    @Test
    void splitPotAndUnmatchedExcessAreReturnedExactly() {
        HeadsUpPokerRound round = new HeadsUpPokerRound(101, 5, 10);
        round.act(HOST, ALL_IN);
        round.act(CHALLENGER, ALL_IN);
        round.moveToShowdown();

        HeadsUpPokerRound.Settlement split = round.settleShowdown(0);
        assertEquals(101, split.hostPayout());
        assertEquals(101, split.challengerPayout());
        assertTrue(split.split());
    }

    @Test
    void rejectsOutOfTurnUnderMinimumAndIllegalChecks() {
        HeadsUpPokerRound round = new HeadsUpPokerRound(1_000, 10, 20);

        assertThrows(IllegalStateException.class, () -> round.act(CHALLENGER, CHECK));
        assertThrows(IllegalStateException.class, () -> round.act(HOST, CHECK));
        assertThrows(IllegalArgumentException.class, () -> round.act(HOST, RAISE_TO, 30));
    }

    private static HeadsUpPokerRound flopRound() {
        HeadsUpPokerRound round = new HeadsUpPokerRound(1_000, 10, 20);
        round.act(HOST, CALL);
        round.act(CHALLENGER, CHECK);
        round.advanceStreet();
        return round;
    }
}
