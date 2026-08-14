package me.arthed.smartgambling.games.poker;

import java.util.List;
import java.util.Objects;

/** Comparable five-card hand value. Higher values always win. */
public record PokerHandValue(Category category, List<Integer> kickers)
        implements Comparable<PokerHandValue> {
    public PokerHandValue {
        Objects.requireNonNull(category, "category");
        kickers = List.copyOf(Objects.requireNonNull(kickers, "kickers"));
        if (kickers.isEmpty()) {
            throw new IllegalArgumentException("Poker hand value must contain at least one rank");
        }
    }

    @Override
    public int compareTo(PokerHandValue other) {
        int categoryResult = Integer.compare(category.strength(), other.category.strength());
        if (categoryResult != 0) {
            return categoryResult;
        }
        int length = Math.min(kickers.size(), other.kickers.size());
        for (int index = 0; index < length; index++) {
            int result = Integer.compare(kickers.get(index), other.kickers.get(index));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(kickers.size(), other.kickers.size());
    }

    public enum Category {
        HIGH_CARD(0, "高牌"),
        ONE_PAIR(1, "一对"),
        TWO_PAIR(2, "两对"),
        THREE_OF_A_KIND(3, "三条"),
        STRAIGHT(4, "顺子"),
        FLUSH(5, "同花"),
        FULL_HOUSE(6, "葫芦"),
        FOUR_OF_A_KIND(7, "四条"),
        STRAIGHT_FLUSH(8, "同花顺");

        private final int strength;
        private final String displayName;

        Category(int strength, String displayName) {
            this.strength = strength;
            this.displayName = displayName;
        }

        public int strength() {
            return strength;
        }

        public String displayName() {
            return displayName;
        }
    }
}
