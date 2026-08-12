package me.arthed.smartgambling.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MachineEntityRecordTest {
    @Test
    void roundTripsRoleUuidPositionAndExpectedChunk() {
        UUID uuid = UUID.randomUUID();
        MachineEntityRecord original = new MachineEntityRecord(
                EntityRole.BLACKJACK_CHALLENGER_SEAT,
                uuid,
                new MachineEntityRecord.Position(
                        "casino", -16.25D, 72.5D, 31.75D, -2, 1
                ),
                new MachineEntityRecord.Position(
                        "casino", 48.25D, 72.0D, -32.25D, 3, -3
                )
        );

        MachineEntityRecord restored = MachineEntityRecord.fromJson(original.toJson());

        assertEquals(original, restored);
        assertEquals(uuid, restored.uuid());
        assertEquals(EntityRole.BLACKJACK_CHALLENGER_SEAT, restored.role());
        assertEquals(-2, restored.chunkX());
        assertEquals(1, restored.chunkZ());
        assertEquals(-2, restored.actual().chunkX());
        assertEquals(1, restored.actual().chunkZ());
        assertEquals(3, restored.expected().chunkX());
        assertEquals(-3, restored.expected().chunkZ());
        assertEquals(-16.25D, restored.actual().x());
        assertEquals(48.25D, restored.expected().x());
    }

    @Test
    void derivesMissingLegacyChunkCoordinatesAndAcceptsNullUuid() {
        JsonObject json = new JsonObject();
        json.addProperty("role", "MODEL");
        json.add("uuid", com.google.gson.JsonNull.INSTANCE);
        json.addProperty("world", "world");
        json.addProperty("x", 32.1D);
        json.addProperty("y", 64.0D);
        json.addProperty("z", -0.1D);

        MachineEntityRecord restored = MachineEntityRecord.fromJson(json);

        assertNull(restored.uuid());
        assertNull(restored.actual());
        assertEquals(2, restored.chunkX());
        assertEquals(-1, restored.chunkZ());
        assertEquals(2, restored.expected().chunkX());
    }

    @Test
    void explicitNullActualStillRetainsExpectedRecoveryChunk() {
        JsonObject json = new JsonObject();
        json.addProperty("role", "PRIMARY_SEAT");
        json.add("uuid", com.google.gson.JsonNull.INSTANCE);
        json.add("actual", com.google.gson.JsonNull.INSTANCE);
        JsonObject expected = new JsonObject();
        expected.addProperty("world", "world_nether");
        expected.addProperty("x", -32.01D);
        expected.addProperty("y", 60.0D);
        expected.addProperty("z", 47.99D);
        expected.addProperty("chunkX", -3);
        expected.addProperty("chunkZ", 2);
        json.add("expected", expected);

        MachineEntityRecord restored = MachineEntityRecord.fromJson(json);

        assertNull(restored.actual());
        assertTrue(restored.expected().isInChunk("world_nether", -3, 2));
    }
}
