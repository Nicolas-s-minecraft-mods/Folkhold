package io.github.nicolasgoyon.folkhold.world.construction;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.entity.AnimatedVillagers;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.BlockedVillagers;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The rescues of the blocked villagers and units of a village by its builders (see {@link BlockedVillagers}).
 *
 * <ol>
 *   <li>At each check of the villagers, a village with no rescue takes its first blocked villager or unit with a plan
 *       (see {@link RescuePlan}): a column of ladders or of scaffolding up to an exit. A blocked villager with no plan
 *       waits {@value #NO_PLAN_TICKS} ticks for the next plan.</li>
 *   <li>The nearest available builder of the village takes the rescue: a builder that is awake and calm. A builder on a
 *       construction site leaves the site: the rescue comes before the repairs and the sites.</li>
 *   <li>The builder walks to the exit, or to the nearest position of its walk. After {@value #MOVE_TIMEOUT} ticks,
 *       it works from where it is when it is {@value #WORK_REACH} blocks from the exit or nearer. Else it goes to the
 *       exit directly.</li>
 *   <li>The builder places the blocks of the column from the bottom, one block each {@value #PLACE_TICKS} ticks. A
 *       ladder comes from the stock of the village, as a block of a construction site (see
 *       {@link BuilderMaterials}). With no ladder in the stock, the rescue waits, and the heart screen says it. The
 *       scaffolding uses no materials, as the scaffolding of a site.</li>
 *   <li>The blocked villager walks to the bottom of the column and climbs to the exit.</li>
 *   <li>The villager walks to the heart. When it is {@value #LEAVE_DISTANCE} blocks from the exit, or after
 *       {@value #LEAVE_TICKS} ticks, the builder removes the blocks of the column from the top. A removed ladder goes
 *       back to the stock.</li>
 *   <li>When the villager still has no way to the heart (a ledge in a deep hole), the next check starts a new rescue
 *       for it at once.</li>
 *   <li>A rescue removes its blocks {@value #TIME_LIMIT} ticks after the end of the column, also with no builder. The
 *       heart saves the blocks of the rescue: after a load, the first check of the heart removes them. A block that a
 *       player changed stays.</li>
 * </ol>
 *
 * <p>The rescue is not saved: after a load, the village detects its blocked villagers again.
 */
public final class VillageRescues {
    /** A rescue removes its blocks after this time from the end of its column, in ticks. */
    public static final int TIME_LIMIT = 2400;
    /** A builder that does not get to the exit in this time goes there directly, in ticks. */
    public static final int MOVE_TIMEOUT = 400;
    /** The builder places or removes one block each this number of ticks. */
    public static final int PLACE_TICKS = 10;
    /** A blocked villager walks to the bottom of the column for this time at most, in ticks. Then it goes there. */
    public static final int WALK_TICKS = 100;
    /** The speed of a villager that climbs the column, in blocks for each tick. */
    public static final double CLIMB_SPEED = 0.15;
    /** The builder removes the column when the villager is this far from the exit, in blocks. */
    public static final int LEAVE_DISTANCE = 4;
    /** The builder removes the column after this time from the end of the climb, in ticks. */
    public static final int LEAVE_TICKS = 400;
    /** A blocked villager with no plan waits this time for a new plan, in ticks. */
    public static final int NO_PLAN_TICKS = 1200;
    /** The builder is at the exit at this distance or nearer, in blocks. */
    static final double REACH = 4.0;
    /**
     * A builder that does not get to the exit works from where it is at this distance or nearer, in blocks: for
     * example from the edge of a deep hole.
     */
    static final double WORK_REACH = 8.0;
    /** The speed of a builder or of a villager that walks for a rescue, as a multiple of its speed. */
    static final float WALK_SPEED = 0.6F;
    /** A villager at the bottom of the column is this near the middle of the column, in blocks. */
    private static final double AT_BASE = 0.3;
    /** The builder gets a new walk target each this number of ticks. */
    private static final int WALK_REFRESH = 20;
    private static final String BLOCKS_TAG = "Blocks";
    private static final String POS_TAG = "Pos";
    private static final String PREVIOUS_TAG = "Previous";
    private static final String PLACED_TAG = "Placed";
    /** The builders with a rescue, in all the villages: a construction site does not take them. */
    private static final Set<UUID> RESCUERS = ConcurrentHashMap.newKeySet();

    @Nullable
    private Rescue active;
    /** The blocks of a rescue before a load: the first update removes them. */
    private final List<Placed> leftovers = new ArrayList<>();
    /** The game time of the next plan for a blocked villager with no plan, by UUID. */
    private final Map<UUID, Long> noPlanUntil = new HashMap<>();
    /** A villager that needs a new rescue at once: it has no way after the climb. */
    @Nullable
    private UUID again;
    private boolean waitsForLadders;

    /**
     * A block of a rescue.
     *
     * @param pos      the position
     * @param previous the block before the rescue
     * @param placed   the block of the rescue
     */
    record Placed(BlockPos pos, BlockState previous, BlockState placed) {
    }

    /** The state of a rescue. */
    enum State {
        /** The rescue waits for an available builder. */
        ASSIGN,
        /** The builder walks to the exit. */
        MOVE,
        /** The builder places the blocks of the column. */
        PLACE,
        /** The villager walks to the bottom of the column and climbs to the exit. */
        CLIMB,
        /** The villager walks away from the exit. */
        LEAVE,
        /** The builder removes the blocks of the column. */
        CLEAR,
        /** The rescue is finished. */
        DONE
    }

    /**
     * True if a builder has a rescue: a construction site does not take it.
     *
     * @param id the UUID of the builder
     * @return true for a builder with a rescue
     */
    public static boolean isRescuer(UUID id) {
        return RESCUERS.contains(id);
    }

    /** True while the village has a rescue. */
    public boolean isActive() {
        return active != null;
    }

    /** True while the rescue waits for ladders in the stock of the village. The heart screen shows it. */
    public boolean waitsForLadders() {
        return active != null && waitsForLadders;
    }

    /** The state of the rescue, or empty with no rescue. For the tests. */
    public Optional<String> state() {
        return Optional.ofNullable(active).map(rescue -> rescue.state.name());
    }

    /** The plan of the rescue, or empty with no rescue. For the tests. */
    public Optional<RescuePlan> plan() {
        return Optional.ofNullable(active).map(rescue -> rescue.plan);
    }

    /**
     * Starts a rescue: the first blocked villager or unit with a plan. The village heart calls it at each check of
     * the villagers. The method also removes the blocks of a rescue before a load.
     *
     * @param level   the level
     * @param heart   the position of the village heart
     * @param blocked the blocked villagers and units, in the order of their detection
     * @param now     the game time
     */
    public void update(ServerLevel level, BlockPos heart, List<UUID> blocked, long now) {
        if (!leftovers.isEmpty()) {
            leftovers.forEach(placed -> restore(level, placed));
            leftovers.clear();
        }
        if (active != null || VillageFolk.of(level, heart, ModProfessions.BUILDER.get()).isEmpty()) {
            return;
        }
        noPlanUntil.values().removeIf(until -> until <= now);
        List<UUID> candidates = new ArrayList<>();
        if (again != null) {
            candidates.add(again);
            again = null;
        }
        candidates.addAll(blocked);
        for (UUID id : candidates) {
            if (noPlanUntil.containsKey(id) || !(level.getEntity(id) instanceof PathfinderMob mob) || !mob.isAlive()) {
                continue;
            }
            Optional<RescuePlan> plan = RescuePlan.of(level, mob.blockPosition());
            if (plan.isEmpty()) {
                noPlanUntil.put(id, now + NO_PLAN_TICKS);
                continue;
            }
            active = new Rescue(id, plan.get());
            waitsForLadders = false;
            return;
        }
    }

    /**
     * One tick of the rescue of a village. The village heart calls it on each tick.
     *
     * @param level the level
     * @param heart the village heart
     */
    public void tick(ServerLevel level, VillageHeartBlockEntity heart) {
        Rescue rescue = active;
        if (rescue == null) {
            return;
        }
        rescue.tick(level, heart);
        if (rescue.state == State.DONE) {
            rescue.release(level);
            if (rescue.again) {
                again = rescue.target;
            }
            active = null;
            waitsForLadders = false;
        }
    }

    /**
     * Stops the rescue: the heart goes away or its chunk unloads. The builder is free. The blocks of the rescue stay
     * in the saved data of the heart: the next load removes them.
     *
     * @param level the level
     */
    public void stop(ServerLevel level) {
        Rescue rescue = active;
        if (rescue != null) {
            rescue.release(level);
            leftovers.addAll(rescue.placed);
            active = null;
        }
    }

    /** The blocks of the rescue and the blocks before a load, as NBT data. */
    public CompoundTag save() {
        ListTag list = new ListTag();
        List<Placed> all = new ArrayList<>(leftovers);
        if (active != null) {
            all.addAll(active.placed);
        }
        for (Placed placed : all) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(POS_TAG, placed.pos().asLong());
            entry.put(PREVIOUS_TAG, NbtUtils.writeBlockState(placed.previous()));
            entry.put(PLACED_TAG, NbtUtils.writeBlockState(placed.placed()));
            list.add(entry);
        }
        CompoundTag tag = new CompoundTag();
        tag.put(BLOCKS_TAG, list);
        return tag;
    }

    /**
     * Loads the blocks of a rescue before a load: the first update removes them.
     *
     * @param blocks the blocks of the registries
     * @param tag    the NBT data of {@link #save}
     */
    public void load(HolderGetter<Block> blocks, CompoundTag tag) {
        leftovers.clear();
        ListTag list = tag.getList(BLOCKS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            leftovers.add(new Placed(BlockPos.of(entry.getLong(POS_TAG)),
                    NbtUtils.readBlockState(blocks, entry.getCompound(PREVIOUS_TAG)),
                    NbtUtils.readBlockState(blocks, entry.getCompound(PLACED_TAG))));
        }
    }

    /** The number of blocks to remove after a load. For the tests. */
    public int leftovers() {
        return leftovers.size();
    }

    /** Puts back the block before a rescue, when the block of the rescue is still there. */
    private static void restore(ServerLevel level, Placed placed) {
        if (level.isLoaded(placed.pos()) && level.getBlockState(placed.pos()).is(placed.placed().getBlock())) {
            level.setBlock(placed.pos(), placed.previous(), Block.UPDATE_ALL);
        }
    }

    /** One rescue. */
    private final class Rescue {
        private final UUID target;
        private final RescuePlan plan;
        private final List<Placed> placed = new ArrayList<>();
        private final SiteMaterials materials = new SiteMaterials();
        private State state = State.ASSIGN;
        private int stateTicks;
        @Nullable
        private UUID builder;
        /** The game time of the end of the column, or -1 before. */
        private long builtAt = -1;
        /** True when the villager has no way after the climb: it needs a new rescue. */
        private boolean again;
        private boolean climbing;

        Rescue(UUID target, RescuePlan plan) {
            this.target = target;
            this.plan = plan;
        }

        void tick(ServerLevel level, VillageHeartBlockEntity heart) {
            long now = level.getGameTime();
            stateTicks++;
            PathfinderMob mob = level.getEntity(target) instanceof PathfinderMob found && found.isAlive() ? found
                    : null;
            if (state != State.CLEAR && state != State.DONE && (mob == null || (builtAt >= 0 && now - builtAt > TIME_LIMIT))) {
                setState(State.CLEAR);
            }
            Villager worker = builder(level, heart.getBlockPos());
            switch (state) {
                case ASSIGN -> {
                    if (worker != null) {
                        setState(State.MOVE);
                    }
                }
                case MOVE -> move(worker);
                case PLACE -> place(level, heart, worker);
                case CLIMB -> climb(mob);
                case LEAVE -> leave(heart.getBlockPos(), mob);
                case CLEAR -> clear(level, heart, worker);
                case DONE -> {
                }
            }
        }

        /** The builder of the rescue. A builder that cannot work any more leaves it, and another builder comes. */
        @Nullable
        private Villager builder(ServerLevel level, BlockPos heart) {
            if (builder != null) {
                if (level.getEntity(builder) instanceof Villager villager && BuilderWorker.canWork(villager)) {
                    return villager;
                }
                RESCUERS.remove(builder);
                builder = null;
            }
            if (state == State.DONE) {
                return null;
            }
            Vec3 exit = Vec3.atBottomCenterOf(plan.exit());
            Optional<Villager> next = VillageFolk.of(level, heart, ModProfessions.BUILDER.get()).stream()
                    .filter(BuilderWorker::canWork)
                    .filter(villager -> !RESCUERS.contains(villager.getUUID()))
                    .min(Comparator.comparing((Villager villager) -> villager.navigation instanceof BuilderNavigation)
                            .thenComparingDouble(villager -> villager.distanceToSqr(exit)));
            next.ifPresent(villager -> {
                builder = villager.getUUID();
                RESCUERS.add(builder);
            });
            return next.orElse(null);
        }

        private void move(@Nullable Villager worker) {
            if (worker == null) {
                return;
            }
            Vec3 exit = Vec3.atBottomCenterOf(plan.exit());
            if (worker.distanceToSqr(exit) <= REACH * REACH) {
                setState(State.PLACE);
            } else if (stateTicks >= MOVE_TIMEOUT) {
                // The builder works from the nearest position of its walk, or goes to the exit directly.
                if (worker.distanceToSqr(exit) > WORK_REACH * WORK_REACH) {
                    worker.getNavigation().stop();
                    worker.moveTo(exit);
                }
                setState(State.PLACE);
            } else if (stateTicks % WALK_REFRESH == 1 && !(worker.navigation instanceof BuilderNavigation)) {
                worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(plan.exit(), WALK_SPEED,
                        1));
            }
        }

        private void place(ServerLevel level, VillageHeartBlockEntity heart, @Nullable Villager worker) {
            if (worker == null) {
                return;
            }
            int index = placed.size();
            if (index >= plan.blocks().size()) {
                builtAt = level.getGameTime();
                setState(State.CLIMB);
                return;
            }
            RescuePlan.Placement next = plan.blocks().get(index);
            Brain<Villager> brain = worker.getBrain();
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(next.pos()));
            if (stateTicks % PLACE_TICKS != 0) {
                return;
            }
            if (!RescuePlan.isFree(level, next.pos())) {
                // The level changed: the rescue stops, and the next check makes a new plan.
                setState(State.CLEAR);
                return;
            }
            if (next.isLadder()) {
                Optional<Settlement> settlement = Settlements.of(level.getServer(), heart);
                List<BuilderMaterials.Material> ladder = List.of(BuilderMaterials.of(level.getServer(),
                        Items.LADDER));
                if (!materials.take(ladder, settlement.orElse(null), level.getServer(), level.getGameTime())) {
                    waitsForLadders = true;
                    return;
                }
                waitsForLadders = false;
            }
            placed.add(new Placed(next.pos(), level.getBlockState(next.pos()), next.state()));
            level.setBlock(next.pos(), next.state(), Block.UPDATE_ALL);
            AnimatedVillagers.place(worker);
        }

        private void climb(@Nullable PathfinderMob mob) {
            if (mob == null) {
                return;
            }
            Vec3 base = Vec3.atBottomCenterOf(plan.base());
            double dx = mob.getX() - base.x;
            double dz = mob.getZ() - base.z;
            if (!climbing && stateTicks < WALK_TICKS && dx * dx + dz * dz > AT_BASE * AT_BASE) {
                if (stateTicks % WALK_REFRESH == 1) {
                    if (mob instanceof Villager villager) {
                        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(plan.base(),
                                WALK_SPEED, 0));
                    } else {
                        mob.getNavigation().moveTo(base.x, base.y, base.z, 1.0);
                    }
                }
                return;
            }
            climbing = true;
            mob.getNavigation().stop();
            if (mob instanceof Villager villager) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
            double y = Math.max(mob.getY(), base.y) + CLIMB_SPEED;
            if (y >= plan.exit().getY()) {
                mob.moveTo(Vec3.atBottomCenterOf(plan.exit()));
                mob.setDeltaMovement(Vec3.ZERO);
                mob.resetFallDistance();
                setState(State.LEAVE);
                return;
            }
            mob.setPos(base.x, y, base.z);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.resetFallDistance();
        }

        private void leave(BlockPos heart, @Nullable PathfinderMob mob) {
            if (mob == null) {
                setState(State.CLEAR);
                return;
            }
            if (stateTicks == 1 && mob instanceof Villager villager) {
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(heart, WALK_SPEED,
                        BlockedVillagers.HEART_DISTANCE));
            }
            if (mob.distanceToSqr(Vec3.atBottomCenterOf(plan.exit())) > LEAVE_DISTANCE * LEAVE_DISTANCE
                    || stateTicks >= LEAVE_TICKS) {
                again = !FolkNavigation.hasWay(mob, heart, BlockedVillagers.HEART_DISTANCE);
                setState(State.CLEAR);
            }
        }

        private void clear(ServerLevel level, VillageHeartBlockEntity heart, @Nullable Villager worker) {
            if (placed.isEmpty()) {
                setState(State.DONE);
                return;
            }
            boolean near = worker != null
                    && worker.distanceToSqr(Vec3.atBottomCenterOf(plan.exit())) <= WORK_REACH * WORK_REACH;
            if (near && stateTicks % PLACE_TICKS != 0) {
                return;
            }
            Optional<Settlement> settlement = Settlements.of(level.getServer(), heart);
            do {
                Placed last = placed.remove(placed.size() - 1);
                restore(level, last);
                if (last.placed().is(Blocks.LADDER)) {
                    materials.keep(BuilderMaterials.of(level.getServer(), Items.LADDER).cost(),
                            settlement.orElse(null), level.getServer());
                }
                if (near) {
                    worker.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(last.pos()));
                    AnimatedVillagers.place(worker);
                }
            } while (!near && !placed.isEmpty());
            if (placed.isEmpty()) {
                settlement.ifPresent(found -> materials.giveBack(found, level.getServer()));
                setState(State.DONE);
            }
        }

        /** The builder is free: no rescue, no walk target and no look target. */
        void release(ServerLevel level) {
            if (builder == null) {
                return;
            }
            RESCUERS.remove(builder);
            if (level.getEntity(builder) instanceof Villager villager) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            }
            builder = null;
        }

        private void setState(State next) {
            state = next;
            stateTicks = 0;
        }
    }

    /** Forgets the rescuers of the server. */
    public static void onServerStopped(ServerStoppedEvent event) {
        RESCUERS.clear();
    }
}
