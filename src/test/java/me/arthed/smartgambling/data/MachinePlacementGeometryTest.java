package me.arthed.smartgambling.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class MachinePlacementGeometryTest {
    private static final double EPSILON = 0.000001D;

    @Test
    void blackjackOffsetsRotateFromSouthReferenceWithoutMirroringPairsTogether() {
        double[] offset = {1.0D, -1.0D, 2.0D};
        assertArrayEquals(new double[]{1.0D, -1.0D, 2.0D},
                MachineData.orientedBlackjackOffset(offset, BlockFace.SOUTH), EPSILON);
        assertArrayEquals(new double[]{-1.0D, -1.0D, -2.0D},
                MachineData.orientedBlackjackOffset(offset, BlockFace.NORTH), EPSILON);
        assertArrayEquals(new double[]{2.0D, -1.0D, -1.0D},
                MachineData.orientedBlackjackOffset(offset, BlockFace.EAST), EPSILON);
        assertArrayEquals(new double[]{-2.0D, -1.0D, 1.0D},
                MachineData.orientedBlackjackOffset(offset, BlockFace.WEST), EPSILON);
    }

    @Test
    void blackjackSeatsAlwaysFaceOppositeDirections() {
        for (BlockFace direction : new BlockFace[]{
                BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            float host = MachineEntityFactory.yawForRole(direction, EntityRole.BLACKJACK_HOST_SEAT);
            float challenger = MachineEntityFactory.yawForRole(
                    direction, EntityRole.BLACKJACK_CHALLENGER_SEAT);
            float difference = Math.abs(host - challenger);
            assertEquals(180.0F, difference == 180.0F ? difference : 360.0F - difference, 0.001F);
        }
    }

    @Test
    void pokerSeatsAlwaysFaceOppositeDirections() {
        for (BlockFace direction : new BlockFace[]{
                BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            float host = MachineEntityFactory.yawForRole(direction, EntityRole.POKER_HOST_SEAT);
            float challenger = MachineEntityFactory.yawForRole(
                    direction, EntityRole.POKER_CHALLENGER_SEAT);
            float difference = Math.abs(host - challenger);
            assertEquals(180.0F, difference == 180.0F ? difference : 360.0F - difference, 0.001F);
        }
    }
}
