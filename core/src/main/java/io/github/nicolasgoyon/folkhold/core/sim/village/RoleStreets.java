package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Dir;
import java.util.function.IntBinaryOperator;

/**
 * The turn of the plaza that puts the role streets of a village on the best ground. The first piece of the axis street
 * to the north of the plan holds the bank, and the first piece of the street to the south holds the first jobs (see
 * {@link PlotRole}). These pieces and their plots do not obey the rules of the ground, thus the first wall goes around
 * them also on high ground.
 *
 * <ul>
 *   <li>The area of the first piece of an axis street: the first segment, the street end after it and the plots on
 *       its 2 sides, in the direction of the street from the plaza.</li>
 *   <li>The climb of an area: the sum, on the grid points of {@link PlanGround#STEP} blocks in the area, of the blocks
 *       of these points above the plaza and {@link PlanGround#HIGH_RISE}.</li>
 *   <li>The score of a turn: the climb of the area of the street to the north of the plan, plus the climb of the area of
 *       the street to the south. The plaza keeps its turn when no other turn has a lower score.</li>
 * </ul>
 */
public final class RoleStreets {
    private RoleStreets() {
    }

    /**
     * The best turn of the plaza.
     *
     * @param surface   the surface of the terrain at an offset from the middle of the plaza, from the offset on the X
     *                  axis and on the Z axis, in blocks
     * @param level     the surface at the middle of the plaza
     * @param plazaSize the size of the plaza, in blocks
     * @param style     the style of the street pieces
     * @param turns     the clockwise quarter turns of the plaza in the world, from 0 to 3
     * @return the best clockwise quarter turns, from 0 to 3: the given turns when no other turn is better
     */
    public static int bestTurns(IntBinaryOperator surface, int level, int plazaSize, StreetStyle style, int turns) {
        // The directions at 0, 1, 2 and 3 clockwise quarter turns from the north.
        Dir[] clockwise = {Dir.NORTH, Dir.EAST, Dir.SOUTH, Dir.WEST};
        long[] climbs = new long[clockwise.length];
        for (int i = 0; i < clockwise.length; i++) {
            climbs[i] = climb(surface, level, plazaSize, style, clockwise[i]);
        }
        int best = Math.floorMod(turns, 4);
        long bestScore = score(climbs, best);
        for (int turn = 0; turn < 4; turn++) {
            long score = score(climbs, turn);
            if (score < bestScore) {
                best = turn;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The score of a turn: the climbs of the world directions of the streets to the north and to the south of the
     * plan. Index i of the climbs is the direction at i clockwise quarter turns from the north.
     */
    private static long score(long[] climbs, int turn) {
        return climbs[turn] + climbs[(turn + 2) % 4];
    }

    /**
     * The climb of the area of the first piece of an axis street in a direction of the world.
     *
     * @param surface   the surface of the terrain at an offset from the middle of the plaza
     * @param level     the surface at the middle of the plaza
     * @param plazaSize the size of the plaza, in blocks
     * @param style     the style of the street pieces
     * @param dir       the direction of the street in the world
     * @return the climb, 0 or more
     */
    public static long climb(IntBinaryOperator surface, int level, int plazaSize, StreetStyle style, Dir dir) {
        int from = plazaSize / 2 + 1;
        int length = style.segment() + StreetStyle.END;
        int half = StreetStyle.WIDTH / 2 + style.home().depth();
        Dir side = dir.counterclockwise();
        long climb = 0;
        for (int along = from; along < from + length; along += PlanGround.STEP) {
            for (int across = -half; across <= half; across += PlanGround.STEP) {
                int dx = along * dir.dx() + across * side.dx();
                int dz = along * dir.dz() + across * side.dz();
                climb += Math.max(0, surface.applyAsInt(dx, dz) - level - PlanGround.HIGH_RISE);
            }
        }
        return climb;
    }
}
