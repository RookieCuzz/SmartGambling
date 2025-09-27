/*    */ package me.arthed.smartgambling.games.common.inventories.animation;
/*    */ 
/*    */ import java.util.List;
/*    */ import org.bukkit.Material;
/*    */ 
/*    */ 
/*    */ 
/*    */ public class ItemAnimation
/*    */ {
/*    */   public List<Material> materials;
/*    */   public List<Integer> slots;
/*    */   public int delay;
/*    */   public int vary;
/*    */   
/*    */   public ItemAnimation(List<Material> materials, List<Integer> slots, int delay, int vary) {
/* 16 */     this.materials = materials;
/* 17 */     this.slots = slots;
/* 18 */     this.delay = delay;
/* 19 */     this.vary = vary;
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgambling\games\common\inventories\animation\ItemAnimation.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */