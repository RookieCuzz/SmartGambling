package me.arthed.smartgambling.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void commitsRevisionedV3SnapshotAndReloadsIt() throws Exception {
        Path dataFile = temporaryDirectory.resolve("data.json");
        MachineRepository repository = new MachineRepository(dataFile);
        assertEquals(0L, repository.load().revision());

        MachineRepository.Snapshot committed = repository.transact(worlds -> {
            worlds.add("world", new JsonArray());
            return worlds;
        });

        assertEquals(1L, committed.revision());
        assertFalse(repository.isDirty());
        JsonObject root = JsonParser.parseString(Files.readString(dataFile)).getAsJsonObject();
        assertEquals(3, root.get("dataVersion").getAsInt());
        assertEquals(1L, root.get("revision").getAsLong());
        assertTrue(root.getAsJsonObject("worlds").has("world"));

        MachineRepository.Snapshot reloaded = new MachineRepository(dataFile).load();
        assertEquals(1L, reloaded.revision());
        assertTrue(reloaded.worldsCopy().has("world"));
    }

    @Test
    void writeFailureRemainsDirtyUntilExplicitAbort() throws Exception {
        Path blockingParent = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(blockingParent, "block", StandardCharsets.UTF_8);
        MachineRepository repository = new MachineRepository(blockingParent.resolve("data.json"));
        repository.load();

        assertThrows(IOException.class, () -> repository.transact(worlds -> {
            worlds.add("ghost", new JsonArray());
            return worlds;
        }));

        assertTrue(repository.isDirty());
        assertEquals(0L, repository.durableSnapshot().revision());
        assertEquals(1L, repository.workingSnapshot().revision());
        assertTrue(repository.workingSnapshot().worldsCopy().has("ghost"));

        assertTrue(repository.abort());
        assertFalse(repository.isDirty());
        assertEquals(0L, repository.workingSnapshot().revision());
        assertFalse(repository.workingSnapshot().worldsCopy().has("ghost"));
    }

    @Test
    void corruptPrimaryRecoversBackupAndImmediatelyRestoresPrimary() throws Exception {
        Path dataFile = temporaryDirectory.resolve("data.json");
        MachineRepository repository = new MachineRepository(dataFile);
        repository.load();
        repository.transact(worlds -> {
            worlds.add("revision-one", new JsonArray());
            return worlds;
        });
        repository.transact(worlds -> {
            worlds.add("revision-two", new JsonArray());
            return worlds;
        });
        Files.writeString(dataFile, "{broken", StandardCharsets.UTF_8);

        MachineRepository recovering = new MachineRepository(dataFile);
        MachineRepository.Snapshot recovered = recovering.load();
        assertTrue(recovering.isDirty());
        assertEquals(2L, recovered.revision());
        assertTrue(recovered.worldsCopy().has("revision-one"));
        assertFalse(recovered.worldsCopy().has("revision-two"));

        assertTrue(recovering.flush());
        assertFalse(recovering.isDirty());
        MachineRepository.Snapshot restored = new MachineRepository(dataFile).load();
        assertEquals(2L, restored.revision());
        assertTrue(restored.worldsCopy().has("revision-one"));
    }
}
