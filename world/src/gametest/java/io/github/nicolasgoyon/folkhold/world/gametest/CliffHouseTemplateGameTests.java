package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.village.TemplateSigns;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the cliff houses: the pieces of the pool {@code cliff_houses} of each style and each biome
 * variant, written by {@code tools/structures/village.py}. A cliff house is a tall house with its back wall against a
 * rock face. The plan test of these pieces is {@link VillageTemplateGameTests#eachVillagePlanHasNoWarning}, and the
 * reach test of their beds is {@link HomeTemplateGameTests#eachHomeWorkBlockAndBedCanBeReached}.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CliffHouseTemplateGameTests {
    /** The cliff houses of each variant, by template name, with their number of storeys. */
    static final Map<String, Integer> CLIFF_HOUSES = Map.of("cliff_house_2", 2, "cliff_house_3", 3);
    /** The height of a storey, in blocks: the floor of the storey n (from 0) is at the layer n times this height. */
    private static final int STOREY = 4;
    /** The beds of each storey. */
    private static final int STOREY_BEDS = 2;
    /** The width of the body of the house, from x = 0: the stair of the upper doors is east of it. */
    private static final int BODY = 7;

    private CliffHouseTemplateGameTests() {
    }

    /**
     * In each style and each variant, the pool {@code cliff_houses} has the cliff houses of {@link #CLIFF_HOUSES}. Each
     * cliff house has:
     *
     * <ul>
     *   <li>the plot of the house of the base style: 9 x 9 blocks;</li>
     *   <li>one house sign, no job sign, and {@link #STOREY_BEDS} home beds for each storey (see
     *       {@link TemplateSigns});</li>
     *   <li>a door on each storey, at the level of its floor, and a walk from the front of the piece to each door (see
     *       {@link TemplateReach#reached});</li>
     *   <li>a back wall of full blocks on all the storeys of the body: the wall against the rock has no opening.</li>
     * </ul>
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "home_templates")
    public static void eachCliffHouseHasADoorOnEachStorey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Registry<StructureTemplatePool> pools = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        List<String> problems = new ArrayList<>();
        int pieces = 0;
        for (String style : JobBuildingTemplateGameTests.STYLES) {
            for (String variant : JobBuildingTemplateGameTests.VARIANTS) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
                        "village/" + style + variant + VillagePieces.CLIFF_HOUSES);
                Optional<StructureTemplatePool> pool = pools.getOptional(id);
                if (pool.isEmpty()) {
                    problems.add("no pool " + id);
                    continue;
                }
                Set<String> names = new TreeSet<>();
                for (StructurePoolElement element : pool.get().getShuffledTemplates(RandomSource.create(0L))) {
                    Optional<ResourceLocation> template = VillageReference.templateOf(level, element);
                    if (template.isEmpty()) {
                        problems.add(id + " has an element with no template");
                        continue;
                    }
                    String path = template.get().getPath();
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    names.add(name);
                    Integer storeys = CLIFF_HOUSES.get(name);
                    if (storeys == null) {
                        problems.add(id + " has the piece " + template.get());
                        continue;
                    }
                    pieces++;
                    check(level, template.get(), storeys, problems);
                }
                if (!names.equals(CLIFF_HOUSES.keySet())) {
                    problems.add(id + " has the pieces " + names + ", not " + new TreeSet<>(CLIFF_HOUSES.keySet()));
                }
            }
        }
        int expected = CLIFF_HOUSES.size() * JobBuildingTemplateGameTests.STYLES.size()
                * JobBuildingTemplateGameTests.VARIANTS.size();
        helper.assertTrue(problems.isEmpty(), "Problems of the cliff houses: " + problems);
        helper.assertTrue(pieces == expected, "Expected " + expected + " cliff houses: " + pieces);
        helper.succeed();
    }

    /** Checks one cliff house: see {@link #eachCliffHouseHasADoorOnEachStorey}. */
    private static void check(ServerLevel level, ResourceLocation id, int storeys, List<String> problems) {
        Optional<StructureTemplate> found = level.getStructureManager().get(id);
        if (found.isEmpty()) {
            problems.add("no template " + id);
            return;
        }
        StructureTemplate template = found.get();
        Vec3i size = template.getSize();
        if (size.getX() != 9 || size.getZ() != 9 || size.getY() <= storeys * STOREY) {
            problems.add(id + " has the size " + size);
            return;
        }
        TemplateSigns signs = TemplateSigns.of(id, template);
        if (!signs.kinds().equals(Map.of("house", 1)) || !signs.jobs().isEmpty()
                || signs.beds() != STOREY_BEDS * storeys) {
            problems.add(id + " has the signs and the beds " + signs);
        }
        Map<BlockPos, BlockState> states = new HashMap<>();
        Optional<Set<BlockPos>> reached = TemplateReach.reached(level, template, states);
        if (reached.isEmpty()) {
            problems.add(id + " has no front connector");
            return;
        }
        Set<Integer> doorStoreys = new TreeSet<>();
        states.forEach((pos, state) -> {
            if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                if (Math.floorMod(pos.getY() - 1, STOREY) != 0) {
                    problems.add(id + " has a door off the level of a floor at " + pos.toShortString());
                }
                doorStoreys.add((pos.getY() - 1) / STOREY);
                if (!TemplateReach.isReached(reached.get(), pos)) {
                    problems.add(id + " has no walk to the door at " + pos.toShortString());
                }
            }
        });
        Set<Integer> all = new TreeSet<>();
        for (int storey = 0; storey < storeys; storey++) {
            all.add(storey);
        }
        if (!doorStoreys.equals(all)) {
            problems.add(id + " has doors on the storeys " + doorStoreys + ", not " + all);
        }
        int back = size.getZ() - 1;
        for (int x = 0; x < BODY; x++) {
            for (int y = 1; y <= storeys * STOREY; y++) {
                BlockPos pos = new BlockPos(x, y, back);
                BlockState state = states.get(pos);
                if (state == null || !Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE,
                        BlockPos.ZERO))) {
                    problems.add(id + " has an opening in the back wall at " + pos.toShortString() + ": " + state);
                    return;
                }
            }
        }
    }
}
