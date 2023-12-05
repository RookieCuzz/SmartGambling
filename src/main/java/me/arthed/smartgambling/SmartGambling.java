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
import me.arthed.smartgambling.handlers.SmartGamblingPlaceholders;
import me.arthed.smartgambling.handlers.WorldGuardImplementation;
import me.arthed.smartgambling.listeners.BlockListener;
import me.arthed.smartgambling.listeners.ChatListener;
import me.arthed.smartgambling.listeners.EntityListener;
import me.arthed.smartgambling.listeners.InventoryListener;
import me.arthed.smartgambling.listeners.WorldSaveListener;
import net.milkbowl.vault.economy.Economy;
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
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class SmartGambling
        extends JavaPlugin
        implements Listener {
    private PlaybackManager playbackManager;
    public static String PREFIX = "SmartGambling";
    public static String ECONOMY_HANDLER = "BossShopPro";
    private static SmartGambling instance;
    private static Economy vaultEconomy;
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
    public double[] chairOffset;
    private String plugin_integration_name;

    public static SmartGambling getInstance() {
        return instance;
    }

    public static Economy getEconomy() {
        return vaultEconomy;
    }

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

    public void onEnable() {
        instance = this;
        if (!this.setupEconomy()) {
            Bukkit.getConsoleSender().sendMessage(String.format("[%s] - Disabled due to no Vault dependency found!", this.getDescription().getName()));
            this.getServer().getPluginManager().disablePlugin((Plugin)this);
            return;
        }
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
    }

    public void onDisable() {
        for (Map.Entry<Player, OpenInterface> entry : this.openMachines.entrySet()) {
            entry.getValue().machineType.close(entry.getKey(), null);
            if (entry.getKey().getVehicle() == null || !entry.getKey().getVehicle().getType().equals((Object)EntityType.ARMOR_STAND)) continue;
            entry.getKey().getVehicle().removePassenger((Entity)entry.getKey());
        }
        WorldSaveListener.save();
        this.jackpotMachine.timerTask.cancel();
    }

    private boolean setupEconomy() {
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        this.plugin_integration_name = "Skript";
        RegisteredServiceProvider rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        vaultEconomy = (Economy)rsp.getProvider();
        return vaultEconomy != null;
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
}