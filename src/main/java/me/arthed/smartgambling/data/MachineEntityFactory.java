package me.arthed.smartgambling.data;

import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Creates the persistent ArmorStands used for SmartGambling seats and CE models. */
public final class MachineEntityFactory {
    public static final String ENTITY_NAME = "SMARTGAMBLING_MACHINE";
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String MACHINE_ID_KEY = "machine_id";
    public static final String ENTITY_ROLE_KEY = "entity_role";
    public static final String DATA_VERSION_KEY = "data_version";
    public static final String ENTITY_STATE_KEY = "entity_state";
    public static final String TRANSACTION_ID_KEY = "transaction_id";

    private MachineEntityFactory() {
    }

    public static ArmorStand spawnModel(Block origin, double[] offset, BlockFace direction, ItemStack model) {
        Location location = relativeLocation(origin, offset, direction);
        ArmorStand armorStand = spawnBase(location, modelYaw(direction));
        if (model != null) {
            armorStand.getEquipment().setHelmet(model.clone());
        }
        return armorStand;
    }

    public static ArmorStand spawnTaggedModel(
            Block origin,
            double[] offset,
            BlockFace direction,
            ItemStack model,
            UUID machineId,
            EntityRole role,
            UUID transactionId
    ) {
        ArmorStand armorStand = spawnModel(origin, offset, direction, model);
        tag(armorStand, machineId, role, STATE_PENDING, transactionId);
        return armorStand;
    }

    public static ArmorStand spawnSeat(Block origin, double[] offset, BlockFace direction, ItemStack model) {
        ArmorStand armorStand = spawnBase(relativeLocation(origin, offset, direction), seatYaw(direction));
        equipModel(armorStand, model);
        return armorStand;
    }

    public static ArmorStand spawnTaggedSeat(
            Block origin,
            double[] offset,
            BlockFace direction,
            ItemStack model,
            UUID machineId,
            EntityRole role,
            UUID transactionId
    ) {
        ArmorStand armorStand = spawnSeat(origin, offset, direction, model);
        armorStand.setRotation(yawForRole(direction, role), 0.0F);
        tag(armorStand, machineId, role, STATE_PENDING, transactionId);
        return armorStand;
    }

    public static ArmorStand spawnSeat(Location location, BlockFace direction, ItemStack model) {
        ArmorStand armorStand = spawnBase(location, seatYaw(direction));
        equipModel(armorStand, model);
        return armorStand;
    }

    public static ArmorStand spawnTaggedSeat(
            Location location,
            BlockFace direction,
            ItemStack model,
            UUID machineId,
            EntityRole role,
            UUID transactionId
    ) {
        ArmorStand armorStand = spawnSeat(location, direction, model);
        armorStand.setRotation(yawForRole(direction, role), 0.0F);
        tag(armorStand, machineId, role, STATE_PENDING, transactionId);
        return armorStand;
    }

    public static void equipModel(ArmorStand armorStand, ItemStack model) {
        if (model != null) {
            armorStand.getEquipment().setHelmet(model.clone());
        }
    }

    public static void align(
            ArmorStand armorStand,
            Location expected,
            BlockFace direction,
            EntityRole role,
            ItemStack model
    ) {
        armorStand.teleport(expected);
        armorStand.setInvisible(true);
        armorStand.setInvulnerable(true);
        armorStand.setGravity(false);
        armorStand.setPersistent(true);
        armorStand.setRotation(yawForRole(direction, role), 0.0F);
        if (model != null) {
            armorStand.getEquipment().setHelmet(model.clone());
        }
    }

    public static ArmorStand spawnBase(Location location, BlockFace direction) {
        return spawnBase(location, modelYaw(direction));
    }

    private static ArmorStand spawnBase(Location location, float yaw) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Cannot spawn a machine entity without a world");
        }
        ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        armorStand.setInvisible(true);
        armorStand.setInvulnerable(true);
        armorStand.setGravity(false);
        armorStand.setPersistent(true);
        armorStand.setCustomName(ENTITY_NAME);
        armorStand.setRotation(yaw, 0.0F);
        return armorStand;
    }

    public static void tag(
            Entity entity,
            UUID machineId,
            EntityRole role,
            String state,
            UUID transactionId
    ) {
        if (entity == null || machineId == null || role == null) {
            throw new IllegalArgumentException("Machine entity ownership requires entity, machine id and role");
        }
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(key(MACHINE_ID_KEY), PersistentDataType.STRING, machineId.toString());
        data.set(key(ENTITY_ROLE_KEY), PersistentDataType.STRING, role.name());
        data.set(key(DATA_VERSION_KEY), PersistentDataType.INTEGER, DataManager.DATA_VERSION);
        data.set(key(ENTITY_STATE_KEY), PersistentDataType.STRING,
                state == null ? STATE_ACTIVE : state);
        if (transactionId == null) {
            data.remove(key(TRANSACTION_ID_KEY));
        } else {
            data.set(key(TRANSACTION_ID_KEY), PersistentDataType.STRING, transactionId.toString());
        }
        entity.setCustomName(ENTITY_NAME);
    }

    public static UUID machineId(Entity entity) {
        return readUuid(entity, MACHINE_ID_KEY);
    }

    public static EntityRole role(Entity entity) {
        if (entity == null) {
            return null;
        }
        String raw = entity.getPersistentDataContainer().get(key(ENTITY_ROLE_KEY), PersistentDataType.STRING);
        return EntityRole.parse(raw);
    }

    public static String state(Entity entity) {
        return entity == null ? null
                : entity.getPersistentDataContainer().get(key(ENTITY_STATE_KEY), PersistentDataType.STRING);
    }

    public static UUID transactionId(Entity entity) {
        return readUuid(entity, TRANSACTION_ID_KEY);
    }

    public static int dataVersion(Entity entity) {
        if (entity == null) {
            return 0;
        }
        Integer version = entity.getPersistentDataContainer().get(key(DATA_VERSION_KEY), PersistentDataType.INTEGER);
        return version == null ? 0 : version;
    }

    public static boolean belongsTo(Entity entity, UUID machineId, EntityRole role) {
        return entity != null
                && machineId != null
                && machineId.equals(MachineEntityFactory.machineId(entity))
                && role == role(entity);
    }

    public static boolean isPendingFrom(Entity entity, UUID transactionId) {
        return entity != null
                && STATE_PENDING.equalsIgnoreCase(state(entity))
                && transactionId != null
                && transactionId.equals(transactionId(entity));
    }

    private static UUID readUuid(Entity entity, String keyName) {
        if (entity == null) {
            return null;
        }
        String raw = entity.getPersistentDataContainer().get(key(keyName), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static NamespacedKey key(String value) {
        SmartGambling plugin = SmartGambling.getInstance();
        if (plugin == null) {
            throw new IllegalStateException("SmartGambling is not initialized");
        }
        return new NamespacedKey(plugin, value);
    }

    public static Location relativeLocation(Block origin, double[] offset, BlockFace direction) {
        double x = offset.length > 0 ? offset[0] : 0.0D;
        double y = offset.length > 1 ? offset[1] : 0.0D;
        double z = offset.length > 2 ? offset[2] : 0.0D;
        Location location = origin.getLocation().add(0.5D, 0.0D, 0.5D);
        return switch (direction) {
            case NORTH -> location.add(z, y, -x);
            case SOUTH -> location.add(z, y, x);
            case EAST -> location.add(x, y, z);
            case WEST -> location.add(-x, y, z);
            default -> location.add(x, y, z);
        };
    }

    private static float modelYaw(BlockFace direction) {
        return switch (direction) {
            case SOUTH -> 0.0F;
            case EAST -> -90.0F;
            case WEST -> 90.0F;
            default -> 180.0F;
        };
    }

    private static float seatYaw(BlockFace direction) {
        return switch (direction) {
            case SOUTH -> 180.0F;
            case EAST -> 90.0F;
            case WEST -> -90.0F;
            default -> 0.0F;
        };
    }

    public static float yawForRole(BlockFace direction, EntityRole role) {
        if (role == EntityRole.MODEL) {
            return modelYaw(direction);
        }
        float yaw = seatYaw(direction);
        if (role == EntityRole.BLACKJACK_CHALLENGER_SEAT) {
            yaw += 180.0F;
            if (yaw >= 180.0F) {
                yaw -= 360.0F;
            }
        }
        return yaw;
    }
}
