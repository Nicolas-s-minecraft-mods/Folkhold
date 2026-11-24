package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.village.structure.PierBlocks;
import io.github.nicolasgoyon.folkhold.world.village.structure.PierPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.PortPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the exits of the water gates: the pier ({@link PierPiece}), the bridge between 2 walls and the port
 * ({@link PortPiece}). The tests build a pool on a floor of dirt, with walls of stone around it, and place the pieces
 * over it.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PierGameTests {
    private static final int LENGTH = 8;
    private static final int DECK_Y = 3;
    private static final PierBlocks BLOCKS = new PierBlocks(Blocks.SPRUCE_PLANKS.defaultBlockState(),
            Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y),
            Blocks.SPRUCE_FENCE.defaultBlockState(), Blocks.SPRUCE_STAIRS.defaultBlockState());
    /** The Y of the top block of the water of the pools: the water is at Y 1 and 2. */
    private static final int WATER_TOP = 2;
    /** The steps of a deck at {@link #DECK_Y}: from the deck down to one block under the top block of the water. */
    private static final int STEPS = DECK_Y - (WATER_TOP - 1) + 1;

    private PierGameTests() {
    }

    /**
     * The pier has a deck of 3 blocks over the water. On the shore, the deck goes through the sand. The posts go under
     * the two sides of the deck every 3 rows and under the last row, down to the floor of the pool. The rails are on
     * the two sides, except on the first row.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "village_pier")
    public static void pierHasADeckPostsAndRails(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < LENGTH + 2; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
            }
        }
        // A shore of sand on the first 2 rows, at the level of the deck and above it.
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 2; z++) {
                helper.setBlock(new BlockPos(x, DECK_Y, z), Blocks.SAND);
                helper.setBlock(new BlockPos(x, DECK_Y + 1, z), Blocks.SAND);
            }
        }
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, DECK_Y, 1));
        PierPiece pier = new PierPiece(start.getX(), start.getZ(), 0, 1, LENGTH, start.getY(), BLOCKS);

        pier.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(),
                pier.getBoundingBox(), new ChunkPos(start), start);

        for (int row = 0; row < LENGTH; row++) {
            boolean postRow = row % PierPiece.POST_SPACING == 0 || row == LENGTH - 1;
            for (int side = -1; side <= 1; side++) {
                BlockPos deck = new BlockPos(2 + side, DECK_Y, 1 + row);
                helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, deck);
                if (side == 0) {
                    continue;
                }
                if (row > 0) {
                    helper.assertBlockPresent(Blocks.SPRUCE_FENCE, deck.above());
                } else {
                    helper.assertBlockNotPresent(Blocks.SPRUCE_FENCE, deck.above());
                }
                for (int y = 1; y < DECK_Y; y++) {
                    BlockPos post = new BlockPos(2 + side, y, 1 + row);
                    if (postRow) {
                        helper.assertBlockPresent(Blocks.SPRUCE_LOG, post);
                    } else {
                        helper.assertBlockPresent(Blocks.WATER, post);
                    }
                }
            }
        }
        helper.assertBlockPresent(Blocks.WATER, new BlockPos(2, 2, 1));
        helper.assertBlockPresent(Blocks.DIRT, new BlockPos(1, 0, 1));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, DECK_Y + 1, 1));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, DECK_Y + 1, 2));
        helper.succeed();
    }

    /**
     * A pig in the water next to a pier with steps reaches the deck on foot: it swims to the lowest step and walks up
     * the flight. The pig has no goal but its float goal, and its navigation goes to the deck.
     */
    @GameTest(template = "arena_large", timeoutTicks = 400, batch = "village_pier_walk")
    public static void aMobInTheWaterClimbsTheStepsOntoTheDeck(GameTestHelper helper) {
        int startX = 3;
        int startZ = 2;
        pool(helper, 1, 1, 11, startZ + LENGTH + 6);
        BlockPos start = helper.absolutePos(new BlockPos(startX, DECK_Y, startZ));
        int seaLevel = helper.absolutePos(new BlockPos(0, WATER_TOP + 1, 0)).getY();
        PierPiece pier = new PierPiece(start.getX(), start.getZ(), 0, 1, LENGTH, start.getY(), BLOCKS,
                seaLevel - 2, false);
        place(helper, pier);
        int lastRow = startZ + LENGTH - 1;
        helper.assertTrue(pier.steps() == STEPS, "The pier has " + STEPS + " steps: " + pier.steps());
        // The lowest step is under the water, next to the last row of the pier.
        helper.assertBlockPresent(Blocks.SPRUCE_STAIRS, new BlockPos(startX + 2, WATER_TOP - 1, lastRow));
        helper.assertBlockPresent(Blocks.SPRUCE_STAIRS, new BlockPos(startX + 2, DECK_Y, lastRow - STEPS + 1));
        helper.assertBlockNotPresent(Blocks.SPRUCE_FENCE, new BlockPos(startX + 1, DECK_Y + 1, lastRow - STEPS + 1));
        // The moored boat floats next to the row before the top step, on the side of the steps.
        assertBoat(helper, new BlockPos(startX + 2, WATER_TOP + 1, lastRow - STEPS));
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(startX + 4, WATER_TOP, lastRow + 2));
        pig.goalSelector.getAvailableGoals().removeIf(wrapped -> !(wrapped.getGoal() instanceof FloatGoal));
        BlockPos target = helper.absolutePos(new BlockPos(startX, DECK_Y + 1, startZ + 2));
        helper.onEachTick(() -> pig.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                1.0));
        helper.succeedWhen(() -> {
            // The position from the origin of the test (the test has no rotation).
            BlockPos feet = pig.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO));
            helper.assertTrue(pig.onGround() && feet.getY() == DECK_Y + 1 && Math.abs(feet.getX() - startX) <= 1
                    && feet.getZ() >= startZ && feet.getZ() <= lastRow, "The pig is not on the deck: " + feet);
        });
    }

    /**
     * The water gate of a wall and the gate of its ring on the same water: the bridge goes from the row after the outer
     * row of the inner gate to the row before the inner row of the ring gate (see {@link VillageWall#bridge}). The deck
     * has no rails on its 2 end rows, rails on its other rows, and a flight of steps in its middle.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 20, batch = "village_pier")
    public static void theBridgeJoinsTheGatesOfTwoWalls(GameTestHelper helper) {
        int middle = 28;
        int innerLine = 52;
        pool(helper, middle - 4, innerLine - WallLayout.MIN_BAND - 2, middle + 4, innerLine + 2);
        BlockPos min = helper.absolutePos(new BlockPos(middle - 20, 0, innerLine + 5));
        BlockPos max = helper.absolutePos(new BlockPos(middle + 20, 0, innerLine + 48));
        int gateX = helper.absolutePos(new BlockPos(middle, 0, 0)).getX();
        WallLayout inner = WallLayout.plan(List.of(new WallLayout.Box(Math.min(min.getX(), max.getX()),
                Math.min(min.getZ(), max.getZ()), Math.max(min.getX(), max.getX()), Math.max(min.getZ(), max.getZ()))),
                4, 40, List.of(new Gate(Side.NORTH, gateX)));
        WallLayout ring = WallLayout.ring(inner, WallLayout.MIN_BAND);
        Gate innerGate = inner.gates().get(0);
        Gate ringGate = ring.gates().get(0);
        int deckY = helper.absolutePos(new BlockPos(0, DECK_Y, 0)).getY();
        int waterTop = helper.absolutePos(new BlockPos(0, WATER_TOP, 0)).getY();
        VillageWall.Wall wall = new VillageWall.Wall(inner, Map.of(innerGate, deckY), Set.of(innerGate), List.of());

        PierPiece bridge = VillageWall.bridge(ring, ringGate, wall, (x, z) -> waterTop, waterTop + 1, BLOCKS)
                .orElseThrow(() -> new AssertionError("The 2 gates on the same water need a bridge"));
        place(helper, bridge);

        int first = innerLine - 2;
        int last = innerLine - WallLayout.MIN_BAND + 2;
        helper.assertTrue(bridge.length() == first - last + 1, "The bridge has " + (first - last + 1) + " rows: "
                + bridge.length());
        for (int z = last; z <= first; z++) {
            for (int x = middle - 1; x <= middle + 1; x++) {
                helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, new BlockPos(x, DECK_Y, z));
            }
        }
        for (int z : new int[] {first, last}) {
            helper.assertBlockNotPresent(Blocks.SPRUCE_FENCE, new BlockPos(middle - 1, DECK_Y + 1, z));
            helper.assertBlockNotPresent(Blocks.SPRUCE_FENCE, new BlockPos(middle + 1, DECK_Y + 1, z));
        }
        helper.assertBlockPresent(Blocks.SPRUCE_FENCE, new BlockPos(middle - 1, DECK_Y + 1, first - 1));
        helper.assertBlockPresent(Blocks.SPRUCE_FENCE, new BlockPos(middle + 1, DECK_Y + 1, last + 1));
        int top = first - bridge.topStep();
        helper.assertBlockPresent(Blocks.SPRUCE_STAIRS, new BlockPos(middle + 2, DECK_Y, top));
        helper.assertBlockPresent(Blocks.SPRUCE_STAIRS, new BlockPos(middle + 2, WATER_TOP - 1, top - STEPS + 1));
        helper.assertBlockNotPresent(Blocks.SPRUCE_FENCE, new BlockPos(middle + 1, DECK_Y + 1, top));
        helper.succeed();
    }

    /**
     * A port: a quay of 4 rows along the wall and 3 jetties out over the water. The quay has rails on its edges, except
     * in front of the gate and at the start of each jetty. Each jetty has a lantern and a mooring post at its end, and
     * a flight of steps on its side of the higher X.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 20, batch = "village_pier")
    public static void thePortHasAQuayJettiesAndSteps(GameTestHelper helper) {
        int middle = 28;
        int line = 2;
        pool(helper, middle - PortPiece.QUAY_HALF - 2, line + 1, middle + PortPiece.QUAY_HALF + 2,
                line + PortPiece.REACH + 2);
        BlockPos gate = helper.absolutePos(new BlockPos(middle, DECK_Y, line));
        int seaLevel = helper.absolutePos(new BlockPos(0, WATER_TOP + 1, 0)).getY();
        place(helper, new PortPiece(gate.getX(), gate.getZ(), 0, 1, gate.getY(), seaLevel - 2, BLOCKS));

        int quayFirst = line + PortPiece.QUAY_FROM;
        int quayLast = quayFirst + PortPiece.QUAY_DEPTH - 1;
        for (int z = quayFirst; z <= quayLast; z++) {
            for (int x = middle - PortPiece.QUAY_HALF; x <= middle + PortPiece.QUAY_HALF; x++) {
                helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, new BlockPos(x, DECK_Y, z));
            }
        }
        // The gate opens on the quay, and the quay opens on each jetty.
        helper.assertBlockNotPresent(Blocks.SPRUCE_FENCE, new BlockPos(middle, DECK_Y + 1, quayFirst));
        helper.assertBlockPresent(Blocks.SPRUCE_FENCE, new BlockPos(middle + 4, DECK_Y + 1, quayLast));
        helper.assertBlockPresent(Blocks.LANTERN, new BlockPos(middle - PortPiece.QUAY_HALF, DECK_Y + 2, quayLast));
        helper.assertBlockPresent(Blocks.BARREL, new BlockPos(middle + PortPiece.QUAY_HALF - 1, DECK_Y + 1,
                quayFirst + 1));
        int end = line + PortPiece.REACH;
        for (int offset : new int[] {-PortPiece.JETTY_OFFSET, 0, PortPiece.JETTY_OFFSET}) {
            int x = middle + offset;
            helper.assertBlockNotPresent(Blocks.SPRUCE_FENCE, new BlockPos(x, DECK_Y + 1, quayLast));
            for (int z = quayLast + 1; z <= end; z++) {
                helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, new BlockPos(x, DECK_Y, z));
            }
            helper.assertBlockPresent(Blocks.LANTERN, new BlockPos(x - 1, DECK_Y + 2, end));
            helper.assertBlockPresent(Blocks.SPRUCE_LOG, new BlockPos(x + 1, DECK_Y + 1, end));
            helper.assertBlockPresent(Blocks.SPRUCE_STAIRS, new BlockPos(x + 2, DECK_Y, end - STEPS + 1));
            helper.assertBlockPresent(Blocks.SPRUCE_STAIRS, new BlockPos(x + 2, WATER_TOP - 1, end));
            assertBoat(helper, new BlockPos(x - PortPiece.BOAT_SIDE, WATER_TOP + 1, end));
        }
        helper.assertTrue(boats(helper).size() == PortPiece.JETTIES, "The port must have " + PortPiece.JETTIES
                + " boats: " + boats(helper).size());
        helper.succeed();
    }

    /**
     * A pool from a corner to another corner: a floor of dirt, water at Y 1 and 2, and walls of stone around it up to
     * 2 blocks above the level of the decks: a mob cannot go out of the pool over them.
     */
    private static void pool(GameTestHelper helper, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                boolean wall = x < minX || x > maxX || z < minZ || z > maxZ;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT);
                for (int y = 1; y <= DECK_Y + 2; y++) {
                    Block block = wall ? Blocks.STONE : y <= WATER_TOP ? Blocks.WATER : Blocks.AIR;
                    helper.setBlock(new BlockPos(x, y, z), block);
                }
            }
        }
    }

    /** The boats in the area of the test. */
    private static List<Boat> boats(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(Boat.class, helper.getBounds());
    }

    /** A spruce boat floats on the water at a position of the test, and the test removes it at its end. */
    private static void assertBoat(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        List<Boat> boats = boats(helper);
        TestSupport.atTestEnd(helper, () -> boats.forEach(Boat::discard));
        helper.assertTrue(boats.stream().anyMatch(boat -> boat.blockPosition().equals(absolute)
                && boat.getVariant() == Boat.Type.SPRUCE), "A spruce boat must float at " + pos.toShortString()
                + ": " + boats.stream().map(boat -> boat.blockPosition().toShortString()).toList());
    }

    /** Places a piece in the level, in its own box. */
    private static void place(GameTestHelper helper, StructurePiece piece) {
        ServerLevel level = helper.getLevel();
        BlockPos pivot = piece.getBoundingBox().getCenter();
        piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(),
                piece.getBoundingBox(), new ChunkPos(pivot), pivot);
    }
}
