package me.arthed.smartgambling.games.slots;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.economy.EconomyService;
import me.arthed.smartgambling.economy.Money;
import me.arthed.smartgambling.economy.PlaceResult;
import me.arthed.smartgambling.economy.TxResult;
import me.arthed.smartgambling.economy.WagerHandle;
import me.arthed.smartgambling.economy.WagerKey;
import me.arthed.smartgambling.economy.WagerResolution;
import me.arthed.smartgambling.games.common.inventories.SubInventory;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.games.slots.objects.SlotItem;
import me.arthed.smartgambling.games.slots.objects.rewards.Reward;
import me.arthed.smartgambling.games.slots.testing.ForcedSlotResultRegistry;
import me.arthed.smartgambling.utils.DisplayUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public class SlotMachine implements Machine {
    public final String name;
    private final ItemStack machineItem;
    private final double[] entityOffset;
    private final Inventory baseInventory;
    public final String inventoryTitle;
    private final List<List<Integer>> displaySlots;
    private final Button spinButton;
    private final Button moneyButton;
    private final Button rewardsGuiButton;
    private final Button closeButton;
    private final SubInventory rewardsGUI;
    private final NavigableMap<Integer, SlotItem> itemsWeighed;
    private final int itemsTotalWeight;
    private final List<String> symbolIds;
    private final Map<String, String> canonicalSymbolIds;
    private final Map<String, SlotItem> symbolsByNormalizedId;
    private final List<Reward> rewards;
    public final int defaultBet;
    private final int animationDuration;
    private final int animationStartingSpeed;
    private final InventoryAnimations animations;
    private final HashMap<Player, SlotMachine.PlayerInventoryData> playerInventoryData;

    public SlotMachine(String name, ItemStack machineItem, double[] entityOffset, String inventoryTitle, Inventory baseInventory, List<List<Integer>> displaySlots, Button spinButton, Button moneyButton, Button rewardsGuiButton, Button closeButton, SubInventory rewardsGUI, NavigableMap<Integer, SlotItem> itemsWeighed, int itemsTotalWeight, Map<String, SlotItem> symbols, List<Reward> rewards, InventoryAnimations animations, int animationDuration, int defaultBet, int animationStartingSpeed) {
        this.name = name;
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.inventoryTitle = inventoryTitle;
        this.baseInventory = baseInventory;
        this.displaySlots = displaySlots;
        this.spinButton = spinButton;
        this.moneyButton = moneyButton;
        this.rewardsGuiButton = rewardsGuiButton;
        this.closeButton = closeButton;
        this.rewardsGUI = rewardsGUI;
        this.itemsWeighed = itemsWeighed;
        this.itemsTotalWeight = itemsTotalWeight;
        LinkedHashMap<String, String> canonicalIds = new LinkedHashMap<>();
        LinkedHashMap<String, SlotItem> normalizedSymbols = new LinkedHashMap<>();
        for (Map.Entry<String, SlotItem> entry : Objects.requireNonNull(symbols, "symbols").entrySet()) {
            String id = Objects.requireNonNull(entry.getKey(), "symbol id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("Slot symbol ID cannot be blank");
            }
            String normalizedId = normalizeSymbolId(id);
            if (canonicalIds.putIfAbsent(normalizedId, id) != null) {
                throw new IllegalArgumentException("Duplicate slot symbol ID ignoring case: " + id);
            }
            normalizedSymbols.put(normalizedId, Objects.requireNonNull(entry.getValue(), "slot symbol"));
        }
        if (normalizedSymbols.isEmpty()) {
            throw new IllegalArgumentException("Slot machine must configure at least one weighted symbol");
        }
        this.symbolIds = List.copyOf(canonicalIds.values());
        this.canonicalSymbolIds = Collections.unmodifiableMap(canonicalIds);
        this.symbolsByNormalizedId = Collections.unmodifiableMap(normalizedSymbols);
        this.rewards = rewards;
        this.animationDuration = animationDuration;
        this.defaultBet = defaultBet;
        this.animations = animations;
        this.animationStartingSpeed = animationStartingSpeed;
        this.playerInventoryData = new HashMap();
    }

    public void open(Player player, OpenInterface openSlot) {
        if (!(openSlot instanceof OpenMachine openMachine)) {
            throw new IllegalArgumentException("Slot machine requires an OpenMachine context.");
        }
        SlotMachine.PlayerInventoryData unresolved = this.findUnresolvedWager(player);
        if (unresolved != null) {
            this.cancelTask(unresolved.settlementTask);
            unresolved.settlementTask = null;
            if (unresolved.wagerPending) {
                if (unresolved.resultsReady && this.hasCompleteResult(unresolved)) {
                    this.settleSpin(player, unresolved);
                } else {
                    this.refundWager(player, unresolved, "slot pending refund before reopen");
                }
                if (unresolved.wagerPending) {
                    this.scheduleWagerRetry(player, unresolved);
                    player.sendMessage(ChatColor.RED + "上一笔老虎机交易仍在处理中，请稍后再试。");
                    return;
                }
            }
        }
        if (openMachine.machineData == null
                || openMachine.machineData.entities == null
                || openMachine.machineData.entities.length == 0
                || openMachine.machineData.entities[0] == null) {
            throw new IllegalStateException("Slot machine '" + this.name + "' has no usable seat entity.");
        }
        if (openSlot.betAmount == 0) {
            openSlot.betAmount = this.defaultBet;
        }
        String title = this.inventoryTitle.replace("%bet%", String.valueOf(openSlot.betAmount));
        Inventory playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), title);
        playerInventory.setContents(this.baseInventory.getContents());

        for(int slot : this.spinButton.getSlots()) {
            ItemStack item = playerInventory.getItem(slot);
            if (item == null) {
                throw new IllegalStateException("Spin button slot " + slot + " is empty in slot machine '" + this.name + "'.");
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                meta.setDisplayName(meta.getDisplayName().replace("%bet%", "" + openSlot.betAmount));
                item.setItemMeta(meta);
                playerInventory.setItem(slot, item);
            }
        }

        SmartGambling.getInstance().openMachines.put(player, openSlot);
        openMachine.machineData.inUse = true;
        for (Entity passenger : openMachine.machineData.entities[0].getPassengers()) {
            if (!passenger.equals(player)) {
                openMachine.machineData.entities[0].removePassenger(passenger);
            }
        }
        Location location = openMachine.machineData.entities[0].getLocation();
        player.setRotation(location.getYaw(), location.getPitch());
        if (!openMachine.machineData.entities[0].getPassengers().contains(player)) {
            openMachine.machineData.entities[0].addPassenger(player);
        }

        openSlot.inventory = playerInventory;
        this.animations.startAnimations(playerInventory);

        this.playerInventoryData.put(player, new SlotMachine.PlayerInventoryData(
                new int[this.displaySlots.size()],
                new SlotItem[this.displaySlots.size()],
                new SlotItem[this.displaySlots.size()],
                openMachine.machineData.id,
                UUID.randomUUID().toString()
        ));
        PlaybackManager.openingPlayers.computeIfAbsent(player.getUniqueId(), ignored -> new OpeningPlayer(player));
        try {
            player.openInventory(playerInventory);
        } catch (RuntimeException exception) {
            this.forceClose(player);
            throw exception;
        }

      //  return playerInventory;
    }


    public static String convertToDownNumber(int number) {
        //
        // 缅甸语的数字字符数组섎섏섐섑섒섓섔섕섖섗
        char[] burmaDigits = {'섎', '섏', '섐', '섐', '섒', '섓', '섔', '섕', '섖', '섗'};
        StringBuilder result = new StringBuilder();

        int digitCount = 0; // 用于跟踪位数

        // 将阿拉伯数值转换为缅甸数值
        while (number > 0) {
            int digit = number % 10;
            result.insert(0, burmaDigits[digit]);

            digitCount++;
            number /= 10;

            // 判断是否需要添加连字符
            if (digitCount < 10 && number > 0) {
                result.insert(0, '\uF801');
                //result.insert(0, '-');
            }
        }

        return result.toString();
    }
    public Inventory changeInventoryTitle(Inventory inv,Player player, String newTitle) {
        Inventory newInventory = Bukkit.createInventory(player, 54, newTitle);

        newInventory.setContents(inv.getContents());
        return newInventory;
    }

    public void close(Player player, Inventory inventory) {
        SlotMachine.PlayerInventoryData invData = this.playerInventoryData.get(player);
        if (inventory != null && invData != null && invData.spinning && player.isOnline()) {
            Bukkit.getScheduler().runTask(SmartGambling.getInstance(), () -> {
                SlotMachine.PlayerInventoryData currentData = this.playerInventoryData.get(player);
                if (player.isOnline() && currentData != null && currentData.spinning) {
                    player.openInventory(inventory);
                }
            });
            return;
        }
        this.forceClose(player);
    }

    @Override
    public void forceClose(Player player) {
        SlotMachine.PlayerInventoryData invData = this.playerInventoryData.get(player);
        if (invData != null) {
            invData.detached = true;
            this.cancelTask(invData.spinEndTask);
            this.cancelTask(invData.settlementTask);
            invData.spinEndTask = null;
            invData.settlementTask = null;
            this.cancelAnimationTasks(invData);
            Arrays.fill(invData.animationSpeed, 0);
            invData.spinning = false;
            if (invData.wagerPending) {
                if (invData.resultsReady && this.hasCompleteResult(invData)) {
                    this.settleSpin(player, invData);
                } else {
                    this.refundWager(player, invData, "slot spin interrupted before settlement");
                }
            }
            if (invData.wagerPending) {
                this.scheduleWagerRetry(player, invData);
            } else {
                this.playerInventoryData.remove(player, invData);
            }
        }

        OpenInterface current = SmartGambling.getInstance().openMachines.remove(player);
        if (current != null && current.inventory != null) {
            this.animations.stopAnimations(current.inventory);
        }
        if (current instanceof OpenMachine openMachine && openMachine.machineData != null) {
            openMachine.machineData.inUse = false;
            if (openMachine.machineData.entities != null) {
                for (Entity entity : openMachine.machineData.entities) {
                    if (entity != null && entity.getPassengers().contains(player)) {
                        entity.removePassenger(player);
                    }
                }
            }
        }
        PlaybackManager.removeOpeningPlayer(player);
    }
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        OpenInterface openInterface = SmartGambling.getInstance().openMachines.get(player);
        SlotMachine.PlayerInventoryData invData = this.playerInventoryData.get(player);
        if (openInterface == null
                || openInterface.machineType != this
                || openInterface.inventory == null
                || event.getView().getTopInventory() != openInterface.inventory
                || invData == null) {
            return;
        }
        if (!invData.spinning && !invData.wagerPending) {
            if (this.spinButton.isClicked(event.getSlot())) {
                this.spin(openInterface.inventory, player);
            } else if (this.moneyButton.isClicked(event.getSlot())) {
                SmartGambling.getInstance().moneyInventory.open(player, openInterface);
            } else if (this.rewardsGuiButton.isClicked(event.getSlot())) {
                this.rewardsGUI.open(player, openInterface);
            } else if (this.closeButton.isClicked(event.getSlot())) {
                player.closeInventory();
            }

        }
    }


    public ItemStack getMachineItem() {
        return this.machineItem;
    }

    public double[] getMachineEntityOffset() {
        return this.entityOffset;
    }

    public int getReelCount() {
        return this.displaySlots.size();
    }

    public List<String> getSymbolIds() {
        return this.symbolIds;
    }

    /**
     * Validates a test result against this machine's weighted Items catalog and
     * returns the IDs with the exact spelling used in configuration.
     */
    public List<String> canonicalizeSymbolIds(List<String> rawIds) {
        Objects.requireNonNull(rawIds, "rawIds");
        if (rawIds.size() != this.getReelCount()) {
            throw new IllegalArgumentException(
                    "Expected " + this.getReelCount() + " slot symbols, but received " + rawIds.size()
            );
        }
        ArrayList<String> canonical = new ArrayList<>(rawIds.size());
        for (String rawId : rawIds) {
            if (rawId == null || rawId.isBlank()) {
                throw new IllegalArgumentException("Slot symbol ID cannot be blank");
            }
            String configuredId = this.canonicalSymbolIds.get(normalizeSymbolId(rawId));
            if (configuredId == null) {
                throw new IllegalArgumentException("Unknown weighted slot symbol '" + rawId + "'");
            }
            canonical.add(configuredId);
        }
        return List.copyOf(canonical);
    }

    private SlotItem[] resolveSymbolIds(List<String> rawIds) {
        List<String> canonical = this.canonicalizeSymbolIds(rawIds);
        SlotItem[] result = new SlotItem[canonical.size()];
        for (int index = 0; index < canonical.size(); index++) {
            result[index] = this.symbolsByNormalizedId.get(normalizeSymbolId(canonical.get(index)));
        }
        return result;
    }

    private static String normalizeSymbolId(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    public void spin(Inventory inventory, Player player) {
        SlotMachine.PlayerInventoryData invData = this.playerInventoryData.get(player);
        OpenInterface openInterface = SmartGambling.getInstance().openMachines.get(player);
        if (invData == null
                || invData.spinning
                || invData.wagerPending
                || !(openInterface instanceof OpenMachine openMachine)
                || openInterface.machineType != this
                || openInterface.inventory != inventory
                || openMachine.machineData == null
                || !invData.machineId.equals(openMachine.machineData.id)) {
            return;
        }

        int bet = openInterface.betAmount;
        if (bet <= 0) {
            player.sendMessage(ChatColor.RED + "老虎机下注金额必须为正数。");
            return;
        }
        Economy economy = SmartGambling.getEconomy();
        double balance = economy.getBalance(player);
        if (balance < (double)bet) {
            DisplayUtils.displayActionBar(player, String.format((String)SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"), bet, balance));
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
            return;
        }

        WagerKey wagerKey = createWagerKey(
                openMachine.machineData.id,
                player.getUniqueId(),
                invData.sessionId,
                invData.nextSpinOrdinal()
        );
        PlaceResult placement;
        try {
            EconomyService economyService = SmartGambling.getInstance().getEconomyService();
            if (economyService == null) {
                player.sendMessage(ChatColor.RED + "资金账本当前不可用，本次旋转未开始。");
                return;
            }
            placement = economyService.place(wagerKey, Money.of((long) bet));
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Failed to journal slot wager for " + player.getName(),
                    exception
            );
            player.sendMessage(ChatColor.RED + "资金账本当前不可用，本次旋转未开始。");
            return;
        }
        if (placement == null) {
            SmartGambling.getInstance().getLogger().severe(
                    "EconomyService returned no placement result for slot player " + player.getName()
            );
            player.sendMessage(ChatColor.RED + "资金账本当前不可用，本次旋转未开始。");
            return;
        }
        if (!placement.accepted() || placement.wager() == null) {
            this.reportPlacementFailure(player, placement, bet, balance);
            return;
        }

        invData.wager = placement.wager();
        invData.wagerAmount = bet;
        invData.wagerPending = true;
        invData.resultsReady = false;
        invData.spinning = true;
        Arrays.fill(invData.finalItems, null);
        invData.clearForcedResult();
        boolean forcedResultReady;
        try {
            forcedResultReady = this.claimForcedResult(player, invData);
        } catch (RuntimeException exception) {
            invData.spinning = false;
            try {
                SmartGambling.getInstance().getLogger().log(
                        Level.SEVERE,
                        "Failed to prepare forced slot test state after placing wager for " + player.getName(),
                        exception
                );
            } catch (RuntimeException ignored) {
                // The wager safety path below must still run if logging itself fails.
            }
            this.abortPlacedSpin(player, invData, "forced slot test preparation failed");
            return;
        }
        if (!forcedResultReady) {
            this.abortPlacedSpin(player, invData, "forced slot test result was invalid");
            return;
        }
        try {
            this.animations.startDependentAnimations(inventory);
            this.startAnimation(inventory, player);
            invData.spinEndTask = Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                if (this.playerInventoryData.get(player) != invData || !invData.wagerPending) {
                    return;
                }
                invData.spinEndTask = null;
                this.cancelAnimationTasks(invData);
                invData.spinning = false;
                try {
                    this.animations.stopDependentAnimations(inventory);
                } catch (RuntimeException exception) {
                    SmartGambling.getInstance().getLogger().log(Level.WARNING, "Failed to stop slot animation for " + player.getName(), exception);
                }
                if (invData.forcedFinalItems != null && !invData.forcedResultApplied) {
                    invData.resultsReady = false;
                    SmartGambling.getInstance().getLogger().severe(
                            "Forced slot test result did not reach every reel for " + player.getName()
                    );
                    this.refundWager(player, invData, "forced slot test result was incomplete");
                    if (invData.wagerPending) {
                        this.scheduleWagerRetry(player, invData);
                    }
                    return;
                }
                invData.resultsReady = this.hasCompleteResult(invData);
                if (!invData.resultsReady) {
                    this.refundWager(player, invData, "slot result was incomplete");
                    if (invData.wagerPending) {
                        this.scheduleWagerRetry(player, invData);
                    }
                    return;
                }
                try {
                    invData.settlementTask = Bukkit.getScheduler().runTaskLater(
                            SmartGambling.getInstance(),
                            () -> this.stoppedSpinning(inventory, player, invData),
                            8L
                    );
                } catch (RuntimeException exception) {
                    SmartGambling.getInstance().getLogger().log(Level.WARNING, "Failed to schedule slot settlement for " + player.getName(), exception);
                    this.settleSpin(player, invData);
                    if (invData.wagerPending) {
                        this.scheduleWagerRetry(player, invData);
                    }
                }
            }, spinEndDelay(this.animationDuration, this.displaySlots.size()));
        } catch (RuntimeException exception) {
            this.cancelAnimationTasks(invData);
            Arrays.fill(invData.animationSpeed, 0);
            invData.spinning = false;
            invData.resultsReady = false;
            this.refundWager(player, invData, "slot spin failed to start");
            if (invData.wagerPending) {
                this.scheduleWagerRetry(player, invData);
            }
            SmartGambling.getInstance().getLogger().log(Level.SEVERE, "Failed to start slot spin for " + player.getName(), exception);
            return;
        }
        player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), bet, SmartGambling.getEconomy().getBalance(player)));
    }

    private void abortPlacedSpin(
            Player player,
            SlotMachine.PlayerInventoryData invData,
            String reason
    ) {
        invData.spinning = false;
        invData.resultsReady = false;
        this.refundWager(player, invData, reason);
        if (invData.wagerPending) {
            this.scheduleWagerRetry(player, invData);
        }
    }

    private boolean claimForcedResult(Player player, SlotMachine.PlayerInventoryData invData) {
        SmartGambling plugin = SmartGambling.getInstance();
        if (!plugin.isForcedSlotResultsEnabled()) {
            return true;
        }

        Optional<ForcedSlotResultRegistry.Directive> claimed;
        try {
            plugin.auditExpiredForcedSlotDirectives();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to audit expired forced slot directives", exception);
        }
        claimed = plugin.getForcedSlotResultRegistry().claim(player.getUniqueId(), this.name);
        if (claimed.isEmpty()) {
            return true;
        }

        ForcedSlotResultRegistry.Directive directive = claimed.get();
        SlotItem[] forcedItems;
        List<String> canonicalIds;
        try {
            canonicalIds = this.canonicalizeSymbolIds(directive.symbolIds());
            forcedItems = this.resolveSymbolIds(canonicalIds);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Claimed forced slot directive " + directive.directiveId()
                            + " is not valid for machine type '" + this.name + "'",
                    exception
            );
            if (player.isOnline()) {
                try {
                    player.sendMessage(ChatColor.RED + "老虎机测试组合无效，本次下注将退还。请联系管理员。");
                } catch (RuntimeException noticeException) {
                    plugin.getLogger().log(Level.WARNING, "Failed to notify player about invalid forced slot result", noticeException);
                }
            }
            return false;
        }

        invData.forcedFinalItems = forcedItems;
        invData.forcedLinesApplied = new boolean[forcedItems.length];
        invData.forcedDirective = directive;
        try {
            plugin.auditForcedSlotDirective("consumed", directive, "wager=" + invData.wager.id());
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to audit consumed forced slot directive", exception);
        }
        if (player.isOnline()) {
            try {
                player.sendMessage(ChatColor.YELLOW + "本局使用测试组合，结果不代表实际概率。");
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to notify player about forced slot test result", exception);
            }
        }
        return true;
    }

    static boolean copyForcedLineResult(
            SlotItem[] forcedItems,
            SlotItem[] finalItems,
            boolean[] appliedLines,
            int line
    ) {
        Objects.requireNonNull(forcedItems, "forcedItems");
        Objects.requireNonNull(finalItems, "finalItems");
        Objects.requireNonNull(appliedLines, "appliedLines");
        if (forcedItems.length == 0
                || forcedItems.length != finalItems.length
                || forcedItems.length != appliedLines.length) {
            throw new IllegalArgumentException("Forced slot result must match the configured reel count");
        }
        if (line < 0 || line >= forcedItems.length) {
            throw new IndexOutOfBoundsException("Forced slot line is outside the configured reels: " + line);
        }
        finalItems[line] = Objects.requireNonNull(forcedItems[line], "forced slot item");
        appliedLines[line] = true;
        for (boolean applied : appliedLines) {
            if (!applied) {
                return false;
            }
        }
        return true;
    }

    public void stoppedSpinning(Inventory inventory, Player player) {
        SlotMachine.PlayerInventoryData invData = this.playerInventoryData.get(player);
        this.stoppedSpinning(inventory, player, invData);
    }

    private void stoppedSpinning(
            Inventory inventory,
            Player player,
            SlotMachine.PlayerInventoryData invData
    ) {
        if (invData == null
                || this.playerInventoryData.get(player) != invData
                || !invData.wagerPending) {
            return;
        }
        this.cancelTask(invData.settlementTask);
        invData.settlementTask = null;
        if (!invData.resultsReady || !this.hasCompleteResult(invData)) {
            this.refundWager(player, invData, "slot settlement had no complete result");
            if (invData.wagerPending) {
                this.scheduleWagerRetry(player, invData);
            }
            return;
        }
        this.settleSpin(player, invData);
        if (invData.wagerPending) {
            this.scheduleWagerRetry(player, invData);
        }
    }

    private void settleSpin(Player player, SlotMachine.PlayerInventoryData invData) {
        if (!invData.wagerPending || invData.wager == null) {
            return;
        }
        Reward reward;
        try {
            reward = this.checkRewards(invData.finalItems);
        } catch (RuntimeException exception) {
            invData.resultsReady = false;
            this.refundWager(player, invData, "slot reward evaluation failed");
            SmartGambling.getInstance().getLogger().log(Level.SEVERE, "Failed to evaluate slot result for " + player.getName(), exception);
            return;
        }

        int wager = invData.wagerAmount;
        long amountWon = reward == null ? 0L : Math.round((double)wager * reward.moneyMultiplier);
        WagerResolution resolution = reward != null && amountWon > 0L
                ? WagerResolution.payout(Money.of(amountWon))
                : WagerResolution.loss();
        String outcome = reward != null && amountWon > 0L ? "payout" : "loss";
        TxResult result;
        try {
            EconomyService economyService = SmartGambling.getInstance().getEconomyService();
            if (economyService == null) {
                return;
            }
            result = economyService.resolve(
                    wagerOperationId(invData.wager, outcome),
                    invData.wager,
                    resolution
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Failed to journal slot " + outcome + " for " + player.getName(),
                    exception
            );
            return;
        }
        if (!result.durable()) {
            SmartGambling.getInstance().getLogger().warning(
                    "Slot " + outcome + " is not durable for " + player.getName()
                            + " (" + result.status() + "): " + result.detail()
            );
            return;
        }

        this.clearWager(invData);
        if (reward == null) {
            return;
        }
        boolean providerApplied = result.status() == TxResult.Status.DURABLE
                || result.status() == TxResult.Status.ALREADY_APPLIED;
        if (amountWon > 0L && player.isOnline() && providerApplied) {
            try {
                double currentBalance = SmartGambling.getEconomy().getBalance(player);
                DisplayUtils.displayActionBar(player, String.format((String)SmartGambling.getInstance().configManager.messages.get("wonMoneyActionBar"), amountWon, currentBalance));
                player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("wonMoney"), amountWon, currentBalance));
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(
                        Level.WARNING,
                        "Slot payout was journaled, but its balance message failed for " + player.getName(),
                    exception
                );
            }
        } else if (amountWon > 0L && player.isOnline()) {
            player.sendMessage(ChatColor.RED + "本次老虎机派奖已写入账本，但仍等待人工核对（"
                    + result.status() + "）。");
        }
        if (reward.winningCommands != null) {
            for(String command : reward.winningCommands) {
                this.executeWinningCommand(player, command);
            }
        }
        if (player.isOnline() && reward.sound != null) {
            try {
                reward.sound.play(player);
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(
                        Level.WARNING,
                        "Slot reward sound failed for " + player.getName(),
                        exception
                );
            }
        }
    }

    private void executeWinningCommand(Player player, String command) {
        try {
            if (command.startsWith("message:")) {
                if (player.isOnline()) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', command.replace("message: ", "").replace("%player%", player.getName())));
                }
            } else if (command.startsWith("bossbar")) {
                if (player.isOnline()) {
                    String[] data = command.split(": ", 2);
                    String[] bossbarData = data[0].split(" ");
                    BarColor color = BarColor.valueOf(bossbarData[1].toUpperCase());
                    BarStyle style = BarStyle.valueOf(bossbarData[2].toUpperCase());
                    int seconds = Integer.parseInt(bossbarData[3]);
                    DisplayUtils.displayBossBar(player, data[1].replace("%player%", player.getName()), color, style, seconds);
                }
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
            }
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(Level.WARNING, "Failed to run slot reward command: " + command, exception);
        }
    }

    private boolean refundWager(Player player, SlotMachine.PlayerInventoryData invData, String reason) {
        if (!invData.wagerPending) {
            return true;
        }
        if (invData.wager == null) {
            SmartGambling.getInstance().getLogger().severe(
                    "Slot wager cache is missing its ledger handle for " + player.getName() + ": " + reason
            );
            return false;
        }
        TxResult result;
        try {
            EconomyService economyService = SmartGambling.getInstance().getEconomyService();
            if (economyService == null) {
                return false;
            }
            result = economyService.resolve(
                    wagerOperationId(invData.wager, "refund"),
                    invData.wager,
                    WagerResolution.refund()
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Failed to journal slot refund for " + player.getName() + ": " + reason,
                    exception
            );
            return false;
        }
        if (!result.durable()) {
            SmartGambling.getInstance().getLogger().warning(
                    "Slot refund is not durable for " + player.getName() + " (" + result.status()
                            + "): " + result.detail() + "; context=" + reason
            );
            return false;
        }
        this.clearWager(invData);
        return true;
    }

    private void clearWager(SlotMachine.PlayerInventoryData invData) {
        invData.wagerPending = false;
        invData.wagerAmount = 0;
        invData.resultsReady = false;
        invData.wager = null;
        invData.clearForcedResult();
    }

    private SlotMachine.PlayerInventoryData findUnresolvedWager(Player player) {
        SlotMachine.PlayerInventoryData direct = this.playerInventoryData.get(player);
        if (direct != null) {
            return direct;
        }
        Player previous = null;
        for (Player candidate : this.playerInventoryData.keySet()) {
            if (candidate.getUniqueId().equals(player.getUniqueId())) {
                previous = candidate;
                break;
            }
        }
        if (previous == null) {
            return null;
        }
        SlotMachine.PlayerInventoryData data = this.playerInventoryData.remove(previous);
        if (data != null) {
            this.playerInventoryData.put(player, data);
        }
        return data;
    }

    private void scheduleWagerRetry(Player player, SlotMachine.PlayerInventoryData invData) {
        this.cancelTask(invData.settlementTask);
        if (!SmartGambling.getInstance().isEnabled()) {
            return;
        }
        try {
            invData.settlementTask = Bukkit.getScheduler().runTaskLater(
                    SmartGambling.getInstance(),
                    () -> {
                        invData.settlementTask = null;
                        if (invData.wagerPending) {
                            if (invData.resultsReady && this.hasCompleteResult(invData)) {
                                this.settleSpin(player, invData);
                            } else {
                                this.refundWager(player, invData, "slot pending refund retry");
                            }
                        }
                        if (invData.wagerPending) {
                            this.scheduleWagerRetry(player, invData);
                            return;
                        }
                        if (invData.detached) {
                            this.playerInventoryData.remove(player, invData);
                        }
                    },
                    200L
            );
        } catch (RuntimeException exception) {
            invData.settlementTask = null;
            SmartGambling.getInstance().getLogger().log(
                    Level.WARNING,
                    "Could not schedule slot ledger retry for " + player.getName(),
                    exception
            );
        }
    }

    private void reportPlacementFailure(Player player, PlaceResult placement, int bet, double balance) {
        switch (placement.status()) {
            case REJECTED -> {
                DisplayUtils.displayActionBar(
                        player,
                        String.format(
                                (String)SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"),
                                bet,
                                balance
                        )
                );
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
            }
            case UNKNOWN -> player.sendMessage(
                    ChatColor.RED + "本次老虎机扣款结果未知。核对完成前，您无法继续下注。"
            );
            case PLAYER_FROZEN -> player.sendMessage(
                    ChatColor.RED + "您的账户存在未解决的资金交易，暂时无法继续下注。"
            );
            default -> player.sendMessage(
                    ChatColor.RED + "资金账本当前不可用，本次旋转未开始。"
            );
        }
        SmartGambling.getInstance().getLogger().warning(
                "Slot wager was not accepted for " + player.getName() + " (" + placement.status()
                        + "): " + placement.detail()
        );
    }

    static WagerKey createWagerKey(
            UUID machineId,
            UUID playerId,
            String sessionId,
            long spinOrdinal
    ) {
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(playerId, "playerId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        if (spinOrdinal <= 0L) {
            throw new IllegalArgumentException("spinOrdinal must be positive");
        }
        return new WagerKey(
                "slot",
                machineId.toString(),
                sessionId + ":" + spinOrdinal,
                playerId,
                "spin:" + spinOrdinal
        );
    }

    static String wagerOperationId(WagerHandle wager, String outcome) {
        Objects.requireNonNull(wager, "wager");
        if (!"loss".equals(outcome) && !"payout".equals(outcome) && !"refund".equals(outcome)) {
            throw new IllegalArgumentException("Unsupported slot wager outcome: " + outcome);
        }
        return "slot:" + wager.id() + ":" + outcome;
    }

    private boolean hasCompleteResult(SlotMachine.PlayerInventoryData invData) {
        if (invData.finalItems.length == 0) {
            return false;
        }
        for (SlotItem item : invData.finalItems) {
            if (item == null) {
                return false;
            }
        }
        return true;
    }

    private void cancelTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private void cancelAnimationTasks(SlotMachine.PlayerInventoryData invData) {
        for (BukkitTask task : new HashSet<>(invData.animationTasks)) {
            this.cancelTask(task);
        }
        invData.animationTasks.clear();
    }

    private void scheduleAnimationTask(
            Player player,
            SlotMachine.PlayerInventoryData invData,
            Runnable action,
            long delay
    ) {
        if (!SmartGambling.getInstance().isEnabled()) {
            return;
        }
        BukkitTask[] reference = new BukkitTask[1];
        reference[0] = Bukkit.getScheduler().runTaskLater(
                SmartGambling.getInstance(),
                () -> {
                    invData.animationTasks.remove(reference[0]);
                    if (this.playerInventoryData.get(player) != invData) {
                        return;
                    }
                    action.run();
                },
                Math.max(0L, delay)
        );
        invData.animationTasks.add(reference[0]);
    }

    private SlotItem getRandomItem() {
        int number = SmartGambling.getInstance().random.nextInt(this.itemsTotalWeight);
        return (SlotItem)this.itemsWeighed.higherEntry(number).getValue();
    }

    private Reward checkRewards(SlotItem[] results) {
        Reward maxReward = null;

        for(Reward reward : this.rewards) {
            if (reward.check(results) && (maxReward == null || reward.moneyMultiplier > maxReward.moneyMultiplier)) {
                maxReward = reward;
            }
        }

        return maxReward;
    }

    private void playSpinningMusic(Player player, SlotMachine.PlayerInventoryData invData, boolean pitch) {
        if (this.playerInventoryData.get(player) == invData && invData.animationSpeed.length > 0) {
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5F, pitch ? 1.0F : 1.5F);
            int speedIndex = Math.min(1, invData.animationSpeed.length - 1);
            int pitchIndex = Math.min(3, invData.animationSpeed.length - 1);
            if (invData.animationSpeed[speedIndex] > 0) {
                this.scheduleAnimationTask(
                        player,
                        invData,
                        () -> this.playSpinningMusic(player, invData, !pitch),
                        invData.animationSpeed[pitchIndex] == 1
                                ? 4L
                                : (long)invData.animationSpeed[speedIndex] * 3L
                );
            }

        }
    }

    private void spinLine(int line, Inventory inventory, Player player, SlotMachine.PlayerInventoryData invData) {
        if (this.playerInventoryData.get(player) == invData && invData.spinning) {
            boolean forcedTerminalFrame = invData.forcedFinalItems != null
                    && invData.animationSpeed[line] == 0;

            int topSlot = this.displaySlots.get(line).get(0);
            int middleSlot = this.displaySlots.get(line).get(1);
            int bottomSlot = this.displaySlots.get(line).get(2);
            boolean forcedFrameRendered = false;
            if (forcedTerminalFrame) {
                forcedFrameRendered = this.renderForcedTerminalFrame(line, inventory, invData);
                if (!forcedFrameRendered) {
                    return;
                }
            } else {
                invData.finalItems[line] = invData.lastItems[line];
                invData.lastItems[line] = this.getRandomItem();
                inventory.setItem(bottomSlot, inventory.getItem(middleSlot));
                inventory.setItem(middleSlot, inventory.getItem(topSlot));
                inventory.setItem(topSlot, invData.lastItems[line].itemStack);
            }
            if (shouldAnimateDependent(line, forcedFrameRendered)) {
                this.animations.animateDependent(inventory);
            }

            player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.02F, 0.5F);
            if (invData.animationSpeed[line] > 0) {
                this.scheduleAnimationTask(
                        player,
                        invData,
                        () -> this.spinLine(line, inventory, player, invData),
                        invData.animationSpeed[line]
                );
            }

        }
    }

    static boolean shouldAnimateDependent(int line, boolean forcedFrameRendered) {
        return line == 1 && !forcedFrameRendered;
    }

    static long reelStopDelay(int animationDuration, int line) {
        if (animationDuration < 0 || line < 0) {
            throw new IllegalArgumentException("Animation duration and reel index cannot be negative");
        }
        return (long) animationDuration + 20L + 6L * line;
    }

    static long spinEndDelay(int animationDuration, int reelCount) {
        if (animationDuration < 0 || reelCount <= 0) {
            throw new IllegalArgumentException("Animation duration cannot be negative and reel count must be positive");
        }
        return (long) animationDuration + 21L + 6L * reelCount;
    }

    private boolean renderForcedTerminalFrame(
            int line,
            Inventory inventory,
            SlotMachine.PlayerInventoryData invData
    ) {
        if (!isForcedLinePending(invData.forcedFinalItems, invData.forcedLinesApplied, line)) {
            return false;
        }
        SlotItem forcedItem = invData.forcedFinalItems[line];
        if (forcedItem == null || forcedItem.itemStack == null) {
            throw new IllegalStateException("Forced slot result contains a non-displayable item");
        }

        int topSlot = this.displaySlots.get(line).get(0);
        int middleSlot = this.displaySlots.get(line).get(1);
        int bottomSlot = this.displaySlots.get(line).get(2);
        SlotItem filler = this.getRandomItem();
        invData.lastItems[line] = filler;
        inventory.setItem(bottomSlot, inventory.getItem(middleSlot));
        inventory.setItem(middleSlot, forcedItem.itemStack.clone());
        inventory.setItem(topSlot, filler.itemStack);
        boolean complete = copyForcedLineResult(
                invData.forcedFinalItems,
                invData.finalItems,
                invData.forcedLinesApplied,
                line
        );
        if (complete && !invData.forcedResultApplied) {
            invData.forcedResultApplied = true;
            SmartGambling plugin = SmartGambling.getInstance();
            try {
                plugin.auditForcedSlotDirective(
                        "applied",
                        invData.forcedDirective,
                        "wager=" + invData.wager.id()
                );
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to audit applied forced slot directive", exception);
            }
        }
        return true;
    }

    static boolean isForcedLinePending(SlotItem[] forcedItems, boolean[] appliedLines, int line) {
        if (forcedItems == null) {
            return false;
        }
        Objects.requireNonNull(appliedLines, "appliedLines");
        if (forcedItems.length == 0 || forcedItems.length != appliedLines.length) {
            throw new IllegalArgumentException("Forced slot result must match the configured reel count");
        }
        if (line < 0 || line >= forcedItems.length) {
            throw new IndexOutOfBoundsException("Forced slot line is outside the configured reels: " + line);
        }
        return !appliedLines[line];
    }

    private void startAnimation(Inventory inventory, Player player) {
        SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);
        this.cancelAnimationTasks(invData);

        for(int i = 0; i < this.displaySlots.size(); ++i) {
            invData.animationSpeed[i] = this.animationStartingSpeed;
        }

        this.playSpinningMusic(player, invData, true);

        for(int i = 0; i < this.displaySlots.size(); ++i) {
            int k = i;
            this.scheduleAnimationTask(player, invData, () -> {
                int var10002 = invData.animationSpeed[k]--;
                this.spinLine(k, inventory, player, invData);
            }, 2L * (long)i);
        }

        for(int j = 0; j < 2; ++j) {
            this.scheduleAnimationTask(player, invData, () -> {
                for(int i = 0; i < this.displaySlots.size(); ++i) {
                    int k = i;
                    this.scheduleAnimationTask(player, invData, () -> {
                        int var10002 = invData.animationSpeed[k]--;
                    }, 2L * (long)i);
                }

            }, (long)j * 4L);
        }

        this.scheduleAnimationTask(player, invData, () -> {
            for(int j = 0; j < 3; ++j) {
                this.scheduleAnimationTask(player, invData, () -> {
                    for(int i = 0; i < this.displaySlots.size(); ++i) {
                        int k = i;
                        this.scheduleAnimationTask(player, invData, () -> {
                            int var10002 = invData.animationSpeed[k]++;
                        }, 4L * (long)i);
                    }

                }, (long)j * 5L);
            }

        }, (long)this.animationDuration);
        for (int i = 0; i < this.displaySlots.size(); ++i) {
            int k = i;
            this.scheduleAnimationTask(player, invData, () -> {
                invData.animationSpeed[k] = 0;
                boolean forcedFrameRendered = this.renderForcedTerminalFrame(k, inventory, invData);
                if (forcedFrameRendered) {
                    player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.02F, 0.5F);
                }
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BIT, 2.0F, 1.0F);
            }, reelStopDelay(this.animationDuration, i));
        }

    }

    private static class PlayerInventoryData {
        public final int[] animationSpeed;
        public final SlotItem[] lastItems;
        public final SlotItem[] finalItems;
        public final Set<BukkitTask> animationTasks = new HashSet<>();
        public boolean spinning = false;
        public boolean resultsReady = false;
        public boolean wagerPending = false;
        public boolean detached = false;
        public int wagerAmount = 0;
        public final UUID machineId;
        public final String sessionId;
        public long spinOrdinal = 0L;
        public WagerHandle wager;
        public BukkitTask spinEndTask;
        public BukkitTask settlementTask;
        public SlotItem[] forcedFinalItems;
        public boolean[] forcedLinesApplied;
        public boolean forcedResultApplied;
        public ForcedSlotResultRegistry.Directive forcedDirective;

        private PlayerInventoryData(
                int[] animationSpeed,
                SlotItem[] lastItems,
                SlotItem[] finalItems,
                UUID machineId,
                String sessionId
        ) {
            this.animationSpeed = animationSpeed;
            this.lastItems = lastItems;
            this.finalItems = finalItems;
            this.machineId = Objects.requireNonNull(machineId, "machineId");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        }

        private long nextSpinOrdinal() {
            if (this.spinOrdinal == Long.MAX_VALUE) {
                throw new IllegalStateException("Slot session exhausted its spin ordinal");
            }
            return ++this.spinOrdinal;
        }

        private void clearForcedResult() {
            this.forcedFinalItems = null;
            this.forcedLinesApplied = null;
            this.forcedResultApplied = false;
            this.forcedDirective = null;
        }
    }
}
