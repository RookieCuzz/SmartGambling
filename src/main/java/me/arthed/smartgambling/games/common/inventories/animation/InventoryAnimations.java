/*    */ package me.arthed.smartgambling.games.common.inventories.animation;
/*    */ 
/*    */ import java.util.ArrayList;
/*    */ import java.util.HashMap;
/*    */ import java.util.Iterator;
/*    */ import java.util.List;
/*    */ import me.arthed.smartgambling.SmartGambling;
/*    */ import org.bukkit.Bukkit;
/*    */ import org.bukkit.inventory.Inventory;
/*    */ import org.bukkit.inventory.ItemStack;
/*    */ import org.bukkit.plugin.Plugin;
/*    */ import org.bukkit.scheduler.BukkitTask;
/*    */ 
/*    */ public class InventoryAnimations
/*    */ {
/*    */   public List<ItemAnimation> itemAnimations;
/*    */   public List<ItemAnimation> dependentItemAnimations;
/* 18 */   private final HashMap<Inventory, List<BukkitTask>> activeInventories = new HashMap<>();
/* 19 */   private final HashMap<Inventory, List<BukkitTask>> activeDependentInventories = new HashMap<>();
/*    */   
/*    */   public InventoryAnimations(List<ItemAnimation> itemAnimations, List<ItemAnimation> dependentItemAnimations) {
/* 22 */     this.itemAnimations = itemAnimations;
/* 23 */     this.dependentItemAnimations = dependentItemAnimations;
/*    */   }
/*    */   
/*    */   public void startAnimations(Inventory inventory) {

/* 27 */     List<BukkitTask> tasks = new ArrayList<>();
/* 28 */     for (ItemAnimation animation : this.itemAnimations) {
/* 29 */       ItemAnimation currentAnimation = animation;
/* 30 */       BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously((Plugin)SmartGambling.getInstance(), () -> animateItem(inventory, currentAnimation), 0L, animation.delay);
/*    */ 
/*    */       
/* 33 */       tasks.add(task);
/*    */     } 
/* 35 */     if (tasks.size() > 0)
/* 36 */       this.activeInventories.put(inventory, tasks); 
/* 37 */     stopDependentAnimations(inventory);
/*    */   }
/*    */   
/*    */   public void stopAnimations(Inventory inventory) {
/* 41 */     if (this.activeInventories.containsKey(inventory)) {
/* 42 */       for (BukkitTask task : this.activeInventories.get(inventory)) {
/* 43 */         task.cancel();
/* 44 */         Bukkit.getScheduler().cancelTask(task.getTaskId());
/*    */       } 
/* 46 */       this.activeInventories.remove(inventory);
/*    */     } 
/* 48 */     startDependentAnimations(inventory);
/*    */   }
/*    */   
/*    */   public boolean isAnimated(Inventory inventory) {
/* 52 */     return (this.activeInventories.containsKey(inventory) || this.activeDependentInventories.containsKey(inventory));
/*    */   }
/*    */   
/*    */   public void startDependentAnimations(Inventory inventory) {
/* 56 */     if (this.activeDependentInventories.containsKey(inventory)) {
/* 57 */       for (BukkitTask task : this.activeDependentInventories.get(inventory)) {
/* 58 */         task.cancel();
/* 59 */         Bukkit.getScheduler().cancelTask(task.getTaskId());
/*    */       } 
/* 61 */       this.activeDependentInventories.remove(inventory);
/*    */     } 
/*    */   }
/*    */   
/*    */   public void stopDependentAnimations(Inventory inventory) {
/* 66 */     List<BukkitTask> tasks = new ArrayList<>();
/* 67 */     for (ItemAnimation animation : this.dependentItemAnimations) {
/* 68 */       if (animation.delay > 0) {
/* 69 */         ItemAnimation currentAnimation = animation;
/* 70 */         tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously((Plugin)SmartGambling.getInstance(), () -> animateItem(inventory, currentAnimation), 0L, animation.delay));
/*    */       } 
/*    */     } 
/*    */ 
/*    */     
/* 75 */     if (tasks.size() > 0) {
/* 76 */       this.activeDependentInventories.put(inventory, tasks);
/*    */     }
/*    */   }
/*    */   
/*    */   public void animateDependent(Inventory inventory) {
/* 81 */     for (ItemAnimation animation : this.dependentItemAnimations) {
/* 82 */       animateItem(inventory, animation);
/*    */     }
/*    */   }
/*    */   
/*    */   private void animateItem(Inventory inventory, ItemAnimation animation) {
/* 87 */     for (Iterator<Integer> iterator = animation.slots.iterator(); iterator.hasNext(); ) { int slot = ((Integer)iterator.next()).intValue();
/* 88 */       ItemStack item = inventory.getItem(slot);
/* 89 */       assert item != null;
/* 90 */       if (!animation.materials.contains(item.getType())) {
/* 91 */         item.setType(animation.materials.get(slot % animation.vary)); continue;
/*    */       } 
/* 93 */       int index = animation.materials.indexOf(item.getType());
/* 94 */       item.setType(animation.materials.get((index + 1) % animation.materials.size())); }
/*    */   
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgambling\games\common\inventories\animation\InventoryAnimations.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */