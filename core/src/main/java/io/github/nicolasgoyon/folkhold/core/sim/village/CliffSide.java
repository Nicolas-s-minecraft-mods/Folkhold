package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/**
 * The cliff side of a wall: the parts of the line of the palisade where the terrain outside goes down steeply (see
 * {@link WallLayout}). The calculation uses the heights of the terrain only.
 *
 * <ul>
 *   <li>The drop of a block of the ring is its level minus the lowest terrain at 1 to {@link #DISTANCE} blocks outside
 *       the line, on the axis out of its side. The level is the base of the column of the palisade (see
 *       {@link PalisadeHeights}), or the terrain of the line. A block is a cliff block when its drop is {@link #DROP}
 *       blocks or more.</li>
 *   <li>A gate is on a cliff when its drop is {@link #DROP} blocks or more: its level minus the lowest terrain at 1 to
 *       {@link #DISTANCE} blocks in front of its blocks, its posts included. The level of a gate is the highest base
 *       under it, or the level that its street can reach (see {@link GateDrop}). Such a gate opens on the cliff, and
 *       the flat terrain in front of it ends at the edge.</li>
 *   <li>A gate of the first wall on a cliff moves along its side to the nearest position with no cliff block, at most
 *       {@link #MAX_SHIFT} blocks: at this distance, the gate stays on the axis of its street. A gate of a ring does
 *       not move: the path across the band goes straight from the gate of the inner wall to the gate of the ring.</li>
 *   <li>A gate on a cliff that does not move goes away when the wall keeps another gate.</li>
 *   <li>The wall keeps at least one gate. When each gate is on a cliff, the gate with the nearest free position on its
 *       side moves there (first wall only). When no side has a free position, the gate with the smallest drop
 *       stays.</li>
 *   <li>A cliff block has a low palisade: {@link PalisadeHeights#LOW_LOGS} logs. The terrain below the cliff is too far
 *       down for a mob: the low palisade only keeps the inhabitants away from the edge.</li>
 * </ul>
 */
public final class CliffSide {
    /** A block of the ring is a cliff block when the terrain outside it goes down by this number of blocks or more. */
    public static final int DROP = 8;
    /**
     * The terrain outside a block of the ring counts up to this distance from the line, in blocks. The flat terrain in
     * front of a gate goes 6 blocks out, and the stairs of an exit have 6 steps at most: a drop of {@link #DROP} blocks
     * in this distance is too steep for them.
     */
    public static final int DISTANCE = 10;
    /** A gate of the first wall on a cliff moves along its side by this number of blocks at most. */
    public static final int MAX_SHIFT = 2;

    private CliffSide() {
    }

    /**
     * The drop at each block of the ring of a wall (see {@link WallLayout#ringSize()}), from the terrain of the line.
     *
     * @param layout the wall
     * @param height the Y of the top block of the terrain at a position (X, Z)
     * @return the drop of each block of the ring, 0 or more
     */
    public static int[] drops(WallLayout layout, IntBinaryOperator height) {
        int[] levels = new int[layout.ringSize()];
        for (int i = 0; i < levels.length; i++) {
            Cell cell = layout.ringCell(i);
            levels[i] = height.applyAsInt(cell.x(), cell.z());
        }
        return drops(layout, levels, height);
    }

    /**
     * The drop at each block of the ring of a wall, from a level at each block: for example the base of the column of
     * the palisade (see {@link PalisadeHeights}). The base is not lower than the terrain of the line.
     *
     * @param layout the wall
     * @param levels the level of each block of the ring
     * @param height the Y of the top block of the terrain at a position (X, Z)
     * @return the drop of each block of the ring, 0 or more
     */
    public static int[] drops(WallLayout layout, int[] levels, IntBinaryOperator height) {
        if (levels.length != layout.ringSize()) {
            throw new IllegalArgumentException("The levels need the size of the ring: " + levels.length + " and "
                    + layout.ringSize());
        }
        int[] drops = new int[levels.length];
        for (int i = 0; i < drops.length; i++) {
            Cell cell = layout.ringCell(i);
            Side side = layout.ringSide(i);
            int lowest = levels[i];
            for (int distance = 1; distance <= DISTANCE; distance++) {
                lowest = Math.min(lowest, height.applyAsInt(cell.x() - side.insideX() * distance,
                        cell.z() - side.insideZ() * distance));
            }
            drops[i] = levels[i] - lowest;
        }
        return drops;
    }

    /**
     * True for a cliff block.
     *
     * @param drop the drop of the block
     * @return true if the drop is {@link #DROP} blocks or more
     */
    public static boolean isCliff(int drop) {
        return drop >= DROP;
    }

    /**
     * The cliff blocks of a ring.
     *
     * @param drops the drop of each block of the ring
     * @return for each block of the ring, true for a cliff block
     */
    public static boolean[] cliffs(int[] drops) {
        boolean[] cliffs = new boolean[drops.length];
        for (int i = 0; i < drops.length; i++) {
            cliffs[i] = isCliff(drops[i]);
        }
        return cliffs;
    }

    /**
     * The number of logs of a column of the palisade.
     *
     * @param cliff true for a cliff block
     * @return {@link PalisadeHeights#LOW_LOGS} on a cliff, else {@link PalisadeHeights#LOGS}
     */
    public static int logs(boolean cliff) {
        return cliff ? PalisadeHeights.LOW_LOGS : PalisadeHeights.LOGS;
    }

    /** The drop of a gate at a position of a side: the level of the gate minus the lowest terrain in front of it. */
    @FunctionalInterface
    public interface GateDrop {
        /**
         * The drop of a gate.
         *
         * @param side  the side of the gate
         * @param along the distance from the start of the side to the middle of the gate
         * @return the drop, in blocks
         */
        int drop(Side side, int along);
    }

    /**
     * The drop of a gate on the highest level of its blocks: a gate stands on the highest base under it (see
     * {@link PalisadeHeights}).
     *
     * @param layout the wall
     * @param levels the level of each block of the ring
     * @param height the Y of the top block of the terrain at a position (X, Z)
     * @return the drop of a gate at each position
     */
    public static GateDrop gateDrop(WallLayout layout, int[] levels, IntBinaryOperator height) {
        return (side, along) -> {
            int level = Integer.MIN_VALUE;
            for (int position = along - WallLayout.GATE / 2; position <= along + WallLayout.GATE / 2; position++) {
                level = Math.max(level, levels[layout.ringIndex(side, position)]);
            }
            return level - lowestInFront(layout, side, along, height);
        };
    }

    /**
     * The lowest terrain in front of a gate: at 1 to {@link #DISTANCE} blocks outside the line, in front of each block
     * of the gate, its posts included.
     *
     * @param layout the wall
     * @param side   the side of the gate
     * @param along  the distance from the start of the side to the middle of the gate
     * @param height the Y of the top block of the terrain at a position (X, Z)
     * @return the Y of the lowest top block
     */
    public static int lowestInFront(WallLayout layout, Side side, int along, IntBinaryOperator height) {
        int lowest = Integer.MAX_VALUE;
        for (int position = along - WallLayout.GATE / 2; position <= along + WallLayout.GATE / 2; position++) {
            Cell cell = layout.cell(side, position);
            for (int distance = 1; distance <= DISTANCE; distance++) {
                lowest = Math.min(lowest, height.applyAsInt(cell.x() - side.insideX() * distance,
                        cell.z() - side.insideZ() * distance));
            }
        }
        return lowest;
    }

    /**
     * The gates of the first wall of a village with the rules of the cliff side: a gate on a cliff moves along its side
     * or goes away.
     *
     * @param layout the wall
     * @param drop   the drop of a gate at each position
     * @param keep   for each block of the ring, true if the block keeps the palisade (see {@link SeaWall}). A gate
     *               with no block that keeps the palisade does not count, and it stays in the list
     * @return the gates, in the order of the gates of the wall
     */
    public static List<Gate> gates(WallLayout layout, GateDrop drop, boolean[] keep) {
        return choose(layout, drop, keep, MAX_SHIFT, true);
    }

    /**
     * The gates of a ring with the rules of the cliff side: a gate on a cliff goes away, and does not move.
     *
     * @param layout the ring
     * @param drop   the drop of a gate at each position
     * @param keep   for each block of the ring, true if the block keeps the palisade
     * @return the gates, in the order of the gates of the ring
     */
    public static List<Gate> ringGates(WallLayout layout, GateDrop drop, boolean[] keep) {
        return choose(layout, drop, keep, 0, false);
    }

    /** The gates with the rules of the cliff side, with a maximum shift and with or without a move on the full side. */
    private static List<Gate> choose(WallLayout layout, GateDrop drop, boolean[] keep, int maxShift, boolean free) {
        List<Gate> gates = layout.gates();
        Gate[] chosen = new Gate[gates.size()];
        List<Integer> onCliff = new ArrayList<>();
        boolean open = false;
        for (int g = 0; g < gates.size(); g++) {
            Gate gate = gates.get(g);
            int along = layout.along(gate);
            if (!kept(layout, gate.side(), along, keep)) {
                chosen[g] = gate;
                continue;
            }
            int shift = nearest(layout, gate.side(), along, drop, keep, maxShift);
            if (shift == Integer.MIN_VALUE) {
                onCliff.add(g);
            } else {
                chosen[g] = at(layout, gate.side(), along + shift);
                open = true;
            }
        }
        if (!open && !onCliff.isEmpty()) {
            int stay = last(layout, gates, onCliff, drop, keep, free);
            chosen[stay] = lastGate(layout, gates.get(stay), drop, keep, free);
        }
        List<Gate> result = new ArrayList<>();
        for (Gate gate : chosen) {
            if (gate != null) {
                result.add(gate);
            }
        }
        return List.copyOf(result);
    }

    /**
     * The index of the gate that stays when each gate is on a cliff: the gate with the nearest free position on its
     * side, else the gate with the smallest drop. The first gate in the order of the wall wins a tie.
     */
    private static int last(WallLayout layout, List<Gate> gates, List<Integer> onCliff, GateDrop drop,
            boolean[] keep, boolean free) {
        int best = onCliff.get(0);
        long bestCost = Long.MAX_VALUE;
        for (int g : onCliff) {
            Gate gate = gates.get(g);
            int along = layout.along(gate);
            int shift = free ? nearest(layout, gate.side(), along, drop, keep, WallLayout.length(layout.outline(),
                    gate.side())) : Integer.MIN_VALUE;
            // A free position comes before any gate on a cliff: its cost is under the drops.
            long cost = shift != Integer.MIN_VALUE ? Math.abs(shift)
                    : (long) Integer.MAX_VALUE + drop.drop(gate.side(), along);
            if (cost < bestCost) {
                bestCost = cost;
                best = g;
            }
        }
        return best;
    }

    /** The last gate: at the nearest free position of its side, else at its position on the cliff. */
    private static Gate lastGate(WallLayout layout, Gate gate, GateDrop drop, boolean[] keep, boolean free) {
        int along = layout.along(gate);
        int shift = free ? nearest(layout, gate.side(), along, drop, keep, WallLayout.length(layout.outline(),
                gate.side())) : Integer.MIN_VALUE;
        return shift == Integer.MIN_VALUE ? gate : at(layout, gate.side(), along + shift);
    }

    /**
     * The shift from a position of a gate to the nearest free position on its side: a drop under {@link #DROP} and a
     * block that keeps the palisade. A positive shift comes first. {@link Integer#MIN_VALUE} if no free position is in
     * the maximum shift.
     */
    private static int nearest(WallLayout layout, Side side, int along, GateDrop drop, boolean[] keep,
            int maxShift) {
        int first = WallLayout.firstGate();
        int last = WallLayout.lastGate(WallLayout.length(layout.outline(), side));
        for (int shift = 0; shift <= maxShift; shift++) {
            for (int sign : shift == 0 ? new int[] {1} : new int[] {1, -1}) {
                int position = along + sign * shift;
                if (position >= first && position <= last && kept(layout, side, position, keep)
                        && !isCliff(drop.drop(side, position))) {
                    return sign * shift;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /** True if a block of a gate at a position keeps the palisade. */
    private static boolean kept(WallLayout layout, Side side, int along, boolean[] keep) {
        for (int position = along - WallLayout.GATE / 2; position <= along + WallLayout.GATE / 2; position++) {
            if (keep[layout.ringIndex(side, position)]) {
                return true;
            }
        }
        return false;
    }

    /** The gate at a distance from the start of a side. */
    private static Gate at(WallLayout layout, Side side, int along) {
        return new Gate(side, WallLayout.position(layout.outline(), side, along));
    }
}
