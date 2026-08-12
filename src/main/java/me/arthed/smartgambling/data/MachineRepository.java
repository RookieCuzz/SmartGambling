package me.arthed.smartgambling.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Copy-on-write, revisioned storage for data.json.
 *
 * <p>The durable snapshot is never replaced in memory until the atomic file
 * replacement succeeds. A failed candidate remains dirty so {@link #flush()}
 * can retry the exact desired state.</p>
 */
public final class MachineRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 3;

    private final Path target;
    private final Path temporary;
    private final Path backup;
    private final Path backupTemporary;
    private Snapshot durable;
    private Snapshot working;
    private boolean dirty;

    public MachineRepository(Path target) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath();
        this.temporary = this.target.resolveSibling(this.target.getFileName() + ".tmp");
        this.backup = this.target.resolveSibling(this.target.getFileName() + ".bak");
        this.backupTemporary = this.target.resolveSibling(this.target.getFileName() + ".bak.tmp");
    }

    public synchronized Snapshot load() throws IOException {
        Snapshot loaded;
        boolean recoveredFromBackup = false;
        if (!Files.exists(target)) {
            loaded = Snapshot.empty();
        } else {
            try {
                loaded = readSnapshot(target);
            } catch (IOException | RuntimeException primaryFailure) {
                if (!Files.exists(backup)) {
                    throw new IOException("Could not read " + target + " and no valid backup exists", primaryFailure);
                }
                try {
                    loaded = readSnapshot(backup);
                    recoveredFromBackup = true;
                } catch (IOException | RuntimeException backupFailure) {
                    primaryFailure.addSuppressed(backupFailure);
                    throw new IOException("Neither " + target + " nor " + backup + " is readable", primaryFailure);
                }
            }
        }
        this.durable = loaded;
        this.working = recoveredFromBackup
                ? new Snapshot(SCHEMA_VERSION, loaded.revision() + 1L, loaded.worldsCopy())
                : loaded;
        this.dirty = recoveredFromBackup;
        return this.working.copy();
    }

    public synchronized Snapshot transact(UnaryOperator<JsonObject> mutation) throws IOException {
        ensureLoaded();
        JsonObject candidateWorlds = this.working.worldsCopy();
        JsonObject mutated = Objects.requireNonNull(mutation.apply(candidateWorlds), "mutation result");
        this.working = new Snapshot(SCHEMA_VERSION, this.working.revision() + 1L, mutated);
        this.dirty = true;
        flush();
        return this.durable.copy();
    }

    public synchronized boolean flush() throws IOException {
        ensureLoaded();
        if (!dirty) {
            return false;
        }
        writeSnapshot(working);
        this.durable = this.working;
        this.dirty = false;
        return true;
    }

    /**
     * Explicitly abandons a failed logical mutation.
     *
     * <p>{@link #transact(UnaryOperator)} deliberately leaves its candidate
     * dirty when the physical write fails. Higher layers that also roll back
     * the corresponding runtime operation must call this method so a later,
     * unrelated {@link #flush()} cannot commit that abandoned candidate.</p>
     */
    public synchronized boolean abort() {
        ensureLoaded();
        if (!dirty) {
            return false;
        }
        this.working = this.durable.copy();
        this.dirty = false;
        return true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized Snapshot durableSnapshot() {
        ensureLoaded();
        return durable.copy();
    }

    public synchronized Snapshot workingSnapshot() {
        ensureLoaded();
        return working.copy();
    }

    private void ensureLoaded() {
        if (durable == null || working == null) {
            throw new IllegalStateException("MachineRepository.load() must be called first");
        }
    }

    private Snapshot readSnapshot(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (RuntimeException exception) {
            throw new IOException("Malformed JSON in " + path, exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Machine data root is not an object in " + path);
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("worlds")) {
            if (!root.get("worlds").isJsonObject()) {
                throw new IOException("Machine data worlds property is not an object in " + path);
            }
            int schema = root.has("dataVersion") ? root.get("dataVersion").getAsInt() : SCHEMA_VERSION;
            long revision = root.has("revision") ? root.get("revision").getAsLong() : 0L;
            if (revision < 0L) {
                throw new IOException("Machine data revision cannot be negative in " + path);
            }
            return new Snapshot(schema, revision, root.getAsJsonObject("worlds"));
        }

        // v1/v2 stored world names directly at the root.
        return new Snapshot(2, 0L, root);
    }

    private void writeSnapshot(Snapshot snapshot) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = GSON.toJson(snapshot.toJson()).getBytes(StandardCharsets.UTF_8);
        writeForced(temporary, bytes);

        if (Files.exists(target) && isReadableSnapshot(target)) {
            Files.copy(target, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
            forceExisting(backupTemporary);
            moveReplace(backupTemporary, backup);
        }

        moveReplace(temporary, target);
        forceDirectory(parent);
    }

    private boolean isReadableSnapshot(Path path) {
        try {
            readSnapshot(path);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void forceExisting(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not supported by every Windows/JVM file system.
        }
    }

    public static final class Snapshot {
        private final int dataVersion;
        private final long revision;
        private final JsonObject worlds;

        public Snapshot(int dataVersion, long revision, JsonObject worlds) {
            this.dataVersion = dataVersion;
            this.revision = revision;
            this.worlds = Objects.requireNonNull(worlds, "worlds").deepCopy();
        }

        public static Snapshot empty() {
            return new Snapshot(SCHEMA_VERSION, 0L, new JsonObject());
        }

        public int dataVersion() {
            return dataVersion;
        }

        public long revision() {
            return revision;
        }

        public JsonObject worldsCopy() {
            return worlds.deepCopy();
        }

        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("dataVersion", SCHEMA_VERSION);
            root.addProperty("revision", revision);
            root.add("worlds", worlds.deepCopy());
            return root;
        }

        public Snapshot copy() {
            return new Snapshot(dataVersion, revision, worlds);
        }
    }
}
