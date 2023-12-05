package me.arthed.smartgambling.games.blackjack;

import java.util.List;
import java.util.UUID;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.PlayingCard;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public class MachineDataBlackjack extends MachineData {
    public Player player1;
    public Player player2;
    public int bet;
    public boolean startGame;
    public Inventory player1Inventory;
    public Inventory player2Inventory;
    public List<PlayingCard> player1Cards;
    public List<PlayingCard> player2Cards;
    public boolean player1stopped;
    public boolean player2stopped;
    public int player1Value;
    public int player2Value;

    public MachineDataBlackjack(UUID id, Machine machineType, Block[] blocks, Entity[] entities, BlockFace direction) {
        super(id, machineType, blocks, entities, direction);
    }
}




