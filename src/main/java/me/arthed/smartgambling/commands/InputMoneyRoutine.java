/*    */ package me.arthed.smartgambling.commands;
/*    */ 
/*    */ import java.util.HashSet;
/*    */ import java.util.Set;
/*    */ import me.arthed.smartgambling.SmartGambling;
/*    */ import me.arthed.smartgambling.config.ConfigManager;
/*    */ import me.arthed.smartgambling.games.common.inventories.MoneyInventory;
/*    */ import me.arthed.smartgambling.games.common.machine.OpenInterface;
/*    */ import org.bukkit.Bukkit;
/*    */ import org.bukkit.entity.Player;
/*    */ import org.bukkit.event.player.AsyncPlayerChatEvent;
/*    */ import org.bukkit.plugin.Plugin;
/*    */ 
/*    */ public class InputMoneyRoutine {
/* 15 */   public Set<Player> playersInRoutine = new HashSet<>();
/* 16 */   private final ConfigManager configManager = (SmartGambling.getInstance()).configManager;
/*    */   
/*    */   public void startRoutine(Player player) {
/* 19 */     this.playersInRoutine.add(player);
/* 20 */     player.closeInventory();
/* 21 */     player.sendMessage((String)this.configManager.messages.get("chooseMoneyAmount"));
/*    */   }
/*    */   public void onPlayerChat(AsyncPlayerChatEvent event) {
/*    */     int moneyAmount;
/* 25 */     event.setCancelled(true);
/*    */     
/*    */     try {
/* 28 */       moneyAmount = Integer.parseInt(event.getMessage().replace(",", ""));
/* 29 */     } catch (NumberFormatException e) {
/* 30 */       event.getPlayer().sendMessage((String)this.configManager.messages.get("invalidMoneyAmount"));
/*    */       return;
/*    */     } 
/* 33 */     this.playersInRoutine.remove(event.getPlayer());
/* 34 */     Bukkit.getScheduler().runTask((Plugin)SmartGambling.getInstance(), () -> ((MoneyInventory)((OpenInterface)(SmartGambling.getInstance()).openMachines.get(event.getPlayer())).machineType).inputCustomAmount(event.getPlayer(), moneyAmount));
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgambling\commands\InputMoneyRoutine.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */