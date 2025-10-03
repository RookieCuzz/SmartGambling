/*    */ package me.arthed.smartgambling.games.common.inventories;
/*    */ 
/*    */ import java.util.HashSet;
/*    */ import me.arthed.smartgambling.SmartGambling;
/*    */ import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
/*    */ import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
/*    */ import me.arthed.smartgambling.games.common.inventories.objects.Button;
/*    */ import me.arthed.smartgambling.games.common.machine.OpenInterface;
/*    */ import me.arthed.smartgambling.games.common.machine.OpenMachine;
/*    */ import me.arthed.smartgambling.utils.DisplayUtils;
/*    */ import org.bukkit.Bukkit;
/*    */ import org.bukkit.OfflinePlayer;
/*    */ import org.bukkit.Sound;
/*    */ import org.bukkit.entity.Entity;
/*    */ import org.bukkit.entity.Player;
/*    */ import org.bukkit.event.inventory.InventoryClickEvent;
/*    */ import org.bukkit.inventory.Inventory;
/*    */ import org.bukkit.inventory.InventoryHolder;
/*    */ import org.bukkit.inventory.ItemStack;
/*    */ import org.bukkit.inventory.meta.ItemMeta;
/*    */ 
/*    */ public class ConfirmGameInventory
/*    */   extends SubInventory
/*    */ {
/*    */   private final int confirmButton;
/*    */   private final int declineButton;
/*    */   
/*    */   public ConfirmGameInventory(Inventory baseInventory, String inventoryTitle, InventoryAnimations animations, int confirmButton, int declineButton) {
/* 30 */     super(baseInventory, inventoryTitle, animations, new Button(new HashSet()));
/* 31 */     this.confirmButton = confirmButton;
/* 32 */     this.declineButton = declineButton;
/*    */   }
/*    */ 
/*    */   
/*    */   public void open(Player player, OpenInterface openInterface) {
/* 37 */     MachineDataBlackjack machineData = (MachineDataBlackjack)((OpenMachine)openInterface).machineData;
/*    */     
/* 39 */     Inventory playerInventory = Bukkit.createInventory((InventoryHolder)player, this.baseInventory.getSize(), this.inventoryTitle);
/* 40 */     playerInventory.setContents(this.baseInventory.getContents());
/*    */     
/* 42 */     for (int i : new int[] { this.confirmButton, this.declineButton }) {
/* 43 */       ItemStack button = playerInventory.getItem(i);
/* 44 */       ItemMeta confirmMeta = button.getItemMeta();
/* 45 */       confirmMeta.setDisplayName(confirmMeta.getDisplayName().replace("%bet%", "" + machineData.bet));
/* 46 */       button.setItemMeta(confirmMeta);
/*    */     } 
/*    */     
/* 49 */     this.oldInterfaces.put(player, openInterface);
/*    */ 
/*    */     
/* 52 */     OpenInterface newInterface = new OpenInterface(this);
/* 53 */     newInterface.inventory = playerInventory;
/* 54 */     (SmartGambling.getInstance()).openMachines.put(player, newInterface);
/*    */     
/* 56 */     this.animations.startAnimations(playerInventory);
/* 57 */     player.openInventory(playerInventory);
/*    */
}
/*    */ 
/*    */   
/*    */   public void inventoryClick(InventoryClickEvent event) {
/* 62 */     event.setCancelled(true);
/* 63 */     if (event.getSlot() == this.confirmButton) {
/* 64 */       Player player = (Player)event.getWhoClicked();
/* 65 */       OpenMachine openMachine = (OpenMachine)this.oldInterfaces.get(event.getWhoClicked());
/* 66 */       MachineDataBlackjack machineData = (MachineDataBlackjack)openMachine.machineData;
/* 68 */       if (SmartGambling.getBalance((OfflinePlayer)player) < machineData.bet) {
/* 69 */         DisplayUtils.displayActionBar(player, 
/*    */             
/* 71 */             String.format((String)(SmartGambling.getInstance()).configManager.messages.get("notEnoughMoneyActionBar"), new Object[] { Integer.valueOf(machineData.bet), Double.valueOf(SmartGambling.getBalance((OfflinePlayer)player)) }));
/*    */         
/* 73 */         player.playSound((Entity)player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
/*    */         return;
/*    */       } 
/* 76 */       machineData.player2 = player;
/* 77 */       openMachine.betAmount = machineData.bet;
/* 78 */       close((Player)event.getWhoClicked(), event.getInventory());
/*    */     }
/* 80 */     else if (event.getSlot() == this.declineButton) {
/* 81 */       OpenMachine openMachine = (OpenMachine)this.oldInterfaces.get(event.getWhoClicked());
/* 82 */       openMachine.betAmount = -1;
/* 83 */       close((Player)event.getWhoClicked(), event.getInventory());
/*    */     } 
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgambling\games\common\inventories\ConfirmGameInventory.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */