package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.level.Tier;
import io.github.nicolasgoyon.folkhold.core.sim.village.CliffSide;
import io.github.nicolasgoyon.folkhold.core.sim.village.PlanGround;
import io.github.nicolasgoyon.folkhold.core.sim.village.PlotTerrain;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.faction.CultureMap;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.level.TerrainColumns;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementDiscovery;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageBeds;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.PlotTerrains;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillagePathPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRings;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game measure of the terrain of the plots (see {@link PlotTerrain}) on the vanilla Overworld terrain. The GameTest
 * server has a flat world: the tests make a noise chunk generator of the Overworld with a fixed seed, and the layouts
 * of the villages read its heights. The tests place no block and generate no chunk.
 *
 * <p>Each measured village has all its plots and homes, and no problem of its layout (a missing piece, home beds under
 * the maximum population). The measure reads the box of each building on a grid of {@link #FINE_STEP} blocks, finer
 * than the grid of the check of the plots. A building of a plot with no role over lava or deep water on this grid is
 * in the log: the grid of the check does not see it. The buildings of the bank and of the first jobs keep their plot
 * on bad terrain, and the log counts them.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlotTerrainGameTests {
    /** The seed of the measured world. */
    private static final long SEED = 783L;
    /** The number of measured villages on land, for each style. */
    private static final int LAND_VILLAGES = 2;
    /** The number of measured maritime villages. */
    private static final int MARITIME_VILLAGES = 2;
    /** The largest number of start chunks that a search tries. */
    private static final int TRIES = 4000;
    /** The distance between 2 start chunks on land, in chunks. */
    private static final int LAND_STEP = 37;
    /** The distance between 2 start chunks of the search of the coasts, in chunks. */
    private static final int COAST_STEP = 6;
    /** The number of start chunks on each row of a search. */
    private static final int ROW = 60;
    /** The distance between 2 points of the grid of the measure, in blocks. */
    private static final int FINE_STEP = 2;
    /** The highest start level of a hamlet. */
    private static final int HAMLET_LEVEL = 4;
    /** The highest start level of a town. */
    private static final int TOWN_LEVEL = 12;
    /** The environment variable that adds the village, city and capital tiers to the measure of the tiers. */
    static final String MEASURE_VARIABLE = "FOLKHOLD_VILLAGE_MEASURE";
    /** The number of villages of the measure of the rings. */
    private static final int RING_VILLAGES = 40;
    /** The measure of the rings looks for its villages in a square of this number of chunks. */
    private static final int RING_SEARCH = 2000;
    /** The tiers of the measure with no environment variable {@link #MEASURE_VARIABLE}. */
    private static final List<String> DEFAULT_TIERS = List.of("hamlet", "town");
    /** The maritime measure also has the capital with no environment variable: the coast plots are the hard case. */
    private static final List<String> DEFAULT_MARITIME_TIERS = List.of("hamlet", "town", "capital");
    /** The smallest and the largest share of the spare plots, from the plots that the village needs. */
    private static final double MIN_SPARE_SHARE = 0.10;
    private static final double MAX_SPARE_SHARE = 0.20;
    /** The number of mountain sites of the test of the cliff side. */
    private static final int MOUNTAIN_SITES = 6;

    private PlotTerrainGameTests() {
    }

    /** The villages of the base style: see {@link PlotTerrainGameTests}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "plot_terrain_base")
    public static void baseVillagesHaveTheirHomes(GameTestHelper helper) {
        landVillages(helper, StreetStyle.BASE);
    }

    /** The villages of the Sunreach style: see {@link PlotTerrainGameTests}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "plot_terrain_sunreach")
    public static void sunreachVillagesHaveTheirHomes(GameTestHelper helper) {
        landVillages(helper, StreetStyle.SUNREACH);
    }

    /** The villages of the Valeholm style: see {@link PlotTerrainGameTests}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "plot_terrain_valeholm")
    public static void valeholmVillagesHaveTheirHomes(GameTestHelper helper) {
        landVillages(helper, StreetStyle.VALEHOLM);
    }

    /**
     * The first villages of a style on land, in the start chunks of the search. The culture of the region and the
     * biome select the style before the layout: the search makes the layout of the sites of the style only.
     */
    private static void landVillages(GameTestHelper helper, StreetStyle style) {
        Measure measure = new Measure(helper.getLevel());
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (int i = 0; i < TRIES && found < LAND_VILLAGES; i++) {
            Structure.GenerationContext context = measure.context(new ChunkPos((i % ROW) * LAND_STEP,
                    (i / ROW) * LAND_STEP));
            if (!measure.styleAt(context).equals(style.name())) {
                continue;
            }
            Optional<FortifiedVillageStructure.Layout> layout = measure.structure.layout(context);
            if (layout.isPresent() && layout.get().style().name().equals(style.name())) {
                found++;
                measure.check(context, layout.get(), "", problems);
            }
        }
        measure.end(helper, found, LAND_VILLAGES, problems);
    }

    /** The maritime villages: the sites on a beach that the check of the site accepts as maritime. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "plot_terrain_maritime")
    public static void maritimeVillagesHaveTheirHomes(GameTestHelper helper) {
        Measure measure = new Measure(helper.getLevel());
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (int i = 0; i < TRIES && found < MARITIME_VILLAGES; i++) {
            Structure.GenerationContext context = measure.maritimeSite(i).orElse(null);
            if (context != null) {
                Optional<FortifiedVillageStructure.Layout> layout = measure.structure.layout(context);
                if (layout.isPresent() && layout.get().style().name().equals(StreetStyle.MARITIME.name())) {
                    found++;
                    measure.check(context, layout.get(), "", problems);
                }
            }
        }
        measure.end(helper, found, MARITIME_VILLAGES, problems);
    }

    /** The measure of the tiers of the base style: see {@link #measureTiers}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_measure_base", required = false)
    public static void measureBaseTiers(GameTestHelper helper) {
        measureTiers(helper, StreetStyle.BASE);
    }

    /** The measure of the tiers of the Sunreach style: see {@link #measureTiers}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_measure_sunreach", required = false)
    public static void measureSunreachTiers(GameTestHelper helper) {
        measureTiers(helper, StreetStyle.SUNREACH);
    }

    /** The measure of the tiers of the Valeholm style: see {@link #measureTiers}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_measure_valeholm", required = false)
    public static void measureValeholmTiers(GameTestHelper helper) {
        measureTiers(helper, StreetStyle.VALEHOLM);
    }

    /** The measure of the tiers of the maritime style: see {@link #measureTiers}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_measure_maritime")
    public static void measureMaritimeTiers(GameTestHelper helper) {
        measureTiers(helper, StreetStyle.MARITIME);
    }

    /**
     * The measure of the tiers of a style: at the first site of the style (a site on a beach for the maritime style),
     * the village of the top level of each tier. The tests of the land styles are optional: a capital takes the most
     * time. With no environment variable {@link #MEASURE_VARIABLE}, the tests measure the hamlet and the town only.
     * The maritime test is required, and it also measures the capital: the coast plots over deep water are the hard
     * case (the missing homes take these plots on their foundation, see {@link VillagePlan}).
     *
     * <ul>
     *   <li>A village has all its plots and homes, no layout problem, no home on bad terrain except a home on its
     *       foundation over deep water (see {@link Measure#check}), the buildings, the job posts and the home beds of
     *       its level (see {@link VillagePlanGameTests#checkLevel}), and 10 to 20 % spare plots. The free market plot
     *       below level 5 is not a spare plot.</li>
     *   <li>The log has a line {@code Village measure |} for each village: a row of the table of the measure.</li>
     * </ul>
     */
    private static void measureTiers(GameTestHelper helper, StreetStyle style) {
        Measure measure = new Measure(helper.getLevel());
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = measure.structure;
        boolean maritime = style.name().equals(StreetStyle.MARITIME.name());
        Optional<Structure.GenerationContext> site = maritime
                ? IntStream.range(0, TRIES).mapToObj(measure::maritimeSite).flatMap(Optional::stream).findFirst()
                : IntStream.range(0, TRIES)
                        .mapToObj(i -> measure.context(new ChunkPos((i % ROW) * LAND_STEP, (i / ROW) * LAND_STEP)))
                        .filter(context -> measure.styleAt(context).equals(style.name())
                                && structure.site(context).equals(Optional.of(VillageSite.Result.ACCEPTED)))
                        .findFirst();
        helper.assertTrue(site.isPresent(), "No site of the style " + style.name());
        Structure.GenerationContext context = site.get();
        FortifiedVillageStructure.Variant variant = measure.variant(context, maritime);
        List<String> problems = new ArrayList<>();
        List<Tier> tiers = LevelTables.get().tiers();
        if (System.getenv(MEASURE_VARIABLE) == null) {
            List<String> defaults = maritime ? DEFAULT_MARITIME_TIERS : DEFAULT_TIERS;
            tiers = tiers.stream().filter(tier -> defaults.contains(tier.id())).toList();
        }
        for (Tier tier : tiers) {
            long time = System.nanoTime();
            FortifiedVillageStructure.Layout layout = structure.layout(context, variant, maritime, tier.maxLevel())
                    .orElseThrow();
            time = (System.nanoTime() - time) / 1_000_000;
            String name = "chunk " + context.chunkPos() + " level " + tier.maxLevel() + " " + tier.id() + " "
                    + style.name();
            measure.check(context, layout, " forced", problems);
            VillagePlanGameTests.checkLevel(level, measure.generator, layout, name, problems);
            List<VillagePlan.Plot> plots = layout.plots();
            long spare = plots.stream().filter(VillagePlan.Plot::spare).count();
            double share = (double) spare / (plots.size() - spare);
            if (share < MIN_SPARE_SHARE || share > MAX_SPARE_SHARE) {
                problems.add(name + ": " + spare + " spare plots for " + plots.size() + " plots");
            }
            Map<String, Integer> signs = VillagePlanGameTests.signs(level, layout);
            FolkholdWorld.LOGGER.info("Village measure | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} / {} / {} "
                    + "| {} | {} | {} | {} | {} |", tier.id(), style.name(), tier.maxLevel(), plots.size(),
                    layout.plans().stream().map(part -> String.valueOf(part.plots().size()))
                            .collect(Collectors.joining(" + ")),
                    layout.free(), spare, signs.getOrDefault(VillagePlanGameTests.HOME, 0) - layout.posts().size(),
                    VillagePlanGameTests.homeBeds(level, layout),
                    LevelTables.get().traits(tier.maxLevel()).maxPopulation() - VillageBeds.staff(),
                    layout.built(), signs.getOrDefault("storehouse", 0), signs.getOrDefault("barracks", 0),
                    signs.getOrDefault(VillageBuildings.MARKET, 0), layout.posts().size(), layout.problems().size(),
                    plots.stream().filter(plot -> plot.spare() && !plot.good()).count(), time, rules(layout));
        }
        measure.end(helper, tiers.size(), tiers.size(), problems);
    }

    /** The rules of the ground of the plans of a layout: the core, then each band (see {@link VillagePlan.Rules}). */
    static String rules(FortifiedVillageStructure.Layout layout) {
        return layout.plans().stream().map(plan -> plan.rules().name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" + "));
    }

    /**
     * The measure of the rings of the natural villages: the first {@link #RING_VILLAGES} towns, cities and capitals of
     * the placement of the faction villages, from the chunk (0, 0), with the start level of their chunk. For each
     * village, the measure counts the rings of its tier, the rings that it keeps near the other villages, and the
     * cause of the lost rings (see {@link VillageRings#count}). The test runs only with the environment variable
     * {@link #MEASURE_VARIABLE}: a layout on noise terrain takes seconds.
     *
     * <p>The log has a line {@code Village ring measure |} for each tier: the villages, the villages with all their
     * rings, the villages that lose a ring to a faction village, and the villages that lose a ring to a possible
     * vanilla village. A village can lose rings to the two causes.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_measure_rings", required = false)
    public static void measureRings(GameTestHelper helper) {
        if (System.getenv(MEASURE_VARIABLE) == null) {
            helper.succeed();
            return;
        }
        Measure measure = new Measure(helper.getLevel());
        Holder<StructureSet> set = measure.registries.registryOrThrow(Registries.STRUCTURE_SET)
                .getHolderOrThrow(SettlementDiscovery.VILLAGE_SET);
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(measure.random, SEED,
                measure.generator.getBiomeSource(), Stream.of(set));
        Structure.GenerationContext anchor = measure.context(ChunkPos.ZERO);
        // For each tier: the villages, the villages with all their rings, the losses to factions and to vanilla.
        Map<String, int[]> counts = new TreeMap<>();
        int villages = 0;
        for (int x = 0; x < RING_SEARCH && villages < RING_VILLAGES; x++) {
            for (int z = 0; z < RING_SEARCH && villages < RING_VILLAGES; z++) {
                if (!set.value().placement().isStructureChunk(state, x, z)) {
                    continue;
                }
                ChunkPos start = new ChunkPos(x, z);
                Optional<VillageRings.RingCount> count = VillageRings.count(measure.structure, set, anchor, start);
                if (count.isEmpty()) {
                    continue;
                }
                villages++;
                VillageRings.RingCount rings = count.get();
                String tier = VillageSizes.forLevel(Settlements.startLevel(SEED, start)).tier();
                int[] row = counts.computeIfAbsent(tier, key -> new int[4]);
                row[0]++;
                row[1] += rings.kept() == rings.rings() ? 1 : 0;
                row[2] += rings.keptFactions() < rings.rings() ? 1 : 0;
                row[3] += rings.keptVanilla() < rings.rings() ? 1 : 0;
                FolkholdWorld.LOGGER.info("Village ring measure: chunk {} {} keeps {} of {} rings (faction villages:"
                        + " {}, vanilla villages: {})", start, tier, rings.kept(), rings.rings(),
                        rings.keptFactions(), rings.keptVanilla());
            }
        }
        int total = villages;
        counts.forEach((tier, row) -> FolkholdWorld.LOGGER.info("Village ring measure | {} | {} | {} | {} | {} |",
                tier, row[0], row[1], row[2], row[3]));
        helper.assertTrue(total > 0, "The measure must find a village with rings");
        helper.succeed();
    }

    /**
     * The layouts with one noise object for each chunk are the layouts with one read of the chunk generator for each
     * column (see {@link TerrainCache#direct}): the same plan and the same pieces, for a hamlet and a town on land and
     * for a maritime hamlet. The test logs the time of the two layouts.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "plot_terrain_cache")
    public static void layoutsAreTheSameWithTheTerrainCache(GameTestHelper helper) {
        Measure measure = new Measure(helper.getLevel());
        FortifiedVillageStructure structure = measure.structure;
        FortifiedVillageStructure.Variant land = new FortifiedVillageStructure.Variant(HolderSet.direct(),
                structure.startPool(), structure.wall(), structure.homes(), Optional.empty());
        FortifiedVillageStructure.Variant maritime = structure.variants().stream()
                .filter(FortifiedVillageStructure.Variant::maritime).filter(variant -> variant.biomes().size() == 0)
                .findFirst().orElseThrow();
        Structure.GenerationContext site = IntStream.range(0, TRIES)
                .mapToObj(i -> measure.context(new ChunkPos((i % ROW) * LAND_STEP, (i / ROW) * LAND_STEP)))
                .filter(context -> structure.site(context).equals(Optional.of(VillageSite.Result.ACCEPTED)))
                .findFirst().orElseThrow();
        Structure.GenerationContext coast = IntStream.range(0, TRIES).mapToObj(measure::maritimeSite)
                .flatMap(Optional::stream).findFirst().orElseThrow();
        List<String> problems = new ArrayList<>();
        measure.compare(site, land, false, HAMLET_LEVEL, problems);
        measure.compare(site, land, false, TOWN_LEVEL, problems);
        measure.compare(coast, maritime, true, HAMLET_LEVEL, problems);
        helper.assertTrue(problems.isEmpty(), "Layouts with differences: " + problems);
        helper.succeed();
    }

    /**
     * The cliff side of the walls at the first mountain sites: a biome of the tags {@code is_mountain} or
     * {@code is_hill} at the middle of the start chunk, and a site that the check accepts. Each site gets a town: its
     * first wall and its ring. The test reads the heights of the terrain as the wall reads them (see
     * {@link VillageWall#heights}), and the drops from the level of each gate (see {@link CliffSide}).
     *
     * <ul>
     *   <li>Each wall keeps one gate or more.</li>
     *   <li>No gate opens on a drop: no block of a gate is a cliff block, and the terrain in front of each block of a
     *       gate is never {@link CliffSide#DROP} blocks or more under the level of the gate.</li>
     *   <li>Each gate is reachable from the inside: a path starts behind it, 2 blocks inside the line (the arm of a
     *       gate of the first wall, the path across the band of a gate of a ring). A water gate, and a gate of a ring
     *       in front of a water gate of the inner wall, need no path.</li>
     *   <li>Each column of the palisade on a cliff block has the low palisade, and each other column the full
     *       palisade.</li>
     *   <li>One wall at least has cliff blocks: the test sees the cliff side.</li>
     * </ul>
     *
     * <p>The test places no block: a walk on the blocks of the generated village is not possible on the flat world of
     * the GameTest server.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "plot_terrain_cliff")
    public static void mountainWallsHaveNoGateOnACliff(GameTestHelper helper) {
        Measure measure = new Measure(helper.getLevel());
        FortifiedVillageStructure structure = measure.structure;
        List<String> problems = new ArrayList<>();
        int sites = 0;
        int cliffWalls = 0;
        for (int i = 0; i < TRIES && sites < MOUNTAIN_SITES; i++) {
            Structure.GenerationContext context = measure.context(new ChunkPos((i % ROW) * LAND_STEP,
                    (i / ROW) * LAND_STEP));
            if (!measure.isMountain(context)
                    || !structure.site(context).equals(Optional.of(VillageSite.Result.ACCEPTED))) {
                continue;
            }
            sites++;
            FortifiedVillageStructure.Variant variant = measure.variant(context, false);
            FortifiedVillageStructure.Layout layout = structure.layout(context, variant, false, TOWN_LEVEL)
                    .orElseThrow();
            TerrainCache terrain = TerrainCache.of(context);
            List<VillageWall.Wall> walls = new ArrayList<>(List.of(layout.firstWall()));
            walls.addAll(layout.rings());
            for (int w = 0; w < walls.size(); w++) {
                String name = "chunk " + context.chunkPos() + " wall " + w;
                Optional<VillageWall.Wall> inner = w == 0 ? Optional.empty() : Optional.of(walls.get(w - 1));
                if (checkCliffSide(name, walls.get(w), inner, VillageWall.heights(terrain), problems)) {
                    cliffWalls++;
                }
            }
        }
        FolkholdWorld.LOGGER.info("Cliff side: {} mountain sites, {} walls with cliff blocks, problems {}", sites,
                cliffWalls, problems);
        helper.assertTrue(sites == MOUNTAIN_SITES, "Only " + sites + " mountain sites of " + MOUNTAIN_SITES);
        helper.assertTrue(cliffWalls > 0, "No wall of the mountain sites has cliff blocks");
        helper.assertTrue(problems.isEmpty(), "Problems of the cliff side: " + problems);
        helper.succeed();
    }

    /**
     * The plans of the towns at the first mountain sites (see {@link #mountainWallsHaveNoGateOnACliff}) follow the
     * ground of the site (see {@link PlanGround}). The test reads the terrain from a new terrain cache.
     *
     * <ul>
     *   <li>Each town has all its plots and homes.</li>
     *   <li>For a plan with the level rule, each grid point of each plot with a building is
     *       {@link PlanGround#MAX_PLOT_RISE} blocks or less above or below the surface at its connector. The plots of
     *       the first pieces of the axis streets (the bank and the first jobs) do not obey this rule.</li>
     *   <li>For a core plan with the outline rule, the line of the first wall around the plan climbs no more than the
     *       line around the plaza and the first pieces of the axis streets (see {@link PlanGround#climb}).</li>
     *   <li>One site at least has a steep side: a grid point in the radius of the core is not in the region of the
     *       ground with the small rise.</li>
     * </ul>
     *
     * <p>The log has a line {@code Ground of the plan} for each town, with the rules of its plans.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "plot_terrain_ground")
    public static void mountainPlansFollowTheGround(GameTestHelper helper) {
        Measure measure = new Measure(helper.getLevel());
        FortifiedVillageStructure structure = measure.structure;
        List<String> problems = new ArrayList<>();
        Map<String, Integer> rules = new TreeMap<>();
        int sites = 0;
        int steep = 0;
        for (int i = 0; i < TRIES && sites < MOUNTAIN_SITES; i++) {
            Structure.GenerationContext context = measure.context(new ChunkPos((i % ROW) * LAND_STEP,
                    (i / ROW) * LAND_STEP));
            if (!measure.isMountain(context)
                    || !structure.site(context).equals(Optional.of(VillageSite.Result.ACCEPTED))) {
                continue;
            }
            sites++;
            FortifiedVillageStructure.Variant variant = measure.variant(context, false);
            FortifiedVillageStructure.Layout layout = structure.layout(context, variant, false, TOWN_LEVEL)
                    .orElseThrow();
            String name = "chunk " + context.chunkPos() + " town " + layout.style().name();
            measure.check(context, layout, " ground", problems);
            TerrainCache terrain = TerrainCache.of(context);
            PlotTerrains plots = new PlotTerrains(terrain);
            BlockPos center = layout.center();
            Rotation rotation = layout.village().get(0).getRotation();
            int radius = Math.min(FortifiedVillageStructure.firstRadius(layout.size()),
                    structure.maxDistanceFromCenter());
            PlanGround core = plots.core(center, rotation, radius, variant.wall().margin());
            PlanGround band = plots.band(center, rotation);
            for (int p = 0; p < layout.plans().size(); p++) {
                VillagePlan plan = layout.plans().get(p);
                rules.merge(plan.rules().name(), 1, Integer::sum);
                if (plan.rules() == VillagePlan.Rules.NONE) {
                    continue;
                }
                PlanGround ground = p == 0 ? core : band;
                for (VillagePlan.Plot plot : plan.plots()) {
                    boolean first = p == 0 && plan.pieces().get(plot.piece()).parent() < 0;
                    if (plan.rules().level() && !first && !plot.spare()
                            && !ground.level(plot.area(), plot.x(), plot.z())) {
                        problems.add(name + ": the plot at " + plot.x() + " " + plot.z() + " of the plan " + p
                                + " is far above or below its street");
                    }
                }
            }
            VillagePlan plan = layout.plan();
            if (plan.rules().outline()) {
                int half = layout.village().get(0).getBoundingBox().getXSpan() / 2;
                VillagePlan.Box start = new VillagePlan.Box(-half, -half, half, half);
                VillagePlan.Box all = start;
                for (VillagePlan.Piece piece : plan.pieces()) {
                    all = union(all, piece.box());
                    if (piece.parent() < 0) {
                        // The first piece and the street end after it: the bounds of the plan at its start.
                        start = union(start, piece.box());
                        start = union(start, new VillagePlan.Box(piece.box().minX() + Math.min(0, piece.dir().dx())
                                * StreetStyle.END, piece.box().minZ() + Math.min(0, piece.dir().dz()) * StreetStyle.END,
                                piece.box().maxX() + Math.max(0, piece.dir().dx()) * StreetStyle.END,
                                piece.box().maxZ() + Math.max(0, piece.dir().dz()) * StreetStyle.END));
                    }
                }
                for (VillagePlan.Plot plot : plan.plots()) {
                    all = union(all, plot.area());
                    if (plan.pieces().get(plot.piece()).parent() < 0) {
                        start = union(start, plot.area());
                    }
                }
                long climb = core.climb(all, plan.rules().rise());
                long first = core.climb(start, plan.rules().rise());
                if (climb > first) {
                    problems.add(name + ": the line of the first wall climbs " + climb + " blocks, more than the "
                            + first + " blocks of the first pieces");
                }
            }
            boolean cut = false;
            for (int dx = -radius; dx <= radius && !cut; dx += PlanGround.STEP) {
                for (int dz = -radius; dz <= radius && !cut; dz += PlanGround.STEP) {
                    cut = dx * dx + dz * dz <= radius * radius
                            && !core.inRegion(center.getX() + dx, center.getZ() + dz, PlanGround.MAX_RISE);
                }
            }
            steep += cut ? 1 : 0;
            FolkholdWorld.LOGGER.info("Ground of the plan {}: rules {}, steep side {}", name, rules(layout), cut);
        }
        FolkholdWorld.LOGGER.info("Ground of the plan: {} mountain sites, {} with a steep side, rules {}, problems {}",
                sites, steep, rules, problems);
        helper.assertTrue(sites == MOUNTAIN_SITES, "Only " + sites + " mountain sites of " + MOUNTAIN_SITES);
        helper.assertTrue(steep > 0, "No mountain site has a steep side");
        helper.assertTrue(problems.isEmpty(), "Problems of the ground: " + problems);
        helper.succeed();
    }

    /** The box around 2 boxes of a plan. */
    private static VillagePlan.Box union(VillagePlan.Box first, VillagePlan.Box second) {
        return new VillagePlan.Box(Math.min(first.minX(), second.minX()), Math.min(first.minZ(), second.minZ()),
                Math.max(first.maxX(), second.maxX()), Math.max(first.maxZ(), second.maxZ()));
    }

    /**
     * Checks the cliff side of a wall (see {@link #mountainWallsHaveNoGateOnACliff}), adds its problems, and logs the
     * gates and the cliff blocks.
     *
     * @return true if the wall has cliff blocks
     */
    private static boolean checkCliffSide(String name, VillageWall.Wall wall, Optional<VillageWall.Wall> inner,
            IntBinaryOperator height, List<String> problems) {
        WallLayout layout = wall.layout();
        int[] drops = CliffSide.drops(layout, height);
        // The bases of the columns are the levels of the drops of the wall: the drops of the pieces.
        Map<Long, Integer> bases = new HashMap<>();
        for (StructurePiece piece : wall.pieces()) {
            if (piece instanceof PalisadePiece palisade) {
                int[] columns = palisade.bases();
                for (int i = 0; i < columns.length; i++) {
                    bases.put(ChunkPos.asLong(palisade.startX() + palisade.stepX() * i,
                            palisade.startZ() + palisade.stepZ() * i), columns[i]);
                }
            }
        }
        int[] levels = new int[layout.ringSize()];
        for (int i = 0; i < levels.length; i++) {
            WallLayout.Cell cell = layout.ringCell(i);
            levels[i] = bases.getOrDefault(ChunkPos.asLong(cell.x(), cell.z()), height.applyAsInt(cell.x(), cell.z()));
        }
        int[] baseDrops = CliffSide.drops(layout, levels, height);
        Map<Long, Boolean> cliffs = new HashMap<>();
        int count = 0;
        for (int i = 0; i < baseDrops.length; i++) {
            WallLayout.Cell cell = layout.ringCell(i);
            cliffs.put(ChunkPos.asLong(cell.x(), cell.z()), CliffSide.isCliff(baseDrops[i]));
            count += CliffSide.isCliff(baseDrops[i]) ? 1 : 0;
        }
        if (wall.gateLevels().isEmpty()) {
            problems.add(name + ": no gate");
        }
        for (Map.Entry<WallLayout.Gate, Integer> entry : wall.gateLevels().entrySet()) {
            WallLayout.Gate gate = entry.getKey();
            WallLayout.Side side = gate.side();
            int middle = layout.along(gate);
            int lowest = CliffSide.lowestInFront(layout, side, middle, height);
            if (CliffSide.isCliff(entry.getValue() - lowest)) {
                problems.add(name + ": the gate " + gate + " at the level " + entry.getValue()
                        + " opens on a drop to " + lowest);
            }
            WallLayout.Cell cell = layout.cell(side, middle);
            int x = cell.x() + 2 * side.insideX();
            int z = cell.z() + 2 * side.insideZ();
            boolean path = wall.pieces().stream().anyMatch(piece -> piece instanceof VillagePathPiece
                    && piece.getBoundingBox().minX() <= x && piece.getBoundingBox().maxX() >= x
                    && piece.getBoundingBox().minZ() <= z && piece.getBoundingBox().maxZ() >= z);
            // An inner gate on water gives no path across the band (see VillageWall#bandArm).
            boolean water = wall.waterGates().contains(gate) || inner.stream().flatMap(value -> value.waterGates()
                    .stream()).anyMatch(innerGate -> innerGate.side() == side);
            if (!path && !water) {
                problems.add(name + ": the gate " + gate + " has no path behind it");
            }
        }
        for (StructurePiece piece : wall.pieces()) {
            if (piece instanceof PalisadePiece palisade) {
                for (int i = 0; i < palisade.bases().length; i++) {
                    long column = ChunkPos.asLong(palisade.startX() + palisade.stepX() * i,
                            palisade.startZ() + palisade.stepZ() * i);
                    boolean cliff = cliffs.getOrDefault(column, false);
                    if (palisade.logs() != CliffSide.logs(cliff)) {
                        problems.add(name + ": the column " + ChunkPos.getX(column) + " " + ChunkPos.getZ(column)
                                + " has " + palisade.logs() + " logs, cliff " + cliff);
                    }
                }
            }
        }
        FolkholdWorld.LOGGER.info("Cliff side {}: {} cliff blocks of {} (from the terrain of the line: {}), gates {}",
                name, count, levels.length, Arrays.stream(drops).filter(CliffSide::isCliff).count(),
                wall.gateLevels());
        return count > 0;
    }

    /** The noise terrain of the measure, and the counts of the villages. */
    private static final class Measure {
        private final ServerLevel level;
        private final RegistryAccess registries;
        private final FortifiedVillageStructure structure;
        private final NoiseBasedChunkGenerator generator;
        private final RandomState random;
        private final CultureMap cultures;
        private int badPlots;
        private final Map<PlotTerrain.Result, Integer> roleBuildings = new EnumMap<>(PlotTerrain.Result.class);
        private final Map<PlotTerrain.Result, Integer> missed = new EnumMap<>(PlotTerrain.Result.class);

        Measure(ServerLevel level) {
            this.level = level;
            this.registries = level.registryAccess();
            this.structure = (FortifiedVillageStructure) registries.registryOrThrow(Registries.STRUCTURE)
                    .getOrThrow(Settlements.VILLAGE_STRUCTURE);
            Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                    .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
            this.generator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(
                    registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                            .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings);
            this.random = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), SEED);
            this.cultures = new CultureMap(SEED, generator.getBiomeSource(), random.sampler());
        }

        /**
         * The style of the variant of the land village at the start chunk of a context, from the culture of the
         * region and the biome at the middle of the chunk. The plaza of the generation has its middle near this point.
         */
        String styleAt(Structure.GenerationContext context) {
            return FortifiedVillageStructure.styleOf(variant(context, false)).name();
        }

        /**
         * The variant of the village at the start chunk of a context, from the culture of the region and the biome at
         * the middle of the chunk. The plaza of the generation has its middle near this point.
         */
        FortifiedVillageStructure.Variant variant(Structure.GenerationContext context, boolean maritime) {
            BlockPos middle = context.chunkPos().getMiddleBlockPosition(generator.getSeaLevel());
            String culture = cultures.cultureAt(middle.getX(), middle.getZ());
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(middle.getX()),
                    QuartPos.fromBlock(middle.getY()), QuartPos.fromBlock(middle.getZ()), random.sampler());
            return structure.variantAt(culture, biome, maritime);
        }

        /** True if the biome at the middle of the start chunk of a context is a mountain or a hill. */
        boolean isMountain(Structure.GenerationContext context) {
            BlockPos middle = context.chunkPos().getMiddleBlockPosition(generator.getSeaLevel());
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(middle.getX()),
                    QuartPos.fromBlock(middle.getY()), QuartPos.fromBlock(middle.getZ()), random.sampler());
            return biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL);
        }

        Structure.GenerationContext context(ChunkPos chunk) {
            return new Structure.GenerationContext(registries, generator, generator.getBiomeSource(), random,
                    level.getStructureManager(), SEED, chunk, level, biome -> true);
        }

        /**
         * The context of a start chunk of the search of the coasts, if the chunk is on a beach and the check of its
         * site gives a maritime village. The biome is cheap to read: the check of the site reads the heights.
         */
        Optional<Structure.GenerationContext> maritimeSite(int index) {
            ChunkPos chunk = new ChunkPos((index % ROW) * COAST_STEP, (index / ROW) * COAST_STEP);
            BlockPos middle = chunk.getMiddleBlockPosition(generator.getSeaLevel());
            boolean beach = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(middle.getX()),
                    QuartPos.fromBlock(middle.getY()), QuartPos.fromBlock(middle.getZ()), random.sampler())
                    .is(BiomeTags.IS_BEACH);
            if (!beach) {
                return Optional.empty();
            }
            Structure.GenerationContext context = context(chunk);
            return structure.site(context).filter(site -> site == VillageSite.Result.MARITIME).map(site -> context);
        }

        /** Measures a village and adds its problems. */
        void check(Structure.GenerationContext context, FortifiedVillageStructure.Layout layout, String forced,
                List<String> problems) {
            PoolElementStructurePiece plaza = layout.village().get(0);
            String name = "chunk " + context.chunkPos() + " level " + layout.level() + forced + " "
                    + layout.size().tier() + " " + layout.style().name();
            int missingPlots = layout.plans().stream().mapToInt(VillagePlan::missingPlots).sum();
            int missingHomes = layout.plans().stream().mapToInt(VillagePlan::missingHomes).sum();
            if (missingHomes > 0 || missingPlots > 0) {
                problems.add(name + ": " + missingPlots + " missing plots, " + missingHomes + " missing homes");
            }
            layout.problems().forEach(problem -> problems.add(name + ": " + problem));
            PlotTerrains terrain = new PlotTerrains(TerrainCache.of(context));
            Map<PlotTerrain.Result, Integer> bad = new EnumMap<>(PlotTerrain.Result.class);
            Map<PlotTerrain.Result, Integer> fineBad = new EnumMap<>(PlotTerrain.Result.class);
            for (PoolElementStructurePiece building : layout.buildings().stream().skip(1)
                    .filter(piece -> piece.getElement().getProjection() == StructureTemplatePool.Projection.RIGID)
                    .toList()) {
                BoundingBox box = building.getBoundingBox();
                Optional<VillagePlan.Plot> plot = plotOf(layout.plots(), layout.center(), plaza.getRotation(), box);
                boolean role = plot.map(value -> value.role() != VillagePlan.PlotRole.HOME).orElse(false);
                PlotTerrain.Result result = terrain.check(box);
                if (role) {
                    if (result != PlotTerrain.Result.GOOD) {
                        roleBuildings.merge(result, 1, Integer::sum);
                    }
                    continue;
                }
                if (result != PlotTerrain.Result.GOOD) {
                    bad.merge(result, 1, Integer::sum);
                    // A home on a plot over deep water, when the good plots are short, stands on its foundation: the
                    // same test as the plan (no lava, no steep slope, no water deeper than the stilts).
                    boolean foundation = result == PlotTerrain.Result.DEEP_WATER
                            && plot.filter(value -> !value.good()).isPresent() && terrain.onFoundation(box);
                    if (!foundation) {
                        problems.add(name + ": a building at " + box + " on bad terrain: " + result);
                    }
                }
                PlotTerrain.Result fine = fine(context, box);
                if (fine != PlotTerrain.Result.GOOD) {
                    fineBad.merge(fine, 1, Integer::sum);
                    missed.merge(fine, 1, Integer::sum);
                    FolkholdWorld.LOGGER.info("Plot terrain {}: the grid of {} blocks sees {} under the building {}",
                            name, FINE_STEP, fine, box);
                }
            }
            long spareBad = layout.plots().stream().filter(plot -> plot.spare() && !plot.good()).count();
            badPlots += (int) spareBad;
            FolkholdWorld.LOGGER.info("Plot terrain {}: {} plots, {} spare plots with bad terrain, {} missing plots, "
                    + "{} missing homes, {} buildings, buildings on bad terrain {}, buildings on bad terrain on the "
                    + "grid of {} blocks {}, {} problems", name, layout.plots().size(), spareBad, missingPlots,
                    missingHomes, layout.built(), bad, FINE_STEP, fineBad, layout.problems().size());
        }

        /**
         * Makes the layout of a village with the two modes of the terrain cache, and adds a problem if the plans or the
         * saved pieces are not the same.
         */
        void compare(Structure.GenerationContext context, FortifiedVillageStructure.Variant variant, boolean maritime,
                int startLevel, List<String> problems) {
            TerrainCache chunks = TerrainCache.of(context);
            TerrainCache columns = TerrainCache.direct(generator, random, context.heightAccessor());
            long start = System.nanoTime();
            FortifiedVillageStructure.Layout fast = structure.layout(context, variant, maritime, startLevel,
                    Optional.empty(), chunks).orElseThrow();
            long middle = System.nanoTime();
            FortifiedVillageStructure.Layout slow = structure.layout(context, variant, maritime, startLevel,
                    Optional.empty(), columns).orElseThrow();
            long end = System.nanoTime();
            String name = "chunk " + context.chunkPos() + " level " + startLevel + (maritime ? " maritime" : "");
            FolkholdWorld.LOGGER.info("Terrain cache {}: {} ms with {} noise objects of chunks, {} ms with one read "
                    + "for each column", name, (middle - start) / 1_000_000, chunks.chunks(), (end - middle) / 1_000_000);
            if (chunks.chunks() == 0) {
                problems.add(name + ": no noise object of a chunk");
            }
            if (!fast.plans().equals(slow.plans())) {
                problems.add(name + ": the plans are not the same");
            }
            if (!saved(fast).equals(saved(slow)) || !fast.problems().equals(slow.problems())) {
                problems.add(name + ": the pieces are not the same");
            }
        }

        /** The saved data of the pieces of a layout. */
        private List<CompoundTag> saved(FortifiedVillageStructure.Layout layout) {
            StructurePieceSerializationContext saveContext = StructurePieceSerializationContext.fromLevel(level);
            return layout.allPieces().stream().map(piece -> piece.createTag(saveContext)).toList();
        }

        /** Logs the totals, and ends the test. */
        void end(GameTestHelper helper, int found, int wanted, List<String> problems) {
            FolkholdWorld.LOGGER.info("Plot terrain: {} villages, {} spare plots with bad terrain, buildings of the "
                    + "bank and of the first jobs on bad terrain {}, buildings on bad terrain on the grid of {} blocks "
                    + "only {}", found, badPlots, roleBuildings, FINE_STEP, missed);
            helper.assertTrue(found == wanted, "Only " + found + " villages of " + wanted);
            helper.assertTrue(problems.isEmpty(), "Problems of the villages: " + problems);
            helper.succeed();
        }

        /** The terrain of a box on the grid of {@link #FINE_STEP} blocks, with no cache. */
        private PlotTerrain.Result fine(Structure.GenerationContext context, BoundingBox box) {
            int minY = context.heightAccessor().getMinBuildHeight();
            int maxY = context.heightAccessor().getMaxBuildHeight();
            List<PlotTerrain.Column> columns = new ArrayList<>();
            for (int x : fineAxis(box.minX(), box.maxX())) {
                for (int z : fineAxis(box.minZ(), box.maxZ())) {
                    columns.add(TerrainColumns.column(generator.getBaseColumn(x, z, context.heightAccessor(),
                            random), minY, maxY));
                }
            }
            return PlotTerrain.check(columns);
        }

        /** The points from a minimum to a maximum, every {@link #FINE_STEP} blocks, with the maximum. */
        private static List<Integer> fineAxis(int min, int max) {
            List<Integer> points = new ArrayList<>();
            for (int value = min; value < max; value += FINE_STEP) {
                points.add(value);
            }
            points.add(max);
            return points;
        }
    }

    /** The plot of the plan with the middle of a box in its area. */
    private static Optional<VillagePlan.Plot> plotOf(List<VillagePlan.Plot> plots, BlockPos center,
            Rotation rotation, BoundingBox box) {
        BlockPos middle = box.getCenter();
        Predicate<VillagePlan.Plot> holds = plot -> {
            BlockPos first = center.atY(0).offset(new BlockPos(plot.area().minX(), 0, plot.area().minZ())
                    .rotate(rotation));
            BlockPos second = center.atY(0).offset(new BlockPos(plot.area().maxX(), 0, plot.area().maxZ())
                    .rotate(rotation));
            BoundingBox area = BoundingBox.fromCorners(first, second);
            return middle.getX() >= area.minX() && middle.getX() <= area.maxX() && middle.getZ() >= area.minZ()
                    && middle.getZ() <= area.maxZ();
        };
        return plots.stream().filter(holds).findFirst();
    }
}
