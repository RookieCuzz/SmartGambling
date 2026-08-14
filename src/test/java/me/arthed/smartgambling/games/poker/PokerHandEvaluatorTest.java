package me.arthed.smartgambling.games.poker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import me.arthed.smartgambling.games.poker.PokerCard.Rank;
import me.arthed.smartgambling.games.poker.PokerCard.Suit;
import org.junit.jupiter.api.Test;

class PokerHandEvaluatorTest {
    @Test
    void recognizesEveryStandardCategory() {
        assertCategory(PokerHandValue.Category.STRAIGHT_FLUSH, "AH KH QH JH 10H 2C 3D");
        assertCategory(PokerHandValue.Category.FOUR_OF_A_KIND, "AS AH AC AD 2H 3C 4D");
        assertCategory(PokerHandValue.Category.FULL_HOUSE, "KS KH KC 2D 2C 8H 9S");
        assertCategory(PokerHandValue.Category.FLUSH, "AH JH 8H 4H 2H 9C 10D");
        assertCategory(PokerHandValue.Category.STRAIGHT, "9H 8C 7D 6S 5H 2C AD");
        assertCategory(PokerHandValue.Category.THREE_OF_A_KIND, "QS QH QC 9D 7C 3H 2S");
        assertCategory(PokerHandValue.Category.TWO_PAIR, "JS JH 4C 4D 9S 3H 2C");
        assertCategory(PokerHandValue.Category.ONE_PAIR, "10S 10H AC 8D 6C 3H 2S");
        assertCategory(PokerHandValue.Category.HIGH_CARD, "AS JD 9C 7H 4S 3D 2C");
    }

    @Test
    void aceCanPlayLowButWheelLosesToSixHighStraight() {
        PokerHandValue wheel = evaluate("AH 2C 3D 4S 5H 9C KD");
        PokerHandValue sixHigh = evaluate("2H 3C 4D 5S 6H 9D KC");

        assertEquals(List.of(5), wheel.kickers());
        assertTrue(sixHigh.compareTo(wheel) > 0);
    }

    @Test
    void comparesPairsAndKickersLexicographically() {
        PokerHandValue aceKicker = evaluate("KH KC AS 9D 7C 4H 2S");
        PokerHandValue queenKicker = evaluate("KS KD QH JD 10C 4S 2H");

        assertTrue(aceKicker.compareTo(queenKicker) > 0);
    }

    @Test
    void rejectsDuplicateOrWrongCardCounts() {
        assertThrows(IllegalArgumentException.class, () -> evaluate("AH AH 2C 3D 4S"));
        assertThrows(IllegalArgumentException.class, () -> evaluate("AH 2C 3D 4S"));
        assertThrows(IllegalArgumentException.class,
                () -> evaluate("AH 2C 3D 4S 5H 6C 7D 8S"));
    }

    private static void assertCategory(PokerHandValue.Category expected, String cards) {
        assertEquals(expected, evaluate(cards).category());
    }

    private static PokerHandValue evaluate(String cards) {
        return PokerHandEvaluator.evaluate(Arrays.stream(cards.split(" ")).map(PokerHandEvaluatorTest::card).toList());
    }

    private static PokerCard card(String value) {
        String rankText = value.substring(0, value.length() - 1);
        char suitText = value.charAt(value.length() - 1);
        Rank rank = Rank.parse(rankText);
        Suit suit = switch (suitText) {
            case 'H' -> Suit.HEARTS;
            case 'D' -> Suit.DIAMONDS;
            case 'C' -> Suit.CLUBS;
            case 'S' -> Suit.SPADES;
            default -> throw new IllegalArgumentException("Unknown suit");
        };
        return new PokerCard(rank, suit, null);
    }
}
