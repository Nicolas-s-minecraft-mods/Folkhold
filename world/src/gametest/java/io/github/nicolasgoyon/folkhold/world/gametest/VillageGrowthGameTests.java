package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.block.BuildingSignKind;
import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobAssignment;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.HomeNeed;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.construction.GhostPlacement;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementLevels;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageBeds;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageGrowth;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairData;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairs;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import io.github.nicolasgoyon.folkhold.world.village.structure.WallSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the growth of a village: its builders build a house-workshop or a building of the level on a free
 * plot (see {@link VillageGrowth}). Most villages of the tests have the house-workshop of the farmer (a food post that
 * trades) as a piece of their village structure, south-west of the heart. The tests of the Sunreach style grow the
 * pieces of a village structure with the rules of the growth, with no heart and no block in the level.
 * See {@link TestSupport} for the templates and the helpers.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageGrowthGameTests {
    private static final int SIZE = 32;
    private static final BlockPos HEART = new BlockPos(8, 1, 8);
    /** The connector of the plot, in the street: the plot is to the east of it. */
    private static final BlockPos CONNECTOR = new BlockPos(16, 1, 16);
    private static final ResourceLocation HOUSE_FRONT =
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "house_front");
    private static final ResourceLocation HOMES = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/homes");
    /** The pool of the homes of the test variant (GameTest data): its pool of jobs has no piece of a valid job. */
    private static final ResourceLocation TEST_HOMES = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/test/homes");
    private static final ResourceLocation STOREHOUSE = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/house_storehouse");
    private static final ResourceLocation FARM = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/job_farmer");
    /** The origin of the house-workshop of the farmer: out of the space of the plot. */
    private static final BlockPos FARM_ORIGIN = new BlockPos(1, 1, 22);
    private static final String FARMER = FolkholdWorld.MOD_ID + ":farmer";
    /** The house-workshop of the baker: a home that grows in place. */
    private static final ResourceLocation BAKERY = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/job_baker");
    private static final ResourceLocation BAKERY_STEP2 = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/job_baker_step2");
    /** The job of the job slots of a legacy village: not a food job, so the first post is not a legacy job. */
    private static final String LEGACY_JOB = FolkholdWorld.MOD_ID + ":miner";
    private static final ResourceLocation BARRACKS = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/house_barracks");
    /** The folder of the pools and the pieces of the Sunreach style. */
    private static final String SUNREACH = "village/sunreach/";
    /** The id of the Sunreach culture. */
    private static final String SUNREACH_CULTURE = "sunreach";
    /** The first candidate chunk of the generated Sunreach villages: far from the areas of the other tests. */
    private static final int SUNREACH_FIRST_CHUNK = 18000;
    /** The distance between 2 candidate chunks, in chunks. */
    private static final int SUNREACH_STEP = 12;
    private static final int SUNREACH_CANDIDATES = 40;
    /** The size of a Sunreach house-workshop (x, y, z). */
    private static final Vec3i SUNREACH_WORKSHOP = new Vec3i(9, 7, 13);
    /** The size of the Sunreach plaza, in blocks. */
    private static final int PLAZA_SIZE = 15;
    /** The position of the north-west corner of the plaza of the test of the wall: far from the other tests. */
    private static final int WALL_TEST_X = 16 * (SUNREACH_FIRST_CHUNK - 40);
    private static final int WALL_TEST_Z = 16 * (SUNREACH_FIRST_CHUNK - 40);

    private VillageGrowthGameTests() {
    }

    /**
     * The population follows the homes. A village of the level 1 has 2 job slots and a farmer post: a free post. Its
     * builders start the house-workshop of the job that {@link JobAssignment#next} gives on the spare plot. The
     * connector of the house-workshop is in front of the connector of the plot, and the house-workshop faces the
     * street. At the end of the site, the scan gives 1 more post with the job and the places of the new home. Then
     * the village has no free post: with a population above its places, it builds no home. The population of the
     * level is not a target.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_plot")
    public static void freePostBuildsAHouseWorkshopOnTheSparePlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = village(helper, HOMES, true, List.of(plot(helper, false)), 1, 3, false);
        Settlement settlement = village.settlement();
        VillageHeartBlockEntity heart = village.heart();
        try {
            hamletBuildings(helper, heart);
            HomeNeed.Cause need = VillageGrowth.homeNeed(level, heart, settlement);
            String job = nextJob(level, settlement);
            int places = VillageBeds.places(settlement);
            Optional<BuilderSite> site = VillageGrowth.check(level, heart);

            helper.assertTrue(need == HomeNeed.Cause.FREE_POST, "A village with a free post must need a home: "
                    + need);
            helper.assertTrue(site.isPresent(), "A village with a free post must start a home on the spare plot");
            helper.assertTrue(site.get().template().equals(workshop(job)), "The home must be the house-workshop of "
                    + job + ": " + site.get().template());
            // The connector of the house-workshop is at (4, 1, 0) and faces north. To face the west, the piece turns
            // counterclockwise: the connector goes to (0, 1, -4) from the origin. The connector of the piece is in
            // front of the connector of the plot (one block to the east), at the Y of the first free block above the
            // ground.
            BlockPos connector = helper.absolutePos(CONNECTOR);
            BlockPos front = connector.east();
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, connector.getX(),
                    connector.getZ());
            BlockPos expected = new BlockPos(front.getX(), ground - 1, front.getZ() + 4);
            helper.assertTrue(site.get().origin().equals(expected), "Expected the origin " + expected + ", found "
                    + site.get().origin());

            finish(level, heart, site.get());
            List<String> posts = settlement.jobPosts().orElseThrow().stream().map(JobPosts.Post::jobId).toList();
            List<String> expectedPosts = new ArrayList<>(List.of(FARMER, job));
            helper.assertTrue(posts.stream().sorted().toList().equals(expectedPosts.stream().sorted().toList()),
                    "The scan must give the post of the new home: " + posts);
            helper.assertTrue(VillageBeds.places(settlement) > places, "The new home must give places: "
                    + VillageBeds.places(settlement) + " places, " + places + " before");
            helper.assertFalse(heart.isHomeNoSpace(), "A free plot held the new home");

            settlement.economy().setPopulation(LevelTables.get().traits(1).maxPopulation());
            helper.assertTrue(settlement.economy().population() > VillageBeds.places(settlement),
                    "The population of the test must be above the places");
            HomeNeed.Cause noNeed = VillageGrowth.homeNeed(level, heart, settlement);
            helper.assertTrue(noNeed == HomeNeed.Cause.NONE, "A village with no free post must need no home: "
                    + noNeed);
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A village with no need builds no home: its posts fill the job slots of its level, and it has no surplus. Its
     * population is the maximum population of the level, above its places. The spare plot stays free.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_no_need")
    public static void villageWithNoNeedBuildsNoHome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = village(helper, HOMES, true, List.of(plot(helper, false)), 1, 3, false);
        Settlement settlement = village.settlement();
        try {
            hamletBuildings(helper, village.heart());
            fillPosts(settlement, LevelTables.get().traits(1).jobSlots());
            settlement.economy().setPopulation(LevelTables.get().traits(1).maxPopulation());
            HomeNeed.Cause need = VillageGrowth.homeNeed(level, village.heart(), settlement);
            Optional<BuilderSite> site = VillageGrowth.check(level, village.heart());

            helper.assertTrue(settlement.economy().population() > VillageBeds.places(settlement),
                    "The population of the test must be above the places");
            helper.assertTrue(need == HomeNeed.Cause.NONE, "The village must need no home: " + need);
            helper.assertTrue(site.isEmpty(), "A village with no need must build no home: "
                    + site.map(BuilderSite::template));
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A surplus gives one home at a time. The village has no free post. With a surplus for the days of the setting,
     * it starts one house-workshop. Then the next home waits for the delay of the setting, also with the surplus.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_surplus")
    public static void surplusGivesOneHomeAtATime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH_SURPLUS_DAYS, 3);
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH_HOME_DELAY_DAYS, 2);
        Village village = village(helper, HOMES, true, List.of(plot(helper, false)), 1, 3, false);
        Settlement settlement = village.settlement();
        VillageHeartBlockEntity heart = village.heart();
        try {
            hamletBuildings(helper, heart);
            fillPosts(settlement, LevelTables.get().traits(1).jobSlots());
            VillageGrowth.setSurplusSource((found, ignored) -> 2);
            HomeNeed.Cause brief = VillageGrowth.homeNeed(level, heart, settlement);
            VillageGrowth.setSurplusSource((found, ignored) -> 3);
            HomeNeed.Cause surplus = VillageGrowth.homeNeed(level, heart, settlement);
            Optional<BuilderSite> site = VillageGrowth.check(level, heart);
            HomeNeed.Cause after = VillageGrowth.homeNeed(level, heart, settlement);

            helper.assertTrue(brief == HomeNeed.Cause.NONE, "A short surplus must give no home: " + brief);
            helper.assertTrue(surplus == HomeNeed.Cause.SURPLUS, "The surplus must give a home: " + surplus);
            helper.assertTrue(site.isPresent() && site.get().template().getPath().startsWith("village/job_"),
                    "The surplus must start a house-workshop: " + site.map(BuilderSite::template));
            helper.assertTrue(heart.lastHomeTick() == level.getGameTime(), "The heart must record the new home");
            helper.assertTrue(after == HomeNeed.Cause.NONE, "The next home must wait for the delay: " + after);
        } finally {
            VillageGrowth.setSurplusSource(VillageGrowth.SurplusSource.NONE);
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A village with no food post and no need of places: its builders start a food house-workshop that trades before
     * the other buildings. The village of the level 3 needs a storehouse: the house-workshop comes first.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_food")
    public static void villageWithNoFoodPostBuildsAFoodHouseWorkshop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = village(helper, HOMES, false, List.of(plot(helper, false)), 3, 3, false);
        try {
            helper.assertFalse(village.settlement().economy().population() > VillageBeds.places(village.settlement()),
                    "The village must have its places");
            Optional<BuilderSite> site = VillageGrowth.check(level, village.heart());
            String job = site.map(found -> jobOf(found.template())).orElse("");
            helper.assertTrue(site.isPresent() && site.get().template().equals(workshop(job)),
                    "The village must start a house-workshop: " + site.map(BuilderSite::template));
            helper.assertTrue(Economies.producesFood(job) && Merchants.trades(job),
                    "The house-workshop must be of a food job that trades: " + job);
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A village with no food post in a variant with no piece for its jobs: the growth tries {@link
     * VillageGrowth#WORKSHOP_JOBS} jobs and goes on with the storehouse of the level 3. With no free plot, the growth
     * starts no site.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_no_piece")
    public static void noHouseWorkshopDoesNotStopTheGrowth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = village(helper, TEST_HOMES, false, List.of(plot(helper, false)), 3, 3, false);
        try {
            Optional<BuilderSite> site = VillageGrowth.check(level, village.heart());
            helper.assertTrue(site.isPresent() && site.get().template().equals(STOREHOUSE),
                    "The growth must go on with the storehouse: " + site.map(BuilderSite::template));
            helper.assertTrue(village.heart().isHomeNoSpace(), "No free plot holds a house-workshop of the jobs");

            BuilderSites.stop(level);
            village.heart().setRepairReference(new VillageReference(List.of(), List.of(), Optional.empty(),
                    List.of(plot(helper, true)), Optional.of(HOMES)));
            helper.assertTrue(VillageGrowth.check(level, village.heart()).isEmpty(),
                    "With no free plot, the growth must start no site");
            helper.assertTrue(village.heart().isHomeNoSpace(), "With no free plot, the home has no space");
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A legacy village (its save has no list of posts) with too few places gets a food house-workshop on the free plot.
     * After the scan, the new post takes one legacy job slot: the village keeps the job slots of its level.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_legacy")
    public static void legacyVillageGetsAHouseWorkshop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = village(helper, HOMES, false, List.of(plot(helper, false)), 3, 20, true);
        Settlement settlement = village.settlement();
        int slots = LevelTables.get().traits(3).jobSlots();
        try {
            List<String> before = Economies.activeJobIds(settlement);
            Optional<BuilderSite> site = VillageGrowth.check(level, village.heart());
            String job = site.map(found -> jobOf(found.template())).orElse("");
            helper.assertTrue(site.isPresent() && site.get().template().equals(workshop(job)),
                    "The legacy village must start a house-workshop: " + site.map(BuilderSite::template));
            finish(level, village.heart(), site.get());
            List<String> after = Economies.activeJobIds(settlement);

            helper.assertTrue(before.size() == slots, "Before the site, the legacy slots give the jobs: " + before);
            helper.assertTrue(after.size() == slots && after.get(0).equals(job),
                    "The post must take one legacy slot: " + after);
            long legacyJobs = after.stream().filter(found -> found.equals(LEGACY_JOB)).count();
            helper.assertTrue(legacyJobs == slots - 1, "The legacy slots must go down by 1: " + after);
            helper.assertTrue(settlement.jobPosts().orElseThrow().size() == 1, "The village must have 1 post: "
                    + settlement.jobPosts());
            helper.assertTrue(settlement.legacyJobs(), "The village stays a legacy village");
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A site of a house-workshop that stops before its end leaves no post: the village keeps no job of this site.
     * The space of the stopped site stays taken, so the next check starts the food house-workshop again on the second
     * plot.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_stopped")
    public static void stoppedHouseWorkshopLeavesNoPost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillagePlots.Plot second = new VillagePlots.Plot(helper.absolutePos(CONNECTOR.north(12)), Direction.EAST,
                HOUSE_FRONT, false);
        Village village = village(helper, HOMES, false, List.of(plot(helper, false), second), 3, 3, false);
        try {
            BuilderSite first = VillageGrowth.check(level, village.heart()).orElseThrow();
            String job = jobOf(first.template());
            BuilderSites.stop(level);
            village.heart().requestScan();
            village.heart().ensureGarrison(level.getGameTime());
            List<String> posts = VillageGrowth.postJobs(level.getServer(), village.settlement());
            Optional<BuilderSite> again = VillageGrowth.check(level, village.heart());

            helper.assertTrue(Economies.producesFood(job), "The first site must be a food house-workshop: " + job);
            helper.assertTrue(posts.isEmpty(), "A stopped site must give no post: " + posts);
            helper.assertTrue(VillageGrowth.needsFirstPost(posts), "The village must need its food post again");
            helper.assertTrue(again.isPresent() && again.get().template().equals(first.template())
                    && !again.get().origin().equals(first.origin()),
                    "The food house-workshop must start again on the second plot: " + again.map(BuilderSite::origin));
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A village with its food post, a post for each job slot and a missing building of its next tier: its builders
     * start this building on the nearest free plot. The village has a storehouse and a market: a village of the level 1
     * has the buildings of the next tier, and builds nothing. A village of the level 5 needs a barracks to become a
     * town: the barracks comes. A town of the level 9 with the barracks needs a second storehouse to become a city: the
     * storehouse comes on the second plot. The forge is the building of the blacksmith, not a building of a level.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_building")
    public static void buildersStartAMissingBuildingOfTheNextTier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillagePlots.Plot second = new VillagePlots.Plot(helper.absolutePos(CONNECTOR.north(12)), Direction.EAST,
                HOUSE_FRONT, false);
        // The population fills the places of the staff.
        Village village = village(helper, HOMES, true, List.of(plot(helper, false), second), 1, 3, false);
        Settlement settlement = village.settlement();
        VillageHeartBlockEntity heart = village.heart();
        try {
            hamletBuildings(helper, heart);
            // The posts fill the job slots of the level 9: the village needs no home.
            fillPosts(settlement, LevelTables.get().traits(9).jobSlots());
            boolean lowLevel = VillageGrowth.check(level, heart).isEmpty();
            settlement.setProgress(5, 0);
            Optional<BuilderSite> barracks = VillageGrowth.check(level, heart);
            BuilderSites.stop(level);
            helper.setBlock(HEART.north(2), ModBlocks.buildingSign(BuildingSignKind.BARRACKS));
            heart.requestScan();
            heart.ensureGarrison(level.getGameTime());
            fillPosts(settlement, LevelTables.get().traits(9).jobSlots());
            settlement.setProgress(9, 0);
            Optional<BuilderSite> storehouse = VillageGrowth.check(level, heart);

            helper.assertTrue(heart.getLayout().markets().size() == 1, "The scan must find the market: "
                    + heart.getLayout().markets());
            helper.assertTrue(heart.getLayout().buildings().getOrDefault("storehouse", 0) == 1,
                    "The scan must count the storehouse sign: " + heart.getLayout().buildings());
            helper.assertTrue(lowLevel, "A village of the level 1 with its market and its storehouse must build "
                    + "nothing");
            helper.assertTrue(barracks.isPresent() && barracks.get().template().equals(BARRACKS),
                    "A village of the level 5 must start the barracks of the next tier: "
                    + barracks.map(BuilderSite::template));
            helper.assertTrue(storehouse.isPresent() && storehouse.get().template().equals(STOREHOUSE),
                    "A town of the level 9 must start its second storehouse: " + storehouse.map(BuilderSite::template));
            helper.assertFalse(storehouse.get().origin().equals(barracks.get().origin()),
                    "The storehouse must go on the second plot");
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * The gate of the hamlet tier. A scanned hamlet of the level 4 with the experience of the level 5 and no market
     * keeps the level 4 and its experience. With a market and a storehouse, the next gain gives the level 5. A
     * settlement with no scan goes up with no gate. The operator command sets a level with no gate.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_tier_gate")
    public static void hamletNeedsAMarketAndAStorehouseToBecomeAVillage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = village(helper, HOMES, true, List.of(), 4, 3, false);
        Settlement settlement = village.settlement();
        VillageHeartBlockEntity heart = village.heart();
        int needed = LevelTables.get().traits(4).experience();
        try {
            settlement.setProgress(4, needed - 1);
            SettlementLevels.addExperience(level.getServer(), settlement, 5);
            int keptLevel = settlement.level();
            int keptExperience = settlement.experience();
            hamletBuildings(helper, heart);
            SettlementLevels.addExperience(level.getServer(), settlement, 1);
            int openLevel = settlement.level();

            Settlement simulated = new Settlement("tier-gate-test", 0, 0, settlement.factionId(), "Test", 0, 0);
            simulated.setProgress(4, needed - 1);
            SettlementLevels.addExperience(level.getServer(), simulated, 5);
            settlement.setBuildings(Optional.of(Map.of()));
            SettlementLevels.setProgress(level.getServer(), settlement, 8, 0);
            SettlementLevels.setProgress(level.getServer(), settlement, 9, 0);

            helper.assertTrue(keptLevel == 4 && keptExperience == needed, "With no market, the hamlet must keep the "
                    + "level 4 and the experience " + needed + ": level " + keptLevel + ", experience "
                    + keptExperience);
            helper.assertTrue(openLevel == 5, "With a market and a storehouse, the hamlet must become a village: "
                    + openLevel);
            helper.assertTrue(simulated.level() == 5, "A settlement with no scan must go up with no gate: "
                    + simulated.level());
            helper.assertTrue(settlement.level() == 9, "The command must set a level with no gate: "
                    + settlement.level());
        } finally {
            end(helper, village);
        }
        helper.succeed();
    }

    /**
     * A town with no free plot and a free post makes one house-workshop larger in place. The village of the level 9
     * has the house-workshop of the baker as a piece of its structure. The growth starts the piece of step 2 of the
     * baker on the piece of the home, with the same origin and the same turn. After the construction, the home has 4
     * beds, the job post of the baker stays at the same position, and the reference of the repairs has the piece of
     * step 2: the repairs see no damage in the home. A town permits the step 2 only: the next check makes no home
     * larger.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "village_growth_in_place")
    public static void townMakesAHouseWorkshopLargerInPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = village(helper, HOMES, false, List.of(), 9, 3, false);
        Settlement settlement = village.settlement();
        VillageHeartBlockEntity heart = village.heart();
        StructureTemplate template = level.getStructureManager().get(BAKERY).orElseThrow();
        BlockPos origin = helper.absolutePos(FARM_ORIGIN);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .addProcessor(JigsawReplacementProcessor.INSTANCE).setKnownShape(true);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        BoundingBox grownBox = GhostPlacement.box(level.getStructureManager().get(BAKERY_STEP2).orElseThrow()
                .getSize(), Rotation.NONE, Mirror.NONE, origin);
        List<BoundingBox> boxes = new ArrayList<>(village.boxes());
        boxes.add(grownBox);
        Village grown = new Village(heart, settlement, boxes);
        try {
            heart.setRepairReference(new VillageReference(List.of(new VillageReference.Piece(BAKERY, origin,
                    Rotation.NONE)), List.of(), Optional.empty(), List.of(), Optional.of(HOMES)));
            heart.requestScan();
            heart.ensureGarrison(level.getGameTime());
            List<JobPosts.Post> before = settlement.jobPosts().orElseThrow();
            HomeNeed.Cause need = VillageGrowth.homeNeed(level, heart, settlement);
            Optional<BuilderSite> site = VillageGrowth.check(level, heart);

            helper.assertTrue(need == HomeNeed.Cause.FREE_POST, "The town must need a home: " + need);
            helper.assertTrue(site.isPresent() && site.get().template().equals(BAKERY_STEP2)
                    && site.get().origin().equals(origin) && site.get().rotation() == Rotation.NONE,
                    "The town must make the bakery larger in place: " + site.map(BuilderSite::template));
            finish(level, heart, site.get());
            List<JobPosts.Post> after = settlement.jobPosts().orElseThrow();
            Optional<BuilderSite> next = VillageGrowth.check(level, heart);
            VillageReference reference = heart.repairReference(level).orElseThrow();
            Set<BlockPos> damaged = VillageRepairs.damaged(level, heart, reference);

            helper.assertTrue(before.size() == 1 && after.size() == 1, "The town must keep 1 post: " + after);
            JobPosts.Post post = after.get(0);
            helper.assertTrue(post.jobId().equals(before.get(0).jobId()) && post.x() == before.get(0).x()
                    && post.y() == before.get(0).y() && post.z() == before.get(0).z(),
                    "The job post must stay: " + before + " -> " + after);
            helper.assertTrue(post.homeBeds() == 4, "The home must have 4 beds: " + post.homeBeds());
            helper.assertTrue(reference.pieces().equals(List.of(new VillageReference.Piece(BAKERY_STEP2, origin,
                    Rotation.NONE))), "The reference must have the piece of step 2: " + reference.pieces());
            helper.assertFalse(VillageRepairData.get(level).keeps(heart.getBlockPos(), origin.above(6)),
                    "The repairs must cover the home");
            helper.assertTrue(damaged.stream().noneMatch(grownBox::isInside),
                    "The repairs must see no damage in the home: " + damaged.stream().filter(grownBox::isInside)
                    .limit(10).toList());
            helper.assertTrue(next.isEmpty(), "A town permits the step 2 only: " + next.map(BuilderSite::template));
        } finally {
            end(helper, grown);
        }
        helper.succeed();
    }

    /**
     * The growth of generated Sunreach villages (see {@link #grow}): each new house-workshop is a Sunreach piece of
     * 9 x 7 x 13 blocks, it has room on its plot, and it intersects no piece of the generation and no palisade.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_growth_sunreach")
    public static void sunreachVillageGrowsSunreachHouseWorkshops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        Registry<StructureTemplatePool> pools = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        // The style with no variants: the biome and the culture of the GameTest world do not select another style.
        FortifiedVillageStructure sunreach = new FortifiedVillageStructure(new Structure.StructureSettings(
                structure.biomes(), structure.spawnOverrides(), structure.step(), structure.terrainAdaptation()),
                poolHolder(pools, SUNREACH + "plaza"), structure.maxDistanceFromCenter(),
                sunreachWall(structure), poolHolder(pools, SUNREACH + "homes"), List.of());
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        List<String> problems = new ArrayList<>();
        int workshops = 0;
        for (int i = 0; i < SUNREACH_CANDIDATES; i++) {
            ChunkPos chunk = new ChunkPos(SUNREACH_FIRST_CHUNK + i * SUNREACH_STEP, SUNREACH_FIRST_CHUNK);
            StructureStart start = sunreach.generate(level.registryAccess(), generator, generator.getBiomeSource(),
                    level.getChunkSource().randomState(), level.getServer().getStructureManager(), level.getSeed(),
                    chunk, 0, level, biome -> true);
            helper.assertTrue(start.isValid(), "The village must start in " + chunk);
            workshops += grow(level, start.getPieces(), problems);
        }
        helper.assertTrue(problems.isEmpty(), "Problems of the growth of the Sunreach villages: " + problems);
        helper.assertTrue(workshops > 0, "The growth of the Sunreach villages must build a house-workshop");
        helper.succeed();
    }

    /**
     * The wall keeps the space of a Sunreach house-workshop on a free plot, also when the plot is at the edge of the
     * village. The village has the Sunreach plaza and a street to the east of it. The plot to the east of the street is
     * free, and its space is the east end of the village. The north-east corner tower of the wall and its stair go
     * along the east side, in front of this plot. The wall comes from {@link VillageWall#add} with the spaces of
     * {@link FortifiedVillageStructure#reservedSpaces}: the space of a new home on each free plot connector. The line of the palisade is 5 blocks (the margin of 4 blocks
     * and 1 block) from the east end of the spaces, and the tower goes 4 blocks inside the line: 1 free block stays
     * between the tower and the space of the plot. Then the growth (see {@link #grow}) builds a Sunreach
     * house-workshop on this plot: the tower and its stair are not in its space. A space that is 2 blocks less deep
     * than the house-workshop puts the tower in the house-workshop.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_growth_sunreach_wall")
    public static void wallKeepsTheSpaceOfASunreachHouseWorkshop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureTemplateManager templates = level.getStructureManager();
        Registry<StructureTemplatePool> pools = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        BlockPos corner = new BlockPos(WALL_TEST_X, 0, WALL_TEST_Z);
        BlockPos origin = corner.atY(generator.getBaseHeight(corner.getX(), corner.getZ(),
                Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()) - 1);
        StructurePoolElement plaza = element(level, pools, SUNREACH + "plaza", SUNREACH + "plaza");
        StructurePoolElement street = element(level, pools, SUNREACH + "streets_first", SUNREACH + "street");
        // The street starts at the east side of the plaza (15 x 15 blocks). Its plots are 8 blocks from its start.
        BlockPos streetOrigin = origin.east(PLAZA_SIZE);
        List<StructurePiece> village = new ArrayList<>(List.of(piece(templates, plaza, origin),
                piece(templates, street, streetOrigin)));
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                generator.getBiomeSource(), level.getChunkSource().randomState(), templates, level.getSeed(),
                new ChunkPos(origin), level, biome -> true);
        // The spaces of the free plots, with the method of the generation.
        List<BoundingBox> reserved = FortifiedVillageStructure.reservedSpaces(level.registryAccess(), templates,
                poolHolder(pools, SUNREACH + "homes"), village);
        List<StructurePiece> pieces = new ArrayList<>(village);
        pieces.addAll(VillageWall.add(context, TerrainCache.of(context), village, reserved,
                origin.offset(PLAZA_SIZE / 2, 0, PLAZA_SIZE / 2), sunreachWall(structure), false));
        List<String> problems = new ArrayList<>();
        int workshops = grow(level, pieces, problems);
        helper.assertTrue(problems.isEmpty(), "Problems of the growth of the Sunreach village: " + problems);
        helper.assertTrue(workshops == 1, "The free plot east of the street must get a house-workshop: "
                + workshops);
        helper.succeed();
    }

    /**
     * The pool of the homes of each variant of the village structure, and of its base variant, has a pool
     * {@code jobs} in its folder: the space of a free plot holds the house-workshops of the variant (see
     * {@link VillagePlots#homePools}).
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_growth_job_pools")
    public static void eachVariantHasAPoolOfHouseWorkshops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        Registry<StructureTemplatePool> pools = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        List<Holder<StructureTemplatePool>> homes = new ArrayList<>(List.of(structure.homes()));
        structure.variants().forEach(variant -> homes.add(variant.homes()));
        List<String> missing = new ArrayList<>();
        for (Holder<StructureTemplatePool> pool : homes) {
            ResourceLocation id = pool.unwrapKey().orElseThrow().location();
            Optional<StructureTemplatePool> jobs = pools.getOptional(VillagePlots.sibling(id, VillagePlots.JOBS));
            if (jobs.isEmpty() || jobs.get().getShuffledTemplates(RandomSource.create(0L)).isEmpty()
                    || VillagePlots.homePools(level.registryAccess(), pool).size() != 2) {
                missing.add(id.toString());
            }
        }
        helper.assertTrue(missing.isEmpty(), "Pools of homes with no pool of house-workshops: " + missing);
        helper.succeed();
    }

    /** The wall settings of the base Sunreach variant: the variant of the culture with no biome. */
    private static WallSettings sunreachWall(FortifiedVillageStructure structure) {
        return structure.variants().stream()
                .filter(variant -> variant.culture().equals(Optional.of(SUNREACH_CULTURE)))
                .filter(variant -> variant.biomes().size() == 0)
                .findFirst().orElseThrow().wall();
    }

    /**
     * Grows a village with the rules of {@link VillageGrowth}: the free plots come in the order of their distance to
     * the middle of the first piece. A plot with room for a home of the Sunreach pool {@code homes} gets the next
     * Sunreach house-workshop of the pool {@code jobs}, in the order of the template ids. The connector of a piece goes
     * at the surface of the terrain at the plot. The new house-workshops count as pieces of the village for the next
     * plots.
     *
     * @param pieces   the pieces of the village structure
     * @param problems the list that gets the problems: a house-workshop with no room, of another style, or that
     *                 intersects a piece of the village structure
     * @return the number of new house-workshops
     */
    private static int grow(ServerLevel level, List<StructurePiece> pieces, List<String> problems) {
        StructureTemplateManager templates = level.getStructureManager();
        Registry<StructureTemplatePool> pools = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        List<StructurePoolElement> homes = poolHolder(pools, SUNREACH + "homes").value()
                .getShuffledTemplates(RandomSource.create(0L));
        List<StructurePoolElement> jobs = poolHolder(pools, SUNREACH + "jobs").value()
                .getShuffledTemplates(RandomSource.create(0L)).stream()
                .sorted(Comparator.comparing(element -> VillageReference.templateOf(level, element).orElseThrow()))
                .toList();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        VillageReference generated = VillageReference.of(level, pieces).orElseThrow();
        BlockPos middle = pieces.get(0).getBoundingBox().getCenter();
        List<VillageReference.Piece> grown = new ArrayList<>(generated.pieces());
        List<VillagePlots.Plot> plots = generated.plots().stream().filter(plot -> !plot.built())
                .sorted(Comparator.comparingDouble(plot -> plot.connector().distSqr(middle))).toList();
        int workshops = 0;
        for (VillagePlots.Plot plot : plots) {
            // The test opens a plot only when a home of the pool homes (the cottage) has room on it. A plot with no
            // room for the cottage has no room for a piece of the growth either: the plot stays closed.
            VillageReference village = new VillageReference(grown, generated.palisades(), generated.area(),
                    List.of(), Optional.empty());
            int ground = generator.getBaseHeight(plot.connector().getX(), plot.connector().getZ(),
                    Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
            boolean home = homes.stream().map(element -> VillagePlots.place(templates, element, plot, ground))
                    .flatMap(Optional::stream)
                    .anyMatch(placement -> VillageGrowth.hasRoom(templates, village, placement.box()));
            if (!home) {
                continue;
            }
            StructurePoolElement job = jobs.get(workshops % jobs.size());
            ResourceLocation template = VillageReference.templateOf(level, job).orElseThrow();
            VillagePlots.Placement placement = VillagePlots.place(templates, job, plot, ground).orElseThrow();
            BoundingBox box = placement.box();
            if (!VillageGrowth.hasRoom(templates, village, box)) {
                problems.add("no room for " + template.getPath() + " " + box + " at the plot " + plot.connector()
                        + ": " + obstacles(level, generated, pieces, box));
                continue;
            }
            Vec3i size = templates.get(template).orElseThrow().getSize();
            if (!template.getPath().startsWith(SUNREACH + "job_") || !size.equals(SUNREACH_WORKSHOP)) {
                problems.add("not a Sunreach house-workshop: " + template + " " + size);
            }
            List<String> obstacles = obstacles(level, generated, pieces, box);
            if (!obstacles.isEmpty()) {
                problems.add(template.getPath() + " at the plot " + plot.connector() + " intersects " + obstacles);
            }
            grown.add(new VillageReference.Piece(template, placement.origin(), placement.rotation()));
            workshops++;
        }
        return workshops;
    }

    /**
     * The pieces of a village structure in a box: the template pieces that intersect it, and a palisade with a column
     * in it.
     */
    private static List<String> obstacles(ServerLevel level, VillageReference reference, List<StructurePiece> pieces,
            BoundingBox box) {
        List<String> found = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            if (piece instanceof PoolElementStructurePiece pool
                    && pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID
                    && pool.getBoundingBox().intersects(box)) {
                found.add(VillageReference.templateOf(level, pool.getElement()).map(ResourceLocation::getPath)
                        .orElse("piece") + " " + pool.getBoundingBox());
            }
        }
        reference.palisadeBlocks().keySet().stream()
                .filter(column -> column.getX() >= box.minX() && column.getX() <= box.maxX()
                        && column.getZ() >= box.minZ() && column.getZ() <= box.maxZ())
                .findFirst()
                .ifPresent(column -> found.add("palisade " + column));
        return found;
    }

    /** The element of a pool with a template. */
    private static StructurePoolElement element(ServerLevel level, Registry<StructureTemplatePool> pools, String pool,
            String template) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, template);
        return poolHolder(pools, pool).value().getShuffledTemplates(RandomSource.create(0L)).stream()
                .filter(element -> VillageReference.templateOf(level, element).filter(id::equals).isPresent())
                .findFirst().orElseThrow();
    }

    /** A piece of a pool element at a position, with no rotation, as the jigsaw makes it. */
    private static PoolElementStructurePiece piece(StructureTemplateManager templates, StructurePoolElement element,
            BlockPos origin) {
        return new PoolElementStructurePiece(templates, element, origin, 1, Rotation.NONE,
                element.getBoundingBox(templates, origin, Rotation.NONE), LiquidSettings.APPLY_WATERLOGGING);
    }

    private static Holder<StructureTemplatePool> poolHolder(Registry<StructureTemplatePool> pools, String path) {
        return pools.getHolderOrThrow(ResourceKey.create(Registries.TEMPLATE_POOL,
                ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, path)));
    }

    private record Village(VillageHeartBlockEntity heart, Settlement settlement, List<BoundingBox> boxes) {
    }

    /** The plot of the tests, east of the connector in the street. */
    private static VillagePlots.Plot plot(GameTestHelper helper, boolean built) {
        return new VillagePlots.Plot(helper.absolutePos(CONNECTOR), Direction.EAST, HOUSE_FRONT, built);
    }

    /**
     * A village in the plains with its heart, its builders and a village structure. The first check of the garrison
     * scans the village, records its posts and creates the builders.
     *
     * @param farm   true for the house-workshop of the farmer as a piece of the village structure
     * @param legacy true for a legacy village with the job slots of its level
     */
    private static Village village(GameTestHelper helper, ResourceLocation homes, boolean farm,
            List<VillagePlots.Plot> plots, int level, int population, boolean legacy) {
        ServerLevel serverLevel = helper.getLevel();
        // The growth of the villages is off by default: the tests of the growth turn it on.
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH, true);
        TestSupport.buildFloor(helper, SIZE);
        List<VillageReference.Piece> pieces = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        if (farm) {
            StructureTemplate template = serverLevel.getStructureManager().get(FARM).orElseThrow();
            BlockPos origin = helper.absolutePos(FARM_ORIGIN);
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .addProcessor(JigsawReplacementProcessor.INSTANCE).setKnownShape(true);
            template.placeInWorld(serverLevel, origin, origin, settings, serverLevel.getRandom(),
                    Block.UPDATE_CLIENTS);
            boxes.add(template.getBoundingBox(settings, origin));
            pieces.add(new VillageReference.Piece(FARM, origin, Rotation.NONE));
        }
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        heart.ensureSettlement();
        heart.setRepairReference(new VillageReference(pieces, List.of(), Optional.empty(), plots,
                Optional.of(homes)));
        Settlement settlement = Settlements.of(serverLevel.getServer(), heart).orElseThrow();
        settlement.setProgress(level, 0);
        settlement.setBiome("minecraft:plains");
        settlement.economy().setPopulation(population);
        int slots = LevelTables.get().traits(level).jobSlots();
        settlement.economy().setJobs(legacy ? Collections.nCopies(slots, LEGACY_JOB) : List.of());
        settlement.setLegacyJobs(legacy);
        settlement.setJobPosts(Optional.empty());
        heart.ensureGarrison(serverLevel.getGameTime());
        return new Village(heart, settlement, boxes);
    }

    /**
     * Gives a village the buildings of the hamlet tier: a storehouse sign and a market sign with the market post behind
     * it. The heart scans the village again.
     */
    private static void hamletBuildings(GameTestHelper helper, VillageHeartBlockEntity heart) {
        helper.setBlock(HEART.east(2), ModBlocks.buildingSign(BuildingSignKind.STOREHOUSE));
        helper.setBlock(HEART.west(2), ModBlocks.buildingSign(BuildingSignKind.MARKET).defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        helper.setBlock(HEART.west(2).south(), Blocks.OAK_LOG);
        heart.requestScan();
        heart.ensureGarrison(helper.getLevel().getGameTime());
    }

    /**
     * Gives a village posts up to a number: copies of its first post at other positions. The heart keeps them until
     * its next scan.
     */
    private static void fillPosts(Settlement settlement, int count) {
        List<JobPosts.Post> posts = new ArrayList<>(settlement.jobPosts().orElseThrow());
        JobPosts.Post first = posts.get(0);
        for (int i = posts.size(); i < count; i++) {
            posts.add(new JobPosts.Post(first.jobId(), first.x(), first.y() + 100 + i, first.z(), first.homeBeds()));
        }
        settlement.setJobPosts(Optional.of(posts));
    }

    /** The end of a site: the blocks of its piece are in place, and the heart scans the village again. */
    private static void finish(ServerLevel level, VillageHeartBlockEntity heart, BuilderSite site) {
        StructureTemplate template = level.getStructureManager().get(site.template()).orElseThrow();
        StructurePlaceSettings settings = GhostPlacement.settings(site.rotation(), Mirror.NONE)
                .addProcessor(JigsawReplacementProcessor.INSTANCE);
        template.placeInWorld(level, site.origin(), site.origin(), settings, level.getRandom(), Block.UPDATE_CLIENTS);
        BuilderSites.stop(level);
        heart.requestScan();
        heart.ensureGarrison(level.getGameTime());
    }

    /** The job that {@link JobAssignment#next} gives for the next house-workshop of a village. */
    private static String nextJob(ServerLevel level, Settlement settlement) {
        List<String> posts = VillageGrowth.postJobs(level.getServer(), settlement);
        return JobAssignment.next(posts, Economies.eligibleJobs(level.getServer(), settlement), EconomyData.catalog(),
                Merchants::trades, JobAssignment.postSeed(Settlements.seed(settlement.id()), posts.size()))
                .orElseThrow();
    }

    /** The job id of the template of a house-workshop: {@code village/job_<id>} gives {@code folkhold_world:<id>}. */
    private static String jobOf(ResourceLocation template) {
        String path = template.getPath();
        return FolkholdWorld.MOD_ID + ":" + path.substring(path.lastIndexOf("/job_") + "/job_".length());
    }

    /** The template of the house-workshop of a job in the base variant. */
    private static ResourceLocation workshop(String jobId) {
        return ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
                "village/job_" + ResourceLocation.parse(jobId).getPath());
    }

    /** Removes the sites, the villagers, the pieces and the heart: other tests near this test must not find them. */
    private static void end(GameTestHelper helper, Village village) {
        ServerLevel level = helper.getLevel();
        BuilderSites.stop(level);
        VillageHeartBlockEntity heart = village.heart();
        heart.getUnits().forEach(Entity::discard);
        heart.getCats().forEach(Entity::discard);
        VillageFolk.inhabitants(level, heart.getBlockPos()).forEach(Entity::discard);
        village.settlement().setLegacyJobs(false);
        VillageRepairData.get(level).forget(heart.getBlockPos());
        helper.setBlock(HEART, Blocks.AIR);
        village.boxes().forEach(box -> BlockPos.betweenClosedStream(box).forEach(pos -> level.setBlock(pos,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)));
    }
}
