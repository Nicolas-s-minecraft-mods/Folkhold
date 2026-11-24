package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;

/**
 * The pillager faction.
 *
 * <ul>
 *   <li>The faction is fixed: the faction generator does not make it, and it is not in the generated factions
 *       ({@link io.github.nicolasgoyon.folkhold.world.faction.FactionData#factions()}). Thus no settlement belongs to
 *       it, and it has no quest board, no quests, no traders and no caravans.</li>
 *   <li>Its territory is the land around the pillager camps (see {@link PillagerTerritories}).</li>
 *   <li>The reputation of a player with it follows {@link PillagerReputation}: it never goes up, and it does not
 *       spread to other factions. The value is in {@link PillagerData}, not in the reputation data of the other
 *       factions.</li>
 * </ul>
 */
public final class PillagerFaction {
    /** The id of the faction. A generated faction has an id {@code faction_<n>}, so it cannot be this value. */
    public static final String ID = "pillagers";
    /** The color of the faction (RGB): the grey of the pillager clothes. */
    public static final int COLOR = 0x6E6E5A;
    /** The translation key of the name of the faction. */
    public static final String NAME_KEY = "folkhold_world.faction.pillagers";

    private PillagerFaction() {
    }

    /** The name of the faction in its color: "Pillagers". */
    public static MutableComponent name() {
        return Component.translatable(NAME_KEY).withStyle(style -> style.withColor(TextColor.fromRgb(COLOR)));
    }

    /** The reputation of a player with the pillagers. */
    public static int reputation(MinecraftServer server, UUID player) {
        return PillagerData.get(server).reputation(player);
    }

    /** The tier of a player with the pillagers: always {@link ReputationTier#HOSTILE} or worse. */
    public static ReputationTier tier(MinecraftServer server, UUID player) {
        return ReputationTier.of(reputation(server, player));
    }

    /**
     * Applies a change to the reputation of a player with the pillagers. A positive change has no effect.
     *
     * @param server the server
     * @param player the id of the player
     * @param delta  the change
     */
    public static void apply(MinecraftServer server, UUID player, int delta) {
        PillagerData data = PillagerData.get(server);
        int value = data.reputation(player);
        int next = PillagerReputation.apply(value, delta);
        if (next != value) {
            data.setReputation(player, next);
        }
    }
}
