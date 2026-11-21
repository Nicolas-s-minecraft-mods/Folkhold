package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.block.BuildingSignKind;
import io.github.nicolasgoyon.folkhold.blocks.block.JobSignBlockEntity;
import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the pieces of the cave village ({@code village/cave/}, written by
 * {@code tools/structures/village.py}) and for the processor of their shell
 * ({@link io.github.nicolasgoyon.folkhold.world.village.structure.RockShellProcessor}).
 *
 * <p>The tests place the pieces in a block of rock, as the generation places them: with the jigsaw replacement and the
 * processor list {@code village/cave/rock}. The GameTest world is flat, under the Y 0: a hole of the shell becomes
 * cobbled deepslate there.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CaveTemplateGameTests {
    /** The processor list of the cave pieces. */
    private static final ResourceKey<StructureProcessorList> ROCK = ResourceKey.create(Registries.PROCESSOR_LIST,
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village/cave/rock"));
    /** The origin of the street in the test area: the block of rock goes 2 blocks around it. */
    private static final BlockPos STREET = new BlockPos(4, 2, 4);
    /** The minimum number of beds of a house-workshop. */
    private static final int HOME_BEDS = 2;
    /** The cave pieces of the light test, with their origins in the test area. */
    private static final Map<String, BlockPos> LIGHT_PIECES = Map.of(
            "plaza", new BlockPos(2, 2, 2),
            "street_cross", new BlockPos(22, 2, 2),
            "gate", new BlockPos(30, 2, 2),
            "house_cottage", new BlockPos(42, 2, 2),
            "market", new BlockPos(2, 2, 22),
            "bank", new BlockPos(22, 2, 22),
            "job_farmer", new BlockPos(34, 2, 22),
            "job_miner", new BlockPos(2, 2, 40),
            "tunnel", new BlockPos(16, 2, 40));
    /** The light test waits this number of ticks at most for the light of the level. */
    private static final int LIGHT_TICKS = 100;

    private CaveTemplateGameTests() {
    }

    /**
     * The street of the cave village in a block of stone, with holes and other rocks at the positions of its shell:
     *
     * <ul>
     *   <li>The tunnel and the openings of the plots are air: the piece removes the stone there.</li>
     *   <li>A hole of air, of water and of lava in the shell becomes cobbled deepslate (the rock under the Y 0): the
     *       shell is closed. The hole behind the shell stays: the piece does not change the world outside its
     *       shell.</li>
     *   <li>The stone of the shell becomes cobblestone, the granite polished granite, the deepslate cobbled deepslate.
     *       An ore stays. A sand block (a falling block) becomes cobbled deepslate.</li>
     *   <li>The stone outside the shell stays stone.</li>
     * </ul>
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "cave_templates")
    public static void caveStreetKeepsTheRockAndClosesItsShell(GameTestHelper helper) {
        StructureTemplate template = template(helper, "street");
        fill(helper, STREET, template.getSize(), 2, Blocks.STONE);
        // The positions of the template: the west wall is x = 0, the east wall x = 4 and the ceiling y = 5. The plots
        // have their openings at z = 5 to 11.
        set(helper, 0, 2, 1, Blocks.AIR);
        set(helper, -1, 2, 1, Blocks.AIR);
        set(helper, 4, 2, 1, Blocks.WATER);
        set(helper, 5, 2, 1, Blocks.WATER);
        set(helper, 2, 5, 7, Blocks.LAVA);
        set(helper, 2, 6, 7, Blocks.LAVA);
        set(helper, 2, 5, 1, Blocks.GRANITE);
        set(helper, 0, 3, 2, Blocks.IRON_ORE);
        set(helper, 4, 3, 2, Blocks.DEEPSLATE);
        set(helper, 0, 1, 3, Blocks.SAND);
        place(helper, template, STREET);

        List<String> problems = new ArrayList<>();
        for (int z = 0; z < template.getSize().getZ(); z++) {
            for (int x = 1; x <= 3; x++) {
                for (int y = 1; y <= 3; y++) {
                    expect(helper, problems, x, y, z, Blocks.AIR, "the tunnel");
                }
            }
        }
        for (int z = 5; z <= 11; z++) {
            for (int y = 1; y <= 3; y++) {
                if (y != 1 || z != 8) {
                    expect(helper, problems, 0, y, z, Blocks.AIR, "the opening of the west plot");
                    expect(helper, problems, 4, y, z, Blocks.AIR, "the opening of the east plot");
                }
            }
        }
        expect(helper, problems, 0, 2, 1, Blocks.COBBLED_DEEPSLATE, "a hole of air in the shell");
        expect(helper, problems, -1, 2, 1, Blocks.AIR, "the hole of air behind the shell");
        expect(helper, problems, 4, 2, 1, Blocks.COBBLED_DEEPSLATE, "a hole of water in the shell");
        expect(helper, problems, 5, 2, 1, Blocks.WATER, "the water behind the shell");
        expect(helper, problems, 2, 5, 7, Blocks.COBBLED_DEEPSLATE, "a hole of lava in the shell");
        expect(helper, problems, 2, 6, 7, Blocks.LAVA, "the lava behind the shell");
        expect(helper, problems, 2, 5, 1, Blocks.POLISHED_GRANITE, "granite in the shell");
        expect(helper, problems, 0, 3, 2, Blocks.IRON_ORE, "an ore in the shell");
        expect(helper, problems, 4, 3, 2, Blocks.COBBLED_DEEPSLATE, "deepslate in the shell");
        expect(helper, problems, 0, 1, 3, Blocks.COBBLED_DEEPSLATE, "sand in the shell");
        expect(helper, problems, 0, 2, 3, Blocks.COBBLESTONE, "stone in the west wall");
        expect(helper, problems, 2, 5, 0, Blocks.COBBLESTONE, "stone in the ceiling");
        expect(helper, problems, -1, 2, 3, Blocks.STONE, "the stone behind the west wall");
        expect(helper, problems, 2, 6, 3, Blocks.STONE, "the stone above the ceiling");
        expect(helper, problems, 0, 0, 3, Blocks.STONE_BRICKS, "the floor");
        fill(helper, STREET, template.getSize(), 2, Blocks.AIR);
        helper.assertTrue(problems.isEmpty(), "Blocks of the cave street: " + problems);
        helper.succeed();
    }

    /**
     * Some cave pieces in blocks of stone, as the generation places them: each position in the box of a piece where a
     * monster can spawn (a position with no collision and no fluid, on a block that lets a zombie spawn) has a block
     * light of 1 or more. Thus no
     * monster spawns in the halls, the tunnels and the rooms. The structure script checks the light of each cave
     * piece with the lights of the piece (see {@code cave_lit}).
     */
    @GameTest(template = "arena_huge", timeoutTicks = LIGHT_TICKS + 20, batch = "cave_templates")
    public static void cavePiecesHaveLightOnEachFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BoundingBox> boxes = new ArrayList<>();
        LIGHT_PIECES.forEach((name, origin) -> {
            StructureTemplate template = template(helper, name);
            fill(helper, origin, template.getSize(), 1, Blocks.STONE);
            boxes.add(place(helper, template, origin));
        });
        helper.succeedWhen(() -> {
            List<String> dark = new ArrayList<>();
            for (BoundingBox box : boxes) {
                BlockPos.betweenClosed(box.minX(), box.minY() + 1, box.minZ(), box.maxX(), box.maxY(), box.maxZ())
                        .forEach(pos -> {
                            BlockState state = level.getBlockState(pos);
                            BlockPos below = pos.below();
                            if (state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
                                    && level.getBlockState(below).isValidSpawn(level, below, EntityType.ZOMBIE)
                                    && level.getBrightness(LightLayer.BLOCK, pos) < 1 && dark.size() < 10) {
                                dark.add(pos.toShortString());
                            }
                        });
            }
            helper.assertTrue(dark.isEmpty(), "Dark floor positions of the cave pieces: " + dark);
            LIGHT_PIECES.forEach((name, origin) -> fill(helper, origin, template(helper, name).getSize(), 1,
                    Blocks.AIR));
        });
    }

    /**
     * Each job with a house-workshop of all the cultures has a carved room in the cave village, and this room is a
     * house-workshop: 1 job sign with the job, 1 house sign and {@link #HOME_BEDS} beds or more. A worker can go from the front of the room to each work block and to
     * each bed (see {@link TemplateReach#unreachable}).
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "cave_templates")
    public static void caveRoomsOfTheJobsAreHouseWorkshops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> problems = new ArrayList<>();
        int rooms = 0;
        for (String id : EconomyData.jobs().keySet()) {
            String job = ResourceLocation.parse(id).getPath();
            ResourceLocation building = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
                    "village/job_" + job);
            if (level.getStructureManager().get(building).isEmpty()) {
                // A job of the tests has no building of all the cultures.
                continue;
            }
            Optional<StructureTemplate> found = level.getStructureManager().get(cave("job_" + job));
            if (found.isEmpty()) {
                problems.add("no cave room for the job " + job);
                continue;
            }
            rooms++;
            StructureTemplate template = found.get();
            List<StructureTemplate.StructureBlockInfo> signs = template.filterBlocks(BlockPos.ZERO,
                    new StructurePlaceSettings(), ModBlocks.JOB_SIGN.get());
            if (signs.size() != 1 || signs.get(0).nbt() == null
                    || !job.equals(signs.get(0).nbt().getString(JobSignBlockEntity.JOB))) {
                problems.add(job + ": " + signs.size() + " job signs");
            }
            int houseSigns = template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(),
                    ModBlocks.buildingSign(BuildingSignKind.HOUSE)).size();
            long beds = BuiltInRegistries.BLOCK.stream().filter(BedBlock.class::isInstance)
                    .flatMap(bed -> template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), bed).stream())
                    .filter(info -> info.state().getValue(BedBlock.PART) == BedPart.HEAD).count();
            if (houseSigns != 1 || beds < HOME_BEDS) {
                problems.add(job + ": " + houseSigns + " house signs and " + beds + " beds");
            }
            List<String> blocks = TemplateReach.unreachable(level, template);
            if (!blocks.isEmpty()) {
                problems.add(job + ": " + blocks);
            }
        }
        helper.assertTrue(rooms > 0, "No job for the cave rooms");
        helper.assertTrue(problems.isEmpty(), "Problems of the cave rooms of the jobs: " + problems);
        helper.succeed();
    }

    /** The id of a cave piece. */
    private static ResourceLocation cave(String name) {
        return ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village/cave/" + name);
    }

    private static StructureTemplate template(GameTestHelper helper, String name) {
        return helper.getLevel().getStructureManager().get(cave(name))
                .orElseThrow(() -> new IllegalStateException("No cave piece " + name));
    }

    /**
     * Fills the box of a template at an origin, with a margin on each side and 1 block under it: a block of stone
     * before the test, and air after it.
     */
    private static void fill(GameTestHelper helper, BlockPos origin, Vec3i size, int margin, Block block) {
        for (int x = -margin; x < size.getX() + margin; x++) {
            for (int y = -1; y < size.getY() + margin; y++) {
                for (int z = -margin; z < size.getZ() + margin; z++) {
                    helper.getLevel().setBlock(helper.absolutePos(origin.offset(x, y, z)), block.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Sets a block at a position of the street template, with no update of the neighbors. */
    private static void set(GameTestHelper helper, int x, int y, int z, Block block) {
        helper.getLevel().setBlock(helper.absolutePos(STREET.offset(x, y, z)), block.defaultBlockState(),
                Block.UPDATE_CLIENTS);
    }

    /** Places a cave template as the generation does, and gives its box in the level. */
    private static BoundingBox place(GameTestHelper helper, StructureTemplate template, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        StructurePlaceSettings settings = new StructurePlaceSettings().addProcessor(JigsawReplacementProcessor.INSTANCE)
                .setKnownShape(true);
        level.registryAccess().registryOrThrow(Registries.PROCESSOR_LIST).getOrThrow(ROCK).list()
                .forEach(settings::addProcessor);
        BlockPos origin = helper.absolutePos(relative);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        return template.getBoundingBox(settings, origin);
    }

    /** Adds a problem when the block at a position of the street template is not the expected block. */
    private static void expect(GameTestHelper helper, List<String> problems, int x, int y, int z, Block block,
            String what) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(STREET.offset(x, y, z)));
        if (!state.is(block)) {
            problems.add(what + " at " + x + ", " + y + ", " + z + ": " + state.getBlock() + " for " + block);
        }
    }
}
