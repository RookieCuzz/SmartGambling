package me.arthed.smartgambling.games.slots;

import me.arthed.smartgambling.games.slots.objects.SlotItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotMachineForcedResultTest {

    @Test
    void forcedResultReplacesEverySettlementItemInOrder() {
        SlotItem first = new SlotItem(null);
        SlotItem second = new SlotItem(null);
        SlotItem third = new SlotItem(null);
        SlotItem[] finalItems = {
                new SlotItem(null),
                new SlotItem(null),
                new SlotItem(null)
        };
        boolean[] applied = new boolean[3];

        SlotItem[] forced = {first, second, third};
        assertFalse(SlotMachine.copyForcedLineResult(forced, finalItems, applied, 0));
        assertFalse(SlotMachine.copyForcedLineResult(forced, finalItems, applied, 1));
        assertTrue(SlotMachine.copyForcedLineResult(forced, finalItems, applied, 2));

        assertArrayEquals(new SlotItem[]{first, second, third}, finalItems);
    }

    @Test
    void forcedResultMustExactlyMatchReelCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SlotMachine.copyForcedLineResult(
                        new SlotItem[]{new SlotItem(null)},
                        new SlotItem[2],
                        new boolean[2],
                        0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SlotMachine.copyForcedLineResult(
                        new SlotItem[0],
                        new SlotItem[0],
                        new boolean[0],
                        0
                )
        );
    }

    @Test
    void forcedResultRejectsMissingSymbolsBeforeMutatingSettlement() {
        SlotItem existing = new SlotItem(null);
        SlotItem[] finalItems = {existing, existing};

        assertThrows(
                NullPointerException.class,
                () -> SlotMachine.copyForcedLineResult(
                        new SlotItem[]{new SlotItem(null), null},
                        finalItems,
                        new boolean[2],
                        1
                )
        );
        assertArrayEquals(new SlotItem[]{existing, existing}, finalItems);
    }

    @Test
    void queuedAnimationFrameIsIgnoredAfterStopTaskAppliedItsReel() {
        SlotItem forced = new SlotItem(null);
        SlotItem[] forcedItems = {forced};
        SlotItem[] finalItems = {new SlotItem(null)};
        boolean[] applied = {false};

        assertTrue(SlotMachine.isForcedLinePending(forcedItems, applied, 0));
        assertTrue(SlotMachine.copyForcedLineResult(forcedItems, finalItems, applied, 0));
        assertFalse(SlotMachine.isForcedLinePending(forcedItems, applied, 0));
        assertArrayEquals(new SlotItem[]{forced}, finalItems);
    }

    @Test
    void forcedTerminalFrameCannotBeOverwrittenByDependentAnimation() {
        assertTrue(SlotMachine.shouldAnimateDependent(1, false));
        assertFalse(SlotMachine.shouldAnimateDependent(1, true));
        assertFalse(SlotMachine.shouldAnimateDependent(0, false));
        assertFalse(SlotMachine.shouldAnimateDependent(2, false));
    }

    @Test
    void everyScheduledReelStopPrecedesGlobalSpinEnd() {
        int animationDuration = 80;
        int reelCount = 5;
        long lastReelStop = SlotMachine.reelStopDelay(animationDuration, reelCount - 1);
        long globalSpinEnd = SlotMachine.spinEndDelay(animationDuration, reelCount);

        assertTrue(lastReelStop < globalSpinEnd);
        assertEquals(7L, globalSpinEnd - lastReelStop);
    }
}
