package me.arthed.smartgambling.integrations;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves CraftEngine item IDs without falling back to legacy
 * Material/CustomModelData pairs.
 */
public final class CraftEngineItemResolver {
    private CraftEngineItemResolver() {
    }

    public static ItemStack build(String id, String configPath) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Missing CraftEngine item ID at '" + configPath + "'.");
        }

        BukkitItemDefinition definition = CraftEngineItems.byId(id);
        if (definition == null) {
            throw new IllegalStateException(
                    "Unknown CraftEngine item '" + id + "' at '" + configPath + "'. "
                            + "Install SmartGambling-CraftEngine/smartgambling in "
                            + "plugins/CraftEngine/resources and run /ce reload all."
            );
        }
        return definition.buildBukkitItem();
    }
}
