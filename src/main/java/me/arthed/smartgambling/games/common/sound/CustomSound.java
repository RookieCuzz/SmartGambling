/*    */ package me.arthed.smartgambling.games.common.sound;
/*    */ 
/*    */ import java.util.List;
/*    */ import me.arthed.smartgambling.SmartGambling;
/*    */ import org.bukkit.Bukkit;
/*    */ import org.bukkit.entity.Entity;
/*    */ import org.bukkit.entity.Player;
/*    */ import org.bukkit.plugin.Plugin;
/*    */ 
/*    */ public class CustomSound {
/* 11 */   private static final SmartGambling smartGambling = SmartGambling.getInstance();
/*    */   
/*    */   private final List<SoundElement> elements;
/*    */   
/*    */   public CustomSound(List<SoundElement> elements) {
/* 16 */     this.elements = elements;
/*    */   }
/*    */   
/*    */   public void play(Player player) {
/* 20 */     int totalDelay = 0;
/* 21 */     for (SoundElement soundElement : this.elements) {
/* 22 */       totalDelay += soundElement.delay;
/* 23 */       Bukkit.getScheduler().runTaskLater((Plugin)smartGambling, () -> player.playSound((Entity)player, soundElement.sound, soundElement.volume, soundElement.pitch), totalDelay);
/*    */     } 
/*    */   }
/*    */ }
