package me.arthed.smartgambling.games.blackjack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.NavigableMap;
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
import me.arthed.smartgambling.games.common.machine.Machine;
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

public class BlackJack implements Machine, ConfirmableWagerMachine {
    private static final int MAX_SETTLEMENT_RETRY_ATTEMPTS = 12;
    private static final long SETTLEMENT_RETRY_DELAY_TICKS = 100L;
    public final ItemStack machineItem;
    public final double[] entityOffset;
    public final double[] chair1Offset;
    public final double[] chair2Offset;
    private final Inventory baseInventory;
    private final InventoryAnimations animations;
    private final String inventoryTitle;
    private final String inventoryTitleStand;
    private final String inventoryTitleLost;
    private final String inventoryTitleWin;
    private final String inventoryTitleDraw;
    private final Button hitButton;
    private final Button standButton;
    private final ItemStack cardBack;
    private final List<Integer> cardSlots;
    private final List<Integer> opponentCardSlots;
    private final List<Integer> placeholderSlots;
    private final NavigableMap<Integer, PlayingCard> cards;
    private final int cardsTotalChance;

    private enum Reservation {
        HOST,
        CHALLENGER
    }

    private record PotSettlement(boolean winnerPaid, boolean complete) {
    }

    public BlackJack(
            ItemStack machineItem,
            double[] entityOffset,
            double[] char1Offset,
            double[] chair2Offset,
            Inventory baseInventory,
            InventoryAnimations animations,
            String inventoryTitle,
            String inventoryTitleStand,
            String inventoryTitleLost,
            String inventoryTitleWin,
            String inventoryTitleDraw,
            Button hitButton,
            Button standButton,
            ItemStack cardBack,
            List<Integer> cardSlots,
            List<Integer> opponentCardSlots,
            List<Integer> placeholderSlots,
            NavigableMap<Integer, PlayingCard> cards,
            int cardsTotalChance
    ) {
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.chair1Offset = char1Offset;
        this.chair2Offset = chair2Offset;
        this.baseInventory = baseInventory;
        this.animations = animations;
        this.inventoryTitle = inventoryTitle;
        this.inventoryTitleStand = inventoryTitleStand;
        this.inventoryTitleLost = inventoryTitleLost;
        this.inventoryTitleWin = inventoryTitleWin;
        this.inventoryTitleDraw = inventoryTitleDraw;
        this.hitButton = hitButton;
        this.standButton = standButton;
        this.cardBack = cardBack;
        this.cardSlots = cardSlots;
        this.opponentCardSlots = opponentCardSlots;
        this.placeholderSlots = placeholderSlots;
        this.cards = cards;
        this.cardsTotalChance = cardsTotalChance;
    }

    @Override
    public boolean canConfirmChallenger(Player player, MachineData machineData) {
        return machineData instanceof MachineDataBlackjack blackjack
                && blackjack.canCommitChallenger(player);
    }

    @Override
    public int requiredStake(MachineData machineData) {
        if (!(machineData instanceof MachineDataBlackjack blackjack)) {
            throw new IllegalArgumentException("Blackjack confirmation requires a blackjack table");
        }
        return blackjack.bet;
    }

    @Override
    public void open(Player player, OpenInterface openInterface) {
        if (!(openInterface instanceof OpenBlackjack openBlackjack)
                || !(openBlackjack.machineData instanceof MachineDataBlackjack machineData)) {
            return;
        }

        if (openInterface.betAmount == 0) {
            // Entity interaction did not historically apply BlockListener's
            // open-interface guard. Do it here as the final authority.
            if (SmartGambling.getInstance().openMachines.containsKey(player)) {
                return;
            }
            this.openBetSelection(player, openBlackjack, machineData);
            return;
        }

        // SubInventory returns here on the next tick after removing its own
        // mapping, but it does not itself close the visible selector GUI.
        player.closeInventory();

        if (openInterface.betAmount == -1) {
            machineData.releaseReservation(player);
            this.removeOpenMapping(player, machineData);
            return;
        }

        if (openInterface.betAmount <= 0) {
            machineData.releaseReservation(player);
            this.removeOpenMapping(player, machineData);
            return;
        }

        boolean hostReservation;
        boolean challengerReservation;
        synchronized (machineData) {
            hostReservation = machineData.canCommitHost(player);
            challengerReservation = machineData.canCommitChallenger(player);
        }

        if (hostReservation) {
            this.commitHost(player, openBlackjack, machineData);
        } else if (challengerReservation) {
            this.commitChallenger(player, openBlackjack, machineData);
        } else {
            this.rejectOccupied(player);
        }
    }

    private void openBetSelection(
            Player player,
            OpenBlackjack openBlackjack,
            MachineDataBlackjack machineData
    ) {
        Reservation reservation = null;
        synchronized (machineData) {
            if (machineData.player1 == null) {
                if (machineData.reserveHost(player)) {
                    reservation = Reservation.HOST;
                }
            } else if (machineData.reserveChallenger(player)) {
                reservation = Reservation.CHALLENGER;
            }
        }

        if (reservation == null) {
            this.rejectOccupied(player);
            return;
        }

        player.closeInventory();
        try {
            if (reservation == Reservation.HOST) {
                SmartGambling.getInstance().moneyInventory.open(player, openBlackjack);
            } else {
                SmartGambling.getInstance().confirmGameInventory.open(player, openBlackjack);
            }
        } catch (RuntimeException exception) {
            machineData.releaseReservation(player);
            this.removeOpenMapping(player, machineData);
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not open a blackjack bet interface for " + player.getName(),
                    exception
            );
        }
    }

    private void commitHost(
            Player player,
            OpenBlackjack openBlackjack,
            MachineDataBlackjack machineData
    ) {
        int amount = openBlackjack.betAmount;
        PlaceResult placement;
        boolean invalidExistingStake;
        synchronized (machineData) {
            if (!machineData.canCommitHost(player)) {
                this.rejectOccupied(player);
                return;
            }
            placement = this.placeWager(machineData, player, amount, "host");
            if (!this.acceptedPlacement(placement)) {
                machineData.releaseReservation(player);
                invalidExistingStake = false;
            } else {
                WagerHandle wager = placement.wager();
                invalidExistingStake = wager.stake().compareTo(Money.of(amount)) != 0;
                machineData.pendingHost = null;
                machineData.pendingHostWagerNonce = null;
                machineData.player1 = player;
                machineData.bet = amount;
                machineData.player1Wager = wager;
                machineData.player1StakePaid = true;
                machineData.refreshInUse();
            }
        }

        if (!this.acceptedPlacement(placement)) {
            this.notifyPlacementFailure(player, placement, "host");
            return;
        }
        if (invalidExistingStake) {
            this.logWagerMismatch(machineData, placement.wager(), Money.of(amount), "host");
            this.shutdownAndRefund(machineData);
            return;
        }

        openBlackjack.inventory = null;
        SmartGambling.getInstance().openMachines.put(player, openBlackjack);
        this.sendMoneyExtracted(player, amount);
        try {
            this.seatPlayer(machineData, player, 0);
            this.startWaitingMessage(machineData, player);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not initialize waiting state for blackjack table " + machineData.id,
                    exception
            );
            this.shutdownAndRefund(machineData);
        }
    }

    private void commitChallenger(
            Player player,
            OpenBlackjack openBlackjack,
            MachineDataBlackjack machineData
    ) {
        Player host;
        boolean hostUnavailable = false;
        PlaceResult placement = null;
        boolean invalidExistingStake = false;
        synchronized (machineData) {
            if (!machineData.canCommitChallenger(player)) {
                this.rejectOccupied(player);
                return;
            }
            if (openBlackjack.betAmount != machineData.bet
                    || machineData.bet <= 0) {
                machineData.releaseReservation(player);
                this.rejectOccupied(player);
                return;
            }

            host = machineData.player1;
            if (host == null || !host.isOnline()) {
                machineData.releaseReservation(player);
                hostUnavailable = true;
            } else {
                placement = this.placeWager(machineData, player, machineData.bet, "challenger");
                if (!this.acceptedPlacement(placement)) {
                    machineData.releaseReservation(player);
                } else {
                    WagerHandle wager = placement.wager();
                    invalidExistingStake = wager.stake().compareTo(Money.of(machineData.bet)) != 0;
                    machineData.pendingChallenger = null;
                    machineData.pendingChallengerWagerNonce = null;
                    machineData.player2 = player;
                    machineData.player2Wager = wager;
                    machineData.player2StakePaid = true;
                    machineData.refreshInUse();
                }
            }
        }

        if (hostUnavailable) {
            this.shutdownAndRefund(machineData);
            return;
        }
        if (!this.acceptedPlacement(placement)) {
            this.notifyPlacementFailure(player, placement, "challenger");
            return;
        }
        if (invalidExistingStake) {
            this.logWagerMismatch(machineData, placement.wager(), Money.of(machineData.bet), "challenger");
            this.shutdownAndRefund(machineData);
            return;
        }

        openBlackjack.inventory = null;
        SmartGambling.getInstance().openMachines.put(player, openBlackjack);
        this.sendMoneyExtracted(player, machineData.bet);
        this.seatPlayer(machineData, player, 1);
        try {
            this.startGame(host, player, machineData);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not start blackjack table " + machineData.id + "; refunding both stakes",
                    exception
            );
            this.shutdownAndRefund(machineData);
        }
    }

    private PlaceResult placeWager(
            MachineDataBlackjack machineData,
            Player player,
            int amount,
            String seat
    ) {
        try {
            java.util.UUID reservationNonce = "host".equals(seat)
                    ? machineData.pendingHostWagerNonce
                    : machineData.pendingChallengerWagerNonce;
            if (reservationNonce == null) {
                throw new IllegalStateException("Blackjack seat has no stable wager nonce: " + seat);
            }
            WagerKey key = createWagerKey(
                    machineData.id,
                    this.ensureRoundId(machineData),
                    player.getUniqueId(),
                    seat,
                    reservationNonce
            );
            return SmartGambling.getInstance().getEconomyService().place(key, Money.of(amount));
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not place the durable blackjack " + seat + " wager for table "
                            + machineData.id,
                    exception
            );
            return new PlaceResult(
                    PlaceResult.Status.STORAGE_FAILURE,
                    null,
                    null,
                    exception.getMessage()
            );
        }
    }

    private boolean acceptedPlacement(PlaceResult result) {
        return result != null && result.durable() && result.accepted() && result.wager() != null;
    }

    private void notifyPlacementFailure(Player player, PlaceResult result, String seat) {
        PlaceResult.Status status = result == null
                ? PlaceResult.Status.STORAGE_FAILURE
                : result.status();
        if (status == PlaceResult.Status.REJECTED) {
            DisplayUtils.displayActionBar(player, this.message("notEnoughMoneyActionBar"));
        } else if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.RED
                    + "下注未被接受。工作人员已收到通知，本局游戏未开始。");
        }
        String detail = result == null ? "null result" : result.detail();
        SmartGambling.getInstance().getLogger().warning(
                "Blackjack " + seat + " wager was not accepted for " + player.getName()
                        + ": status=" + status + ", detail=" + detail
        );
    }

    private void logWagerMismatch(
            MachineDataBlackjack machineData,
            WagerHandle wager,
            Money requested,
            String seat
    ) {
        SmartGambling.getInstance().getLogger().severe(
                "Durable blackjack " + seat + " wager " + wager.id()
                        + " has stake " + wager.stake().decimal()
                        + " but the table requested " + requested.decimal()
                        + "; the whole round will be refunded atomically"
        );
    }

    private TxResult lockWagers(
            MachineDataBlackjack machineData,
            WagerHandle first,
            WagerHandle second
    ) {
        try {
            return SmartGambling.getInstance().getEconomyService().lockAll(
                    roundOperationId(machineData.id, this.ensureRoundId(machineData), "lock"),
                    lockPayload(first, second)
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not lock durable blackjack wagers for table " + machineData.id,
                    exception
            );
            return new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
    }

    private TxResult resolveWagers(
            String operationId,
            List<EconomyService.Resolution> resolutions
    ) {
        try {
            return SmartGambling.getInstance().getEconomyService().resolveAll(
                    operationId,
                    resolutions
            );
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not submit durable blackjack settlement " + operationId,
                    exception
            );
            return new TxResult(TxResult.Status.STORAGE_FAILURE, null, exception.getMessage());
        }
    }

    private java.util.UUID ensureRoundId(MachineDataBlackjack machineData) {
        synchronized (machineData) {
            if (machineData.roundId == null) {
                machineData.roundId = java.util.UUID.randomUUID();
            }
            return machineData.roundId;
        }
    }

    static WagerKey createWagerKey(
            UUID machineId,
            UUID roundId,
            UUID playerId,
            String seat,
            UUID reservationNonce
    ) {
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reservationNonce, "reservationNonce");
        if (!Set.of("host", "challenger").contains(seat)) {
            throw new IllegalArgumentException("Unsupported blackjack seat: " + seat);
        }
        return new WagerKey(
                "blackjack",
                machineId.toString(),
                roundId.toString(),
                playerId,
                seat + ':' + reservationNonce
        );
    }

    static String roundOperationId(UUID machineId, UUID roundId, String phase) {
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(roundId, "roundId");
        if (!Set.of("lock", "settle").contains(phase)) {
            throw new IllegalArgumentException("Unsupported blackjack round operation: " + phase);
        }
        return "blackjack:" + machineId + ':' + roundId + ':' + phase;
    }

    static List<WagerHandle> lockPayload(WagerHandle first, WagerHandle second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.stake().compareTo(second.stake()) != 0) {
            throw new IllegalArgumentException("Blackjack wagers must have equal stakes");
        }
        return List.of(first, second);
    }

    private String settlementOperationId(MachineDataBlackjack machineData) {
        return roundOperationId(machineData.id, this.ensureRoundId(machineData), "settle");
    }

    private void logTxFailure(
            MachineDataBlackjack machineData,
            String phase,
            TxResult result
    ) {
        SmartGambling.getInstance().getLogger().severe(
                "Blackjack " + phase + " was not durable for table " + machineData.id
                        + ": status=" + (result == null ? "null" : result.status())
                        + ", detail=" + (result == null ? "null result" : result.detail())
                        + ". The table remains locked with its wager handles intact."
        );
    }

    @Override
    public void close(Player player, Inventory inventory) {
        OpenBlackjack openBlackjack = this.getOpenBlackjack(player);
        if (openBlackjack == null) {
            return;
        }
        MachineDataBlackjack machineData = (MachineDataBlackjack) openBlackjack.machineData;

        if (inventory == null) {
            this.forceClose(player);
            return;
        }

        // Opening a replacement inventory synchronously fires a close event for
        // the old one. It must not erase the newly installed interface.
        if (openBlackjack.inventory != null && !inventory.equals(openBlackjack.inventory)) {
            return;
        }

        boolean activeParticipant;
        boolean resolving;
        synchronized (machineData) {
            activeParticipant = machineData.startGame
                    && (machineData.player1 == player || machineData.player2 == player);
            resolving = machineData.resolving;
        }

        if (activeParticipant) {
            Inventory expectedInventory = openBlackjack.inventory;
            Bukkit.getScheduler().runTask(SmartGambling.getInstance(), () -> {
                synchronized (machineData) {
                    if (!machineData.startGame || machineData.resolving
                            || !machineData.hasParticipant(player)) {
                        return;
                    }
                }
                OpenBlackjack current = this.getOpenBlackjack(player);
                if (current != null && current.machineData == machineData
                        && current.inventory == expectedInventory && player.isOnline()) {
                    player.openInventory(expectedInventory);
                }
            });
            return;
        }

        if (resolving) {
            this.removeOpenMapping(player, machineData);
            return;
        }

        this.removeOpenMapping(player, machineData);
    }

    @Override
    public void forceClose(Player player) {
        MachineDataBlackjack machineData = this.findMachineData(player);
        if (machineData == null) {
            OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
            if (current != null && current.machineType == this) {
                SmartGambling.getInstance().openMachines.remove(player, current);
            }
            return;
        }

        boolean pending;
        boolean active;
        boolean resolving;
        boolean waitingHost;
        boolean committedButNotStarted;
        synchronized (machineData) {
            pending = machineData.pendingHost == player || machineData.pendingChallenger == player;
            active = machineData.startGame
                    && (machineData.player1 == player || machineData.player2 == player);
            resolving = machineData.resolving
                    && (machineData.player1 == player || machineData.player2 == player);
            waitingHost = machineData.player1 == player && machineData.player2 == null
                    && !machineData.startGame && !machineData.resolving;
            committedButNotStarted = !machineData.startGame && !machineData.resolving
                    && machineData.player2 != null
                    && (machineData.player1 == player || machineData.player2 == player);
        }

        if (pending) {
            machineData.releaseReservation(player);
            this.removeOpenMapping(player, machineData);
        } else if (active) {
            this.settleForfeit(machineData, player);
        } else if (resolving) {
            if (this.retryPendingSettlement(machineData)) {
                this.cleanupTable(machineData);
            } else {
                this.scheduleSettlementRetry(machineData);
            }
        } else if (waitingHost) {
            this.refundWaitingHost(machineData);
        } else if (committedButNotStarted) {
            this.shutdownAndRefund(machineData);
        } else {
            this.removeOpenMapping(player, machineData);
        }
    }

    public void startGame(Player player1, Player player2, MachineDataBlackjack machineData) {
        TxResult lockResult = null;
        boolean invalidWagers = false;
        synchronized (machineData) {
            if (machineData.startGame || machineData.resolving
                    || machineData.player1 != player1 || machineData.player2 != player2
                    || !machineData.player1StakePaid || !machineData.player2StakePaid) {
                return;
            }
            WagerHandle firstWager = machineData.player1Wager;
            WagerHandle secondWager = machineData.player2Wager;
            invalidWagers = machineData.bet <= 0
                    || firstWager == null || secondWager == null
                    || firstWager.stake().compareTo(secondWager.stake()) != 0;
            if (!invalidWagers) {
                Money tableStake = Money.of(machineData.bet);
                invalidWagers = firstWager.stake().compareTo(tableStake) != 0;
            }
            if (!invalidWagers) {
                lockResult = this.lockWagers(machineData, firstWager, secondWager);
            }
            if (invalidWagers || lockResult == null || !lockResult.durable()) {
                machineData.resolving = true;
            } else {
                machineData.wagersLocked = true;
                machineData.startGame = true;
                machineData.dealingInitialCards = true;
                machineData.player1stopped = false;
                machineData.player2stopped = false;
            }
            machineData.refreshInUse();
        }

        if (invalidWagers || lockResult == null || !lockResult.durable()) {
            if (invalidWagers) {
                SmartGambling.getInstance().getLogger().severe(
                        "Blackjack table " + machineData.id
                                + " has missing or unequal durable wagers; refunding the round"
                );
            } else {
                this.logTxFailure(machineData, "lock", lockResult);
            }
            this.shutdownAndRefund(machineData);
            return;
        }
        this.cancelWaitingMessage(machineData);

        Inventory playerInventory1 = Bukkit.createInventory(
                (InventoryHolder) player1,
                this.baseInventory.getSize(),
                this.inventoryTitle
        );
        Inventory playerInventory2 = Bukkit.createInventory(
                (InventoryHolder) player2,
                this.baseInventory.getSize(),
                this.inventoryTitle
        );
        playerInventory1.setContents(this.baseInventory.getContents());
        playerInventory2.setContents(this.baseInventory.getContents());

        synchronized (machineData) {
            machineData.player1Inventory = playerInventory1;
            machineData.player2Inventory = playerInventory2;
            machineData.player1Cards = new ArrayList<>();
            machineData.player2Cards = new ArrayList<>();
            machineData.player1Value = 0;
            machineData.player2Value = 0;
        }
        this.installOpenInventory(player1, machineData, playerInventory1);
        this.installOpenInventory(player2, machineData, playerInventory2);
        this.animations.startAnimations(playerInventory1);
        this.animations.startAnimations(playerInventory2);

        this.addCard(playerInventory1, machineData, true, true);
        this.addCard(playerInventory1, machineData, true, false);
        this.addCard(playerInventory2, machineData, false, true);
        this.addCard(playerInventory2, machineData, false, false);

        synchronized (machineData) {
            machineData.dealingInitialCards = false;
        }

        if (this.shouldAutoStand(machineData, true)) {
            this.standClick(machineData, true);
        }
        if (machineData.startGame && this.shouldAutoStand(machineData, false)) {
            this.standClick(machineData, false);
        }

        // standClick may have replaced an inventory or even completed the game.
        // Always open the currently registered inventory, never the stale local
        // variables created before the initial cards were dealt.
        synchronized (machineData) {
            if (!machineData.startGame || machineData.resolving) {
                return;
            }
            playerInventory1 = machineData.player1Inventory;
            playerInventory2 = machineData.player2Inventory;
        }
        this.openInventoryIfOnline(player1, playerInventory1);
        this.openInventoryIfOnline(player2, playerInventory2);
    }

    public void addCard(
            Inventory inventory,
            MachineDataBlackjack machineData,
            boolean firstPlayer,
            boolean showOpponent
    ) {
        boolean autoStand;
        synchronized (machineData) {
            if (!machineData.startGame || machineData.resolving) {
                return;
            }

            List<PlayingCard> hand = firstPlayer
                    ? machineData.player1Cards
                    : machineData.player2Cards;
            if (hand == null || hand.size() >= this.cardSlots.size()) {
                return;
            }

            Inventory ownInventory = firstPlayer
                    ? machineData.player1Inventory
                    : machineData.player2Inventory;
            Inventory opponentInventory = firstPlayer
                    ? machineData.player2Inventory
                    : machineData.player1Inventory;
            if (ownInventory == null) {
                ownInventory = inventory;
            }
            if (ownInventory == null) {
                return;
            }

            PlayingCard card = this.getRandomCard();
            hand.add(card);
            ItemStack displayedCard = card.getRandomItem().clone();
            this.setItemIfValid(ownInventory, this.cardSlots.get(hand.size() - 1), displayedCard);
            if (opponentInventory != null && hand.size() <= this.opponentCardSlots.size()) {
                ItemStack opponentCard = showOpponent ? displayedCard.clone() : this.cardBack.clone();
                this.setItemIfValid(
                        opponentInventory,
                        this.opponentCardSlots.get(hand.size() - 1),
                        opponentCard
                );
            }

            int value = this.calculateHandValue(hand);
            if (firstPlayer) {
                machineData.player1Value = value;
            } else {
                machineData.player2Value = value;
            }
            this.updateOwnStatus(ownInventory, machineData, firstPlayer);

            Player player = firstPlayer ? machineData.player1 : machineData.player2;
            if (player != null && player.isOnline()) {
                player.playSound(
                        player.getLocation(),
                        Sound.ITEM_ARMOR_EQUIP_GENERIC,
                        0.5F,
                        1.0F
                );
            }
            autoStand = !machineData.dealingInitialCards
                    && (value >= 21 || hand.size() == this.cardSlots.size());
        }

        if (autoStand) {
            this.standClick(machineData, firstPlayer);
        }
    }

    private PlayingCard getRandomCard() {
        if (this.cardsTotalChance <= 0 || this.cards.isEmpty()) {
            throw new IllegalStateException("Blackjack has no cards with a positive total chance");
        }
        int number = SmartGambling.getInstance().random.nextInt(this.cardsTotalChance);
        var entry = this.cards.higherEntry(number);
        if (entry == null) {
            throw new IllegalStateException("Blackjack card chance map does not cover roll " + number);
        }
        return entry.getValue();
    }

    @Override
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        OpenBlackjack openBlackjack = this.getOpenBlackjack(player);
        if (openBlackjack == null || openBlackjack.inventory != event.getInventory()) {
            return;
        }
        MachineDataBlackjack machineData = (MachineDataBlackjack) openBlackjack.machineData;
        boolean firstPlayer;
        synchronized (machineData) {
            if (!machineData.startGame || machineData.resolving
                    || (machineData.player1 != player && machineData.player2 != player)) {
                return;
            }
            firstPlayer = machineData.player1 == player;
            if (firstPlayer ? machineData.player1stopped : machineData.player2stopped) {
                return;
            }
        }

        if (this.hitButton.isClicked(event.getSlot())) {
            this.addCard(event.getInventory(), machineData, firstPlayer, false);
        } else if (this.standButton.isClicked(event.getSlot())) {
            this.standClick(machineData, firstPlayer);
        }
    }

    public void standClick(MachineDataBlackjack machineData, boolean firstPlayer) {
        Player player;
        Inventory oldInventory;
        synchronized (machineData) {
            if (!machineData.startGame || machineData.resolving) {
                return;
            }
            if (firstPlayer) {
                if (machineData.player1stopped) {
                    return;
                }
                machineData.player1stopped = true;
                player = machineData.player1;
                oldInventory = machineData.player1Inventory;
            } else {
                if (machineData.player2stopped) {
                    return;
                }
                machineData.player2stopped = true;
                player = machineData.player2;
                oldInventory = machineData.player2Inventory;
            }
        }

        if (player == null || oldInventory == null) {
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0F, 1.0F);
        this.stopAnimations(oldInventory);

        Inventory standInventory = Bukkit.createInventory(
                (InventoryHolder) player,
                this.baseInventory.getSize(),
                this.inventoryTitleStand
        );
        standInventory.setContents(oldInventory.getContents());
        this.animations.startAnimations(standInventory);
        synchronized (machineData) {
            if (firstPlayer) {
                machineData.player1Inventory = standInventory;
            } else {
                machineData.player2Inventory = standInventory;
            }
        }
        this.installOpenInventory(player, machineData, standInventory);
        this.openInventoryIfOnline(player, standInventory);

        Inventory opponentInventory;
        synchronized (machineData) {
            opponentInventory = firstPlayer
                    ? machineData.player2Inventory
                    : machineData.player1Inventory;
        }
        this.markOpponentStopped(opponentInventory);

        boolean bothStopped;
        synchronized (machineData) {
            bothStopped = machineData.player1stopped && machineData.player2stopped;
        }
        if (bothStopped) {
            this.finishGame(machineData);
        }
    }

    public void finishGame(MachineDataBlackjack machineData) {
        Player player1;
        Player player2;
        Inventory oldInventory1;
        Inventory oldInventory2;
        int value1;
        int value2;
        synchronized (machineData) {
            if (!machineData.startGame || machineData.resolving) {
                return;
            }
            machineData.startGame = false;
            machineData.resolving = true;
            machineData.dealingInitialCards = false;
            machineData.refreshInUse();
            player1 = machineData.player1;
            player2 = machineData.player2;
            oldInventory1 = machineData.player1Inventory;
            oldInventory2 = machineData.player2Inventory;
            value1 = machineData.player1Value;
            value2 = machineData.player2Value;
        }

        if (player1 == null || player2 == null) {
            this.shutdownAndRefund(machineData);
            return;
        }

        boolean bothBust = value1 > 21 && value2 > 21;
        boolean draw = bothBust || value1 == value2;
        Player winner = null;
        Player loser = null;
        String title1;
        String title2;
        boolean settlementComplete;

        if (draw) {
            title1 = this.inventoryTitleDraw;
            title2 = this.inventoryTitleDraw;
            String message = bothBust
                    ? this.message("drawBlackJackOver")
                    : this.formatMessage("drawBlackJack", value1);
            this.sendIfOnline(player1, message);
            this.sendIfOnline(player2, message);
            // A draw returns both original stakes, including the both-bust case.
            settlementComplete = this.refundAllOutstandingStakes(
                    machineData,
                    "blackjack draw refund"
            );
        } else {
            if (value1 > 21 || (value2 <= 21 && value2 > value1)) {
                winner = player2;
                loser = player1;
                title1 = this.inventoryTitleLost;
                title2 = this.inventoryTitleWin;
            } else {
                winner = player1;
                loser = player2;
                title1 = this.inventoryTitleWin;
                title2 = this.inventoryTitleLost;
            }

            int winnerValue = winner == player1 ? value1 : value2;
            int loserValue = loser == player1 ? value1 : value2;
            double amountWon = machineData.bet * 2.0D;
            PotSettlement settlement = this.payWinnerPot(
                    machineData,
                    winner,
                    amountWon,
                    "blackjack winner payout"
            );
            settlementComplete = settlement.complete();

            this.sendIfOnline(
                    winner,
                    this.formatMessage("wonBlackJack", winnerValue, loserValue)
            );
            this.sendIfOnline(
                    loser,
                    this.formatMessage("lostBlackJack", loserValue, winnerValue)
            );
            if (settlement.winnerPaid()) {
                this.notifyWinnerPayment(winner, amountWon);
            }
            this.playWinSound(winner);
        }

        this.stopAnimations(oldInventory1);
        this.stopAnimations(oldInventory2);
        Inventory result1 = this.buildResultInventory(
                player1,
                title1,
                oldInventory1,
                oldInventory2
        );
        Inventory result2 = this.buildResultInventory(
                player2,
                title2,
                oldInventory2,
                oldInventory1
        );

        synchronized (machineData) {
            machineData.player1Inventory = result1;
            machineData.player2Inventory = result2;
        }
        this.animations.startAnimations(result1);
        this.animations.startAnimations(result2);
        this.installOpenInventory(player1, machineData, result1);
        this.installOpenInventory(player2, machineData, result2);
        this.openInventoryIfOnline(player1, result1);
        this.openInventoryIfOnline(player2, result2);

        if (settlementComplete) {
            BukkitTask cleanupTask = Bukkit.getScheduler().runTaskLater(
                    SmartGambling.getInstance(),
                    () -> this.cleanupTable(machineData),
                    80L
            );
            synchronized (machineData) {
                if (machineData.resolving && !this.hasOutstandingStakes(machineData)) {
                    machineData.cleanupTask = cleanupTask;
                } else {
                    cleanupTask.cancel();
                    this.scheduleSettlementRetry(machineData);
                }
            }
        } else {
            this.scheduleSettlementRetry(machineData);
        }
    }

    /**
     * Refunds every live blackjack table owned by this machine type. Call this
     * before replacing configuration objects or disabling the plugin.
     */
    public void shutdownAndRefund() {
        Set<MachineDataBlackjack> tables = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MachineData machineData : new ArrayList<>(SmartGambling.getInstance().uuidMachines.values())) {
            if (machineData instanceof MachineDataBlackjack blackjack
                    && blackjack.machineType == this) {
                tables.add(blackjack);
            }
        }
        for (MachineData machineData : new ArrayList<>(SmartGambling.getInstance().machinesToAdd)) {
            if (machineData instanceof MachineDataBlackjack blackjack
                    && blackjack.machineType == this) {
                tables.add(blackjack);
            }
        }
        for (OpenInterface openInterface
                : new ArrayList<>(SmartGambling.getInstance().openMachines.values())) {
            if (openInterface instanceof OpenBlackjack openBlackjack
                    && openBlackjack.machineData instanceof MachineDataBlackjack blackjack
                    && blackjack.machineType == this) {
                tables.add(blackjack);
            }
        }
        for (MachineDataBlackjack table : tables) {
            this.shutdownAndRefund(table);
        }
    }

    /** Refunds both stakes (if present), cancels tasks, and resets one table. */
    public void shutdownAndRefund(MachineDataBlackjack machineData) {
        synchronized (machineData) {
            machineData.startGame = false;
            machineData.resolving = true;
            machineData.dealingInitialCards = false;
            if (machineData.settlementRetryTask == null
                    && machineData.settlementRetryAttempts >= MAX_SETTLEMENT_RETRY_ATTEMPTS) {
                machineData.settlementRetryAttempts = 0;
            }
            machineData.refreshInUse();
        }
        this.cancelWaitingMessage(machineData);
        if (this.refundAllOutstandingStakes(machineData, "blackjack shutdown refund")) {
            this.cleanupTable(machineData);
        } else {
            this.scheduleSettlementRetry(machineData);
        }
    }

    private void refundWaitingHost(MachineDataBlackjack machineData) {
        synchronized (machineData) {
            if (machineData.player1 == null || machineData.player2 != null
                    || machineData.startGame || machineData.resolving) {
                return;
            }
            machineData.resolving = true;
            machineData.refreshInUse();
        }
        this.cancelWaitingMessage(machineData);
        if (this.refundAllOutstandingStakes(machineData, "blackjack waiting-host refund")) {
            this.cleanupTable(machineData);
        } else {
            this.scheduleSettlementRetry(machineData);
        }
    }

    private void settleForfeit(MachineDataBlackjack machineData, Player forfeitingPlayer) {
        Player winner;
        Player loser;
        int winnerValue;
        int loserValue;
        synchronized (machineData) {
            if (!machineData.startGame || machineData.resolving
                    || (machineData.player1 != forfeitingPlayer
                    && machineData.player2 != forfeitingPlayer)) {
                return;
            }
            loser = forfeitingPlayer;
            winner = machineData.player1 == forfeitingPlayer
                    ? machineData.player2
                    : machineData.player1;
            winnerValue = winner == machineData.player1
                    ? machineData.player1Value
                    : machineData.player2Value;
            loserValue = loser == machineData.player1
                    ? machineData.player1Value
                    : machineData.player2Value;
            machineData.startGame = false;
            machineData.resolving = true;
            machineData.dealingInitialCards = false;
            machineData.refreshInUse();
        }

        if (winner != null) {
            double amountWon = machineData.bet * 2.0D;
            PotSettlement settlement = this.payWinnerPot(
                    machineData,
                    winner,
                    amountWon,
                    "blackjack opponent-forfeit payout"
            );
            this.sendIfOnline(
                    winner,
                    this.formatMessage("wonBlackJack", winnerValue, loserValue)
            );
            this.sendIfOnline(
                    loser,
                    this.formatMessage("lostBlackJack", loserValue, winnerValue)
            );
            if (settlement.winnerPaid()) {
                this.notifyWinnerPayment(winner, amountWon);
            }
            this.playWinSound(winner);
            if (settlement.complete()) {
                this.cleanupTable(machineData);
            } else {
                this.scheduleSettlementRetry(machineData);
            }
        } else {
            if (this.refundAllOutstandingStakes(
                    machineData,
                    "blackjack orphaned-game refund"
            )) {
                this.cleanupTable(machineData);
            } else {
                this.scheduleSettlementRetry(machineData);
            }
        }
    }

    private PotSettlement payWinnerPot(
            MachineDataBlackjack machineData,
            Player winner,
            double amount,
            String reason
    ) {
        MachineDataBlackjack.SettlementKind kind;
        synchronized (machineData) {
            if (winner == machineData.player1) {
                kind = MachineDataBlackjack.SettlementKind.PLAYER1_WINS;
            } else if (winner == machineData.player2) {
                kind = MachineDataBlackjack.SettlementKind.PLAYER2_WINS;
            } else {
                SmartGambling.getInstance().getLogger().severe(
                        "Could not identify the winner's durable blackjack wager for table "
                                + machineData.id + " (" + reason + ")"
                );
                return new PotSettlement(false, false);
            }
        }
        TxResult result = this.prepareAndExecuteSettlement(machineData, kind, reason);
        boolean complete = result != null && result.durable();
        boolean paidImmediately = complete && result.status() == TxResult.Status.DURABLE;
        return new PotSettlement(paidImmediately, complete);
    }

    private boolean refundAllOutstandingStakes(
            MachineDataBlackjack machineData,
            String reason
    ) {
        TxResult result = this.prepareAndExecuteSettlement(
                machineData,
                MachineDataBlackjack.SettlementKind.REFUND,
                reason
        );
        return result != null && result.durable() && !this.hasOutstandingStakes(machineData);
    }

    private boolean retryPendingSettlement(MachineDataBlackjack machineData) {
        MachineDataBlackjack.SettlementKind kind;
        synchronized (machineData) {
            kind = machineData.pendingSettlement;
        }
        if (kind == null) {
            kind = MachineDataBlackjack.SettlementKind.REFUND;
        }
        TxResult result = this.prepareAndExecuteSettlement(
                machineData,
                kind,
                "blackjack durable settlement retry"
        );
        return result != null && result.durable() && !this.hasOutstandingStakes(machineData);
    }

    private TxResult prepareAndExecuteSettlement(
            MachineDataBlackjack machineData,
            MachineDataBlackjack.SettlementKind requestedKind,
            String reason
    ) {
        synchronized (machineData) {
            if (!this.hasOutstandingStakes(machineData)) {
                machineData.pendingSettlement = null;
                machineData.pendingSettlementOperationId = null;
                machineData.wagersLocked = false;
                machineData.refreshInUse();
                return new TxResult(TxResult.Status.ALREADY_APPLIED, null, "no live wagers");
            }
            if (machineData.pendingSettlement == null) {
                machineData.pendingSettlement = requestedKind;
                machineData.pendingSettlementOperationId = this.settlementOperationId(machineData);
            } else if (machineData.pendingSettlement != requestedKind) {
                // Once a result has been submitted, retries must keep both its
                // operation id and payload. A shutdown racing a failed response
                // must not submit a contradictory second disposition.
                SmartGambling.getInstance().getLogger().warning(
                        "Blackjack table " + machineData.id + " already has pending settlement "
                                + machineData.pendingSettlement + "; preserving it instead of "
                                + requestedKind + " (" + reason + ")"
                );
            }
        }
        return this.executePendingSettlement(machineData, reason);
    }

    private TxResult executePendingSettlement(
            MachineDataBlackjack machineData,
            String reason
    ) {
        TxResult result;
        Player refundPlayer1 = null;
        Player refundPlayer2 = null;
        int refundAmount = 0;
        synchronized (machineData) {
            MachineDataBlackjack.SettlementKind kind = machineData.pendingSettlement;
            String operationId = machineData.pendingSettlementOperationId;
            WagerHandle first = machineData.player1Wager;
            WagerHandle second = machineData.player2Wager;

            if (first == null && second == null
                    && !machineData.player1StakePaid && !machineData.player2StakePaid) {
                result = new TxResult(TxResult.Status.ALREADY_APPLIED, null, "no live wagers");
            } else if (kind == null || operationId == null) {
                result = new TxResult(
                        TxResult.Status.CONFLICT,
                        null,
                        "outstanding wagers have no pending settlement identity"
                );
            } else if ((machineData.player1StakePaid && first == null)
                    || (machineData.player2StakePaid && second == null)) {
                result = new TxResult(
                        TxResult.Status.CONFLICT,
                        null,
                        "a paid stake is missing its durable wager handle"
                );
            } else if (kind != MachineDataBlackjack.SettlementKind.REFUND
                    && (first == null || second == null)) {
                result = new TxResult(
                        TxResult.Status.CONFLICT,
                        null,
                        "a winner settlement requires both durable wager handles"
                );
            } else {
                List<EconomyService.Resolution> resolutions = this.buildResolutions(
                        kind,
                        first,
                        second
                );
                if (resolutions.isEmpty()) {
                    result = new TxResult(TxResult.Status.ALREADY_APPLIED, null, "no live wagers");
                } else {
                    result = this.resolveWagers(operationId, resolutions);
                }

                if (result.durable()) {
                    if (kind == MachineDataBlackjack.SettlementKind.REFUND
                            && result.status() == TxResult.Status.DURABLE) {
                        refundPlayer1 = first == null ? null : machineData.player1;
                        refundPlayer2 = second == null ? null : machineData.player2;
                        refundAmount = machineData.bet;
                    }
                    machineData.player1Wager = null;
                    machineData.player2Wager = null;
                    machineData.player1StakePaid = false;
                    machineData.player2StakePaid = false;
                    machineData.wagersLocked = false;
                    machineData.pendingSettlement = null;
                    machineData.pendingSettlementOperationId = null;
                    machineData.settlementRetryAttempts = 0;
                    machineData.refreshInUse();
                }
            }
        }

        if (!result.durable()) {
            this.logTxFailure(machineData, reason, result);
        } else if (refundAmount > 0) {
            this.sendMoneyReceived(refundPlayer1, refundAmount);
            this.sendMoneyReceived(refundPlayer2, refundAmount);
        }
        return result;
    }

    static List<EconomyService.Resolution> buildResolutions(
            MachineDataBlackjack.SettlementKind kind,
            WagerHandle first,
            WagerHandle second
    ) {
        Objects.requireNonNull(kind, "kind");
        List<EconomyService.Resolution> resolutions = new ArrayList<>(2);
        if (kind == MachineDataBlackjack.SettlementKind.REFUND) {
            if (first != null) {
                resolutions.add(new EconomyService.Resolution(first, WagerResolution.refund()));
            }
            if (second != null) {
                resolutions.add(new EconomyService.Resolution(second, WagerResolution.refund()));
            }
            return resolutions;
        }
        if (first == null || second == null) {
            return List.of();
        }

        boolean firstWins = kind == MachineDataBlackjack.SettlementKind.PLAYER1_WINS;
        resolutions.add(new EconomyService.Resolution(
                first,
                firstWins
                        ? WagerResolution.payout(first.stake().add(first.stake()))
                        : WagerResolution.loss()
        ));
        resolutions.add(new EconomyService.Resolution(
                second,
                firstWins
                        ? WagerResolution.loss()
                        : WagerResolution.payout(second.stake().add(second.stake()))
        ));
        return resolutions;
    }

    private boolean hasOutstandingStakes(MachineDataBlackjack machineData) {
        synchronized (machineData) {
            return machineData.player1Wager != null || machineData.player2Wager != null
                    || machineData.player1StakePaid || machineData.player2StakePaid;
        }
    }

    private void scheduleSettlementRetry(MachineDataBlackjack machineData) {
        boolean exhausted = false;
        boolean alreadySettled = false;
        RuntimeException schedulingFailure = null;
        synchronized (machineData) {
            if (!this.hasOutstandingStakes(machineData)) {
                alreadySettled = true;
            } else if (machineData.settlementRetryTask != null
                    && !machineData.settlementRetryTask.isCancelled()) {
                return;
            } else if (machineData.settlementRetryAttempts
                    >= MAX_SETTLEMENT_RETRY_ATTEMPTS) {
                exhausted = true;
            } else {
                machineData.resolving = true;
                machineData.refreshInUse();
                try {
                    machineData.settlementRetryTask = Bukkit.getScheduler().runTaskLater(
                            SmartGambling.getInstance(),
                            () -> this.runSettlementRetry(machineData),
                            SETTLEMENT_RETRY_DELAY_TICKS
                    );
                } catch (RuntimeException exception) {
                    schedulingFailure = exception;
                }
            }
        }

        if (alreadySettled) {
            this.cleanupTable(machineData);
        } else if (exhausted) {
            SmartGambling.getInstance().getLogger().severe(
                    "Blackjack settlement for table " + machineData.id
                            + " still failed after " + MAX_SETTLEMENT_RETRY_ATTEMPTS
                            + " retries. The table remains locked with its stake ledger intact; "
                            + "a later shutdown/reload will retry it."
            );
        } else if (schedulingFailure != null) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not schedule a blackjack settlement retry for table "
                            + machineData.id + ". Its stake ledger remains locked and intact.",
                    schedulingFailure
            );
        }
    }

    private void runSettlementRetry(MachineDataBlackjack machineData) {
        boolean alreadySettled;
        synchronized (machineData) {
            machineData.settlementRetryTask = null;
            alreadySettled = !this.hasOutstandingStakes(machineData);
            if (!alreadySettled) {
                ++machineData.settlementRetryAttempts;
            }
        }
        if (alreadySettled) {
            this.cleanupTable(machineData);
            return;
        }

        if (this.retryPendingSettlement(machineData)) {
            this.cleanupTable(machineData);
        } else {
            this.scheduleSettlementRetry(machineData);
        }
    }

    private void cleanupTable(MachineDataBlackjack machineData) {
        if (this.hasOutstandingStakes(machineData)) {
            synchronized (machineData) {
                machineData.resolving = true;
                machineData.refreshInUse();
            }
            this.scheduleSettlementRetry(machineData);
            return;
        }

        Player player1;
        Player player2;
        Player pendingHost;
        Player pendingChallenger;
        Inventory inventory1;
        Inventory inventory2;
        BukkitTask waitingTask;
        BukkitTask cleanupTask;
        BukkitTask settlementRetryTask;
        Entity seat1;
        Entity seat2;
        boolean rotateRound;

        synchronized (machineData) {
            rotateRound = machineData.player1 != null || machineData.player2 != null
                    || machineData.pendingHost != null || machineData.pendingChallenger != null
                    || machineData.bet > 0 || machineData.startGame || machineData.resolving
                    || machineData.pendingSettlement != null || machineData.wagersLocked;
            player1 = machineData.player1;
            player2 = machineData.player2;
            pendingHost = machineData.pendingHost;
            pendingChallenger = machineData.pendingChallenger;
            inventory1 = machineData.player1Inventory;
            inventory2 = machineData.player2Inventory;
            waitingTask = machineData.waitingMessageTask;
            cleanupTask = machineData.cleanupTask;
            settlementRetryTask = machineData.settlementRetryTask;
            seat1 = this.getEntity(machineData, 0);
            seat2 = this.getEntity(machineData, 1);

            machineData.waitingMessageTask = null;
            machineData.cleanupTask = null;
            machineData.settlementRetryTask = null;
            machineData.settlementRetryAttempts = 0;
            machineData.pendingHost = null;
            machineData.pendingChallenger = null;
            machineData.pendingHostWagerNonce = null;
            machineData.pendingChallengerWagerNonce = null;
            machineData.player1 = null;
            machineData.player2 = null;
            machineData.bet = 0;
            machineData.player1Wager = null;
            machineData.player2Wager = null;
            machineData.player1StakePaid = false;
            machineData.player2StakePaid = false;
            machineData.wagersLocked = false;
            machineData.pendingSettlement = null;
            machineData.pendingSettlementOperationId = null;
            machineData.startGame = false;
            machineData.resolving = false;
            machineData.dealingInitialCards = false;
            machineData.player1Inventory = null;
            machineData.player2Inventory = null;
            machineData.player1Cards = null;
            machineData.player2Cards = null;
            machineData.player1stopped = false;
            machineData.player2stopped = false;
            machineData.player1Value = 0;
            machineData.player2Value = 0;
            if (rotateRound) {
                machineData.roundId = java.util.UUID.randomUUID();
            }
            machineData.refreshInUse();
        }

        this.cancelTask(waitingTask);
        this.cancelTask(cleanupTask);
        this.cancelTask(settlementRetryTask);
        this.stopAnimations(inventory1);
        this.stopAnimations(inventory2);
        this.cleanupPlayer(player1, machineData, inventory1, seat1, false);
        this.cleanupPlayer(player2, machineData, inventory2, seat2, false);
        this.cleanupPlayer(pendingHost, machineData, null, null, true);
        this.cleanupPlayer(pendingChallenger, machineData, null, null, true);
    }

    private void cleanupPlayer(
            Player player,
            MachineDataBlackjack machineData,
            Inventory expectedInventory,
            Entity expectedSeat,
            boolean pendingInterface
    ) {
        if (player == null) {
            return;
        }

        OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
        boolean ownsBlackjack = current instanceof OpenBlackjack openBlackjack
                && openBlackjack.machineData == machineData;
        boolean ownsPendingInterface = pendingInterface && current != null
                && (current.machineType == SmartGambling.getInstance().moneyInventory
                || current.machineType == SmartGambling.getInstance().confirmGameInventory);

        if (ownsPendingInterface) {
            // The table state was reset first, so returning through SubInventory
            // cannot refund or settle a second time.
            current.machineType.forceClose(player);
        }
        if (ownsBlackjack) {
            SmartGambling.getInstance().openMachines.remove(player);
        }
        this.removeOpenMapping(player, machineData);

        boolean seatedHere = expectedSeat != null && player.getVehicle() == expectedSeat;
        if (seatedHere) {
            expectedSeat.removePassenger(player);
        }

        if (player.isOnline()) {
            Inventory topInventory = player.getOpenInventory().getTopInventory();
            boolean ownsCurrentInventory = (ownsBlackjack || ownsPendingInterface)
                    && current != null && current.inventory == topInventory;
            boolean shouldClose = ownsCurrentInventory
                    || (expectedInventory != null && expectedInventory == topInventory);
            if (shouldClose) {
                player.closeInventory();
            }
            if (seatedHere && shouldClose) {
                player.teleport(player.getLocation().add(1.0D, 0.0D, 0.0D));
            }
        }
    }

    private Inventory buildResultInventory(
            Player owner,
            String title,
            Inventory ownOldInventory,
            Inventory opponentOldInventory
    ) {
        Inventory result = Bukkit.createInventory(
                (InventoryHolder) owner,
                this.baseInventory.getSize(),
                title
        );
        result.setContents(this.baseInventory.getContents());

        int cardCount = Math.min(this.cardSlots.size(), this.opponentCardSlots.size());
        for (int i = 0; i < cardCount; ++i) {
            ItemStack ownCard = this.getItemIfValid(ownOldInventory, this.cardSlots.get(i));
            ItemStack opponentCard = this.getItemIfValid(opponentOldInventory, this.cardSlots.get(i));
            this.setItemIfValid(result, this.cardSlots.get(i), this.cloneOrNull(ownCard));
            this.setItemIfValid(result, this.opponentCardSlots.get(i), this.cloneOrNull(opponentCard));
        }
        for (int slot : this.placeholderSlots) {
            ItemStack placeholder = this.getItemIfValid(ownOldInventory, slot);
            this.setItemIfValid(result, slot, this.cloneOrNull(placeholder));
        }
        return result;
    }

    private void updateOwnStatus(
            Inventory inventory,
            MachineDataBlackjack machineData,
            boolean firstPlayer
    ) {
        String opponentStatus = firstPlayer
                ? (machineData.player2stopped
                ? this.message("opponentStopped")
                : this.message("opponentPlaying"))
                : (machineData.player1stopped
                ? this.message("opponentStopped")
                : this.message("opponentPlaying"));
        String amount = Integer.toString(
                firstPlayer ? machineData.player1Value : machineData.player2Value
        );

        for (int slot : this.placeholderSlots) {
            ItemStack placeholder = this.getItemIfValid(inventory, slot);
            ItemStack basePlaceholder = this.getItemIfValid(this.baseInventory, slot);
            if (placeholder == null || basePlaceholder == null
                    || !placeholder.hasItemMeta() || !basePlaceholder.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = placeholder.getItemMeta();
            ItemMeta baseMeta = basePlaceholder.getItemMeta();
            if (baseMeta.hasDisplayName()) {
                meta.setDisplayName(
                        baseMeta.getDisplayName()
                                .replace("%amount%", amount)
                                .replace("%opponent_status%", opponentStatus)
                );
            }
            if (baseMeta.hasLore() && baseMeta.getLore() != null) {
                List<String> lore = new ArrayList<>();
                for (String line : baseMeta.getLore()) {
                    lore.add(
                            line.replace("%amount%", amount)
                                    .replace("%opponent_status%", opponentStatus)
                    );
                }
                meta.setLore(lore);
            }
            placeholder.setItemMeta(meta);
        }
    }

    private void markOpponentStopped(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        String playing = this.message("opponentPlaying");
        String stopped = this.message("opponentStopped");
        for (int slot : this.placeholderSlots) {
            ItemStack placeholder = this.getItemIfValid(inventory, slot);
            if (placeholder == null || !placeholder.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = placeholder.getItemMeta();
            if (meta.hasDisplayName()) {
                meta.setDisplayName(meta.getDisplayName().replace(playing, stopped));
            }
            if (meta.hasLore() && meta.getLore() != null) {
                List<String> lore = new ArrayList<>();
                for (String line : meta.getLore()) {
                    lore.add(line.replace(playing, stopped));
                }
                meta.setLore(lore);
            }
            placeholder.setItemMeta(meta);
        }
    }

    private int calculateHandValue(List<PlayingCard> hand) {
        int value = 0;
        int softAces = 0;
        for (PlayingCard card : hand) {
            value += card.value();
            if (card.value() == 11) {
                ++softAces;
            }
        }
        while (value > 21 && softAces-- > 0) {
            value -= 10;
        }
        return value;
    }

    private boolean shouldAutoStand(MachineDataBlackjack machineData, boolean firstPlayer) {
        synchronized (machineData) {
            List<PlayingCard> hand = firstPlayer
                    ? machineData.player1Cards
                    : machineData.player2Cards;
            int value = firstPlayer ? machineData.player1Value : machineData.player2Value;
            return hand != null && (value >= 21 || hand.size() >= this.cardSlots.size());
        }
    }

    private void startWaitingMessage(MachineDataBlackjack machineData, Player player) {
        this.cancelWaitingMessage(machineData);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                SmartGambling.getInstance(),
                () -> {
                    synchronized (machineData) {
                        if (machineData.player1 != player || machineData.player2 != null
                                || machineData.startGame || machineData.resolving) {
                            return;
                        }
                    }
                    if (player.isOnline()) {
                        DisplayUtils.displayActionBar(player, this.message("waitingForOpponent"));
                    }
                },
                20L,
                20L
        );
        synchronized (machineData) {
            machineData.waitingMessageTask = task;
        }
    }

    private void cancelWaitingMessage(MachineDataBlackjack machineData) {
        BukkitTask task;
        synchronized (machineData) {
            task = machineData.waitingMessageTask;
            machineData.waitingMessageTask = null;
        }
        this.cancelTask(task);
    }

    private void seatPlayer(
            MachineDataBlackjack machineData,
            Player player,
            int entityIndex
    ) {
        Entity seat = this.getEntity(machineData, entityIndex);
        if (seat != null && seat.isValid()) {
            seat.addPassenger(player);
            player.setRotation(seat.getLocation().getYaw(), 0.0F);
        }
    }

    private Entity getEntity(MachineDataBlackjack machineData, int index) {
        return machineData.entities != null && index >= 0 && index < machineData.entities.length
                ? machineData.entities[index]
                : null;
    }

    private void installOpenInventory(
            Player player,
            MachineDataBlackjack machineData,
            Inventory inventory
    ) {
        OpenBlackjack openBlackjack = this.getOpenBlackjack(player);
        if (openBlackjack == null || openBlackjack.machineData != machineData) {
            openBlackjack = new OpenBlackjack(this, machineData);
            openBlackjack.betAmount = machineData.bet;
            SmartGambling.getInstance().openMachines.put(player, openBlackjack);
        }
        openBlackjack.inventory = inventory;
    }

    private void openInventoryIfOnline(Player player, Inventory inventory) {
        if (player != null && player.isOnline() && inventory != null) {
            player.openInventory(inventory);
        }
    }

    private OpenBlackjack getOpenBlackjack(Player player) {
        OpenInterface openInterface = SmartGambling.getInstance().openMachines.get(player);
        if (openInterface instanceof OpenBlackjack openBlackjack
                && openBlackjack.machineType == this
                && openBlackjack.machineData instanceof MachineDataBlackjack) {
            return openBlackjack;
        }
        return null;
    }

    private MachineDataBlackjack findMachineData(Player player) {
        OpenBlackjack openBlackjack = this.getOpenBlackjack(player);
        if (openBlackjack != null) {
            return (MachineDataBlackjack) openBlackjack.machineData;
        }
        for (MachineData machineData : new ArrayList<>(SmartGambling.getInstance().uuidMachines.values())) {
            if (machineData instanceof MachineDataBlackjack blackjack
                    && blackjack.machineType == this && blackjack.hasParticipant(player)) {
                return blackjack;
            }
        }
        return null;
    }

    private void removeOpenMapping(Player player, MachineDataBlackjack machineData) {
        OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
        if (current instanceof OpenBlackjack openBlackjack
                && openBlackjack.machineData == machineData) {
            SmartGambling.getInstance().openMachines.remove(player);
        }
    }

    private void rejectOccupied(Player player) {
        DisplayUtils.displayActionBar(player, this.message("blackjackAlreadyInUse"));
    }

    private void sendMoneyExtracted(Player player, int amount) {
        if (player == null) {
            return;
        }
        try {
            double balance = SmartGambling.getEconomy().getBalance(player);
            this.sendIfOnline(
                    player,
                    this.formatMessage("moneyExtracted", amount, balance)
            );
        } catch (RuntimeException exception) {
            this.logBalanceNotificationFailure(player, exception);
        }
    }

    private void sendMoneyReceived(Player player, int amount) {
        if (player == null) {
            return;
        }
        try {
            double balance = SmartGambling.getEconomy().getBalance(player);
            this.sendIfOnline(
                    player,
                    this.formatMessage("moneyReceived", amount, balance)
            );
        } catch (RuntimeException exception) {
            this.logBalanceNotificationFailure(player, exception);
        }
    }

    private void notifyWinnerPayment(Player winner, double amount) {
        if (winner == null) {
            return;
        }
        try {
            double balance = SmartGambling.getEconomy().getBalance(winner);
            DisplayUtils.displayActionBar(
                    winner,
                    this.formatMessage("wonMoneyActionBar", amount, balance)
            );
            this.sendIfOnline(
                    winner,
                    this.formatMessage("wonMoney", amount, balance)
            );
        } catch (RuntimeException exception) {
            this.logBalanceNotificationFailure(winner, exception);
        }
    }

    private void logBalanceNotificationFailure(Player player, RuntimeException exception) {
        SmartGambling.getInstance().getLogger().log(
                Level.WARNING,
                "Could not read or display the post-transaction blackjack balance for "
                        + (player == null ? "unknown player" : player.getName()),
                exception
        );
    }

    private void sendIfOnline(Player player, String message) {
        if (player != null && player.isOnline() && message != null) {
            player.sendMessage(message);
        }
    }

    private void playWinSound(Player winner) {
        if (winner == null || !winner.isOnline()) {
            return;
        }
        var sound = SmartGambling.getInstance().customSounds.get("blackjackWin");
        if (sound != null) {
            try {
                sound.play(winner);
            } catch (RuntimeException exception) {
                SmartGambling.getInstance().getLogger().log(
                        Level.WARNING,
                        "Could not play the blackjack win sound for " + winner.getName(),
                        exception
                );
            }
        }
    }

    private String message(String key) {
        String message = SmartGambling.getInstance().configManager.messages.get(key);
        return message == null ? "" : message;
    }

    private String formatMessage(String key, Object... arguments) {
        String template = this.message(key);
        try {
            return String.format(template, arguments);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.WARNING,
                    "Invalid blackjack message format for key '" + key + "'",
                    exception
            );
            return template;
        }
    }

    private void stopAnimations(Inventory inventory) {
        if (inventory != null) {
            this.animations.stopAnimations(inventory);
        }
    }

    private void cancelTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private ItemStack getItemIfValid(Inventory inventory, int slot) {
        return inventory != null && slot >= 0 && slot < inventory.getSize()
                ? inventory.getItem(slot)
                : null;
    }

    private void setItemIfValid(Inventory inventory, int slot, ItemStack item) {
        if (inventory != null && slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    @Override
    public ItemStack getMachineItem() {
        return this.machineItem;
    }

    @Override
    public double[] getMachineEntityOffset() {
        return this.entityOffset;
    }
}
