package io.github.nicolasgoyon.folkhold.world.village;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** The distance of the welcome message of a village ({@link VillageHearts#isNear}). */
class VillageMessageDistanceTest {
    private static final BlockPos HEART = new BlockPos(0, 64, 0);
    private static final double RADIUS = 50.0;
    private static final int MARGIN = 16;

    /** A hamlet: its wall is at about 20 blocks. The radius of the message gives the distance. */
    @Test
    void hamletUsesTheRadius() {
        Optional<BoundingBox> hamlet = Optional.of(new BoundingBox(-20, 40, -20, 20, 90, 20));
        assertTrue(VillageHearts.isNear(HEART, hamlet, new BlockPos(48, 64, 0), RADIUS, MARGIN));
        assertTrue(VillageHearts.isNear(HEART, hamlet, new BlockPos(36, 64, 0), RADIUS, MARGIN));
        assertFalse(VillageHearts.isNear(HEART, hamlet, new BlockPos(52, 64, 0), RADIUS, MARGIN));
    }

    /** A capital: its wall is at about 100 blocks. The message shows at the margin out of the wall. */
    @Test
    void capitalUsesItsStructureArea() {
        Optional<BoundingBox> capital = Optional.of(new BoundingBox(-100, 30, -90, 100, 110, 90));
        assertTrue(VillageHearts.isNear(HEART, capital, new BlockPos(115, 64, 0), RADIUS, MARGIN),
                "15 blocks out of the east side");
        assertTrue(VillageHearts.isNear(HEART, capital, new BlockPos(0, 64, -105), RADIUS, MARGIN),
                "15 blocks out of the north side");
        assertFalse(VillageHearts.isNear(HEART, capital, new BlockPos(117, 64, 0), RADIUS, MARGIN),
                "17 blocks out of the east side");
        assertFalse(VillageHearts.isNear(HEART, capital, new BlockPos(112, 64, 102), RADIUS, MARGIN),
                "At a corner: 12 and 12 blocks out, about 17 blocks");
        assertFalse(VillageHearts.isNear(HEART, capital, new BlockPos(110, 140, 0), RADIUS, MARGIN),
                "Far above the heart");
    }

    /** A heart with no structure area: only the radius. */
    @Test
    void heartWithNoStructureUsesTheRadius() {
        assertTrue(VillageHearts.isNear(HEART, Optional.empty(), new BlockPos(30, 64, 30), RADIUS, MARGIN));
        assertFalse(VillageHearts.isNear(HEART, Optional.empty(), new BlockPos(60, 64, 0), RADIUS, MARGIN));
    }
}
