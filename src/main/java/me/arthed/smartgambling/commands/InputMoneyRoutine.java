/*    */ package me.arthed.smartgambling.commands;
/*    */ 
/*    */ import java.util.Set;
/*    */ import java.util.UUID;
/*    */ import java.util.concurrent.ConcurrentHashMap;
/*    */ import java.util.concurrent.ConcurrentMap;
/*    */ import me.arthed.smartgambling.SmartGambling;
/*    */ import me.arthed.smartgambling.config.ConfigManager;
/*    */ import me.arthed.smartgambling.games.common.inventories.MoneyInventory;
/*    */ import me.arthed.smartgambling.games.common.machine.OpenInterface;
/*    */ import org.bukkit.Bukkit;
/*    */ import org.bukkit.ChatColor;
/*    */ import org.bukkit.entity.Player;
/*    */ import org.bukkit.event.player.AsyncPlayerChatEvent;
/*    */ import org.bukkit.plugin.Plugin;
/*    */ 
/*    */ public class InputMoneyRoutine {
/* 15 */   public final Set<Player> playersInRoutine = ConcurrentHashMap.newKeySet();
/* 16 */   private final ConcurrentMap<UUID, OpenInterface> sessions = new ConcurrentHashMap<>();
/* 16 */   private final ConfigManager configManager = (SmartGambling.getInstance()).configManager;
/*    */   
/*    */   public void startRoutine(Player player) {
/* 20 */     OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
/* 21 */     if (current == null
/* 22 */         || !(current.machineType instanceof MoneyInventory moneyInventory)
/* 23 */         || !moneyInventory.hasActiveSession(player)) {
/* 24 */       player.sendMessage(ChatColor.RED + "本次金额输入会话已失效。");
/* 25 */       return;
/*    */     }
/* 27 */     this.sessions.put(player.getUniqueId(), current);
/* 28 */     this.playersInRoutine.add(player);
/* 29 */     player.closeInventory();
/* 30 */     player.sendMessage((String)this.configManager.messages.get("chooseMoneyAmount"));
/*    */   }
/*    */   public void onPlayerChat(AsyncPlayerChatEvent event) {
/*    */     int moneyAmount;
/* 24 */     Player player = event.getPlayer();
/* 25 */     if (!this.playersInRoutine.contains(player)) {
/* 26 */       return;
/*    */     }
/* 25 */     event.setCancelled(true);
/* 28 */     String input = event.getMessage().trim();
/* 29 */     if (!input.matches("[1-9]\\d*")) {
/* 30 */       this.sendInvalidAmount(player);
/* 31 */       return;
/*    */     }
/*    */     try {
/* 34 */       moneyAmount = Integer.parseInt(input);
/* 29 */     } catch (NumberFormatException e) {
/* 36 */       this.sendInvalidAmount(player);
/*    */       return;
/*    */     } 
/* 40 */     if (!this.playersInRoutine.remove(player)) {
/* 41 */       return;
/*    */     }
/* 42 */     OpenInterface expected = this.sessions.remove(player.getUniqueId());
/* 42 */     Bukkit.getScheduler().runTask((Plugin)SmartGambling.getInstance(), () -> {
/* 42 */       OpenInterface current = SmartGambling.getInstance().openMachines.get(player);
/* 43 */       if (!player.isOnline()
/* 44 */           || expected == null
/* 45 */           || current != expected
/* 44 */           || current == null
/* 45 */           || !(current.machineType instanceof MoneyInventory moneyInventory)
/* 46 */           || !moneyInventory.hasActiveSession(player)) {
/* 47 */         if (current == expected && current != null && current.machineType instanceof MoneyInventory moneyInventory) {
/* 48 */           moneyInventory.forceClose(player);
/*    */         }
/* 50 */         if (player.isOnline()) {
/* 51 */           player.sendMessage(ChatColor.RED + "本次金额输入会话已失效。");
/*    */         }
/* 53 */         return;
/*    */       }
/* 55 */       moneyInventory.inputCustomAmount(player, moneyAmount);
/*    */     });
/*    */   }

/*    */   public void cancelRoutine(Player player) {
/* 61 */     this.playersInRoutine.remove(player);
/* 62 */     this.sessions.remove(player.getUniqueId());
/*    */   }

/*    */   private void sendInvalidAmount(Player player) {
/* 67 */     Bukkit.getScheduler().runTask((Plugin)SmartGambling.getInstance(), () -> {
/* 68 */       if (player.isOnline() && this.playersInRoutine.contains(player)) {
/* 69 */         player.sendMessage((String)this.configManager.messages.get("invalidMoneyAmount"));
/*    */       }
/*    */     });
/*    */   }
/*    */ }
