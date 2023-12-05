package me.arthed.smartgambling.games.slots.objects.rewards;

import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.games.slots.objects.SlotItem;

public class ExactMatchReward extends Reward {
    private final SlotItem[] requiredCombination;
    private final int startingLine;

    public ExactMatchReward(SlotItem[] requiredCombination, int startingLine, CustomSound sound) {
        this.requiredCombination = requiredCombination;
        this.startingLine = startingLine;
        this.sound = sound;
    }

    public boolean check(SlotItem[] itemsOnLine) {
        if (this.startingLine >= 0) {
            return this.check(itemsOnLine, this.startingLine);
        } else {
            boolean result = false;

            for(int i = 0; i < itemsOnLine.length - this.requiredCombination.length + 1; ++i) {
                if (this.check(itemsOnLine, i)) {
                    result = true;
                    break;
                }
            }

            return result;
        }
    }

    public boolean check(SlotItem[] itemsOnLine, int startingLine) {
        for(int i = 0; i < this.requiredCombination.length; ++i) {
            if (!this.requiredCombination[i].isEquivalent(itemsOnLine[startingLine + i])) {
                return false;
            }
        }

        return true;
    }
}
 