package me.arthed.smartgambling.games.jackpot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

public class JackpotMachine implements Machine {
    static final String LEDGER_MACHINE_ID = "global";
    private final ItemStack machineItem;
    private final double[] entityOffset;
    private final Inventory baseInventory;
    private final InventoryAnimations animations;
    private final String inventoryTitle;
    private final String inventoryTitleAfterBet;
    private final List<Integer> playerHeadSlots;
    private final ItemStack basePlayerHead;
    private final HashMap<List<Integer>, ItemStack> nextPageItems;
    private final HashMap<List<Integer>, ItemStack> previousPageItems;
    private final HashMap<List<Integer>, ItemStack> betItems;
    private final HashMap<List<Integer>, ItemStack> removeBetItems;
    private final Button closeButton;
    private final HashSet<Player> activePlayers;
    private final Inventory baseGameInventory;
    private final InventoryAnimations gameAnimations;
    private final String gameInventoryTitle;
    private final List<Integer> gamePlayerHeadSlots;
    private final int winningHeadSlot;
    private final int gameDuration;
    private final int timeBetweenGames;
    private final int timeAddedOnBet;
    private final int animationDuration;
    private final Set<BukkitTask> ownedTasks = new HashSet<>();
    /** Ledger handles are authoritative; bets/totalBets are GUI caches. */
    private final Map<Player, WagerHandle> wagers = new HashMap<>();
    private final Map<UUID, Long> placementOrdinals = new HashMap<>();
    private final Map<UUID, Long> pendingPlacementOrdinals = new HashMap<>();

    public int timeLeft;
    public final HashMap<Player, Integer> bets;
    private NavigableMap<Integer, ItemStack> weighedBets;
    public int totalBets;
    public boolean spinning;
    public BukkitTask timerTask;

    private int animationSpeed;
    private boolean activated;
    private boolean openingInventory;
    private boolean cooldown;
    private boolean shuttingDown;
    private boolean locking;
    private boolean wagersLocked;
    private boolean resultChosen;
    private UUID roundId;
    private UUID settlementWinnerId;
    private BukkitTask settlementRetryTask;

    public JackpotMachine(ItemStack machineItem, double[] entityOffset, Inventory baseInventory,
                          InventoryAnimations animations, String inventoryTitle, String inventoryTitleAfterBet,
                          List<Integer> playerHeadSlots, ItemStack basePlayerHead,
                          HashMap<List<Integer>, ItemStack> nextPageItems,
                          HashMap<List<Integer>, ItemStack> previousPageItems,
                          HashMap<List<Integer>, ItemStack> betItems,
                          HashMap<List<Integer>, ItemStack> removeBetItems, Button closeButton,
                          Inventory baseGameInventory, String gameInventoryTitle,
                          InventoryAnimations gameAnimations, List<Integer> gamePlayerHeadSlots,
                          int winningHeadSlot, int gameDuration, int timeBetweenGames,
                          int timeAddedOnBet, int animationDuration) {
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.baseInventory = baseInventory;
        this.animations = animations;
        this.inventoryTitle = inventoryTitle;
        this.inventoryTitleAfterBet = inventoryTitleAfterBet;
        this.playerHeadSlots = playerHeadSlots;
        this.basePlayerHead = basePlayerHead;
        this.nextPageItems = nextPageItems;
        this.previousPageItems = previousPageItems;
        this.betItems = betItems;
        this.removeBetItems = removeBetItems;
        this.closeButton = closeButton;
        this.baseGameInventory = baseGameInventory;
        this.gameAnimations = gameAnimations;
        this.gameInventoryTitle = gameInventoryTitle;
        this.gamePlayerHeadSlots = gamePlayerHeadSlots;
        this.winningHeadSlot = winningHeadSlot;
        this.gameDuration = Math.max(1, gameDuration);
        this.timeBetweenGames = Math.max(0, timeBetweenGames);
        this.timeAddedOnBet = Math.max(0, timeAddedOnBet);
        this.animationDuration = Math.max(0, animationDuration);
        this.bets = new HashMap<>();
        this.activePlayers = new HashSet<>();
        this.timeLeft = this.gameDuration;
    }

    static WagerKey createWagerKey(UUID roundId, UUID playerId, long placementOrdinal) {
        if (placementOrdinal < 1L) {
            throw new IllegalArgumentException("Jackpot placement ordinal must be positive");
        }
        return new WagerKey(
                "jackpot",
                LEDGER_MACHINE_ID,
                Objects.requireNonNull(roundId, "roundId").toString(),
                Objects.requireNonNull(playerId, "playerId"),
                "ticket:" + placementOrdinal
        );
    }

    static String roundOperationId(UUID roundId, String phase) {
        Objects.requireNonNull(roundId, "roundId");
        if (!Set.of("lock", "result", "shutdown-refund").contains(phase)) {
            throw new IllegalArgumentException("Unsupported Jackpot round operation: " + phase);
        }
        return "jackpot:" + LEDGER_MACHINE_ID + ':' + roundId + ':' + phase;
    }

    static String wagerRefundOperationId(WagerHandle wager) {
        return "jackpot:" + Objects.requireNonNull(wager, "wager").id() + ":refund";
    }

    static Money totalStake(Collection<WagerHandle> wagers) {
        Objects.requireNonNull(wagers, "wagers");
        Money total = null;
        for (WagerHandle wager : wagers) {
            Objects.requireNonNull(wager, "wager");
            total = total == null ? wager.stake() : total.add(wager.stake());
        }
        if (total == null) {
            throw new IllegalArgumentException("Jackpot wager batch cannot be empty");
        }
        return total;
    }

    static List<EconomyService.Resolution> resultResolutions(
            Collection<WagerHandle> wagers,
            UUID winnerId
    ) {
        Objects.requireNonNull(winnerId, "winnerId");
        Money pot = totalStake(wagers);
        List<EconomyService.Resolution> resolutions = new ArrayList<>(wagers.size());
        boolean winnerFound = false;
        for (WagerHandle wager : wagers) {
            boolean winner = wager.playerId().equals(winnerId);
            winnerFound |= winner;
            resolutions.add(new EconomyService.Resolution(
                    wager,
                    winner ? WagerResolution.payout(pot) : WagerResolution.loss()
            ));
        }
        if (!winnerFound) {
            throw new IllegalArgumentException("Jackpot winner has no wager in this round");
        }
        return List.copyOf(resolutions);
    }

    static List<EconomyService.Resolution> refundResolutions(Collection<WagerHandle> wagers) {
        Objects.requireNonNull(wagers, "wagers");
        List<EconomyService.Resolution> resolutions = new ArrayList<>(wagers.size());
        for (WagerHandle wager : wagers) {
            resolutions.add(new EconomyService.Resolution(
                    Objects.requireNonNull(wager, "wager"),
                    WagerResolution.refund()
            ));
        }
        return List.copyOf(resolutions);
    }

    /** Starts timers only after the containing runtime snapshot is committed. */
    public synchronized void activate() {
        if (this.activated || this.shuttingDown) {
            return;
        }
        this.activated = true;
        startTimer();
    }

    private void startTimer() {
        if (this.shuttingDown || !this.activated) {
            return;
        }
        if (!this.wagers.isEmpty()) {
            throw new IllegalStateException("Cannot start a new Jackpot round with unresolved wagers");
        }
        cancelTask(this.timerTask);
        this.roundId = UUID.randomUUID();
        this.locking = false;
        this.wagersLocked = false;
        this.resultChosen = false;
        this.settlementWinnerId = null;
        this.placementOrdinals.clear();
        this.pendingPlacementOrdinals.clear();
        this.spinning = false;
        this.cooldown = false;
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
            } else if (this.timeLeft <= 60
                    && (this.timeLeft <= 5 || this.timeLeft <= 15 && this.timeLeft % 5 == 0
                    || this.timeLeft % 30 == 0)) {
                for (Player player : new HashSet<>(this.bets.keySet())) {
                    if (player.isOnline()) {
                        player.sendMessage(String.format(configManager.messages.get("jackpotReminder"), this.timeLeft));
                    }
                }
            }
        }, 20L, 20L);
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        if (this.shuttingDown || player == null || openInterface == null) {
            return;
        }
        rebindPlayerState(player);
        if (this.spinning || this.locking) {
            sendUnavailableMessage(player);
            return;
        }
        OpenInterface existing = getSession(player);
        if (existing != null && existing.inventory != null) {
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
                .replace("%chance%", Integer.toString(getChance(bet)))
                : this.inventoryTitle;
        Inventory playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), title);
        playerInventory.setContents(this.baseInventory.getContents());
        openInterface.inventory = playerInventory;
        this.animations.startAnimations(playerInventory);
        addBetsToInventory(playerInventory);
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

    private void sendUnavailableMessage(Player player) {
        ConfigManager configManager = SmartGambling.getInstance().configManager;
        String timeUnit = this.timeLeft > 60 ? configManager.messages.get("minutes")
                : configManager.messages.get("seconds");
        String time = Integer.toString(this.timeLeft > 60 ? this.timeLeft / 60 : this.timeLeft);
        String messageKey = this.cooldown ? "jackpotNextGame" : "jackpotAlreadyStarted";
        player.sendMessage(String.format(configManager.messages.get(messageKey), time + " " + timeUnit));
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
                    && this.animations.isAnimated(closingInventory)) {
                this.animations.stopAnimations(closingInventory);
            }
            return;
        }
        if (!forcedClose && this.spinning && !this.cooldown && closingInventory == this.baseGameInventory) {
            runLater(() -> {
                OpenInterface session = getSession(player);
                if (player.isOnline() && this.spinning && !this.cooldown && session != null) {
                    session.inventory = this.baseGameInventory;
                    player.openInventory(this.baseGameInventory);
                }
            }, 0L);
            return;
        }

        // Closing before the draw only leaves the interface; the committed ticket remains in the pot.
        stopBettingAnimation(closingInventory);
        removeSession(player);
    }

    private void stopBettingAnimation(Inventory inventory) {
        if (inventory != null && inventory != this.baseGameInventory && this.animations.isAnimated(inventory)) {
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
        }
        if (previous != null) {
            WagerHandle wager = this.wagers.remove(previous);
            if (wager != null) {
                this.wagers.put(player, wager);
            }
        }
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || this.shuttingDown
                || this.spinning || this.locking) {
            return;
        }
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
                    roundOperationId(this.roundId, "lock"),
                    List.copyOf(this.wagers.values())
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not lock Jackpot round " + this.roundId,
                    exception
            );
            return new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
    }

    private void logTxFailure(String phase, TxResult result) {
        SmartGambling.getInstance().getLogger().severe(
                "Jackpot " + phase + " was not durable for round " + this.roundId
                        + ": status=" + (result == null ? "null" : result.status())
                        + ", detail=" + (result == null ? "null" : result.detail())
        );
    }

    private void startGame() {
        if (this.shuttingDown || this.spinning) {
            return;
        }
        recomputeTotalBets();
        if (this.totalBets <= 0 || this.bets.isEmpty()) {
            cancelTask(this.timerTask);
            startTimer();
            return;
        }
        if (!wagerCacheComplete()) {
            this.locking = true;
            SmartGambling.getInstance().getLogger().severe(
                    "Jackpot round " + this.roundId + " has GUI bets without matching durable wager handles"
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
        this.timeLeft = 0;
        this.spinning = true;
        this.cooldown = false;
        this.weighedBets = new TreeMap<>();
        int cumulativeBets = 0;
        for (Map.Entry<Player, Integer> entry : this.bets.entrySet()) {
            cumulativeBets += entry.getValue();
            this.weighedBets.put(cumulativeBets, getPlayerHead(entry.getKey()));
        }

        Set<Player> players = new HashSet<>(this.activePlayers);
        this.openingInventory = true;
        try {
            for (Player player : players) {
                OpenInterface session = getSession(player);
                if (session == null || !player.isOnline()) {
                    this.activePlayers.remove(player);
                    continue;
                }
                player.openInventory(this.baseGameInventory);
                session.inventory = this.baseGameInventory;
            }
        } finally {
            this.openingInventory = false;
        }

        this.gameAnimations.startAnimations(this.baseGameInventory);
        this.animationSpeed = 6;
        spin();
        for (int i = 0; i < 3; ++i) {
            runLater(() -> this.animationSpeed = Math.max(1, this.animationSpeed - 1), i * 4L);
        }
        runLater(() -> {
            for (int i = 0; i < 3; ++i) {
                runLater(() -> ++this.animationSpeed, i * 20L);
            }
        }, this.animationDuration);
        runLater(() -> this.animationSpeed = 0, this.animationDuration + 60L);
        runLater(this::finishDraw, this.animationDuration + 80L);
    }

    private void finishDraw() {
        if (this.shuttingDown || !this.spinning || this.cooldown || this.bets.isEmpty()) {
            return;
        }
        if (!this.resultChosen) {
            ItemStack winningHead = getItemSafely(this.baseGameInventory, this.winningHeadSlot);
            if (!(winningHead != null && winningHead.getItemMeta() instanceof SkullMeta)) {
                winningHead = getRandomPlayer();
            }
            OfflinePlayer selected = winningHead != null
                    && winningHead.getItemMeta() instanceof SkullMeta meta
                    ? meta.getOwningPlayer() : null;
            UUID selectedCandidateId = selected == null ? null : selected.getUniqueId();
            boolean selectedHasWager = selectedCandidateId != null && this.wagers.values().stream()
                    .anyMatch(wager -> wager.playerId().equals(selectedCandidateId));
            UUID selectedId = selectedCandidateId;
            if (!selectedHasWager) {
                selectedId = this.wagers.values().stream()
                        .findFirst()
                        .map(WagerHandle::playerId)
                        .orElse(null);
            }
            if (selectedId == null) {
                SmartGambling.getInstance().getLogger().severe(
                        "Jackpot round " + this.roundId + " could not select a wager-backed winner"
                );
                return;
            }
            this.settlementWinnerId = selectedId;
            this.resultChosen = true;
        }

        Money pot = totalStake(this.wagers.values());
        TxResult result;
        try {
            result = SmartGambling.getInstance().getEconomyService().resolveAll(
                    roundOperationId(this.roundId, "result"),
                    resultResolutions(this.wagers.values(), this.settlementWinnerId)
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not journal Jackpot result for round " + this.roundId,
                    exception
            );
            result = new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
        if (!result.durable()) {
            logTxFailure("result", result);
            scheduleSettlementRetry();
            return;
        }

        OfflinePlayer winner = Bukkit.getOfflinePlayer(this.settlementWinnerId);
        double amountWon = pot.vaultAmount();
        this.wagers.clear();
        this.bets.clear();
        this.totalBets = 0;
        this.gameAnimations.stopAnimations(this.baseGameInventory);
        boolean providerApplied = result.status() == TxResult.Status.DURABLE
                || result.status() == TxResult.Status.ALREADY_APPLIED;
        try {
            double balance = providerApplied ? SmartGambling.getEconomy().getBalance(winner) : 0.0D;
            if (providerApplied && winner instanceof Player player && player.isOnline()) {
                player.sendMessage(String.format(message("jackpotWinOwn"), amountWon, balance));
                player.sendMessage(String.format(message("wonMoney"), amountWon, balance));
                CustomSound sound = SmartGambling.getInstance().customSounds.get("lotteryWin");
                if (sound != null) {
                    sound.play(player);
                }
            } else if (!providerApplied && winner instanceof Player player && player.isOnline()) {
                player.sendMessage(ChatColor.RED + "您已赢得本轮大奖池，但派奖仍等待账本核对（"
                        + result.status() + "）。");
            }
            for (Player player : providerApplied ? new HashSet<>(this.activePlayers) : Set.<Player>of()) {
                if (player.isOnline() && !player.getUniqueId().equals(this.settlementWinnerId)) {
                    player.sendMessage(String.format(message("jackpotWin"), winner.getName(), amountWon));
                }
            }
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.WARNING,
                    "Jackpot result was durable but its presentation failed for round " + this.roundId,
                    exception
            );
        }
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
            finishDraw();
        }, 20L);
    }

    private void restartGame() {
        if (this.shuttingDown) {
            return;
        }
        Set<Player> players = new HashSet<>(this.activePlayers);
        this.openingInventory = true;
        try {
            for (Player player : players) {
                OpenInterface session = getSession(player);
                if (session != null) {
                    SmartGambling.getInstance().openMachines.remove(player, session);
                    if (player.isOnline() && session.inventory != null
                            && session.inventory == player.getOpenInventory().getTopInventory()) {
                        player.closeInventory();
                    }
                }
            }
        } finally {
            this.openingInventory = false;
        }
        this.activePlayers.clear();
        clearGameHeads();
        this.spinning = true;
        this.cooldown = true;
        int displayedSeconds = Math.min(5, this.timeBetweenGames);
        this.timeLeft = Math.max(0, this.timeBetweenGames - displayedSeconds);
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
        this.spinning = false;
        this.cooldown = false;
        startTimer();
    }

    private void clearGameHeads() {
        for (int slot : this.gamePlayerHeadSlots) {
            setItemSafely(this.baseGameInventory, slot, null);
        }
    }

    private void spin() {
        if (this.shuttingDown || !this.spinning || this.cooldown || this.animationSpeed <= 0
                || this.weighedBets == null || this.weighedBets.isEmpty()) {
            return;
        }
        ItemStack nextPlayer = getRandomPlayer();
        for (int i = this.gamePlayerHeadSlots.size() - 1; i > 0; --i) {
            int target = this.gamePlayerHeadSlots.get(i);
            int source = this.gamePlayerHeadSlots.get(i - 1);
            setItemSafely(this.baseGameInventory, target, getItemSafely(this.baseGameInventory, source));
        }
        if (!this.gamePlayerHeadSlots.isEmpty()) {
            setItemSafely(this.baseGameInventory, this.gamePlayerHeadSlots.get(0), nextPlayer);
        }
        for (Player player : new HashSet<>(this.activePlayers)) {
            if (player.isOnline()) {
                player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.1F, 0.5F);
            }
        }
        if (this.animationSpeed > 0) {
            runLater(this::spin, this.animationSpeed);
        }
    }

    private ItemStack getRandomPlayer() {
        if (this.totalBets <= 0 || this.weighedBets == null || this.weighedBets.isEmpty()) {
            return null;
        }
        int number = SmartGambling.getInstance().random.nextInt(this.totalBets);
        Map.Entry<Integer, ItemStack> entry = this.weighedBets.higherEntry(number);
        return entry == null ? this.weighedBets.lastEntry().getValue() : entry.getValue();
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

    private int getChance(int bet) {
        if (bet <= 0 || this.totalBets <= 0) {
            return 0;
        }
        return (int) Math.min(100L, (long) bet * 100L / this.totalBets);
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
        if (this.shuttingDown || this.spinning || this.locking || this.bets.containsKey(player)
                || amount <= 0 || this.roundId == null) {
            return;
        }
        long newTotal = (long) this.totalBets + amount;
        if (newTotal > Integer.MAX_VALUE) {
            player.sendMessage(ChatColor.RED + "本轮大奖池的总下注额已达上限，无法继续增加。");
            return;
        }
        double balance = SmartGambling.getEconomy().getBalance(player);
        PlaceResult placement;
        try {
            long ordinal = placementOrdinal(player.getUniqueId());
            placement = SmartGambling.getInstance().getEconomyService().place(
                    createWagerKey(this.roundId, player.getUniqueId(), ordinal),
                    Money.of((long) amount)
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not journal Jackpot ticket for " + player.getName(),
                    exception
            );
            player.sendMessage(ChatColor.RED + "资金账本当前不可用，本次奖池下注未提交。");
            return;
        }
        if (!placement.accepted() || placement.wager() == null) {
            if (placement.status() == PlaceResult.Status.REJECTED) {
                this.pendingPlacementOrdinals.remove(player.getUniqueId());
                DisplayUtils.displayActionBar(player,
                        String.format(message("notEnoughMoneyActionBar"), amount, balance));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
            } else {
                player.sendMessage(ChatColor.RED + "下注未被接受，本次奖池投注未提交。");
            }
            SmartGambling.getInstance().getLogger().warning(
                    "Jackpot wager rejected for " + player.getName() + ": "
                            + placement.status() + " (" + placement.detail() + ')'
            );
            return;
        }
        WagerHandle wager = placement.wager();
        if (wager.stake().compareTo(Money.of((long) amount)) != 0) {
            SmartGambling.getInstance().getLogger().severe(
                    "Jackpot ticket replay has a different stake for " + player.getName()
            );
            TxResult mismatchRefund = resolveRefund(wager);
            if (mismatchRefund.durable()) {
                this.pendingPlacementOrdinals.remove(player.getUniqueId());
            }
            return;
        }
        this.wagers.put(player, wager);
        this.bets.put(player, amount);
        this.pendingPlacementOrdinals.remove(player.getUniqueId());
        this.totalBets = (int) newTotal;
        player.sendMessage(String.format(message("moneyExtracted"), amount,
                SmartGambling.getEconomy().getBalance(player)));
        if (this.timeAddedOnBet > 0 && this.timeLeft < 30) {
            this.timeLeft = Math.min(this.gameDuration, this.timeLeft + this.timeAddedOnBet);
        }
        updatePlayerBets();
    }

    public void removeBet(Player player) {
        rebindPlayerState(player);
        Integer bet = this.bets.get(player);
        WagerHandle wager = this.wagers.get(player);
        if (bet == null || wager == null || this.spinning || this.locking || this.shuttingDown) {
            return;
        }
        TxResult result = resolveRefund(wager);
        if (!result.durable()) {
            logTxFailure("ticket removal refund", result);
            return;
        }
        this.wagers.remove(player, wager);
        this.bets.remove(player);
        if (result.status() == TxResult.Status.DURABLE && player.isOnline()) {
            player.sendMessage(String.format(message("moneyReceived"), bet,
                    SmartGambling.getEconomy().getBalance(player)));
        }
        recomputeTotalBets();
        updatePlayerBets();
    }

    private TxResult resolveRefund(WagerHandle wager) {
        try {
            return SmartGambling.getInstance().getEconomyService().resolve(
                    wagerRefundOperationId(wager),
                    wager,
                    WagerResolution.refund()
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not journal Jackpot refund for wager " + wager.id(),
                    exception
            );
            return new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
    }

    public void updatePlayerBets() {
        for (Player player : new HashSet<>(this.activePlayers)) {
            OpenInterface session = getSession(player);
            if (session == null || session.inventory == null || session.inventory == this.baseGameInventory) {
                continue;
            }
            Inventory inventory = session.inventory;
            addBetsToInventory(inventory);
            removeCustomItem(this.betItems, inventory);
            removeCustomItem(this.removeBetItems, inventory);
            addCustomItem(this.bets.containsKey(player) ? this.removeBetItems : this.betItems,
                    inventory, player);
        }
    }

    public void addBetsToInventory(Inventory inventory) {
        for (int slot : this.playerHeadSlots) {
            setItemSafely(inventory, slot, null);
        }
        int limit = Math.min(this.playerHeadSlots.size(), this.bets.size());
        int index = 0;
        for (Player opponent : this.bets.keySet()) {
            if (index >= limit) {
                break;
            }
            setItemSafely(inventory, this.playerHeadSlots.get(index), getPlayerHead(opponent));
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

    private String replacePlaceholders(String string, Player player, int bet) {
        return string.replace("%name%", player.getName()).replace("%bet%", Integer.toString(bet))
                .replace("%chance%", Integer.toString(getChance(bet)));
    }

    private void recomputeTotalBets() {
        long total = 0L;
        for (int amount : this.bets.values()) {
            total += amount;
        }
        this.totalBets = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, total));
    }

    public void shutdownAndRefund() {
        this.shuttingDown = true;
        this.activated = false;
        cancelAllTasks();
        stopAllAnimations();
        settleShutdownWagers();
        recomputeTotalBets();

        Set<Player> players = new HashSet<>(this.activePlayers);
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
        this.spinning = false;
        this.cooldown = false;
        this.animationSpeed = 0;
        if (this.weighedBets != null) {
            this.weighedBets.clear();
        }
    }

    /** True when the ledger still owns a wager this runtime could not settle. */
    public boolean hasOutstandingFunds() {
        return !this.wagers.isEmpty();
    }

    private void settleShutdownWagers() {
        if (this.wagers.isEmpty()) {
            this.bets.clear();
            return;
        }
        TxResult result;
        try {
            if (this.resultChosen) {
                // Once a result payload exists it is immutable; never race it
                // with a contradictory shutdown refund.
                result = SmartGambling.getInstance().getEconomyService().resolveAll(
                        roundOperationId(this.roundId, "result"),
                        resultResolutions(this.wagers.values(), this.settlementWinnerId)
                );
            } else {
                result = SmartGambling.getInstance().getEconomyService().resolveAll(
                        roundOperationId(this.roundId, "shutdown-refund"),
                        refundResolutions(this.wagers.values())
                );
            }
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not journal Jackpot shutdown settlement for round " + this.roundId,
                    exception
            );
            result = new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
        if (!result.durable()) {
            logTxFailure(this.resultChosen ? "shutdown result" : "shutdown refund", result);
            return;
        }

        if (!this.resultChosen && result.status() == TxResult.Status.DURABLE) {
            for (Map.Entry<Player, Integer> entry : new HashMap<>(this.bets).entrySet()) {
                if (entry.getKey().isOnline()) {
                    entry.getKey().sendMessage(String.format(message("moneyReceived"), entry.getValue(),
                            SmartGambling.getEconomy().getBalance(entry.getKey())));
                }
            }
        }
        this.wagers.clear();
        this.bets.clear();
    }

    private void stopAllAnimations() {
        if (this.gameAnimations.isAnimated(this.baseGameInventory)) {
            this.gameAnimations.stopAnimations(this.baseGameInventory);
        }
        for (Player player : new HashSet<>(this.activePlayers)) {
            OpenInterface session = getSession(player);
            if (session != null && session.inventory != null && session.inventory != this.baseGameInventory
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
