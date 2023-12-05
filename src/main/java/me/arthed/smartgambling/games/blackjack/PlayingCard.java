package me.arthed.smartgambling.games.blackjack;

import me.arthed.smartgambling.SmartGambling;
import org.bukkit.inventory.ItemStack;

public record PlayingCard(int value, ItemStack[] items) {
public ItemStack getRandomItem() {
        return this.items[SmartGambling.getInstance().random.nextInt(this.items.length)];
        }
        }
