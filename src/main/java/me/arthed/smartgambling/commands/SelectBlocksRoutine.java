/*    */ package me.arthed.smartgambling.commands;
/*    */ import java.util.ArrayList;
/*    */ import java.util.HashMap;
/*    */ import java.util.List;
/*    */ import me.arthed.smartgambling.SmartGambling;
/*    */ import me.arthed.smartgambling.config.ConfigManager;
import net.md_5.bungee.api.chat.BaseComponent;
/*    */ import net.md_5.bungee.api.chat.ClickEvent;
/*    */ import net.md_5.bungee.api.chat.HoverEvent;
/*    */ import net.md_5.bungee.api.chat.TextComponent;
/*    */ import net.md_5.bungee.api.chat.hover.content.Content;
/*    */ import net.md_5.bungee.api.chat.hover.content.Text;
/*    */ import org.bukkit.block.Block;
/*    */ import org.bukkit.entity.Player;
/*    */ import org.bukkit.event.block.BlockBreakEvent;
/*    */ 
/*    */ public class SelectBlocksRoutine {
/* 17 */   public HashMap<Player, List<Block>> playersInRoutine = new HashMap<>();
/* 18 */   private final ConfigManager configManager = (SmartGambling.getInstance()).configManager;
/*    */   
/*    */   public void startRoutine(Player player) {
/* 21 */     this.playersInRoutine.put(player, new ArrayList<>());
/*    */   }
/*    */   
/*    */   public void blockBreak(BlockBreakEvent event) {
/* 25 */     event.setCancelled(true);
/* 26 */     ((List<Block>)this.playersInRoutine.get(event.getPlayer())).add(event.getBlock());
/*    */     
/* 28 */     BaseComponent[] blockSelectedMessage = new BaseComponent[3];
/* 29 */     blockSelectedMessage[0] = (BaseComponent)new TextComponent(
/* 30 */         String.format((String)this.configManager.messages.get("selectBlockMessage"), new Object[] {
/* 31 */             Integer.valueOf(event.getBlock().getX()), 
/* 32 */             Integer.valueOf(event.getBlock().getY()), 
/* 33 */             Integer.valueOf(event.getBlock().getZ())
/*    */           }));
/*    */     
/* 36 */     blockSelectedMessage[1] = (BaseComponent)new TextComponent((String)this.configManager.messages.get("cancelButton"));
/* 37 */     blockSelectedMessage[2] = (BaseComponent)new TextComponent((String)this.configManager.messages.get("confirmButton"));
/* 38 */     blockSelectedMessage[1].setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sg cancel"));
/* 39 */     blockSelectedMessage[1].setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Content[] { (Content)new Text((String)this.configManager.messages.get("click")) }));
/* 40 */     blockSelectedMessage[2].setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sg confirm"));
/* 41 */     blockSelectedMessage[2].setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Content[] { (Content)new Text((String)this.configManager.messages.get("click")) }));
/*    */     
/* 43 */     event.getPlayer().spigot().sendMessage(blockSelectedMessage);
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgambling\commands\SelectBlocksRoutine.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */