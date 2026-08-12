package me.arthed.smartgambling.games.slots;

import com.google.common.base.Preconditions;
import org.bukkit.entity.Player;

public class OpeningPlayer {
    public OpeningPlayer(Player paramPlayer) {
        Preconditions.checkNotNull(paramPlayer);
    }

    public void restore() {
        // Slot GUIs no longer mutate the player's inventory or game state.
    }
}
