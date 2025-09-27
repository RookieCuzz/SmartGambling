package me.arthed.smartgambling.games.slots.odalita;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.slots.objects.SlotItem;
import me.arthed.smartgambling.games.slots.objects.rewards.Reward;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Factory class for OdalitaSlotMachine
 * Responsible for creating OdalitaSlotMachine instances from configuration files
 */
public class OdalitaSlotMachineFactory {
    
    /**
     * Create OdalitaSlotMachine instance from configuration
     * Maintain compatibility with original SlotMachine configuration format
     */
    public static OdalitaSlotMachine createFromConfig(String machineName, ConfigurationSection config) {
        // Read basic properties from configuration
        int defaultBet = config.getInt("defaultBet", 100);
        
        // Read GUI related properties from GUI configuration section
        ConfigurationSection guiConfig = config.getConfigurationSection("GUI");
        String inventoryTitle = (guiConfig != null) ? guiConfig.getString("title", "Slot Machine") : "Slot Machine";
        int animationDuration = (guiConfig != null) ? guiConfig.getInt("animationDuration", 60) : 60;
        int animationStartingSpeed = (guiConfig != null) ? guiConfig.getInt("animationSpeed", 3) : 3;
        
        // 读取机器物品配置
        ItemStack machineItem = loadMachineItem(config.getConfigurationSection("machineItem"));
        
        // 读取实体偏移配置
        double[] entityOffset = loadEntityOffset(config.getConfigurationSection("entityOffset"));
        
        // 读取显示槽位配置
        List<List<Integer>> displaySlots = loadDisplaySlots(guiConfig);
        
        // 读取物品权重配置
        NavigableMap<Integer, SlotItem> itemsWeighed = new TreeMap<>();
        int totalWeight = loadWeightedItems(config.getConfigurationSection("items"), itemsWeighed);
        
        // 读取奖励配置
        List<Reward> rewards = loadRewards(config.getConfigurationSection("rewards"));
        
        return new OdalitaSlotMachine(
            machineName,
            machineItem,
            entityOffset,
            inventoryTitle,
            displaySlots,
            itemsWeighed,
            totalWeight,
            rewards,
            animationDuration,
            defaultBet,
            animationStartingSpeed
        );
    }
    
    private static ItemStack loadMachineItem(ConfigurationSection config) {
        if (config == null) {
            return new ItemStack(org.bukkit.Material.EMERALD_BLOCK);
        }
        
        // Implement ItemStack loading logic from configuration here
        // Return default item for now
        return new ItemStack(org.bukkit.Material.EMERALD_BLOCK);
    }
    
    private static double[] loadEntityOffset(ConfigurationSection config) {
        if (config == null) {
            return new double[]{0.0, 0.0, 0.0};
        }
        
        return new double[]{
            config.getDouble("x", 0.0),
            config.getDouble("y", 0.0),
            config.getDouble("z", 0.0)
        };
    }
    
    private static List<List<Integer>> loadDisplaySlots(ConfigurationSection config) {
        if (config == null) {
            // Return default 5-column configuration
            return List.of(
                List.of(11, 20, 29), // First column
                List.of(12, 21, 30), // Second column
                List.of(13, 22, 31), // Third column
                List.of(14, 23, 32), // Fourth column
                List.of(15, 24, 33)  // Fifth column
            );
        }
        
        List<List<Integer>> displaySlots = new ArrayList<>();
        
        // Read displaySlots from configuration file
        if (config.isList("displaySlots")) {
            List<?> slotsList = config.getList("displaySlots");
            if (slotsList != null) {
                for (Object columnObj : slotsList) {
                    if (columnObj instanceof List<?>) {
                        List<?> columnList = (List<?>) columnObj;
                        List<Integer> column = new ArrayList<>();
                        for (Object slotObj : columnList) {
                            if (slotObj instanceof Integer) {
                                column.add((Integer) slotObj);
                            }
                        }
                        if (!column.isEmpty()) {
                            displaySlots.add(column);
                        }
                    }
                }
            }
        }
        
        // If configuration reading fails, return default configuration
        if (displaySlots.isEmpty()) {
            return List.of(
                List.of(11, 20, 29), // First column
                List.of(12, 21, 30), // Second column
                List.of(13, 22, 31), // Third column
                List.of(14, 23, 32), // Fourth column
                List.of(15, 24, 33)  // Fifth column
            );
        }
        
        return displaySlots;
    }
    
    private static int loadWeightedItems(ConfigurationSection config, NavigableMap<Integer, SlotItem> itemsWeighed) {
        if (config == null) {
            // 添加默认物品
            SlotItem defaultItem = new SlotItem(new ItemStack(org.bukkit.Material.DIAMOND));
            itemsWeighed.put(0, defaultItem);
            return 1;
        }
        
        int totalWeight = 0;
        for (String itemName : config.getKeys(false)) {
            ConfigurationSection itemConfig = config.getConfigurationSection(itemName);
            if (itemConfig != null) {
                int weight = itemConfig.getInt("weight", 1);
                ItemStack itemStack = loadItemStack(itemConfig.getConfigurationSection("item"));
                SlotItem slotItem = new SlotItem(itemStack);
                
                itemsWeighed.put(totalWeight, slotItem);
                totalWeight += weight;
            }
        }
        
        return totalWeight;
    }
    
    private static ItemStack loadItemStack(ConfigurationSection config) {
        if (config == null) {
            return new ItemStack(org.bukkit.Material.DIAMOND);
        }
        
        // Implement detailed ItemStack loading logic from configuration
        // Including material, amount, display name, description, etc.
        String material = config.getString("material", "DIAMOND");
        int amount = config.getInt("amount", 1);
        
        try {
            org.bukkit.Material mat = org.bukkit.Material.valueOf(material.toUpperCase());
            return new ItemStack(mat, amount);
        } catch (IllegalArgumentException e) {
            SmartGambling.getInstance().getLogger().warning("Invalid material: " + material + ", using DIAMOND instead");
            return new ItemStack(org.bukkit.Material.DIAMOND, amount);
        }
    }
    
    private static List<Reward> loadRewards(ConfigurationSection config) {
        // Load rewards from configuration
        // Return empty list for now
        return List.of();
    }
    
    /**
     * Convert existing SlotMachine to OdalitaSlotMachine
     * Used for runtime switching
     */
    public static OdalitaSlotMachine convertFromSlotMachine(me.arthed.smartgambling.games.slots.SlotMachine original) {
        // Get original SlotMachine properties through reflection or other methods
        // Adjust according to actual SlotMachine implementation
        
        return new OdalitaSlotMachine(
            original.name,
            original.getMachineItem(),
            original.getMachineEntityOffset(),
            original.inventoryTitle,
            getDisplaySlotsFromOriginal(original),
            getItemsWeighedFromOriginal(original),
            getTotalWeightFromOriginal(original),
            getRewardsFromOriginal(original),
            getAnimationDurationFromOriginal(original),
            original.defaultBet,
            getAnimationStartingSpeedFromOriginal(original)
        );
    }
    
    // Get private fields from original SlotMachine using reflection
    @SuppressWarnings("unchecked")
    private static List<List<Integer>> getDisplaySlotsFromOriginal(me.arthed.smartgambling.games.slots.SlotMachine original) {
        try {
            Field field = original.getClass().getDeclaredField("displaySlots");
            field.setAccessible(true);
            return (List<List<Integer>>) field.get(original);
        } catch (Exception e) {
            SmartGambling.getInstance().getLogger().warning("Failed to get displaySlots, using default config: " + e.getMessage());
            // Return default 5-column configuration
            return List.of(
                List.of(12, 21, 30), // Column 1
                List.of(13, 22, 31), // Column 2
                List.of(14, 23, 32), // Column 3
                List.of(15, 24, 33), // Column 4
                List.of(16, 25, 34)  // Column 5
            );
        }
    }
    
    @SuppressWarnings("unchecked")
    private static NavigableMap<Integer, SlotItem> getItemsWeighedFromOriginal(me.arthed.smartgambling.games.slots.SlotMachine original) {
        try {
            Field field = original.getClass().getDeclaredField("itemsWeighed");
            field.setAccessible(true);
            return (NavigableMap<Integer, SlotItem>) field.get(original);
        } catch (Exception e) {
            SmartGambling.getInstance().getLogger().warning("Failed to get itemsWeighed, using empty config: " + e.getMessage());
            return new TreeMap<>();
        }
    }
    
    private static int getTotalWeightFromOriginal(me.arthed.smartgambling.games.slots.SlotMachine original) {
        try {
            Field field = original.getClass().getDeclaredField("itemsTotalWeight");
            field.setAccessible(true);
            return field.getInt(original);
        } catch (Exception e) {
            SmartGambling.getInstance().getLogger().warning("Failed to get itemsTotalWeight, using default value 1: " + e.getMessage());
            return 1;
        }
    }
    
    @SuppressWarnings("unchecked")
    private static List<Reward> getRewardsFromOriginal(me.arthed.smartgambling.games.slots.SlotMachine original) {
        try {
            Field field = original.getClass().getDeclaredField("rewards");
            field.setAccessible(true);
            return (List<Reward>) field.get(original);
        } catch (Exception e) {
            SmartGambling.getInstance().getLogger().warning("Failed to get rewards, using empty config: " + e.getMessage());
            return List.of();
        }
    }
    
    private static int getAnimationDurationFromOriginal(me.arthed.smartgambling.games.slots.SlotMachine original) {
        try {
            Field field = original.getClass().getDeclaredField("animationDuration");
            field.setAccessible(true);
            return field.getInt(original);
        } catch (Exception e) {
            SmartGambling.getInstance().getLogger().warning("Failed to get animationDuration, using default value 60: " + e.getMessage());
            return 60;
        }
    }
    
    private static int getAnimationStartingSpeedFromOriginal(me.arthed.smartgambling.games.slots.SlotMachine original) {
        try {
            Field field = original.getClass().getDeclaredField("animationStartingSpeed");
            field.setAccessible(true);
            return field.getInt(original);
        } catch (Exception e) {
            SmartGambling.getInstance().getLogger().warning("Failed to get animationStartingSpeed, using default value 3: " + e.getMessage());
            return 3;
        }
    }
}