/*    */ package me.arthed.smartgambling.commands;
/*    */ 
/*    */ import me.arthed.smartgambling.SmartGambling;
/*    */ import me.arthed.smartgambling.games.common.machine.Machine;
/*    */ import me.arthed.smartgambling.games.common.machine.OpenInterface;
/*    */ import org.bukkit.ChatColor;
/*    */ import org.bukkit.command.Command;
/*    */ import org.bukkit.command.CommandExecutor;
/*    */ import org.bukkit.command.CommandSender;
/*    */ import org.bukkit.entity.Player;
/*    */ 
/*    */ public class JackpotCommand
/*    */   implements CommandExecutor
/*    */ {
/*    */   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
/* 16 */     if (!cmd.getName().equalsIgnoreCase("jackpot")) {
/* 17 */       return false;
/*    */     }
/* 19 */     if (!(sender instanceof Player)) {
/* 20 */       sender.sendMessage("" + ChatColor.RED + "此命令只能由游戏内玩家执行。");
/* 21 */       return false;
/*    */     } 
/* 23 */     Player player = (Player)sender;
/*    */     if ((SmartGambling.getInstance()).openMachines.containsKey(player)
/*    */         || (SmartGambling.getInstance()).inputMoneyRoutine.playersInRoutine.contains(player)) {
/*    */       player.sendMessage(ChatColor.RED + "请先关闭当前游戏，再打开大奖池。");
/*    */       return true;
/*    */     }
/*    */     
/* 25 */     if ((SmartGambling.getInstance()).worldGuard != null && !(SmartGambling.getInstance()).worldGuard.canUseJackpot(player)) {
/* 26 */       sender.sendMessage((String)(SmartGambling.getInstance()).configManager.messages.get("jackpotCommandDeny"));
/* 27 */       return false;
/*    */     } 
/* 29 */     (SmartGambling.getInstance()).jackpotMachine.open(player, new OpenInterface((Machine)(SmartGambling.getInstance()).jackpotMachine));
/* 30 */     return true;
/*    */   }
/*    */ }
