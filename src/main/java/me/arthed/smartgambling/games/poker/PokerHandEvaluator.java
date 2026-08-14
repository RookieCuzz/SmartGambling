package me.arthed.smartgambling.games.poker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.arthed.smartgambling.games.poker.PokerCard.Rank;

/** Exhaustive best-five evaluator for standard Texas Hold'em hands. */
public final class PokerHandEvaluator {
    private PokerHandEvaluator() {
    }

    public static PokerHandValue evaluate(List<PokerCard> cards) {
        Objects.requireNonNull(cards, "cards");
        if (cards.size() < 5 || cards.size() > 7) {
            throw new IllegalArgumentException("Texas Hold'em evaluation requires 5 to 7 cards");
        }
        Set<String> identities = new HashSet<>();
        for (PokerCard card : cards) {
            Objects.requireNonNull(card, "cards cannot contain null");
            if (!identities.add(card.rank().name() + ':' + card.suit().name())) {
                throw new IllegalArgumentException("Poker hand contains a duplicate card: " + card.shortName());
            }
        }

        PokerHandValue best = null;
        int size = cards.size();
        for (int a = 0; a < size - 4; a++) {
            for (int b = a + 1; b < size - 3; b++) {
                for (int c = b + 1; c < size - 2; c++) {
                    for (int d = c + 1; d < size - 1; d++) {
                        for (int e = d + 1; e < size; e++) {
                            PokerHandValue candidate = evaluateFive(List.of(
                                    cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e)
                            ));
                            if (best == null || candidate.compareTo(best) > 0) {
                                best = candidate;
                            }
                        }
                    }
                }
            }
        }
        return Objects.requireNonNull(best, "best");
    }

    private static PokerHandValue evaluateFive(List<PokerCard> cards) {
        Map<Integer, Integer> counts = new HashMap<>();
        List<Integer> ranks = new ArrayList<>(5);
        for (PokerCard card : cards) {
            int rank = card.rank().value();
            counts.merge(rank, 1, Integer::sum);
            ranks.add(rank);
        }
        ranks.sort(Comparator.reverseOrder());
        boolean flush = cards.stream().map(PokerCard::suit).distinct().count() == 1L;
        int straightHigh = straightHigh(counts.keySet());

        if (flush && straightHigh > 0) {
            return value(PokerHandValue.Category.STRAIGHT_FLUSH, straightHigh);
        }

        List<Integer> quads = ranksWithCount(counts, 4);
        if (!quads.isEmpty()) {
            int quad = quads.get(0);
            return value(PokerHandValue.Category.FOUR_OF_A_KIND, quad, highestExcluding(ranks, Set.of(quad), 1));
        }

        List<Integer> trips = ranksWithCount(counts, 3);
        List<Integer> pairs = ranksWithCount(counts, 2);
        if (!trips.isEmpty() && (!pairs.isEmpty() || trips.size() > 1)) {
            int trip = trips.get(0);
            int pair = !pairs.isEmpty() ? pairs.get(0) : trips.get(1);
            return value(PokerHandValue.Category.FULL_HOUSE, trip, pair);
        }
        if (flush) {
            return new PokerHandValue(PokerHandValue.Category.FLUSH, ranks);
        }
        if (straightHigh > 0) {
            return value(PokerHandValue.Category.STRAIGHT, straightHigh);
        }
        if (!trips.isEmpty()) {
            int trip = trips.get(0);
            List<Integer> kickers = highestExcluding(ranks, Set.of(trip), 2);
            return value(PokerHandValue.Category.THREE_OF_A_KIND, trip, kickers);
        }
        if (pairs.size() >= 2) {
            int highPair = pairs.get(0);
            int lowPair = pairs.get(1);
            return value(
                    PokerHandValue.Category.TWO_PAIR,
                    List.of(highPair, lowPair),
                    highestExcluding(ranks, Set.of(highPair, lowPair), 1)
            );
        }
        if (pairs.size() == 1) {
            int pair = pairs.get(0);
            return value(
                    PokerHandValue.Category.ONE_PAIR,
                    List.of(pair),
                    highestExcluding(ranks, Set.of(pair), 3)
            );
        }
        return new PokerHandValue(PokerHandValue.Category.HIGH_CARD, ranks);
    }

    private static List<Integer> ranksWithCount(Map<Integer, Integer> counts, int target) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() == target)
                .map(Map.Entry::getKey)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    private static int straightHigh(Set<Integer> uniqueRanks) {
        Set<Integer> ranks = new HashSet<>(uniqueRanks);
        if (ranks.contains(Rank.ACE.value())) {
            ranks.add(1);
        }
        for (int high = Rank.ACE.value(); high >= Rank.FIVE.value(); high--) {
            boolean found = true;
            for (int offset = 0; offset < 5; offset++) {
                if (!ranks.contains(high - offset)) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return high;
            }
        }
        return 0;
    }

    private static List<Integer> highestExcluding(List<Integer> ranks, Set<Integer> excluded, int limit) {
        List<Integer> result = new ArrayList<>(limit);
        for (int rank : ranks) {
            if (!excluded.contains(rank) && !result.contains(rank)) {
                result.add(rank);
                if (result.size() == limit) {
                    break;
                }
            }
        }
        return result;
    }

    private static PokerHandValue value(PokerHandValue.Category category, int first) {
        return new PokerHandValue(category, List.of(first));
    }

    private static PokerHandValue value(
            PokerHandValue.Category category,
            int first,
            List<Integer> remaining
    ) {
        return value(category, List.of(first), remaining);
    }

    private static PokerHandValue value(
            PokerHandValue.Category category,
            List<Integer> first,
            List<Integer> remaining
    ) {
        List<Integer> values = new ArrayList<>(first.size() + remaining.size());
        values.addAll(first);
        values.addAll(remaining);
        return new PokerHandValue(category, values);
    }

    private static PokerHandValue value(PokerHandValue.Category category, int first, int second) {
        return new PokerHandValue(category, List.of(first, second));
    }
}
