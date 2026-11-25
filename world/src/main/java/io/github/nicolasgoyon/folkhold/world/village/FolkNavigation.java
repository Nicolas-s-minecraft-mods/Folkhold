package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.entity.ai.UnitNavigation;
import io.github.nicolasgoyon.folkhold.world.entity.ai.UsePassageGoal;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The navigation of a villager: the path finder of a vanilla villager, with the zones of the wall of its village (see
 * {@link WallZones}).
 *
 * <ul>
 *   <li>Each villager gets this navigation when it joins a level (see {@link #onEntityJoin}). A villager that is not
 *       a villager of a faction village keeps the vanilla path finder.</li>
 *   <li>A villager of a faction village does not walk into a zone of the wall of its village: the stairs and the
 *       platforms of the towers, and the tops of the palisade columns. The navigation gives no path to a target in a
 *       zone, and the path finder gives no path through a zone. Thus the vanilla behaviors do not walk to a wander
 *       target or a work target on the wall, and the villagers do not stop in a group at the foot of a stair. The
 *       units of the garrison are not villagers: they walk on the towers.</li>
 *   <li>A villager in a zone walks out of it. In a zone, the path finder gives a step to a position of a zone only
 *       on the same level or down: the villager walks down the stair of a tower, and does not go up. A path does not
 *       start with a node of a zone above the feet of the villager (see {@link #skipStepsUp}). A step to the top
 *       of a palisade column starts only on the top of a column: a path from a tower does not end on a column. The
 *       check of the villagers by the heart gives a walk to the heart to a villager in a zone (see
 *       {@link #leaveWall}). A villager does not jump into a zone (see {@link #allowsJump}), and does not step up
 *       into a zone (see {@link #allowsStepUp}).</li>
 *   <li>A villager of a faction village keeps away from the drops (see {@link Pits}). The navigation gives no path to
 *       a target in a pit or at the edge of a pit, except for a villager in a pit. The path finder gives no fall of {@value #FALL} blocks or
 *       more into a pit, and a position at the edge of a drop of 2 blocks or more costs {@value #EDGE_COST} blocks of
 *       walk more. Thus the wander targets and the paths of the villagers do not go into a pit.</li>
 *   <li>The zones come from the heart of the village of the villager (see
 *       {@link VillageHeartBlockEntity#wallZones}). With the chunk of the heart not loaded, the villager has no
 *       zone.</li>
 *   <li>The path finder of a villager of a faction village walks {@value #PATH_RANGE} blocks at most, and visits
 *       the vanilla number of nodes for each block of this range. The vanilla limit is the follow range of the
 *       villager (48 blocks). A bed behind the back wall of a deep house can be 3 blocks away in a straight line and
 *       more than 48 blocks away on foot, through the door. With the vanilla limit, the path stops behind the wall,
 *       and the villager does not get to its bed.</li>
 *   <li>A long search with no complete path costs about 2 times a vanilla search. The vanilla behaviors search again
 *       at each tick for a walk target that the villager cannot reach. Thus, after a long search with no complete
 *       path, the searches of the villager to the same targets use the vanilla range for
 *       {@value #FAILED_SEARCH_TICKS} ticks. A move of more than {@value #FAILED_SEARCH_MOVE} blocks from the start
 *       of the failed search stops this rule.</li>
 *   <li>The gates of the village (see {@link VillageHeartBlockEntity#gateSet}): with the village calm, a closed fence
 *       gate of a gate is a closed wooden door for the path finder of a villager of the village. The villager opens
 *       the gate on its path and closes it after it (see {@link UsePassageGoal}). An other fence gate, for example
 *       the gate of a pen, stays closed for the villagers. During an alert, the villagers do not open the
 *       gates.</li>
 *   <li>Routes through a gate (see {@link #routeThroughGate}): a long search with no complete path can have a wall of
 *       the village between the villager and its target. For example, the inner wall of a town stands between the
 *       core and the homes of the ring band. The path along the wall to a gate and back is long, and the search
 *       visits all its nodes near the wall before it finds the gate. Thus, after such a search, the villager walks
 *       to a gate first: the gate of the wall between the villager and the target with the shortest walk. The path
 *       ends {@value #ROUTE_STEP} blocks behind the gate, on the side of the target. At the end of this path, the
 *       vanilla behaviors search the path to the target again.</li>
 *   <li>A builder at work gets the navigation of a builder (see
 *       {@link io.github.nicolasgoyon.folkhold.world.construction.BuilderNavigation}). This navigation has no zone: a
 *       builder repairs the towers and the palisade. After the work, the builder gets this navigation again.</li>
 * </ul>
 */
public final class FolkNavigation extends GroundPathNavigation {
    /** The speed of a villager that walks out of a zone of the wall, as a multiple of its speed. */
    public static final float LEAVE_SPEED = 0.6F;
    /** A villager that walks out of a zone of the wall stops at this distance from the heart, in blocks. */
    public static final int LEAVE_CLOSE_ENOUGH = 3;
    /** A villager does not fall this number of blocks or more into a pit (see {@link Pits}). */
    public static final int FALL = 2;
    /** A position at the edge of a drop costs this number of blocks of walk more (see {@link Pits#isEdge}). */
    public static final float EDGE_COST = 4.0F;
    /** A path of a villager of a faction village walks this number of blocks at most. */
    public static final float PATH_RANGE = 96.0F;
    /** The search of a way (see {@link #hasWay}) goes this number of blocks farther than the target. */
    static final int WAY_MARGIN = 16;
    /** The search of a way goes this number of blocks from the villager at least. */
    static final int MIN_WAY_RANGE = 48;
    /** The search of a way goes this number of blocks from the villager at most. */
    static final int MAX_WAY_RANGE = 160;
    /** The search of a way visits this number of nodes at most for each block of its range. */
    static final int WAY_NODES_PER_BLOCK = 32;
    /** The check of a step up (see {@link #allowsStepUp}) makes the box of the villager smaller by this margin. */
    private static final double BOX_MARGIN = 1.0E-7;

    /**
     * After a long search with no complete path, the searches to the same targets use the vanilla range for this
     * time, in ticks.
     */
    public static final int FAILED_SEARCH_TICKS = 100;
    /** A search at this distance or less from the start of the failed search uses the vanilla range, in blocks. */
    public static final int FAILED_SEARCH_MOVE = 2;
    /** The targets of the last long search with no complete path, or null. */
    @Nullable
    private Set<BlockPos> failedTargets;
    /** The block of the villager at the start of the last long search with no complete path. */
    private BlockPos failedFrom = BlockPos.ZERO;
    /** The game time at the end of the use of the vanilla range after the failed long search. */
    private long failedUntil;
    /** A route through a gate ends this number of blocks behind the gate, on the side of the target. */
    public static final int ROUTE_STEP = 2;
    /**
     * The score of a gate of a route is the walk to the gate times this factor, plus the walk from the gate to the
     * target. Thus with 2 gates on one line (the gate of the inner wall and the gate of a ring), the villager goes
     * to the nearer gate first.
     */
    private static final double ROUTE_NEAR_FACTOR = 1.5;
    /** Opens and closes the gates of the village on the path of the villager, or null before the first use. */
    @Nullable
    private UsePassageGoal passage;
    /** True while {@link #passage} runs. */
    private boolean passageRunning;

    private FolkNavigation(Villager villager, PathNavigation original) {
        super(villager, villager.level());
        setCanOpenDoors(true);
        setCanFloat(original.canFloat());
    }

    @Override
    @Nullable
    protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy,
            float followRange) {
        WallZones zones = mob instanceof Villager villager ? zonesOf(villager) : WallZones.NONE;
        boolean folk = mob instanceof Villager villager && VillageFolk.isVillageFolk(villager);
        Set<BlockPos> allowed = targets;
        if (!zones.isEmpty()) {
            allowed = allowed.stream().filter(target -> !zones.contains(target))
                    .collect(Collectors.toUnmodifiableSet());
        }
        if (folk && !allowed.isEmpty() && allowed.stream().anyMatch(target -> isNearPit(target))
                && !Pits.isPit(level, mob.blockPosition())) {
            allowed = allowed.stream().filter(target -> !isNearPit(target)).collect(Collectors.toUnmodifiableSet());
        }
        if (allowed.isEmpty()) {
            return null;
        }
        Path path;
        if (folk && followRange < PATH_RANGE && !failedLongSearch(allowed)) {
            setMaxVisitedNodesMultiplier(PATH_RANGE / followRange);
            try {
                path = super.createPath(allowed, regionOffset, offsetUpward, accuracy, PATH_RANGE);
            } finally {
                resetMaxVisitedNodesMultiplier();
            }
            if (path == null || !path.canReach()) {
                failedTargets = allowed;
                failedFrom = mob.blockPosition();
                failedUntil = level.getGameTime() + FAILED_SEARCH_TICKS;
                Path route = routeThroughGate(allowed, regionOffset, offsetUpward, followRange);
                if (route != null) {
                    path = route;
                }
            } else {
                failedTargets = null;
            }
        } else {
            path = super.createPath(allowed, regionOffset, offsetUpward, accuracy, followRange);
        }
        if (path != null && !zones.isEmpty()) {
            skipStepsUp(path, zones, mob.blockPosition().getY());
        }
        return path;
    }

    /**
     * The path to a gate of the village between the villager and the nearest target (see the rules of the class). A
     * gate is between them when the villager and the target are on 2 sides of the line of the fence gate across
     * its axis. The path ends {@value #ROUTE_STEP} blocks behind the gate, on the side of the target. With the
     * village not calm, or with no such gate, or with no complete path to the end of the route, there is no route.
     *
     * @return the path, or null with no route
     */
    @Nullable
    private Path routeThroughGate(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, float followRange) {
        if (!(mob instanceof Villager villager) || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        VillageHeartBlockEntity heart = heartOf(villager);
        if (heart == null || heart.getAlertState() != AlertState.CALM) {
            return null;
        }
        BlockPos from = mob.blockPosition();
        BlockPos target = targets.stream().min((a, b) -> Double.compare(a.distSqr(from), b.distSqr(from)))
                .orElseThrow();
        BlockPos end = null;
        double best = Double.MAX_VALUE;
        for (BlockPos gate : heart.getLayout().gates()) {
            BlockState state = serverLevel.getBlockState(gate);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }
            Direction.Axis axis = state.getValue(FenceGateBlock.FACING).getAxis();
            int side = Integer.signum(target.get(axis) - gate.get(axis));
            if (side == 0 || Integer.signum(from.get(axis) - gate.get(axis)) != -side) {
                continue;
            }
            double score = ROUTE_NEAR_FACTOR * Math.sqrt(from.distSqr(gate)) + Math.sqrt(gate.distSqr(target));
            if (score < best) {
                best = score;
                end = gate.relative(Direction.fromAxisAndDirection(axis, side > 0 ? Direction.AxisDirection.POSITIVE
                        : Direction.AxisDirection.NEGATIVE), ROUTE_STEP);
            }
        }
        if (end == null) {
            return null;
        }
        setMaxVisitedNodesMultiplier(PATH_RANGE / followRange);
        Path path;
        try {
            path = super.createPath(Set.of(end), regionOffset, offsetUpward, 1, PATH_RANGE);
        } finally {
            resetMaxVisitedNodesMultiplier();
        }
        return path != null && path.canReach() ? path : null;
    }

    /**
     * Ticks the navigation, then opens and closes the gates of the village on the path of a villager of the village
     * (see {@link UsePassageGoal}).
     */
    @Override
    public void tick() {
        super.tick();
        if (!(mob instanceof Villager villager) || !VillageFolk.isVillageFolk(villager)) {
            return;
        }
        if (passage == null) {
            passage = new UsePassageGoal(villager, pos -> isOpenableGate(villager, pos));
        }
        if (passageRunning) {
            if (passage.canContinueToUse()) {
                passage.tick();
            } else {
                passage.stop();
                passageRunning = false;
            }
        } else if (passage.canUse()) {
            passage.start();
            passageRunning = true;
        }
    }

    /** True if the position is a fence gate of a gate of the village of the villager, with the village calm. */
    private static boolean isOpenableGate(Villager villager, BlockPos pos) {
        return gatesOf(villager).contains(pos.asLong());
    }

    /**
     * The fence gates of the gates of the village of a villager that the villager can open: the gates of a calm
     * village (see {@link VillageHeartBlockEntity#gateSet}).
     *
     * @param villager the villager
     * @return the positions as {@link BlockPos#asLong} values, empty with the village not calm or with no heart
     */
    public static LongSet gatesOf(Villager villager) {
        VillageHeartBlockEntity heart = heartOf(villager);
        return heart == null || heart.getAlertState() != AlertState.CALM ? LongSets.EMPTY_SET : heart.gateSet();
    }

    /**
     * True if a search of the villager to the targets uses the range {@value #PATH_RANGE}: the villager is a villager
     * of a faction village, and no recent long search to the same targets failed near its position. The GameTests use
     * this method.
     *
     * @param targets the targets of the search
     * @return true for a long search
     */
    public boolean usesLongSearch(Set<BlockPos> targets) {
        return mob instanceof Villager villager && VillageFolk.isVillageFolk(villager)
                && mob.getAttributeValue(Attributes.FOLLOW_RANGE) < PATH_RANGE && !failedLongSearch(targets);
    }

    /**
     * True if a search to the targets follows a long search with no complete path to the same targets: the search
     * starts near the start of the failed search, less than {@value #FAILED_SEARCH_TICKS} ticks after it.
     */
    private boolean failedLongSearch(Set<BlockPos> targets) {
        return failedTargets != null && level.getGameTime() < failedUntil && failedTargets.equals(targets)
                && mob.blockPosition().distManhattan(failedFrom) <= FAILED_SEARCH_MOVE;
    }

    /**
     * Skips the first nodes of a path that are in a zone and above the block of the feet of the villager. The path
     * finder starts a path at the node above the block of the feet when the villager stands on the low half of a
     * stair. On a stair of a tower, this node is in a zone and above the villager: the villager walks up to it, and
     * the vanilla move control jumps on a stair. The path then starts at the next node, on the same level or down
     * (see {@link Evaluator}). The last node stays.
     *
     * @param path  the path
     * @param zones the zones of the wall of the village of the villager
     * @param feetY the Y of the block of the feet of the villager
     */
    static void skipStepsUp(Path path, WallZones zones, int feetY) {
        while (path.getNextNodeIndex() < path.getNodeCount() - 1) {
            Node node = path.getNextNode();
            if (node.y <= feetY || !zones.contains(node.asBlockPos())) {
                return;
            }
            path.advance();
        }
    }

    /** True if a target is in a pit or at the edge of a pit (see {@link Pits}). */
    private boolean isNearPit(BlockPos target) {
        return Pits.isPit(level, target) || Pits.isPitEdge(level, target);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new Evaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    /**
     * Gives this navigation to a villager that joins a server level.
     *
     * @param event the event
     */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Villager villager) {
            equip(villager);
        }
    }

    /**
     * Gives this navigation to a villager, if it does not have it.
     *
     * @param villager the villager
     */
    public static void equip(Villager villager) {
        PathNavigation current = villager.navigation;
        if (!(current instanceof FolkNavigation)) {
            current.stop();
            villager.navigation = new FolkNavigation(villager, current);
        }
    }

    /**
     * The zones of the wall of the village of a villager.
     *
     * @param villager the villager
     * @return the zones, or {@link WallZones#NONE} for a villager that is not a villager of a faction village, or with
     *         the chunk of its heart not loaded
     */
    public static WallZones zonesOf(Villager villager) {
        VillageHeartBlockEntity heart = heartOf(villager);
        return heart == null ? WallZones.NONE : heart.wallZones((ServerLevel) villager.level());
    }

    /** The heart of the village of a villager of a faction village, or null with its chunk not loaded. */
    @Nullable
    private static VillageHeartBlockEntity heartOf(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level) || !VillageFolk.isVillageFolk(villager)) {
            return null;
        }
        BlockPos heart = BlockPos.of(villager.getPersistentData().getLong(Merchants.VILLAGE_TAG));
        if (!level.isLoaded(heart) || !(level.getBlockEntity(heart) instanceof VillageHeartBlockEntity entity)) {
            return null;
        }
        return entity;
    }

    /**
     * True if a mob has a way to a target: the path finder of the mob finds a path that reaches the target. A villager
     * uses the path finder of the villagers, with the zones of the wall of its village. A unit of a garrison uses the
     * path finder of the units (see {@link UnitNavigation#newEvaluator}). The search does not change the path of the
     * mob. Its range is the distance to the target and {@value #WAY_MARGIN} blocks, from {@value #MIN_WAY_RANGE} to
     * {@value #MAX_WAY_RANGE} blocks.
     *
     * @param mob         the mob
     * @param target      the target
     * @param closeEnough the path reaches the target at this distance or nearer, in blocks
     * @return true with a way
     */
    public static boolean hasWay(PathfinderMob mob, BlockPos target, int closeEnough) {
        BlockPos from = mob.blockPosition();
        int distance = (int) Math.ceil(Math.sqrt(from.distSqr(target)));
        int range = Mth.clamp(distance + WAY_MARGIN, MIN_WAY_RANGE, MAX_WAY_RANGE);
        PathNavigationRegion region = new PathNavigationRegion(mob.level(), from.offset(-range, -range, -range),
                from.offset(range, range, range));
        WalkNodeEvaluator evaluator;
        if (mob.getNavigation() instanceof UnitNavigation) {
            evaluator = UnitNavigation.newEvaluator();
        } else {
            evaluator = new Evaluator();
            evaluator.setCanPassDoors(true);
            evaluator.setCanOpenDoors(true);
        }
        evaluator.setCanFloat(mob.getNavigation().canFloat());
        PathFinder finder = new PathFinder(evaluator, range * WAY_NODES_PER_BLOCK);
        Path path = finder.findPath(region, mob, Set.of(target), range, closeEnough, 1.0F);
        return path != null && path.canReach();
    }

    /**
     * True if the move control of a mob can jump. A villager of a faction village does not jump when its feet or the
     * block above its feet are in a zone of the wall of its village. On the low half of a stair, the vanilla move
     * control jumps at each step. On the first stair of a tower, this jump lifts the villager onto the next stairs.
     * The villager still steps up a stair with no jump, and a path in a zone goes only on the same level or down.
     *
     * @param mob the mob
     * @return false for a villager that jumps into a zone of the wall
     */
    public static boolean allowsJump(Mob mob) {
        if (!(mob instanceof Villager villager) || !(villager.navigation instanceof FolkNavigation)) {
            return true;
        }
        WallZones zones = zonesOf(villager);
        BlockPos feet = villager.blockPosition();
        return zones.isEmpty() || (!zones.contains(feet) && !zones.contains(feet.above()));
    }

    /**
     * True if an entity can step up in a move. A villager of a faction village does not step up when its feet after the
     * step can be in a zone of the wall of its village. The check uses the level of the feet with the full step
     * height, at each block under the box of the villager after the horizontal move. Thus a villager at the side of
     * the stair of a tower does not walk up the stairs, with its feet out of the stair row. The villager still steps
     * onto the low half of the first stair, under the zone of the tower.
     *
     * @param entity   the entity
     * @param movement the move of the entity in this tick
     * @param step     the step height of the entity, more than 0
     * @return false for a villager that can step up into a zone of the wall
     */
    public static boolean allowsStepUp(Entity entity, Vec3 movement, float step) {
        if (!(entity instanceof Villager villager) || !(villager.navigation instanceof FolkNavigation)
                || movement.horizontalDistanceSqr() == 0.0) {
            return true;
        }
        WallZones zones = zonesOf(villager);
        if (zones.isEmpty()) {
            return true;
        }
        int feet = Mth.floor(villager.getY() + step);
        AABB box = villager.getBoundingBox().move(movement.x, 0.0, movement.z).deflate(BOX_MARGIN, 0.0, BOX_MARGIN);
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                if (zones.contains(x, feet, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Gives a walk to the heart to each villager of a village that stands in a zone of the wall. A builder at work and
     * a villager in a bed keep their walk.
     *
     * @param heart       the heart of the village
     * @param zones       the zones of the wall of the village
     * @param inhabitants the loaded villagers of the village
     * @return the number of villagers that get a walk to the heart
     */
    public static int leaveWall(BlockPos heart, WallZones zones, List<Villager> inhabitants) {
        if (zones.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Villager villager : inhabitants) {
            if (!(villager.navigation instanceof FolkNavigation) || villager.isSleeping()
                    || !zones.contains(villager.blockPosition())) {
                continue;
            }
            Brain<Villager> brain = villager.getBrain();
            Optional<WalkTarget> target = brain.getMemory(MemoryModuleType.WALK_TARGET);
            if (target.isPresent() && target.get().getTarget().currentBlockPosition().equals(heart)) {
                continue;
            }
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(heart, LEAVE_SPEED, LEAVE_CLOSE_ENOUGH));
            count++;
        }
        return count;
    }

    /**
     * The path types of a vanilla villager. A closed fence gate of a gate of the village is a closed wooden door. A position in a zone of the wall is a neighbor only of a position in a zone
     * on the same level or above it. A position on a palisade column is a neighbor only of a position on a column.
     */
    private static final class Evaluator extends WalkNodeEvaluator {
        private WallZones zones = WallZones.NONE;
        /** The fence gates that the villager can open (see {@link #gatesOf}). */
        private LongSet gates = LongSets.EMPTY_SET;
        /** True for a villager of a faction village: the rules of the pits and of the edges apply. */
        private boolean folk;
        /** The pits of this search (see {@link Pits#isPit}), by position. */
        private final Long2BooleanMap pits = new Long2BooleanOpenHashMap();
        /** The node of the search that gets its neighbors, or null. */
        @Nullable
        private Node expanded;

        @Override
        public void prepare(PathNavigationRegion region, Mob mob) {
            super.prepare(region, mob);
            zones = mob instanceof Villager villager ? zonesOf(villager) : WallZones.NONE;
            folk = mob instanceof Villager villager && VillageFolk.isVillageFolk(villager);
            gates = mob instanceof Villager villager ? gatesOf(villager) : LongSets.EMPTY_SET;
        }

        @Override
        public void done() {
            super.done();
            zones = WallZones.NONE;
            folk = false;
            gates = LongSets.EMPTY_SET;
            pits.clear();
            expanded = null;
        }

        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            if (!gates.isEmpty() && gates.contains(BlockPos.asLong(x, y, z))) {
                BlockState state = context.getBlockState(new BlockPos(x, y, z));
                if (UnitNavigation.isClosedGate(state)) {
                    return PathType.DOOR_WOOD_CLOSED;
                }
            }
            return super.getPathType(context, x, y, z);
        }

        @Override
        public int getNeighbors(Node[] outputArray, Node node) {
            expanded = node;
            try {
                return super.getNeighbors(outputArray, node);
            } finally {
                expanded = null;
            }
        }

        @Override
        @Nullable
        protected Node findAcceptedNode(int x, int y, int z, int verticalDeltaLimit, double nodeFloorLevel,
                Direction direction, PathType pathType) {
            Node node = super.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathType);
            if (node != null && folk) {
                node = withDrops(node);
            }
            if (node == null || zones.isEmpty()) {
                return node;
            }
            boolean column = zones.onColumn(node.x, node.y, node.z);
            if (!column && !zones.inTower(node.x, node.y, node.z)) {
                return node;
            }
            Node from = expanded;
            if (from == null || node.y > from.y) {
                return null;
            }
            boolean fromColumn = zones.onColumn(from.x, from.y, from.z);
            return fromColumn || (!column && zones.inTower(from.x, from.y, from.z)) ? node : null;
        }

        /**
         * The rules of the drops for a node of a villager of a faction village: a fall of {@value #FALL} blocks or
         * more into a pit gives no node, and a node at the edge of a drop costs {@value #EDGE_COST} blocks of walk
         * more. A fall in a pit where the search starts stays possible.
         */
        @Nullable
        private Node withDrops(Node node) {
            Node from = expanded;
            if (from != null && from.y - node.y >= FALL && isPit(node.x, node.y, node.z)
                    && !isPit(from.x, from.y, from.z)) {
                return null;
            }
            if (node.costMalus >= 0.0F && Pits.isEdge(currentContext.level(), new BlockPos(node.x, node.y, node.z))) {
                node.costMalus = Math.max(node.costMalus, EDGE_COST);
            }
            return node;
        }

        private boolean isPit(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return pits.computeIfAbsent(pos.asLong(), key -> Pits.isPit(currentContext.level(), pos));
        }
    }
}
