// Decompiled with: CFR 0.152
// Class Version: 17
package me.arthed.smartgambling;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import me.arthed.smartgambling.commands.InputMoneyRoutine;
import me.arthed.smartgambling.commands.JackpotCommand;
import me.arthed.smartgambling.commands.MainCommand;
import me.arthed.smartgambling.commands.SelectBlocksRoutine;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.data.DataManager;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.economy.EconomyService;
import me.arthed.smartgambling.economy.RecoveryGate;
import me.arthed.smartgambling.economy.SQLiteEconomyService;
import me.arthed.smartgambling.economy.VaultEconomyGateway;
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
import me.arthed.smartgambling.games.slots.testing.ForcedSlotResultRegistry;
import me.arthed.smartgambling.games.poker.Poker;
import me.arthed.smartgambling.handlers.PlaceholderSnapshot;
import me.arthed.smartgambling.handlers.WorldGuardImplementation;
import me.arthed.smartgambling.integrations.OptionalIntegration;
import me.arthed.smartgambling.listeners.BlockListener;
import me.arthed.smartgambling.listeners.ChatListener;
import me.arthed.smartgambling.listeners.EntityListener;
import me.arthed.smartgambling.listeners.InventoryListener;
import me.arthed.smartgambling.listeners.WorldSaveListener;
import me.arthed.smartgambling.listeners.WorldLoadListener;
import me.arthed.smartgambling.utils.MachineTypeIds;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SmartGambling
        extends JavaPlugin
        implements Listener {
    private static final int ECONOMY_READY_POLL_LIMIT = 600;
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
    public HashMap<String, Machine> machineTypes = new HashMap();
    public HashMap<Player, OpenInterface> openMachines = new HashMap();
    public HashMap<String, CustomSound> customSounds = new HashMap();
    public MoneyInventory moneyInventory;
    public ConfirmGameInventory confirmGameInventory;
    public JackpotMachine jackpotMachine;
    public CrashMachine crashMachine;
    public BlackJack blackJack;
    public Poker poker;
    public SelectBlocksRoutine selectBlocksRoutine;
    public InputMoneyRoutine inputMoneyRoutine;
    public WorldGuardImplementation worldGuard;
    public Random random = new Random();
    public ItemStack chairItem;
    public double[] chairOffset;
    private String plugin_integration_name;
    private boolean contentInitialized;
    private boolean initializationInProgress;
    private final AtomicReference<PlaceholderSnapshot> placeholderSnapshot =
            new AtomicReference<>(PlaceholderSnapshot.empty());
    private BukkitTask placeholderSnapshotTask;
    private BukkitTask ledgerRetryTask;
    private BukkitTask economyReadyPollTask;
    private BukkitTask forcedSlotExpiryTask;
    private EconomyService economyService;
    private VaultEconomyGateway economyGateway;
    private final RecoveryGate economyRecoveryGate = new RecoveryGate();
    private boolean craftEngineReady;
    private int economyReadyPolls;
    private long runtimeGeneration;
    private final ForcedSlotResultRegistry forcedSlotResultRegistry = new ForcedSlotResultRegistry(
            Clock.systemUTC(),
            this::auditExpiredForcedSlotDirective
    );

    public static SmartGambling getInstance() {
        return instance;
    }

    public static Economy getEconomy() {
        return vaultEconomy;
    }

    public EconomyService getEconomyService() {
        if (this.economyService == null) {
            throw new IllegalStateException("The durable economy ledger is not initialized");
        }
        return this.economyService;
    }

    public long getRuntimeGeneration() {
        return this.runtimeGeneration;
    }

    public long advanceRuntimeGeneration() {
        return ++this.runtimeGeneration;
    }

    public ForcedSlotResultRegistry getForcedSlotResultRegistry() {
        return this.forcedSlotResultRegistry;
    }

    public boolean isForcedSlotResultsEnabled() {
        return this.configManager != null
                && this.configManager.getForcedSlotTestSettings().enabled();
    }

    public int getForcedSlotResultExpirySeconds() {
        return this.configManager == null
                ? 120
                : this.configManager.getForcedSlotTestSettings().expiresSeconds();
    }

    public void refreshForcedSlotTestMode() {
        if (this.isForcedSlotResultsEnabled()) {
            this.getLogger().warning(
                    "[SLOT TEST] Forced slot results are ENABLED. "
                            + "Test spins use real stakes, payouts and reward commands."
            );
        } else {
            this.clearForcedSlotResults("test mode disabled");
        }
    }

    public void auditForcedSlotDirective(
            String action,
            ForcedSlotResultRegistry.Directive directive,
            String detail
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(directive, "directive");
        String issuer = directive.issuerName().orElseGet(
                () -> directive.issuerId().map(UUID::toString).orElse("unknown")
        );
        this.getLogger().warning(
                "[SLOT TEST] action=" + auditValue(action)
                        + " directive=" + directive.directiveId()
                        + " issuer=" + auditValue(issuer)
                        + " target=" + directive.playerId()
                        + " machineType=" + auditValue(directive.machineTypeId())
                        + " symbols=" + auditValue(String.join(",", directive.symbolIds()))
                        + (detail == null || detail.isBlank()
                                ? ""
                                : " detail=" + auditValue(detail))
        );
    }

    public void clearForcedSlotResults(UUID playerId, String reason) {
        this.auditExpiredForcedSlotDirectives();
        for (ForcedSlotResultRegistry.Directive directive
                : this.forcedSlotResultRegistry.clearAll(playerId)) {
            this.auditForcedSlotDirective("cleared", directive, reason);
        }
    }

    public void clearForcedSlotResults(String reason) {
        this.auditExpiredForcedSlotDirectives();
        for (ForcedSlotResultRegistry.Directive directive
                : this.forcedSlotResultRegistry.clearAll()) {
            this.auditForcedSlotDirective("cleared", directive, reason);
        }
    }

    public void auditExpiredForcedSlotDirectives() {
        this.forcedSlotResultRegistry.purgeExpired();
    }

    private void auditExpiredForcedSlotDirective(ForcedSlotResultRegistry.Directive directive) {
        this.auditForcedSlotDirective("expired", directive, "TTL reached");
    }

    private static String auditValue(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    public static String getMachineTypeId(Machine machineType) {
        if (machineType instanceof SlotMachine) {
            SlotMachine slotMachine = (SlotMachine)machineType;
            return MachineTypeIds.normalize(slotMachine.name);
        }
        if (machineType instanceof JackpotMachine) {
            return MachineTypeIds.LOTTERY;
        }
        if (machineType instanceof BlackJack) {
            return MachineTypeIds.BLACKJACK;
        }
        if (machineType instanceof Poker) {
            return MachineTypeIds.POKER;
        }
        if (machineType instanceof CrashMachine) {
            return MachineTypeIds.CRASH;
        }
        throw new IllegalArgumentException("Unsupported machine type: "
                + (machineType == null ? "null" : machineType.getClass().getName()));
    }

    /** Compatibility alias retained for display and data serialization callers. */
    public static String getMachineName(Machine machineType) {
        return getMachineTypeId(machineType);
    }

    public Machine findMachineType(String rawId) {
        if (rawId == null) {
            return null;
        }
        try {
            return this.machineTypes.get(MachineTypeIds.normalize(rawId));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public void registerMachineType(String rawId, Machine machineType) {
        String id = MachineTypeIds.normalize(rawId);
        Machine existing = this.machineTypes.putIfAbsent(id, Objects.requireNonNull(machineType, "machineType"));
        if (existing != null && existing != machineType) {
            throw new IllegalStateException("Duplicate machine type ID '" + id + "'");
        }
    }

    public void onEnable() {
        instance = this;
        vaultEconomy = null;
        this.setupEconomy();
        this.configManager = new ConfigManager();
        this.saveDefaultConfig();
        try {
            this.economyService = SQLiteEconomyService.inDataFolder(
                    this.getDataFolder().toPath(),
                    this.economyGateway,
                    this.getLogger()
            );
            EconomyService.MigrationReport migration = this.economyService.migratePendingPayouts(
                    this.getDataFolder().toPath().resolve("pending-payouts.yml")
            );
            if (migration.status() == EconomyService.MigrationReport.Status.INVALID
                    || migration.status() == EconomyService.MigrationReport.Status.STORAGE_FAILURE) {
                throw new IllegalStateException("Could not migrate pending-payouts.yml: " + migration.detail());
            }
            this.getLogger().info("Economy ledger opened; migrated=" + migration.readyImported()
                    + ", unknown=" + migration.uncertainImported()
                    + ". Recovery is waiting for an enabled Vault economy provider.");
        } catch (RuntimeException exception) {
            this.getLogger().log(Level.SEVERE, "The SQLite economy ledger is unavailable; gambling is disabled", exception);
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.getServer().getPluginManager().registerEvents(this, this);
        this.awaitEconomyProvider();
        this.getLogger().info("Waiting for CraftEngine to finish loading custom items...");

        // With CraftEngine's recommended delayed configuration loading the
        // reload event initializes us. This fallback also covers servers that
        // disabled that option and fired the event before SmartGambling loaded.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (CraftEngineItems.loadedItems().isEmpty()) {
                this.getLogger().severe("CraftEngine did not expose any loaded items within 10 seconds.");
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            }
            this.craftEngineReady = true;
            this.initializeContentWhenReady();
        }, 200L);
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        this.craftEngineReady = true;
        if (this.contentInitialized) {
            this.getLogger().info("CraftEngine reloaded; existing SmartGambling CE item stacks remain ID-backed.");
            return;
        }
        this.initializeContentWhenReady();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!this.economyRecoveryGate.attempted()) {
            this.tryRecoverEconomy();
        }
    }

    private void initializeContentWhenReady() {
        if (!this.economyRecoveryGate.completed() || !this.craftEngineReady) {
            return;
        }
        this.initializeContent();
    }

    private void initializeContent() {
        if (!this.economyRecoveryGate.completed()
                || this.contentInitialized || this.initializationInProgress) {
            return;
        }
        this.initializationInProgress = true;
        try {
            this.configManager.load();
            DataManager.load();
            if (this.jackpotMachine != null) {
                this.jackpotMachine.activate();
            }
            this.initializePlaceholderIntegration();
            this.selectBlocksRoutine = new SelectBlocksRoutine(this);
            this.inputMoneyRoutine = new InputMoneyRoutine();
            this.playbackManager = new PlaybackManager();
            this.getServer().getPluginManager().registerEvents(this.playbackManager, this);
            this.getServer().getPluginManager().registerEvents((Listener)new InventoryListener(), (Plugin)this);
            this.getServer().getPluginManager().registerEvents((Listener)new BlockListener(), (Plugin)this);
            this.getServer().getPluginManager().registerEvents((Listener)new ChatListener(), (Plugin)this);
            this.getServer().getPluginManager().registerEvents((Listener)new WorldSaveListener(), (Plugin)this);
            this.getServer().getPluginManager().registerEvents((Listener)new WorldLoadListener(), (Plugin)this);
            this.getServer().getPluginManager().registerEvents((Listener)new EntityListener(), (Plugin)this);
            this.getServer().getPluginManager().registerEvents(this.selectBlocksRoutine, this);
            Objects.requireNonNull(this.getCommand("sg")).setExecutor((CommandExecutor)new MainCommand());
            Objects.requireNonNull(this.getCommand("jackpot")).setExecutor((CommandExecutor)new JackpotCommand());
            this.refreshForcedSlotTestMode();
            this.startForcedSlotExpiryAudit();
            this.contentInitialized = true;
            this.getLogger().info("Initialized with CraftEngine item IDs.");
        } catch (RuntimeException exception) {
            this.getLogger().log(
                    Level.SEVERE,
                    "SmartGambling could not initialize its CraftEngine items. "
                            + "Deploy the bundled SmartGambling-CraftEngine pack and migrate legacy YAML files.",
                    exception
            );
            this.getServer().getPluginManager().disablePlugin(this);
        } finally {
            this.initializationInProgress = false;
        }
    }

    private void initializePlaceholderIntegration() {
        this.refreshPlaceholderSnapshot();
        if (!OptionalIntegration.registerPapi(this, this.placeholderSnapshot::get)) {
            return;
        }
        if (this.placeholderSnapshotTask != null && !this.placeholderSnapshotTask.isCancelled()) {
            return;
        }
        this.placeholderSnapshotTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::refreshPlaceholderSnapshot,
                1L,
                1L
        );
    }

    private void refreshPlaceholderSnapshot() {
        if (this.configManager == null) {
            return;
        }
        try {
            this.placeholderSnapshot.set(
                    PlaceholderSnapshot.capture(this, this.configManager.getPlaceholderMessages())
            );
        } catch (RuntimeException exception) {
            this.getLogger().log(Level.WARNING, "Could not refresh the placeholder state snapshot", exception);
        }
    }

    public void onDisable() {
        if (this.selectBlocksRoutine != null) {
            this.selectBlocksRoutine.shutdown();
        }
        if (this.placeholderSnapshotTask != null) {
            this.placeholderSnapshotTask.cancel();
            this.placeholderSnapshotTask = null;
        }
        if (this.ledgerRetryTask != null) {
            this.ledgerRetryTask.cancel();
            this.ledgerRetryTask = null;
        }
        if (this.economyReadyPollTask != null) {
            this.economyReadyPollTask.cancel();
            this.economyReadyPollTask = null;
        }
        if (this.forcedSlotExpiryTask != null) {
            this.forcedSlotExpiryTask.cancel();
            this.forcedSlotExpiryTask = null;
        }
        OptionalIntegration.unregisterPapi(this);
        this.shutdownGamesAndRefund();
        if (this.contentInitialized) {
            WorldSaveListener.save();
        }
        if (this.economyService != null) {
            this.runShutdownStep("economy ledger", this.economyService::close);
            this.economyService = null;
        }
        vaultEconomy = null;
    }

    /** Stops every active game and refunds wagers that have not been settled. */
    public void shutdownGamesAndRefund() {
        this.clearForcedSlotResults("gameplay shutdown or successful reload");
        if (this.blackJack != null) {
            this.runShutdownStep("blackjack", this.blackJack::shutdownAndRefund);
        }
        if (this.poker != null) {
            this.runShutdownStep("poker", this.poker::shutdownAndRefund);
        }
        if (this.jackpotMachine != null) {
            this.runShutdownStep("jackpot", this.jackpotMachine::shutdownAndRefund);
        }

        Set<CrashMachine> crashMachines = Collections.newSetFromMap(new IdentityHashMap<>());
        if (this.crashMachine != null) {
            crashMachines.add(this.crashMachine);
        }
        for (MachineData machineData : this.uuidMachines.values()) {
            if (machineData.machineType instanceof CrashMachine crash) {
                crashMachines.add(crash);
            }
        }
        for (OpenInterface openInterface : this.openMachines.values()) {
            if (openInterface.machineType instanceof CrashMachine crash) {
                crashMachines.add(crash);
            }
        }
        for (CrashMachine crash : crashMachines) {
            this.runShutdownStep("crash", crash::shutdownAndRefund);
        }

        for (Map.Entry<Player, OpenInterface> entry : new ArrayList<>(this.openMachines.entrySet())) {
            Player player = entry.getKey();
            OpenInterface current = this.openMachines.get(player);
            if (current != entry.getValue()) {
                continue;
            }
            this.runShutdownStep(
                    "open session for " + player.getName(),
                    () -> entry.getValue().machineType.forceClose(player)
            );
            // forceClose releases gameplay state, but several legacy Machine
            // implementations do not actually close the Bukkit view. Close it
            // only after the mapping is gone so InventoryCloseEvent cannot
            // reopen a stale pre-reload GUI.
            if (player.isOnline()) {
                this.runShutdownStep("inventory for " + player.getName(), player::closeInventory);
            }
            if (player.getVehicle() != null && player.getVehicle().getType() == EntityType.ARMOR_STAND) {
                player.getVehicle().removePassenger(player);
            }
        }
        this.openMachines.clear();

        if (this.inputMoneyRoutine != null) {
            for (Player player : new ArrayList<>(this.inputMoneyRoutine.playersInRoutine)) {
                this.inputMoneyRoutine.cancelRoutine(player);
            }
        }
        PlaybackManager.openingPlayers.clear();
    }

    private void startForcedSlotExpiryAudit() {
        if (this.forcedSlotExpiryTask != null) {
            this.forcedSlotExpiryTask.cancel();
        }
        this.forcedSlotExpiryTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::auditExpiredForcedSlotDirectives,
                20L,
                20L
        );
    }

    private void runShutdownStep(String description, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            this.getLogger().log(Level.SEVERE, "Failed to shut down " + description, exception);
        }
    }

    private void setupEconomy() {
        this.plugin_integration_name = "Skript";
        this.economyGateway = new VaultEconomyGateway(this.getServer().getServicesManager());
    }

    private void awaitEconomyProvider() {
        if (this.tryRecoverEconomy()) {
            return;
        }
        this.economyReadyPolls = 0;
        this.economyReadyPollTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (this.tryRecoverEconomy()) {
                this.cancelEconomyReadyPoll();
                return;
            }
            if (++this.economyReadyPolls >= ECONOMY_READY_POLL_LIMIT) {
                this.cancelEconomyReadyPoll();
                this.getLogger().severe(
                        "No enabled Vault economy provider became ready within 30 seconds; gambling is disabled."
                );
                this.getServer().getPluginManager().disablePlugin(this);
            }
        }, 1L, 1L);
    }

    private boolean tryRecoverEconomy() {
        if (this.economyService == null || this.economyGateway == null) {
            return false;
        }
        Economy provider = this.economyGateway.currentProvider();
        try {
            boolean ran = this.economyRecoveryGate.runIfReady(provider != null, () -> {
                // Publish the same enabled provider used to open the recovery
                // gate for legacy balance-display calls.
                vaultEconomy = provider;
                EconomyService.RecoveryReport recovery = this.economyService.recover();
                if (recovery.callingMadeUnknown() > 0) {
                    this.getLogger().severe("Recovered " + recovery.callingMadeUnknown()
                            + " interrupted economy calls as UNKNOWN; affected players are frozen pending reconciliation.");
                }
                this.getLogger().info("Economy recovery complete; refunds queued="
                        + recovery.wagersQueuedForRefund() + ", locked payouts restored="
                        + recovery.lockedPayoutsRestored() + ", recovered payments="
                        + recovery.readyPaid() + ", ready remaining=" + recovery.readyRemaining());
            });
            if (ran) {
                this.startLedgerRetryTask();
                this.initializeContentWhenReady();
            }
            return this.economyRecoveryGate.completed();
        } catch (RuntimeException exception) {
            this.getLogger().log(
                    Level.SEVERE,
                    "Economy recovery failed after it started; it will not be replayed automatically",
                    exception
            );
            this.cancelEconomyReadyPoll();
            this.getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    private void startLedgerRetryTask() {
        if (this.ledgerRetryTask != null && !this.ledgerRetryTask.isCancelled()) {
            return;
        }
        this.ledgerRetryTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    if (this.economyGateway.isAvailable()) {
                        this.economyService.retryReady(100);
                    }
                },
                1200L,
                1200L
        );
    }

    private void cancelEconomyReadyPoll() {
        if (this.economyReadyPollTask != null) {
            this.economyReadyPollTask.cancel();
            this.economyReadyPollTask = null;
        }
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
