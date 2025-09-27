package me.arthed.smartgambling.games.slots.objects;

import java.util.HashSet;
import java.util.Set;
import me.arthed.smartgambling.games.slots.objects.SlotItem;
import org.bukkit.inventory.ItemStack;

public class SlotItem {
    public  ItemStack itemStack;
    public  Set<SlotItem> equivalents;

    public SlotItem(ItemStack itemStack) {
        this.itemStack = itemStack;
        this.equivalents = new HashSet();
    }

    public boolean isEquivalent(SlotItem item) {
        return item.equals(this) ? true : this.equivalents.contains(item);
    }
}
 