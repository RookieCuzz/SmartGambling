package me.arthed.smartgambling.data;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;

public class DataManager {
    private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting().create();
    private static final File DBFILE = new File(SmartGambling.getInstance().getDataFolder() + "/data.json");
    public static boolean initialized;
    private static JsonObject db;

    public static void load() {
        if (!DBFILE.exists()) {
            createConfig();
        }

        try {
            BufferedReader bufferedReader = Files.newReader(DBFILE, StandardCharsets.UTF_8);
            db = (JsonObject)GSON.fromJson(bufferedReader, JsonObject.class);
        } catch (Exception var24) {
            var24.printStackTrace();
        }

        for(Entry<String, JsonElement> entry : db.entrySet()) {
            World world = Bukkit.getWorld((String)entry.getKey());
            HashMap<Chunk, List<MachineData>> worldMachines = new HashMap();
            if (world == null) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[SmartGambling] Error loading plugin data. Theres no world called: " + (String)entry.getKey());
            } else {
                for(JsonElement element : ((JsonElement)entry.getValue()).getAsJsonArray()) {
                    JsonObject chunkObj = element.getAsJsonObject();
                    int x = chunkObj.get("chunkX").getAsInt();
                    int z = chunkObj.get("chunkZ").getAsInt();
                    Chunk chunk = world.getChunkAt(x, z);
                    List<MachineData> machines = new ArrayList();

                    for(JsonElement element2 : chunkObj.get("machines").getAsJsonArray()) {
                        JsonObject machine = element2.getAsJsonObject();
                        Machine machineType = (Machine)SmartGambling.getInstance().machineTypes.get(machine.get("type").getAsString().hashCode());
                        if (machineType == null) {
                            Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[SmartGambling] Error loading plugin data. Invalid machine type: " + machine.get("type").getAsString());
                        } else {
                            JsonArray blockJsonArray = machine.get("blocks").getAsJsonArray();
                            Block[] blocks = new Block[blockJsonArray.size() / 3];
                            int j = 0;

                            for(int i = 0; i < blockJsonArray.size(); i += 3) {
                                blocks[j] = world.getBlockAt(blockJsonArray.get(i).getAsInt(), blockJsonArray.get(i + 1).getAsInt(), blockJsonArray.get(i + 2).getAsInt());
                                ++j;
                            }

                            JsonArray entityJsonArray = machine.get("entities").getAsJsonArray();
                            Entity[] entities = new Entity[blockJsonArray.size()];

                            for(int i = 0; i < entityJsonArray.size(); ++i) {
                                entities[i] = Bukkit.getEntity(UUID.fromString(entityJsonArray.get(i).getAsString()));
                            }

                            UUID id = UUID.fromString(machine.get("id").getAsString());
                            BlockFace direction = BlockFace.valueOf(machine.get("direction").getAsString());
                            if (machineType instanceof BlackJack) {
                                machines.add(new MachineDataBlackjack(id, machineType, blocks, entities, direction));
                            } else {
                                machines.add(new MachineData(id, machineType, blocks, entities, direction));
                            }
                        }
                    }

                    for(MachineData machineData : machines) {
                        SmartGambling.getInstance().uuidMachines.put(machineData.id, machineData);
                    }

                    worldMachines.put(chunk, machines);
                }

                SmartGambling.getInstance().machines.put(world, worldMachines);
            }
        }

        initialized = true;
    }

    public static void addMachine(Chunk chunk, MachineData machineData) {
        boolean newWorld = false;
        if (!db.has(chunk.getWorld().getName())) {
            JsonArray array = new JsonArray();
            db.add(chunk.getWorld().getName(), array);
            newWorld = true;
        }

        for(JsonElement chunkElement : db.get(chunk.getWorld().getName()).getAsJsonArray()) {
            JsonObject chunkObject = chunkElement.getAsJsonObject();
            if (chunkObject.get("chunkX").getAsInt() == chunk.getX() && chunkObject.get("chunkZ").getAsInt() == chunk.getZ()) {
                JsonArray machines = chunkObject.get("machines").getAsJsonArray();
                machines.add(machineToJson(machineData));
                return;
            }
        }

        JsonObject chunkObject = new JsonObject();
        chunkObject.addProperty("chunkX", chunk.getX());
        chunkObject.addProperty("chunkZ", chunk.getZ());
        JsonArray machines = new JsonArray();
        machines.add(machineToJson(machineData));
        chunkObject.add("machines", machines);
        db.get(chunk.getWorld().getName()).getAsJsonArray().add(chunkObject);
    }

    private static JsonObject machineToJson(MachineData machineData) {
        JsonObject newMachine = new JsonObject();
        newMachine.addProperty("id", machineData.id.toString());
        newMachine.addProperty("type", SmartGambling.getMachineName(machineData.machineType));
        JsonArray blocksJson = new JsonArray();

        for(Block block : machineData.blocks) {
            blocksJson.add(block.getX());
            blocksJson.add(block.getY());
            blocksJson.add(block.getZ());
        }

        newMachine.add("blocks", blocksJson);
        JsonArray entitiesJson = new JsonArray();

        for(Entity entity : machineData.entities) {
            if (entity != null) {
                entitiesJson.add(entity.getUniqueId().toString());
            }
        }

        newMachine.addProperty("direction", machineData.direction.toString());
        newMachine.add("entities", entitiesJson);
        return newMachine;
    }

    public static void removeMachine(Chunk chunk, MachineData machineData) {
        if (db.has(chunk.getWorld().getName())) {
            JsonArray chunks = db.get(chunk.getWorld().getName()).getAsJsonArray();

            for(JsonElement chunkElement : chunks) {
                JsonObject chunkObject = chunkElement.getAsJsonObject();
                if (chunkObject.get("chunkX").getAsInt() == chunk.getX() && chunkObject.get("chunkZ").getAsInt() == chunk.getZ()) {
                    JsonArray machines = chunkObject.get("machines").getAsJsonArray();
                    if (machines == null) {
                        return;
                    }

                    for(JsonElement machineElement : machines) {
                        JsonObject machineObject = machineElement.getAsJsonObject();
                        if (machineObject.get("id").getAsString().equals(machineData.id.toString())) {
                            machines.remove(machineElement);
                            if (machines.size() == 0) {
                                chunks.remove(chunkElement);
                            }

                            return;
                        }
                    }

                    return;
                }
            }

        }
    }

    private static void createConfig() {
        db = new JsonObject();
        save();
    }

    public static void save() {
        try {
            BufferedWriter bufferedWriter = Files.newWriter(DBFILE, StandardCharsets.UTF_8);
            String json = GSON.toJson(db);
            bufferedWriter.write(json);
            bufferedWriter.close();
        } catch (Exception var2) {
            var2.printStackTrace();
        }

    }
}
 