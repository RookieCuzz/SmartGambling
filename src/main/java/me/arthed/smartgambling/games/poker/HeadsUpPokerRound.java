package me.arthed.smartgambling.games.poker;

import java.util.Objects;

/** Pure, deterministic no-limit betting state for one heads-up Hold'em hand. */
public final class HeadsUpPokerRound {
    public enum Seat {
        HOST,
        CHALLENGER;

        public Seat other() {
            return this == HOST ? CHALLENGER : HOST;
        }
    }

    public enum Street {
        PREFLOP,
        FLOP,
        TURN,
        RIVER,
        SHOWDOWN,
        COMPLETE
    }

    public enum Action {
        FOLD,
        CHECK,
        CALL,
        RAISE_TO,
        ALL_IN
    }

    public record ActionResult(
            Seat actor,
            Action action,
            long chipsPaid,
            boolean streetComplete,
            boolean showdownReady,
            boolean handComplete
    ) {
    }

    public record Settlement(long hostPayout, long challengerPayout, Seat winner, boolean split) {
        public Settlement {
            if (hostPayout < 0L || challengerPayout < 0L) {
                throw new IllegalArgumentException("Poker payouts cannot be negative");
            }
            if (split && winner != null) {
                throw new IllegalArgumentException("A split pot cannot have one winner");
            }
        }
    }

    private final long buyIn;
    private final long smallBlind;
    private final long bigBlind;
    private final long[] stack = new long[2];
    private final long[] streetContribution = new long[2];
    private final long[] totalContribution = new long[2];
    private final boolean[] acted = new boolean[2];
    private final boolean[] raiseRights = {true, true};
    private Street street = Street.PREFLOP;
    private Seat actor = Seat.HOST;
    private Seat folded;
    private long currentBet;
    private long minimumRaiseIncrement;
    private boolean complete;

    public HeadsUpPokerRound(long buyIn, long smallBlind, long bigBlind) {
        if (smallBlind <= 0L || bigBlind <= smallBlind || buyIn < bigBlind) {
            throw new IllegalArgumentException("Buy-in must cover blinds and big blind must exceed small blind");
        }
        this.buyIn = buyIn;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        stack[0] = buyIn;
        stack[1] = buyIn;
        pay(Seat.HOST, smallBlind);
        pay(Seat.CHALLENGER, bigBlind);
        currentBet = bigBlind;
        minimumRaiseIncrement = bigBlind;
    }

    public ActionResult act(Seat seat, Action action) {
        return act(seat, action, -1L);
    }

    public ActionResult act(Seat seat, Action action, long raiseTo) {
        ensureTurn(seat);
        Objects.requireNonNull(action, "action");
        long paid = 0L;
        switch (action) {
            case FOLD -> {
                folded = seat;
                complete = true;
                street = Street.COMPLETE;
            }
            case CHECK -> {
                if (toCall(seat) != 0L) {
                    throw new IllegalStateException("Cannot check while facing a bet");
                }
                acted[index(seat)] = true;
            }
            case CALL -> {
                long call = toCall(seat);
                if (call <= 0L) {
                    throw new IllegalStateException("There is no bet to call");
                }
                paid = pay(seat, Math.min(call, stack(seat)));
                acted[index(seat)] = true;
            }
            case RAISE_TO -> paid = raiseTo(seat, raiseTo);
            case ALL_IN -> {
                long maximum = maximumRaiseTo(seat);
                if (maximum <= currentBet) {
                    long call = Math.min(toCall(seat), stack(seat));
                    if (call <= 0L) {
                        throw new IllegalStateException("Player is already all-in or has nothing to call");
                    }
                    paid = pay(seat, call);
                    acted[index(seat)] = true;
                } else {
                    paid = raiseTo(seat, maximum);
                }
            }
        }

        boolean handComplete = complete;
        boolean streetComplete = !handComplete && bettingClosed();
        boolean showdownReady = streetComplete && (isAllInRunout() || street == Street.RIVER);
        if (!handComplete && !streetComplete) {
            actor = seat.other();
        } else if (!handComplete) {
            actor = null;
        }
        return new ActionResult(seat, action, paid, streetComplete, showdownReady, handComplete);
    }

    public void advanceStreet() {
        if (complete || actor != null || !bettingClosed() || isAllInRunout()) {
            throw new IllegalStateException("The current betting street cannot advance");
        }
        street = switch (street) {
            case PREFLOP -> Street.FLOP;
            case FLOP -> Street.TURN;
            case TURN -> Street.RIVER;
            case RIVER -> Street.SHOWDOWN;
            default -> throw new IllegalStateException("No street follows " + street);
        };
        if (street == Street.SHOWDOWN) {
            return;
        }
        resetStreet();
        actor = Seat.CHALLENGER;
    }

    public void moveToShowdown() {
        if (complete || actor != null || !bettingClosed()) {
            throw new IllegalStateException("Betting is not closed");
        }
        street = Street.SHOWDOWN;
    }

    public Settlement settleFold() {
        if (!complete || folded == null) {
            throw new IllegalStateException("The hand did not end by folding");
        }
        Seat winner = folded.other();
        long pot = pot();
        return winner == Seat.HOST
                ? new Settlement(stack[0] + pot, stack[1], winner, false)
                : new Settlement(stack[0], stack[1] + pot, winner, false);
    }

    public Settlement forfeit(Seat seat) {
        ensureActiveSeat(seat);
        if (complete || street == Street.SHOWDOWN) {
            throw new IllegalStateException("The hand can no longer be forfeited");
        }
        folded = seat;
        complete = true;
        street = Street.COMPLETE;
        actor = null;
        return settleFold();
    }

    /** comparison is positive for host, negative for challenger, and zero for a tie. */
    public Settlement settleShowdown(int comparison) {
        if (street != Street.SHOWDOWN || complete) {
            throw new IllegalStateException("The hand is not ready for showdown");
        }
        complete = true;
        street = Street.COMPLETE;
        long matched = Math.min(totalContribution[0], totalContribution[1]);
        long contestedPot = Math.multiplyExact(matched, 2L);
        long host = stack[0] + (totalContribution[0] - matched);
        long challenger = stack[1] + (totalContribution[1] - matched);
        if (comparison > 0) {
            host += contestedPot;
            return checkedSettlement(host, challenger, Seat.HOST, false);
        }
        if (comparison < 0) {
            challenger += contestedPot;
            return checkedSettlement(host, challenger, Seat.CHALLENGER, false);
        }
        long half = contestedPot / 2L;
        host += half + contestedPot % 2L; // button receives the indivisible odd chip
        challenger += half;
        return checkedSettlement(host, challenger, null, true);
    }

    public long suggestedPotRaiseTo(Seat seat) {
        ensureActiveSeat(seat);
        long target = currentBet + pot() + toCall(seat);
        return Math.min(maximumRaiseTo(seat), Math.max(minimumRaiseTo(seat), target));
    }

    public long minimumRaiseTo(Seat seat) {
        ensureActiveSeat(seat);
        return Math.min(maximumRaiseTo(seat), Math.addExact(currentBet, minimumRaiseIncrement));
    }

    public long maximumRaiseTo(Seat seat) {
        ensureActiveSeat(seat);
        return Math.addExact(streetContribution(seat), stack(seat));
    }

    public boolean canRaise(Seat seat) {
        return seat == actor && raiseRights[index(seat)] && maximumRaiseTo(seat) > currentBet;
    }

    public long toCall(Seat seat) {
        ensureActiveSeat(seat);
        return Math.max(0L, currentBet - streetContribution(seat));
    }

    public long stack(Seat seat) {
        return stack[index(seat)];
    }

    public long streetContribution(Seat seat) {
        return streetContribution[index(seat)];
    }

    public long totalContribution(Seat seat) {
        return totalContribution[index(seat)];
    }

    public long pot() {
        return Math.addExact(totalContribution[0], totalContribution[1]);
    }

    public long buyIn() {
        return buyIn;
    }

    public long smallBlind() {
        return smallBlind;
    }

    public long bigBlind() {
        return bigBlind;
    }

    public long currentBet() {
        return currentBet;
    }

    public Seat actor() {
        return actor;
    }

    public Seat folded() {
        return folded;
    }

    public Street street() {
        return street;
    }

    public boolean complete() {
        return complete;
    }

    public boolean isAllInRunout() {
        return stack[0] == 0L || stack[1] == 0L;
    }

    private long raiseTo(Seat seat, long target) {
        int seatIndex = index(seat);
        if (!raiseRights[seatIndex]) {
            throw new IllegalStateException("A short all-in did not reopen raising");
        }
        long maximum = maximumRaiseTo(seat);
        if (target <= currentBet || target > maximum) {
            throw new IllegalArgumentException("Raise target must exceed the current bet and fit the stack");
        }
        long increment = target - currentBet;
        boolean fullRaise = increment >= minimumRaiseIncrement;
        if (!fullRaise && target != maximum) {
            throw new IllegalArgumentException("A sub-minimum raise is only legal as an all-in");
        }
        Seat other = seat.other();
        boolean otherAlreadyActed = acted[index(other)];
        long paid = pay(seat, target - streetContribution(seat));
        currentBet = target;
        acted[seatIndex] = true;
        if (fullRaise) {
            minimumRaiseIncrement = increment;
            acted[index(other)] = false;
            raiseRights[index(other)] = true;
        } else if (otherAlreadyActed) {
            raiseRights[index(other)] = false;
        }
        return paid;
    }

    private long pay(Seat seat, long amount) {
        int seatIndex = index(seat);
        if (amount < 0L || amount > stack[seatIndex]) {
            throw new IllegalArgumentException("Invalid chip payment: " + amount);
        }
        stack[seatIndex] -= amount;
        streetContribution[seatIndex] = Math.addExact(streetContribution[seatIndex], amount);
        totalContribution[seatIndex] = Math.addExact(totalContribution[seatIndex], amount);
        return amount;
    }

    private boolean bettingClosed() {
        boolean hostActed = acted[0] || stack[0] == 0L;
        boolean challengerActed = acted[1] || stack[1] == 0L;
        boolean amountsClosed = streetContribution[0] == streetContribution[1]
                || stack[0] == 0L || stack[1] == 0L;
        return hostActed && challengerActed && amountsClosed;
    }

    private void resetStreet() {
        streetContribution[0] = 0L;
        streetContribution[1] = 0L;
        acted[0] = false;
        acted[1] = false;
        raiseRights[0] = true;
        raiseRights[1] = true;
        currentBet = 0L;
        minimumRaiseIncrement = bigBlind;
    }

    private Settlement checkedSettlement(long host, long challenger, Seat winner, boolean split) {
        if (Math.addExact(host, challenger) != Math.multiplyExact(buyIn, 2L)) {
            throw new IllegalStateException("Poker settlement does not conserve the escrowed buy-ins");
        }
        return new Settlement(host, challenger, winner, split);
    }

    private void ensureTurn(Seat seat) {
        ensureActiveSeat(seat);
        if (complete || street == Street.SHOWDOWN || actor != seat || stack(seat) == 0L) {
            throw new IllegalStateException("It is not " + seat + "'s turn");
        }
    }

    private void ensureActiveSeat(Seat seat) {
        Objects.requireNonNull(seat, "seat");
    }

    private static int index(Seat seat) {
        return seat == Seat.HOST ? 0 : 1;
    }
}
