package me.arthed.smartgambling.creation;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Immutable block identity used by an administrator creation session. */
public record BlockPosition(UUID worldId, String worldName, int x, int y, int z) {
    public BlockPosition {
        Objects.requireNonNull(worldId, "worldId");
        worldName = Objects.requireNonNull(worldName, "worldName");
    }

    public static BlockPosition of(Block block) {
        Objects.requireNonNull(block, "block");
        if (block.getWorld() == null) {
            throw new IllegalArgumentException("block has no world");
        }
        return new BlockPosition(
                block.getWorld().getUID(),
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()
        );
    }

    public Block resolve() {
        requirePrimaryThread();
        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            throw new IllegalStateException("World is not loaded: " + worldName);
        }
        return world.getBlockAt(x, y, z);
    }

    public double distanceSquared(BlockPosition other) {
        Objects.requireNonNull(other, "other");
        if (!worldId.equals(other.worldId)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = (double) x - other.x;
        double dy = (double) y - other.y;
        double dz = (double) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean sameWorld(BlockPosition other) {
        return other != null && worldId.equals(other.worldId);
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit world access must run on the primary thread");
        }
    }
}
