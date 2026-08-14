package me.arthed.smartgambling.games.poker;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/** One physical card in the configured 52-card poker deck. */
public record PokerCard(Rank rank, Suit suit, ItemStack itemStack) {
    public PokerCard {
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(suit, "suit");
    }

    public ItemStack displayItem() {
        if (itemStack == null) {
            throw new IllegalStateException("Poker card has no display item: " + shortName());
        }
        return itemStack.clone();
    }

    public String shortName() {
        return rank.symbol() + suit.symbol();
    }

    public enum Suit {
        HEARTS("♥", true),
        DIAMONDS("♦", true),
        CLUBS("♣", false),
        SPADES("♠", false);

        private final String symbol;
        private final boolean red;

        Suit(String symbol, boolean red) {
            this.symbol = symbol;
            this.red = red;
        }

        public String symbol() {
            return symbol;
        }

        public boolean red() {
            return red;
        }

        public static Suit parse(String value) {
            return valueOf(Objects.requireNonNull(value, "suit").trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Rank {
        TWO(2, "2"),
        THREE(3, "3"),
        FOUR(4, "4"),
        FIVE(5, "5"),
        SIX(6, "6"),
        SEVEN(7, "7"),
        EIGHT(8, "8"),
        NINE(9, "9"),
        TEN(10, "10"),
        JACK(11, "J"),
        QUEEN(12, "Q"),
        KING(13, "K"),
        ACE(14, "A");

        private final int value;
        private final String symbol;

        Rank(int value, String symbol) {
            this.value = value;
            this.symbol = symbol;
        }

        public int value() {
            return value;
        }

        public String symbol() {
            return symbol;
        }

        public static Rank parse(String value) {
            String normalized = Objects.requireNonNull(value, "rank").trim().toUpperCase(Locale.ROOT);
            for (Rank rank : values()) {
                if (rank.name().equals(normalized) || rank.symbol.equals(normalized)) {
                    return rank;
                }
            }
            throw new IllegalArgumentException("Unknown poker rank: " + value);
        }
    }
}
