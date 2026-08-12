package me.arthed.smartgambling.creation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MachineCreationValidatorTest {
    @Test
    void finiteRejectsNullNanAndInfinity() {
        assertTrue(MachineCreationValidator.allFinite());
        assertTrue(MachineCreationValidator.allFinite(-1.25D, 0.0D, 9.5D));
        assertFalse(MachineCreationValidator.allFinite((double[]) null));
        assertFalse(MachineCreationValidator.allFinite(Double.NaN));
        assertFalse(MachineCreationValidator.allFinite(Double.POSITIVE_INFINITY));
        assertFalse(MachineCreationValidator.allFinite(Double.NEGATIVE_INFINITY));
    }

    @Test
    void radiusIsThreeDimensionalAndInclusive() {
        assertTrue(MachineCreationValidator.withinRadius(0, 0, 0, 16, 0, 0, 16.0D));
        assertTrue(MachineCreationValidator.withinRadius(5, 10, -3, 5, 10, -3, 0.0D));
        assertFalse(MachineCreationValidator.withinRadius(0, 0, 0, 16, 1, 0, 16.0D));
        assertFalse(MachineCreationValidator.withinRadius(0, 0, 0, 0, 0, 0, -1.0D));
        assertFalse(MachineCreationValidator.withinRadius(0, 0, 0, 0, 0, 0, Double.NaN));
    }

    @Test
    void uniquenessRejectsDuplicatesAndNulls() {
        assertTrue(MachineCreationValidator.hasUniqueElements(List.of()));
        assertTrue(MachineCreationValidator.hasUniqueElements(List.of("origin", "one", "two")));
        assertFalse(MachineCreationValidator.hasUniqueElements(List.of("origin", "one", "origin")));
        assertFalse(MachineCreationValidator.hasUniqueElements(java.util.Arrays.asList("origin", null)));
        assertFalse(MachineCreationValidator.hasUniqueElements(null));
    }
}
