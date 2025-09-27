/*    */ package me.arthed.smartgambling.utils;
/*    */ 
/*    */ import java.util.regex.Matcher;
/*    */ import java.util.regex.Pattern;
/*    */ import net.md_5.bungee.api.ChatColor;
/*    */ import org.bukkit.Color;
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ public class ColorUtils
/*    */ {
/*    */   public static String replaceAllColorCodes(String string) {
/* 14 */     return ChatColor.translateAlternateColorCodes('&', translateHexColorCodes("#", "", string));
/*    */   }
/*    */ 
/*    */   
/*    */   public static String translateHexColorCodes(String startTag, String endTag, String message) {
/* 19 */     Pattern hexPattern = Pattern.compile(startTag + "([A-Fa-f0-9]{6})" + startTag);
/* 20 */     Matcher matcher = hexPattern.matcher(message);
/* 21 */     StringBuffer buffer = new StringBuffer(message.length() + 32);
/* 22 */     while (matcher.find()) {
/*    */       
/* 24 */       String group = matcher.group(1);
/* 25 */       matcher.appendReplacement(buffer, "§x§" + group
/* 26 */           .charAt(0) + "§" + group.charAt(1) + "§" + group
/* 27 */           .charAt(2) + "§" + group.charAt(3) + "§" + group
/* 28 */           .charAt(4) + "§" + group.charAt(5));
/*    */     } 
/*    */     
/* 31 */     return matcher.appendTail(buffer).toString();
/*    */   }
/*    */   
/*    */   public static Color colorFromHex(String hex) {
/* 35 */     return Color.fromRGB(
/* 36 */         Integer.parseInt(hex.substring(1, 3), 16), 
/* 37 */         Integer.parseInt(hex.substring(3, 5), 16), 
/* 38 */         Integer.parseInt(hex.substring(5, 7), 16));
/*    */   }
/*    */ }


/* Location:              D:\ChromeCoreDownloads\Smart Survival-4.6 Pre-Configured (1)\Update 4.6\plugins\SmartGambling.jar!\me\arthed\smartgamblin\\utils\ColorUtils.class
 * Java compiler version: 17 (61.0)
 * JD-Core Version:       1.1.3
 */