/*    */ package me.arthed.smartgambling.games.common.inventories.objects;
/*    */ 
/*    */ import java.util.Set;
/*    */ 
/*    */ 
/*    */ public class Button
/*    */ {
/*    */   private final Set<Integer> slots;
/*    */   
/*    */   public Button(Set<Integer> slots) {
/* 11 */     this.slots = slots;
/*    */   }
/*    */   
/*    */   public boolean isClicked(int slot) {
/* 15 */     return this.slots.contains(Integer.valueOf(slot));
/*    */   }
/*    */   
/*    */   public Set<Integer> getSlots() {
/* 19 */     return this.slots;
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgambling\games\common\inventories\objects\Button.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */