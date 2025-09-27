package me.arthed.smartgambling.utils;

import me.arthed.smartgambling.SmartGambling;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

public class DisplayUtils {
    public static void displayBossBar(Player player, String message, BarColor color, BarStyle style, int seconds) {
        BossBar bossBar = Bukkit.createBossBar(message, color, style, new BarFlag[0]);
        bossBar.addPlayer(player);
        bossBar.setProgress(1.0D);
        float stepAmount = 1.0F / ((float)seconds * 10.0F);

        for(int i = 0; i < seconds * 10; ++i) {
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                if (bossBar.getProgress() - (double)stepAmount >= 0.0D) {
                    bossBar.setProgress(bossBar.getProgress() - (double)stepAmount);
                }

            }, 2L * (long)(i + 1));
        }

        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> bossBar.removePlayer(player), (long)seconds * 20L);
    }

    public static void displayActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }
}
 