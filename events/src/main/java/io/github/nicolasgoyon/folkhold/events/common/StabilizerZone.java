package io.github.nicolasgoyon.folkhold.events.common;

import java.util.List;

/**
 * The positions that the stabilizers protect in the area of one event. A stabilizer protects each block at a distance
 * of {@code radius} blocks or nearer: a sphere around the stabilizer.
 *
 * <p>Pure Java: {@link Stabilizers} finds the stabilizers in the level.
 */
public final class StabilizerZone {
    /** A zone with no stabilizer: it protects no position. */
    public static final StabilizerZone EMPTY = new StabilizerZone(List.of(), 0);

    /**
     * The position of a stabilizer.
     *
     * @param x the X
     * @param y the Y
     * @param z the Z
     */
    public record Point(int x, int y, int z) {
    }

    private final List<Point> stabilizers;
    private final long radiusSquared;

    /**
     * Makes a zone.
     *
     * @param stabilizers the positions of the stabilizers
     * @param radius      the radius of the protection, in blocks. A negative radius protects no position.
     */
    public StabilizerZone(List<Point> stabilizers, int radius) {
        this.stabilizers = radius < 0 ? List.of() : List.copyOf(stabilizers);
        radiusSquared = (long) radius * radius;
    }

    /** The number of stabilizers of the zone. */
    public int size() {
        return stabilizers.size();
    }

    /**
     * True if a stabilizer protects a position.
     *
     * @param x the X of the position
     * @param y the Y of the position
     * @param z the Z of the position
     * @return true if the position is at {@code radius} blocks of a stabilizer or nearer
     */
    public boolean covers(int x, int y, int z) {
        for (Point point : stabilizers) {
            long dx = (long) x - point.x();
            long dy = (long) y - point.y();
            long dz = (long) z - point.z();
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                return true;
            }
        }
        return false;
    }
}
