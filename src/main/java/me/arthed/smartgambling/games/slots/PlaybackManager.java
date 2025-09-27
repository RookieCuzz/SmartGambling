package me.arthed.smartgambling.games.slots;

import com.google.common.base.Preconditions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlaybackManager implements Listener {
    public static Map<UUID, OpeningPlayer> openingPlayers = new HashMap<>();



    public static void removeOpeningPlayer(Player paramPlayer) {
        Preconditions.checkNotNull(paramPlayer);
        OpeningPlayer touringPlayer = PlaybackManager.openingPlayers.get(paramPlayer.getUniqueId());
        if (touringPlayer == null)
            return;
        touringPlayer.restore();
        PlaybackManager.openingPlayers.remove(paramPlayer.getUniqueId());
    }

    public boolean isOpeningPlayer(Player paramPlayer) {
        return (getOpeningPlayer(paramPlayer) != null);
    }

    public OpeningPlayer getOpeningPlayer(Player paramPlayer) {
        return this.openingPlayers.get(paramPlayer.getUniqueId());
    }
    @EventHandler
    public void onExit(PlayerQuitEvent paramPlayerQuitEvent) {
        for (OpeningPlayer openingPlayer : this.openingPlayers.values()) {
            openingPlayer.restore();
        }
        this.openingPlayers.clear();
    }


    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent paramPlayerQuitEvent) {
        Player player = paramPlayerQuitEvent.getPlayer();
        if (isOpeningPlayer(player)){
            removeOpeningPlayer(player);
        }

    }



}
