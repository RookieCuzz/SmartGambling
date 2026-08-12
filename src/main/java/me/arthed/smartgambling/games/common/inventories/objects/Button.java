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
