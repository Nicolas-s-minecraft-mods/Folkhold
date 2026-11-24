package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.construction.BuildPos;
import io.github.nicolasgoyon.folkhold.core.sim.construction.ConstructionPlanner;
import io.github.nicolasgoyon.folkhold.core.sim.construction.Plan;
import io.github.nicolasgoyon.folkhold.core.sim.construction.Reach;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.construction.ConstructionTargets;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageGrowth;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.WallSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for all the templates of the village structure (see {@link VillagePieces}), written by
 * {@code tools/structures/village.py}.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageTemplateGameTests {
    /** The number of variants of the village structure: 4 styles in 6 biome variants each. */
    private static final int VARIANTS = JobBuildingTemplateGameTests.STYLES.size()
            * JobBuildingTemplateGameTests.VARIANTS.size();
    /** The number of jobs with a house-workshop piece in each variant. */
    private static final int JOBS = 34;
    /**
     * The number of the other rigid pieces of each variant: the plaza, the bank, the market, the tower, the gate, the
     * stone tower, the stone gate, the 2 fields and the mill.
     */
    private static final int OTHER_PIECES = 10;
    /**
     * The number of the street pieces of each variant: {@code street}, {@code street_bank} and
     * {@code street_end}, and the planned streets {@code street_next}, {@code street_cross} and {@code street_side}
     * (see {@link VillagePieces#PLANNED_STREETS}).
     */
    private static final int STREET_PIECES = 6;
    /**
     * The number of the short street pieces: the piece {@code street_short} of the Valeholm style, in each biome
     * variant. The other styles have no short segment (see
     * {@link io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle#shortSegment()}).
     */
    private static final int SHORT_STREET_PIECES = JobBuildingTemplateGameTests.VARIANTS.size();
    /**
     * The number of the pieces of the cave village that are not homes: a room for each job, the main hall, the bank,
     * the market, the 6 pieces of the tunnels of the streets, and the tunnel and the gate of the entrance. All the cave
     * pieces are rigid.
     */
    private static final int CAVE_PIECES = JOBS + 3 + STREET_PIECES + 2;
    /**
     * The number of the pieces of the steps of the homes that grow in place: for each style, its jobs with homes that
     * grow in place, in each step and in each biome variant.
     */
    private static final int HOME_STEP_PIECES = HomeStepTemplateGameTests.STEP_JOBS.values().stream()
            .mapToInt(Integer::intValue).sum() * VillageGrowth.HOME_STEPS.size()
            * JobBuildingTemplateGameTests.VARIANTS.size();
    /**
     * The number of the pieces that the plan test checks: all the rigid pieces of all the variants. The home pieces
     * include the cliff houses.
     */
    private static final int PLAN_PIECES = (JOBS + OTHER_PIECES) * VARIANTS + HomeTemplateGameTests.HOME_PIECES
            + CAVE_PIECES + HOME_STEP_PIECES;
    /**
     * The number of the pieces that the plan test does not check: the streets of all the variants, and the short
     * Valeholm streets.
     */
    private static final int SKIPPED_PIECES = STREET_PIECES * VARIANTS + SHORT_STREET_PIECES;
    /**
     * The size of the floor of the plan test, in blocks: the crossroad of the cave village (27 blocks long, a rigid
     * piece) and a margin around it.
     */
    private static final int PLAN_FLOOR = 33;
    /** The origin of the template of the plan test: the floor goes around the piece on each side. */
    private static final BlockPos PLAN_ORIGIN = new BlockPos(3, 1, 3);

    /** The folder of the templates of the village structure, in the namespace of the mod. */
    private static final String VILLAGE_FOLDER = "village/";
    /**
     * The templates of the village folder that are not pieces of the structure, in the resources of the GameTests: the
     * compound of the GameTests ({@code center.nbt}) and the house-workshop of the test variant of the growth tests
     * ({@code test/job_test_smith.nbt}, see {@link VillageGrowthGameTests}).
     */
    private static final Set<ResourceLocation> TEST_TEMPLATES = Set.of(
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, VILLAGE_FOLDER + "center"),
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, VILLAGE_FOLDER + "test/job_test_smith"));

    private VillageTemplateGameTests() {
    }

    /**
     * Each template file of the village folder is a piece of the structure: {@link VillagePieces#all} reaches it, or it
     * is a template of the GameTests ({@link #TEST_TEMPLATES}). A template file that no pool has is an error: the
     * structure script writes only the templates of the pools.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_templates")
    public static void eachVillageTemplateIsInAPool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> problems = new ArrayList<>();
        Set<ResourceLocation> pieces = VillagePieces.all(level, problems).keySet();
        List<ResourceLocation> files = level.getStructureManager().listTemplates()
                .filter(id -> id.getNamespace().equals(FolkholdWorld.MOD_ID)
                        && id.getPath().startsWith(VILLAGE_FOLDER))
                .sorted()
                .toList();
        List<ResourceLocation> unused = files.stream()
                .filter(id -> !pieces.contains(id) && !TEST_TEMPLATES.contains(id))
                .toList();
        FolkholdWorld.LOGGER.info("Template files of the village folder: {}, pieces of the pools: {}", files.size(),
                pieces.size());
        helper.assertTrue(problems.isEmpty(), "Problems of the village pieces: " + problems);
        helper.assertTrue(files.containsAll(TEST_TEMPLATES), "Missing templates of the GameTests: " + TEST_TEMPLATES);
        helper.assertTrue(unused.isEmpty(), "Template files of the village folder in no pool: " + unused);
        helper.succeed();
    }

    /**
     * Each variant of the village structure has a stone wall (see {@link WallSettings.StoneWall}), except the cave
     * variant: a cave village has no palisade. The stone tower and
     * the stone gate have the shape of the wooden tower and gate: the same positions, and at each position the same
     * collision shape. Thus the watch post, the stair and the passage of the gate are at the same places.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_templates")
    public static void stoneWallHasTheShapeOfThePalisade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        List<WallSettings> walls = new ArrayList<>(List.of(structure.wall()));
        // A cave village has no palisade: its variant has no stone wall.
        structure.variants().stream().filter(variant -> !variant.cave()).forEach(variant -> walls.add(variant.wall()));
        List<String> problems = new ArrayList<>();
        for (WallSettings wall : walls) {
            Optional<WallSettings.StoneWall> stone = wall.stone();
            if (stone.isEmpty()) {
                problems.add(wall.tower() + ": no stone wall");
                continue;
            }
            compareShapes(level, wall.tower(), stone.get().tower(), problems);
            compareShapes(level, wall.gate(), stone.get().gate(), problems);
        }
        helper.assertTrue(problems.isEmpty(), "Stone pieces with another shape: " + problems);
        helper.succeed();
    }

    /** Adds a problem for each position where 2 templates have blocks with another collision shape. */
    private static void compareShapes(ServerLevel level, ResourceLocation wooden, ResourceLocation stone,
            List<String> problems) {
        Optional<StructureTemplate> first = level.getStructureManager().get(wooden);
        Optional<StructureTemplate> second = level.getStructureManager().get(stone);
        if (first.isEmpty() || second.isEmpty()) {
            problems.add("no template " + (first.isEmpty() ? wooden : stone));
            return;
        }
        if (!first.get().getSize().equals(second.get().getSize())) {
            problems.add(stone + " has the size " + second.get().getSize());
            return;
        }
        Map<BlockPos, BlockState> stoneBlocks = new HashMap<>();
        ConstructionTargets.templateBlocks(level, second.get())
                .forEach(block -> stoneBlocks.put(block.pos(), block.state()));
        for (ConstructionTargets.TemplateBlock block : ConstructionTargets.templateBlocks(level, first.get())) {
            BlockState other = stoneBlocks.get(block.pos());
            if (other == null || Shapes.joinIsNotEmpty(block.state().getCollisionShape(level, BlockPos.ZERO),
                    other.getCollisionShape(level, BlockPos.ZERO), BooleanOp.NOT_SAME)) {
                problems.add(stone + " at " + block.pos() + ": " + other + " in place of " + block.state());
            }
        }
    }

    /**
     * Builders can place each block of each piece that they build or repair: the construction plan of the piece on a
     * flat floor has no warning. A block with no position to stand for a builder gives the warning {@code NO_STAND}.
     *
     * <ul>
     *   <li>The builders build the pieces of the pools of the growth (see
     *       {@link io.github.nicolasgoyon.folkhold.world.village.VillageGrowth#pools}): the house-workshops, the
     *       buildings of the levels, the market, the fields and the mills.</li>
     *   <li>The builders repair each rigid piece of the generation (see
     *       {@link io.github.nicolasgoyon.folkhold.world.village.VillageReference}): the plaza, the bank, the homes,
     *       the house-workshops, the market, the towers and the gates.</li>
     *   <li>The test does not check the streets: their projection is {@code TERRAIN_MATCHING}. Their blocks go to the
     *       height of the terrain, thus the template does not give the blocks of the street. The builders never build a
     *       street piece, and the repairs fill only the holes of the surface of the streets (see
     *       {@link io.github.nicolasgoyon.folkhold.world.village.StreetBlocks}). The log gives the skipped
     *       pieces.</li>
     * </ul>
     *
     * <p>The test checks exactly {@link #PLAN_PIECES} pieces and skips exactly {@link #SKIPPED_PIECES} pieces: a
     * missing template or a new piece fails it.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 20, batch = "village_templates", skyAccess = true)
    public static void eachVillagePlanHasNoWarning(GameTestHelper helper) {
        long start = System.nanoTime();
        TestSupport.buildFloor(helper, PLAN_FLOOR);
        ServerLevel level = helper.getLevel();
        List<String> problems = new ArrayList<>();
        Map<ResourceLocation, VillagePieces.Piece> pieces = VillagePieces.all(level, problems);
        BlockPos origin = helper.absolutePos(PLAN_ORIGIN);
        int room = PLAN_FLOOR - 2 * PLAN_ORIGIN.getX();
        List<ResourceLocation> skipped = new ArrayList<>();
        int checked = 0;
        for (VillagePieces.Piece piece : pieces.values()) {
            if (piece.projection() == StructureTemplatePool.Projection.TERRAIN_MATCHING) {
                skipped.add(piece.template());
                continue;
            }
            Optional<StructureTemplate> template = level.getStructureManager().get(piece.template());
            if (template.isEmpty()) {
                problems.add("no template " + piece.template());
                continue;
            }
            Vec3i size = template.get().getSize();
            if (size.getX() > room || size.getZ() > room) {
                problems.add(piece.template() + " is larger than the floor: " + size);
                continue;
            }
            checked++;
            Plan plan = ConstructionPlanner.plan(ConstructionTargets.fromTemplate(level, template.get(), origin,
                    new StructurePlaceSettings()), ConstructionTargets.view(level), Reach.DEFAULT);
            if (!plan.warnings().isEmpty()) {
                problems.add(piece.template() + ": " + plan.warnings().stream().map(warning -> warning.problem()
                        + " " + relative(warning.pos(), origin)).toList());
            }
        }
        FolkholdWorld.LOGGER.info("Plan test of the village pieces: {} pieces checked in {} ms, {} street pieces"
                + " skipped (the builders do not build them): {}", checked, (System.nanoTime() - start) / 1_000_000L,
                skipped.size(), skipped);
        helper.assertTrue(problems.isEmpty(), "Plans of village pieces with warnings: " + problems);
        helper.assertTrue(checked == PLAN_PIECES, "Expected " + PLAN_PIECES + " pieces to check: " + checked);
        helper.assertTrue(skipped.size() == SKIPPED_PIECES, "Expected " + SKIPPED_PIECES + " street pieces to skip: "
                + skipped.size());
        helper.succeed();
    }

    /** The position of a block of the plan, relative to the origin of the template. */
    private static String relative(BuildPos pos, BlockPos origin) {
        return (pos.x() - origin.getX()) + ", " + (pos.y() - origin.getY()) + ", " + (pos.z() - origin.getZ());
    }
}
