package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.entity.ai.UnitNavigation;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
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
 *       on the same level or down: the villager walks down the stair of a tower, and does not go up. A step to the top
 *       of a palisade column starts only on the top of a column: a path from a tower does not end on a column. The
 *       check of the villagers by the heart gives a walk to the heart to a villager in a zone (see
 *       {@link #leaveWall}).</li>
 *   <li>A villager of a faction village keeps away from the drops (see {@link Pits}). The navigation gives no path to
 *       a target in a pit or at the edge of a pit, except for a villager in a pit. The path finder gives no fall of {@value #FALL} blocks or
 *       more into a pit, and a position at the edge of a drop of 2 blocks or more costs {@value #EDGE_COST} blocks of
 *       walk more. Thus the wander targets and the paths of the villagers do not go into a pit.</li>
 *   <li>The zones come from the heart of the village of the villager (see
 *       {@link VillageHeartBlockEntity#wallZones}). With the chunk of the heart not loaded, the villager has no
 *       zone.</li>
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
    /** The search of a way (see {@link #hasWay}) goes this number of blocks farther than the target. */
    static final int WAY_MARGIN = 16;
    /** The search of a way goes this number of blocks from the villager at least. */
    static final int MIN_WAY_RANGE = 48;
    /** The search of a way goes this number of blocks from the villager at most. */
    static final int MAX_WAY_RANGE = 160;
    /** The search of a way visits this number of nodes at most for each block of its range. */
    static final int WAY_NODES_PER_BLOCK = 32;

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
        return allowed.isEmpty() ? null
                : super.createPath(allowed, regionOffset, offsetUpward, accuracy, followRange);
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
        if (!(villager.level() instanceof ServerLevel level) || !VillageFolk.isVillageFolk(villager)) {
            return WallZones.NONE;
        }
        BlockPos heart = BlockPos.of(villager.getPersistentData().getLong(Merchants.VILLAGE_TAG));
        if (!level.isLoaded(heart) || !(level.getBlockEntity(heart) instanceof VillageHeartBlockEntity entity)) {
            return WallZones.NONE;
        }
        return entity.wallZones(level);
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
     * The path types of a vanilla villager. A position in a zone of the wall is a neighbor only of a position in a zone
     * on the same level or above it. A position on a palisade column is a neighbor only of a position on a column.
     */
    private static final class Evaluator extends WalkNodeEvaluator {
        private WallZones zones = WallZones.NONE;
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
        }

        @Override
        public void done() {
            super.done();
            zones = WallZones.NONE;
            folk = false;
            pits.clear();
            expanded = null;
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
