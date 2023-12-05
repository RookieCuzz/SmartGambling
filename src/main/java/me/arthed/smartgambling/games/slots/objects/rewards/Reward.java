package me.arthed.smartgambling.games.slots.objects.rewards;

import java.util.List;
import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.games.slots.objects.SlotItem;

public abstract class Reward {
    public float moneyMultiplier;
    public List<String> winningCommands;
    public CustomSound sound;

    public boolean check(SlotItem[] items) {
        return false;
    }
}
