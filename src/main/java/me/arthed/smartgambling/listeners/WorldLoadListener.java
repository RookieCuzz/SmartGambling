package me.arthed.smartgambling.listeners;

import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.DataManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/** Publishes machine data retained for worlds unavailable during startup. */
public final class WorldLoadListener implements Listener {
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        try {
            int loaded = DataManager.loadDeferredWorld(event.getWorld());
            if (loaded > 0) {
                SmartGambling.getInstance().getLogger().info(
                        "Loaded " + loaded + " deferred machines for world "
                                + event.getWorld().getName()
                );
            }
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    Level.SEVERE,
                    "Could not load deferred machine data for world "
                            + event.getWorld().getName()
                            + "; the original entry remains retained for retry",
                    exception
            );
        }
    }
}
