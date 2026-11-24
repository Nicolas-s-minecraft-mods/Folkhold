package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.construction.Plan;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.construction.ConstructionRun;
import io.github.nicolasgoyon.folkhold.world.construction.GhostPlacement;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.village.RepairRules;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHearts;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairData;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the repairs of the land of a village outside its palisade: the builders replant the crops of its
 * fields (see {@link VillageRepairs}). The village of the tests has a small area around its heart and the small field
 * east of it, as a land piece of the heart.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LandRepairGameTests {
    private static final int SIZE = 32;
    private static final BlockPos HEART = new BlockPos(4, 1, 4);
    private static final BlockPos AREA_MIN = new BlockPos(1, 1, 1);
    private static final BlockPos AREA_MAX = new BlockPos(8, 3, 8);
    /** The origin of the small field: outside the area. */
    private static final BlockPos FIELD_ORIGIN = new BlockPos(14, 0, 4);
    private static final ResourceLocation FIELD = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/field_small");
    /** A crop of the field that a player breaks, in the coordinates of the template. */
    private static final BlockPos BROKEN_CROP = new BlockPos(2, 1, 4);
    /** A farmland of the field that a mob tramples into dirt: its crop breaks. */
    private static final BlockPos TRAMPLED = new BlockPos(1, 0, 6);
    /** 2 crops of the field that grow: a young crop and a ripe crop. */
    private static final BlockPos YOUNG_CROP = new BlockPos(8, 1, 4);
    private static final BlockPos RIPE_CROP = new BlockPos(9, 1, 8);
    private static final int BUILDERS = 2;

    private LandRepairGameTests() {
    }

    /**
     * A broken crop and a trampled farmland of a field are damage, and a crop of another age is not. The box of the
     * construction site of the field does not keep its blocks. The repair check starts a repair of the damaged
     * positions, and the repair puts back the farmland and young crops (age 0). After the repair, the field has no
     * damage.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "land_repair")
    public static void repairReplantsTheBrokenCropsOfAField(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_REPAIRS, true);
        TestSupport.buildFloor(helper, SIZE);
        BuilderSites.stop(level);
        StructureTemplate template = level.getStructureManager().get(FIELD).orElseThrow();
        BlockPos origin = helper.absolutePos(FIELD_ORIGIN);
        StructurePlaceSettings settings = GhostPlacement.settings(Rotation.NONE, Mirror.NONE).setKnownShape(true);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        BlockPos heartPos = heart.getBlockPos();
        BoundingBox area = BoundingBox.fromCorners(helper.absolutePos(AREA_MIN), helper.absolutePos(AREA_MAX));
        VillageReference reference = new VillageReference(List.of(), List.of(), Optional.of(area));
        heart.setRepairReference(reference);
        VillageHearts.register(level, heartPos, Optional.of(area));
        heart.addLandPiece(new VillageReference.Piece(FIELD, origin, Rotation.NONE));
        VillageRepairData.get(level).addConstruction(heartPos, GhostPlacement.box(template.getSize(), Rotation.NONE,
                Mirror.NONE, origin));

        BlockPos broken = origin.offset(BROKEN_CROP);
        BlockPos trampled = origin.offset(TRAMPLED);
        BlockPos young = origin.offset(YOUNG_CROP);
        BlockPos ripe = origin.offset(RIPE_CROP);
        BlockState brokenCrop = level.getBlockState(broken);
        BlockState trampledCrop = level.getBlockState(trampled.above());
        helper.assertTrue(brokenCrop.getBlock() instanceof CropBlock && trampledCrop.getBlock() instanceof CropBlock
                && level.getBlockState(trampled).is(Blocks.FARMLAND), "The field must have its crops and farmland");
        level.setBlock(broken, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(trampled.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(trampled, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        setAge(level, young, 0);
        setAge(level, ripe, ((CropBlock) level.getBlockState(ripe).getBlock()).getMaxAge());

        List<Villager> builders = new ArrayList<>();
        try {
            Set<BlockPos> expected = Set.of(broken, trampled, trampled.above());
            Set<BlockPos> damaged = VillageRepairs.damaged(level, heart, reference);
            helper.assertTrue(damaged.equals(expected), "The damage must be the broken crop and the trampled farmland: "
                    + damaged.stream().map(pos -> pos.subtract(origin)).toList());
            for (int i = 0; i < BUILDERS; i++) {
                Villager builder = VillageFolk.spawn(level, heartPos, ModProfessions.BUILDER.get());
                helper.assertTrue(builder != null, "The builder must spawn");
                builders.add(builder);
            }
            Optional<BuilderSite> site = VillageRepairs.check(level, heart);
            helper.assertTrue(site.isPresent() && site.get().isRepair(), "The village must start a repair");
            helper.assertTrue(site.get().repairPositions().equals(expected), "The repair must be the damage: "
                    + site.get().repairPositions().stream().map(pos -> pos.subtract(origin)).toList());
            Plan plan = site.get().plan(level).orElseThrow();
            helper.assertTrue(plan.warnings().isEmpty(), "The plan of the repair has no warning: " + plan.warnings());
            new ConstructionRun(level, plan, 1).finish();
            BuilderSites.stop(level);

            helper.assertTrue(level.getBlockState(trampled).is(Blocks.FARMLAND), "The repair puts back the farmland: "
                    + level.getBlockState(trampled));
            for (BlockPos pos : List.of(broken, trampled.above())) {
                BlockState state = level.getBlockState(pos);
                helper.assertTrue(state.getBlock() instanceof CropBlock crop && crop.getAge(state) == 0,
                        "The repair replants a young crop at " + pos.subtract(origin) + ": " + state);
            }
            helper.assertTrue(level.getBlockState(broken).is(brokenCrop.getBlock())
                    && level.getBlockState(trampled.above()).is(trampledCrop.getBlock()),
                    "The repair replants the crops of the template");
            Set<BlockPos> left = VillageRepairs.damaged(level, heart, reference);
            helper.assertTrue(left.isEmpty(), "The field has no damage after the repair: "
                    + left.stream().map(pos -> pos.subtract(origin)).toList());
        } finally {
            BuilderSites.stop(level);
            builders.forEach(Entity::discard);
            VillageRepairData.get(level).forget(heartPos);
        }
        helper.succeed();
    }

    /**
     * The rules of the crops: the age of a crop is not damage, a trampled farmland is. A repair replants a crop with
     * no seeds in the stock for free only with the setting {@link WorldConfig#CONSTRUCTION_FREE_REPLANT}.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "land_repair_rules")
    public static void repairRulesOfTheCrops(GameTestHelper helper) {
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState ripe = wheat.getStateForAge(wheat.getMaxAge());
        BlockState young = wheat.getStateForAge(0);
        helper.assertFalse(RepairRules.isDamaged(young, ripe), "A young crop is not damage");
        helper.assertFalse(RepairRules.isDamaged(ripe, young), "A ripe crop is not damage");
        helper.assertTrue(RepairRules.isDamaged(Blocks.AIR.defaultBlockState(), ripe), "A missing crop is damage");
        helper.assertTrue(RepairRules.isDamaged(Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState()),
                "A trampled farmland is damage");
        helper.assertFalse(RepairRules.isDamaged(Blocks.FARMLAND.defaultBlockState(),
                Blocks.DIRT.defaultBlockState()), "A farmland in the place of dirt is not damage");
        helper.assertTrue(VillageRepairs.replanted(ripe).equals(young), "A repair replants a young crop");
        BlockState stone = Blocks.STONE.defaultBlockState();
        helper.assertTrue(VillageRepairs.replanted(stone) == stone, "A repair places the other blocks as they are");
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_FREE_REPLANT, true);
        helper.assertTrue(VillageRepairs.isFreeReplant(young, false), "No seeds in the stock: a free replant");
        helper.assertFalse(VillageRepairs.isFreeReplant(young, true), "Seeds in the stock: the repair takes them");
        helper.assertFalse(VillageRepairs.isFreeReplant(stone, false), "Only a crop is a free replant");
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_FREE_REPLANT, false);
        helper.assertFalse(VillageRepairs.isFreeReplant(young, false), "With the setting off, the repair waits");
        helper.succeed();
    }

    private static void setAge(ServerLevel level, BlockPos pos, int age) {
        BlockState state = level.getBlockState(pos);
        level.setBlock(pos, ((CropBlock) state.getBlock()).getStateForAge(age), Block.UPDATE_CLIENTS);
    }
}
