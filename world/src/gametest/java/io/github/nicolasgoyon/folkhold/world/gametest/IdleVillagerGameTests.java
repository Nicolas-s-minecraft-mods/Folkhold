package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.village.schedule.IdleVillagers;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the idle villagers of a faction village (see {@link IdleVillagers}): an inhabitant with no job has
 * no meeting at the bell, and an idle villager far from its home walks back to it. The arena is
 * {@code arena_huge}: the bed is at about 50 blocks from the villager.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdleVillagerGameTests {
    private static final int ARENA_SIZE = 56;
    private static final BlockPos START = new BlockPos(4, 1, 4);
    private static final BlockPos BED = new BlockPos(ARENA_SIZE - 6, 1, ARENA_SIZE - 6);
    /** The time of the vanilla meeting at the bell, in ticks of the day. */
    private static final int MEETING_TIME = 10000;
    /** A time in the sleep phase of the default daily schedule, in ticks of the day. */
    private static final int NIGHT_TIME = 13000;
    /** The test waits this number of ticks at most for the walk home. */
    private static final int WALK_TICKS = 1200;
    /** The interval of the checks of the villagers of a heart, in ticks. */
    private static final int CHECK_TICKS = 40;

    private IdleVillagerGameTests() {
    }

    /**
     * An inhabitant with no job gets the inhabitant schedule: idle at the time of the vanilla meeting, rest at night.
     * Far from its home, it gets a walk target to its home. With the checks of the
     * heart, it walks to its home.
     */
    @GameTest(template = "arena_huge", timeoutTicks = WALK_TICKS + 100, batch = "idle_villagers_home")
    public static void idleInhabitantWalksBackToItsHome(GameTestHelper helper) {
        TestSupport.sweep(helper);
        TestSupport.buildFloor(helper, ARENA_SIZE);
        TestSupport.placeBed(helper, BED, Direction.NORTH);
        Villager villager = villager(helper);
        long added = helper.getLevel().getGameTime();
        BlockPos home = helper.absolutePos(BED.north());
        helper.runAfterDelay(5, () -> {
            villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            int walks = IdleVillagers.update(helper.getLevel(), List.of(villager));
            Brain<Villager> brain = villager.getBrain();
            Schedule schedule = brain.getSchedule();
            helper.assertTrue(schedule == IdleVillagers.inhabitantSchedule(),
                    "An inhabitant with no job must get the inhabitant schedule");
            helper.assertTrue(schedule.getActivityAt(MEETING_TIME) == Activity.IDLE,
                    "The inhabitant must have no meeting at the bell: " + schedule.getActivityAt(MEETING_TIME));
            helper.assertTrue(schedule.getActivityAt(NIGHT_TIME) == Activity.REST, "The inhabitant must rest at night");
            helper.assertTrue(walks == 1 && brain.getMemory(MemoryModuleType.WALK_TARGET)
                    .filter(target -> target.getTarget().currentBlockPosition().equals(home)).isPresent(),
                    "The idle inhabitant far from its home must get a walk target to its home");
            helper.succeedWhen(() -> {
                // The heart checks its villagers each 40 ticks.
                if (helper.getLevel().getGameTime() % CHECK_TICKS == 0) {
                    IdleVillagers.update(helper.getLevel(), List.of(villager));
                }
                TestSupport.assertTicks(helper, villager, added);
                double dx = villager.getX() - (home.getX() + 0.5);
                double dz = villager.getZ() - (home.getZ() + 0.5);
                helper.assertTrue(dx * dx + dz * dz <= IdleVillagers.HOME_RADIUS * IdleVillagers.HOME_RADIUS,
                        "The inhabitant must walk to its home: " + villager.blockPosition());
                villager.discard();
            });
        });
    }

    /**
     * With the option {@code villagersNearHome} off, an inhabitant with the inhabitant schedule gets the vanilla
     * schedule again, and no villager gets a walk target to its home.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 100, batch = "idle_villagers_off")
    public static void inhabitantKeepsTheVanillaScheduleWithTheOptionOff(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.SCHEDULE_NEAR_HOME, false);
        TestSupport.sweep(helper);
        TestSupport.buildFloor(helper, ARENA_SIZE);
        TestSupport.placeBed(helper, BED, Direction.NORTH);
        Villager villager = villager(helper);
        BlockPos home = helper.absolutePos(BED.north());
        helper.runAfterDelay(5, () -> {
            villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            villager.getBrain().setSchedule(IdleVillagers.inhabitantSchedule());
            int walks = IdleVillagers.update(helper.getLevel(), List.of(villager));
            boolean vanilla = villager.getBrain().getSchedule() == Schedule.VILLAGER_DEFAULT;
            villager.discard();
            helper.assertTrue(vanilla, "With the option off, the inhabitant must keep the vanilla schedule");
            helper.assertTrue(walks == 0, "With the option off, no villager must walk home");
            helper.succeed();
        });
    }

    /** An adult villager of a faction village with no profession. */
    private static Villager villager(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, START);
        villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, helper.absolutePos(BlockPos.ZERO).asLong());
        return villager;
    }
}
