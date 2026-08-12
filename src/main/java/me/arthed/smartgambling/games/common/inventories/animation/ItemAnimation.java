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
