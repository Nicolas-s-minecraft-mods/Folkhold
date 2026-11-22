package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.RepairRules;
import io.github.nicolasgoyon.folkhold.world.village.StoneWall;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHearts;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairData;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairs;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.WallSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The builders change the first wall of a village into a stone wall at the village tier (see {@link StoneWall}). */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoneWallGameTests {
    private static final int SIZE = 32;
    private static final BlockPos HEART = new BlockPos(16, 1, 24);
    private static final BlockPos TOWER = new BlockPos(2, 1, 2);
    private static final BlockPos GATE = new BlockPos(20, 1, 2);
    /** The first column of the segment of the palisade, along X. */
    private static final BlockPos SEGMENT = new BlockPos(2, 1, 14);
    private static final int COLUMNS = 5;
    private static final int LOGS = 4;
    private static final int BUILDERS = 2;

    private StoneWallGameTests() {
    }

    /**
     * A first wall of the base variant (a tower, a gate and a segment of 5 columns): at the level 4 (hamlet), the
     * builders start no site. At the level 5 (village), they change the tower, the gate and the segment into stone,
     * with one site for each part. Then the reference of the repairs has the stone wall: the repairs see no damage,
     * and a broken stone block is damage with its stone block. A level that goes down does not change the wall back.
     */
    @GameTest(template = "arena_large", timeoutTicks = 12000, batch = "stone_wall")
    public static void villageTierChangesThePalisadeIntoAStoneWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.buildFloor(helper, SIZE);
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_BUILDER_TICKS, 1);
        // The test is about the parts of the wall: the materials are free.
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_FREE_MATERIALS, true);
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        WallSettings wall = structure.wall();
        WallSettings.StoneWall stone = wall.stone().orElseThrow();
        VillageReference.Piece tower = place(helper, wall.tower(), TOWER);
        VillageReference.Piece gate = place(helper, wall.gate(), GATE);
        VillageReference.Palisade segment = palisade(helper, wall);
        BoundingBox area = BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO),
                helper.absolutePos(new BlockPos(SIZE - 1, 16, SIZE - 1)));

        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        BlockPos heartPos = heart.getBlockPos();
        heart.ensureSettlement();
        VillageHearts.register(level, heartPos);
        VillageReference reference = new VillageReference(List.of(tower, gate), List.of(segment), Optional.of(area));
        heart.setRepairReference(reference);
        heart.stoneWall().clear();
        heart.stoneWall().setFirstWall(new StoneWall.FirstWall(List.of(tower), List.of(gate), List.of(segment),
                wall.palisade(), stone));
        Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
        Settlements.startEconomy(level.getServer(), settlement);
        settlement.setProgress(4, 0);
        List<Villager> builders = new ArrayList<>();
        for (int i = 0; i < BUILDERS; i++) {
            Villager builder = VillageFolk.spawn(level, heartPos, ModProfessions.BUILDER.get());
            helper.assertTrue(builder != null, "The builder must spawn");
            builders.add(builder);
        }
        long[] time = {TestSupport.NOON};
        helper.onEachTick(() -> TestSupport.setDayTimeForTest(helper, time[0]));
        TestSupport.atTestEnd(helper, () -> {
            BuilderSites.stop(level);
            builders.forEach(Entity::discard);
            heart.stoneWall().clear();
            VillageRepairData.get(level).forget(heartPos);
            level.setBlock(heartPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        });
        int[] sites = {0};
        BlockPos broken = segment.start().above(2);

        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertTrue(StoneWall.check(level, heart).isEmpty(), "A hamlet must start no wall site");
                    helper.assertTrue(heart.stoneWall().isEmpty(), "A hamlet has no stone part");
                    settlement.setProgress(5, 0);
                })
                .thenWaitUntil(() -> {
                    if (BuilderSites.of(level, heartPos).isEmpty()) {
                        Optional<BuilderSite> site = StoneWall.check(level, heart);
                        site.ifPresent(found -> {
                            helper.assertTrue(found.isRepair() && !found.repairPositions().isEmpty(),
                                    "A wall site changes the blocks of one part: " + found.repairPositions().size());
                            sites[0]++;
                        });
                    }
                    List<BuilderSite> open = BuilderSites.of(level, heartPos);
                    helper.assertTrue(open.isEmpty() && heart.stoneWall().pieces().size() == 2
                            && heart.stoneWall().palisades().size() == 1, "The builders must change the 3 parts: "
                            + sites[0] + " sites, " + open.stream().map(site -> site.phase() + " " + site.status()
                            + " " + site.appliedSteps() + "/" + site.totalSteps() + " " + site.materialQuests().keySet())
                            .toList());
                })
                .thenExecute(() -> {
                    helper.assertTrue(sites[0] == 3, "One site for each part: " + sites[0]);
                    VillageReference stoneReference = heart.repairReference(level).orElseThrow();
                    helper.assertTrue(stoneReference.pieces().equals(List.of(
                            new VillageReference.Piece(stone.tower(), tower.origin(), tower.rotation()),
                            new VillageReference.Piece(stone.gate(), gate.origin(), gate.rotation()))),
                            "The reference has the stone pieces: " + stoneReference.pieces());
                    Set<BlockPos> damaged = VillageRepairs.damaged(level, heart, stoneReference);
                    helper.assertTrue(damaged.isEmpty(), "The repairs must see no damage in the stone wall: "
                            + damaged.stream().limit(10).map(pos -> pos.subtract(helper.absolutePos(BlockPos.ZERO))
                                    + " " + level.getBlockState(pos)).toList());
                    for (int i = 0; i < COLUMNS; i++) {
                        BlockPos base = segment.start().east(i);
                        for (int log = 1; log <= LOGS; log++) {
                            helper.assertTrue(level.getBlockState(base.above(log)).is(stone.palisade().log()
                                    .getBlock()), "The column " + i + " must be in stone: "
                                    + level.getBlockState(base.above(log)));
                        }
                        BlockState top = level.getBlockState(base.above(LOGS + 1));
                        boolean pointed = PalisadePiece.isPointed(base.getX(), base.getZ());
                        helper.assertTrue(pointed ? top.is(stone.palisade().point().getBlock()) : top.isAir(),
                                "The point of the column " + i + " must be in stone: " + top);
                    }
                    Map<BlockPos, BlockState> stoneBlocks = VillageRepairs.expectedBlocks(level,
                            new VillageReference(List.of(new VillageReference.Piece(stone.tower(), tower.origin(),
                                    tower.rotation()))));
                    long changed = stoneBlocks.entrySet().stream()
                            .filter(entry -> entry.getValue().is(Blocks.STONE_BRICKS)).count();
                    helper.assertTrue(changed > 0 && stoneBlocks.entrySet().stream()
                            .filter(entry -> entry.getValue().is(Blocks.STONE_BRICKS))
                            .allMatch(entry -> level.getBlockState(entry.getKey()).is(Blocks.STONE_BRICKS)),
                            "The tower must be in stone bricks");
                    // A broken stone block is damage, with the stone block of the reference. The positions of the
                    // sites wait before a new repair (see VillageRepairs#RETRY_TICKS): the test reads the rules.
                    level.setBlock(broken, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    BlockState expected = VillageRepairs.expectedBlocks(level, stoneReference).get(broken);
                    helper.assertTrue(expected.is(stone.palisade().log().getBlock())
                            && RepairRules.isDamaged(level.getBlockState(broken), expected),
                            "The repairs must keep the stone wall: " + expected);
                    helper.assertTrue(RepairRules.isDamaged(wall.palisade().log(), expected),
                            "A log in the stone wall is damage");
                    // A level that goes down does not change the wall back.
                    settlement.setProgress(4, 0);
                    helper.assertTrue(StoneWall.check(level, heart).isEmpty(), "A hamlet starts no wall site");
                    helper.assertTrue(VillageRepairs.expectedBlocks(level, heart.repairReference(level).orElseThrow())
                            .get(broken).is(stone.palisade().log().getBlock()),
                            "A level that goes down keeps the stone wall");
                })
                .thenSucceed();
    }

    /** Places a template of the wall at a position of the test, and gives its piece. */
    private static VillageReference.Piece place(GameTestHelper helper, ResourceLocation id,
            BlockPos relative) {
        ServerLevel level = helper.getLevel();
        StructureTemplate template = level.getStructureManager().get(id).orElseThrow();
        BlockPos origin = helper.absolutePos(relative);
        template.placeInWorld(level, origin, origin, new StructurePlaceSettings().setKnownShape(true),
                level.getRandom(), Block.UPDATE_CLIENTS);
        return new VillageReference.Piece(id, origin, Rotation.NONE);
    }

    /** Places a segment of the palisade along X, as the generation does, and gives its reference. */
    private static VillageReference.Palisade palisade(GameTestHelper helper, WallSettings wall) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(SEGMENT);
        List<Integer> bases = new ArrayList<>();
        for (int i = 0; i < COLUMNS; i++) {
            BlockPos base = start.east(i);
            bases.add(base.getY());
            level.setBlock(base, wall.palisade().base(), Block.UPDATE_CLIENTS);
            for (int log = 1; log <= LOGS; log++) {
                level.setBlock(base.above(log), wall.palisade().log(), Block.UPDATE_CLIENTS);
            }
            if (PalisadePiece.isPointed(base.getX(), base.getZ())) {
                level.setBlock(base.above(LOGS + 1), wall.palisade().point(), Block.UPDATE_ALL);
            }
        }
        return new VillageReference.Palisade(start, 1, 0, bases, wall.palisade().base(), wall.palisade().log(), LOGS);
    }
}
