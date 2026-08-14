package me.arthed.smartgambling.games.poker;

import java.util.ArrayList;
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

/** Runtime state owned by one physical heads-up poker table. */
public final class MachineDataPoker extends MachineData {
    public Player host;
    public Player challenger;
    public Player pendingHost;
    public Player pendingChallenger;
    public UUID pendingHostWagerNonce;
    public UUID pendingChallengerWagerNonce;
    public UUID roundId = UUID.randomUUID();
    public int buyIn;
    public WagerHandle hostWager;
    public WagerHandle challengerWager;
    public boolean hostStakePaid;
    public boolean challengerStakePaid;
    public boolean wagersLocked;
    public HeadsUpPokerRound round;
    public Inventory hostInventory;
    public Inventory challengerInventory;
    public List<PokerCard> hostCards = new ArrayList<>();
    public List<PokerCard> challengerCards = new ArrayList<>();
    public List<PokerCard> communityCards = new ArrayList<>();
    public List<PokerCard> deck = new ArrayList<>();
    public int deckCursor;
    public BukkitTask waitingTask;
    public BukkitTask turnTask;
    public BukkitTask cleanupTask;
    public BukkitTask settlementRetryTask;
    public int settlementRetryAttempts;
    public String pendingSettlementOperationId;
    public boolean pendingRefund;
    public long pendingHostPayout = -1L;
    public long pendingChallengerPayout = -1L;
    public boolean resolving;

    public MachineDataPoker(
            UUID id,
            Machine machineType,
            Block[] blocks,
            Entity[] entities,
            BlockFace direction
    ) {
        this(id, machineType, blocks, entities, direction, false);
    }

    public MachineDataPoker(
            UUID id,
            Machine machineType,
            Block[] blocks,
            Entity[] entities,
            BlockFace direction,
            boolean forceEntityRebuild
    ) {
        super(id, machineType, blocks, entities, direction, forceEntityRebuild);
    }

    public synchronized boolean reserveHost(Player player) {
        if (player == null || host != null || challenger != null || pendingChallenger != null
                || hostWager != null || challengerWager != null || round != null || resolving) {
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
        if (player == null || player == host || host == null || !hostStakePaid || hostWager == null
                || pendingHost != null || challenger != null || challengerWager != null
                || wagersLocked || round != null || resolving) {
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
                && host == null && challenger == null && hostWager == null && challengerWager == null
                && !wagersLocked && round == null && !resolving;
    }

    public synchronized boolean canCommitChallenger(Player player) {
        return pendingChallenger == player && pendingChallengerWagerNonce != null
                && host != null && hostStakePaid && hostWager != null
                && challenger == null && challengerWager == null
                && !wagersLocked && round == null && !resolving && player != host;
    }

    public synchronized void releaseReservation(Player player) {
        if (pendingHost == player) {
            pendingHost = null;
            pendingHostWagerNonce = null;
        }
        if (pendingChallenger == player) {
            pendingChallenger = null;
            pendingChallengerWagerNonce = null;
        }
        refreshInUse();
    }

    public synchronized boolean hasParticipant(Player player) {
        return host == player || challenger == player
                || pendingHost == player || pendingChallenger == player;
    }

    public synchronized HeadsUpPokerRound.Seat seat(Player player) {
        if (host == player) {
            return HeadsUpPokerRound.Seat.HOST;
        }
        if (challenger == player) {
            return HeadsUpPokerRound.Seat.CHALLENGER;
        }
        return null;
    }

    public synchronized void refreshInUse() {
        inUse = pendingHost != null || pendingChallenger != null
                || hostWager != null || challengerWager != null
                || hostStakePaid || challengerStakePaid || host != null || challenger != null
                || wagersLocked || round != null || resolving || pendingSettlementOperationId != null;
    }
}
