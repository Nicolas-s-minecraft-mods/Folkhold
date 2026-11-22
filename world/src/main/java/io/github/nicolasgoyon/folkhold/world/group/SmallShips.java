package io.github.nicolasgoyon.folkhold.world.group;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.neoforged.fml.ModList;

/**
 * The ships of the optional mod Small Ships for the crossings of the groups (see {@link GroupBoats}). Small Ships is
 * not a dependency: the mod finds the ship types by their ids in the registry of the entity types. A ship of Small
 * Ships is a vanilla {@link Boat}, so the crossing steers it as a boat.
 *
 * <ul>
 *   <li>A group crosses in the smallest ship that has a seat for each member: the cog (5 seats), the galley (9) or
 *       the brigg (11).</li>
 *   <li>A group with more members than the seats of the brigg takes the brigg, and the other members take vanilla
 *       boats.</li>
 *   <li>With no Small Ships, or with no ship type in the registry, the group crosses in vanilla boats.</li>
 * </ul>
 */
public final class SmallShips {
    /** The mod id of Small Ships. */
    public static final String MOD_ID = "smallships";

    /**
     * A ship type of Small Ships.
     *
     * @param id    the path of the id of the entity type
     * @param seats the number of passengers
     */
    public record ShipType(String id, int seats) {
    }

    /** The ship types, from the smallest. */
    public static final List<ShipType> TYPES = List.of(new ShipType("cog", 5), new ShipType("galley", 9),
            new ShipType("brigg", 11));

    private SmallShips() {
    }

    /**
     * The ship type for a number of passengers: the smallest type with enough seats, else the largest type.
     *
     * @param passengers the number of members that cross
     * @return the ship type
     */
    public static ShipType typeFor(int passengers) {
        for (ShipType type : TYPES) {
            if (type.seats() >= passengers) {
                return type;
            }
        }
        return TYPES.get(TYPES.size() - 1);
    }

    /** True if the game has Small Ships. */
    public static boolean isLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    /**
     * Makes a ship of Small Ships for a number of passengers, at a position. The ship does not join the level.
     *
     * @param level      the level
     * @param passengers the number of members that cross
     * @param x          the X of the ship
     * @param y          the Y of the ship
     * @param z          the Z of the ship
     * @return the ship, or empty with no Small Ships or no ship type
     */
    public static Optional<Boat> create(ServerLevel level, int passengers, double x, double y, double z) {
        if (!isLoaded()) {
            return Optional.empty();
        }
        ShipType type = typeFor(passengers);
        Optional<EntityType<?>> entityType = BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath(MOD_ID, type.id()));
        if (entityType.isEmpty()) {
            return Optional.empty();
        }
        Entity entity = entityType.get().create(level);
        if (!(entity instanceof Boat ship)) {
            if (entity != null) {
                entity.discard();
            }
            return Optional.empty();
        }
        ship.moveTo(x, y, z, 0.0F, 0.0F);
        return Optional.of(ship);
    }
}
