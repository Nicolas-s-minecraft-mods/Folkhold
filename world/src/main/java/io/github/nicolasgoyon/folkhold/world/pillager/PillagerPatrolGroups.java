package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupSimulation;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MemberRole;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PatrolRoutes;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamps;
import io.github.nicolasgoyon.folkhold.core.sim.tick.TickScheduler.TickInfo;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * The abstract patrols of the pillager camps: a {@link io.github.nicolasgoyon.folkhold.world.settlement.WorldSystem}
 * that runs on each abstract tick.
 *
 * <ul>
 *   <li>Each camp has {@code pillagers.patrolsPerCamp} patrols (mobile groups of the kind
 *       {@link GroupKind#PILLAGER_PATROL}). With {@code pillagers.patrolSize} at 0, the camps have no patrols.</li>
 *   <li>A patrol has {@code pillagers.patrolSize} plus {@code pillagers.swornEnemyExtra} members. All members have
 *       the role {@link MemberRole#GUARD}: the loss of one member does not stop the patrol.</li>
 *   <li>The route of a patrol is the loop of its camp (see {@link PatrolRoutes}), on the chunks of the territory of
 *       the camp that no settlement claims. The patrols of a camp start at equal distances on the loop.</li>
 *   <li>A patrol moves {@code groups.blocksPerTick} blocks on each abstract tick, also with no player near. At the
 *       end of the loop, the patrol gets the loop again from the claims of this time, and starts it again. A patrol
 *       never arrives.</li>
 *   <li>When its camp goes away, or when the loop of its camp has no good chunk, the patrol goes away.</li>
 *   <li>The patrols are saved with the camps (see {@link PillagerData}). They are not in the registry of the
 *       caravans: they do not count in {@code groups.maxGroups}, and the code of the caravans and of the escorts
 *       does not see them.</li>
 * </ul>
 */
public final class PillagerPatrolGroups {
    /** The health of a new member: the maximum health of a vanilla pillager. */
    static final float MEMBER_HEALTH = 24.0F;
    /** The text between the id of the camp and the index of the patrol, in the id of a patrol. */
    private static final String ID_SEPARATOR = "/patrol_";

    private PillagerPatrolGroups() {
    }

    /** Runs one abstract tick of the patrols. */
    public static void tick(MinecraftServer server, TickInfo tick) {
        update(server, tick.number());
    }

    /**
     * Gives each camp its patrols, then moves the patrols on their loops.
     *
     * @param server     the server
     * @param tickNumber the number of the abstract tick
     */
    public static void update(MinecraftServer server, long tickNumber) {
        PillagerData data = PillagerData.get(server);
        for (MobileGroup patrol : List.copyOf(data.patrols().all())) {
            if (data.camp(patrol.originId()).isEmpty()) {
                remove(server, patrol);
            }
        }
        for (PillagerCamp camp : data.camps()) {
            ensure(server, camp);
        }
        if (data.patrols().size() == 0) {
            return;
        }
        GroupSimulation.Report report = GroupSimulation.tick(data.patrols(), tickNumber,
                WorldConfig.GROUP_BLOCKS_PER_TICK.get(), patrol -> false, new GroupSimulation.Listener() {
                    @Override
                    public boolean onEndOfRoute(MobileGroup patrol, long number) {
                        restartLoop(server, patrol);
                        return false;
                    }

                    @Override
                    public void onArrival(MobileGroup patrol) {
                        FolkholdWorld.LOGGER.warn("A pillager patrol must not arrive: {}", patrol);
                    }

                    @Override
                    public void onLost(MobileGroup patrol) {
                        FolkholdWorld.LOGGER.debug("Pillager patrol lost: {}", patrol);
                    }
                });
        if (report.moved() > 0 || report.lost() > 0) {
            data.setDirty();
        }
    }

    /**
     * Gives a camp the number of patrols of the server config. The new patrols travel at once.
     *
     * @param server the server
     * @param camp   the camp
     * @return the patrols of the camp, in id order
     */
    public static List<MobileGroup> ensure(MinecraftServer server, PillagerCamp camp) {
        PillagerData data = PillagerData.get(server);
        int wanted = wantedPatrols();
        List<MobileGroup> patrols = data.patrolsOf(camp.id());
        boolean[] present = new boolean[wanted];
        for (MobileGroup patrol : patrols) {
            int index = index(patrol);
            if (index < 0 || index >= wanted) {
                remove(server, patrol);
            } else {
                present[index] = true;
            }
        }
        Optional<Route> route = Optional.empty();
        for (int index = 0; index < wanted; index++) {
            if (present[index]) {
                continue;
            }
            if (route.isEmpty()) {
                route = loop(server, camp);
                if (route.isEmpty()) {
                    break;
                }
            }
            MobileGroup patrol = create(camp, index, route.get());
            patrol.setProgress(PatrolRoutes.startProgress(route.get(), index, wanted));
            data.patrols().add(patrol);
            data.setDirty();
        }
        return data.patrolsOf(camp.id());
    }

    /**
     * Makes a patrol of a camp, at the start of a route, in the state {@link GroupState#TRAVELING}.
     *
     * @param camp  the camp
     * @param index the index of the patrol in its camp, from 0
     * @param route the loop of the camp
     * @return the patrol
     */
    static MobileGroup create(PillagerCamp camp, int index, Route route) {
        MobileGroup patrol = new MobileGroup(camp.id() + ID_SEPARATOR + index, GroupKind.PILLAGER_PATROL,
                PillagerFaction.ID, camp.id(), camp.id(), route);
        int size = WorldConfig.PILLAGER_PATROL_SIZE.get() + WorldConfig.PILLAGER_SWORN_ENEMY_EXTRA.get();
        for (int i = 0; i < size; i++) {
            patrol.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, MEMBER_HEALTH));
        }
        patrol.setState(GroupState.TRAVELING);
        return patrol;
    }

    /** The number of patrols of each camp, from the server config. */
    static int wantedPatrols() {
        return WorldConfig.PILLAGER_PATROL_SIZE.get() > 0 ? WorldConfig.PILLAGER_PATROLS_PER_CAMP.get() : 0;
    }

    /**
     * The loop of a camp, on the chunks of its territory that no settlement claims.
     *
     * @param server the server
     * @param camp   the camp
     * @return the loop, or empty if the territory has too few good chunks
     */
    public static Optional<Route> loop(MinecraftServer server, PillagerCamp camp) {
        SettlementData settlements = SettlementData.get(server);
        Collection<PillagerCamp> camps = PillagerData.get(server).camps();
        return PatrolRoutes.loop(camp, (chunkX, chunkZ) ->
                settlements.claims().ownerAt(chunkX * 16 + 8, chunkZ * 16 + 8).isEmpty()
                        && PillagerCamps.ownerOfChunk(camps, chunkX, chunkZ)
                                .filter(owner -> owner.id().equals(camp.id())).isPresent());
    }

    /**
     * The index of a patrol in its camp, from its id.
     *
     * @param patrol the patrol
     * @return the index, or -1 for an id with no index
     */
    static int index(MobileGroup patrol) {
        String prefix = patrol.originId() + ID_SEPARATOR;
        if (!patrol.id().startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(patrol.id().substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A patrol at the end of its loop gets the loop of this time and starts it again. */
    private static void restartLoop(MinecraftServer server, MobileGroup patrol) {
        Optional<PillagerCamp> camp = PillagerData.get(server).camp(patrol.originId());
        Optional<Route> route = camp.flatMap(found -> loop(server, found));
        if (route.isEmpty()) {
            remove(server, patrol);
            return;
        }
        patrol.setRoute(route.get());
    }

    /**
     * Removes a patrol.
     *
     * @param server the server
     * @param patrol the patrol
     */
    static void remove(MinecraftServer server, MobileGroup patrol) {
        PillagerData data = PillagerData.get(server);
        if (data.patrols().remove(patrol.id())) {
            data.setDirty();
            FolkholdWorld.LOGGER.debug("Pillager patrol removed: {}", patrol);
        }
    }

    /**
     * The patrols of all camps, in id order.
     *
     * @param server the server
     * @return the patrols
     */
    public static List<MobileGroup> all(MinecraftServer server) {
        List<MobileGroup> patrols = new ArrayList<>(PillagerData.get(server).patrols().all());
        patrols.sort((first, second) -> first.id().compareTo(second.id()));
        return patrols;
    }
}
