package io.github.nicolasgoyon.folkhold.core.sim.village;

import java.util.OptionalInt;

/**
 * The version of the rules that make the plan of a faction village: its streets, its plots, its buildings and its
 * walls.
 *
 * <ul>
 *   <li>Each world keeps one version from its creation. All the villages of the world use the rules of this version,
 *       for their core and for their ring districts. Thus a ring district that generates later than its core uses the
 *       rules of its core.</li>
 *   <li>A world with no saved version has the version {@link #FIRST}. A new world gets {@link #LATEST}.</li>
 *   <li>A rule of the plan applies only from its version: the rule reads {@link #atLeast}.</li>
 *   <li>The flags of the heart search and of the rings are separate. They keep their own rules.</li>
 * </ul>
 *
 * <p>The versions:
 *
 * <ol>
 *   <li>The rules of a world with no saved version.</li>
 *   <li>{@link #STREET_RAMP}: each street of the core has a ramp profile, with a cut and a fill on each side.</li>
 *   <li>{@link #HOUSE_LEVEL}: each house on a street of the core stands at the level of the profile at its plot.</li>
 *   <li>{@link #ARM_WIDTH}: the path from a gate to the plaza stops at the first row with a building in its width,
 *       not only on its middle. A gate off the axis of its street gives a path off this axis.</li>
 *   <li>{@link #STREET_FILL}: each street of a ring band has a ramp profile, as a street of the core. Each path of a
 *       street or of a gate also fills the open holes on its 2 sides.</li>
 * </ol>
 */
public final class PlanVersion {
    /** The version of a world with no saved version. */
    public static final int FIRST = 1;
    /** The version of the ramp profile of the streets (see {@link StreetRamp}). */
    public static final int STREET_RAMP = 2;
    /** The version of the houses at the level of their street (see {@link StreetRamp}). */
    public static final int HOUSE_LEVEL = 3;
    /** The version of the paths of the gates that stop at a building in their width. */
    public static final int ARM_WIDTH = 4;
    /** The version of the ramp profile of the streets of the bands and of the fill on the sides of the paths. */
    public static final int STREET_FILL = 5;
    /** The version of a new world: the version of the last rule. */
    public static final int LATEST = STREET_FILL;

    private PlanVersion() {
    }

    /**
     * The version of a world.
     *
     * @param saved   the saved version of the world, or empty if the world has no saved version
     * @param created true if the world is new: it has no chunk and no saved data yet
     * @return the saved version in the range {@link #FIRST} to {@link #LATEST}, else {@link #LATEST} for a new world
     *         and {@link #FIRST} for a world with no saved version
     */
    public static int ofWorld(OptionalInt saved, boolean created) {
        if (saved.isPresent()) {
            return Math.max(FIRST, Math.min(LATEST, saved.getAsInt()));
        }
        return created ? LATEST : FIRST;
    }

    /**
     * True if a version has the rules of a version or of a later version.
     *
     * @param version the version of the world
     * @param rule    the first version of the rule
     * @return true if the rule applies
     */
    public static boolean atLeast(int version, int rule) {
        return version >= rule;
    }
}
