package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.village.VillageLayout;
import io.github.nicolasgoyon.folkhold.world.village.VillagePens;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The animals in the pens of the job buildings (see {@link VillagePens}). */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillagePenGameTests {
    private static final int HUGE = 56;
    /** The origin of the building of a test, in the arena. */
    private static final BlockPos ORIGIN = new BlockPos(20, 1, 20);
    /** The job sign of a job building stands next to the west porch post. */
    private static final BlockPos SIGN = ORIGIN.offset(1, 1, 0);
    /** The folders of the pieces of the tests: each style, with a variant that changes the edge of the yard. */
    private static final List<String> FOLDERS = List.of("", "desert/", "swamp/", "valeholm/", "valeholm/desert/",
            "sunreach/", "sunreach/swamp/");
    private static final List<String> JOBS = List.of("rancher", "shepherd");

    private VillagePenGameTests() {
    }

    /**
     * The pen of a complete rancher building gets its cows one time. A second check and a second placement of the
     * building add no cow. When a cow dies, a cow comes back after the delay, and not before.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 40, batch = "village_pen_animals", skyAccess = true)
    public static void penGetsItsAnimalsOneTime(GameTestHelper helper) {
        TestSupport.buildFloor(helper, HUGE);
        TestSupport.setConfigForTest(helper, WorldConfig.PEN_REPLACE_SECONDS, 10);
        ServerLevel level = helper.getLevel();
        int count = WorldConfig.PEN_ANIMALS.get();
        BoundingBox box = place(helper, "", "rancher");
        AABB area = AABB.of(box);
        TestSupport.atTestEnd(helper, () -> clear(level, box));
        VillagePens pens = new VillagePens();
        List<VillageLayout.JobSign> signs = List.of(new VillageLayout.JobSign(helper.absolutePos(SIGN), "rancher"));

        helper.assertTrue(pens.check(level, signs, 0L), "The first check must find the pen");
        helper.assertTrue(cows(level, area).size() == count && pens.animals(helper.absolutePos(SIGN)) == count,
                "The pen must get " + count + " cows: " + cows(level, area).size());
        pens.check(level, signs, 200L);
        place(helper, "", "rancher");
        pens.check(level, signs, 400L);
        helper.assertTrue(cows(level, area).size() == count, "A second check and a second construction must add no"
                + " cow: " + cows(level, area).size());

        cows(level, area).get(0).discard();
        pens.check(level, signs, 600L);
        pens.check(level, signs, 600L + 10 * 20 - 1);
        helper.assertTrue(cows(level, area).size() == count - 1, "No cow must come back before the delay: "
                + cows(level, area).size());
        pens.check(level, signs, 600L + 10 * 20);
        helper.assertTrue(cows(level, area).size() == count, "A cow must come back after the delay: "
                + cows(level, area).size());
        helper.assertTrue(cows(level, area).stream().allMatch(Animal::hasRestriction),
                "The cows of the village must have the pen as their walk area");

        // The state of the pens goes through the saved data: a loaded state adds no cow.
        VillagePens loaded = new VillagePens();
        loaded.load(pens.save());
        loaded.check(level, signs, 2000L);
        helper.assertTrue(cows(level, area).size() == count && loaded.animals(helper.absolutePos(SIGN)) == count,
                "A loaded state must keep the cows and add no cow: " + cows(level, area).size());
        helper.succeed();
    }

    /**
     * The pen of the rancher and of the shepherd is a closed yard in each style: the search finds its positions from
     * a hay block. With no gate in the edge of the yard, the yard is open: it is not a pen, and it gets no animals.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 40, batch = "village_pen_styles", skyAccess = true)
    public static void eachStyleHasAClosedPen(GameTestHelper helper) {
        TestSupport.buildFloor(helper, HUGE);
        ServerLevel level = helper.getLevel();
        for (String folder : FOLDERS) {
            for (String job : JOBS) {
                BoundingBox box = place(helper, folder, job);
                try {
                    String name = folder + "job_" + job;
                    List<BlockPos> hay = BlockPos.betweenClosedStream(box).filter(pos -> level.getBlockState(pos)
                            .is(Blocks.HAY_BLOCK)).map(BlockPos::immutable)
                            .filter(pos -> !VillagePens.find(level, pos).isEmpty()).toList();
                    helper.assertTrue(!hay.isEmpty(), "The piece " + name + " must have a closed pen with a hay block");
                    List<BlockPos> gates = BlockPos.betweenClosedStream(box).filter(pos -> level.getBlockState(pos)
                            .getBlock() instanceof FenceGateBlock).map(BlockPos::immutable).toList();
                    helper.assertTrue(gates.size() == 1, "The piece " + name + " must have one pen gate: " + gates);
                    level.setBlock(gates.get(0), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    helper.assertTrue(VillagePens.find(level, hay.get(0)).isEmpty(), "The yard of " + name
                            + " with no gate must not be a pen");
                    VillagePens pens = new VillagePens();
                    pens.check(level, List.of(new VillageLayout.JobSign(helper.absolutePos(SIGN), job)), 0L);
                    helper.assertTrue(level.getEntitiesOfClass(Animal.class, AABB.of(box).inflate(8.0)).isEmpty(),
                            "The open yard of " + name + " must get no animals");
                } finally {
                    clear(level, box);
                }
            }
        }
        helper.succeed();
    }

    private static List<Cow> cows(ServerLevel level, AABB area) {
        return level.getEntities(EntityType.COW, area, Cow::isAlive);
    }

    /** Places the job building of a job from a folder of the pieces. Returns its box. */
    private static BoundingBox place(GameTestHelper helper, String folder, String job) {
        ServerLevel level = helper.getLevel();
        StructureTemplate template = level.getStructureManager().get(ResourceLocation.fromNamespaceAndPath(
                FolkholdWorld.MOD_ID, "village/" + folder + "job_" + job)).orElseThrow();
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .addProcessor(JigsawReplacementProcessor.INSTANCE).setKnownShape(true);
        BlockPos origin = helper.absolutePos(ORIGIN);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        return template.getBoundingBox(settings, origin);
    }

    /** Removes the blocks and the animals of a building. */
    private static void clear(ServerLevel level, BoundingBox box) {
        level.getEntitiesOfClass(Animal.class, AABB.of(box).inflate(8.0)).forEach(Animal::discard);
        BlockPos.betweenClosedStream(box).forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                Block.UPDATE_CLIENTS));
    }
}
