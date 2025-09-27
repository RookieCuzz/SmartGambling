// Decompiled with: FernFlower
// Class Version: 17
package me.arthed.smartgambling.listeners;

import me.arthed.smartgambling.SmartGambling;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatListener implements Listener {
    private final SmartGambling smartGambling = SmartGambling.getInstance();

    @EventHandler
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        if (this.smartGambling.inputMoneyRoutine.playersInRoutine.contains(event.getPlayer())) {
            this.smartGambling.inputMoneyRoutine.onPlayerChat(event);
        }

    }
}
