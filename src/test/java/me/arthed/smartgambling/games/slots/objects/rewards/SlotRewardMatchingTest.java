package me.arthed.smartgambling.games.slots.objects.rewards;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.arthed.smartgambling.games.slots.objects.SlotItem;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class SlotRewardMatchingTest {
    @Test
    void rowRewardAcceptsWildAsAReplacementForARegularSymbol() {
        SlotItem grape = symbol();
        SlotItem wild = symbol();
        wild.equivalents.add(grape);

        RowReward reward = new RowReward(grape, 3, 0, null);

        assertTrue(reward.check(new SlotItem[]{grape, wild, grape}));
    }

    @Test
    void rowCategoryAcceptsAnyOfItsDirectMembers() {
        SlotItem grape = symbol();
        SlotItem watermelon = symbol();
        SlotItem fruitCategory = category(grape, watermelon);

        RowReward reward = new RowReward(fruitCategory, 3, 0, null);

        assertTrue(reward.check(new SlotItem[]{grape, watermelon, grape}));
    }

    @Test
    void rowCategoryAcceptsWildThatCanReplaceACategoryMember() {
        SlotItem grape = symbol();
        SlotItem watermelon = symbol();
        SlotItem wild = symbol();
        SlotItem fruitCategory = category(grape, watermelon);
        wild.equivalents.add(grape);
        wild.equivalents.add(watermelon);

        RowReward reward = new RowReward(fruitCategory, 3, 0, null);

        assertTrue(reward.check(new SlotItem[]{grape, wild, watermelon}));
    }

    @Test
    void rowCategoryRejectsAnUnrelatedSymbol() {
        SlotItem grape = symbol();
        SlotItem watermelon = symbol();
        SlotItem lemon = symbol();
        SlotItem fruitCategory = category(grape, watermelon);

        RowReward reward = new RowReward(fruitCategory, 3, 0, null);

        assertFalse(reward.check(new SlotItem[]{grape, lemon, watermelon}));
    }

    @Test
    void exactRewardAcceptsWildAsAReplacementForARegularRequirement() {
        SlotItem grape = symbol();
        SlotItem lemon = symbol();
        SlotItem wild = symbol();
        wild.equivalents.add(grape);

        ExactMatchReward reward = new ExactMatchReward(
                new SlotItem[]{grape, lemon},
                0,
                null
        );

        assertTrue(reward.check(new SlotItem[]{wild, lemon}));
    }

    @Test
    void exactRewardCategoryAcceptsWildThatCanReplaceACategoryMember() {
        SlotItem grape = symbol();
        SlotItem watermelon = symbol();
        SlotItem wild = symbol();
        SlotItem fruitCategory = category(grape, watermelon);
        wild.equivalents.add(watermelon);

        ExactMatchReward reward = new ExactMatchReward(
                new SlotItem[]{fruitCategory, grape},
                0,
                null
        );

        assertTrue(reward.check(new SlotItem[]{wild, grape}));
    }

    @Test
    void regularSymbolCannotReplaceARequiredWildInEitherRewardType() {
        SlotItem grape = symbol();
        SlotItem wild = symbol();
        wild.equivalents.add(grape);

        assertFalse(new RowReward(wild, 1, 0, null).check(new SlotItem[]{grape}));
        assertFalse(new ExactMatchReward(new SlotItem[]{wild}, 0, null)
                .check(new SlotItem[]{grape}));
    }

    @Test
    void rowRewardHonorsFixedStartAndScansWhenStartIsUnspecified() {
        SlotItem grape = symbol();
        SlotItem lemon = symbol();
        SlotItem[] result = {grape, lemon, grape, grape};

        assertFalse(new RowReward(grape, 2, 0, null).check(result));
        assertTrue(new RowReward(grape, 2, 2, null).check(result));
        assertTrue(new RowReward(grape, 2, -1, null).check(result));
    }

    @Test
    void exactRewardHonorsFixedStartAndScansWhenStartIsUnspecified() {
        SlotItem grape = symbol();
        SlotItem lemon = symbol();
        SlotItem[] result = {grape, grape, lemon};
        SlotItem[] required = {grape, lemon};

        assertFalse(new ExactMatchReward(required, 0, null).check(result));
        assertTrue(new ExactMatchReward(required, 1, null).check(result));
        assertTrue(new ExactMatchReward(required, -1, null).check(result));
    }

    private static SlotItem symbol() {
        return new SlotItem(new TestItemStack());
    }

    private static SlotItem category(SlotItem... members) {
        SlotItem category = new SlotItem(null);
        for (SlotItem member : members) {
            category.equivalents.add(member);
        }
        return category;
    }

    private static final class TestItemStack extends ItemStack {
        private TestItemStack() {
            super();
        }
    }
}
