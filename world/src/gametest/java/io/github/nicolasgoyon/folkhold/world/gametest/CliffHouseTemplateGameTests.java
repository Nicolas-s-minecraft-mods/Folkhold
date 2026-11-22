package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.construction.ConstructionTargets;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
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
    /** The cliff house of the walk test: 3 storeys, in the base variant of all the cultures. */
    private static final ResourceLocation WALK_HOUSE = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/cliff_house_3");
    /** The processor list of the houses of the base variant: the foundation under the house. */
    private static final ResourceKey<StructureProcessorList> FOUNDATION = ResourceKey.create(
            Registries.PROCESSOR_LIST, ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
                    "village/foundation"));
    /** The size of the floor of the walk test. */
    private static final int WALK_FLOOR = 32;
    /** The origin of the cliff house in the walk test: its floor layer replaces the floor of the arena. */
    private static final BlockPos WALK_ORIGIN = new BlockPos(12, 0, 12);
    /** The rock face of the walk test: a block of stone from this row of the house to 5 blocks behind it. */
    private static final int ROCK_FROM = 4;
    /** The height of the rock face above the floor of the walk test, in blocks: above the walls of the house. */
    private static final int ROCK_HEIGHT = 14;
    /** The rock goes this number of blocks to the sides of the house and behind it. */
    private static final int ROCK_MARGIN = 4;
    /** The rows of the house in front of the face that stand over a hole of 2 blocks in the floor. */
    private static final int HOLE_ROWS = 2;
    /** The position of the villager of the walk test: in front of the house, on the street. */
    private static final BlockPos HEART = new BlockPos(16, 1, 1);

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

    /**
     * A cliff house against a rock face, as on a cliff plot: a villager of a faction village walks from a position in
     * front of the house (the street, toward the heart) into each storey through its door. The path finder of the
     * villagers keeps away from the pits and the drops (see {@link FolkNavigation#hasWay}).
     *
     * <ul>
     *   <li>The scene: a floor of stone, and a block of stone from the middle of the house to {@link #ROCK_MARGIN}
     *       blocks behind it and to its sides, {@link #ROCK_HEIGHT} blocks high. The front rows of the house stand
     *       over a hole of 2 blocks in the floor.</li>
     *   <li>The test places the template with the foundation processor of the houses, as the generation does with no
     *       terrain adaptation: the air of the template removes the rock in its box.</li>
     *   <li>Each door of the house has a way from the villager to the position inside it. The rock stays behind and
     *       beside the box of the house. No block of the floor layer of the house has air under it: the foundation
     *       fills the hole.</li>
     * </ul>
     */
    @GameTest(template = "arena_large", timeoutTicks = 100, batch = "cliff_house_walk", skyAccess = true)
    public static void villagersWalkToEachDoorOfACliffHouse(GameTestHelper helper) {
        TestSupport.buildFloor(helper, WALK_FLOOR);
        ServerLevel level = helper.getLevel();
        StructureTemplate template = level.getStructureManager().get(WALK_HOUSE).orElseThrow();
        Vec3i size = template.getSize();
        BoundingBox rock = BoundingBox.fromCorners(helper.absolutePos(WALK_ORIGIN.offset(-ROCK_MARGIN, 1, ROCK_FROM)),
                helper.absolutePos(WALK_ORIGIN.offset(size.getX() - 1 + ROCK_MARGIN, ROCK_HEIGHT,
                        size.getZ() - 1 + ROCK_MARGIN)));
        BlockPos.betweenClosed(rock.minX(), rock.minY(), rock.minZ(), rock.maxX(), rock.maxY(), rock.maxZ())
                .forEach(pos -> level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS));
        for (int x = 0; x < size.getX(); x++) {
            for (int z = 0; z < HOLE_ROWS; z++) {
                for (int y = -1; y <= 0; y++) {
                    level.setBlock(helper.absolutePos(WALK_ORIGIN.offset(x, y, z)), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().addProcessor(JigsawReplacementProcessor.INSTANCE)
                .setKnownShape(true);
        level.registryAccess().registryOrThrow(Registries.PROCESSOR_LIST).getOrThrow(FOUNDATION).list()
                .forEach(settings::addProcessor);
        BlockPos origin = helper.absolutePos(WALK_ORIGIN);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        Villager villager = helper.spawn(EntityType.VILLAGER, HEART);
        villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, helper.absolutePos(HEART).asLong());
        Map<BlockPos, BlockState> states = new HashMap<>();
        ConstructionTargets.templateBlocks(level, template).forEach(block -> states.put(block.pos(), block.state()));
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            List<String> problems = new ArrayList<>();
            int doors = 0;
            for (Map.Entry<BlockPos, BlockState> block : states.entrySet()) {
                BlockState state = block.getValue();
                if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                doors++;
                BlockPos inside = origin.offset(block.getKey())
                        .relative(state.getValue(DoorBlock.FACING).getOpposite());
                if (!FolkNavigation.hasWay(villager, inside, 0)) {
                    problems.add("no way to the door at " + block.getKey().toShortString());
                }
            }
            if (doors != 3) {
                problems.add(doors + " doors, not 3");
            }
            // The rock behind the back wall and beside the box stays.
            BlockPos behind = origin.offset(BODY / 2, ROCK_HEIGHT - 1, size.getZ());
            BlockPos beside = origin.offset(-1, ROCK_HEIGHT - 1, size.getZ() - 1);
            for (BlockPos pos : List.of(behind, beside)) {
                if (!level.getBlockState(pos).is(Blocks.STONE)) {
                    problems.add("no rock at " + pos.subtract(origin).toShortString());
                }
            }
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos below = origin.offset(x, -1, z);
                    if (level.getBlockState(below).isAir()) {
                        problems.add("air under the floor at " + x + ", " + z);
                    }
                }
            }
            villager.discard();
            // The scene goes away: the rock and the house above the floor.
            BlockPos.betweenClosed(rock.minX(), rock.minY(), origin.getZ(), rock.maxX(), origin.getY() + size.getY(),
                    rock.maxZ()).forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS));
            helper.assertTrue(problems.isEmpty(), "Problems of the walk to the cliff house: " + problems);
        }).thenSucceed();
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
