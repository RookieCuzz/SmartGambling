package me.arthed.smartgambling.games.blackjack;

import java.util.List;
import java.util.UUID;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.economy.WagerHandle;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runtime state for one physical blackjack table.
 *
 * <p>The reservation fields deliberately live on the table rather than on the
 * global {@link BlackJack} instance. Bukkit normally calls this code from its
 * primary thread, but the synchronized reservation methods also make nested
 * inventory callbacks atomic and prevent two scheduled opens from claiming the
 * same seat.</p>
 */
public class MachineDataBlackjack extends MachineData {
    public enum SettlementKind {
        REFUND,
        PLAYER1_WINS,
        PLAYER2_WINS
    }

    public Player player1;
    public Player player2;
    public Player pendingHost;
    public Player pendingChallenger;
    public UUID pendingHostWagerNonce;
    public UUID pendingChallengerWagerNonce;
    /** Stable financial identity for the current table round. */
    public UUID roundId;
    public int bet;
    public WagerHandle player1Wager;
    public WagerHandle player2Wager;
    public boolean player1StakePaid;
    public boolean player2StakePaid;
    public boolean wagersLocked;
    public SettlementKind pendingSettlement;
    public String pendingSettlementOperationId;
    public boolean startGame;
    public boolean resolving;
    public boolean dealingInitialCards;
    public Inventory player1Inventory;
    public Inventory player2Inventory;
    public List<PlayingCard> player1Cards;
    public List<PlayingCard> player2Cards;
    public boolean player1stopped;
    public boolean player2stopped;
    public int player1Value;
    public int player2Value;
    public BukkitTask waitingMessageTask;
    public BukkitTask cleanupTask;
    public BukkitTask settlementRetryTask;
    public int settlementRetryAttempts;

    public MachineDataBlackjack(
            UUID id,
            Machine machineType,
            Block[] blocks,
            Entity[] entities,
            BlockFace direction
    ) {
        this(id, machineType, blocks, entities, direction, false);
    }

    public MachineDataBlackjack(
            UUID id,
            Machine machineType,
            Block[] blocks,
            Entity[] entities,
            BlockFace direction,
            boolean forceEntityRebuild
    ) {
        super(id, machineType, blocks, entities, direction, forceEntityRebuild);
        this.roundId = UUID.randomUUID();
    }

    public synchronized boolean reserveHost(Player player) {
        if (player == null || player1 != null || pendingChallenger != null
                || player2 != null || player1Wager != null || player2Wager != null
                || wagersLocked || pendingSettlement != null || startGame || resolving) {
            return false;
        }
        if (pendingHost != null && pendingHost != player) {
            return false;
        }
        if (pendingHost == null) {
            pendingHostWagerNonce = UUID.randomUUID();
        }
        pendingHost = player;
        refreshInUse();
        return true;
    }

    public synchronized boolean reserveChallenger(Player player) {
        if (player == null || player == player1 || player1 == null
                || !player1StakePaid || player1Wager == null
                || pendingHost != null || player2 != null || player2Wager != null
                || wagersLocked || pendingSettlement != null
                || startGame || resolving) {
            return false;
        }
        if (pendingChallenger != null && pendingChallenger != player) {
            return false;
        }
        if (pendingChallenger == null) {
            pendingChallengerWagerNonce = UUID.randomUUID();
        }
        pendingChallenger = player;
        refreshInUse();
        return true;
    }

    public synchronized boolean canCommitHost(Player player) {
        return pendingHost == player && pendingHostWagerNonce != null
                && player1 == null && player2 == null
                && player1Wager == null && player2Wager == null
                && !wagersLocked && pendingSettlement == null
                && !startGame && !resolving;
    }

    public synchronized boolean canCommitChallenger(Player player) {
        return pendingChallenger == player && pendingChallengerWagerNonce != null
                && player1 != null
                && player1StakePaid && player1Wager != null
                && player2 == null && player2Wager == null
                && !wagersLocked && pendingSettlement == null && !startGame
                && !resolving && player != player1;
    }

    public synchronized boolean releaseReservation(Player player) {
        boolean released = false;
        if (pendingHost == player) {
            pendingHost = null;
            pendingHostWagerNonce = null;
            released = true;
        }
        if (pendingChallenger == player) {
            pendingChallenger = null;
            pendingChallengerWagerNonce = null;
            released = true;
        }
        refreshInUse();
        return released;
    }

    public synchronized boolean hasParticipant(Player player) {
        return player1 == player || player2 == player
                || pendingHost == player || pendingChallenger == player;
    }

    public synchronized void refreshInUse() {
        // Blackjack entry points inspect the seat reservations themselves, so
        // a paid host may keep the table protected from administrative removal
        // while a challenger is still allowed to join.
        inUse = pendingHost != null || pendingChallenger != null
                || player1Wager != null || player2Wager != null
                || player1StakePaid || player2StakePaid || player2 != null
                || wagersLocked || pendingSettlement != null
                || startGame || resolving;
    }
}
