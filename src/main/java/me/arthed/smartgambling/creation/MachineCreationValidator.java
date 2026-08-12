package me.arthed.smartgambling.creation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.EntityRole;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.common.machine.Machine;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Performs every non-mutating check required before a physical machine is created. */
public final class MachineCreationValidator {
    public static final int MAX_EXTRA_BLOCKS = 32;
    public static final double MAX_BLOCK_RADIUS = 16.0D;
    public static final double ENTITY_CLEARANCE = 0.5D;

    private final SmartGambling plugin;

    public MachineCreationValidator(SmartGambling plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Validates the current session against the plugin's published machine snapshot. */
    public ValidationResult validate(Player player, CreationSession session) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(session, "session");

        final List<Block> blocks;
        try {
            blocks = session.resolveBlocks();
        } catch (RuntimeException exception) {
            return invalid("无法解析已选方块；请确认世界仍已加载。");
        }
        CreationGuideSettings settings = plugin.configManager.getCreationGuideSettings();
        int configuredMax = settings.maxInteractionBlocks();
        double configuredRadius = settings.maxRadius();
        return validate(
                player,
                session.machineTypeId(),
                session.machineType(),
                blocks,
                session.direction(),
                List.copyOf(plugin.uuidMachines.values()),
                configuredMax,
                configuredRadius
        );
    }

    /**
     * Dependency-explicit overload used by callers that already hold a stable machine snapshot.
     * This method only reads Bukkit state; it never creates, moves, or removes an entity.
     */
    public ValidationResult validate(
            Player player,
            String machineTypeId,
            Machine machineType,
            List<Block> blocks,
            BlockFace direction,
            Collection<MachineData> existingMachines
    ) {
        return validate(player, machineTypeId, machineType, blocks, direction,
                existingMachines, MAX_EXTRA_BLOCKS, MAX_BLOCK_RADIUS);
    }

    public ValidationResult validate(
            Player player,
            String machineTypeId,
            Machine machineType,
            List<Block> blocks,
            BlockFace direction,
            Collection<MachineData> existingMachines,
            int maxExtraBlocks,
            double maxBlockRadius
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(existingMachines, "existingMachines");
        if (maxExtraBlocks < 1 || !Double.isFinite(maxBlockRadius) || maxBlockRadius < 1.0D) {
            return invalid("创建向导的数量或半径配置无效，请先修复 config.yml。");
        }
        if (machineType == null) {
            return invalid("机器类型已失效，请取消后重新创建。");
        }
        try {
            if (CraftEngine.instance().isInitializing() || CraftEngine.instance().isReloading()) {
                return invalid("CraftEngine 正在加载或重载，请稍后再确认。");
            }
        } catch (RuntimeException | LinkageError exception) {
            return invalid("无法确认 CraftEngine 当前状态，已为安全起见拒绝创建。");
        }
        if (blocks == null || blocks.isEmpty() || blocks.get(0) == null) {
            return invalid("请先设置机器原点。");
        }
        if (!isCardinal(direction)) {
            return invalid("机器朝向无效，请先旋转到东、南、西或北。");
        }
        if (blocks.size() - 1 > maxExtraBlocks) {
            return invalid("交互方块最多只能额外选择 " + maxExtraBlocks + " 个。");
        }

        Block origin = blocks.get(0);
        World originWorld = origin.getWorld();
        if (originWorld == null) {
            return invalid("原点所在世界未加载。");
        }

        List<BlockKey> selectedKeys = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            if (block == null || block.getWorld() == null) {
                return invalid("已选方块包含无效位置。");
            }
            if (!originWorld.getUID().equals(block.getWorld().getUID())) {
                return invalid("原点和交互方块必须位于同一个世界。");
            }
            Material material = block.getType();
            if (material.isAir() || block.isLiquid()) {
                return invalid("机器方块不能是空气或液体：" + coordinates(block));
            }
            if (!withinRadius(
                    origin.getX(), origin.getY(), origin.getZ(),
                    block.getX(), block.getY(), block.getZ(),
                    maxBlockRadius
            )) {
                return invalid("交互方块距原点不能超过 "
                        + format(maxBlockRadius) + " 格：" + coordinates(block));
            }
            selectedKeys.add(BlockKey.of(block));
        }
        if (!hasUniqueElements(selectedKeys)) {
            return invalid("原点和交互方块中存在重复位置。");
        }

        Set<BlockKey> occupiedBlocks = new HashSet<>();
        for (MachineData existing : existingMachines) {
            if (existing == null || existing.blocks == null) {
                continue;
            }
            for (Block block : existing.blocks) {
                if (block != null && block.getWorld() != null) {
                    occupiedBlocks.add(BlockKey.of(block));
                }
            }
        }
        for (int index = 0; index < selectedKeys.size(); index++) {
            if (occupiedBlocks.contains(selectedKeys.get(index))) {
                return invalid("方块已属于其他机器：" + coordinates(blocks.get(index)));
            }
        }

        ItemStack machineItem = machineType.getMachineItem();
        if (isMissingItem(machineItem)) {
            return invalid("机器类型 " + displayType(machineTypeId) + " 未配置有效的模型物品。");
        }
        ValidationResult machineCeItem = validateCraftEngineItem(machineItem, "机器模型");
        if (!machineCeItem.valid()) {
            return machineCeItem;
        }
        double[] modelOffset = machineType.getMachineEntityOffset();
        if (!allFinite(modelOffset)) {
            return invalid("机器类型 " + displayType(machineTypeId) + " 的模型偏移包含无效数值。");
        }

        List<EntityRole> roles = EntityRole.forMachine(machineType);
        boolean needsSeat = roles.stream().anyMatch(role -> role != EntityRole.MODEL);
        if (needsSeat && isMissingItem(plugin.chairItem)) {
            return invalid("椅子模型物品未配置或已失效。");
        }
        if (needsSeat) {
            ValidationResult chairCeItem = validateCraftEngineItem(plugin.chairItem, "椅子模型");
            if (!chairCeItem.valid()) {
                return chairCeItem;
            }
        }
        if (roles.contains(EntityRole.PRIMARY_SEAT) && !allFinite(plugin.chairOffset)) {
            return invalid("椅子偏移包含无效数值。");
        }
        if (machineType instanceof BlackJack blackJack
                && (!allFinite(blackJack.chair1Offset) || !allFinite(blackJack.chair2Offset))) {
            return invalid("二十一点椅子偏移包含无效数值。");
        }

        List<Location> expectedLocations = new ArrayList<>(roles.size());
        try {
            for (EntityRole role : roles) {
                Location expected = MachineData.expectedLocation(machineType, origin, direction, role);
                if (!finiteLocation(expected)) {
                    return invalid(roleChinese(role) + "的预期位置无效。");
                }
                expectedLocations.add(expected);
            }
        } catch (RuntimeException exception) {
            return invalid("无法计算机器模型或椅子的位置：" + safeMessage(exception));
        }

        for (int first = 0; first < expectedLocations.size(); first++) {
            for (int second = first + 1; second < expectedLocations.size(); second++) {
                if (locationsWithin(
                        expectedLocations.get(first),
                        expectedLocations.get(second),
                        ENTITY_CLEARANCE
                )) {
                    return invalid("本机器的模型或椅子位置互相重叠："
                            + roleChinese(roles.get(first)) + " / " + roleChinese(roles.get(second)));
                }
            }
        }

        ValidationResult clearance = validateEntityClearance(expectedLocations, existingMachines);
        if (!clearance.valid()) {
            return clearance;
        }

        if (plugin.worldGuard != null && plugin.worldGuard.isEnabled()) {
            String deniedBlock = null;
            for (Block block : blocks) {
                if (!plugin.worldGuard.canSelectMachineBlock(player, block) && deniedBlock == null) {
                    deniedBlock = coordinates(block);
                }
            }
            String deniedEntity = null;
            for (Location expected : expectedLocations) {
                if (!plugin.worldGuard.canPlaceMachineEntity(player, expected) && deniedEntity == null) {
                    deniedEntity = coordinates(expected);
                }
            }
            if (deniedBlock != null) {
                return invalid("你没有在此位置创建机器的领地权限：" + deniedBlock);
            }
            if (deniedEntity != null) {
                return invalid("你没有在模型或椅子位置创建实体的领地权限："
                        + deniedEntity);
            }
        }
        return new ValidationResult(true, "创建前校验通过。");
    }

    private ValidationResult validateEntityClearance(
            List<Location> expectedLocations,
            Collection<MachineData> existingMachines
    ) {
        for (MachineData existing : existingMachines) {
            if (existing == null) {
                continue;
            }
            List<Location> occupied = new ArrayList<>();
            try {
                for (EntityRole role : existing.entityRoles()) {
                    Location location = existing.expectedLocation(role);
                    if (finiteLocation(location)) {
                        occupied.add(location);
                    }
                }
            } catch (RuntimeException exception) {
                return invalid("无法检查现有机器 " + existing.id + " 的实体位置。");
            }
            if (existing.entities != null) {
                for (Entity entity : existing.entities) {
                    if (entity == null) {
                        continue;
                    }
                    try {
                        Location actual = entity.getLocation();
                        if (finiteLocation(actual)) {
                            occupied.add(actual);
                        }
                    } catch (RuntimeException ignored) {
                        // The durable expected position above still protects this role.
                    }
                }
            }
            for (Location planned : expectedLocations) {
                for (Location current : occupied) {
                    if (locationsWithin(planned, current, ENTITY_CLEARANCE)) {
                        return invalid("模型或椅子位置与现有机器距离不足 0.5 格："
                                + coordinates(planned));
                    }
                }
            }
        }
        return new ValidationResult(true, "");
    }

    /** Pure helper: true only when the array is non-null and every component is finite. */
    public static boolean allFinite(double... values) {
        if (values == null) {
            return false;
        }
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    /** Pure helper using three-dimensional Euclidean block distance. */
    public static boolean withinRadius(
            int originX,
            int originY,
            int originZ,
            int x,
            int y,
            int z,
            double radius
    ) {
        if (!Double.isFinite(radius) || radius < 0.0D) {
            return false;
        }
        long dx = (long) x - originX;
        long dy = (long) y - originY;
        long dz = (long) z - originZ;
        double squaredDistance = (double) dx * dx + (double) dy * dy + (double) dz * dz;
        return squaredDistance <= radius * radius;
    }

    /** Pure helper that also rejects null collections and null elements. */
    public static boolean hasUniqueElements(Collection<?> values) {
        if (values == null) {
            return false;
        }
        Set<Object> unique = new HashSet<>();
        for (Object value : values) {
            if (value == null || !unique.add(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean locationsWithin(Location first, Location second, double distance) {
        World firstWorld = first.getWorld();
        World secondWorld = second.getWorld();
        if (firstWorld == null || secondWorld == null
                || !firstWorld.getUID().equals(secondWorld.getUID())) {
            return false;
        }
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz <= distance * distance;
    }

    private static boolean finiteLocation(Location location) {
        return location != null
                && location.getWorld() != null
                && allFinite(location.getX(), location.getY(), location.getZ());
    }

    private static boolean isMissingItem(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static ValidationResult validateCraftEngineItem(ItemStack item, String label) {
        try {
            net.momirealms.craftengine.core.util.Key id = CraftEngineItems.getCustomItemId(item);
            if (id == null || CraftEngineItems.byId(id) == null) {
                return invalid(label + "对应的 CraftEngine 物品 ID 已失效，请先恢复资源包并重载 CraftEngine。");
            }
            return new ValidationResult(true, "");
        } catch (RuntimeException | LinkageError exception) {
            return invalid("无法校验" + label + "的 CraftEngine 物品：" + safeMessage(exception));
        }
    }

    private static boolean isCardinal(BlockFace direction) {
        return direction == BlockFace.NORTH || direction == BlockFace.EAST
                || direction == BlockFace.SOUTH || direction == BlockFace.WEST;
    }

    private static String displayType(String machineTypeId) {
        if (machineTypeId == null || machineTypeId.isBlank()) {
            return "未知类型";
        }
        try {
            return me.arthed.smartgambling.utils.MachineTypeIds.displayName(machineTypeId);
        } catch (IllegalArgumentException exception) {
            return "未知类型";
        }
    }

    private static String roleChinese(EntityRole role) {
        return switch (role) {
            case MODEL -> "机器模型";
            case PRIMARY_SEAT -> "主座椅";
            case BLACKJACK_HOST_SEAT -> "庄家座椅";
            case BLACKJACK_CHALLENGER_SEAT -> "挑战者座椅";
        };
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String coordinates(Block block) {
        return block.getWorld().getName() + " " + block.getX() + " " + block.getY() + " " + block.getZ();
    }

    private static String coordinates(Location location) {
        return location.getWorld().getName() + " "
                + format(location.getX()) + " " + format(location.getY()) + " " + format(location.getZ());
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static ValidationResult invalid(String message) {
        return new ValidationResult(false, message);
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    public record ValidationResult(boolean valid, String message) {
        public ValidationResult {
            Objects.requireNonNull(message, "message");
        }
    }
}
