package me.arthed.smartgambling.games.slots.objects.rewards;

import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.games.slots.objects.SlotItem;

public class RowReward extends Reward {
    private final SlotItem item;
    private final int amount;
    private final int startingLine;

    public RowReward(SlotItem item, int amount, int startingLine, CustomSound sound) {
        this.item = item;
        this.amount = amount;
        this.startingLine = startingLine;
        this.sound = sound;
    }

    public boolean check(SlotItem[] itemsOnLine) {
        if (this.startingLine >= 0) {
            return this.check(itemsOnLine, this.startingLine);
        } else {
            boolean result = false;

            for(int i = 0; i < itemsOnLine.length - this.amount + 1; ++i) {
                if (this.check(itemsOnLine, i)) {
                    result = true;
                    break;
                }
            }

            return result;
        }
    }

    public boolean check(SlotItem[] itemsOnLine, int startingLine) {
        for(int i = 0; i < this.amount; ++i) {
            if (this.item.itemStack == null) {
                if (!this.item.isEquivalent(itemsOnLine[startingLine + i])) {
                    return false;
                }
            } else if (!itemsOnLine[startingLine + i].isEquivalent(this.item)) {
                return false;
            }
        }

        return true;
    }
}