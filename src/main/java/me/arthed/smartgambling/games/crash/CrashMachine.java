package me.arthed.smartgambling.games.crash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.economy.EconomyService;
import me.arthed.smartgambling.economy.Money;
import me.arthed.smartgambling.economy.PlaceResult;
import me.arthed.smartgambling.economy.TxResult;
import me.arthed.smartgambling.economy.WagerHandle;
import me.arthed.smartgambling.economy.WagerKey;
import me.arthed.smartgambling.economy.WagerResolution;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.utils.DisplayUtils;
import me.arthed.smartgambling.utils.MathUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

public class CrashMachine implements Machine {
    private final ItemStack machineItem;
    private final double[] entityOffset;
    private final Inventory baseInventory;
    private final InventoryAnimations animations;
    private final String inventoryTitle;
    private final String inventoryTitleAfterBet;
    private final List<Integer> playerHeadSlots;
    private final ItemStack basePlayerHead;
    private final ItemStack crashedPlayerHead;
    private final ItemStack crashButton;
    private final ItemStack crashedButton;
    private final List<Integer> crashButtonSlots;
    private final HashMap<List<Integer>, ItemStack> nextPageItems;
    private final HashMap<List<Integer>, ItemStack> previousPageItems;
    private final HashMap<List<Integer>, ItemStack> betItems;
    private final HashMap<List<Integer>, ItemStack> removeBetItems;
    private final Button closeButton;
    private final HashSet<Player> activePlayers;
    private final int gameInventorySize;
    private final String baseGameInventoryTitle;
    private final String crashedGameInventoryTitle;
    private final String endGameInventoryTitle;
    private final Inventory baseGameInventory;
    private final Inventory crashedGameInventory;
    private final InventoryAnimations gameAnimations;
    private final String gameInventoryTitle;
    private final Inventory endGameInventory;
    private final List<Integer> gamePlayerHeadSlots;
    private final int gameDuration;
    private final int timeBetweenGames;
    private final int timeAddedOnBet;
    private final boolean original;
    private final NavigableMap<Integer, Double> chances;
    private final int totalChances;
    private final List<Double> chanceLimits;
    private final Set<BukkitTask> ownedTasks = new HashSet<>();
    private final Map<Player, OpenInterface> returningPlayers = new HashMap<>();
    /** Ledger handles are the authority; bets/crashedAt only drive the GUI. */
    private final Map<Player, WagerHandle> wagers = new HashMap<>();
    /** First requested multiplier is retained across storage retries. */
    private final Map<Player, Double> pendingCashoutMultipliers = new HashMap<>();
    private final Map<UUID, Long> placementOrdinals = new HashMap<>();
    private final Map<UUID, Long> pendingPlacementOrdinals = new HashMap<>();

    public int timeLeft;
    public final HashMap<Player, Integer> bets;
    public HashMap<Player, Double> crashedAt;
    public boolean crashing;
    public BukkitTask timerTask;
    public BukkitTask increasingValue;
    public double value;

    private boolean openingInventory;
    private boolean cooldown;
    private boolean shuttingDown;
    private boolean activated;
    private boolean locking;
    private boolean wagersLocked;
    private boolean exploded;
    private UUID machineId;
    private UUID roundId;
    private BukkitTask settlementRetryTask;

    public CrashMachine(ItemStack machineItem, double[] entityOffset, Inventory baseInventory,
                        InventoryAnimations animations, String inventoryTitle, String inventoryTitleAfterBet,
                        List<Integer> playerHeadSlots, ItemStack basePlayerHead, ItemStack crashedPlayerHead,
                        ItemStack crashButton, ItemStack crashedButton, List<Integer> crashButtonSlots,
                        HashMap<List<Integer>, ItemStack> nextPageItems,
                        HashMap<List<Integer>, ItemStack> previousPageItems,
                        HashMap<List<Integer>, ItemStack> betItems,
                        HashMap<List<Integer>, ItemStack> removeBetItems, Button closeButton,
                        Inventory baseGameInventory, Inventory crashedGameInventory,
                        InventoryAnimations gameAnimations, String gameInventoryTitle,
                        Inventory endGameInventory, List<Integer> gamePlayerHeadSlots, int gameDuration,
                        int timeBetweenGames, int timeAddedOnBet, NavigableMap<Integer, Double> chances, int totalChances,
                        List<Double> chanceLimits, boolean original, int gameInventorySize,
                        String baseGameInventoryTitle, String crashedGameInventoryTitle,
                        String endGameInventoryTitle) {
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.baseInventory = baseInventory;
        this.animations = animations;
        this.inventoryTitle = inventoryTitle;
        this.inventoryTitleAfterBet = inventoryTitleAfterBet;
        this.playerHeadSlots = playerHeadSlots;
        this.basePlayerHead = basePlayerHead;
        this.crashedPlayerHead = crashedPlayerHead;
        this.crashButton = crashButton;
        this.crashedButton = crashedButton;
        this.crashButtonSlots = crashButtonSlots;
        this.nextPageItems = nextPageItems;
        this.previousPageItems = previousPageItems;
        this.betItems = betItems;
        this.removeBetItems = removeBetItems;
        this.closeButton = closeButton;
        this.gameAnimations = gameAnimations;
        this.gameInventoryTitle = gameInventoryTitle;
        this.gamePlayerHeadSlots = gamePlayerHeadSlots;
        this.gameDuration = Math.max(1, gameDuration);
        this.timeBetweenGames = Math.max(0, timeBetweenGames);
        this.timeAddedOnBet = Math.max(0, timeAddedOnBet);
        this.original = original;
        this.chances = chances;
        this.chanceLimits = chanceLimits;
        this.gameInventorySize = gameInventorySize;
        this.baseGameInventoryTitle = baseGameInventoryTitle;
        this.crashedGameInventoryTitle = crashedGameInventoryTitle;
        this.endGameInventoryTitle = endGameInventoryTitle;
        this.bets = new HashMap<>();
        this.baseGameInventory = Bukkit.createInventory((InventoryHolder) null, this.gameInventorySize,
                this.baseGameInventoryTitle);
        this.baseGameInventory.setContents(baseGameInventory.getContents());
        this.crashedGameInventory = Bukkit.createInventory((InventoryHolder) null, this.gameInventorySize,
                this.crashedGameInventoryTitle);
        this.crashedGameInventory.setContents(crashedGameInventory.getContents());
        this.endGameInventory = Bukkit.createInventory((InventoryHolder) null, this.gameInventorySize,
                this.endGameInventoryTitle);
        this.endGameInventory.setContents(endGameInventory.getContents());
        this.activePlayers = new HashSet<>();
        this.crashedAt = new HashMap<>();
        this.totalChances = totalChances;
        this.crashedGameInventory.setContents(this.baseGameInventory.getContents());
    }

    /** Binds this clone to exactly one durable physical-machine identity. */
    public synchronized void bindMachineId(UUID machineId) {
        Objects.requireNonNull(machineId, "machineId");
        if (this.machineId != null && !this.machineId.equals(machineId)) {
            throw new IllegalStateException("Crash runtime is already bound to " + this.machineId);
        }
        if (this.activated && this.machineId == null) {
            throw new IllegalStateException("Cannot bind an already activated Crash runtime");
        }
        this.machineId = machineId;
    }

    static WagerKey createWagerKey(UUID machineId, UUID roundId, UUID playerId, long placementOrdinal) {
        if (placementOrdinal < 1L) {
            throw new IllegalArgumentException("Crash placement ordinal must be positive");
        }
        return new WagerKey(
                "crash",
                Objects.requireNonNull(machineId, "machineId").toString(),
                Objects.requireNonNull(roundId, "roundId").toString(),
                Objects.requireNonNull(playerId, "playerId"),
                "bet:" + placementOrdinal
        );
    }

    static String roundOperationId(UUID machineId, UUID roundId, String phase) {
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(roundId, "roundId");
        if (!Set.of("lock", "loss", "shutdown-refund").contains(phase)) {
            throw new IllegalArgumentException("Unsupported Crash round operation: " + phase);
        }
        return "crash:" + machineId + ':' + roundId + ':' + phase;
    }

    static String wagerOperationId(WagerHandle wager, String outcome) {
        Objects.requireNonNull(wager, "wager");
        if (!Set.of("payout", "refund").contains(outcome)) {
            throw new IllegalArgumentException("Unsupported Crash wager outcome: " + outcome);
        }
        return "crash:" + wager.id() + ':' + outcome;
    }

    static Money payoutFor(Money stake, double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0D) {
            throw new IllegalArgumentException("Crash multiplier must be finite and positive");
        }
        return Objects.requireNonNull(stake, "stake")
                .multiply(BigDecimal.valueOf(multiplier), 2);
    }

    static List<EconomyService.Resolution> lossResolutions(Collection<WagerHandle> wagers) {
        Objects.requireNonNull(wagers, "wagers");
        List<EconomyService.Resolution> resolutions = new ArrayList<>(wagers.size());
        for (WagerHandle wager : wagers) {
            resolutions.add(new EconomyService.Resolution(
                    Objects.requireNonNull(wager, "wager"),
                    WagerResolution.loss()
            ));
        }
        return List.copyOf(resolutions);
    }

    /** Starts timers only after this physical machine is durably published. */
    public synchronized void activate() {
        if (this.activated || this.shuttingDown || this.original) {
            return;
        }
        if (this.machineId == null) {
            throw new IllegalStateException("Crash runtime must be bound to a physical machine before activation");
        }
        this.activated = true;
        startTimer();
    }

    @Override
    public CrashMachine clone() {
        return new CrashMachine(this.machineItem, this.entityOffset, this.baseInventory, this.animations,
                this.inventoryTitle, this.inventoryTitleAfterBet, this.playerHeadSlots, this.basePlayerHead,
                this.crashedPlayerHead, this.crashButton, this.crashedButton, this.crashButtonSlots,
                this.nextPageItems, this.previousPageItems, this.betItems, this.removeBetItems,
                this.closeButton, this.baseGameInventory, this.crashedGameInventory, this.gameAnimations,
                this.gameInventoryTitle, this.endGameInventory, this.gamePlayerHeadSlots, this.gameDuration,
                this.timeBetweenGames, this.timeAddedOnBet, this.chances, this.totalChances, this.chanceLimits, false,
                this.gameInventorySize, this.baseGameInventoryTitle, this.crashedGameInventoryTitle,
                this.endGameInventoryTitle);
    }

    private void startTimer() {
        if (this.shuttingDown || !this.activated) {
            return;
        }
        if (!this.wagers.isEmpty()) {
            throw new IllegalStateException("Cannot start a new Crash round with unresolved wagers");
        }
        cancelTask(this.timerTask);
        this.roundId = UUID.randomUUID();
        this.locking = false;
        this.wagersLocked = false;
        this.exploded = false;
        this.pendingCashoutMultipliers.clear();
        this.placementOrdinals.clear();
        this.pendingPlacementOrdinals.clear();
        this.cooldown = false;
        this.crashing = false;
        this.timeLeft = this.gameDuration;
        ConfigManager configManager = SmartGambling.getInstance().configManager;
        this.timerTask = runTimer(() -> {
            this.timeLeft = Math.max(0, this.timeLeft - 1);
            int seconds = this.timeLeft % 60;
            String timer = configManager.messages.get("timeLeft") + this.timeLeft / 60 + ":"
                    + (seconds < 10 ? "0" : "") + seconds;
            for (Player player : new HashSet<>(this.activePlayers)) {
                if (player.isOnline()) {
                    DisplayUtils.displayActionBar(player, timer);
                }
            }
            if (this.timeLeft <= 0) {
                startGame();
            }
        }, 20L, 20L);
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        if (this.shuttingDown || player == null || openInterface == null) {
            return;
        }
        rebindPlayerState(player);
        if (this.cooldown || this.locking) {
            sendNextGameMessage(player);
            return;
        }
        if (this.crashing) {
            openRunningInventory(player, openInterface);
            return;
        }

        int requestedBet = openInterface.betAmount;
        openInterface.betAmount = 0;
        if (!this.bets.containsKey(player) && requestedBet > 0) {
            placeBet(player, requestedBet);
        }
        int bet = this.bets.getOrDefault(player, 0);
        String title = bet > 0
                ? this.inventoryTitleAfterBet.replace("%bet%", Integer.toString(bet))
                : this.inventoryTitle;
        Inventory playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), title);
        playerInventory.setContents(this.baseInventory.getContents());
        openInterface.inventory = playerInventory;
        this.animations.startAnimations(playerInventory);
        addBetsToInventory(playerInventory, this.playerHeadSlots);
        removeCustomItem(this.betItems, playerInventory);
        removeCustomItem(this.removeBetItems, playerInventory);
        addCustomItem(bet > 0 ? this.removeBetItems : this.betItems, playerInventory, player);

        this.openingInventory = true;
        try {
            player.openInventory(playerInventory);
        } finally {
            this.openingInventory = false;
        }
        SmartGambling.getInstance().openMachines.put(player, openInterface);
        this.activePlayers.add(player);
    }

    private void openRunningInventory(Player player, OpenInterface openInterface) {
        if (this.timeLeft != 0) {
            sendNextGameMessage(player);
            return;
        }
        Inventory target = this.bets.containsKey(player) && !this.crashedAt.containsKey(player)
                ? this.baseGameInventory : this.crashedGameInventory;
        this.openingInventory = true;
        try {
            player.openInventory(target);
        } finally {
            this.openingInventory = false;
        }
        openInterface.inventory = target;
        SmartGambling.getInstance().openMachines.put(player, openInterface);
        this.activePlayers.add(player);
    }

    private void sendNextGameMessage(Player player) {
        ConfigManager configManager = SmartGambling.getInstance().configManager;
        String timeUnit = this.timeLeft > 60 ? configManager.messages.get("minutes")
                : configManager.messages.get("seconds");
        String time = Integer.toString(this.timeLeft > 60 ? this.timeLeft / 60 : this.timeLeft);
        player.sendMessage(String.format(configManager.messages.get("crashNextGame"), time + " " + timeUnit));
    }

    @Override
    public void close(Player player, Inventory inventory) {
        boolean forcedClose = inventory == null;
        OpenInterface currentSession = getSession(player);
        Inventory closingInventory = inventory != null ? inventory
                : currentSession == null ? null : currentSession.inventory;
        if (this.shuttingDown) {
            stopBettingAnimation(closingInventory);
            removeSession(player);
            return;
        }
        if (this.openingInventory) {
            if (closingInventory != null && closingInventory != this.baseGameInventory
                    && closingInventory != this.crashedGameInventory && closingInventory != this.endGameInventory
                    && this.animations.isAnimated(closingInventory)) {
                this.animations.stopAnimations(closingInventory);
            }
            return;
        }
        if (this.cooldown) {
            stopBettingAnimation(closingInventory);
            removeSession(player);
            this.returningPlayers.remove(player);
            return;
        }
        if (this.crashing) {
            if (!forcedClose && (closingInventory == this.baseGameInventory
                    || closingInventory == this.crashedGameInventory)) {
                Inventory target = this.crashedAt.containsKey(player)
                        ? this.crashedGameInventory : this.baseGameInventory;
                runLater(() -> {
                    OpenInterface session = getSession(player);
                    if (player.isOnline() && this.crashing && !this.cooldown && session != null) {
                        session.inventory = target;
                        player.openInventory(target);
                    }
                }, 0L);
                return;
            }
            // A null inventory is a disconnect/forced close. The already committed bet remains in the round.
            stopBettingAnimation(closingInventory);
            removeSession(player);
            return;
        }

        // Closing the betting screen is an explicit request to withdraw the bet.
        if (this.bets.containsKey(player)) {
            removeBet(player);
        }
        stopBettingAnimation(closingInventory);
        removeSession(player);
    }

    private void stopBettingAnimation(Inventory inventory) {
        if (inventory != null && inventory != this.baseGameInventory && inventory != this.crashedGameInventory
                && inventory != this.endGameInventory && this.animations.isAnimated(inventory)) {
            this.animations.stopAnimations(inventory);
        }
    }

    private void removeSession(Player player) {
        OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
        if (current != null && current.machineType == this) {
            SmartGambling.getInstance().openMachines.remove(player);
        }
        this.activePlayers.remove(player);
    }

    private OpenInterface getSession(Player player) {
        OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
        return current != null && current.machineType == this ? current : null;
    }

    private void rebindPlayerState(Player player) {
        Player previous = this.bets.keySet().stream()
                .filter(candidate -> candidate != player
                        && candidate.getUniqueId().equals(player.getUniqueId()))
                .findFirst()
                .orElse(null);
        if (previous != null) {
            Integer amount = this.bets.remove(previous);
            if (amount != null) {
                this.bets.put(player, amount);
            }
            Double stoppedAt = this.crashedAt.remove(previous);
            if (stoppedAt != null) {
                this.crashedAt.put(player, stoppedAt);
            }
        }
        if (previous != null) {
            WagerHandle wager = this.wagers.remove(previous);
            if (wager != null) {
                this.wagers.put(player, wager);
            }
            Double multiplier = this.pendingCashoutMultipliers.remove(previous);
            if (multiplier != null) {
                this.pendingCashoutMultipliers.put(player, multiplier);
            }
        }
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || this.shuttingDown) {
            return;
        }
        if (!this.crashing && !this.cooldown && !this.locking) {
            if (this.closeButton.isClicked(event.getSlot())) {
                player.closeInventory();
                return;
            }
            if (!this.bets.containsKey(player) && isCustomItemClicked(this.betItems, event.getSlot())) {
                OpenInterface session = getSession(player);
                if (session != null) {
                    stopBettingAnimation(session.inventory);
                    SmartGambling.getInstance().moneyInventory.open(player, session);
                }
            } else if (this.bets.containsKey(player)
                    && isCustomItemClicked(this.removeBetItems, event.getSlot())) {
                removeBet(player);
            }
            return;
        }
        if (!this.crashing || this.cooldown || event.getInventory() != this.baseGameInventory
                || this.exploded || !this.crashButtonSlots.contains(event.getSlot())
                || this.crashedAt.containsKey(player)) {
            return;
        }
        for (int slot : this.gamePlayerHeadSlots) {
            ItemStack item = getItemSafely(this.baseGameInventory, slot);
            if (item == null || item.getType() != Material.PLAYER_HEAD || !(item.getItemMeta() instanceof SkullMeta meta)
                    || !Objects.equals(meta.getOwningPlayer(), player)) {
                continue;
            }
            settleCashout(player, true);
            return;
        }
    }

    private boolean settleCashout(Player player, boolean updateInterface) {
        if (!this.wagersLocked) {
            return false;
        }
        WagerHandle wager = this.wagers.get(player);
        if (wager == null) {
            return this.crashedAt.containsKey(player);
        }
        double multiplier = this.pendingCashoutMultipliers.computeIfAbsent(
                player,
                ignored -> MathUtils.roundDecimals(this.value)
        );
        if (!Double.isFinite(multiplier) || multiplier <= 0.0D) {
            this.pendingCashoutMultipliers.remove(player);
            return false;
        }

        Money payout;
        TxResult result;
        try {
            payout = payoutFor(wager.stake(), multiplier);
            result = SmartGambling.getInstance().getEconomyService().resolve(
                    wagerOperationId(wager, "payout"),
                    wager,
                    WagerResolution.payout(payout)
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not journal Crash cashout for " + player.getName(),
                    exception
            );
            return false;
        }
        if (!result.durable()) {
            logTxFailure("cashout", result);
            return false;
        }

        this.wagers.remove(player, wager);
        this.pendingCashoutMultipliers.remove(player);
        boolean newlyCashedOut = this.crashedAt.putIfAbsent(player, multiplier) == null;
        boolean providerApplied = result.status() == TxResult.Status.DURABLE
                || result.status() == TxResult.Status.ALREADY_APPLIED;
        if (newlyCashedOut && updateInterface && providerApplied) {
            try {
                markCashoutInGui(player, multiplier, payout);
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(
                        Level.WARNING,
                        "Crash cashout was durable but its presentation failed for " + player.getName(),
                    exception
                );
            }
        } else if (newlyCashedOut && updateInterface && player.isOnline()) {
            player.sendMessage(ChatColor.RED + "已锁定以 " + multiplier
                    + " 倍兑现，但派奖仍等待账本核对（"
                    + result.status() + "）。");
        }
        return true;
    }

    private void markCashoutInGui(Player player, double multiplier, Money payout) {
        for (int slot : this.gamePlayerHeadSlots) {
            ItemStack item = getItemSafely(this.baseGameInventory, slot);
            if (!(item != null && item.getItemMeta() instanceof SkullMeta meta)
                    || meta.getOwningPlayer() == null
                    || !meta.getOwningPlayer().getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            ItemStack newHead = getCrashedPlayerHead(player);
            setItemSafely(this.baseGameInventory, slot, newHead);
            setItemSafely(this.crashedGameInventory, slot, newHead);
        }
        if (!player.isOnline()) {
            return;
        }
        OpenInterface session = getSession(player);
        if (session != null) {
            this.openingInventory = true;
            try {
                player.openInventory(this.crashedGameInventory);
            } finally {
                this.openingInventory = false;
            }
            session.inventory = this.crashedGameInventory;
        }
        CustomSound sound = SmartGambling.getInstance().customSounds.get("crashWin");
        if (sound != null) {
            sound.play(player);
        }
        double amountWon = payout.vaultAmount();
        double balance = SmartGambling.getEconomy().getBalance(player);
        player.sendMessage(String.format(message("crashWin"), amountWon, multiplier, this.value));
        player.sendMessage(String.format(message("wonMoney"), amountWon, balance));
    }

    private boolean isCustomItemClicked(HashMap<List<Integer>, ItemStack> itemMap, int clickedSlot) {
        for (List<Integer> slots : itemMap.keySet()) {
            if (slots.contains(clickedSlot)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ItemStack getMachineItem() {
        return this.machineItem;
    }

    @Override
    public double[] getMachineEntityOffset() {
        return this.entityOffset;
    }

    private boolean wagerCacheComplete() {
        if (this.wagers.size() != this.bets.size()) {
            return false;
        }
        for (Map.Entry<Player, Integer> entry : this.bets.entrySet()) {
            WagerHandle wager = this.wagers.get(entry.getKey());
            if (wager == null || wager.stake().compareTo(Money.of((long) entry.getValue())) != 0) {
                return false;
            }
        }
        return true;
    }

    private TxResult lockRound() {
        try {
            return SmartGambling.getInstance().getEconomyService().lockAll(
                    roundOperationId(this.machineId, this.roundId, "lock"),
                    List.copyOf(this.wagers.values())
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not lock Crash round " + this.roundId,
                    exception
            );
            return new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
    }

    private void logTxFailure(String phase, TxResult result) {
        SmartGambling.getInstance().getLogger().severe(
                "Crash " + phase + " was not durable for machine " + this.machineId
                        + ", round " + this.roundId + ": status="
                        + (result == null ? "null" : result.status()) + ", detail="
                        + (result == null ? "null" : result.detail())
        );
    }

    private void startGame() {
        if (this.shuttingDown || this.crashing || this.cooldown) {
            return;
        }
        if (this.bets.isEmpty()) {
            cancelTask(this.timerTask);
            startTimer();
            return;
        }
        if (!wagerCacheComplete()) {
            this.locking = true;
            SmartGambling.getInstance().getLogger().severe(
                    "Crash round " + this.roundId + " has GUI bets without matching durable wager handles"
            );
            return;
        }
        this.locking = true;
        TxResult lockResult = lockRound();
        if (!lockResult.durable()) {
            logTxFailure("lock", lockResult);
            return;
        }

        cancelTask(this.timerTask);
        this.locking = false;
        this.wagersLocked = true;
        this.crashing = true;
        this.timeLeft = 0;
        Set<Player> players = new HashSet<>(this.activePlayers);
        this.openingInventory = true;
        try {
            for (Player player : players) {
                OpenInterface session = getSession(player);
                if (session == null || !player.isOnline()) {
                    this.activePlayers.remove(player);
                    continue;
                }
                Inventory target = this.bets.containsKey(player)
                        ? this.baseGameInventory : this.crashedGameInventory;
                player.openInventory(target);
                session.inventory = target;
            }
        } finally {
            this.openingInventory = false;
        }

        double valueToCrash = selectCrashValue();
        this.gameAnimations.startAnimations(this.baseGameInventory);
        this.gameAnimations.startAnimations(this.crashedGameInventory);
        addBetsToInventory(this.baseGameInventory, this.gamePlayerHeadSlots);
        addBetsToInventory(this.crashedGameInventory, this.gamePlayerHeadSlots);
        for (int slot : this.crashButtonSlots) {
            setItemSafely(this.baseGameInventory, slot, this.crashButton.clone());
            setItemSafely(this.crashedGameInventory, slot, this.crashedButton.clone());
        }

        this.value = 0.0D;
        ItemStack crashItem = this.crashButtonSlots.isEmpty() ? null
                : getItemSafely(this.baseGameInventory, this.crashButtonSlots.get(0));
        ItemStack crashedItem = this.crashButtonSlots.isEmpty() ? null
                : getItemSafely(this.crashedGameInventory, this.crashButtonSlots.get(0));
        ItemMeta crashMeta = crashItem == null ? null : crashItem.getItemMeta();
        ItemMeta crashedMeta = crashedItem == null ? null : crashedItem.getItemMeta();
        String baseName = this.crashButton.hasItemMeta() && this.crashButton.getItemMeta().hasDisplayName()
                ? this.crashButton.getItemMeta().getDisplayName() : "%value%";
        double finalValueToCrash = MathUtils.roundDecimals(valueToCrash);
        this.increasingValue = runTimer(() -> {
            this.value = MathUtils.roundDecimals(this.value + 0.01D);
            String newName = baseName.replace("%value%", Double.toString(this.value));
            if (crashMeta != null) {
                crashMeta.setDisplayName(newName);
            }
            if (crashedMeta != null) {
                crashedMeta.setDisplayName(newName);
            }
            for (int slot : this.crashButtonSlots) {
                ItemStack activeButton = getItemSafely(this.baseGameInventory, slot);
                ItemStack stoppedButton = getItemSafely(this.crashedGameInventory, slot);
                if (activeButton != null && crashMeta != null) {
                    activeButton.setItemMeta(crashMeta);
                }
                if (stoppedButton != null && crashedMeta != null) {
                    stoppedButton.setItemMeta(crashedMeta);
                }
            }
            for (Player player : new HashSet<>(this.activePlayers)) {
                if (player.isOnline()) {
                    player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.1F, 0.5F);
                }
            }
            if (this.value >= finalValueToCrash) {
                this.value = finalValueToCrash;
                stopGame();
            }
        }, 1L, 1L);
    }

    private double selectCrashValue() {
        if (this.totalChances <= 0 || this.chances == null || this.chances.isEmpty()) {
            return 1.2D;
        }
        Map.Entry<Integer, Double> selected = this.chances.higherEntry(
                SmartGambling.getInstance().random.nextInt(this.totalChances));
        if (selected == null) {
            selected = this.chances.lastEntry();
        }
        double maximum = selected.getValue();
        double minimum = 0.0D;
        for (int i = 0; i < this.chanceLimits.size(); ++i) {
            if (Double.compare(this.chanceLimits.get(i), maximum) == 0) {
                minimum = i == 0 ? 0.0D : this.chanceLimits.get(i - 1);
                break;
            }
        }
        if (maximum <= minimum) {
            return Math.max(0.01D, maximum);
        }
        return SmartGambling.getInstance().random.nextDouble(minimum, maximum);
    }

    private void stopGame() {
        if (this.shuttingDown || !this.crashing || this.cooldown) {
            return;
        }
        // Financial retries may keep the round visible, but the crash point is
        // final: no new cashout request may enter after this line.
        this.exploded = true;
        cancelTask(this.increasingValue);
        this.gameAnimations.stopAnimations(this.baseGameInventory);
        this.gameAnimations.stopAnimations(this.crashedGameInventory);

        for (Player player : new HashSet<>(this.pendingCashoutMultipliers.keySet())) {
            settleCashout(player, true);
        }
        if (!this.pendingCashoutMultipliers.isEmpty()) {
            scheduleSettlementRetry();
            return;
        }

        if (!this.wagers.isEmpty()) {
            TxResult lossResult;
            try {
                lossResult = SmartGambling.getInstance().getEconomyService().resolveAll(
                        roundOperationId(this.machineId, this.roundId, "loss"),
                        lossResolutions(this.wagers.values())
                );
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(
                        Level.SEVERE,
                        "Could not journal Crash losses for round " + this.roundId,
                        exception
                );
                lossResult = new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
            }
            if (!lossResult.durable()) {
                logTxFailure("loss", lossResult);
                scheduleSettlementRetry();
                return;
            }
            this.wagers.clear();
        }
        this.bets.clear();

        this.cooldown = true;
        this.crashing = true;
        this.timeLeft = this.timeBetweenGames;
        this.endGameInventory.setContents(this.crashedGameInventory.getContents());
        this.openingInventory = true;
        try {
            for (Player player : new HashSet<>(this.activePlayers)) {
                OpenInterface session = getSession(player);
                if (session == null || !player.isOnline()) {
                    this.activePlayers.remove(player);
                    continue;
                }
                player.openInventory(this.endGameInventory);
                session.inventory = this.endGameInventory;
            }
        } finally {
            this.openingInventory = false;
        }
        this.crashedAt.clear();
        long resultDisplayTicks = Math.min(100L, (long) this.timeBetweenGames * 20L);
        runLater(this::restartGame, resultDisplayTicks);
    }

    private void scheduleSettlementRetry() {
        if (this.shuttingDown || this.settlementRetryTask != null
                && !this.settlementRetryTask.isCancelled()) {
            return;
        }
        this.settlementRetryTask = runLater(() -> {
            this.settlementRetryTask = null;
            stopGame();
        }, 20L);
    }

    private void restartGame() {
        if (this.shuttingDown) {
            return;
        }
        this.returningPlayers.clear();
        Set<Player> players = new HashSet<>(this.activePlayers);
        this.openingInventory = true;
        try {
            for (Player player : players) {
                OpenInterface session = getSession(player);
                if (session != null && player.isOnline()) {
                    this.returningPlayers.put(player, session);
                    player.closeInventory();
                } else {
                    removeSession(player);
                }
            }
        } finally {
            this.openingInventory = false;
        }
        this.activePlayers.clear();
        clearGameHeads();
        int displayedSeconds = Math.min(5, this.timeBetweenGames);
        beginCooldown(Math.max(0, this.timeBetweenGames - displayedSeconds));
    }

    private void beginCooldown(int remainingSeconds) {
        this.cooldown = true;
        this.crashing = true;
        this.timeLeft = remainingSeconds;
        cancelTask(this.timerTask);
        if (this.timeLeft <= 0) {
            finishCooldown();
            return;
        }
        this.timerTask = runTimer(() -> {
            this.timeLeft = Math.max(0, this.timeLeft - 1);
            if (this.timeLeft <= 0) {
                cancelTask(this.timerTask);
                finishCooldown();
            }
        }, 20L, 20L);
    }

    private void finishCooldown() {
        if (this.shuttingDown) {
            return;
        }
        this.cooldown = false;
        this.crashing = false;
        startTimer();
        Map<Player, OpenInterface> players = new HashMap<>(this.returningPlayers);
        this.returningPlayers.clear();
        for (Map.Entry<Player, OpenInterface> entry : players.entrySet()) {
            Player player = entry.getKey();
            if (player.isOnline() && SmartGambling.getInstance().openMachines.get(player) == entry.getValue()) {
                open(player, entry.getValue());
            } else {
                removeSession(player);
            }
        }
    }

    private void clearGameHeads() {
        for (int slot : this.gamePlayerHeadSlots) {
            setItemSafely(this.baseGameInventory, slot, null);
            setItemSafely(this.crashedGameInventory, slot, null);
        }
    }

    public void addCustomItem(HashMap<List<Integer>, ItemStack> itemMap, Inventory inventory, Player player) {
        for (Map.Entry<List<Integer>, ItemStack> entry : itemMap.entrySet()) {
            ItemStack item = entry.getValue().clone();
            Integer bet = this.bets.get(player);
            if (bet != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    if (meta.hasDisplayName()) {
                        meta.setDisplayName(replacePlaceholders(meta.getDisplayName(), player, bet));
                    }
                    if (meta.hasLore()) {
                        List<String> lore = meta.getLore();
                        if (lore != null) {
                            for (int i = 0; i < lore.size(); ++i) {
                                lore.set(i, replacePlaceholders(lore.get(i), player, bet));
                            }
                            meta.setLore(lore);
                        }
                    }
                    item.setItemMeta(meta);
                }
            }
            for (int slot : entry.getKey()) {
                setItemSafely(inventory, slot, item);
            }
        }
    }

    public void removeCustomItem(HashMap<List<Integer>, ItemStack> itemMap, Inventory inventory) {
        for (List<Integer> slots : itemMap.keySet()) {
            for (int slot : slots) {
                setItemSafely(inventory, slot, null);
            }
        }
    }

    private long placementOrdinal(UUID playerId) {
        Long pending = this.pendingPlacementOrdinals.get(playerId);
        if (pending != null) {
            return pending;
        }
        long next = Math.addExact(this.placementOrdinals.getOrDefault(playerId, 0L), 1L);
        this.placementOrdinals.put(playerId, next);
        this.pendingPlacementOrdinals.put(playerId, next);
        return next;
    }

    public void placeBet(Player player, int amount) {
        rebindPlayerState(player);
        if (this.shuttingDown || this.crashing || this.cooldown || this.locking
                || this.bets.containsKey(player) || amount <= 0
                || this.machineId == null || this.roundId == null) {
            return;
        }
        int capacity = Math.min(this.playerHeadSlots.size(), this.gamePlayerHeadSlots.size());
        if (capacity <= 0 || this.bets.size() >= capacity) {
            player.sendMessage(ChatColor.RED + "本轮爆点游戏参与人数已满。");
            return;
        }
        double balance = SmartGambling.getEconomy().getBalance(player);
        PlaceResult placement;
        try {
            long ordinal = placementOrdinal(player.getUniqueId());
            placement = SmartGambling.getInstance().getEconomyService().place(
                    createWagerKey(this.machineId, this.roundId, player.getUniqueId(), ordinal),
                    Money.of((long) amount)
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not journal Crash bet for " + player.getName(),
                    exception
            );
            player.sendMessage(ChatColor.RED + "资金账本当前不可用，本次下注未提交。");
            return;
        }
        if (!placement.accepted() || placement.wager() == null) {
            if (placement.status() == PlaceResult.Status.REJECTED) {
                this.pendingPlacementOrdinals.remove(player.getUniqueId());
                DisplayUtils.displayActionBar(player,
                        String.format(message("notEnoughMoneyActionBar"), amount, balance));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
            } else {
                player.sendMessage(ChatColor.RED + "下注未被接受，本轮游戏未开始。");
            }
            SmartGambling.getInstance().getLogger().warning(
                    "Crash wager rejected for " + player.getName() + ": "
                            + placement.status() + " (" + placement.detail() + ')'
            );
            return;
        }
        WagerHandle wager = placement.wager();
        if (wager.stake().compareTo(Money.of((long) amount)) != 0) {
            SmartGambling.getInstance().getLogger().severe(
                    "Crash wager replay has a different stake for " + player.getName()
            );
            TxResult mismatchRefund = resolveWager(wager, WagerResolution.refund(), "refund");
            if (mismatchRefund.durable()) {
                this.pendingPlacementOrdinals.remove(player.getUniqueId());
            }
            return;
        }
        this.wagers.put(player, wager);
        this.bets.put(player, amount);
        this.pendingPlacementOrdinals.remove(player.getUniqueId());
        player.sendMessage(String.format(message("moneyExtracted"), amount,
                SmartGambling.getEconomy().getBalance(player)));
        if (this.timeAddedOnBet > 0 && this.timeLeft < 30) {
            this.timeLeft = Math.min(this.gameDuration, this.timeLeft + this.timeAddedOnBet);
        }
        updatePlayerBets();
    }

    public boolean removeBet(Player player) {
        rebindPlayerState(player);
        Integer bet = this.bets.get(player);
        WagerHandle wager = this.wagers.get(player);
        if (bet == null || wager == null || this.crashing || this.cooldown
                || this.locking || this.shuttingDown) {
            return false;
        }
        TxResult result = resolveWager(wager, WagerResolution.refund(), "refund");
        if (!result.durable()) {
            logTxFailure("bet removal refund", result);
            return false;
        }
        this.wagers.remove(player, wager);
        this.bets.remove(player);
        if (result.status() == TxResult.Status.DURABLE && player.isOnline()) {
            player.sendMessage(String.format(message("moneyReceived"), bet,
                    SmartGambling.getEconomy().getBalance(player)));
        }
        updatePlayerBets();
        return true;
    }

    private TxResult resolveWager(WagerHandle wager, WagerResolution resolution, String outcome) {
        try {
            return SmartGambling.getInstance().getEconomyService().resolve(
                    wagerOperationId(wager, outcome),
                    wager,
                    resolution
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not journal Crash " + outcome + " for wager " + wager.id(),
                    exception
            );
            return new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
    }

    public void updatePlayerBets() {
        for (Player player : new HashSet<>(this.activePlayers)) {
            OpenInterface session = getSession(player);
            if (session == null || session.inventory == null || session.inventory == this.baseGameInventory
                    || session.inventory == this.crashedGameInventory || session.inventory == this.endGameInventory) {
                continue;
            }
            Inventory inventory = session.inventory;
            addBetsToInventory(inventory, this.playerHeadSlots);
            removeCustomItem(this.betItems, inventory);
            removeCustomItem(this.removeBetItems, inventory);
            addCustomItem(this.bets.containsKey(player) ? this.removeBetItems : this.betItems,
                    inventory, player);
        }
    }

    public void addBetsToInventory(Inventory inventory, List<Integer> headSlots) {
        for (int slot : headSlots) {
            setItemSafely(inventory, slot, null);
        }
        int limit = Math.min(headSlots.size(), this.bets.size());
        int index = 0;
        for (Player opponent : this.bets.keySet()) {
            if (index >= limit) {
                break;
            }
            setItemSafely(inventory, headSlots.get(index), getPlayerHead(opponent));
            ++index;
        }
    }

    public ItemStack getPlayerHead(Player player) {
        int bet = this.bets.getOrDefault(player, 0);
        ItemStack playerItem = this.basePlayerHead.clone();
        ItemMeta rawMeta = playerItem.getItemMeta();
        if (!(rawMeta instanceof SkullMeta meta)) {
            return playerItem;
        }
        meta.setOwningPlayer(player);
        if (meta.hasDisplayName()) {
            meta.setDisplayName(replacePlaceholders(meta.getDisplayName(), player, bet));
        }
        List<String> lore = meta.getLore();
        if (lore != null) {
            for (int i = 0; i < lore.size(); ++i) {
                lore.set(i, replacePlaceholders(lore.get(i), player, bet));
            }
            meta.setLore(lore);
        }
        playerItem.setItemMeta(meta);
        return playerItem;
    }

    public ItemStack getCrashedPlayerHead(Player player) {
        int bet = this.bets.getOrDefault(player, 0);
        ItemStack playerItem = this.crashedPlayerHead.clone();
        ItemMeta meta = playerItem.getItemMeta();
        if (meta == null) {
            return playerItem;
        }
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
        }
        if (meta.hasDisplayName()) {
            meta.setDisplayName(replacePlaceholders(meta.getDisplayName(), player, bet));
        }
        List<String> lore = meta.getLore();
        if (lore != null) {
            for (int i = 0; i < lore.size(); ++i) {
                lore.set(i, replacePlaceholders(lore.get(i), player, bet));
            }
            meta.setLore(lore);
        }
        playerItem.setItemMeta(meta);
        return playerItem;
    }

    private String replacePlaceholders(String string, Player player, int bet) {
        return string.replace("%name%", player.getName()).replace("%bet%", Integer.toString(bet))
                .replace("%crash%", Double.toString(this.crashedAt.getOrDefault(player, 0.0D)));
    }

    public void shutdownAndRefund() {
        this.shuttingDown = true;
        this.activated = false;
        cancelAllTasks();
        stopAllAnimations();
        settleShutdownWagers();

        Set<Player> players = new HashSet<>(this.activePlayers);
        players.addAll(this.returningPlayers.keySet());
        this.openingInventory = true;
        try {
            for (Player player : players) {
                OpenInterface session = getSession(player);
                if (session != null) {
                    SmartGambling.getInstance().openMachines.remove(player);
                    if (player.isOnline()) {
                        player.closeInventory();
                    }
                }
            }
        } finally {
            this.openingInventory = false;
        }
        this.activePlayers.clear();
        this.returningPlayers.clear();
        this.crashedAt.clear();
        this.crashing = false;
        this.cooldown = false;
    }

    /** True when an administrator must retry shutdown before discarding this machine instance. */
    public boolean hasOutstandingFunds() {
        return !this.wagers.isEmpty();
    }

    private void settleShutdownWagers() {
        // A cashout request fixes the payout payload. Never replace it with a
        // contradictory principal refund merely because the first response failed.
        for (Player player : new HashSet<>(this.pendingCashoutMultipliers.keySet())) {
            settleCashout(player, false);
        }

        Map<Player, WagerHandle> refundable = new HashMap<>();
        for (Map.Entry<Player, WagerHandle> entry : this.wagers.entrySet()) {
            if (!this.pendingCashoutMultipliers.containsKey(entry.getKey())) {
                refundable.put(entry.getKey(), entry.getValue());
            }
        }
        if (!refundable.isEmpty()) {
            List<EconomyService.Resolution> resolutions = new ArrayList<>(refundable.size());
            for (WagerHandle wager : refundable.values()) {
                resolutions.add(new EconomyService.Resolution(wager, WagerResolution.refund()));
            }
            TxResult result;
            try {
                result = SmartGambling.getInstance().getEconomyService().resolveAll(
                        roundOperationId(this.machineId, this.roundId, "shutdown-refund"),
                        resolutions
                );
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(
                        Level.SEVERE,
                        "Could not journal Crash shutdown refunds for round " + this.roundId,
                        exception
                );
                result = new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
            }
            if (result.durable()) {
                for (Map.Entry<Player, WagerHandle> entry : refundable.entrySet()) {
                    this.wagers.remove(entry.getKey(), entry.getValue());
                    Integer bet = this.bets.remove(entry.getKey());
                    if (result.status() == TxResult.Status.DURABLE
                            && bet != null && entry.getKey().isOnline()) {
                        entry.getKey().sendMessage(String.format(message("moneyReceived"), bet,
                                SmartGambling.getEconomy().getBalance(entry.getKey())));
                    }
                }
            } else {
                logTxFailure("shutdown refund", result);
            }
        }
        // Wagers already cashed out are absent from the authoritative map and
        // must never be refunded as principal during shutdown.
        this.bets.keySet().removeIf(player -> !this.wagers.containsKey(player));
    }

    private void stopAllAnimations() {
        if (this.gameAnimations.isAnimated(this.baseGameInventory)) {
            this.gameAnimations.stopAnimations(this.baseGameInventory);
        }
        if (this.gameAnimations.isAnimated(this.crashedGameInventory)) {
            this.gameAnimations.stopAnimations(this.crashedGameInventory);
        }
        for (Player player : new HashSet<>(this.activePlayers)) {
            OpenInterface session = getSession(player);
            if (session != null && session.inventory != null && session.inventory != this.baseGameInventory
                    && session.inventory != this.crashedGameInventory && session.inventory != this.endGameInventory
                    && this.animations.isAnimated(session.inventory)) {
                this.animations.stopAnimations(session.inventory);
            }
        }
    }

    private BukkitTask runTimer(Runnable runnable, long delay, long period) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(SmartGambling.getInstance(), () -> {
            if (!this.shuttingDown) {
                runnable.run();
            }
        }, delay, period);
        this.ownedTasks.add(task);
        return task;
    }

    private BukkitTask runLater(Runnable runnable, long delay) {
        BukkitTask[] reference = new BukkitTask[1];
        reference[0] = Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            this.ownedTasks.remove(reference[0]);
            if (!this.shuttingDown) {
                runnable.run();
            }
        }, delay);
        this.ownedTasks.add(reference[0]);
        return reference[0];
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
            this.ownedTasks.remove(task);
        }
    }

    private void cancelAllTasks() {
        for (BukkitTask task : new HashSet<>(this.ownedTasks)) {
            task.cancel();
        }
        this.ownedTasks.clear();
        if (this.timerTask != null) {
            this.timerTask.cancel();
        }
        if (this.increasingValue != null) {
            this.increasingValue.cancel();
        }
    }

    private ItemStack getItemSafely(Inventory inventory, int slot) {
        return slot >= 0 && slot < inventory.getSize() ? inventory.getItem(slot) : null;
    }

    private void setItemSafely(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private String message(String key) {
        String value = SmartGambling.getInstance().configManager.messages.get(key);
        return value == null ? key : value;
    }
}
