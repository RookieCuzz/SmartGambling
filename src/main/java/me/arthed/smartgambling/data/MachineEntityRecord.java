package me.arthed.smartgambling.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.UUID;

/** Bukkit-independent v3 representation of one persisted machine entity. */
public record MachineEntityRecord(
        EntityRole role,
        UUID uuid,
        Position actual,
        Position expected
) {
    public MachineEntityRecord {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(expected, "expected");
    }

    /**
     * Source-compatible constructor for the first v3 format. That format had
     * only one flat position, so it is conservatively treated as both the
     * observed and expected position until the next canonical save.
     */
    public MachineEntityRecord(
            EntityRole role,
            UUID uuid,
            String world,
            double x,
            double y,
            double z,
            int chunkX,
            int chunkZ
    ) {
        this(role, uuid, new Position(world, x, y, z, chunkX, chunkZ),
                new Position(world, x, y, z, chunkX, chunkZ));
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("role", role.name());
        if (uuid == null) {
            json.add("uuid", JsonNull.INSTANCE);
        } else {
            json.addProperty("uuid", uuid.toString());
        }
        json.add("actual", actual == null ? JsonNull.INSTANCE : actual.toJson());
        json.add("expected", expected.toJson());
        return json;
    }

    public static MachineEntityRecord fromJson(JsonObject json) {
        EntityRole role = EntityRole.parse(requiredString(json, "role"));
        if (role == null) {
            throw new IllegalArgumentException("Invalid entity role: " + json.get("role"));
        }
        JsonElement rawUuid = json.get("uuid");
        UUID uuid = rawUuid == null || rawUuid.isJsonNull()
                ? null
                : UUID.fromString(rawUuid.getAsString());

        // The initial v3 implementation wrote one flat position. Read it as a
        // compatibility fallback, then rewrite it into explicit actual and
        // expected objects on the next repository transaction.
        Position flat = hasFlatPosition(json) ? Position.fromJson(json) : null;
        Position actual = readPosition(json, "actual", uuid == null ? null : flat);
        Position expected = readPosition(json, "expected", flat);
        if (expected == null) {
            throw new IllegalArgumentException("Missing entity property: expected");
        }
        return new MachineEntityRecord(role, uuid, actual, expected);
    }

    /** Compatibility accessors for code/tests written against the first v3 record. */
    public String world() {
        return (actual == null ? expected : actual).world();
    }

    public double x() {
        return (actual == null ? expected : actual).x();
    }

    public double y() {
        return (actual == null ? expected : actual).y();
    }

    public double z() {
        return (actual == null ? expected : actual).z();
    }

    public int chunkX() {
        return (actual == null ? expected : actual).chunkX();
    }

    public int chunkZ() {
        return (actual == null ? expected : actual).chunkZ();
    }

    private static Position readPosition(JsonObject json, String key, Position fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : Position.fromJson(value.getAsJsonObject());
    }

    private static boolean hasFlatPosition(JsonObject json) {
        return json.has("world") && json.has("x") && json.has("y") && json.has("z");
    }

    private static String requiredString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing entity property: " + key);
        }
        return json.get(key).getAsString();
    }

    /** Exact world position plus the chunk that must be preloaded to resolve it. */
    public record Position(
            String world,
            double x,
            double y,
            double z,
            int chunkX,
            int chunkZ
    ) {
        public Position {
            Objects.requireNonNull(world, "world");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Entity coordinates must be finite");
            }
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("world", world);
            json.addProperty("x", x);
            json.addProperty("y", y);
            json.addProperty("z", z);
            json.addProperty("chunkX", chunkX);
            json.addProperty("chunkZ", chunkZ);
            return json;
        }

        public static Position fromJson(JsonObject json) {
            String world = requiredString(json, "world");
            double x = requiredDouble(json, "x");
            double y = requiredDouble(json, "y");
            double z = requiredDouble(json, "z");
            int chunkX = json.has("chunkX") ? json.get("chunkX").getAsInt() : floorToChunk(x);
            int chunkZ = json.has("chunkZ") ? json.get("chunkZ").getAsInt() : floorToChunk(z);
            return new Position(world, x, y, z, chunkX, chunkZ);
        }

        public boolean isInChunk(String worldName, int candidateChunkX, int candidateChunkZ) {
            return world.equalsIgnoreCase(worldName)
                    && chunkX == candidateChunkX
                    && chunkZ == candidateChunkZ;
        }

        private static double requiredDouble(JsonObject json, String key) {
            if (!json.has(key)) {
                throw new IllegalArgumentException("Missing entity property: " + key);
            }
            double value = json.get(key).getAsDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite entity property: " + key);
            }
            return value;
        }

        private static int floorToChunk(double coordinate) {
            return Math.floorDiv((int) Math.floor(coordinate), 16);
        }
    }
}
