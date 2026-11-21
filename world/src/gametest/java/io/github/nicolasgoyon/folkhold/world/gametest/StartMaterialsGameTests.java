package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.economy.StartMaterials;
import io.github.nicolasgoyon.folkhold.world.faction.Cultures;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.RepairRules;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHearts;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairData;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The start materials of a new village (see {@link StartMaterials}). */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StartMaterialsGameTests {
    private static final ResourceLocation CENTER = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/center");
    /** 2 spruce logs of the north palisade, in the coordinates of the template. The torch is on the log above. */
    private static final List<BlockPos> WALL = List.of(new BlockPos(8, 4, 1), new BlockPos(8, 5, 1));
    /** The biome of the base variant: its palisade has spruce logs, as the template. */
    private static final String TAIGA = "minecraft:taiga";
    private static final String SPRUCE_LOG = "minecraft:spruce_log";
    private static final int BUILDERS = 2;

    private StartMaterialsGameTests() {
    }

    /**
     * A new hamlet of Valeholm in the taiga has the start materials of its style in its stock, up to the stock
     * capacity. Its builders repair 2 logs of the palisade with these materials: the repair asks for no delivery. A
     * settlement with a started economy keeps its stock: the start materials come only one time.
     */
    @GameTest(template = "village/center", timeoutTicks = 3000, batch = "start_materials")
    public static void newVillageRepairsWithItsStartMaterials(GameTestHelper helper) {
        VillageHeartBlockEntity heart = TestSupport.findHeart(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos heartPos = heart.getBlockPos();
        BuilderSites.stop(level);
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_BUILDER_TICKS, 1);
        VillageHearts.register(level, heartPos);
        helper.assertTrue(heart.getLayout().scan(level, heartPos), "The heart must scan the village");
        heart.ensureSettlement();
        Settlement settlement = Settlements.of(server, heart).orElseThrow();
        settlement.setProgress(1, 0);
        settlement.setBiome(TAIGA);
        RaidBalanceGameTests.joinFaction(helper, heart, Cultures.VALEHOLM.id());

        String tier = LevelTables.get().tier(settlement.level()).id();
        int capacity = LevelTables.get().traits(settlement.level()).stockCapacity();
        Map<String, Integer> materials = StartMaterials.get().materials(Cultures.VALEHOLM.id(), tier,
                TAIGA::equals);
        helper.assertTrue(materials.containsKey(SPRUCE_LOG) && materials.containsKey("minecraft:spruce_planks"),
                "The hamlet of Valeholm must get spruce: " + materials);
        materials.forEach((item, count) -> helper.assertTrue(
                settlement.economy().stock().count(item) >= Math.min(count, capacity),
                "The stock must have the start materials: " + item + " " + settlement.economy().stock().count(item)
                + " for " + count));
        Map<String, Integer> before = new TreeMap<>(settlement.economy().stock().asMap());
        Settlements.startEconomy(server, settlement);
        helper.assertTrue(before.equals(settlement.economy().stock().asMap()),
                "A settlement with a started economy must keep its stock");
        int logs = settlement.economy().stock().count(SPRUCE_LOG);

        BlockPos origin = VillageRepairGameTests.templateOrigin(helper, heartPos);
        VillageReference reference = new VillageReference(List.of(new VillageReference.Piece(CENTER, origin,
                Rotation.NONE)));
        heart.setRepairReference(reference);
        long[] time = {TestSupport.NOON};
        helper.onEachTick(() -> TestSupport.setDayTimeForTest(helper, time[0]));
        Map<BlockPos, BlockState> expected = new HashMap<>();
        for (BlockPos relative : WALL) {
            BlockPos pos = origin.offset(relative);
            expected.put(pos, level.getBlockState(pos));
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Set<BlockPos> wall = WALL.stream().map(origin::offset).collect(Collectors.toSet());
        List<Villager> builders = new ArrayList<>();
        for (int i = 0; i < BUILDERS; i++) {
            Villager builder = VillageFolk.spawn(level, heartPos, ModProfessions.BUILDER.get());
            helper.assertTrue(builder != null, "The builder must spawn");
            builders.add(builder);
        }
        BuilderSite[] site = new BuilderSite[1];
        boolean[] asked = {false};
        helper.onEachTick(() -> {
            if (site[0] != null && (!site[0].materialQuests().isEmpty() || !site[0].missingMaterials().isEmpty())) {
                asked[0] = true;
            }
        });

        helper.startSequence()
                .thenExecute(() -> {
                    site[0] = VillageRepairs.check(level, heart).orElse(null);
                    helper.assertTrue(site[0] != null && site[0].isRepair()
                            && site[0].repairPositions().equals(wall), "The repair of the wall must start");
                })
                .thenWaitUntil(() -> helper.assertTrue(BuilderSites.of(level, heartPos).isEmpty(),
                        "The builders must finish the repair: " + site[0].phase() + " " + site[0].status() + " "
                        + site[0].appliedSteps() + "/" + site[0].totalSteps()))
                .thenExecute(() -> {
                    int left = settlement.economy().stock().count(SPRUCE_LOG);
                    builders.forEach(Entity::discard);
                    VillageRepairData.get(level).forget(heartPos);
                    level.setBlock(heartPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    for (BlockPos pos : wall) {
                        helper.assertFalse(RepairRules.isDamaged(level.getBlockState(pos), expected.get(pos)),
                                "The log at " + pos.subtract(origin) + " must be repaired: " + level.getBlockState(pos));
                    }
                    helper.assertFalse(asked[0], "The repair must not wait for a delivery");
                    helper.assertTrue(left == logs - WALL.size(), "The repair must take the logs from the stock: "
                            + left + " of " + logs);
                })
                .thenSucceed();
    }
}
