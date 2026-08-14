package me.arthed.smartgambling.games.slots.objects;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.inventory.ItemStack;

public class SlotItem {
    public ItemStack itemStack;
    public Set<SlotItem> equivalents;

    public SlotItem(ItemStack itemStack) {
        this.itemStack = itemStack;
        this.equivalents = new HashSet<>();
    }

    public boolean isEquivalent(SlotItem item) {
        return item != null && (item.equals(this) || this.equivalents.contains(item));
    }

    /**
     * Returns whether this rolled item satisfies a configured reward requirement.
     * Equivalences belong to the rolled item (for example, a Wild), while a
     * category is represented by an item without an ItemStack whose equivalents
     * are the category members.
     */
    public boolean matchesRequirement(SlotItem requirement) {
        if (requirement == null) {
            return false;
        }
        if (requirement.itemStack != null) {
            return this.isEquivalent(requirement);
        }
        for (SlotItem categoryMember : requirement.equivalents) {
            if (this.isEquivalent(categoryMember)) {
                return true;
            }
        }
        return false;
    }
}
