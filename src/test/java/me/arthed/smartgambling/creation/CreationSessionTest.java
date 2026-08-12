package me.arthed.smartgambling.creation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.arthed.smartgambling.games.common.machine.Machine;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class CreationSessionTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Machine MACHINE = new NoopMachine();

    @Test
    void rotatesAllFourCardinalDirections() {
        CreationSession session = session(100L, 1_000L);
        session.setOrigin(position(0, 64, 0), BlockFace.NORTH, 100L);
        session.rotate(false, 110L);
        assertEquals(BlockFace.EAST, session.direction());
        session.rotate(false, 120L);
        assertEquals(BlockFace.SOUTH, session.direction());
        session.rotate(false, 130L);
        assertEquals(BlockFace.WEST, session.direction());
        session.rotate(false, 140L);
        assertEquals(BlockFace.NORTH, session.direction());
        session.rotate(true, 150L);
        assertEquals(BlockFace.WEST, session.direction());
    }

    @Test
    void enforcesOriginWorldRadiusLimitAndStableOrder() {
        CreationSession session = session(0L, 10_000L);
        assertEquals(CreationSession.AddResult.NO_ORIGIN,
                session.addInteraction(position(1, 64, 0), 2, 4.0D, 1L));
        session.setOrigin(position(0, 64, 0), BlockFace.NORTH, 2L);
        assertEquals(CreationSession.AddResult.ADDED,
                session.addInteraction(position(1, 64, 0), 2, 4.0D, 3L));
        assertEquals(CreationSession.AddResult.ALREADY_SELECTED,
                session.addInteraction(position(1, 64, 0), 2, 4.0D, 4L));
        assertEquals(CreationSession.AddResult.TOO_FAR,
                session.addInteraction(position(5, 64, 0), 2, 4.0D, 5L));
        assertEquals(CreationSession.AddResult.ADDED,
                session.addInteraction(position(2, 64, 0), 2, 4.0D, 6L));
        assertEquals(CreationSession.AddResult.LIMIT_REACHED,
                session.addInteraction(position(3, 64, 0), 2, 4.0D, 7L));
        assertEquals(2, session.interactionCount());
        assertEquals(3, session.totalBlockCount());
        assertEquals(1, session.interactionPositions().iterator().next().x());
    }

    @Test
    void changingOriginClearsInteractionsAndTimeoutUsesLastActivity() {
        CreationSession session = session(100L, 500L);
        session.setOrigin(position(0, 64, 0), BlockFace.NORTH, 100L);
        session.addInteraction(position(1, 64, 0), 5, 5.0D, 200L);
        session.setOrigin(position(2, 64, 0), BlockFace.SOUTH, 300L);
        assertEquals(0, session.interactionCount());
        assertFalse(session.expired(799L));
        assertTrue(session.expired(800L));
    }

    private static CreationSession session(long now, long timeout) {
        return new CreationSession(UUID.randomUUID(), "slotexample", MACHINE, now, timeout);
    }

    private static BlockPosition position(int x, int y, int z) {
        return new BlockPosition(WORLD, "world", x, y, z);
    }

    private static final class NoopMachine implements Machine {
        @Override public void open(Player player, me.arthed.smartgambling.games.common.machine.OpenInterface open) {}
        @Override public void close(Player player, Inventory inventory) {}
        @Override public void inventoryClick(InventoryClickEvent event) {}
        @Override public ItemStack getMachineItem() { return null; }
        @Override public double[] getMachineEntityOffset() { return new double[]{0, 0, 0}; }
    }
}
