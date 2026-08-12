package me.arthed.smartgambling.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeferredWorldRegistryTest {
    @Test
    void retainsDefensiveCopiesUntilExplicitCompletion() {
        DeferredWorldRegistry registry = new DeferredWorldRegistry();
        JsonObject chunk = new JsonObject();
        chunk.addProperty("chunkX", 7);
        JsonArray source = new JsonArray();
        source.add(chunk);

        registry.defer("survival", source);
        chunk.addProperty("chunkX", 99);
        JsonArray firstCopy = registry.copy("survival").getAsJsonArray();
        firstCopy.get(0).getAsJsonObject().addProperty("chunkX", -1);

        assertEquals(
                7,
                registry.copy("survival").getAsJsonArray()
                        .get(0).getAsJsonObject().get("chunkX").getAsInt()
        );
        assertEquals("survival", registry.resolve("SURVIVAL"));
        assertTrue(registry.names().contains("survival"));
        assertTrue(registry.complete("survival"));
        assertFalse(registry.complete("survival"));
        assertNull(registry.copy("survival"));
    }

    @Test
    void ambiguousCaseInsensitiveNamesRequireAnExactMatch() {
        DeferredWorldRegistry registry = new DeferredWorldRegistry();
        registry.defer("World", new JsonArray());
        registry.defer("world", new JsonArray());

        assertEquals("World", registry.resolve("World"));
        assertEquals("world", registry.resolve("world"));
        assertNull(registry.resolve("WORLD"));
        assertEquals(2, registry.names().size());
    }

    @Test
    void indexesDeferredMachineIdsForOrphanProtection() {
        UUID retainedId = UUID.randomUUID();
        DeferredWorldRegistry registry = new DeferredWorldRegistry();
        registry.defer("offline_world", worldWithMachines(retainedId, "not-a-uuid"));

        assertEquals(Set.of(retainedId), registry.machineIds());

        registry.complete("offline_world");
        assertTrue(registry.machineIds().isEmpty());
    }

    @Test
    void durableIndexFindsIdsAcrossWorldsWithoutTrustingMalformedEntries() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        JsonObject worlds = new JsonObject();
        worlds.add("world", worldWithMachines(first));
        worlds.add("world_nether", worldWithMachines(second, "broken"));

        assertEquals(Set.of(first, second), MachineSnapshotIndex.machineIds(worlds));
    }

    private static JsonArray worldWithMachines(UUID id, String... extraIds) {
        JsonArray machines = new JsonArray();
        JsonObject machine = new JsonObject();
        machine.addProperty("id", id.toString());
        machines.add(machine);
        for (String extraId : extraIds) {
            JsonObject extra = new JsonObject();
            extra.addProperty("id", extraId);
            machines.add(extra);
        }
        JsonObject chunk = new JsonObject();
        chunk.add("machines", machines);
        JsonArray world = new JsonArray();
        world.add(chunk);
        return world;
    }
}
