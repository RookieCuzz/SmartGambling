package me.arthed.smartgambling.games.poker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.economy.EconomyService;
import me.arthed.smartgambling.economy.Money;
import me.arthed.smartgambling.economy.PlaceResult;
import me.arthed.smartgambling.economy.TxResult;
import me.arthed.smartgambling.economy.WagerHandle;
import me.arthed.smartgambling.economy.WagerKey;
import me.arthed.smartgambling.economy.WagerResolution;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.ConfirmableWagerMachine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.utils.DisplayUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/** One-hand, two-player no-limit Texas Hold'em table backed by the durable wager ledger. */
public final class Poker implements ConfirmableWagerMachine {
    private static final int MAX_SETTLEMENT_RETRY_ATTEMPTS = 12;
    private static final long SETTLEMENT_RETRY_DELAY_TICKS = 100L;

    public final ItemStack machineItem;
    public final double[] entityOffset;
    public final double[] chair1Offset;
    public final double[] chair2Offset;
    private final Inventory baseInventory;
    private final InventoryAnimations animations;
    private final String inventoryTitle;
    private final Button foldButton;
    private final Button checkCallButton;
    private final Button minimumRaiseButton;
    private final Button potRaiseButton;
    private final Button allInButton;
    private final ItemStack cardBack;
    private final List<Integer> ownCardSlots;
    private final List<Integer> opponentCardSlots;
    private final List<Integer> communityCardSlots;
    private final List<PokerCard> deckTemplate;
    private final int smallBlind;
    private final int bigBlind;
    private final int actionTimeoutSeconds;
    private final int resultDisplayTicks;

    private enum Reservation {
        HOST,
        CHALLENGER
    }

    public Poker(
            ItemStack machineItem,
            double[] entityOffset,
            double[] chair1Offset,
            double[] chair2Offset,
            Inventory baseInventory,
            InventoryAnimations animations,
            String inventoryTitle,
            Button foldButton,
            Button checkCallButton,
            Button minimumRaiseButton,
            Button potRaiseButton,
            Button allInButton,
            ItemStack cardBack,
            List<Integer> ownCardSlots,
            List<Integer> opponentCardSlots,
            List<Integer> communityCardSlots,
            List<PokerCard> deckTemplate,
            int smallBlind,
            int bigBlind,
            int actionTimeoutSeconds,
            int resultDisplayTicks
    ) {
        this.machineItem = Objects.requireNonNull(machineItem, "machineItem");
        this.entityOffset = Objects.requireNonNull(entityOffset, "entityOffset").clone();
        this.chair1Offset = Objects.requireNonNull(chair1Offset, "chair1Offset").clone();
        this.chair2Offset = Objects.requireNonNull(chair2Offset, "chair2Offset").clone();
        this.baseInventory = Objects.requireNonNull(baseInventory, "baseInventory");
        this.animations = Objects.requireNonNull(animations, "animations");
        this.inventoryTitle = Objects.requireNonNull(inventoryTitle, "inventoryTitle");
        this.foldButton = Objects.requireNonNull(foldButton, "foldButton");
        this.checkCallButton = Objects.requireNonNull(checkCallButton, "checkCallButton");
        this.minimumRaiseButton = Objects.requireNonNull(minimumRaiseButton, "minimumRaiseButton");
        this.potRaiseButton = Objects.requireNonNull(potRaiseButton, "potRaiseButton");
        this.allInButton = Objects.requireNonNull(allInButton, "allInButton");
        this.cardBack = Objects.requireNonNull(cardBack, "cardBack");
        this.ownCardSlots = requireSize(ownCardSlots, 2, "ownCardSlots");
        this.opponentCardSlots = requireSize(opponentCardSlots, 2, "opponentCardSlots");
        this.communityCardSlots = requireSize(communityCardSlots, 5, "communityCardSlots");
        this.deckTemplate = List.copyOf(Objects.requireNonNull(deckTemplate, "deckTemplate"));
        if (this.deckTemplate.size() != 52) {
            throw new IllegalArgumentException("Texas Hold'em requires exactly 52 configured cards");
        }
        if (smallBlind <= 0 || bigBlind <= smallBlind || actionTimeoutSeconds <= 0 || resultDisplayTicks <= 0) {
            throw new IllegalArgumentException("Poker blinds and timeouts must be positive and ordered");
        }
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.actionTimeoutSeconds = actionTimeoutSeconds;
        this.resultDisplayTicks = resultDisplayTicks;
    }

    @Override
    public boolean canConfirmChallenger(Player player, MachineData machineData) {
        return machineData instanceof MachineDataPoker poker && poker.canCommitChallenger(player);
    }

    @Override
    public int requiredStake(MachineData machineData) {
        if (!(machineData instanceof MachineDataPoker poker)) {
            throw new IllegalArgumentException("Poker confirmation requires a poker table");
        }
        return poker.buyIn;
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        if (!(openInterface instanceof OpenPoker openPoker)
                || !(openPoker.machineData instanceof MachineDataPoker machineData)) {
            return;
        }
        if (openInterface.betAmount == 0) {
            if (SmartGambling.getInstance().openMachines.containsKey(player)) {
                return;
            }
            openBetSelection(player, openPoker, machineData);
            return;
        }

        player.closeInventory();
        if (openInterface.betAmount <= 0) {
            machineData.releaseReservation(player);
            removeOpenMapping(player, machineData);
            return;
        }

        if (machineData.canCommitHost(player)) {
            commitHost(player, openPoker, machineData);
        } else if (machineData.canCommitChallenger(player)) {
            commitChallenger(player, openPoker, machineData);
        } else {
            rejectOccupied(player);
        }
    }

    private void openBetSelection(Player player, OpenPoker openPoker, MachineDataPoker machineData) {
        Reservation reservation = null;
        synchronized (machineData) {
            if (machineData.host == null && machineData.reserveHost(player)) {
                reservation = Reservation.HOST;
            } else if (machineData.reserveChallenger(player)) {
                reservation = Reservation.CHALLENGER;
            }
        }
        if (reservation == null) {
            rejectOccupied(player);
            return;
        }
        player.closeInventory();
        try {
            if (reservation == Reservation.HOST) {
                SmartGambling.getInstance().moneyInventory.open(player, openPoker);
            } else {
                SmartGambling.getInstance().confirmGameInventory.open(player, openPoker);
            }
        } catch (RuntimeException exception) {
            machineData.releaseReservation(player);
            removeOpenMapping(player, machineData);
            SmartGambling.getInstance().getLogger().log(Level.SEVERE,
                    "Could not open poker buy-in selection for " + player.getName(), exception);
        }
    }

    private void commitHost(Player player, OpenPoker openPoker, MachineDataPoker machineData) {
        int amount = openPoker.betAmount;
        if (amount < bigBlind) {
            machineData.releaseReservation(player);
            player.sendMessage(ChatColor.RED + "德州扑克买入必须至少覆盖大盲 " + bigBlind + "。 ");
            return;
        }
        PlaceResult placement;
        synchronized (machineData) {
            if (!machineData.canCommitHost(player)) {
                rejectOccupied(player);
                return;
            }
            placement = placeWager(machineData, player, amount, "host", machineData.pendingHostWagerNonce);
            if (accepted(placement)) {
                machineData.pendingHost = null;
                machineData.pendingHostWagerNonce = null;
                machineData.host = player;
                machineData.buyIn = amount;
                machineData.hostWager = placement.wager();
                machineData.hostStakePaid = true;
                machineData.refreshInUse();
            } else {
                machineData.releaseReservation(player);
            }
        }
        if (!accepted(placement)) {
            notifyPlacementFailure(player, placement);
            return;
        }
        if (placement.wager().stake().compareTo(Money.of(amount)) != 0) {
            SmartGambling.getInstance().getLogger().severe("Poker host wager stake mismatch at " + machineData.id);
            refundTable(machineData, "poker host wager mismatch");
            return;
        }

        openPoker.inventory = null;
        SmartGambling.getInstance().openMachines.put(player, openPoker);
        sendMoneyExtracted(player, amount);
        try {
            seatPlayer(machineData, player, 0);
            startWaitingMessage(machineData);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(Level.SEVERE,
                    "Could not initialize poker waiting state for " + machineData.id, exception);
            refundTable(machineData, "poker waiting state failed");
        }
    }

    private void commitChallenger(Player player, OpenPoker openPoker, MachineDataPoker machineData) {
        Player host;
        PlaceResult placement;
        synchronized (machineData) {
            if (!machineData.canCommitChallenger(player)
                    || openPoker.betAmount != machineData.buyIn || machineData.buyIn < bigBlind) {
                machineData.releaseReservation(player);
                rejectOccupied(player);
                return;
            }
            host = machineData.host;
            if (host == null || !host.isOnline()) {
                machineData.releaseReservation(player);
                refundTable(machineData, "poker host left before challenger joined");
                return;
            }
            placement = placeWager(
                    machineData,
                    player,
                    machineData.buyIn,
                    "challenger",
                    machineData.pendingChallengerWagerNonce
            );
            if (accepted(placement)) {
                machineData.pendingChallenger = null;
                machineData.pendingChallengerWagerNonce = null;
                machineData.challenger = player;
                machineData.challengerWager = placement.wager();
                machineData.challengerStakePaid = true;
                machineData.refreshInUse();
            } else {
                machineData.releaseReservation(player);
            }
        }
        if (!accepted(placement)) {
            notifyPlacementFailure(player, placement);
            return;
        }
        if (placement.wager().stake().compareTo(Money.of(machineData.buyIn)) != 0) {
            SmartGambling.getInstance().getLogger().severe("Poker challenger wager stake mismatch at " + machineData.id);
            refundTable(machineData, "poker challenger wager mismatch");
            return;
        }

        openPoker.inventory = null;
        SmartGambling.getInstance().openMachines.put(player, openPoker);
        sendMoneyExtracted(player, machineData.buyIn);
        try {
            seatPlayer(machineData, player, 1);
            TxResult lock = lockWagers(machineData);
            if (lock == null || !lock.durable()) {
                logTxFailure(machineData, "lock", lock);
                refundTable(machineData, "poker wager lock failed");
                return;
            }
            synchronized (machineData) {
                machineData.wagersLocked = true;
                machineData.refreshInUse();
            }
            startHand(host, player, machineData);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(Level.SEVERE,
                    "Could not start poker hand at " + machineData.id, exception);
            refundTable(machineData, "poker hand failed to start");
        }
    }

    private void startHand(Player host, Player challenger, MachineDataPoker machineData) {
        cancelTask(machineData.waitingTask);
        machineData.waitingTask = null;
        machineData.round = new HeadsUpPokerRound(machineData.buyIn, smallBlind, bigBlind);
        machineData.deck = new ArrayList<>(deckTemplate);
        Collections.shuffle(machineData.deck, SmartGambling.getInstance().random);
        machineData.deckCursor = 0;
        machineData.hostCards = new ArrayList<>(2);
        machineData.challengerCards = new ArrayList<>(2);
        machineData.communityCards = new ArrayList<>(5);
        machineData.hostCards.add(draw(machineData));
        machineData.challengerCards.add(draw(machineData));
        machineData.hostCards.add(draw(machineData));
        machineData.challengerCards.add(draw(machineData));

        machineData.hostInventory = createInventory(host);
        machineData.challengerInventory = createInventory(challenger);
        installOpenInventory(host, machineData, machineData.hostInventory);
        installOpenInventory(challenger, machineData, machineData.challengerInventory);
        animations.startAnimations(machineData.hostInventory);
        animations.startAnimations(machineData.challengerInventory);
        renderTable(machineData, false, null);
        host.openInventory(machineData.hostInventory);
        challenger.openInventory(machineData.challengerInventory);
        broadcast(machineData, ChatColor.GOLD + "牌局开始：庄家/小盲 " + smallBlind
                + "，大盲 " + bigBlind + "，买入 " + machineData.buyIn + "。 ");
        scheduleTurnTimeout(machineData);
        machineData.refreshInUse();
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        MachineDataPoker machineData = findMachineData(player);
        if (machineData == null || machineData.round == null || machineData.resolving
                || machineData.round.complete()) {
            return;
        }
        HeadsUpPokerRound.Seat seat = machineData.seat(player);
        if (seat == null || machineData.round.actor() != seat) {
            DisplayUtils.displayActionBar(player, ChatColor.YELLOW + "现在轮到对手行动。 ");
            return;
        }

        try {
            if (foldButton.isClicked(event.getSlot())) {
                performAction(machineData, seat, HeadsUpPokerRound.Action.FOLD, -1L);
            } else if (checkCallButton.isClicked(event.getSlot())) {
                HeadsUpPokerRound.Action action = machineData.round.toCall(seat) == 0L
                        ? HeadsUpPokerRound.Action.CHECK
                        : HeadsUpPokerRound.Action.CALL;
                performAction(machineData, seat, action, -1L);
            } else if (minimumRaiseButton.isClicked(event.getSlot())) {
                ensureCanRaise(machineData, seat);
                performAction(machineData, seat, HeadsUpPokerRound.Action.RAISE_TO,
                        machineData.round.minimumRaiseTo(seat));
            } else if (potRaiseButton.isClicked(event.getSlot())) {
                ensureCanRaise(machineData, seat);
                performAction(machineData, seat, HeadsUpPokerRound.Action.RAISE_TO,
                        machineData.round.suggestedPotRaiseTo(seat));
            } else if (allInButton.isClicked(event.getSlot())) {
                performAction(machineData, seat, HeadsUpPokerRound.Action.ALL_IN, -1L);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            DisplayUtils.displayActionBar(player, ChatColor.RED + exception.getMessage());
        }
    }

    private void ensureCanRaise(MachineDataPoker machineData, HeadsUpPokerRound.Seat seat) {
        if (!machineData.round.canRaise(seat)) {
            throw new IllegalStateException("当前不能继续加注。 ");
        }
    }

    private void performAction(
            MachineDataPoker machineData,
            HeadsUpPokerRound.Seat seat,
            HeadsUpPokerRound.Action action,
            long target
    ) {
        cancelTask(machineData.turnTask);
        machineData.turnTask = null;
        HeadsUpPokerRound.ActionResult result = action == HeadsUpPokerRound.Action.RAISE_TO
                ? machineData.round.act(seat, action, target)
                : machineData.round.act(seat, action);
        broadcastAction(machineData, seat, action, target, result.chipsPaid());

        if (result.handComplete()) {
            settleCompletedHand(machineData, machineData.round.settleFold(), "fold");
            return;
        }
        if (result.showdownReady()) {
            revealRemainingBoard(machineData);
            machineData.round.moveToShowdown();
            showdown(machineData);
            return;
        }
        if (result.streetComplete()) {
            machineData.round.advanceStreet();
            revealForStreet(machineData);
            broadcast(machineData, ChatColor.AQUA + "进入 " + streetName(machineData.round.street()) + "。 ");
        }
        renderTable(machineData, false, null);
        scheduleTurnTimeout(machineData);
    }

    private void showdown(MachineDataPoker machineData) {
        List<PokerCard> hostSeven = new ArrayList<>(machineData.hostCards);
        hostSeven.addAll(machineData.communityCards);
        List<PokerCard> challengerSeven = new ArrayList<>(machineData.challengerCards);
        challengerSeven.addAll(machineData.communityCards);
        PokerHandValue hostHand = PokerHandEvaluator.evaluate(hostSeven);
        PokerHandValue challengerHand = PokerHandEvaluator.evaluate(challengerSeven);
        int comparison = hostHand.compareTo(challengerHand);
        HeadsUpPokerRound.Settlement settlement = machineData.round.settleShowdown(comparison);
        String summary = comparison > 0
                ? "庄家以" + hostHand.category().displayName() + "获胜"
                : comparison < 0
                        ? "挑战者以" + challengerHand.category().displayName() + "获胜"
                        : "双方" + hostHand.category().displayName() + "，平分底池";
        renderTable(machineData, true, summary);
        broadcast(machineData, ChatColor.GOLD + "摊牌：" + summary + "。 ");
        settleCompletedHand(machineData, settlement, "showdown");
    }

    private void settleCompletedHand(
            MachineDataPoker machineData,
            HeadsUpPokerRound.Settlement settlement,
            String reason
    ) {
        synchronized (machineData) {
            machineData.resolving = true;
            if (machineData.pendingSettlementOperationId == null) {
                machineData.pendingSettlementOperationId = roundOperationId(
                        machineData.id, machineData.roundId, "settle");
                machineData.pendingRefund = false;
                machineData.pendingHostPayout = settlement.hostPayout();
                machineData.pendingChallengerPayout = settlement.challengerPayout();
            }
            machineData.refreshInUse();
        }
        TxResult result = executePendingSettlement(machineData, reason);
        if (result != null && result.durable()) {
            String summary = settlement.split()
                    ? "平分底池"
                    : (settlement.winner() == HeadsUpPokerRound.Seat.HOST ? "庄家获胜" : "挑战者获胜");
            renderTable(machineData, true, summary);
            scheduleCleanup(machineData);
        } else {
            broadcast(machineData, ChatColor.RED + "账本结算暂未完成，牌桌已锁定并自动重试。 ");
            scheduleSettlementRetry(machineData);
        }
    }

    private void revealForStreet(MachineDataPoker machineData) {
        switch (machineData.round.street()) {
            case FLOP -> {
                burn(machineData);
                machineData.communityCards.add(draw(machineData));
                machineData.communityCards.add(draw(machineData));
                machineData.communityCards.add(draw(machineData));
            }
            case TURN, RIVER -> {
                burn(machineData);
                machineData.communityCards.add(draw(machineData));
            }
            default -> throw new IllegalStateException("Cannot reveal cards for " + machineData.round.street());
        }
    }

    private void revealRemainingBoard(MachineDataPoker machineData) {
        while (machineData.communityCards.size() < 5) {
            burn(machineData);
            int count = machineData.communityCards.isEmpty() ? 3 : 1;
            for (int index = 0; index < count; index++) {
                machineData.communityCards.add(draw(machineData));
            }
        }
    }

    private PokerCard draw(MachineDataPoker machineData) {
        if (machineData.deckCursor >= machineData.deck.size()) {
            throw new IllegalStateException("Poker deck is exhausted");
        }
        return machineData.deck.get(machineData.deckCursor++);
    }

    private void burn(MachineDataPoker machineData) {
        draw(machineData);
    }

    @Override
    public void close(Player player, Inventory inventory) {
        MachineDataPoker machineData = findMachineData(player);
        if (machineData == null) {
            return;
        }
        if (inventory == null) {
            forceClose(player);
            return;
        }
        if (machineData.round != null && !machineData.resolving) {
            Inventory expected = machineData.seat(player) == HeadsUpPokerRound.Seat.HOST
                    ? machineData.hostInventory : machineData.challengerInventory;
            Bukkit.getScheduler().runTask(SmartGambling.getInstance(), () -> {
                if (player.isOnline() && findMachineData(player) == machineData
                        && machineData.round != null && !machineData.resolving) {
                    player.openInventory(expected);
                }
            });
        }
    }

    @Override
    public void forceClose(Player player) {
        MachineDataPoker machineData = findMachineData(player);
        if (machineData == null) {
            removeOpenMapping(player, null);
            return;
        }
        machineData.releaseReservation(player);
        if (machineData.round != null && !machineData.round.complete() && !machineData.resolving) {
            HeadsUpPokerRound.Seat seat = machineData.seat(player);
            if (seat != null) {
                cancelTask(machineData.turnTask);
                machineData.turnTask = null;
                HeadsUpPokerRound.Settlement settlement = machineData.round.forfeit(seat);
                broadcast(machineData, ChatColor.YELLOW + player.getName() + " 离开牌桌，按弃牌处理。 ");
                settleCompletedHand(machineData, settlement, "participant forfeit");
            }
        } else if (machineData.round == null && machineData.host == player
                && machineData.hostWager != null) {
            refundTable(machineData, "waiting host left poker table");
        }
        removeOpenMapping(player, machineData);
        player.leaveVehicle();
    }

    public void shutdownAndRefund() {
        Set<MachineDataPoker> tables = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MachineData machineData : SmartGambling.getInstance().uuidMachines.values()) {
            if (machineData instanceof MachineDataPoker poker && poker.machineType == this) {
                tables.add(poker);
            }
        }
        for (OpenInterface open : SmartGambling.getInstance().openMachines.values()) {
            if (open instanceof OpenPoker poker && poker.machineData instanceof MachineDataPoker table
                    && table.machineType == this) {
                tables.add(table);
            }
        }
        for (MachineDataPoker table : tables) {
            cancelTask(table.waitingTask);
            cancelTask(table.turnTask);
            cancelTask(table.cleanupTask);
            refundTable(table, "poker shutdown refund");
        }
    }

    private void refundTable(MachineDataPoker machineData, String reason) {
        synchronized (machineData) {
            machineData.resolving = true;
            if (machineData.pendingSettlementOperationId == null) {
                machineData.pendingSettlementOperationId = roundOperationId(
                        machineData.id, machineData.roundId, "settle");
                machineData.pendingRefund = true;
                machineData.pendingHostPayout = -1L;
                machineData.pendingChallengerPayout = -1L;
            }
            machineData.refreshInUse();
        }
        TxResult result = executePendingSettlement(machineData, reason);
        if (result != null && result.durable()) {
            cleanupTable(machineData);
        } else {
            scheduleSettlementRetry(machineData);
        }
    }

    private TxResult executePendingSettlement(MachineDataPoker machineData, String reason) {
        TxResult result;
        long hostPayout;
        long challengerPayout;
        boolean refund;
        synchronized (machineData) {
            if (!hasOutstandingWagers(machineData)) {
                clearPendingSettlement(machineData);
                return new TxResult(TxResult.Status.ALREADY_APPLIED, null, "no live poker wagers");
            }
            if (machineData.pendingSettlementOperationId == null) {
                return new TxResult(TxResult.Status.CONFLICT, null, "poker settlement has no operation id");
            }
            refund = machineData.pendingRefund;
            hostPayout = machineData.pendingHostPayout;
            challengerPayout = machineData.pendingChallengerPayout;
            List<EconomyService.Resolution> resolutions;
            try {
                resolutions = buildResolutions(
                        machineData.hostWager,
                        machineData.challengerWager,
                        refund,
                        hostPayout,
                        challengerPayout
                );
                result = SmartGambling.getInstance().getEconomyService().resolveAll(
                        machineData.pendingSettlementOperationId,
                        resolutions
                );
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(Level.SEVERE,
                        "Could not submit poker settlement for " + machineData.id, exception);
                result = new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
            }
            if (result.durable()) {
                machineData.hostWager = null;
                machineData.challengerWager = null;
                machineData.hostStakePaid = false;
                machineData.challengerStakePaid = false;
                machineData.wagersLocked = false;
                machineData.settlementRetryAttempts = 0;
                clearPendingSettlement(machineData);
            }
        }
        if (!result.durable()) {
            logTxFailure(machineData, reason, result);
        } else if (result.status() == TxResult.Status.DURABLE && !refund) {
            notifyPayout(machineData.host, hostPayout);
            notifyPayout(machineData.challenger, challengerPayout);
        }
        return result;
    }

    static List<EconomyService.Resolution> buildResolutions(
            WagerHandle host,
            WagerHandle challenger,
            boolean refund,
            long hostPayout,
            long challengerPayout
    ) {
        List<EconomyService.Resolution> resolutions = new ArrayList<>(2);
        if (refund) {
            if (host != null) {
                resolutions.add(new EconomyService.Resolution(host, WagerResolution.refund()));
            }
            if (challenger != null) {
                resolutions.add(new EconomyService.Resolution(challenger, WagerResolution.refund()));
            }
            return resolutions;
        }
        if (host == null || challenger == null || hostPayout < 0L || challengerPayout < 0L) {
            throw new IllegalArgumentException("Completed poker settlement requires two wagers and non-negative payouts");
        }
        long escrow = Math.addExact(host.stake().decimal().longValueExact(), challenger.stake().decimal().longValueExact());
        if (Math.addExact(hostPayout, challengerPayout) != escrow) {
            throw new IllegalArgumentException("Poker payouts must exactly conserve escrow");
        }
        resolutions.add(new EconomyService.Resolution(host, resolution(hostPayout)));
        resolutions.add(new EconomyService.Resolution(challenger, resolution(challengerPayout)));
        return List.copyOf(resolutions);
    }

    private static WagerResolution resolution(long payout) {
        return payout == 0L ? WagerResolution.loss() : WagerResolution.payout(Money.of(payout));
    }

    static WagerKey createWagerKey(
            UUID machineId,
            UUID roundId,
            UUID playerId,
            String seat,
            UUID nonce
    ) {
        if (!Set.of("host", "challenger").contains(seat)) {
            throw new IllegalArgumentException("Unsupported poker seat: " + seat);
        }
        return new WagerKey(
                "poker",
                Objects.requireNonNull(machineId, "machineId").toString(),
                Objects.requireNonNull(roundId, "roundId").toString(),
                Objects.requireNonNull(playerId, "playerId"),
                seat + ':' + Objects.requireNonNull(nonce, "nonce")
        );
    }

    static String roundOperationId(UUID machineId, UUID roundId, String phase) {
        if (!Set.of("lock", "settle").contains(phase)) {
            throw new IllegalArgumentException("Unsupported poker operation: " + phase);
        }
        return "poker:" + Objects.requireNonNull(machineId, "machineId") + ':'
                + Objects.requireNonNull(roundId, "roundId") + ':' + phase;
    }

    private PlaceResult placeWager(
            MachineDataPoker machineData,
            Player player,
            int amount,
            String seat,
            UUID nonce
    ) {
        try {
            return SmartGambling.getInstance().getEconomyService().place(
                    createWagerKey(machineData.id, machineData.roundId, player.getUniqueId(), seat, nonce),
                    Money.of(amount)
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(Level.SEVERE,
                    "Could not place poker " + seat + " wager at " + machineData.id, exception);
            return new PlaceResult(PlaceResult.Status.STORAGE_FAILURE, null, null, exception.getMessage());
        }
    }

    private TxResult lockWagers(MachineDataPoker machineData) {
        try {
            return SmartGambling.getInstance().getEconomyService().lockAll(
                    roundOperationId(machineData.id, machineData.roundId, "lock"),
                    List.of(machineData.hostWager, machineData.challengerWager)
            );
        } catch (RuntimeException exception) {
            return new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
    }

    private void scheduleSettlementRetry(MachineDataPoker machineData) {
        synchronized (machineData) {
            if (!hasOutstandingWagers(machineData)) {
                cleanupTable(machineData);
                return;
            }
            if (machineData.settlementRetryTask != null && !machineData.settlementRetryTask.isCancelled()) {
                return;
            }
            if (machineData.settlementRetryAttempts >= MAX_SETTLEMENT_RETRY_ATTEMPTS) {
                SmartGambling.getInstance().getLogger().severe(
                        "Poker settlement retry limit reached for " + machineData.id
                                + "; ledger recovery remains authoritative");
                return;
            }
            machineData.settlementRetryAttempts++;
            machineData.settlementRetryTask = Bukkit.getScheduler().runTaskLater(
                    SmartGambling.getInstance(),
                    () -> {
                        machineData.settlementRetryTask = null;
                        TxResult result = executePendingSettlement(machineData, "poker settlement retry");
                        if (result != null && result.durable()) {
                            cleanupTable(machineData);
                        } else {
                            scheduleSettlementRetry(machineData);
                        }
                    },
                    SETTLEMENT_RETRY_DELAY_TICKS
            );
        }
    }

    private void scheduleTurnTimeout(MachineDataPoker machineData) {
        cancelTask(machineData.turnTask);
        HeadsUpPokerRound expectedRound = machineData.round;
        HeadsUpPokerRound.Seat expectedActor = expectedRound.actor();
        if (expectedActor == null) {
            return;
        }
        machineData.turnTask = Bukkit.getScheduler().runTaskLater(
                SmartGambling.getInstance(),
                () -> {
                    machineData.turnTask = null;
                    if (machineData.round != expectedRound || expectedRound.actor() != expectedActor
                            || machineData.resolving || expectedRound.complete()) {
                        return;
                    }
                    HeadsUpPokerRound.Action timeoutAction = expectedRound.toCall(expectedActor) == 0L
                            ? HeadsUpPokerRound.Action.CHECK : HeadsUpPokerRound.Action.FOLD;
                    broadcast(machineData, ChatColor.YELLOW + seatName(expectedActor)
                            + " 操作超时，自动" + (timeoutAction == HeadsUpPokerRound.Action.CHECK ? "过牌" : "弃牌") + "。 ");
                    performAction(machineData, expectedActor, timeoutAction, -1L);
                },
                actionTimeoutSeconds * 20L
        );
    }

    private void scheduleCleanup(MachineDataPoker machineData) {
        cancelTask(machineData.cleanupTask);
        machineData.cleanupTask = Bukkit.getScheduler().runTaskLater(
                SmartGambling.getInstance(),
                () -> cleanupTable(machineData),
                resultDisplayTicks
        );
    }

    private void cleanupTable(MachineDataPoker machineData) {
        cancelTask(machineData.waitingTask);
        cancelTask(machineData.turnTask);
        cancelTask(machineData.cleanupTask);
        cancelTask(machineData.settlementRetryTask);
        Player host = machineData.host;
        Player challenger = machineData.challenger;
        stopAnimations(machineData.hostInventory);
        stopAnimations(machineData.challengerInventory);
        removeOpenMapping(host, machineData);
        removeOpenMapping(challenger, machineData);
        closeAndDismount(host);
        closeAndDismount(challenger);
        synchronized (machineData) {
            machineData.host = null;
            machineData.challenger = null;
            machineData.pendingHost = null;
            machineData.pendingChallenger = null;
            machineData.pendingHostWagerNonce = null;
            machineData.pendingChallengerWagerNonce = null;
            machineData.buyIn = 0;
            machineData.hostInventory = null;
            machineData.challengerInventory = null;
            machineData.hostCards = new ArrayList<>();
            machineData.challengerCards = new ArrayList<>();
            machineData.communityCards = new ArrayList<>();
            machineData.deck = new ArrayList<>();
            machineData.deckCursor = 0;
            machineData.round = null;
            machineData.resolving = false;
            machineData.roundId = UUID.randomUUID();
            machineData.waitingTask = null;
            machineData.turnTask = null;
            machineData.cleanupTask = null;
            machineData.settlementRetryTask = null;
            machineData.refreshInUse();
        }
    }

    private void renderTable(MachineDataPoker machineData, boolean revealOpponent, String result) {
        if (machineData.hostInventory != null) {
            renderInventory(machineData, machineData.hostInventory, HeadsUpPokerRound.Seat.HOST,
                    revealOpponent, result);
        }
        if (machineData.challengerInventory != null) {
            renderInventory(machineData, machineData.challengerInventory, HeadsUpPokerRound.Seat.CHALLENGER,
                    revealOpponent, result);
        }
    }

    private void renderInventory(
            MachineDataPoker machineData,
            Inventory inventory,
            HeadsUpPokerRound.Seat viewer,
            boolean revealOpponent,
            String result
    ) {
        List<PokerCard> own = viewer == HeadsUpPokerRound.Seat.HOST
                ? machineData.hostCards : machineData.challengerCards;
        List<PokerCard> opponent = viewer == HeadsUpPokerRound.Seat.HOST
                ? machineData.challengerCards : machineData.hostCards;
        for (int index = 0; index < ownCardSlots.size(); index++) {
            inventory.setItem(ownCardSlots.get(index), index < own.size() ? own.get(index).displayItem() : null);
            inventory.setItem(opponentCardSlots.get(index), index < opponent.size()
                    ? (revealOpponent ? opponent.get(index).displayItem() : cardBack.clone()) : null);
        }
        for (int index = 0; index < communityCardSlots.size(); index++) {
            inventory.setItem(communityCardSlots.get(index), index < machineData.communityCards.size()
                    ? machineData.communityCards.get(index).displayItem() : null);
        }
        HeadsUpPokerRound round = machineData.round;
        boolean turn = round != null && !round.complete() && round.actor() == viewer;
        long call = round == null ? 0L : round.toCall(viewer);
        List<String> statusLore = round == null ? List.of() : List.of(
                ChatColor.WHITE + "阶段: " + ChatColor.AQUA + streetName(round.street()),
                ChatColor.WHITE + "底池: " + ChatColor.GOLD + round.pot(),
                ChatColor.WHITE + "你的筹码: " + ChatColor.GREEN + round.stack(viewer),
                ChatColor.WHITE + "对手筹码: " + ChatColor.YELLOW + round.stack(viewer.other()),
                ChatColor.WHITE + "待跟注: " + ChatColor.RED + call,
                result == null
                        ? (turn ? ChatColor.GREEN + "轮到你行动" : ChatColor.GRAY + "等待对手行动")
                        : ChatColor.GOLD + result
        );
        setButton(inventory, foldButton, turn ? ChatColor.RED + "弃牌" : ChatColor.DARK_GRAY + "弃牌", statusLore);
        setButton(inventory, checkCallButton,
                turn ? (call == 0L ? ChatColor.GREEN + "过牌" : ChatColor.GREEN + "跟注 " + Math.min(call, round.stack(viewer)))
                        : ChatColor.DARK_GRAY + "过牌 / 跟注",
                statusLore);
        long minRaise = round != null && turn && round.canRaise(viewer) ? round.minimumRaiseTo(viewer) : 0L;
        long potRaise = round != null && turn && round.canRaise(viewer) ? round.suggestedPotRaiseTo(viewer) : 0L;
        setButton(inventory, minimumRaiseButton,
                minRaise > 0L ? ChatColor.YELLOW + "最小加注到 " + minRaise : ChatColor.DARK_GRAY + "最小加注",
                statusLore);
        setButton(inventory, potRaiseButton,
                potRaise > 0L ? ChatColor.GOLD + "底池加注到 " + potRaise : ChatColor.DARK_GRAY + "底池加注",
                statusLore);
        setButton(inventory, allInButton,
                turn ? ChatColor.DARK_RED + "全下（" + round.stack(viewer) + "）" : ChatColor.DARK_GRAY + "全下",
                statusLore);
    }

    private void setButton(Inventory inventory, Button button, String name, List<String> lore) {
        for (int slot : button.getSlots()) {
            ItemStack item = baseInventory.getItem(slot);
            if (item == null) {
                continue;
            }
            item = item.clone();
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
    }

    private Inventory createInventory(Player player) {
        Inventory inventory = Bukkit.createInventory((InventoryHolder) player, baseInventory.getSize(), inventoryTitle);
        inventory.setContents(baseInventory.getContents());
        return inventory;
    }

    private void broadcastAction(
            MachineDataPoker machineData,
            HeadsUpPokerRound.Seat seat,
            HeadsUpPokerRound.Action action,
            long target,
            long paid
    ) {
        String text = switch (action) {
            case FOLD -> "弃牌";
            case CHECK -> "过牌";
            case CALL -> "跟注 " + paid;
            case RAISE_TO -> "加注到 " + target;
            case ALL_IN -> "全下 " + paid;
        };
        broadcast(machineData, ChatColor.YELLOW + seatName(seat) + " " + text + "。 ");
    }

    private void broadcast(MachineDataPoker machineData, String message) {
        sendIfOnline(machineData.host, message);
        sendIfOnline(machineData.challenger, message);
    }

    private void startWaitingMessage(MachineDataPoker machineData) {
        cancelTask(machineData.waitingTask);
        machineData.waitingTask = Bukkit.getScheduler().runTaskTimer(
                SmartGambling.getInstance(),
                () -> {
                    if (machineData.host == null || !machineData.host.isOnline()
                            || machineData.challenger != null || machineData.hostWager == null) {
                        cancelTask(machineData.waitingTask);
                        machineData.waitingTask = null;
                        return;
                    }
                    DisplayUtils.displayActionBar(machineData.host,
                            ChatColor.YELLOW + "等待对手加入德州扑克桌，买入 " + machineData.buyIn + "。 ");
                },
                1L,
                40L
        );
    }

    private void installOpenInventory(Player player, MachineDataPoker machineData, Inventory inventory) {
        OpenPoker open = getOpenPoker(player);
        if (open == null || open.machineData != machineData) {
            open = new OpenPoker(this, machineData);
        }
        open.inventory = inventory;
        SmartGambling.getInstance().openMachines.put(player, open);
    }

    private OpenPoker getOpenPoker(Player player) {
        OpenInterface open = SmartGambling.getInstance().openMachines.get(player);
        return open instanceof OpenPoker poker && poker.machineType == this ? poker : null;
    }

    private MachineDataPoker findMachineData(Player player) {
        OpenPoker open = getOpenPoker(player);
        if (open != null && open.machineData instanceof MachineDataPoker poker) {
            return poker;
        }
        for (MachineData machineData : SmartGambling.getInstance().uuidMachines.values()) {
            if (machineData instanceof MachineDataPoker poker && poker.machineType == this
                    && poker.hasParticipant(player)) {
                return poker;
            }
        }
        return null;
    }

    private void removeOpenMapping(Player player, MachineDataPoker machineData) {
        if (player == null) {
            return;
        }
        OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
        if (current instanceof OpenPoker open && open.machineType == this
                && (machineData == null || open.machineData == machineData)) {
            SmartGambling.getInstance().openMachines.remove(player);
        }
    }

    private void seatPlayer(MachineDataPoker machineData, Player player, int entityIndex) {
        if (machineData.entities == null || entityIndex < 0 || entityIndex >= machineData.entities.length
                || machineData.entities[entityIndex] == null || !machineData.entities[entityIndex].isValid()) {
            throw new IllegalStateException("Poker table has no valid seat entity at index " + entityIndex);
        }
        machineData.entities[entityIndex].addPassenger(player);
    }

    private void closeAndDismount(Player player) {
        if (player == null) {
            return;
        }
        player.leaveVehicle();
        if (player.isOnline()) {
            player.closeInventory();
        }
    }

    private void stopAnimations(Inventory inventory) {
        if (inventory != null && animations.isAnimated(inventory)) {
            animations.stopAnimations(inventory);
            animations.startDependentAnimations(inventory);
        }
    }

    private void notifyPlacementFailure(Player player, PlaceResult result) {
        String detail = result == null ? "null result" : result.status() + ": " + result.detail();
        player.sendMessage(ChatColor.RED + "德州扑克买入失败，本局没有开始。 ");
        SmartGambling.getInstance().getLogger().warning(
                "Poker wager was not accepted for " + player.getName() + ": " + detail);
    }

    private void logTxFailure(MachineDataPoker machineData, String phase, TxResult result) {
        SmartGambling.getInstance().getLogger().severe(
                "Poker " + phase + " was not durable for " + machineData.id + ": "
                        + (result == null ? "null" : result.status() + " / " + result.detail()));
    }

    private void rejectOccupied(Player player) {
        DisplayUtils.displayActionBar(player, ChatColor.RED + "这张德州扑克桌当前无法加入。 ");
    }

    private void sendMoneyExtracted(Player player, int amount) {
        player.sendMessage(ChatColor.YELLOW + "德州扑克买入已托管：" + amount + "。 ");
    }

    private void notifyPayout(Player player, long amount) {
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.GREEN + "德州扑克结算到账：" + amount + "。 ");
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.1F);
        }
    }

    private void sendIfOnline(Player player, String message) {
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private boolean accepted(PlaceResult result) {
        return result != null && result.accepted() && result.wager() != null;
    }

    private boolean hasOutstandingWagers(MachineDataPoker machineData) {
        return machineData.hostWager != null || machineData.challengerWager != null
                || machineData.hostStakePaid || machineData.challengerStakePaid;
    }

    private void clearPendingSettlement(MachineDataPoker machineData) {
        machineData.pendingSettlementOperationId = null;
        machineData.pendingRefund = false;
        machineData.pendingHostPayout = -1L;
        machineData.pendingChallengerPayout = -1L;
        machineData.resolving = false;
        machineData.refreshInUse();
    }

    private void cancelTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private static String streetName(HeadsUpPokerRound.Street street) {
        return switch (street) {
            case PREFLOP -> "翻牌前";
            case FLOP -> "翻牌圈";
            case TURN -> "转牌圈";
            case RIVER -> "河牌圈";
            case SHOWDOWN -> "摊牌";
            case COMPLETE -> "结束";
        };
    }

    private static String seatName(HeadsUpPokerRound.Seat seat) {
        return seat == HeadsUpPokerRound.Seat.HOST ? "庄家" : "挑战者";
    }

    private static List<Integer> requireSize(List<Integer> slots, int expected, String name) {
        List<Integer> copy = List.copyOf(Objects.requireNonNull(slots, name));
        if (copy.size() != expected || new HashSet<>(copy).size() != expected) {
            throw new IllegalArgumentException(name + " must contain exactly " + expected + " unique slots");
        }
        return copy;
    }

    @Override
    public ItemStack getMachineItem() {
        return machineItem;
    }

    @Override
    public double[] getMachineEntityOffset() {
        return entityOffset.clone();
    }
}
