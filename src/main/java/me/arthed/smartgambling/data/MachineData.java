package me.arthed.smartgambling.data;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.poker.Poker;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/** Runtime data and entity ownership for one physical machine. */
public class MachineData {
    public final UUID id;
    /**
     * Crash is deliberately replaced by a per-machine clone only from
     * {@link #activateRuntime()}, after the data transaction commits.
     */
    public Machine machineType;
    public final Block[] blocks;
    public Entity[] entities;
    public boolean inUse;
    public final BlockFace direction;

    private final UUID entityTransactionId = UUID.randomUUID();
    private final Set<UUID> spawnedPendingEntities = new HashSet<>();
    private boolean entityLayoutChanged;
    private boolean runtimeActive;

    public MachineData(UUID id, Machine machineType, Block[] blocks, Entity[] entities, BlockFace direction) {
        this(id, machineType, blocks, entities, direction, false);
    }

    public MachineData(
            UUID id,
            Machine machineType,
            Block[] blocks,
            Entity[] entities,
            BlockFace direction,
            boolean allowLegacyDiscovery
    ) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.machineType = java.util.Objects.requireNonNull(machineType, "machineType");
        this.blocks = java.util.Objects.requireNonNull(blocks, "blocks");
        this.direction = java.util.Objects.requireNonNull(direction, "direction");
        if (blocks.length == 0 || blocks[0] == null || blocks[0].getWorld() == null) {
            throw new IllegalArgumentException("A machine needs an origin block in a loaded world");
        }

        int expectedCount = entityRoles().size();
        this.entities = new Entity[expectedCount];
        if (entities != null) {
            System.arraycopy(entities, 0, this.entities, 0, Math.min(entities.length, expectedCount));
            if (entities.length != expectedCount) {
                this.entityLayoutChanged = true;
            }
        } else {
            this.entityLayoutChanged = true;
        }
        try {
            ensureEntities(allowLegacyDiscovery);
        } catch (RuntimeException exception) {
            rollbackPendingEntities();
            throw exception;
        }
    }

    public synchronized boolean ensureEntities() {
        return ensureEntities(false);
    }

    /** Ensures one valid ArmorStand for every stable role without deleting unrelated entities. */
    public synchronized boolean ensureEntities(boolean allowLegacyDiscovery) {
        List<EntityRole> roles = entityRoles();
        if (entities == null || entities.length != roles.size()) {
            Entity[] old = entities;
            entities = new Entity[roles.size()];
            if (old != null) {
                System.arraycopy(old, 0, entities, 0, Math.min(old.length, entities.length));
            }
            entityLayoutChanged = true;
        }

        Set<UUID> claimed = new HashSet<>();
        for (int index = 0; index < roles.size(); index++) {
            EntityRole role = roles.get(index);
            Entity current = entities[index];
            boolean acceptable = isAcceptable(current, role)
                    && claimed.add(current.getUniqueId());
            if (!acceptable) {
                current = findTaggedEntity(role, claimed);
                if (current == null && allowLegacyDiscovery) {
                    current = findLegacyEntity(role, claimed);
                }
                if (current == null) {
                    current = spawnEntity(role);
                    spawnedPendingEntities.add(current.getUniqueId());
                }
                entities[index] = current;
                claimed.add(current.getUniqueId());
                entityLayoutChanged = true;
            }
        }
        return entityLayoutChanged;
    }

    /** Backwards-compatible spelling retained for existing callers. */
    public void creteEntities() {
        removeOwnedEntities();
        ensureEntities(false);
    }

    public synchronized void replaceEntities(Entity[] replacement) {
        List<EntityRole> roles = entityRoles();
        Entity[] normalized = new Entity[roles.size()];
        if (replacement != null) {
            System.arraycopy(replacement, 0, normalized, 0, Math.min(replacement.length, normalized.length));
        }
        this.entities = normalized;
        this.entityLayoutChanged = true;
        ensureEntities(false);
    }

    /** Captures the exact pre-repair references so a failed candidate can be undone. */
    synchronized EntityMutationCheckpoint checkpointEntityMutation() {
        return new EntityMutationCheckpoint(
                entities == null ? null : entities.clone(),
                Set.copyOf(spawnedPendingEntities),
                entityLayoutChanged
        );
    }

    /**
     * Removes only entities spawned after {@code checkpoint}, then restores the
     * original entity array and mutation flags without running discovery again.
     */
    synchronized void rollbackEntityMutation(EntityMutationCheckpoint checkpoint) {
        java.util.Objects.requireNonNull(checkpoint, "checkpoint");
        Set<UUID> newlyPending = new HashSet<>(spawnedPendingEntities);
        newlyPending.removeAll(checkpoint.pendingEntityIds());
        if (entities != null) {
            for (Entity entity : entities) {
                if (entity != null
                        && newlyPending.contains(entity.getUniqueId())
                        && MachineEntityFactory.isPendingFrom(entity, entityTransactionId)) {
                    try {
                        entity.remove();
                    } catch (RuntimeException ignored) {
                        // The references are still restored below. A tagged
                        // PENDING stand can be identified safely on the next repair.
                    }
                }
            }
        }
        spawnedPendingEntities.clear();
        spawnedPendingEntities.addAll(checkpoint.pendingEntityIds());
        entities = checkpoint.entityReferences() == null
                ? null
                : checkpoint.entityReferences().clone();
        entityLayoutChanged = checkpoint.layoutChanged();
    }

    /** Validates known activation prerequisites before a durable add is attempted. */
    public synchronized void validateRuntimeActivation() {
        if (!runtimeActive && machineType instanceof CrashMachine
                && SmartGambling.getInstance().crashMachine == null) {
            throw new IllegalStateException("Crash template is unavailable");
        }
    }

    /**
     * Refreshes only the visual presentation of currently selected entities.
     * It does not clone/activate Crash, discover or spawn entities, or mutate
     * the persisted entity layout.
     */
    public synchronized void refreshEntityPresentation() {
        if (entities == null) {
            return;
        }
        List<EntityRole> roles = entityRoles();
        for (int index = 0; index < roles.size(); index++) {
            Entity entity = index < entities.length ? entities[index] : null;
            if (entity instanceof ArmorStand armorStand && entity.isValid()) {
                EntityRole role = roles.get(index);
                MachineEntityFactory.align(
                        armorStand,
                        expectedLocation(role),
                        direction,
                        role,
                        itemForRole(role)
                );
                MachineEntityFactory.tag(
                        entity,
                        id,
                        role,
                        MachineEntityFactory.STATE_ACTIVE,
                        entityTransactionId
                );
            }
        }
    }

    /** Publishes PDC ownership and starts per-machine Crash runtime only after persistence succeeds. */
    public synchronized void activateRuntime() {
        validateRuntimeActivation();
        refreshEntityPresentation();
        spawnedPendingEntities.clear();
        entityLayoutChanged = false;

        if (!runtimeActive && machineType instanceof CrashMachine) {
            CrashMachine template = SmartGambling.getInstance().crashMachine;
            CrashMachine runtime = template.clone();
            runtime.bindMachineId(this.id);
            machineType = runtime;
            try {
                runtime.activate();
            } catch (RuntimeException | LinkageError exception) {
                try {
                    runtime.shutdownAndRefund();
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                machineType = template;
                runtimeActive = false;
                throw exception;
            }
        }
        runtimeActive = true;
    }

    /** Stops an activated per-machine runtime when a staged publication is rolled back or removed. */
    public synchronized void deactivateRuntime() {
        if (machineType instanceof CrashMachine crashMachine
                && machineType != SmartGambling.getInstance().crashMachine) {
            try {
                crashMachine.shutdownAndRefund();
            } finally {
                if (SmartGambling.getInstance().crashMachine != null) {
                    machineType = SmartGambling.getInstance().crashMachine;
                }
                runtimeActive = false;
            }
            return;
        }
        runtimeActive = false;
    }

    /** Stops a durably removed runtime while retaining its identity for idempotent caller cleanup. */
    public synchronized void shutdownRuntimeForRemoval() {
        try {
            if (machineType instanceof CrashMachine crashMachine
                    && machineType != SmartGambling.getInstance().crashMachine) {
                crashMachine.shutdownAndRefund();
            }
        } finally {
            runtimeActive = false;
        }
    }

    /** Makes a failed removal safely activatable again without reusing a shut down Crash clone. */
    public synchronized void resetRuntimeActivation() {
        if (machineType instanceof CrashMachine && SmartGambling.getInstance().crashMachine != null) {
            machineType = SmartGambling.getInstance().crashMachine;
        }
        runtimeActive = false;
    }

    public synchronized boolean isRuntimeActive() {
        return runtimeActive;
    }

    public synchronized boolean entityLayoutChanged() {
        return entityLayoutChanged;
    }

    public synchronized void rollbackPendingEntities() {
        for (Entity entity : entities == null ? new Entity[0] : entities) {
            if (entity != null && spawnedPendingEntities.contains(entity.getUniqueId())
                    && MachineEntityFactory.isPendingFrom(entity, entityTransactionId)) {
                entity.remove();
            }
        }
        spawnedPendingEntities.clear();
        if (entities != null) {
            Arrays.setAll(entities, index -> entities[index] != null && entities[index].isValid()
                    ? entities[index] : null);
        }
    }

    public synchronized void removeOwnedEntities() {
        if (entities == null) {
            return;
        }
        List<EntityRole> roles = entityRoles();
        for (int index = 0; index < entities.length; index++) {
            Entity entity = entities[index];
            if (entity == null) {
                continue;
            }
            UUID owner = MachineEntityFactory.machineId(entity);
            EntityRole persistedRole = MachineEntityFactory.role(entity);
            boolean owned = id.equals(owner)
                    && index < roles.size()
                    && roles.get(index) == persistedRole;
            boolean newlyCreatedLegacy = owner == null
                    && MachineEntityFactory.ENTITY_NAME.equalsIgnoreCase(entity.getCustomName());
            if (owned || newlyCreatedLegacy) {
                entity.remove();
            }
            entities[index] = null;
        }
        entityLayoutChanged = true;
    }

    public List<EntityRole> entityRoles() {
        return EntityRole.forMachine(machineType);
    }

    public Location expectedLocation(EntityRole role) {
        return expectedLocation(machineType, blocks[0], direction, role);
    }

    public static Location expectedLocation(
            Machine machineType,
            Block origin,
            BlockFace direction,
            EntityRole role
    ) {
        return switch (role) {
            case MODEL -> MachineEntityFactory.relativeLocation(
                    origin, machineType.getMachineEntityOffset(), direction
            );
            case PRIMARY_SEAT -> MachineEntityFactory.relativeLocation(
                    origin, SmartGambling.getInstance().chairOffset, direction
            );
            case BLACKJACK_HOST_SEAT -> blackjackSeatLocation(
                    machineType, origin, direction, true
            );
            case BLACKJACK_CHALLENGER_SEAT -> blackjackSeatLocation(
                    machineType, origin, direction, false
            );
            case POKER_HOST_SEAT -> pokerSeatLocation(
                    machineType, origin, direction, true
            );
            case POKER_CHALLENGER_SEAT -> pokerSeatLocation(
                    machineType, origin, direction, false
            );
        };
    }

    public Chunk expectedChunk(EntityRole role) {
        Location location = expectedLocation(role);
        return location.getWorld().getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private boolean isAcceptable(Entity entity, EntityRole expectedRole) {
        if (!(entity instanceof ArmorStand) || !entity.isValid()) {
            return false;
        }
        UUID owner = MachineEntityFactory.machineId(entity);
        EntityRole role = MachineEntityFactory.role(entity);
        if (owner == null && role == null) {
            return true; // A v2 entity referenced by its saved UUID.
        }
        return id.equals(owner) && expectedRole == role;
    }

    private Entity findTaggedEntity(EntityRole expectedRole, Set<UUID> claimed) {
        Chunk chunk = expectedChunk(expectedRole);
        for (Entity candidate : chunk.getEntities()) {
            if (candidate instanceof ArmorStand
                    && candidate.isValid()
                    && !claimed.contains(candidate.getUniqueId())
                    && MachineEntityFactory.belongsTo(candidate, id, expectedRole)) {
                return candidate;
            }
        }
        return null;
    }

    private Entity findLegacyEntity(EntityRole role, Set<UUID> claimed) {
        Location expected = expectedLocation(role);
        Entity found = null;
        for (Entity candidate : expected.getWorld().getNearbyEntities(expected, 0.75D, 0.75D, 0.75D)) {
            if (!(candidate instanceof ArmorStand) || !candidate.isValid()
                    || claimed.contains(candidate.getUniqueId())
                    || MachineEntityFactory.machineId(candidate) != null
                    || !MachineEntityFactory.ENTITY_NAME.equalsIgnoreCase(candidate.getCustomName())) {
                continue;
            }
            if (found != null) {
                return null; // Ambiguous legacy ownership: spawn rather than stealing either stand.
            }
            found = candidate;
        }
        return found;
    }

    private Entity spawnEntity(EntityRole role) {
        Block origin = blocks[0];
        return switch (role) {
            case MODEL -> MachineEntityFactory.spawnTaggedModel(
                    origin,
                    machineType.getMachineEntityOffset(),
                    direction,
                    machineType.getMachineItem(),
                    id,
                    role,
                    entityTransactionId
            );
            case PRIMARY_SEAT -> MachineEntityFactory.spawnTaggedSeat(
                    origin,
                    SmartGambling.getInstance().chairOffset,
                    direction,
                    SmartGambling.getInstance().chairItem,
                    id,
                    role,
                    entityTransactionId
            );
            case BLACKJACK_HOST_SEAT, BLACKJACK_CHALLENGER_SEAT,
                    POKER_HOST_SEAT, POKER_CHALLENGER_SEAT -> MachineEntityFactory.spawnTaggedSeat(
                    expectedLocation(role),
                    direction,
                    SmartGambling.getInstance().chairItem,
                    id,
                    role,
                    entityTransactionId
            );
        };
    }

    private ItemStack itemForRole(EntityRole role) {
        return role == EntityRole.MODEL
                ? machineType.getMachineItem()
                : SmartGambling.getInstance().chairItem;
    }

    private static Location blackjackSeatLocation(
            Machine machineType,
            Block origin,
            BlockFace direction,
            boolean host
    ) {
        if (!(machineType instanceof BlackJack blackJack)) {
            throw new IllegalStateException("Blackjack seat requested for a non-blackjack machine");
        }
        double[] configured = host ? blackJack.chair1Offset : blackJack.chair2Offset;
        double[] offset = orientedBlackjackOffset(configured, direction);
        return origin.getLocation().add(0.5D, 0.0D, 0.5D)
                .add(offset[0], offset[1], offset[2]);
    }

    private static Location pokerSeatLocation(
            Machine machineType,
            Block origin,
            BlockFace direction,
            boolean host
    ) {
        if (!(machineType instanceof Poker poker)) {
            throw new IllegalStateException("Poker seat requested for a non-poker machine");
        }
        double[] configured = host ? poker.chair1Offset : poker.chair2Offset;
        double[] offset = orientedBlackjackOffset(configured, direction);
        return origin.getLocation().add(0.5D, 0.0D, 0.5D)
                .add(offset[0], offset[1], offset[2]);
    }

    public static double[] orientedBlackjackOffset(double[] configuredOffset, BlockFace direction) {
        if (configuredOffset == null || configuredOffset.length < 3) {
            throw new IllegalArgumentException("Blackjack chair offset must contain x, y and z");
        }
        double x = configuredOffset[0];
        double y = configuredOffset[1];
        double z = configuredOffset[2];
        return switch (direction) {
            case SOUTH -> new double[]{x, y, z};
            case NORTH -> new double[]{-x, y, -z};
            case EAST -> new double[]{z, y, -x};
            case WEST -> new double[]{-z, y, x};
            default -> new double[]{x, y, z};
        };
    }

    static final class EntityMutationCheckpoint {
        private final Entity[] entityReferences;
        private final Set<UUID> pendingEntityIds;
        private final boolean layoutChanged;

        private EntityMutationCheckpoint(
                Entity[] entityReferences,
                Set<UUID> pendingEntityIds,
                boolean layoutChanged
        ) {
            this.entityReferences = entityReferences;
            this.pendingEntityIds = pendingEntityIds;
            this.layoutChanged = layoutChanged;
        }

        Entity[] entityReferences() {
            return entityReferences;
        }

        Set<UUID> pendingEntityIds() {
            return pendingEntityIds;
        }

        boolean layoutChanged() {
            return layoutChanged;
        }
    }
}
