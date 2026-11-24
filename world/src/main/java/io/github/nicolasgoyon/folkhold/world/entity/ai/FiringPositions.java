package io.github.nicolasgoyon.folkhold.world.entity.ai;

import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The firing positions of a ranged unit on the platform of its post, and the query that selects a firing position for
 * a target. The target goals and the lookout goal of the unit use the same query.
 *
 * <ul>
 *   <li>The firing positions are the lookout spots of the platform (see {@link PlatformLookoutGoal#spots}). The unit
 *       reads them again after {@link #SPOTS_TICKS}, or when its post changes. Thus a change of the blocks of the
 *       tower (a repair, a stone tower) changes the spots.</li>
 *   <li>A spot has a line of fire to a target when the target is in the range of the unit from the spot, and no block
 *       is on the line from the eyes of the unit at the spot to the eyes of the target.</li>
 *   <li>The query runs the cheap tests first: the line of sight from the current position of the unit, then the range
 *       from the post, then the range from each spot. The ray tests from the spots come last.</li>
 *   <li>The query selects the spot with a line of fire that is nearest to the unit. Thus the unit walks the shortest
 *       distance, and stays at the parapet on the side of the targets. For two spots at the same distance, the spot
 *       nearer to the post wins.</li>
 *   <li>The result of the test of a target stays valid for {@link #CACHE_TICKS}. A target goal tests all the
 *       monsters near the unit, so the cache limits the ray tests.</li>
 *   <li>The spots count only when the unit is on the platform of its post. On the way to its post, the unit has a
 *       line of fire only to the targets that it sees.</li>
 * </ul>
 */
public final class FiringPositions {
    /** The unit reads the spots of its platform again after this time, in ticks. */
    static final int SPOTS_TICKS = 100;
    /** The result of the test of a target stays valid for this time, in ticks. */
    static final int CACHE_TICKS = 10;
    /** The distance from the post to the farthest spot of the platform, in blocks, with a margin. */
    private static final double PLATFORM_REACH = PlatformLookoutGoal.PLATFORM_RADIUS * Math.sqrt(2.0) + 1.0;
    /** The tolerance of the comparison of two distances, in blocks squared. */
    private static final double SAME_DISTANCE = 1.0E-4;

    private final FactionUnit unit;
    private final DoubleSupplier range;
    @Nullable
    private BlockPos spotsPost;
    private long spotsTime = Long.MIN_VALUE;
    private List<Vec3> spots = List.of();
    private final Map<Integer, Entry> cache = new HashMap<>();

    /** The result of the test of one target: a line of fire from a spot, at a game time. */
    private record Entry(long time, boolean line) {
    }

    /**
     * @param unit  the unit
     * @param range the range of the weapon of the unit, in blocks: the attack radius
     */
    public FiringPositions(FactionUnit unit, DoubleSupplier range) {
        this.unit = unit;
        this.range = range;
    }

    /**
     * True if the unit can shoot the target: it sees the target from its current position, or a spot of the platform
     * of its post has a line of fire to the target. False for a unit with no post that does not see the target.
     *
     * @param target the target
     * @return true if the unit sees the target or a spot has a line of fire to it
     */
    public boolean hasLineOfFire(LivingEntity target) {
        if (unit.getSensing().hasLineOfSight(target)) {
            return true;
        }
        BlockPos post = unit.getPostPos();
        if (post == null || !isOnPlatform(post) || !isInReach(Vec3.atBottomCenterOf(post), target, PLATFORM_REACH)) {
            return false;
        }
        long now = unit.level().getGameTime();
        Entry entry = cache.get(target.getId());
        if (entry != null && now - entry.time() < CACHE_TICKS) {
            return entry.line();
        }
        if (cache.size() > 64) {
            cache.values().removeIf(old -> now - old.time() >= CACHE_TICKS);
        }
        boolean line = select(target) != null;
        cache.put(target.getId(), new Entry(now, line));
        return line;
    }

    /**
     * The spot with a line of fire to the target that is nearest to the unit, or null if no spot has a line of fire.
     *
     * @param target the target
     * @return the position of the feet of the unit at the spot, or null
     */
    @Nullable
    public Vec3 select(LivingEntity target) {
        BlockPos post = unit.getPostPos();
        if (post == null) {
            return null;
        }
        Vec3 postCenter = Vec3.atBottomCenterOf(post);
        Vec3 eyes = target.getEyePosition();
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        double bestToPost = Double.MAX_VALUE;
        for (Vec3 spot : spots(post)) {
            double distance = spot.distanceToSqr(unit.position());
            double toPost = spot.distanceToSqr(postCenter);
            boolean better = distance < bestDistance - SAME_DISTANCE
                    || (distance <= bestDistance + SAME_DISTANCE && toPost < bestToPost);
            if (better && isInReach(spot, target, 0.0) && PlatformLookoutGoal.canSee(unit, spot, eyes)) {
                best = spot;
                bestDistance = distance;
                bestToPost = toPost;
            }
        }
        return best;
    }

    /** The spots of the platform of the post. The unit reads them again after {@link #SPOTS_TICKS}. */
    private List<Vec3> spots(BlockPos post) {
        long now = unit.level().getGameTime();
        if (!post.equals(spotsPost) || now - spotsTime >= SPOTS_TICKS || now < spotsTime) {
            spots = PlatformLookoutGoal.spots(unit, post);
            spotsPost = post;
            spotsTime = now;
        }
        return spots;
    }

    /** True if the unit is on the platform of its post: near the post, at the level of the post. */
    private boolean isOnPlatform(BlockPos post) {
        double dx = unit.getX() - (post.getX() + 0.5);
        double dz = unit.getZ() - (post.getZ() + 0.5);
        return Math.max(Math.abs(dx), Math.abs(dz)) <= PlatformLookoutGoal.PLATFORM_RADIUS + 0.5
                && Math.abs(unit.getY() - post.getY()) <= PlatformLookoutGoal.MAX_LEVEL_DIFFERENCE + 0.5;
    }

    /**
     * True if the target is in the range of the unit from a position of the feet of the unit, plus a margin. The
     * attack goals measure the same distance: from the feet of the unit to the feet of the target.
     */
    private boolean isInReach(Vec3 feet, LivingEntity target, double margin) {
        double reach = range.getAsDouble() + margin;
        return feet.distanceToSqr(target.position()) <= reach * reach;
    }
}
