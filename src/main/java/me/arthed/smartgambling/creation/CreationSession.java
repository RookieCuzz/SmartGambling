package me.arthed.smartgambling.creation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** Mutable, main-thread-only state for one guided machine creation. */
public final class CreationSession {
    private final UUID playerId;
    private final String machineTypeId;
    private final Machine machineType;
    private final long createdAtMillis;
    private final long timeoutMillis;
    private final LinkedHashSet<BlockPosition> interactionBlocks = new LinkedHashSet<>();
    private BlockPosition origin;
    private BlockFace direction = BlockFace.NORTH;
    private long lastActivityMillis;

    public CreationSession(
            UUID playerId,
            String machineTypeId,
            Machine machineType,
            long nowMillis,
            long timeoutMillis
    ) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.machineTypeId = Objects.requireNonNull(machineTypeId, "machineTypeId");
        this.machineType = Objects.requireNonNull(machineType, "machineType");
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.createdAtMillis = nowMillis;
        this.lastActivityMillis = nowMillis;
        this.timeoutMillis = timeoutMillis;
    }

    public UUID playerId() {
        return playerId;
    }

    public String machineTypeId() {
        return machineTypeId;
    }

    public Machine machineType() {
        return machineType;
    }

    public BlockPosition origin() {
        return origin;
    }

    public BlockFace direction() {
        return direction;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long expiresAtMillis() {
        return lastActivityMillis + timeoutMillis;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public long remainingMillis(long nowMillis) {
        return Math.max(0L, expiresAtMillis() - nowMillis);
    }

    public boolean expired(long nowMillis) {
        return nowMillis >= expiresAtMillis();
    }

    public void touch(long nowMillis) {
        lastActivityMillis = Math.max(lastActivityMillis, nowMillis);
    }

    public void setOrigin(Block block, BlockFace initialDirection, long nowMillis) {
        setOrigin(BlockPosition.of(block), initialDirection, nowMillis);
    }

    /** Pure state overload used by validation and unit tests. */
    public void setOrigin(BlockPosition next, BlockFace initialDirection, long nowMillis) {
        Objects.requireNonNull(next, "next");
        if (origin != null && !origin.equals(next)) {
            interactionBlocks.clear();
        }
        origin = next;
        interactionBlocks.remove(next);
        direction = cardinal(initialDirection);
        touch(nowMillis);
    }

    public AddResult addInteraction(Block block, int maxExtraBlocks, double maxRadius, long nowMillis) {
        return addInteraction(BlockPosition.of(block), maxExtraBlocks, maxRadius, nowMillis);
    }

    /** Pure state overload used by validation and unit tests. */
    public AddResult addInteraction(
            BlockPosition point,
            int maxExtraBlocks,
            double maxRadius,
            long nowMillis
    ) {
        Objects.requireNonNull(point, "point");
        if (maxExtraBlocks < 0) {
            throw new IllegalArgumentException("maxExtraBlocks must not be negative");
        }
        if (!Double.isFinite(maxRadius) || maxRadius < 0.0D) {
            throw new IllegalArgumentException("maxRadius must be finite and non-negative");
        }
        if (origin == null) {
            return AddResult.NO_ORIGIN;
        }
        if (!origin.worldId().equals(point.worldId())) {
            return AddResult.OTHER_WORLD;
        }
        if (origin.equals(point) || interactionBlocks.contains(point)) {
            return AddResult.ALREADY_SELECTED;
        }
        if (interactionBlocks.size() >= maxExtraBlocks) {
            return AddResult.LIMIT_REACHED;
        }
        if (origin.distanceSquared(point) > maxRadius * maxRadius) {
            return AddResult.TOO_FAR;
        }
        interactionBlocks.add(point);
        touch(nowMillis);
        return AddResult.ADDED;
    }

    public boolean removeInteraction(Block block, long nowMillis) {
        return removeInteraction(BlockPosition.of(block), nowMillis);
    }

    /** Pure state overload used by validation and unit tests. */
    public boolean removeInteraction(BlockPosition position, long nowMillis) {
        boolean removed = interactionBlocks.remove(Objects.requireNonNull(position, "position"));
        if (removed) {
            touch(nowMillis);
        }
        return removed;
    }

    public void rotate(boolean left, long nowMillis) {
        direction = switch (direction) {
            case NORTH -> left ? BlockFace.WEST : BlockFace.EAST;
            case EAST -> left ? BlockFace.NORTH : BlockFace.SOUTH;
            case SOUTH -> left ? BlockFace.EAST : BlockFace.WEST;
            case WEST -> left ? BlockFace.SOUTH : BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };
        touch(nowMillis);
    }

    public Set<BlockPosition> interactionPositions() {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(interactionBlocks));
    }

    public int interactionCount() {
        return interactionBlocks.size();
    }

    public int totalBlockCount() {
        return origin == null ? 0 : interactionBlocks.size() + 1;
    }

    /** Origin is always the first and is itself an interaction entry at runtime. */
    public List<Block> resolveBlocks() {
        if (origin == null) {
            return List.of();
        }
        List<Block> blocks = new ArrayList<>(interactionBlocks.size() + 1);
        blocks.add(origin.resolve());
        for (BlockPosition position : interactionBlocks) {
            blocks.add(position.resolve());
        }
        return List.copyOf(blocks);
    }

    private static BlockFace cardinal(BlockFace face) {
        return switch (face) {
            case NORTH, EAST, SOUTH, WEST -> face;
            default -> BlockFace.NORTH;
        };
    }

    public enum AddResult {
        ADDED,
        NO_ORIGIN,
        OTHER_WORLD,
        ALREADY_SELECTED,
        LIMIT_REACHED,
        TOO_FAR
    }
}
