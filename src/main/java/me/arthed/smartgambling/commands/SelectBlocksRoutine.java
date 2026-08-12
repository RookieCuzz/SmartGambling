package me.arthed.smartgambling.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.creation.BlockPosition;
import me.arthed.smartgambling.creation.CreationSession;
import me.arthed.smartgambling.creation.CreationGuideSettings;
import me.arthed.smartgambling.data.EntityRole;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread-only creation session manager, selection tool and live guide. */
public final class SelectBlocksRoutine implements Listener {
    private final SmartGambling plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, CreationSession> sessions = new HashMap<>();
    private final Map<UUID, UUID> wandNonces = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, UUID> lastCreated = new HashMap<>();
    private CreationGuideSettings settings;
    private BukkitTask guideTask;

    public SelectBlocksRoutine(SmartGambling plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "creation_wand");
        this.settings = plugin.configManager.getCreationGuideSettings();
        scheduleGuideTask();
    }

    public void applySettings(CreationGuideSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        if (guideTask != null) {
            guideTask.cancel();
        }
        scheduleGuideTask();
    }

    private void scheduleGuideTask() {
        long interval = settings.previewIntervalTicks();
        this.guideTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public Optional<CreationSession> session(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public boolean startRoutine(Player player, String machineTypeId, Machine machineType) {
        UUID playerId = player.getUniqueId();
        if (sessions.containsKey(playerId)) {
            return false;
        }
        removeOwnedWands(player);
        int slot = player.getInventory().firstEmpty();
        if (slot < 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        long timeout = settings.timeoutSeconds() * 1000L;
        CreationSession creation = new CreationSession(
                playerId, machineTypeId, machineType, now, timeout);
        UUID nonce = UUID.randomUUID();
        sessions.put(playerId, creation);
        wandNonces.put(playerId, nonce);
        player.getInventory().setItem(slot, createWand(nonce));
        if (slot < 9) {
            player.getInventory().setHeldItemSlot(slot);
        }
        BossBar bar = Bukkit.createBossBar(
                ChatColor.AQUA + "机器创建向导：左键设置原点",
                BarColor.BLUE,
                BarStyle.SOLID
        );
        bar.addPlayer(player);
        bossBars.put(playerId, bar);
        updateGuide(player, creation, now);
        return true;
    }

    public boolean cancel(Player player) {
        return cancel(player.getUniqueId());
    }

    public boolean cancel(UUID playerId) {
        CreationSession removed = sessions.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        cleanup(playerId, player);
        return removed != null;
    }

    public void cancelAll() {
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            cancel(playerId);
        }
    }

    public void shutdown() {
        if (guideTask != null) {
            guideTask.cancel();
            guideTask = null;
        }
        cancelAll();
        lastCreated.clear();
    }

    public void complete(UUID playerId, UUID machineId) {
        cancel(playerId);
        lastCreated.put(playerId, machineId);
    }

    public boolean rotate(UUID playerId, boolean left) {
        CreationSession creation = sessions.get(playerId);
        if (creation == null) {
            return false;
        }
        creation.rotate(left, System.currentTimeMillis());
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            updateGuide(player, creation, System.currentTimeMillis());
        }
        return true;
    }

    public Optional<UUID> lastCreated(UUID playerId) {
        return Optional.ofNullable(lastCreated.get(playerId));
    }

    public void clearLastCreated(UUID playerId) {
        lastCreated.remove(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isCurrentWand(event.getPlayer(), event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        CreationSession creation = sessions.get(player.getUniqueId());
        if (creation == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (action == Action.LEFT_CLICK_BLOCK) {
            if (!validSelectionBlock(block)) {
                player.sendMessage(ChatColor.RED + "机器原点不能是空气或液体。");
                return;
            }
            if (!canSelect(player, block)) {
                player.sendMessage(ChatColor.RED + "你没有在该领地位置设置机器原点的权限。");
                return;
            }
            creation.setOrigin(block, facing(block, player.getLocation()), now);
            player.sendMessage(format("creationOriginSet", block.getX(), block.getY(), block.getZ()));
            updateGuide(player, creation, now);
            return;
        }
        if (player.isSneaking()) {
            if (creation.removeInteraction(block, now)) {
                player.sendMessage(format("creationBlockRemoved", block.getX(), block.getY(), block.getZ()));
            } else {
                player.sendMessage(message("creationNotSelected", "该方块未被选中。"));
            }
            updateGuide(player, creation, now);
            return;
        }
        if (!validSelectionBlock(block)) {
            player.sendMessage(ChatColor.RED + "交互方块不能是空气或液体。");
            return;
        }
        if (!canSelect(player, block)) {
            player.sendMessage(ChatColor.RED + "你没有在该领地位置添加交互方块的权限。");
            return;
        }
        int maxBlocks = settings.maxInteractionBlocks();
        double maxRadius = settings.maxRadius();
        CreationSession.AddResult result = creation.addInteraction(block, maxBlocks, maxRadius, now);
        switch (result) {
            case ADDED -> player.sendMessage(format(
                    "creationBlockAdded", block.getX(), block.getY(), block.getZ()));
            case NO_ORIGIN -> player.sendMessage(message("creationNoOrigin", "请先左键设置机器原点。"));
            case OTHER_WORLD -> player.sendMessage(message("creationOtherWorld", "所选方块必须位于同一世界。"));
            case ALREADY_SELECTED -> player.sendMessage(message("creationAlreadySelected", "该方块已被选中。"));
            case LIMIT_REACHED -> player.sendMessage(message("creationLimit", "已达到交互方块数量上限。"));
            case TOO_FAR -> player.sendMessage(message("creationTooFar", "该方块超出创建半径。"));
        }
        updateGuide(player, creation, now);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isCurrentWand(event.getPlayer(), event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "创建选择棒不能被丢弃，请使用 /sg cancel 结束向导。");
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND
                && isCurrentWand(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED
                    + "创建选择棒不能用于实体，请对方块使用或输入 /sg cancel 结束向导。");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isCurrentWand(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isCurrentWand(event.getPlayer(), event.getMainHandItem())
                || isCurrentWand(event.getPlayer(), event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (isCurrentWand(player, event.getCurrentItem())
                || isCurrentWand(player, event.getCursor())
                || (event.getHotbarButton() >= 0
                && isCurrentWand(player, player.getInventory().getItem(event.getHotbarButton())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isCurrentWand(player, event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        if (!sessions.containsKey(playerId)) {
            return;
        }
        event.getDrops().removeIf(this::isTaggedWand);
        cancel(playerId);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, CreationSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CreationSession> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            CreationSession creation = entry.getValue();
            if (player == null || !player.isOnline()) {
                cleanup(entry.getKey(), player);
                iterator.remove();
                continue;
            }
            if (creation.expired(now)) {
                player.sendMessage(message("creationTimedOut", "机器创建向导已超时并取消。"));
                cleanup(entry.getKey(), player);
                iterator.remove();
                continue;
            }
            updateGuide(player, creation, now);
        }
    }

    private void updateGuide(Player player, CreationSession creation, long now) {
        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar != null) {
            long remaining = Math.max(0L, (creation.remainingMillis(now) + 999L) / 1000L);
            int selected = creation.interactionPositions().size();
            String step = creation.origin() == null
                    ? "左键设置机器原点"
                    : "右键添加 / 潜行右键移除；/sg rotate 旋转；/sg confirm 创建";
            bar.setTitle(ChatColor.AQUA + "创建 "
                    + me.arthed.smartgambling.utils.MachineTypeIds.displayName(creation.machineTypeId())
                    + ChatColor.GRAY + " | " + step
                    + ChatColor.WHITE + " | 朝向 " + directionChinese(creation.direction())
                    + " | 交互块 " + selected + " | " + remaining + "秒");
            long configured = settings.timeoutSeconds();
            bar.setProgress(Math.max(0.0D, Math.min(1.0D, remaining / (double) configured)));
        }
        showPreview(player, creation);
    }

    private void showPreview(Player player, CreationSession creation) {
        BlockPosition originPosition = creation.origin();
        if (originPosition == null) {
            return;
        }
        final Block origin;
        try {
            origin = originPosition.resolve();
        } catch (RuntimeException ignored) {
            return;
        }
        Location center = origin.getLocation().add(0.5D, 1.1D, 0.5D);
        player.spawnParticle(Particle.END_ROD, center, 3, 0.12D, 0.12D, 0.12D, 0.0D);
        for (BlockPosition point : creation.interactionPositions()) {
            try {
                Location location = point.resolve().getLocation().add(0.5D, 1.05D, 0.5D);
                player.spawnParticle(Particle.COMPOSTER, location, 2, 0.1D, 0.1D, 0.1D, 0.0D);
            } catch (RuntimeException ignored) {
                // The final validator reports unloaded worlds precisely.
            }
        }
        try {
            for (EntityRole role : EntityRole.forMachine(creation.machineType())) {
                Location expected = MachineData.expectedLocation(
                        creation.machineType(), origin, creation.direction(), role).clone().add(0.0D, 1.0D, 0.0D);
                Particle particle = role == EntityRole.MODEL ? Particle.FLAME : Particle.HEART;
                player.spawnParticle(particle, expected, 3, 0.08D, 0.08D, 0.08D, 0.0D);
            }
            Location direction = center.clone().add(
                    creation.direction().getModX() * 1.3D,
                    0.1D,
                    creation.direction().getModZ() * 1.3D
            );
            player.spawnParticle(Particle.ELECTRIC_SPARK, direction, 4, 0.08D, 0.08D, 0.08D, 0.0D);
        } catch (RuntimeException ignored) {
            // Invalid offsets are rejected at confirm time without world mutation.
        }
    }

    private void cleanup(UUID playerId, Player player) {
        BossBar bar = bossBars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
        }
        wandNonces.remove(playerId);
        if (player != null) {
            removeOwnedWands(player);
        }
    }

    private ItemStack createWand(UUID nonce) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "机器创建选择棒");
        meta.setLore(List.of(
                ChatColor.YELLOW + "左键：设置机器原点",
                ChatColor.GREEN + "右键：添加交互方块",
                ChatColor.RED + "潜行右键：移除交互方块",
                ChatColor.GRAY + "/sg rotate 旋转，/sg confirm 创建"
        ));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, nonce.toString());
        wand.setItemMeta(meta);
        return wand;
    }

    private boolean isCurrentWand(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        UUID expected = wandNonces.get(player.getUniqueId());
        if (expected == null) {
            return false;
        }
        String value = item.getItemMeta().getPersistentDataContainer()
                .get(wandKey, PersistentDataType.STRING);
        return expected.toString().equals(value);
    }

    private void removeOwnedWands(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (isTaggedWand(item)) {
                player.getInventory().setItem(index, null);
            }
        }
        if (isTaggedWand(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    private boolean isTaggedWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING);
    }

    private String message(String key, String fallback) {
        return plugin.configManager.messages.getOrDefault(key, ChatColor.RED + fallback);
    }

    private String format(String key, Object... values) {
        try {
            return String.format(message(key, key), values);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("创建向导消息格式无效：" + key + "，已使用安全提示");
            return ChatColor.RED + "创建向导提示格式错误，请联系管理员检查 config.yml。";
        }
    }

    private boolean canSelect(Player player, Block block) {
        return plugin.worldGuard == null
                || !plugin.worldGuard.isEnabled()
                || plugin.worldGuard.canSelectMachineBlock(player, block);
    }

    private static boolean validSelectionBlock(Block block) {
        return block != null && !block.getType().isAir() && !block.isLiquid();
    }

    private static BlockFace facing(Block origin, Location playerLocation) {
        boolean xAxis = Math.abs(playerLocation.getX() - origin.getX())
                > Math.abs(playerLocation.getZ() - origin.getZ());
        if (xAxis) {
            return playerLocation.getX() > origin.getX() ? BlockFace.EAST : BlockFace.WEST;
        }
        return playerLocation.getZ() > origin.getZ() ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static String directionChinese(BlockFace direction) {
        return switch (direction) {
            case NORTH -> "北";
            case EAST -> "东";
            case SOUTH -> "南";
            case WEST -> "西";
            default -> "未知";
        };
    }
}
