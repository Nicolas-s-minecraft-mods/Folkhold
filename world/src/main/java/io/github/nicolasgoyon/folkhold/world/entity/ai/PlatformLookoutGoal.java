package io.github.nicolasgoyon.folkhold.world.entity.ai;

import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Lookout of a ranged unit on its post: the unit moves on the platform of its post to a firing position with a line of
 * fire to its target. The goal does not use the weapon of the unit: the attack goals of the weapon shoot when the unit
 * sees the target.
 *
 * <ul>
 *   <li>The goal starts when the unit is at its post, has a target, and has no line of sight to the target for
 *       {@link #BLIND_TICKS}. For example, the parapet of a tower hides a target near the foot of the tower.</li>
 *   <li>The platform is the set of positions where the unit can stand, connected to the post with steps of
 *       {@link #MAX_STEP} blocks or less, at {@link #PLATFORM_RADIUS} blocks or less from the post and at
 *       {@link #MAX_LEVEL_DIFFERENCE} block or less above or below the post. The parapet slabs of a tower are a part
 *       of the platform. A drop is not a part of the platform.</li>
 *   <li>The lookout spots are the middle of each position of the platform, and the outer edge of each position at
 *       the border of the platform. At an edge spot, the unit leans over the drop: it sees and shoots the targets
 *       near the foot of its tower.</li>
 *   <li>The unit goes to the spot that {@link FiringPositions#select} gives: the spot with a line of fire to the target
 *       that is nearest to the unit. It does not jump and does not leave the platform. While the unit holds a spot, a
 *       hit does not push it (see {@link FactionUnit#isOnLookout()}).</li>
 *   <li>When the unit loses the line of sight at the spot for {@link #BLIND_TICKS}, it looks for a new spot. When no
 *       spot gives a line of fire, the unit goes back to its post. The target goals then drop the target, and the
 *       unit selects a target that it can shoot.</li>
 *   <li>When the unit has no target for {@link #NO_TARGET_TICKS}, it goes back to its post. A new target in this time
 *       starts the search for a spot from the current spot.</li>
 * </ul>
 */
public class PlatformLookoutGoal extends Goal {
    /** The unit looks for a lookout spot after this time with no line of sight to its target, in ticks. */
    static final int BLIND_TICKS = 10;
    /** The unit goes back to its post after this time with no target, in ticks. */
    static final int NO_TARGET_TICKS = 40;
    /** The largest horizontal distance from the post to a position of the platform, in blocks. */
    static final int PLATFORM_RADIUS = 3;
    /** The largest difference of height from the post to a position of the platform, in blocks. */
    static final double MAX_LEVEL_DIFFERENCE = 1.0;
    /** The largest difference of height between two adjacent positions of the platform, in blocks. */
    static final double MAX_STEP = 0.6;
    /** The unit is at its post at this horizontal distance or less, in blocks. */
    private static final double AT_POST_DISTANCE = 1.0;
    /** The unit stops at a spot at this horizontal distance or less, in blocks. */
    private static final double ARRIVED_DISTANCE = 0.4;
    /** The unit gives up the walk to a spot or to its post after this time, in ticks. */
    private static final int MOVE_TICKS = 60;
    /** The time between two searches of a spot, in ticks. */
    private static final int SEARCH_INTERVAL = 10;
    /** The speed of the unit on the platform. */
    private static final double SPEED = 0.8;
    /** The height of a bottom slab, in blocks. */
    private static final double SLAB = 0.5;

    private enum Phase { MOVE, HOLD, RETURN }

    private final FactionUnit unit;
    private final FiringPositions positions;
    @Nullable
    private Vec3 spot;
    private Phase phase = Phase.MOVE;
    private int moveTicks;
    private long blindSince = -1;
    private long nextSearch;
    private int noTargetTicks;

    /**
     * @param unit      the unit, with the attack goals of its weapon
     * @param positions the firing positions of the unit
     */
    public PlatformLookoutGoal(FactionUnit unit, FiringPositions positions) {
        this.unit = unit;
        this.positions = positions;
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = activeTarget();
        BlockPos post = unit.getPostPos();
        if (target == null || post == null || !isAtPost(post)) {
            blindSince = -1;
            return false;
        }
        if (!isBlind(target) || unit.level().getGameTime() < nextSearch) {
            return false;
        }
        nextSearch = unit.level().getGameTime() + SEARCH_INTERVAL;
        spot = positions.select(target);
        return spot != null;
    }

    @Override
    public boolean canContinueToUse() {
        return unit.getPostPos() != null && spot != null;
    }

    @Override
    public void start() {
        phase = Phase.MOVE;
        moveTicks = 0;
        blindSince = -1;
        noTargetTicks = 0;
        unit.getNavigation().stop();
    }

    @Override
    public void stop() {
        unit.setOnLookout(false);
        spot = null;
        blindSince = -1;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BlockPos post = unit.getPostPos();
        LivingEntity target = activeTarget();
        if (post == null || spot == null) {
            spot = null;
            return;
        }
        noTargetTicks = target == null ? noTargetTicks + 1 : 0;
        if (noTargetTicks > NO_TARGET_TICKS && phase != Phase.RETURN) {
            goBack(post);
        }
        long now = unit.level().getGameTime();
        if (phase != Phase.MOVE && target != null && isBlind(target) && now >= nextSearch) {
            nextSearch = now + SEARCH_INTERVAL;
            Vec3 next = positions.select(target);
            if (next == null) {
                if (phase != Phase.RETURN) {
                    goBack(post);
                }
            } else if (next.distanceToSqr(spot) > 1.0E-4) {
                spot = next;
                phase = Phase.MOVE;
                moveTicks = 0;
                unit.setOnLookout(false);
            } else {
                blindSince = -1;
            }
        }
        if (phase == Phase.HOLD) {
            hold();
            return;
        }
        if (horizontalDistance(spot) <= ARRIVED_DISTANCE && Math.abs(unit.getY() - spot.y) < MAX_STEP) {
            arrive();
            return;
        }
        if (++moveTicks > MOVE_TICKS) {
            if (phase == Phase.RETURN) {
                spot = null;
            } else {
                goBack(post);
            }
            return;
        }
        unit.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, SPEED);
    }

    /** The unit is at the spot: it stops there, on the spot. At its post, the goal ends. */
    private void arrive() {
        unit.getNavigation().stop();
        unit.setPos(spot.x, Math.max(unit.getY(), spot.y), spot.z);
        unit.setDeltaMovement(0.0, unit.getDeltaMovement().y, 0.0);
        if (phase == Phase.RETURN) {
            spot = null;
            return;
        }
        phase = Phase.HOLD;
        blindSince = -1;
        unit.setOnLookout(true);
    }

    /** The unit holds its spot: no horizontal movement, also after a push. */
    private void hold() {
        unit.setDeltaMovement(0.0, unit.getDeltaMovement().y, 0.0);
        unit.setPos(spot.x, unit.getY(), spot.z);
    }

    private void goBack(BlockPos post) {
        spot = Vec3.atBottomCenterOf(post);
        phase = Phase.RETURN;
        moveTicks = 0;
        unit.setOnLookout(false);
    }

    /** The target of the unit, or null if the unit has no target that it can attack. */
    @Nullable
    private LivingEntity activeTarget() {
        LivingEntity target = unit.getTarget();
        return target != null && target.isAlive() && unit.canAttack(target) ? target : null;
    }

    /** True if the unit has no line of sight to the target for {@link #BLIND_TICKS} or more. */
    private boolean isBlind(LivingEntity target) {
        long now = unit.level().getGameTime();
        if (unit.getSensing().hasLineOfSight(target)) {
            blindSince = -1;
            return false;
        }
        if (blindSince < 0) {
            blindSince = now;
        }
        return now - blindSince >= BLIND_TICKS;
    }

    private boolean isAtPost(BlockPos post) {
        return horizontalDistance(Vec3.atBottomCenterOf(post)) <= AT_POST_DISTANCE
                && Math.abs(unit.getY() - post.getY()) <= MAX_LEVEL_DIFFERENCE;
    }

    private double horizontalDistance(Vec3 pos) {
        double dx = unit.getX() - pos.x;
        double dz = unit.getZ() - pos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The lookout spots of the platform of a post: the middle of each position, and the outer edge of each position
     * at the border of the platform where the unit can stand with half of its body over the drop.
     *
     * @param unit the unit
     * @param post the post of the unit
     * @return the positions of the feet of the unit at the spots
     */
    static List<Vec3> spots(FactionUnit unit, BlockPos post) {
        Map<BlockPos, Double> platform = platform(unit, post);
        List<Vec3> spots = new ArrayList<>();
        for (Map.Entry<BlockPos, Double> cell : platform.entrySet()) {
            BlockPos pos = cell.getKey();
            double floor = cell.getValue();
            spots.add(new Vec3(pos.getX() + 0.5, floor, pos.getZ() + 0.5));
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (platform.containsKey(pos.relative(direction))) {
                    continue;
                }
                Vec3 edge = new Vec3(pos.getX() + 0.5 + direction.getStepX() * 0.5, floor,
                        pos.getZ() + 0.5 + direction.getStepZ() * 0.5);
                if (fits(unit, edge) && isDrop(unit.level(), pos.relative(direction), floor)) {
                    spots.add(edge);
                }
            }
        }
        return spots;
    }

    /**
     * The positions of the platform of a post, with the height of their floor. The search goes from the post to the
     * adjacent positions where the unit can stand, with a step of {@link #MAX_STEP} or less.
     */
    private static Map<BlockPos, Double> platform(FactionUnit unit, BlockPos post) {
        Map<BlockPos, Double> platform = new HashMap<>();
        Double postFloor = floor(unit.level(), post);
        if (postFloor == null) {
            return platform;
        }
        platform.put(post, postFloor);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(post);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            double from = platform.get(pos);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos column = pos.relative(direction);
                if (Math.max(Math.abs(column.getX() - post.getX()), Math.abs(column.getZ() - post.getZ()))
                        > PLATFORM_RADIUS) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = column.above(dy);
                    if (platform.containsKey(next)) {
                        continue;
                    }
                    Double floor = floor(unit.level(), next);
                    if (floor != null && Math.abs(floor - from) <= MAX_STEP
                            && Math.abs(floor - post.getY()) <= MAX_LEVEL_DIFFERENCE
                            && fits(unit, new Vec3(next.getX() + 0.5, floor, next.getZ() + 0.5))) {
                        platform.put(next.immutable(), floor);
                        queue.add(next.immutable());
                    }
                }
            }
        }
        return platform;
    }

    /**
     * The height of the floor at a position: the top of the block at the position if it is lower than a full block
     * (a slab), else the top of the block below if it is higher than a slab. Null if the position has no floor. Thus a
     * floor has one position only.
     */
    @Nullable
    private static Double floor(Level level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            double top = shape.max(Direction.Axis.Y);
            return top <= SLAB ? pos.getY() + top : null;
        }
        BlockPos below = pos.below();
        VoxelShape belowShape = level.getBlockState(below).getCollisionShape(level, below);
        if (belowShape.isEmpty() || belowShape.max(Direction.Axis.Y) <= SLAB) {
            return null;
        }
        return below.getY() + belowShape.max(Direction.Axis.Y);
    }

    /** True if the column next to the platform has no floor at the level of the platform: a drop. */
    private static boolean isDrop(Level level, BlockPos column, double floor) {
        BlockPos pos = BlockPos.containing(column.getX(), floor, column.getZ());
        Double next = floor(level, pos);
        return next == null || next < floor - MAX_STEP;
    }

    /** True if the body of the unit has space at the position of its feet. */
    private static boolean fits(FactionUnit unit, Vec3 feet) {
        AABB box = unit.getDimensions(unit.getPose()).makeBoundingBox(feet);
        return unit.level().noCollision(unit, box);
    }

    /** True if no block hides a point from the eyes of the unit at a spot. */
    static boolean canSee(FactionUnit unit, Vec3 feet, Vec3 targetEyes) {
        Vec3 eyes = feet.add(0.0, unit.getEyeHeight(), 0.0);
        return unit.level().clip(new ClipContext(eyes, targetEyes, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, unit)).getType() == HitResult.Type.MISS;
    }
}
