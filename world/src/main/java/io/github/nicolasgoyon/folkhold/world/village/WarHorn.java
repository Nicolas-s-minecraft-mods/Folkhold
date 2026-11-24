package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.core.sim.faction.FactionSpec;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import io.github.nicolasgoyon.folkhold.world.faction.FactionVisuals;
import io.github.nicolasgoyon.folkhold.world.registry.ModSounds;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * The war horn of the villages of some cultures (section {@code warHorn} of the server config, default Valeholm).
 * When the village goes into alert, the horn sounds at the heart, and each loaded unit of the village gets the effect
 * Resistance. While the alert continues, the horn sounds again after an interval. The sound is a vanilla goat horn
 * sound ({@link ModSounds#WAR_HORN}), with the subtitle "War horn
 * sounds". The players hear it up to {@link #RANGE} blocks from the heart.
 */
public final class WarHorn {
    /** The players hear the horn at this distance from the heart or less, in blocks. */
    public static final int RANGE = 64;
    /** The volume of the sound: a volume of 1 has a range of 16 blocks. */
    private static final float VOLUME = RANGE / 16.0F;

    private WarHorn() {
    }

    /**
     * True if the villages of a culture have a war horn.
     *
     * @param culture the id of the culture, or null for a village with no faction
     * @return true for a culture of the config, with a horn level above 0
     */
    public static boolean hasHorn(@Nullable String culture) {
        return culture != null && WorldConfig.WAR_HORN_LEVEL.get() > 0
                && WorldConfig.WAR_HORN_CULTURES.get().contains(culture);
    }

    /**
     * True if the village of a heart has a war horn: the culture of its faction has one.
     *
     * @param level the level of the heart
     * @param heart the heart
     * @return true if the village has a war horn
     */
    public static boolean hasHorn(ServerLevel level, VillageHeartBlockEntity heart) {
        return hasHorn(FactionVisuals.faction(level.getServer(), heart.getFactionId())
                .map(FactionSpec::cultureId).orElse(null));
    }

    /**
     * The time between two sounds of the horn during an alert, in ticks. 0: the horn sounds only when the village goes
     * into alert.
     */
    public static long intervalTicks() {
        return WorldConfig.WAR_HORN_INTERVAL_SECONDS.get() * 20L;
    }

    /**
     * Sounds the horn of a village: the sound at the heart, and the effect Resistance on each loaded unit of the
     * village. The caller checks that the village has a horn (see {@link #hasHorn(ServerLevel,
     * VillageHeartBlockEntity)}).
     *
     * @param level the level of the heart
     * @param heart the heart
     */
    public static void sound(ServerLevel level, VillageHeartBlockEntity heart) {
        BlockPos pos = heart.getBlockPos();
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                ModSounds.WAR_HORN.get(), SoundSource.NEUTRAL, VOLUME, 1.0F);
        int duration = WorldConfig.WAR_HORN_SECONDS.get() * 20;
        int amplifier = WorldConfig.WAR_HORN_LEVEL.get() - 1;
        for (FactionUnit unit : heart.getUnits()) {
            unit.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, duration, amplifier));
        }
    }
}
