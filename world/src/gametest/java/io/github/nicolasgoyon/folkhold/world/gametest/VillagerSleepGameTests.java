package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.AlertState;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHomes;
import io.github.nicolasgoyon.folkhold.world.village.VillageLayout;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.schedule.WorkerBrains;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the sleep of the villagers of a generated village. Each test generates a village, places it in the
 * level and activates its heart. The heart gives the beds of the homes to the villagers. At night, each villager with
 * a bed walks to its bed and sleeps in it. A second test runs extra ticks of the level in each server tick, as a fast
 * night of Slumber does.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillagerSleepGameTests {
    /** The first candidate chunk: far from the spawn and from the areas of the other tests. */
    private static final int FIRST_CHUNK = 23000;
    /** The distance between 2 candidate chunks, in chunks. */
    private static final int CANDIDATE_STEP = 16;
    private static final int CANDIDATES = 8;
    /** The time for the forced chunks of the village to tick, in ticks. */
    private static final int CHUNK_LOAD = 5;
    /** The heart checks its villagers at this interval when a player is near it, in ticks. */
    private static final int HEART_CHECK_TICKS = 40;
    /** The villagers get their beds and walk in the village for this time before the night, in ticks. */
    private static final int DAY_TICKS = 400;
    private static final long NOON = 6000L;
    /** A time after the start of the sleep of all the villagers, with the delays of the workers. */
    private static final long NIGHT = 14000L;
    /** The extra ticks of the level in each server tick of the fast night. */
    private static final int FAST_TICKS = 9;
    /** The monster is at this distance from the heart: in the chase area of the units, in blocks. */
    private static final int MONSTER_RADIUS = 56;
    /** The number of positions on the circle of the monster. */
    private static final int MONSTER_DIRECTIONS = 16;
    /** The units fight the monster for this time, in ticks. */
    private static final int MONSTER_TICKS = 400;

    private VillagerSleepGameTests() {
    }

    /** At night, each villager with a bed sleeps in its bed. */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "villager_sleep")
    public static void villagersSleepInTheirBedsAtNight(GameTestHelper helper) {
        checkSleep(helper, 0, 0, false);
    }

    /** During a fast night (extra ticks of the level in each server tick), each villager with a bed sleeps in it. */
    // The time of a GameTest is the game time: the extra ticks count.
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "villager_sleep_fast")
    public static void villagersSleepInTheirBedsInAFastNight(GameTestHelper helper) {
        checkSleep(helper, 1, FAST_TICKS, false);
    }

    /**
     * At night, the units of the village fight a monster far from the villagers. The village stays calm, and each
     * villager with a bed stays in it. A monster near a villager starts the alert.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "villager_sleep_monster")
    public static void villagersSleepWhenTheUnitsFightAMonsterFarFromThem(GameTestHelper helper) {
        checkSleep(helper, 2, 0, true);
    }

    /**
     * A villager at the foot of its bed, in a corner with no free place near the head of the bed, lies down at the
     * check of the heart (see {@link VillageHomes#lieDown}). A villager farther from its bed does not.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "villager_sleep_lie_down")
    public static void villagerAtTheFootOfItsBedLiesDown(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ServerLevel level = helper.getLevel();
        // A bed along the X axis with its head against a wall, and walls on its 2 sides.
        BlockPos head = new BlockPos(5, 1, 5);
        helper.setBlock(head.east(), Blocks.STONE);
        for (BlockPos side : List.of(head.north(), head.south(), head.west().north(), head.west().south())) {
            helper.setBlock(side, Blocks.STONE);
        }
        BlockPos foot = head.west();
        helper.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD));
        Villager near = helper.spawn(EntityType.VILLAGER, foot.west());
        Villager far = helper.spawn(EntityType.VILLAGER, foot.west(4));
        TestSupport.atTestEnd(helper, () -> {
            near.discard();
            far.discard();
        });
        for (Villager villager : List.of(near, far)) {
            villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(),
                    helper.absolutePos(head)));
            villager.getBrain().setActiveActivityIfPossible(Activity.REST);
        }
        helper.assertTrue(near.getBrain().isActive(Activity.REST), "The villager must rest");
        helper.assertTrue(VillageHomes.lieDown(level, List.of(near, far)) == 1, "One villager must lie down");
        helper.assertTrue(near.isSleeping() && near.getSleepingPos().filter(helper.absolutePos(head)::equals)
                .isPresent(), "The villager at the foot of its bed must sleep in it: " + near.getSleepingPos());
        helper.assertFalse(far.isSleeping(), "A villager 5 blocks from its bed must not lie down");
        helper.succeed();
    }

    /**
     * Generates and activates a village, then sets the night and waits for the sleep of the villagers with a bed.
     *
     * @param row       the row of the candidate chunks
     * @param fastTicks the extra ticks of the level in each server tick of the night
     * @param monster   true for a monster near the village after the start of the sleep
     */
    private static void checkSleep(GameTestHelper helper, int row, int fastTicks, boolean monster) {
        ServerLevel level = helper.getLevel();
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH, false);
        long[] dayTime = {NOON};
        boolean[] night = {false};
        helper.onEachTick(() -> {
            TestSupport.setDayTimeForTest(helper, dayTime[0]);
            if (night[0]) {
                for (int i = 0; i < fastTicks; i++) {
                    level.tick(() -> true);
                    level.setDayTime(dayTime[0]);
                }
            }
        });
        StructureStart start = generate(level, row);
        BoundingBox box = start.getBoundingBox();
        BlockPos heartPos = heart(level, start);
        VillageHeartBlockEntity heart = (VillageHeartBlockEntity) level.getBlockEntity(heartPos);
        helper.assertTrue(heart != null, "The heart must be on the plaza at " + heartPos);
        heart.setRepairReference(VillageReference.of(level, start.getPieces()).orElseThrow());
        setForced(level, box, true);
        List<Entity> monsters = new ArrayList<>();
        TestSupport.atTestEnd(helper, () -> {
            monsters.forEach(Entity::discard);
            heart.getUnits().forEach(Entity::discard);
            heart.getCats().forEach(Entity::discard);
            VillageFolk.inhabitants(level, heartPos).forEach(Entity::discard);
            setForced(level, box, false);
        });
        // The heart checks its villagers only when a player is near it: the test does the checks of the heart.
        // The extra ticks of a fast night make steps of more than 1 tick: the check uses the time since the last one.
        long[] lastCheck = {0L};
        helper.onEachTick(() -> {
            if (helper.getTick() - lastCheck[0] >= HEART_CHECK_TICKS
                    && Settlements.of(level.getServer(), heart).isPresent()) {
                lastCheck[0] = helper.getTick();
                heart.ensureGarrison(level.getGameTime());
            }
        });
        GameTestSequence sequence = helper.startSequence()
                .thenIdle(CHUNK_LOAD)
                .thenWaitUntil(() -> helper.assertTrue(VillageLayout.isAreaReady(level, heart.scanArea(level)),
                        "The scan area of the village must be ready"))
                .thenExecute(() -> {
                    heart.ensureSettlement();
                    heart.clearTrees(level);
                    heart.clearTrees(level);
                    heart.ensureGarrison(level.getGameTime());
                })
                .thenIdle(DAY_TICKS)
                // The heart adds the villagers in groups. The night starts when the village is full and each place
                // of a home has its villager, so that no villager appears at the heart during the night.
                .thenWaitUntil(() -> {
                    Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
                    int expected = VillageFolk.inhabitantCount(settlement);
                    int count = VillageFolk.inhabitants(level, heartPos).size();
                    helper.assertTrue(count >= expected, "The village must be full: " + count + " of " + expected);
                })
                .thenExecute(() -> {
                    List<Villager> villagers = VillageFolk.inhabitants(level, heartPos);
                    helper.assertTrue(villagers.stream().anyMatch(villager -> bed(level, villager).isPresent()),
                            "The villagers must get beds: " + villagers.size() + " villagers");
                    FolkholdWorld.LOGGER.info("Villager sleep: {} villagers at the start of the night", villagers.size());
                    dayTime[0] = NIGHT;
                    night[0] = true;
                })
                .thenWaitUntil(() -> assertAllSleep(helper, level, heart, "at night"));
        if (!monster) {
            sequence.thenSucceed();
            return;
        }
        Zombie[] zombie = {null};
        // The heart rings the bells each 5 seconds in an alert: the wait covers some rings.
        sequence.thenExecute(() -> {
                    zombie[0] = monsterAwayFromTheVillagers(level, heart);
                    monsters.add(zombie[0]);
                })
                .thenExecuteFor(MONSTER_TICKS, () -> {
                    fight(heart, zombie[0]);
                    helper.assertTrue(heart.getAlertState() == AlertState.CALM, "A monster far from the villagers "
                            + "must not start the alert: monster at " + zombie[0].blockPosition().toShortString());
                })
                .thenExecute(() -> {
                    helper.assertTrue(heart.getUnits().stream().anyMatch(unit -> unit.getTarget() == zombie[0]),
                            "A unit must fight the monster");
                    assertAllSleep(helper, level, heart, "with a monster far from the villagers");
                    // A monster near a villager is a threat for the villagers: it starts the alert.
                    Villager villager = VillageFolk.inhabitants(level, heartPos).get(0);
                    zombie[0].moveTo(villager.getX() + 2.0, villager.getY(), villager.getZ());
                })
                .thenWaitUntil(() -> {
                    fight(heart, zombie[0]);
                    helper.assertTrue(heart.getAlertState() != AlertState.CALM,
                            "A monster near a villager must start the alert");
                })
                .thenSucceed();
    }

    /** Each villager with a bed sleeps in it. */
    private static void assertAllSleep(GameTestHelper helper, ServerLevel level, VillageHeartBlockEntity heart,
            String when) {
        List<Villager> villagers = VillageFolk.inhabitants(level, heart.getBlockPos());
        List<String> awake = new ArrayList<>();
        for (Villager villager : villagers) {
            if (bed(level, villager).isPresent() && !villager.isSleeping()) {
                awake.add(describe(level, villager));
            }
        }
        helper.assertTrue(awake.isEmpty(), "Each villager with a bed must sleep in it " + when + ": " + awake.size()
                + " of " + villagers.size() + " awake, alert " + heart.getAlertState() + ": " + awake.stream().limit(4).toList());
    }

    /**
     * The nearest unit of the village fights a monster: the test gives the monster to the unit as its target, as an
     * archer that sees a monster in the night.
     */
    private static void fight(VillageHeartBlockEntity heart, Zombie zombie) {
        heart.getUnits().stream().min(Comparator.comparingDouble(unit -> unit.distanceToSqr(zombie)))
                .filter(unit -> unit.getTarget() == null).ifPresent(unit -> unit.setTarget(zombie));
    }

    /**
     * Puts a monster in the chase area of the units, at the position of a circle around the heart that is farthest
     * from the villagers. The monster does not move and does not die: the units fight it during the test.
     */
    private static Zombie monsterAwayFromTheVillagers(ServerLevel level, VillageHeartBlockEntity heart) {
        List<Villager> villagers = VillageFolk.inhabitants(level, heart.getBlockPos());
        BlockPos best = null;
        double bestDistance = -1.0;
        for (int i = 0; i < MONSTER_DIRECTIONS; i++) {
            double angle = 2.0 * Math.PI * i / MONSTER_DIRECTIONS;
            int x = heart.getBlockPos().getX() + (int) Math.round(Math.cos(angle) * MONSTER_RADIUS);
            int z = heart.getBlockPos().getZ() + (int) Math.round(Math.sin(angle) * MONSTER_RADIUS);
            BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            double distance = villagers.stream().mapToDouble(villager -> Math.sqrt(villager.distanceToSqr(
                    pos.getCenter()))).min().orElse(Double.MAX_VALUE);
            if (distance > bestDistance) {
                best = pos;
                bestDistance = distance;
            }
        }
        FolkholdWorld.LOGGER.info("Villager sleep: monster at {}, {} blocks from the nearest villager", best,
                Math.round(bestDistance));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        zombie.moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
        zombie.setNoAi(true);
        // An invulnerable mob is not a target: the effect keeps the monster alive.
        zombie.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 4));
        zombie.setPersistenceRequired();
        level.addFreshEntity(zombie);
        return zombie;
    }

    /** The bed of a villager in its memory {@code HOME}, in the level of the test. */
    private static Optional<BlockPos> bed(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(home -> home.dimension() == level.dimension()).map(GlobalPos::pos);
    }

    private static String describe(ServerLevel level, Villager villager) {
        Brain<Villager> brain = villager.getBrain();
        Optional<BlockPos> bed = bed(level, villager);
        String bedText = bed.map(pos -> {
            BlockState state = level.getBlockState(pos);
            boolean occupied = state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
            return pos.toShortString() + " d=" + Math.round(Math.sqrt(pos.distToCenterSqr(villager.position())))
                    + " " + state.getBlock().getDescriptionId() + (occupied ? " occupied" : "");
        }).orElse("none");
        return villager.getVillagerData().getProfession() + (WorkerBrains.isWorker(villager) ? " worker" : "")
                + " at " + villager.blockPosition().toShortString() + " bed " + bedText + " sleeping "
                + villager.isSleeping() + " activity " + brain.getActiveNonCoreActivity().orElse(null) + " walk "
                + brain.getMemory(MemoryModuleType.WALK_TARGET).map(target -> target.getTarget()
                        .currentBlockPosition().toShortString()).orElse("none")
                + " cantReach " + brain.getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(-1L)
                + " path " + (villager.getNavigation().getPath() == null ? "none"
                        : villager.getNavigation().getPath().canReach() + "/" + villager.getNavigation().getPath()
                                .getNodeCount());
    }

    /** Generates a village and places it in the level, in the first candidate chunk with no other structure. */
    private static StructureStart generate(ServerLevel level, int row) {
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (int i = 0; i < CANDIDATES; i++) {
            ChunkPos chunk = new ChunkPos(FIRST_CHUNK + i * CANDIDATE_STEP, FIRST_CHUNK + row * CANDIDATE_STEP);
            StructureStart start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(),
                    level.getChunkSource().randomState(), level.getServer().getStructureManager(), level.getSeed(),
                    chunk, 0, level, biome -> true);
            if (!start.isValid()) {
                continue;
            }
            BoundingBox box = start.getBoundingBox();
            if (hasOtherStructures(level, box)) {
                continue;
            }
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
                for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                    start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
                            new BoundingBox(x << 4, level.getMinBuildHeight(), z << 4, (x << 4) + 15,
                                    level.getMaxBuildHeight(), (z << 4) + 15),
                            new ChunkPos(x, z));
                }
            }
            return start;
        }
        throw new IllegalStateException("No candidate chunk has a village with no other structure");
    }

    /** The village heart on the plaza of a generated village. */
    private static BlockPos heart(ServerLevel level, StructureStart start) {
        BoundingBox plaza = start.getPieces().get(0).getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(plaza.minX(), plaza.minY(), plaza.minZ(), plaza.maxX(),
                plaza.maxY(), plaza.maxZ())) {
            if (level.getBlockEntity(pos) instanceof VillageHeartBlockEntity) {
                return pos.immutable();
            }
        }
        throw new IllegalStateException("No village heart on the plaza " + plaza);
    }

    /** Forces the chunks of a box to stay loaded and to tick, or stops this. */
    private static void setForced(ServerLevel level, BoundingBox box, boolean forced) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                level.setChunkForced(x, z, forced);
            }
        }
    }

    private static boolean hasOtherStructures(ServerLevel level, BoundingBox box) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                if (!level.getChunk(x, z).getAllReferences().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
