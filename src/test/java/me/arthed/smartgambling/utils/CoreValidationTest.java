package me.arthed.smartgambling.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreValidationTest {
    @Test
    void onlyStrictlyPositiveFiniteEconomyAmountsAreAccepted() {
        assertTrue(EconomyTransactions.isValidAmount(1.0D));
        assertTrue(EconomyTransactions.isValidAmount(0.01D));
        assertFalse(EconomyTransactions.isValidAmount(0.0D));
        assertFalse(EconomyTransactions.isValidAmount(-1.0D));
        assertFalse(EconomyTransactions.isValidAmount(Double.NaN));
        assertFalse(EconomyTransactions.isValidAmount(Double.POSITIVE_INFINITY));
    }

    @Test
    void standardHexColorDoesNotRequireATrailingHash() {
        assertEquals(
                "§x§1§2§A§b§3§4text",
                ColorUtils.translateHexColorCodes("#", "", "#12Ab34text")
        );
    }
}
