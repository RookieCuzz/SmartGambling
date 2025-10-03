// Decompiled with: CFR 0.152
// Class Version: 17
package me.arthed.smartgambling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import me.arthed.smartgambling.commands.InputMoneyRoutine;
import me.arthed.smartgambling.commands.JackpotCommand;
import me.arthed.smartgambling.commands.MainCommand;
import me.arthed.smartgambling.commands.OdalitaTestCommand;
import me.arthed.smartgambling.commands.SelectBlocksRoutine;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.data.DataManager;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.common.inventories.ConfirmGameInventory;
import me.arthed.smartgambling.games.common.inventories.MoneyInventory;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.games.slots.PlaybackManager;
import me.arthed.smartgambling.games.slots.SlotMachine;
import me.arthed.smartgambling.games.slots.odalita.OdalitaSlotMachineManager;
import me.arthed.smartgambling.handlers.SmartGamblingPlaceholders;
import me.arthed.smartgambling.handlers.WorldGuardImplementation;
import me.arthed.smartgambling.listeners.BlockListener;
import me.arthed.smartgambling.listeners.ChatListener;
import me.arthed.smartgambling.listeners.EntityListener;
import me.arthed.smartgambling.listeners.InventoryListener;
import me.arthed.smartgambling.listeners.WorldSaveListener;
import nl.odalitadevelopments.menus.OdalitaMenus;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class SmartGambling
        extends JavaPlugin
        implements Listener {
    private PlaybackManager playbackManager;
    public static String PREFIX = "SmartGambling";
    public static String ECONOMY_HANDLER = "BossShopPro";
    private static SmartGambling instance;
    // Vault economy removed; using PlayerPoints-only economy
    // PlayerPoints integration via reflection (no compile-time dependency)
    private Object playerPointsApi; // org.black_ixx.playerpoints.PlayerPointsAPI instance
    private boolean usePlayerPoints = false;
    public ConfigManager configManager;
    public final HashMap<World, HashMap<Chunk, List<MachineData>>> machines = new HashMap();
    public final HashMap<UUID, MachineData> uuidMachines = new HashMap();
    public final List<MachineData> machinesToAdd = new ArrayList<MachineData>();
    public final List<MachineData> machinesToRemove = new ArrayList<MachineData>();
    public HashMap<Integer, Machine> machineTypes = new HashMap();
    public HashMap<Player, OpenInterface> openMachines = new HashMap();
    public HashMap<String, CustomSound> customSounds = new HashMap();
    public MoneyInventory moneyInventory;
    public ConfirmGameInventory confirmGameInventory;
    public JackpotMachine jackpotMachine;
    public CrashMachine crashMachine;
    public BlackJack blackJack;
    public SelectBlocksRoutine selectBlocksRoutine;
    public InputMoneyRoutine inputMoneyRoutine;
    public WorldGuardImplementation worldGuard;
    public Random random = new Random();
    public ItemStack chairItem;
    private OdalitaMenus odalitaMenus;
    private OdalitaSlotMachineManager odalitaSlotMachineManager;
    public double[] chairOffset;
    private String plugin_integration_name;

    public static SmartGambling getInstance() {
        return instance;
    }

    // getEconomy removed; use PlayerPoints-only helpers

    public static String getMachineName(Machine machineType) {
        if (machineType instanceof SlotMachine) {
            SlotMachine slotMachine = (SlotMachine)machineType;
            return slotMachine.name;
        }
        if (machineType instanceof JackpotMachine) {
            return "lottery";
        }
        if (machineType instanceof BlackJack) {
            return "blackjack";
        }
        return "crash";
    }
    @Override
    public void onEnable() {
        instance = this;
        // 初始化 PlayerPoints（仅点券经济）
        this.setupPlayerPoints();
        if (!this.usePlayerPoints || this.playerPointsApi == null) {
            Bukkit.getConsoleSender().sendMessage(String.format("[%s] - Disabled: PlayerPoints not found!", this.getDescription().getName()));
            this.getServer().getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        // 设置插件集成名称（保持与旧逻辑一致）
        this.plugin_integration_name = "Skript";
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SmartGamblingPlaceholders(this).register();
        }
        this.configManager = new ConfigManager();
        this.saveDefaultConfig();
        this.configManager.load();
        DataManager.load();
        this.selectBlocksRoutine = new SelectBlocksRoutine();
        this.inputMoneyRoutine = new InputMoneyRoutine();
        this.getServer().getPluginManager().registerEvents((Listener)new InventoryListener(), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new BlockListener(), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new ChatListener(), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new WorldSaveListener(), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new EntityListener(), (Plugin)this);
        Objects.requireNonNull(this.getCommand("sg")).setExecutor((CommandExecutor)new MainCommand());
        Objects.requireNonNull(this.getCommand("jackpot")).setExecutor((CommandExecutor)new JackpotCommand());
        
        // 初始化OdalitaMenus框架
        initializeOdalitaMenus();
        
        // 注册OdalitaMenus测试命令
        OdalitaTestCommand odalitaTestCommand = new OdalitaTestCommand(this);
        if (odalitaSlotMachineManager != null) {
            odalitaTestCommand.setOdalitaManager(odalitaSlotMachineManager);
        }
        Objects.requireNonNull(this.getCommand("odalita")).setExecutor((CommandExecutor)odalitaTestCommand);
        Objects.requireNonNull(this.getCommand("odalita")).setTabCompleter(odalitaTestCommand);
    }

    public void onDisable() {
        for (Map.Entry<Player, OpenInterface> entry : this.openMachines.entrySet()) {
            entry.getValue().machineType.close(entry.getKey(), null);
            if (entry.getKey().getVehicle() == null || !entry.getKey().getVehicle().getType().equals((Object)EntityType.ARMOR_STAND)) continue;
            entry.getKey().getVehicle().removePassenger((Entity)entry.getKey());
        }
        WorldSaveListener.save();
        
        // Cancel jackpot machine timer task if it exists
        if (this.jackpotMachine != null && this.jackpotMachine.timerTask != null) {
            this.jackpotMachine.timerTask.cancel();
        }
        
        // Cancel crash machine timer tasks if they exist
        if (this.crashMachine != null) {
            if (this.crashMachine.timerTask != null) {
                this.crashMachine.timerTask.cancel();
            }
            if (this.crashMachine.increasingValue != null) {
                this.crashMachine.increasingValue.cancel();
            }
        }
        
        // 关闭OdalitaSlotMachineManager
        if (this.odalitaSlotMachineManager != null) {
            this.odalitaSlotMachineManager.shutdown();
        }
    }

    // Vault setup removed

    private void setupPlayerPoints() {
        try {
            if (this.getServer().getPluginManager().isPluginEnabled("PlayerPoints")) {
                // Try typical package first
                Class<?> ppMainClass;
                try {
                    ppMainClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
                } catch (ClassNotFoundException e) {
                    // Fallback to older package
                    ppMainClass = Class.forName("org.black_ixx.PlayerPoints");
                }
                Object ppInstance = ppMainClass.getMethod("getInstance").invoke(null);
                this.playerPointsApi = ppMainClass.getMethod("getAPI").invoke(ppInstance);
                this.usePlayerPoints = (this.playerPointsApi != null);
                if (this.usePlayerPoints) {
                    this.getLogger().info("PlayerPoints API detected and initialized.");
                }
            }
        } catch (Exception e) {
            this.usePlayerPoints = false;
            this.playerPointsApi = null;
            this.getLogger().warning("Failed to initialize PlayerPoints API: " + e.getMessage());
        }
    }

    // Economy helpers (PlayerPoints only)
    public static double getBalance(Player player) {
        SmartGambling plugin = getInstance();
        if (plugin.usePlayerPoints && plugin.playerPointsApi != null) {
            try {
                // Try look(UUID) -> int
                java.lang.reflect.Method lookUuid = plugin.playerPointsApi.getClass().getMethod("look", java.util.UUID.class);
                Object points = lookUuid.invoke(plugin.playerPointsApi, player.getUniqueId());
                return points instanceof Number ? ((Number) points).doubleValue() : 0.0D;
            } catch (NoSuchMethodException nsme) {
                try {
                    // Fallback getPoints(Player) -> int
                    java.lang.reflect.Method getPointsPlayer = plugin.playerPointsApi.getClass().getMethod("getPoints", org.bukkit.entity.Player.class);
                    Object points = getPointsPlayer.invoke(plugin.playerPointsApi, player);
                    return points instanceof Number ? ((Number) points).doubleValue() : 0.0D;
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints getBalance failed: " + e.getMessage());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("PlayerPoints getBalance failed: " + e.getMessage());
            }
        }
        return 0.0D;
    }

    public static double getBalance(org.bukkit.OfflinePlayer player) {
        SmartGambling plugin = getInstance();
        if (plugin.usePlayerPoints && plugin.playerPointsApi != null) {
            try {
                java.lang.reflect.Method lookUuid = plugin.playerPointsApi.getClass().getMethod("look", java.util.UUID.class);
                Object points = lookUuid.invoke(plugin.playerPointsApi, player.getUniqueId());
                return points instanceof Number ? ((Number) points).doubleValue() : 0.0D;
            } catch (Exception e) {
                plugin.getLogger().warning("PlayerPoints getBalance (offline) failed: " + e.getMessage());
            }
        }
        return 0.0D;
    }

    public static boolean withdraw(Player player, double amount) {
        SmartGambling plugin = getInstance();
        int amt = (int) Math.floor(amount);
        if (plugin.usePlayerPoints && plugin.playerPointsApi != null) {
            try {
                // Prefer take(UUID, int)
                try {
                    java.lang.reflect.Method takeUuid = plugin.playerPointsApi.getClass().getMethod("take", java.util.UUID.class, int.class);
                    Object res = takeUuid.invoke(plugin.playerPointsApi, player.getUniqueId(), amt);
                    return (res instanceof Boolean) ? (Boolean) res : true;
                } catch (NoSuchMethodException nsme) {
                    // Fallback take(Player, int)
                    java.lang.reflect.Method takePlayer = plugin.playerPointsApi.getClass().getMethod("take", org.bukkit.entity.Player.class, int.class);
                    takePlayer.invoke(plugin.playerPointsApi, player, amt);
                    return true;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("PlayerPoints withdraw failed: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    public static boolean withdraw(org.bukkit.OfflinePlayer player, double amount) {
        SmartGambling plugin = getInstance();
        int amt = (int) Math.floor(amount);
        if (plugin.usePlayerPoints && plugin.playerPointsApi != null) {
            try {
                java.lang.reflect.Method takeUuid = plugin.playerPointsApi.getClass().getMethod("take", java.util.UUID.class, int.class);
                Object res = takeUuid.invoke(plugin.playerPointsApi, player.getUniqueId(), amt);
                return (res instanceof Boolean) ? (Boolean) res : true;
            } catch (Exception e) {
                plugin.getLogger().warning("PlayerPoints withdraw (offline) failed: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    public static void deposit(Player player, double amount) {
        SmartGambling plugin = getInstance();
        int amt = (int) Math.floor(amount);
        if (plugin.usePlayerPoints && plugin.playerPointsApi != null) {
            try {
                // Prefer give(UUID, int)
                try {
                    java.lang.reflect.Method giveUuid = plugin.playerPointsApi.getClass().getMethod("give", java.util.UUID.class, int.class);
                    giveUuid.invoke(plugin.playerPointsApi, player.getUniqueId(), amt);
                    return;
                } catch (NoSuchMethodException nsme) {
                    java.lang.reflect.Method givePlayer = plugin.playerPointsApi.getClass().getMethod("give", org.bukkit.entity.Player.class, int.class);
                    givePlayer.invoke(plugin.playerPointsApi, player, amt);
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("PlayerPoints deposit failed: " + e.getMessage());
            }
        }
        // No-op if PlayerPoints not available (plugin disabled in onEnable)
    }

    public static void deposit(org.bukkit.OfflinePlayer player, double amount) {
        SmartGambling plugin = getInstance();
        int amt = (int) Math.floor(amount);
        if (plugin.usePlayerPoints && plugin.playerPointsApi != null) {
            try {
                java.lang.reflect.Method giveUuid = plugin.playerPointsApi.getClass().getMethod("give", java.util.UUID.class, int.class);
                giveUuid.invoke(plugin.playerPointsApi, player.getUniqueId(), amt);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("PlayerPoints deposit (offline) failed: " + e.getMessage());
            }
        }
        // No-op if PlayerPoints not available (plugin disabled in onEnable)
    }

    public void onLoad() {
        Plugin worldGuardPlugin = this.getServer().getPluginManager().getPlugin("WorldGuard");
        if (worldGuardPlugin != null) {
            this.worldGuard = new WorldGuardImplementation(worldGuardPlugin, (Plugin)this);
        }
    }

    public boolean checkPluginIntegration() {
        return this.getServer().getPluginManager().getPlugin(this.plugin_integration_name) == null;
    }

    public PlaybackManager getPlaybackManager() {
        return this.playbackManager;
    }

    public OdalitaMenus getOdalitaMenus() {
        return odalitaMenus;
    }
    
    public OdalitaSlotMachineManager getOdalitaSlotMachineManager() {
        return odalitaSlotMachineManager;
    }
    
    /**
     * 初始化OdalitaMenus框架
     */
    private void initializeOdalitaMenus() {
        try {
            // 检查OdalitaMenus插件是否已加载
            if (Bukkit.getPluginManager().getPlugin("OdalitaMenus") == null) {
                getLogger().warning("OdalitaMenus插件未找到，跳过OdalitaMenus框架初始化");
                return;
            }
            
            getLogger().info("开始初始化OdalitaMenus框架...");
            
            // 创建OdalitaMenus实例
            odalitaMenus = OdalitaMenus.createInstance(this);
            
            if (odalitaMenus != null) {
                getLogger().info("OdalitaMenus框架初始化成功");
                
                // 初始化OdalitaSlotMachineManager
                odalitaSlotMachineManager = new OdalitaSlotMachineManager(this);
                odalitaSlotMachineManager.initialize();
                
                getLogger().info("OdalitaSlotMachineManager初始化成功");
            } else {
                getLogger().severe("OdalitaMenus框架初始化失败：createInstance返回null");
            }
            
        } catch (Exception e) {
            getLogger().severe("OdalitaMenus框架初始化时发生异常：" + e.getMessage());
            e.printStackTrace();
            
            // 确保变量为null，避免后续使用时出错
            odalitaMenus = null;
            odalitaSlotMachineManager = null;
        }
    }
}