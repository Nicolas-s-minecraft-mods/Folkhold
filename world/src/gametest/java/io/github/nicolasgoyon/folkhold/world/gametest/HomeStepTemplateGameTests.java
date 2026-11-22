package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.block.BuildingSignBlock;
import io.github.nicolasgoyon.folkhold.blocks.block.JobSignBlock;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.construction.ConstructionTargets;
import io.github.nicolasgoyon.folkhold.world.village.VillageGrowth;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the pieces of the steps of the homes that grow in place ({@code village/job_<id>_step<step>},
 * written by {@code tools/structures/village.py}, see {@link VillageGrowth#HOME_STEPS}). The plan test of the pieces is
 * {@link VillageTemplateGameTests#eachVillagePlanHasNoWarning}.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HomeStepTemplateGameTests {
    /** The beds of the step 1 piece of a home. Each step adds this number of beds. */
    private static final int STEP_BEDS = 2;
    /**
     * The number of the jobs with homes that grow in place, by folder of the style: in the base style, the
     * house-workshops in a house shell of the full depth, and in the Valeholm and the Sunreach styles, all the
     * house-workshops. The maritime villages have the house-workshops of the base style.
     */
    static final Map<String, Integer> STEP_JOBS = Map.of("", 13, "maritime/", 13, "valeholm/", 34, "sunreach/", 34);

    private HomeStepTemplateGameTests() {
    }

    /**
     * In each variant of each style, each piece of a step of a home keeps the ground floor of its step 1 piece: the same
     * plot (the same width and the same depth), the job sign, the house sign, the work blocks, the doors and the beds
     * at the same positions. The piece of step 2 has 4 beds, and the piece of step 3 has 6 beds. A worker can walk from
     * the porch to each work block and to each bed (see {@link TemplateReach#unreachable}). Each job with a piece of
     * step 2 has a piece of step 3.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "home_step_templates")
    public static void eachHomeStepKeepsTheGroundFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureTemplateManager templates = level.getStructureManager();
        Registry<StructureTemplatePool> pools = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        List<String> problems = new ArrayList<>();
        int pieces = 0;
        int expected = 0;
        for (String style : JobBuildingTemplateGameTests.STYLES) {
            for (String variant : JobBuildingTemplateGameTests.VARIANTS) {
                ResourceLocation homes = id(style + variant + "homes");
                Map<Integer, List<ResourceLocation>> steps = new HashMap<>();
                for (int step : VillageGrowth.HOME_STEPS) {
                    expected += STEP_JOBS.get(style);
                    Optional<StructureTemplatePool> pool = pools.getOptional(VillageGrowth.stepPool(homes, step));
                    if (pool.isEmpty()) {
                        problems.add("no pool " + VillageGrowth.stepPool(homes, step));
                        continue;
                    }
                    List<ResourceLocation> ids = new ArrayList<>();
                    for (StructurePoolElement element : pool.get().getShuffledTemplates(RandomSource.create(0L))) {
                        VillageReference.templateOf(level, element).ifPresent(ids::add);
                    }
                    steps.put(step, ids.stream().sorted().toList());
                    for (ResourceLocation id : ids) {
                        pieces++;
                        checkStep(level, templates, id, step, problems);
                    }
                }
                List<String> second = jobs(steps.getOrDefault(2, List.of()), 2);
                List<String> third = jobs(steps.getOrDefault(3, List.of()), 3);
                if (!second.equals(third)) {
                    problems.add(style + variant + ": the jobs of step 2 " + second + " and of step 3 " + third
                            + " must be the same");
                }
            }
        }
        helper.assertTrue(problems.isEmpty(), "Problems of the pieces of the steps of the homes: " + problems);
        helper.assertTrue(pieces == expected, "Expected " + expected + " pieces of steps: " + pieces);
        helper.succeed();
    }

    /** Checks the piece of a step against its step 1 piece: see {@link #eachHomeStepKeepsTheGroundFloor}. */
    private static void checkStep(ServerLevel level, StructureTemplateManager templates, ResourceLocation id, int step,
            List<String> problems) {
        String path = id.getPath();
        String suffix = VillageGrowth.STEP_SUFFIX + step;
        if (!path.endsWith(suffix)) {
            problems.add(id + " must end with " + suffix);
            return;
        }
        ResourceLocation firstId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                path.substring(0, path.length() - suffix.length()));
        Optional<StructureTemplate> first = templates.get(firstId);
        Optional<StructureTemplate> grown = templates.get(id);
        if (first.isEmpty() || grown.isEmpty()) {
            problems.add("no template " + (first.isEmpty() ? firstId : id));
            return;
        }
        Vec3i size = first.get().getSize();
        Vec3i grownSize = grown.get().getSize();
        if (grownSize.getX() != size.getX() || grownSize.getZ() != size.getZ() || grownSize.getY() <= size.getY()) {
            problems.add(id + " must have the plot of " + firstId + " and more height: " + grownSize + ", " + size);
        }
        Map<BlockPos, BlockState> blocks = blocks(level, grown.get());
        for (Map.Entry<BlockPos, BlockState> block : blocks(level, first.get()).entrySet()) {
            BlockState state = block.getValue();
            boolean kept = state.getBlock() instanceof JobSignBlock || state.getBlock() instanceof BuildingSignBlock
                    || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof BedBlock
                    || TemplateReach.isWorkBlock(state);
            if (kept && !state.equals(blocks.get(block.getKey()))) {
                problems.add(id + " must keep " + state + " at " + block.getKey().toShortString() + ": "
                        + blocks.get(block.getKey()));
            }
        }
        long beds = blocks.values().stream().filter(state -> state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.HEAD).count();
        if (beds != (long) STEP_BEDS * step) {
            problems.add(id + " has " + beds + " beds, not " + STEP_BEDS * step);
        }
        List<String> unreached = TemplateReach.unreachable(level, grown.get());
        if (!unreached.isEmpty()) {
            problems.add(id + ": no walk from the porch to " + unreached);
        }
    }

    /** The blocks of a template by position, as the builders place them (see {@link ConstructionTargets}). */
    private static Map<BlockPos, BlockState> blocks(ServerLevel level, StructureTemplate template) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        ConstructionTargets.templateBlocks(level, template).forEach(block -> blocks.put(block.pos(), block.state()));
        return blocks;
    }

    /** The jobs of the pieces of a step: {@code job_<id>_step<step>} gives {@code <id>}. */
    private static List<String> jobs(List<ResourceLocation> ids, int step) {
        String suffix = VillageGrowth.STEP_SUFFIX + step;
        return ids.stream().map(ResourceLocation::getPath)
                .map(path -> path.substring(path.lastIndexOf("/job_") + "/job_".length(), path.length()
                        - suffix.length()))
                .sorted().toList();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village/" + path);
    }
}
