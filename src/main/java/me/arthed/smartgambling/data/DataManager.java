package me.arthed.smartgambling.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;

/** Versioned machine repository and Bukkit entity reconciler. */
public final class DataManager {
    public static final int DATA_VERSION = 3;

    private static final File DBFILE = new File(SmartGambling.getInstance().getDataFolder(), "data.json");
    public static boolean initialized;
    private static MachineRepository repository;
    private static MachineRepository.Snapshot snapshot;
    private static DeferredWorldRegistry deferredWorlds = new DeferredWorldRegistry();

    private DataManager() {
    }

    public static synchronized void load() {
        MachineRepository store = repository();
        boolean missingFile = !DBFILE.exists();
        try {
            if (store.isDirty()) {
                throw new DataStoreException(
                        "Cannot reload data.json while an earlier machine mutation is still dirty"
                );
            }
            snapshot = store.load();
        } catch (IOException exception) {
            throw new DataStoreException("Could not read SmartGambling data file " + DBFILE.getAbsolutePath(), exception);
        }

        JsonObject worldsJson = snapshot.worldsCopy();
        Map<World, HashMap<Chunk, List<MachineData>>> stagedWorlds = new HashMap<>();
        Map<UUID, MachineData> stagedById = new LinkedHashMap<>();
        List<MachineData> stagedMachines = new ArrayList<>();
        List<Entity> duplicateEntities = new ArrayList<>();
        Set<UUID> selectedEntities = new HashSet<>();
        Set<UUID> claimedEntityIds = new HashSet<>();
        DeferredWorldRegistry stagedDeferredWorlds = new DeferredWorldRegistry();
        boolean canonicalChanged = missingFile || snapshot.dataVersion() < DATA_VERSION;

        try {
            for (Map.Entry<String, JsonElement> worldEntry : worldsJson.entrySet()) {
                String worldName = worldEntry.getKey();
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    stagedDeferredWorlds.defer(worldName, worldEntry.getValue());
                    Bukkit.getConsoleSender().sendMessage(ChatColor.YELLOW
                            + "[SmartGambling] World is not loaded yet; deferring machine data for: "
                            + worldName);
                    continue;
                }
                if (!worldEntry.getValue().isJsonArray()) {
                    throw new IllegalArgumentException("World entry is not an array: " + worldName);
                }

                HashMap<Chunk, List<MachineData>> worldMachines = new HashMap<>();
                JsonArray chunkArray = worldEntry.getValue().getAsJsonArray();
                for (JsonElement chunkElement : chunkArray) {
                    JsonObject chunkJson = chunkElement.getAsJsonObject();
                    int chunkX = requiredInt(chunkJson, "chunkX");
                    int chunkZ = requiredInt(chunkJson, "chunkZ");
                    Chunk originChunk = world.getChunkAt(chunkX, chunkZ);
                    JsonArray machinesJson = requiredArray(chunkJson, "machines");
                    List<MachineData> chunkMachines = worldMachines.computeIfAbsent(
                            originChunk, ignored -> new ArrayList<>()
                    );

                    for (int machineIndex = 0; machineIndex < machinesJson.size(); machineIndex++) {
                        JsonObject machineJson = machinesJson.get(machineIndex).getAsJsonObject();
                        String typeName = requiredString(machineJson, "type");
                        Machine machineType = SmartGambling.getInstance().findMachineType(typeName);
                        if (machineType == null) {
                            Bukkit.getConsoleSender().sendMessage(ChatColor.RED
                                    + "[SmartGambling] Error loading plugin data. Invalid machine type: " + typeName);
                            continue;
                        }

                        UUID machineId = UUID.fromString(requiredString(machineJson, "id"));
                        if (stagedById.containsKey(machineId)) {
                            throw new IllegalArgumentException("Duplicate machine UUID in data.json: " + machineId);
                        }
                        Block[] blocks = readBlocks(world, requiredArray(machineJson, "blocks"));
                        BlockFace direction = BlockFace.valueOf(requiredString(machineJson, "direction"));
                        int storedVersion = machineJson.has("dataVersion")
                                ? machineJson.get("dataVersion").getAsInt()
                                : Math.min(2, snapshot.dataVersion());
                        List<EntityRole> roles = EntityRole.forMachine(machineType);
                        List<MachineEntityRecord> records = readEntityRecords(
                                machineJson,
                                roles,
                                machineType,
                                blocks[0],
                                direction,
                                world
                        );
                        EntityResolution resolution = resolveEntities(
                                machineId,
                                machineType,
                                blocks[0],
                                direction,
                                roles,
                                records,
                                storedVersion < DATA_VERSION,
                                claimedEntityIds
                        );

                        MachineData machineData = machineType instanceof BlackJack
                                ? new MachineDataBlackjack(
                                        machineId,
                                        machineType,
                                        blocks,
                                        resolution.entities(),
                                        direction,
                                        false
                                )
                                : new MachineData(
                                        machineId,
                                        machineType,
                                        blocks,
                                        resolution.entities(),
                                        direction,
                                        false
                                );
                        stagedMachines.add(machineData);
                        JsonObject canonical = machineToJson(machineData);
                        if (!canonical.equals(machineJson)) {
                            machinesJson.set(machineIndex, canonical);
                            canonicalChanged = true;
                        }

                        for (Entity entity : machineData.entities) {
                            if (entity != null) {
                                selectedEntities.add(entity.getUniqueId());
                                claimedEntityIds.add(entity.getUniqueId());
                            }
                        }
                        duplicateEntities.addAll(resolution.duplicates());
                        stagedById.put(machineId, machineData);
                        chunkMachines.add(machineData);
                    }
                }
                stagedWorlds.put(world, worldMachines);
            }

            if (canonicalChanged) {
                snapshot = store.transact(ignored -> worldsJson.deepCopy());
            } else if (store.isDirty()) {
                store.flush();
                snapshot = store.durableSnapshot();
            }
        } catch (IOException exception) {
            store.abort();
            rollbackPendingSpawns(stagedMachines);
            throw new DataStoreException("Could not persist SmartGambling v3 entity migration", exception);
        } catch (RuntimeException exception) {
            rollbackPendingSpawns(stagedMachines);
            throw exception;
        }

        SmartGambling plugin = SmartGambling.getInstance();
        // Runtime activation happens only after the complete v3 candidate is
        // durable. If activation throws, none of the staged machines is
        // published and no uncertain duplicate is removed.
        activateStagedRuntimes(stagedMachines);
        plugin.machines.clear();
        plugin.uuidMachines.clear();
        plugin.machines.putAll(stagedWorlds);
        plugin.uuidMachines.putAll(stagedById);

        deferredWorlds = stagedDeferredWorlds;
        initialized = true;
        for (Entity duplicate : duplicateEntities) {
            try {
                if (duplicate != null && duplicate.isValid()
                        && !selectedEntities.contains(duplicate.getUniqueId())
                        && MachineEntityFactory.machineId(duplicate) != null) {
                    duplicate.remove();
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not remove a tagged duplicate after loading machine data",
                        exception
                );
            }
        }
    }

    /**
     * Materializes only the machines retained for a world that was unavailable
     * during the initial repository load. Repeated calls for the same world are
     * idempotent and never clear or rebuild another world's runtime maps.
     *
     * @return number of machines published by this call, or zero when the
     *         world has no deferred entry (including a repeated event)
     */
    public static synchronized int loadDeferredWorld(World world) {
        if (world == null || !initialized || repository == null || snapshot == null) {
            return 0;
        }
        String deferredName = deferredWorlds.resolve(world.getName());
        if (deferredName == null) {
            return 0;
        }
        JsonElement retained = deferredWorlds.copy(deferredName);
        if (retained == null) {
            return 0;
        }
        if (!retained.isJsonArray()) {
            throw new DataStoreException(
                    "Deferred world entry is not an array: " + deferredName
            );
        }

        SmartGambling plugin = SmartGambling.getInstance();
        HashMap<Chunk, List<MachineData>> stagedWorld = new HashMap<>();
        Map<UUID, MachineData> stagedById = new LinkedHashMap<>();
        List<MachineData> stagedMachines = new ArrayList<>();
        List<Entity> duplicateEntities = new ArrayList<>();
        Set<UUID> selectedEntities = new HashSet<>();
        Set<UUID> claimedEntityIds = runtimeEntityIds(plugin.uuidMachines.values(), null);
        selectedEntities.addAll(claimedEntityIds);
        JsonArray canonicalWorld = retained.getAsJsonArray().deepCopy();
        boolean canonicalChanged = false;

        try {
            for (JsonElement chunkElement : canonicalWorld) {
                JsonObject chunkJson = chunkElement.getAsJsonObject();
                int chunkX = requiredInt(chunkJson, "chunkX");
                int chunkZ = requiredInt(chunkJson, "chunkZ");
                Chunk originChunk = world.getChunkAt(chunkX, chunkZ);
                JsonArray machinesJson = requiredArray(chunkJson, "machines");
                List<MachineData> chunkMachines = stagedWorld.computeIfAbsent(
                        originChunk,
                        ignored -> new ArrayList<>()
                );

                for (int machineIndex = 0; machineIndex < machinesJson.size(); machineIndex++) {
                    JsonObject machineJson = machinesJson.get(machineIndex).getAsJsonObject();
                    String typeName = requiredString(machineJson, "type");
                    Machine machineType = plugin.findMachineType(typeName);
                    if (machineType == null) {
                        Bukkit.getConsoleSender().sendMessage(ChatColor.RED
                                + "[SmartGambling] Error loading deferred plugin data. "
                                + "Invalid machine type: " + typeName);
                        continue;
                    }

                    UUID machineId = UUID.fromString(requiredString(machineJson, "id"));
                    if (plugin.uuidMachines.containsKey(machineId)
                            || stagedById.containsKey(machineId)) {
                        throw new IllegalArgumentException(
                                "Duplicate machine UUID while loading deferred world "
                                        + deferredName + ": " + machineId
                        );
                    }
                    Block[] blocks = readBlocks(world, requiredArray(machineJson, "blocks"));
                    BlockFace direction = BlockFace.valueOf(requiredString(machineJson, "direction"));
                    int storedVersion = machineJson.has("dataVersion")
                            ? machineJson.get("dataVersion").getAsInt()
                            : Math.min(2, snapshot.dataVersion());
                    List<EntityRole> roles = EntityRole.forMachine(machineType);
                    List<MachineEntityRecord> records = readEntityRecords(
                            machineJson,
                            roles,
                            machineType,
                            blocks[0],
                            direction,
                            world
                    );
                    EntityResolution resolution = resolveEntities(
                            machineId,
                            machineType,
                            blocks[0],
                            direction,
                            roles,
                            records,
                            storedVersion < DATA_VERSION,
                            claimedEntityIds
                    );

                    MachineData machineData = machineType instanceof BlackJack
                            ? new MachineDataBlackjack(
                                    machineId,
                                    machineType,
                                    blocks,
                                    resolution.entities(),
                                    direction,
                                    false
                            )
                            : new MachineData(
                                    machineId,
                                    machineType,
                                    blocks,
                                    resolution.entities(),
                                    direction,
                                    false
                            );
                    stagedMachines.add(machineData);
                    JsonObject canonical = machineToJson(machineData);
                    if (!canonical.equals(machineJson)) {
                        machinesJson.set(machineIndex, canonical);
                        canonicalChanged = true;
                    }

                    for (Entity entity : machineData.entities) {
                        if (entity != null) {
                            selectedEntities.add(entity.getUniqueId());
                            claimedEntityIds.add(entity.getUniqueId());
                        }
                    }
                    duplicateEntities.addAll(resolution.duplicates());
                    stagedById.put(machineId, machineData);
                    chunkMachines.add(machineData);
                }
            }
        } catch (RuntimeException exception) {
            rollbackPendingSpawns(stagedMachines);
            throw exception;
        }

        MachineRepository store = repository();
        if (canonicalChanged) {
            if (store.isDirty()) {
                rollbackPendingSpawns(stagedMachines);
                throw new DataStoreException(
                        "Cannot migrate deferred world " + deferredName
                                + " while an earlier machine mutation is dirty"
                );
            }
            try {
                snapshot = store.transact(worlds -> {
                    worlds.add(deferredName, canonicalWorld.deepCopy());
                    return worlds;
                });
            } catch (IOException exception) {
                store.abort();
                rollbackPendingSpawns(stagedMachines);
                throw new DataStoreException(
                        "Could not persist SmartGambling v3 migration for deferred world "
                                + deferredName,
                        exception
                );
            } catch (RuntimeException exception) {
                store.abort();
                rollbackPendingSpawns(stagedMachines);
                throw exception;
            }
        }

        // Do not publish before every staged runtime has activated. From this
        // point onward a committed candidate may reference the entities, so an
        // activation failure deliberately leaves them untouched for a retry.
        activateStagedRuntimes(stagedMachines);

        HashMap<Chunk, List<MachineData>> mergedWorld = new HashMap<>();
        HashMap<Chunk, List<MachineData>> existingWorld = plugin.machines.get(world);
        if (existingWorld != null) {
            existingWorld.forEach((chunk, machines) ->
                    mergedWorld.put(chunk, new ArrayList<>(machines)));
        }
        stagedWorld.forEach((chunk, machines) -> mergedWorld
                .computeIfAbsent(chunk, ignored -> new ArrayList<>())
                .addAll(machines));
        plugin.uuidMachines.putAll(stagedById);
        if (!mergedWorld.isEmpty()) {
            plugin.machines.put(world, mergedWorld);
        }

        // Publication is now complete. Mark the retained entry consumed before
        // best-effort duplicate cleanup so a cleanup exception cannot replay
        // the same UUIDs on a repeated WorldLoadEvent.
        deferredWorlds.complete(deferredName);
        for (Entity duplicate : duplicateEntities) {
            try {
                if (duplicate != null && duplicate.isValid()
                        && !selectedEntities.contains(duplicate.getUniqueId())
                        && MachineEntityFactory.machineId(duplicate) != null) {
                    duplicate.remove();
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not remove a tagged duplicate after loading deferred world "
                                + deferredName,
                        exception
                );
            }
        }
        return stagedMachines.size();
    }

    public static synchronized Set<String> deferredWorldNames() {
        return deferredWorlds.names();
    }

    /** Copy-on-write add. The machine is activated only after the new snapshot is durable. */
    public static synchronized boolean addMachine(Chunk chunk, MachineData machineData) {
        requireInitialized();
        final JsonObject machineJson;
        try {
            machineData.validateRuntimeActivation();
            machineData.ensureEntities();
            machineJson = machineToJson(machineData);
        } catch (RuntimeException | LinkageError exception) {
            machineData.rollbackPendingEntities();
            throw exception;
        }
        try {
            snapshot = repository().transact(worlds -> {
                removeMachineJson(worlds, machineData.id);
                addMachineJson(worlds, chunk, machineJson);
                return worlds;
            });
        } catch (IOException exception) {
            repository().abort();
            machineData.rollbackPendingEntities();
            throw new DataStoreException("Could not add machine " + machineData.id, exception);
        } catch (RuntimeException exception) {
            repository().abort();
            machineData.rollbackPendingEntities();
            throw exception;
        }
        try {
            machineData.activateRuntime();
            publishRuntimeMachine(machineData);
        } catch (RuntimeException | LinkageError activationFailure) {
            compensateFailedAddition(machineData, activationFailure);
        }
        return true;
    }

    /** Copy-on-write remove. Runtime/entity destruction happens only after persistence succeeds. */
    public static synchronized boolean removeMachine(Chunk chunk, MachineData machineData) {
        requireInitialized();
        try {
            snapshot = repository().transact(worlds -> {
                removeMachineJson(worlds, machineData.id);
                return worlds;
            });
        } catch (IOException exception) {
            repository().abort();
            throw new DataStoreException("Could not remove machine " + machineData.id, exception);
        } catch (RuntimeException exception) {
            repository().abort();
            throw exception;
        }
        cleanupDurablyRemovedMachine(machineData);
        return true;
    }

    /** Reconciles and atomically persists the current role/UUID/location records. */
    public static synchronized boolean repairMachine(MachineData machineData) {
        requireInitialized();
        MachineData.EntityMutationCheckpoint checkpoint = machineData.checkpointEntityMutation();
        final List<Entity> duplicates;
        final Chunk originChunk;
        final JsonObject machineJson;
        try {
            EntityResolution resolution = resolvePersistedMachine(machineData);
            duplicates = resolution == null ? List.of() : resolution.duplicates();
            if (resolution != null) {
                Entity[] reconciled = resolution.entities();
                for (int index = 0; index < reconciled.length && index < machineData.entities.length; index++) {
                    if (reconciled[index] == null
                            && machineData.entities[index] instanceof ArmorStand
                            && machineData.entities[index].isValid()) {
                        reconciled[index] = machineData.entities[index];
                    }
                }
                machineData.replaceEntities(reconciled);
            }
            machineData.ensureEntities();
            originChunk = machineData.blocks[0].getChunk();
            machineJson = machineToJson(machineData);
        } catch (RuntimeException | LinkageError exception) {
            machineData.rollbackEntityMutation(checkpoint);
            throw exception;
        }
        try {
            snapshot = repository().transact(worlds -> {
                removeMachineJson(worlds, machineData.id);
                addMachineJson(worlds, originChunk, machineJson);
                return worlds;
            });
        } catch (IOException exception) {
            repository().abort();
            machineData.rollbackEntityMutation(checkpoint);
            throw new DataStoreException("Could not repair machine " + machineData.id, exception);
        } catch (RuntimeException exception) {
            repository().abort();
            machineData.rollbackEntityMutation(checkpoint);
            throw exception;
        }
        publishRuntimeMachine(machineData);
        machineData.activateRuntime();
        Set<UUID> selected = new HashSet<>();
        for (Entity entity : machineData.entities) {
            if (entity != null) {
                selected.add(entity.getUniqueId());
            }
        }
        for (Entity duplicate : duplicates) {
            if (duplicate != null && duplicate.isValid()
                    && !selected.contains(duplicate.getUniqueId())
                    && machineData.id.equals(MachineEntityFactory.machineId(duplicate))) {
                duplicate.remove();
            }
        }
        return true;
    }

    public static boolean fixMachineEntities(MachineData machineData) {
        return repairMachine(machineData);
    }

    /**
     * Repairs every machine touching this chunk by origin, current/expected
     * entity position, or the last durable actual/expected entity position.
     */
    public static synchronized int fixEntities(Chunk chunk) {
        requireInitialized();
        List<MachineData> affected = new ArrayList<>();
        for (MachineData machineData : SmartGambling.getInstance().uuidMachines.values()) {
            if (machineTouchesChunk(machineData, chunk)) {
                affected.add(machineData);
            }
        }
        for (MachineData machineData : affected) {
            repairMachine(machineData);
        }
        cleanupTaggedDuplicates(chunk);
        return affected.size();
    }

    /** Retries the exact dirty repository candidate. Dirty state is cleared only on success. */
    public static synchronized boolean save() {
        requireInitialized();
        try {
            repository().flush();
            snapshot = repository().durableSnapshot();
            return true;
        } catch (IOException exception) {
            throw new DataStoreException("Could not save SmartGambling data to " + DBFILE.getAbsolutePath(), exception);
        }
    }

    public static synchronized boolean isDirty() {
        return repository != null && repository.isDirty();
    }

    public static synchronized long revision() {
        return snapshot == null ? 0L : snapshot.revision();
    }

    /** Hides an uncommitted add without contradicting an already durable snapshot. */
    public static synchronized void rollbackFailedAddition(MachineData machineData) {
        if (repository != null && snapshot != null
                && MachineSnapshotIndex.machineIds(repository.durableSnapshot().worldsCopy())
                        .contains(machineData.id)) {
            machineData.inUse = true;
            publishRuntimeMachine(machineData);
            return;
        }
        try {
            unpublishRuntimeMachine(machineData);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.WARNING,
                    "Could not unpublish failed addition " + machineData.id,
                    exception
            );
        }
        try {
            machineData.shutdownRuntimeForRemoval();
        } catch (RuntimeException | LinkageError exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.WARNING,
                    "Could not stop failed addition runtime " + machineData.id,
                    exception
            );
        }
        try {
            machineData.removeOwnedEntities();
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.WARNING,
                    "Could not remove every entity for failed addition " + machineData.id,
                    exception
            );
        } finally {
            machineData.resetRuntimeActivation();
        }
    }

    /** Recreates the visual entities of a failed removal without publishing an interactive machine. */
    public static synchronized void stageFailedRemovalRollback(MachineData machineData) {
        unpublishRuntimeMachine(machineData);
        machineData.resetRuntimeActivation();
        machineData.ensureEntities();
    }

    static JsonObject machineToJson(MachineData machineData) {
        JsonObject json = new JsonObject();
        json.addProperty("dataVersion", DATA_VERSION);
        json.addProperty("id", machineData.id.toString());
        json.addProperty("type", SmartGambling.getMachineTypeId(machineData.machineType));
        JsonArray blocks = new JsonArray();
        for (Block block : machineData.blocks) {
            blocks.add(block.getX());
            blocks.add(block.getY());
            blocks.add(block.getZ());
        }
        json.add("blocks", blocks);
        json.addProperty("direction", machineData.direction.name());

        JsonArray entities = new JsonArray();
        List<EntityRole> roles = machineData.entityRoles();
        for (int index = 0; index < roles.size(); index++) {
            Entity entity = index < machineData.entities.length ? machineData.entities[index] : null;
            Location expected = machineData.expectedLocation(roles.get(index));
            boolean validEntity = entity != null && entity.isValid() && entity.getWorld() != null;
            UUID uuid = entity == null ? null : entity.getUniqueId();
            MachineEntityRecord.Position actualPosition = null;
            if (validEntity) {
                Location actual = entity.getLocation();
                actualPosition = position(actual);
            }
            entities.add(new MachineEntityRecord(
                    roles.get(index),
                    uuid,
                    actualPosition,
                    position(expected)
            ).toJson());
        }
        json.add("entities", entities);
        return json;
    }

    private static MachineRepository repository() {
        if (repository == null) {
            repository = new MachineRepository(DBFILE.toPath());
        }
        return repository;
    }

    private static void requireInitialized() {
        if (!initialized || repository == null || snapshot == null) {
            throw new IllegalStateException("DataManager has not been loaded");
        }
    }

    private static Block[] readBlocks(World world, JsonArray json) {
        if (json.size() < 3 || json.size() % 3 != 0) {
            throw new IllegalArgumentException("Machine blocks must contain complete x/y/z triples");
        }
        Block[] blocks = new Block[json.size() / 3];
        for (int index = 0; index < blocks.length; index++) {
            int offset = index * 3;
            blocks[index] = world.getBlockAt(
                    json.get(offset).getAsInt(),
                    json.get(offset + 1).getAsInt(),
                    json.get(offset + 2).getAsInt()
            );
        }
        return blocks;
    }

    private static List<MachineEntityRecord> readEntityRecords(
            JsonObject machineJson,
            List<EntityRole> roles,
            Machine machineType,
            Block origin,
            BlockFace direction,
            World world
    ) {
        List<MachineEntityRecord> records = new ArrayList<>();
        JsonArray raw = machineJson.has("entities") && machineJson.get("entities").isJsonArray()
                ? machineJson.getAsJsonArray("entities") : new JsonArray();
        for (int index = 0; index < raw.size(); index++) {
            JsonElement element = raw.get(index);
            if (element.isJsonObject()) {
                records.add(MachineEntityRecord.fromJson(element.getAsJsonObject()));
                continue;
            }
            if (index >= roles.size()) {
                continue;
            }
            EntityRole role = roles.get(index);
            UUID uuid = element == null || element.isJsonNull() ? null : UUID.fromString(element.getAsString());
            Location expected = MachineData.expectedLocation(machineType, origin, direction, role);
            records.add(new MachineEntityRecord(
                    role,
                    uuid,
                    uuid == null ? null : position(expected),
                    position(expected)
            ));
        }
        return records;
    }

    private static EntityResolution resolvePersistedMachine(MachineData machineData) {
        JsonObject persisted = findMachineJson(repository().workingSnapshot().worldsCopy(), machineData.id);
        if (persisted == null) {
            return null;
        }
        List<EntityRole> roles = machineData.entityRoles();
        List<MachineEntityRecord> records = readEntityRecords(
                persisted,
                roles,
                machineData.machineType,
                machineData.blocks[0],
                machineData.direction,
                machineData.blocks[0].getWorld()
        );
        int storedVersion = persisted.has("dataVersion")
                ? persisted.get("dataVersion").getAsInt() : 2;
        Set<UUID> claimedEntityIds = runtimeEntityIds(
                SmartGambling.getInstance().uuidMachines.values(),
                machineData
        );
        return resolveEntities(
                machineData.id,
                machineData.machineType,
                machineData.blocks[0],
                machineData.direction,
                roles,
                records,
                storedVersion < DATA_VERSION,
                claimedEntityIds
        );
    }

    private static JsonObject findMachineJson(JsonObject worlds, UUID machineId) {
        for (Map.Entry<String, JsonElement> worldEntry : worlds.entrySet()) {
            if (!worldEntry.getValue().isJsonArray()) {
                continue;
            }
            for (JsonElement chunkElement : worldEntry.getValue().getAsJsonArray()) {
                JsonObject chunk = chunkElement.getAsJsonObject();
                if (!chunk.has("machines") || !chunk.get("machines").isJsonArray()) {
                    continue;
                }
                for (JsonElement machineElement : chunk.getAsJsonArray("machines")) {
                    JsonObject machine = machineElement.getAsJsonObject();
                    if (machine.has("id")
                            && machineId.toString().equals(machine.get("id").getAsString())) {
                        return machine;
                    }
                }
            }
        }
        return null;
    }

    private static EntityResolution resolveEntities(
            UUID machineId,
            Machine machineType,
            Block origin,
            BlockFace direction,
            List<EntityRole> roles,
            List<MachineEntityRecord> records,
            boolean legacy,
            Set<UUID> globallyClaimedEntityIds
    ) {
        World world = origin.getWorld();
        Map<EntityRole, MachineEntityRecord> byRole = new HashMap<>();
        Set<Chunk> loadedChunks = new HashSet<>();
        for (EntityRole role : roles) {
            Location expected = MachineData.expectedLocation(machineType, origin, direction, role);
            loadedChunks.add(world.getChunkAt(expected.getBlockX() >> 4, expected.getBlockZ() >> 4));
        }
        for (MachineEntityRecord record : records) {
            if (!roles.contains(record.role())) {
                continue;
            }
            boolean actualInWorld = record.actual() != null
                    && world.getName().equalsIgnoreCase(record.actual().world());
            boolean expectedInWorld = world.getName().equalsIgnoreCase(record.expected().world());
            if (!actualInWorld && !expectedInWorld) {
                continue;
            }
            byRole.putIfAbsent(record.role(), record);
            if (actualInWorld) {
                loadedChunks.add(world.getChunkAt(
                        record.actual().chunkX(),
                        record.actual().chunkZ()
                ));
            }
            if (expectedInWorld) {
                loadedChunks.add(world.getChunkAt(
                        record.expected().chunkX(),
                        record.expected().chunkZ()
                ));
            }
        }

        Map<EntityRole, List<Entity>> tagged = new HashMap<>();
        Set<UUID> scanned = new HashSet<>();
        for (Chunk chunk : loadedChunks) {
            for (Entity entity : chunk.getEntities()) {
                if (!scanned.add(entity.getUniqueId()) || !(entity instanceof ArmorStand) || !entity.isValid()) {
                    continue;
                }
                if (!machineId.equals(MachineEntityFactory.machineId(entity))) {
                    continue;
                }
                EntityRole role = MachineEntityFactory.role(entity);
                if (role != null && roles.contains(role)) {
                    tagged.computeIfAbsent(role, ignored -> new ArrayList<>()).add(entity);
                }
            }
        }
        tagged.values().forEach(list -> list.sort(Comparator.comparing(entity -> entity.getUniqueId().toString())));

        Entity[] resolved = new Entity[roles.size()];
        List<Entity> duplicates = new ArrayList<>();
        Set<UUID> selectedIds = new HashSet<>();
        for (int index = 0; index < roles.size(); index++) {
            EntityRole role = roles.get(index);
            MachineEntityRecord record = byRole.get(role);
            Entity saved = record == null || record.uuid() == null ? null : Bukkit.getEntity(record.uuid());
            boolean savedWasClaimedElsewhere = saved != null
                    && globallyClaimedEntityIds.contains(saved.getUniqueId());
            if (!validSavedEntity(saved, machineId, role, legacy)
                    || selectedIds.contains(saved.getUniqueId())
                    || savedWasClaimedElsewhere) {
                if (savedWasClaimedElsewhere && legacy) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.YELLOW
                            + "[SmartGambling] Ambiguous v2 entity UUID " + saved.getUniqueId()
                            + " is referenced by more than one machine; "
                            + "the later claim will be replaced without deleting the shared entity.");
                }
                saved = null;
            }
            List<Entity> candidates = tagged.getOrDefault(role, List.of());
            Entity selected = saved;
            if (selected == null) {
                for (Entity candidate : candidates) {
                    UUID candidateId = candidate.getUniqueId();
                    if (!selectedIds.contains(candidateId)
                            && !globallyClaimedEntityIds.contains(candidateId)) {
                        selected = candidate;
                        break;
                    }
                }
            }
            if (selected == null && legacy) {
                selected = findUnambiguousLegacyEntity(
                        MachineData.expectedLocation(machineType, origin, direction, role),
                        selectedIds,
                        globallyClaimedEntityIds
                );
            }
            resolved[index] = selected;
            if (selected != null) {
                selectedIds.add(selected.getUniqueId());
                globallyClaimedEntityIds.add(selected.getUniqueId());
            }
            for (Entity candidate : candidates) {
                if (candidate != selected) {
                    duplicates.add(candidate);
                }
            }
        }
        return new EntityResolution(resolved, duplicates);
    }

    private static boolean validSavedEntity(Entity entity, UUID machineId, EntityRole role, boolean legacy) {
        if (!(entity instanceof ArmorStand) || !entity.isValid()) {
            return false;
        }
        UUID owner = MachineEntityFactory.machineId(entity);
        EntityRole persistedRole = MachineEntityFactory.role(entity);
        if (machineId.equals(owner) && role == persistedRole) {
            return true;
        }
        return legacy && owner == null && persistedRole == null
                && MachineEntityFactory.ENTITY_NAME.equalsIgnoreCase(entity.getCustomName());
    }

    private static void addMachineJson(JsonObject worlds, Chunk chunk, JsonObject machineJson) {
        String worldName = chunk.getWorld().getName();
        JsonArray chunks = worlds.has(worldName) && worlds.get(worldName).isJsonArray()
                ? worlds.getAsJsonArray(worldName) : new JsonArray();
        if (!worlds.has(worldName) || !worlds.get(worldName).isJsonArray()) {
            worlds.add(worldName, chunks);
        }
        for (JsonElement element : chunks) {
            JsonObject existing = element.getAsJsonObject();
            if (requiredInt(existing, "chunkX") == chunk.getX()
                    && requiredInt(existing, "chunkZ") == chunk.getZ()) {
                requiredArray(existing, "machines").add(machineJson.deepCopy());
                return;
            }
        }
        JsonObject chunkJson = new JsonObject();
        chunkJson.addProperty("chunkX", chunk.getX());
        chunkJson.addProperty("chunkZ", chunk.getZ());
        JsonArray machines = new JsonArray();
        machines.add(machineJson.deepCopy());
        chunkJson.add("machines", machines);
        chunks.add(chunkJson);
    }

    private static void removeMachineJson(JsonObject worlds, UUID machineId) {
        List<String> emptyWorlds = new ArrayList<>();
        for (Map.Entry<String, JsonElement> worldEntry : worlds.entrySet()) {
            if (!worldEntry.getValue().isJsonArray()) {
                continue;
            }
            JsonArray chunks = worldEntry.getValue().getAsJsonArray();
            for (int chunkIndex = chunks.size() - 1; chunkIndex >= 0; chunkIndex--) {
                JsonObject chunk = chunks.get(chunkIndex).getAsJsonObject();
                JsonArray machines = requiredArray(chunk, "machines");
                for (int machineIndex = machines.size() - 1; machineIndex >= 0; machineIndex--) {
                    JsonObject machine = machines.get(machineIndex).getAsJsonObject();
                    if (machine.has("id") && machineId.toString().equals(machine.get("id").getAsString())) {
                        machines.remove(machineIndex);
                    }
                }
                if (machines.isEmpty()) {
                    chunks.remove(chunkIndex);
                }
            }
            if (chunks.isEmpty()) {
                emptyWorlds.add(worldEntry.getKey());
            }
        }
        emptyWorlds.forEach(worlds::remove);
    }

    private static void publishRuntimeMachine(MachineData machineData) {
        SmartGambling plugin = SmartGambling.getInstance();
        Chunk origin = machineData.blocks[0].getChunk();
        plugin.uuidMachines.put(machineData.id, machineData);
        HashMap<Chunk, List<MachineData>> worldMachines = plugin.machines.computeIfAbsent(
                origin.getWorld(), ignored -> new HashMap<>()
        );
        List<MachineData> machines = worldMachines.computeIfAbsent(origin, ignored -> new ArrayList<>());
        if (!machines.contains(machineData)) {
            machines.add(machineData);
        }
    }

    private static void unpublishRuntimeMachine(MachineData machineData) {
        SmartGambling plugin = SmartGambling.getInstance();
        plugin.uuidMachines.remove(machineData.id, machineData);
        HashMap<Chunk, List<MachineData>> worldMachines = plugin.machines.get(machineData.blocks[0].getWorld());
        if (worldMachines == null) {
            return;
        }
        for (List<MachineData> machines : worldMachines.values()) {
            machines.remove(machineData);
        }
        worldMachines.values().removeIf(List::isEmpty);
        if (worldMachines.isEmpty()) {
            plugin.machines.remove(machineData.blocks[0].getWorld());
        }
    }

    private static MachineEntityRecord.Position position(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("Entity position needs a loaded world");
        }
        return new MachineEntityRecord.Position(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        );
    }

    private static Set<UUID> runtimeEntityIds(
            Iterable<MachineData> machines,
            MachineData excludedMachine
    ) {
        Set<UUID> ids = new HashSet<>();
        for (MachineData machine : machines) {
            if (machine == null || machine == excludedMachine || machine.entities == null) {
                continue;
            }
            for (Entity entity : machine.entities) {
                if (entity != null) {
                    ids.add(entity.getUniqueId());
                }
            }
        }
        return ids;
    }

    private static Entity findUnambiguousLegacyEntity(
            Location expected,
            Set<UUID> locallyClaimed,
            Set<UUID> globallyClaimed
    ) {
        Entity found = null;
        for (Entity candidate : expected.getWorld().getNearbyEntities(
                expected, 0.75D, 0.75D, 0.75D
        )) {
            UUID candidateId = candidate.getUniqueId();
            if (!(candidate instanceof ArmorStand)
                    || !candidate.isValid()
                    || locallyClaimed.contains(candidateId)
                    || globallyClaimed.contains(candidateId)
                    || MachineEntityFactory.machineId(candidate) != null
                    || MachineEntityFactory.role(candidate) != null
                    || !MachineEntityFactory.ENTITY_NAME.equalsIgnoreCase(candidate.getCustomName())) {
                continue;
            }
            if (found != null) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.YELLOW
                        + "[SmartGambling] Ambiguous nearby v2 entities at " + expected
                        + "; neither uncertain stand will be deleted or claimed.");
                return null;
            }
            found = candidate;
        }
        return found;
    }

    private static void activateStagedRuntimes(List<MachineData> stagedMachines) {
        List<MachineData> activated = new ArrayList<>();
        try {
            for (MachineData machineData : stagedMachines) {
                machineData.activateRuntime();
                activated.add(machineData);
            }
        } catch (RuntimeException | LinkageError activationFailure) {
            for (int index = activated.size() - 1; index >= 0; index--) {
                MachineData machineData = activated.get(index);
                try {
                    machineData.deactivateRuntime();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    activationFailure.addSuppressed(cleanupFailure);
                    SmartGambling.getInstance().getLogger().log(
                            Level.SEVERE,
                            "Could not stop staged runtime " + machineData.id
                                    + " after a later activation failed",
                            cleanupFailure
                    );
                }
            }
            throw activationFailure;
        }
    }

    private static void compensateFailedAddition(
            MachineData machineData,
            Throwable activationFailure
    ) {
        try {
            snapshot = repository().transact(worlds -> {
                removeMachineJson(worlds, machineData.id);
                return worlds;
            });
        } catch (IOException | RuntimeException compensationFailure) {
            repository().abort();
            activationFailure.addSuppressed(compensationFailure);

            // The last durable snapshot still contains this machine. Publish a
            // deliberately unavailable representation so memory and disk do
            // not diverge silently; the administrator can remove/retry it.
            machineData.inUse = true;
            try {
                machineData.deactivateRuntime();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                activationFailure.addSuppressed(cleanupFailure);
            }
            try {
                publishRuntimeMachine(machineData);
            } catch (RuntimeException publicationFailure) {
                activationFailure.addSuppressed(publicationFailure);
            }
            throw new DataStoreException(
                    "Machine " + machineData.id
                            + " became durable but runtime activation and compensation both failed; "
                            + "it is quarantined and the plugin should be restarted",
                    activationFailure
            );
        }

        cleanupDurablyRemovedMachine(machineData);
        throw new DataStoreException(
                "Could not activate machine " + machineData.id
                        + "; its durable add was compensated",
                activationFailure
        );
    }

    /** Post-commit cleanup must never make callers believe a durable removal was rolled back. */
    private static void cleanupDurablyRemovedMachine(MachineData machineData) {
        try {
            unpublishRuntimeMachine(machineData);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Machine " + machineData.id
                            + " was durably removed but could not be fully unpublished",
                    exception
            );
        }
        try {
            machineData.shutdownRuntimeForRemoval();
        } catch (RuntimeException | LinkageError exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Machine " + machineData.id
                            + " was durably removed but its runtime cleanup reported an error",
                    exception
            );
        }
        try {
            machineData.removeOwnedEntities();
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.WARNING,
                    "Machine " + machineData.id
                            + " was durably removed; a tagged orphan will be cleaned by /sg fixentities",
                    exception
            );
        }
    }

    private static boolean machineTouchesChunk(MachineData machineData, Chunk target) {
        String worldName = target.getWorld().getName();
        int chunkX = target.getX();
        int chunkZ = target.getZ();
        Block origin = machineData.blocks[0];
        if (origin.getWorld().getName().equals(worldName)
                && (origin.getX() >> 4) == chunkX
                && (origin.getZ() >> 4) == chunkZ) {
            return true;
        }
        for (EntityRole role : machineData.entityRoles()) {
            Location expected = machineData.expectedLocation(role);
            if (sameChunk(expected, worldName, chunkX, chunkZ)) {
                return true;
            }
        }
        if (machineData.entities != null) {
            for (Entity entity : machineData.entities) {
                if (entity != null && entity.isValid()
                        && sameChunk(entity.getLocation(), worldName, chunkX, chunkZ)) {
                    return true;
                }
            }
        }

        JsonObject persisted = findMachineJson(
                repository().durableSnapshot().worldsCopy(),
                machineData.id
        );
        if (persisted == null) {
            return false;
        }
        List<MachineEntityRecord> records = readEntityRecords(
                persisted,
                machineData.entityRoles(),
                machineData.machineType,
                origin,
                machineData.direction,
                origin.getWorld()
        );
        for (MachineEntityRecord record : records) {
            if (record.actual() != null
                    && record.actual().isInChunk(worldName, chunkX, chunkZ)) {
                return true;
            }
            if (record.expected().isInChunk(worldName, chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameChunk(
            Location location,
            String worldName,
            int chunkX,
            int chunkZ
    ) {
        return location != null
                && location.getWorld() != null
                && location.getWorld().getName().equals(worldName)
                && (location.getBlockX() >> 4) == chunkX
                && (location.getBlockZ() >> 4) == chunkZ;
    }

    private static void cleanupTaggedDuplicates(Chunk chunk) {
        Set<UUID> durableMachineIds = MachineSnapshotIndex.machineIds(
                repository().durableSnapshot().worldsCopy()
        );
        durableMachineIds.addAll(deferredWorlds.machineIds());
        for (Entity entity : chunk.getEntities()) {
            UUID machineId = MachineEntityFactory.machineId(entity);
            EntityRole role = MachineEntityFactory.role(entity);
            if (machineId == null || role == null) {
                continue;
            }
            MachineData machineData = SmartGambling.getInstance().uuidMachines.get(machineId);
            if (machineData == null) {
                // An unloaded/deferred machine is still durable and must never
                // be mistaken for an orphan. A tagged stand is removable only
                // when the last successfully forced snapshot proves its owner
                // no longer exists.
                if (!durableMachineIds.contains(machineId)) {
                    try {
                        entity.remove();
                    } catch (RuntimeException exception) {
                        SmartGambling.getInstance().getLogger().log(
                                Level.WARNING,
                                "Could not remove durable-snapshot orphan entity "
                                        + entity.getUniqueId(),
                                exception
                        );
                    }
                }
                continue;
            }
            int roleIndex = machineData.entityRoles().indexOf(role);
            Entity selected = roleIndex >= 0 && roleIndex < machineData.entities.length
                    ? machineData.entities[roleIndex] : null;
            if (selected == null || !selected.getUniqueId().equals(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    private static void rollbackPendingSpawns(List<MachineData> machines) {
        for (MachineData machine : machines) {
            try {
                machine.rollbackPendingEntities();
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(
                        Level.SEVERE,
                        "Could not roll back pending entities for " + machine.id,
                        exception
                );
            }
        }
    }

    private static String requiredString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing machine data property: " + key);
        }
        return json.get(key).getAsString();
    }

    private static int requiredInt(JsonObject json, String key) {
        if (!json.has(key)) {
            throw new IllegalArgumentException("Missing machine data property: " + key);
        }
        return json.get(key).getAsInt();
    }

    private static JsonArray requiredArray(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Missing machine data array: " + key);
        }
        return json.getAsJsonArray(key);
    }

    private record EntityResolution(Entity[] entities, List<Entity> duplicates) {
    }
}

/** Pure-data ownership for world JSON that cannot be materialized yet. */
final class DeferredWorldRegistry {
    private final Map<String, JsonElement> retained = new LinkedHashMap<>();

    synchronized void defer(String worldName, JsonElement worldJson) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Deferred world name cannot be blank");
        }
        if (worldJson == null) {
            throw new IllegalArgumentException("Deferred world JSON cannot be null");
        }
        retained.put(worldName, worldJson.deepCopy());
    }

    synchronized String resolve(String loadedWorldName) {
        if (loadedWorldName == null) {
            return null;
        }
        if (retained.containsKey(loadedWorldName)) {
            return loadedWorldName;
        }
        String match = null;
        for (String candidate : retained.keySet()) {
            if (!candidate.equalsIgnoreCase(loadedWorldName)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }
        return match;
    }

    synchronized JsonElement copy(String worldName) {
        JsonElement value = retained.get(worldName);
        return value == null ? null : value.deepCopy();
    }

    synchronized boolean complete(String worldName) {
        return retained.remove(worldName) != null;
    }

    synchronized Set<String> names() {
        return Set.copyOf(retained.keySet());
    }

    synchronized Set<UUID> machineIds() {
        Set<UUID> ids = new HashSet<>();
        for (JsonElement worldJson : retained.values()) {
            ids.addAll(MachineSnapshotIndex.machineIdsForWorld(worldJson));
        }
        return ids;
    }
}

/** Tolerant pure-data index used before any PDC entity can be deleted. */
final class MachineSnapshotIndex {
    private MachineSnapshotIndex() {
    }

    static Set<UUID> machineIds(JsonObject worlds) {
        Set<UUID> ids = new HashSet<>();
        if (worlds == null) {
            return ids;
        }
        for (Map.Entry<String, JsonElement> entry : worlds.entrySet()) {
            ids.addAll(machineIdsForWorld(entry.getValue()));
        }
        return ids;
    }

    static Set<UUID> machineIdsForWorld(JsonElement worldJson) {
        Set<UUID> ids = new HashSet<>();
        if (worldJson == null || !worldJson.isJsonArray()) {
            return ids;
        }
        for (JsonElement chunkElement : worldJson.getAsJsonArray()) {
            if (!chunkElement.isJsonObject()) {
                continue;
            }
            JsonObject chunk = chunkElement.getAsJsonObject();
            if (!chunk.has("machines") || !chunk.get("machines").isJsonArray()) {
                continue;
            }
            for (JsonElement machineElement : chunk.getAsJsonArray("machines")) {
                if (!machineElement.isJsonObject()) {
                    continue;
                }
                JsonObject machine = machineElement.getAsJsonObject();
                if (!machine.has("id") || machine.get("id").isJsonNull()) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(machine.get("id").getAsString()));
                } catch (IllegalArgumentException ignored) {
                    // Invalid entries cannot own a valid machine UUID. Loading
                    // still reports the malformed snapshot through its strict path.
                }
            }
        }
        return ids;
    }
}
