package me.arthed.smartgambling.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class MachineTypeIdsTest {
    @Test
    void normalizesCaseWhitespaceAndUsesLocaleRoot() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("slotmachine", MachineTypeIds.normalize("  SLOTMachine  "));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void derivesCanonicalSlotIdFromFileName() {
        assertEquals("slotexample", MachineTypeIds.fromSlotFileName("SlotExample.YML"));
    }

    @Test
    void rejectsBlankIdsAndNonYamlFiles() {
        assertThrows(IllegalArgumentException.class, () -> MachineTypeIds.normalize(" \t "));
        assertThrows(IllegalArgumentException.class, () -> MachineTypeIds.fromSlotFileName("slot.json"));
        assertThrows(IllegalArgumentException.class, () -> MachineTypeIds.fromSlotFileName(".yml"));
    }

    @Test
    void distinctKnownJavaHashCollisionStringsStayDistinct() {
        // The legacy integer-key registry treated both names as the same key.
        assertEquals("FB".hashCode(), "Ea".hashCode());
        assertEquals("fb", MachineTypeIds.normalize("FB"));
        assertEquals("ea", MachineTypeIds.normalize("Ea"));
    }
}
