package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raider;

/**
 * The monsters that start the alert of a village. The units of a village fight the monsters in their chase area, for
 * example the monsters of the night near the wall. A monster far from the villagers does not start the alert: the
 * bells do not ring and the villagers stay in their beds.
 *
 * <ul>
 *   <li>A monster is an {@link Enemy} that is not a {@link Raider}.</li>
 *   <li>A monster starts the alert only when a villager is at {@link WorldConfig#MONSTER_ALERT_DISTANCE} blocks or
 *       nearer. With the value 0, each monster starts the alert.</li>
 *   <li>A raider, a player, a unit and the other entities always start the alert.</li>
 * </ul>
 */
public final class MonsterThreats {
    private MonsterThreats() {
    }

    /**
     * True if an entity that a unit fights, or that attacks a unit, starts the alert of the village.
     *
     * @param entity the entity
     * @return false for a monster far from the villagers
     */
    public static boolean startsAlert(LivingEntity entity) {
        if (!isMonster(entity)) {
            return true;
        }
        int distance = WorldConfig.MONSTER_ALERT_DISTANCE.get();
        if (distance <= 0) {
            return true;
        }
        return !entity.level().getEntitiesOfClass(Villager.class, entity.getBoundingBox().inflate(distance),
                villager -> villager.isAlive() && villager.distanceToSqr(entity) <= (double) distance * distance)
                .isEmpty();
    }

    /**
     * True for a monster: an {@link Enemy} that is not a {@link Raider}.
     *
     * @param entity the entity
     * @return true for a monster
     */
    public static boolean isMonster(LivingEntity entity) {
        return entity instanceof Enemy && !(entity instanceof Raider);
    }
}
