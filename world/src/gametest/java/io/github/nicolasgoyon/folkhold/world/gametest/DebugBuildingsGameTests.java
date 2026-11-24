package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.command.DebugBuildings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game test of the debug grid of the buildings (see {@link DebugBuildings}): a small grid of 2 rows, far from the
 * areas of the other tests.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DebugBuildingsGameTests {
    /** The north-west corner of the grid: far from the spawn and from the areas of the other tests. */
    private static final BlockPos ORIGIN = new BlockPos(-384_000, -60, 384_000);
    /** The buildings of the grid: a house-workshop with 3 steps and a cottage with 1 step. */
    private static final List<String> BUILDINGS = List.of("job_baker", "house_cottage");

    private DebugBuildingsGameTests() {
    }

    /**
     * The grid places each template of its rows, with the steps in columns and the gap between them. The signs give
     * the name, the beds and the job posts of each template. The grid places {@value DebugBuildings#TEMPLATES_PER_TICK}
     * templates in one tick at most. A second grid does not start while the first grid runs.
     */
    @GameTest(template = "empty", timeoutTicks = TestSupport.WORKER_TICKS, batch = "debug_buildings")
    public static void debugGridPlacesTheBuildingsAndTheirSteps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Map<Integer, ResourceLocation>> rows = DebugBuildings.styleRows(level.getStructureManager()
                .listTemplates(), DebugBuildings.folder("base", DebugBuildings.DEFAULT_VARIANT)).stream()
                .filter(row -> BUILDINGS.contains(DebugBuildings.buildingOf(name(row.get(0))))).toList();
        helper.assertTrue(rows.size() == BUILDINGS.size(), "The style base must have the rows " + BUILDINGS + ": "
                + rows);
        helper.assertTrue(rows.stream().anyMatch(row -> row.size() == 3), "The baker must have 3 steps: " + rows);
        List<Component> messages = new ArrayList<>();
        DebugBuildings.End[] end = {null};
        DebugBuildings.Result[] result = {null};
        helper.assertTrue(DebugBuildings.start(level, ORIGIN, rows, messages::add, (ended, placed) -> {
            end[0] = ended;
            result[0] = placed;
        }), "The grid must start");
        helper.assertFalse(DebugBuildings.start(level, ORIGIN, rows, message -> { }, (ended, placed) -> { }),
                "A second grid must not start while the first grid runs");
        GameTestSequence sequence = helper.startSequence();
        TestSupport.thenWaitForWorker(helper, sequence, () -> end[0] != null,
                () -> "The grid must end: " + messages.stream().map(Component::getString).toList())
                .thenExecute(() -> {
                    helper.assertTrue(end[0] == DebugBuildings.End.PLACED, "The grid must place its templates: "
                            + end[0]);
                    check(helper, level, result[0]);
                })
                .thenSucceed();
    }

    private static void check(GameTestHelper helper, ServerLevel level, DebugBuildings.Result result) {
        List<DebugBuildings.Cell> cells = result.cells();
        helper.assertTrue(cells.size() == 4, "The grid must have 4 templates: " + cells);
        helper.assertTrue(result.maxTemplatesInTick() >= 1
                && result.maxTemplatesInTick() <= DebugBuildings.TEMPLATES_PER_TICK,
                "The grid must place " + DebugBuildings.TEMPLATES_PER_TICK + " templates in one tick at most: "
                        + result.maxTemplatesInTick());
        for (DebugBuildings.Cell cell : cells) {
            BoundingBox box = box(cell);
            helper.assertTrue(result.box().isInside(box.getCenter()), "The template must be in the grid: " + cell);
            for (DebugBuildings.Cell other : cells) {
                if (other != cell) {
                    helper.assertFalse(box.inflatedBy(DebugBuildings.GAP - 1).intersects(box(other)),
                            "2 templates must have a gap of " + DebugBuildings.GAP + " blocks: " + cell + ", "
                                    + other);
                }
            }
            int beds = 0;
            int jobSigns = 0;
            for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                    box.maxZ())) {
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                    beds++;
                } else if (state.is(ModBlocks.JOB_SIGN.get())) {
                    jobSigns++;
                }
            }
            helper.assertTrue(beds == cell.signs().beds(), "The placed template " + cell.id() + " must have "
                    + cell.signs().beds() + " beds: " + beds);
            helper.assertTrue(jobSigns == cell.signs().jobs().size(), "The placed template " + cell.id()
                    + " must have " + cell.signs().jobs().size() + " job signs: " + jobSigns);
            String name = lines(level, cell.nameSign());
            helper.assertTrue(name.equals(cell.id().getPath().substring("village/".length())),
                    "The sign must give the template name " + cell.id() + ": " + name);
            List<Object> info = infoValues(level, cell.infoSign());
            helper.assertTrue(info.size() >= 2 && info.get(0).equals(cell.signs().beds())
                    && info.get(1).equals(cell.signs().jobs().size()),
                    "The sign must give the beds and the job posts of " + cell.id() + ": " + info);
            helper.assertTrue(level.getBlockState(cell.nameSign().below()).is(Blocks.GRASS_BLOCK),
                    "The ground under the signs must be flat grass: " + level.getBlockState(cell.nameSign()
                            .below()));
        }
        DebugBuildings.Cell baker = cells.stream().filter(cell -> name(cell.id()).equals("job_baker")).findFirst()
                .orElseThrow();
        DebugBuildings.Cell step3 = cells.stream().filter(cell -> name(cell.id()).equals("job_baker_step3"))
                .findFirst().orElseThrow();
        helper.assertTrue(step3.origin().getZ() == baker.origin().getZ() && step3.origin().getX() > baker.origin()
                .getX(), "The steps of a building must be in one row, from the west to the east");
        helper.assertTrue(step3.signs().beds() > baker.signs().beds(), "The step 3 must have more beds");
    }

    private static BoundingBox box(DebugBuildings.Cell cell) {
        return BoundingBox.fromCorners(cell.origin(), cell.origin().offset(cell.size()).offset(-1, -1, -1));
    }

    /** The text of the front of a sign, with the lines joined. */
    private static String lines(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return "no sign";
        }
        return Arrays.stream(sign.getFrontText().getMessages(false)).map(Component::getString)
                .collect(Collectors.joining());
    }

    /** The first value of each translated line of the front of a sign. */
    private static List<Object> infoValues(ServerLevel level, BlockPos pos) {
        List<Object> values = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            for (Component line : sign.getFrontText().getMessages(false)) {
                if (line.getContents() instanceof TranslatableContents text && text.getArgs().length > 0) {
                    values.add(text.getArgs()[0]);
                }
            }
        }
        return values;
    }

    private static String name(ResourceLocation id) {
        String path = id.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
