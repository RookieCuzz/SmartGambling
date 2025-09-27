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
/* 20 */       sender.sendMessage("" + ChatColor.RED + "Command can only be used by players.");
/* 21 */       return false;
/*    */     } 
/* 23 */     Player player = (Player)sender;
/*    */     
/* 25 */     if ((SmartGambling.getInstance()).worldGuard != null && !(SmartGambling.getInstance()).worldGuard.canUseJackpot(player)) {
/* 26 */       sender.sendMessage((String)(SmartGambling.getInstance()).configManager.messages.get("jackpotCommandDeny"));
/* 27 */       return false;
/*    */     } 
/* 29 */     (SmartGambling.getInstance()).jackpotMachine.open(player, new OpenInterface((Machine)(SmartGambling.getInstance()).jackpotMachine));
/* 30 */     return true;
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgambling\commands\JackpotCommand.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */