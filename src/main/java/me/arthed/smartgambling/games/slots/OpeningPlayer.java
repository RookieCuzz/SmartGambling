package me.arthed.smartgambling.games.slots;

import com.google.common.base.Preconditions;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class OpeningPlayer {
    private Player player;
    private ItemStack[] originalInventory;
    private int originalLevel;

    private float originalExperience;
    private GameMode originalGameMode;
    public OpeningPlayer(Player paramPlayer) {
        Preconditions.checkNotNull(paramPlayer);
        this.player = paramPlayer;
        this.originalGameMode= paramPlayer.getGameMode();
        PlayerInventory playerInventory = paramPlayer.getInventory();
        this.originalInventory = playerInventory.getContents();
        playerInventory.clear();
     //   paramPlayer.setGameMode(GameMode.SPECTATOR);
    }
    public void restore() {
        this.player.setGameMode(this.originalGameMode);
        //  this.player.setLevel(this.originalLevel);
        //   this.player.setExp(this.originalExperience);
        //  this.player.setCollidable(true);
        this.player.getInventory().setContents(this.originalInventory);

    }
}
