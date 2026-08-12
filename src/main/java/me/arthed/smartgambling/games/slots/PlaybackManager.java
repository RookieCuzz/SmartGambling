package me.arthed.smartgambling.games.slots;

import com.google.common.base.Preconditions;
import me.arthed.smartgambling.SmartGambling;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlaybackManager implements Listener {
    public static final Map<UUID, OpeningPlayer> openingPlayers = new HashMap<>();



    public static void removeOpeningPlayer(Player paramPlayer) {
        Preconditions.checkNotNull(paramPlayer);
        OpeningPlayer touringPlayer = PlaybackManager.openingPlayers.remove(paramPlayer.getUniqueId());
        if (touringPlayer == null)
            return;
        touringPlayer.restore();
    }

    public boolean isOpeningPlayer(Player paramPlayer) {
        return (getOpeningPlayer(paramPlayer) != null);
    }

    public OpeningPlayer getOpeningPlayer(Player paramPlayer) {
        return this.openingPlayers.get(paramPlayer.getUniqueId());
    }
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent paramPlayerQuitEvent) {
        Player player = paramPlayerQuitEvent.getPlayer();
        SmartGambling.getInstance().inputMoneyRoutine.cancelRoutine(player);
        removeOpeningPlayer(player);
    }
}
