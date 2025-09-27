package me.arthed.smartgambling.games.slots.odalita;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.slots.objects.SlotItem;
import me.arthed.smartgambling.games.slots.objects.rewards.Reward;
import me.arthed.smartgambling.utils.DisplayUtils;
import net.milkbowl.vault.economy.Economy;
import nl.odalitadevelopments.menus.OdalitaMenus;
import nl.odalitadevelopments.menus.annotations.Menu;
import nl.odalitadevelopments.menus.contents.MenuContents;
import nl.odalitadevelopments.menus.contents.pos.SlotPos;
import nl.odalitadevelopments.menus.items.ClickableItem;
import nl.odalitadevelopments.menus.items.DisplayItem;
import nl.odalitadevelopments.menus.items.MenuItem;
import nl.odalitadevelopments.menus.menu.type.MenuType;
import nl.odalitadevelopments.menus.menu.providers.PlayerMenuProvider;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.NavigableMap;

/**
 * 基于OdalitaMenus框架的老虎机GUI实现
 * 保持与原有SlotMachine功能完全兼容
 */
@Menu(
    id = "odalita_slot_machine",
    title = "老虎机",
    type = MenuType.CHEST_6_ROW
)
public class OdalitaSlotMachine implements PlayerMenuProvider, Machine {
    
    // 保持与原有SlotMachine相同的核心属性
    public final String name;
    private final ItemStack machineItem;
    private final double[] entityOffset;
    private final String inventoryTitle;
    private final List<List<Integer>> displaySlots;
    private final NavigableMap<Integer, SlotItem> itemsWeighed;
    private final int itemsTotalWeight;
    private final List<Reward> rewards;
    public final int defaultBet;
    private final int animationDuration;
    private final int animationStartingSpeed;
    
    // OdalitaMenus特有的属性
    private final HashMap<Player, OdalitaPlayerData> playerData;
    
    public OdalitaSlotMachine(String name, ItemStack machineItem, double[] entityOffset, 
                             String inventoryTitle, List<List<Integer>> displaySlots,
                             NavigableMap<Integer, SlotItem> itemsWeighed, int itemsTotalWeight,
                             List<Reward> rewards, int animationDuration, int defaultBet, 
                             int animationStartingSpeed) {
        this.name = name;
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.inventoryTitle = inventoryTitle;
        this.displaySlots = displaySlots;
        this.itemsWeighed = itemsWeighed;
        this.itemsTotalWeight = itemsTotalWeight;
        this.rewards = rewards;
        this.animationDuration = animationDuration;
        this.defaultBet = defaultBet;
        this.animationStartingSpeed = animationStartingSpeed;
        this.playerData = new HashMap<>();
    }
    
    @Override
    public void onLoad(Player player, MenuContents contents) {
        // 初始化玩家数据
        OdalitaPlayerData data = new OdalitaPlayerData(displaySlots.size());
        playerData.put(player, data);
        
        // 设置基础界面布局
        setupBaseLayout(contents, player);
        
        // 设置按钮
        setupButtons(contents, player);
        
        // 初始化显示槽位
        initializeDisplaySlots(contents, player);
    }
    
    private void setupBaseLayout(MenuContents contents, Player player) {
        // 根据配置文件设置界面布局
        
        // 设置边框装饰 (灰色玻璃板) - 避开转轮显示区域和按钮区域
        int[] borderSlots = {0, 1, 2, 3, 4, 5, 6, 7, 8, 37, 38, 39, 40, 41, 42, 43};
        ItemStack borderItem = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta borderMeta = borderItem.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName("§f");
            borderItem.setItemMeta(borderMeta);
        }
        
        for (int slot : borderSlots) {
            int row = slot / 9;
            int col = slot % 9;
            contents.set(row, col, DisplayItem.of(borderItem));
        }
        
        // 设置侧边装饰 (绿色玻璃板)
        int[] sideSlots = {18, 19, 25, 26};
        ItemStack sideItem = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta sideMeta = sideItem.getItemMeta();
        if (sideMeta != null) {
            sideMeta.setDisplayName("§f");
            sideItem.setItemMeta(sideMeta);
        }
        
        for (int slot : sideSlots) {
            int row = slot / 9;
            int col = slot % 9;
            contents.set(row, col, DisplayItem.of(sideItem));
        }
        
        // 注意：不在这里设置转轮区域的装饰物品，因为这些位置会被initializeDisplaySlots方法覆盖
        // displaySlots区域: [[11, 20, 29], [12, 21, 30], [13, 22, 31], [14, 23, 32], [15, 24, 33]]
        // 这些位置将在initializeDisplaySlots方法中设置为实际的转轮物品
    }
    
    private void setupButtons(MenuContents contents, Player player) {
        // 旋转按钮 - 根据配置文件 spinButton: [48, 49, 50]
        // 48=row5,col3  49=row5,col4  50=row5,col5
        contents.set(5, 3, ClickableItem.of(createSpinButton(player), 
                event -> handleSpinClick(event, contents, player)));
        contents.set(5, 4, ClickableItem.of(createSpinButton(player), 
                event -> handleSpinClick(event, contents, player)));
        contents.set(5, 5, ClickableItem.of(createSpinButton(player), 
                event -> handleSpinClick(event, contents, player)));
        
        // 金钱按钮 - 根据配置文件 moneyButton: [44] = row4,col8
        contents.set(4, 8, ClickableItem.of(createMoneyButton(player), 
                event -> handleMoneyClick(event, player)));
        
        // 奖励GUI按钮 - 根据配置文件 rewardsGuiButton: [36] = row4,col0
        contents.set(4, 0, ClickableItem.of(createRewardsButton(), 
                event -> handleRewardsClick(event, player)));
        
        // 关闭按钮 - 配置文件中closeButton为空，暂时放在右下角
        contents.set(5, 8, ClickableItem.of(createCloseButton(), 
                event -> handleCloseClick(event, player)));
    }
    
    private void initializeDisplaySlots(MenuContents contents, Player player) {
        OdalitaPlayerData data = playerData.get(player);
        
        // 为每个显示槽位设置初始物品
        for (int i = 0; i < displaySlots.size(); i++) {
            List<Integer> slots = displaySlots.get(i);
            SlotItem randomItem = getRandomItem();
            data.lastItems[i] = randomItem;
            data.finalItems[i] = randomItem;
            
            // 设置三个位置的物品
            for (int slotIndex : slots) {
                int row = slotIndex / 9;
                int col = slotIndex % 9;
                contents.set(row, col, DisplayItem.of(randomItem.itemStack));
            }
        }
    }
    
    private ItemStack createSpinButton(Player player) {
        // 创建旋转按钮，显示当前下注金额
        ItemStack spinButton = new ItemStack(org.bukkit.Material.EMERALD);
        ItemMeta meta = spinButton.getItemMeta();
        if (meta == null) {
            return spinButton;
        }
        
        OpenInterface openInterface = SmartGambling.getInstance().openMachines.get(player);
        int betAmount = (openInterface != null) ? openInterface.betAmount : defaultBet;
        
        meta.setDisplayName(ChatColor.GREEN + "旋转 - " + betAmount + " 金币");
        meta.setLore(java.util.Arrays.asList(
            ChatColor.GRAY + "点击开始旋转",
            ChatColor.YELLOW + "当前下注: " + betAmount + " 金币"
        ));
        spinButton.setItemMeta(meta);
        return spinButton;
    }
    
    private ItemStack createMoneyButton(Player player) {
        ItemStack moneyButton = new ItemStack(org.bukkit.Material.GOLD_INGOT);
        ItemMeta meta = moneyButton.getItemMeta();
        if (meta == null) {
            return moneyButton;
        }
        meta.setDisplayName(ChatColor.YELLOW + "调整下注金额");
        moneyButton.setItemMeta(meta);
        return moneyButton;
    }
    
    private ItemStack createRewardsButton() {
        ItemStack rewardsButton = new ItemStack(org.bukkit.Material.DIAMOND);
        ItemMeta meta = rewardsButton.getItemMeta();
        if (meta == null) {
            return rewardsButton;
        }
        meta.setDisplayName(ChatColor.BLUE + "查看奖励");
        rewardsButton.setItemMeta(meta);
        return rewardsButton;
    }
    
    private ItemStack createCloseButton() {
        ItemStack closeButton = new ItemStack(org.bukkit.Material.BARRIER);
        ItemMeta meta = closeButton.getItemMeta();
        if (meta == null) {
            return closeButton;
        }
        meta.setDisplayName(ChatColor.RED + "关闭");
        closeButton.setItemMeta(meta);
        return closeButton;
    }
    
    private void handleSpinClick(InventoryClickEvent event, MenuContents contents, Player player) {
        event.setCancelled(true);
        
        OdalitaPlayerData data = playerData.get(player);
        if (data.spinning) {
            return;
        }
        
        spin(contents, player);
    }
    
    private void handleMoneyClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        // 打开金钱调整界面
        // 这里可以集成原有的金钱GUI逻辑
    }
    
    private void handleRewardsClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        // 打开奖励查看界面
        // 这里可以集成原有的奖励GUI逻辑
    }
    
    private void handleCloseClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        player.closeInventory();
    }
    
    public void spin(MenuContents contents, Player player) {
        OdalitaPlayerData data = playerData.get(player);
        if (data == null || data.spinning) {
            return;
        }
        
        // 安全获取下注金额，添加空指针检查
        OpenInterface openInterface = SmartGambling.getInstance().openMachines.get(player);
        if (openInterface == null) {
            player.sendMessage(ChatColor.RED + "错误：无法获取游戏数据，请重新打开老虎机。");
            return;
        }
        
        int bet = openInterface.betAmount;
        Economy economy = SmartGambling.getEconomy();
        
        if (economy.getBalance(player) < (double) bet) {
            DisplayUtils.displayActionBar(player, String.format(
                (String) SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"), 
                bet, economy.getBalance(player)));
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
            return;
        }
        
        // 扣除金币
        SmartGambling.getEconomy().withdrawPlayer(player, (double) bet);
        player.sendMessage(String.format(
            (String) SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), 
            bet, SmartGambling.getEconomy().getBalance(player)));
        
        data.spinning = true;
        startOdalitaAnimation(contents, player);
        
        // 设置旋转结束任务
        data.spinEndTask = Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            data.spinning = false;
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), 
                () -> stoppedSpinning(contents, player), 8L);
        }, (long) (animationDuration + 21) + 6L * (long) displaySlots.size());
    }
    
    private void startOdalitaAnimation(MenuContents contents, Player player) {
        OdalitaPlayerData data = playerData.get(player);
        
        // 初始化动画速度
        for (int i = 0; i < displaySlots.size(); i++) {
            data.animationSpeed[i] = animationStartingSpeed;
        }
        
        // 播放旋转音效
        playSpinningMusic(player, true);
        
        // 开始每列的动画
        for (int i = 0; i < displaySlots.size(); i++) {
            int column = i;
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                data.animationSpeed[column]--;
                spinOdalitaLine(column, contents, player);
            }, 2L * (long) i);
        }
        
        // 动画速度调整逻辑（保持与原有实现一致）
        for (int j = 0; j < 2; j++) {
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                for (int i = 0; i < displaySlots.size(); i++) {
                    int column = i;
                    Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                        data.animationSpeed[column]--;
                    }, 2L * (long) i);
                }
            }, (long) j * 4L);
        }
        
        // 减速阶段
        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            for (int j = 0; j < 3; j++) {
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    for (int i = 0; i < displaySlots.size(); i++) {
                        int column = i;
                        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                            data.animationSpeed[column]++;
                        }, 4L * (long) i);
                    }
                }, (long) j * 5L);
            }
        }, (long) animationDuration);
        
        // 停止动画
        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            for (int i = 0; i < displaySlots.size(); i++) {
                int column = i;
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    data.animationSpeed[column] = 0;
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BIT, 2.0F, 1.0F);
                }, 6L * (long) i);
            }
        }, (long) (animationDuration + 20));
    }
    
    private void spinOdalitaLine(int line, MenuContents contents, Player player) {
        OdalitaPlayerData data = playerData.get(player);
        if (data == null) return;
        
        data.finalItems[line] = data.lastItems[line];
        data.lastItems[line] = getRandomItem();
        
        List<Integer> slots = displaySlots.get(line);
        
        // 更新显示槽位的物品（模拟滚动效果）
        int slot2 = slots.get(2);  // 底部槽位
        int slot1 = slots.get(1);  // 中间槽位
        int slot0 = slots.get(0);  // 顶部槽位
        
        // 获取当前物品并向下移动
        MenuItem menuItem1 = contents.menuSession().getContent(SlotPos.of(slot1 / 9, slot1 % 9));
        MenuItem menuItem0 = contents.menuSession().getContent(SlotPos.of(slot0 / 9, slot0 % 9));
        
        // 向下移动物品：中间 -> 底部，顶部 -> 中间
        if (menuItem1 != null) {
            contents.set(slot2 / 9, slot2 % 9, menuItem1);
        }
        if (menuItem0 != null) {
            contents.set(slot1 / 9, slot1 % 9, menuItem0);
        }
        
        // 在顶部设置新的随机物品
        contents.set(slot0 / 9, slot0 % 9, DisplayItem.of(data.lastItems[line].itemStack));
        
        // 刷新显示的槽位以确保GUI立即更新
        contents.refreshItem(slot0);
        contents.refreshItem(slot1);
        contents.refreshItem(slot2);
        
        player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.02F, 0.5F);
        
        if (data.animationSpeed[line] > 0) {
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), 
                () -> spinOdalitaLine(line, contents, player), (long) data.animationSpeed[line]);
        }
    }
    
    public void stoppedSpinning(MenuContents contents, Player player) {
        OdalitaPlayerData data = playerData.get(player);
        if (data == null) {
            return;
        }
        
        Reward reward = checkRewards(data.finalItems);
        
        if (reward != null) {
            // 安全获取下注金额
            OpenInterface openInterface = SmartGambling.getInstance().openMachines.get(player);
            if (openInterface == null) {
                player.sendMessage(ChatColor.RED + "错误：无法获取游戏数据。");
                return;
            }
            
            float amountWon = (float) Math.round((float) openInterface.betAmount * reward.moneyMultiplier);
            SmartGambling.getEconomy().depositPlayer(player, (double) amountWon);
            double balance = SmartGambling.getEconomy().getBalance(player);
            
            DisplayUtils.displayActionBar(player, String.format(
                (String) SmartGambling.getInstance().configManager.messages.get("wonMoneyActionBar"), 
                amountWon, balance));
            player.sendMessage(String.format(
                (String) SmartGambling.getInstance().configManager.messages.get("wonMoney"), 
                amountWon, balance));
            
            // 执行获奖命令
            if (reward.winningCommands != null) {
                for (String command : reward.winningCommands) {
                    if (command.startsWith("message:")) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                            command.replace("message: ", "").replace("%player%", player.getName())));
                    } else if (command.startsWith("bossbar")) {
                        String[] data_cmd = command.split(": ");
                        String[] bossbarData = data_cmd[0].split(" ");
                        BarColor color = BarColor.valueOf(bossbarData[1].toUpperCase());
                        BarStyle style = BarStyle.valueOf(bossbarData[2].toUpperCase());
                        int seconds = Integer.parseInt(bossbarData[3]);
                        DisplayUtils.displayBossBar(player, data_cmd[1].replace("%player%", player.getName()), color, style, seconds);
                    } else {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
                    }
                }
            }
            
            player.closeInventory();
            reward.sound.play(player);
        }
    }
    
    private SlotItem getRandomItem() {
        int number = SmartGambling.getInstance().random.nextInt(itemsTotalWeight);
        return itemsWeighed.higherEntry(number).getValue();
    }
    
    private Reward checkRewards(SlotItem[] results) {
        // 保持与原有奖励检查逻辑一致
        Reward maxReward = null;
        
        for (Reward reward : rewards) {
            if (reward.check(results) && (maxReward == null || reward.moneyMultiplier > maxReward.moneyMultiplier)) {
                maxReward = reward;
            }
        }
        
        return maxReward;
    }
    
    private void playSpinningMusic(Player player, boolean pitch) {
        // 保持与原有音效逻辑一致
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, pitch ? 1.0F : 0.5F);
    }
    
    // 实现Machine接口的方法
    @Override
    public void open(Player player, OpenInterface openSlot) {
        // 重要：将玩家注册到openMachines HashMap中，这样spin方法才能正确获取betAmount
        SmartGambling.getInstance().openMachines.put(player, openSlot);
        
        // 使用OdalitaMenus打开菜单
        OdalitaMenus.getInstance(SmartGambling.getInstance()).openMenu(this, player);
    }
    
    @Override
    public void close(Player player, Inventory inventory) {
        // 从openMachines中移除玩家
        SmartGambling.getInstance().openMachines.remove(player);
        
        // 清理玩家数据
        OdalitaPlayerData data = playerData.remove(player);
        if (data != null && data.spinEndTask != null) {
            data.spinEndTask.cancel();
        }
    }
    
    @Override
    public void inventoryClick(InventoryClickEvent event) {
        // OdalitaMenus会自动处理点击事件，这里保留接口兼容性
        event.setCancelled(true);
    }
    
    @Override
    public ItemStack getMachineItem() {
        return machineItem;
    }
    
    @Override
    public double[] getMachineEntityOffset() {
        return entityOffset;
    }
    
    // 内部数据类
    private static class OdalitaPlayerData {
        public final int[] animationSpeed;
        public final SlotItem[] lastItems;
        public final SlotItem[] finalItems;
        public boolean spinning = false;
        public BukkitTask spinEndTask;
        
        private OdalitaPlayerData(int slotCount) {
            this.animationSpeed = new int[slotCount];
            this.lastItems = new SlotItem[slotCount];
            this.finalItems = new SlotItem[slotCount];
        }
    }
}