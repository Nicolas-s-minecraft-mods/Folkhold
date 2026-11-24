package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.construction.GhostPlacement;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageLand;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the land of a village outside its palisade: its builders build a field from the level 5 (see
 * {@link VillageLand}). The village of the tests has a palisade of logs around a square area in the middle of the
 * arena, with a gate of 3 fence gates on its north side. The nearest site to the heart is in front of this gate.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageLandGameTests {
    private static final int SIZE = 56;
    /** The corners of the area of the village structure: the palisade is on its edges. */
    private static final BlockPos AREA_MIN = new BlockPos(20, 1, 20);
    private static final BlockPos AREA_MAX = new BlockPos(35, 4, 35);
    private static final BlockPos HEART = new BlockPos(27, 1, 27);
    /** The middle fence gate of the gate of the palisade, on the north side. */
    private static final BlockPos GATE = new BlockPos(27, 1, 20);
    private static final ResourceLocation HOMES = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/homes");

    private VillageLandGameTests() {
    }

    /**
     * A village of the level 4 builds no field. At the level 5, its builders start a field outside the palisade: the
     * box of the field is {@link VillageLand#GAP} blocks or more from the area, and not in the lane in front of the
     * gate. The front of the field faces the area. After the construction, a villager walks from the gate out to the
     * edge of the arena: the field does not block the gate. The heart keeps the field: a second check starts no site
     * for the same field.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 20, batch = "village_land", skyAccess = true)
    public static void villageOfLevel5BuildsAFieldOutsideThePalisade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_LAND, true);
        TestSupport.buildFloor(helper, SIZE);
        buildPalisade(helper);
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        heart.ensureSettlement();
        BoundingBox area = BoundingBox.fromCorners(helper.absolutePos(AREA_MIN), helper.absolutePos(AREA_MAX));
        heart.setRepairReference(new VillageReference(List.of(), List.of(), Optional.of(area), List.of(),
                Optional.of(HOMES)));
        Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
        settlement.setBiome("minecraft:plains");
        try {
            settlement.setProgress(4, 0);
            heart.ensureGarrison(level.getGameTime());
            helper.assertTrue(VillageLand.check(level, heart).isEmpty(), "A village of the level 4 builds no field");
            settlement.setProgress(5, 0);
            Optional<BuilderSite> site = VillageLand.check(level, heart);
            helper.assertTrue(site.isPresent(), "A village of the level 5 builds a field");
            String name = site.get().template().getPath();
            helper.assertTrue(name.startsWith("village/field_"), "The site is a field: " + name);
            StructureTemplate template = level.getStructureManager().get(site.get().template()).orElseThrow();
            BoundingBox box = GhostPlacement.box(template.getSize(), site.get().rotation(), Mirror.NONE,
                    site.get().origin());
            helper.assertTrue(gap(area, box) >= VillageLand.GAP, "The field is outside the palisade: " + box
                    + ", area " + area);
            BoundingBox lane = VillageLand.lane(area, helper.absolutePos(GATE));
            helper.assertFalse(box.minX() <= lane.maxX() && box.maxX() >= lane.minX() && box.minZ() <= lane.maxZ()
                    && box.maxZ() >= lane.minZ(), "The field is not in front of the gate: " + box);
            helper.assertTrue(heart.landPieces().size() == 1, "The heart keeps the field: " + heart.landPieces());
            BuilderSites.stop(level);
            place(level, site.get(), template);
            helper.assertTrue(walksOut(helper), "A villager walks from the gate to the edge of the arena");
            helper.assertTrue(VillageLand.check(level, heart).isEmpty(), "One field at the level 5");
        } finally {
            BuilderSites.stop(level);
        }
        helper.succeed();
    }

    /** The palisade: 2 logs on each edge of the area, with a gate of 3 fence gates on the north side. */
    private static void buildPalisade(GameTestHelper helper) {
        for (int x = AREA_MIN.getX(); x <= AREA_MAX.getX(); x++) {
            for (int z = AREA_MIN.getZ(); z <= AREA_MAX.getZ(); z++) {
                boolean edge = x == AREA_MIN.getX() || x == AREA_MAX.getX() || z == AREA_MIN.getZ()
                        || z == AREA_MAX.getZ();
                if (!edge) {
                    continue;
                }
                for (int y = 1; y <= 2; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (z == GATE.getZ() && Math.abs(x - GATE.getX()) <= 1) {
                        helper.setBlock(pos, y == 1 ? Blocks.SPRUCE_FENCE_GATE.defaultBlockState()
                                .setValue(FenceGateBlock.FACING, Direction.NORTH) : Blocks.AIR.defaultBlockState());
                    } else {
                        helper.setBlock(pos, Blocks.SPRUCE_LOG.defaultBlockState()
                                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
                    }
                }
            }
        }
    }

    /** The end of the site: the blocks of the field are in place. */
    private static void place(ServerLevel level, BuilderSite site, StructureTemplate template) {
        StructurePlaceSettings settings = GhostPlacement.settings(site.rotation(), Mirror.NONE)
                .addProcessor(JigsawReplacementProcessor.INSTANCE);
        template.placeInWorld(level, site.origin(), site.origin(), settings, level.getRandom(), Block.UPDATE_CLIENTS);
    }

    /** The distance on the horizontal plane between a box and the area, in blocks: 0 when they touch. */
    private static int gap(BoundingBox area, BoundingBox box) {
        int dx = Math.max(area.minX() - box.maxX(), box.minX() - area.maxX());
        int dz = Math.max(area.minZ() - box.maxZ(), box.minZ() - area.maxZ());
        return Math.max(dx, dz) - 1;
    }

    /**
     * True if a villager walks on the floor of the arena from the front of the gate to an edge of the arena: through
     * the positions with free feet and head (no collision) at the level of the floor plus 1, outside the area.
     */
    private static boolean walksOut(GameTestHelper helper) {
        Deque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos start = GATE.north();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty()) {
            BlockPos pos = open.poll();
            if (pos.getX() == 0 || pos.getZ() == 0 || pos.getX() == SIZE - 1 || pos.getZ() == SIZE - 1) {
                return true;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(side);
                boolean inArea = next.getX() >= AREA_MIN.getX() && next.getX() <= AREA_MAX.getX()
                        && next.getZ() >= AREA_MIN.getZ() && next.getZ() <= AREA_MAX.getZ();
                if (!inArea && free(helper, next) && free(helper, next.above()) && seen.add(next)) {
                    open.add(next);
                }
            }
        }
        return false;
    }

    private static boolean free(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return helper.getLevel().getBlockState(absolute).getCollisionShape(helper.getLevel(), absolute).isEmpty();
    }
}
