/*    */ package me.arthed.smartgambling.games.common.sound;
/*    */ 
/*    */ import org.bukkit.Sound;
/*    */ 
/*    */ 
/*    */ public class SoundElement
/*    */ {
/*    */   public final Sound sound;
/*    */   public final float volume;
/*    */   public final float pitch;
/*    */   public final int delay;
/*    */   
/*    */   public SoundElement(Sound sound, float volume, float pitch, int delay) {
/* 14 */     this.sound = sound;
/* 15 */     this.volume = volume;
/* 16 */     this.pitch = pitch;
/* 17 */     this.delay = delay;
/*    */   }
/*    */ }
