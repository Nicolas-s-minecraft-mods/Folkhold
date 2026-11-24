package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MemberRole;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PatrolFight;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.group.GroupMaterializer;
import io.github.nicolasgoyon.folkhold.world.group.MobileGroupData;
import io.github.nicolasgoyon.folkhold.world.quest.AmbushSpawns;
import io.github.nicolasgoyon.folkhold.world.quest.Escorts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The encounters between the caravans and the abstract patrols of the pillager camps (see
 * {@link PillagerPatrolGroups}).
 *
 * <ul>
 *   <li>A caravan meets a patrol when it travels in the territory of a camp, and the camp has a patrol that travels,
 *       has no entities and has a living member. The patrol of the camp that is the nearest to the caravan comes.</li>
 *   <li>A caravan meets the patrols of a camp one time in {@code pillagers.encounterCooldownMinutes} of game time.</li>
 *   <li>A caravan with no entities: on each abstract tick, the server checks the part of the route that the caravan
 *       travelled since the last check, at each {@link #SAMPLE_STEP} blocks. An encounter is an abstract fight (see
 *       {@link PatrolFight}): the guards of the caravan against the members of the patrol, with their health. The dead
 *       guards and members get a health of 0. A beaten patrol goes back to its camp with new members after
 *       {@code pillagers.patrolRespawnMinutes} (see {@link PillagerPatrolGroups#regroup}). A caravan that loses keeps
 *       the share {@code 1 - pillagers.encounterCargoLoss} of each item of its cargo and of its payment, and
 *       continues. A caravan with no living guard is lost.</li>
 *   <li>A caravan with entities: each second, the server checks the position of its merchant. The patrol gets all its
 *       living members as entities near the point {@link #SPAWN_AHEAD} blocks ahead on the route of the caravan, at a
 *       position with a walk path to the merchant (see {@link AmbushSpawns}). They attack the merchant (see
 *       {@link PatrolEntities}). The result is the result of the real fight. With no such position, the patrol does
 *       not come, and the server tries again after {@link #RETRY_TICKS} game ticks.</li>
 *   <li>An escort ambush at a point in a territory (see {@link io.github.nicolasgoyon.folkhold.world.quest.Escorts}):
 *       the patrol of the camp replaces the ambush. When the caravan met the patrols of the camp in the cooldown, the
 *       point has no ambush. When the camp has no free patrol, the ambush of the escort occurs.</li>
 * </ul>
 *
 * <p>The progress of the last check and the encounters of each caravan are in memory. After a restart, the check of a
 * caravan starts at its progress of this time, and the cooldowns start again.
 */
public final class PatrolEncounters {
    /** Distance in blocks between two checked points of the route of a caravan with no entities. */
    static final double SAMPLE_STEP = 16.0;
    /** Distance in blocks ahead of the merchant on the route where the entities of a patrol appear. */
    static final double SPAWN_AHEAD = 24.0;

    /** After a search with no spawn position, the next search of a caravan waits this number of game ticks. */
    static final long RETRY_TICKS = 100L;

    /** The game time of the next search of a spawn position, by caravan id, after a search with none. Not saved. */
    private static final Map<String, Long> NO_SPAWN_UNTIL = new HashMap<>();
    /** The progress of each caravan at its last abstract check, by caravan id. Not saved. */
    private static final Map<String, Double> CHECKED = new HashMap<>();
    /** The game time of the last encounter of each caravan with each camp, by caravan id and camp id. Not saved. */
    private static final Map<String, Map<String, Long>> MET = new HashMap<>();

    private PatrolEncounters() {
    }

    /**
     * Checks the caravans with no entities on an abstract tick, after the patrols move.
     *
     * @param server     the server
     * @param tickNumber the number of the abstract tick
     * @return the number of abstract fights
     */
    public static int checkAbstract(MinecraftServer server, long tickNumber) {
        MobileGroupData groups = MobileGroupData.get(server);
        CHECKED.keySet().removeIf(id -> groups.groups().byId(id).isEmpty());
        MET.keySet().removeIf(id -> groups.groups().byId(id).isEmpty());
        NO_SPAWN_UNTIL.keySet().removeIf(id -> groups.groups().byId(id).isEmpty());
        if (!WorldConfig.PILLAGER_CARAVAN_ENCOUNTERS.get() || PillagerData.get(server).camps().isEmpty()) {
            CHECKED.clear();
            return 0;
        }
        long now = server.overworld().getGameTime();
        int fights = 0;
        for (MobileGroup caravan : List.copyOf(groups.groups().all())) {
            if (caravan.kind() != GroupKind.CARAVAN || caravan.state() != GroupState.TRAVELING
                    || GroupMaterializer.isMaterialized(caravan)) {
                CHECKED.remove(caravan.id());
                continue;
            }
            double to = caravan.progress();
            double from = Math.min(to, CHECKED.getOrDefault(caravan.id(), to));
            CHECKED.put(caravan.id(), to);
            if (fightOnSegment(server, caravan, from, to, now, tickNumber)) {
                fights++;
                groups.setDirty();
            }
        }
        return fights;
    }

    /** Checks the points of a part of the route of a caravan. At most one fight occurs. */
    private static boolean fightOnSegment(MinecraftServer server, MobileGroup caravan, double from, double to,
            long now, long tickNumber) {
        Route route = caravan.route();
        double progress = from;
        while (true) {
            Route.Position position = route.positionAt(Math.min(progress, to));
            BlockPos pos = BlockPos.containing(position.x(), 0, position.z());
            Optional<PillagerCamp> camp = PillagerTerritories.campAt(server, pos);
            if (camp.isPresent() && !hasMet(caravan, camp.get(), now)) {
                Optional<MobileGroup> patrol = PillagerPatrolGroups.nearestFree(server, camp.get(), position.x(),
                        position.z());
                if (patrol.isPresent()) {
                    fight(server, caravan, patrol.get(), tickNumber);
                    recordMeeting(caravan, camp.get(), now);
                    return true;
                }
            }
            if (progress >= to) {
                return false;
            }
            progress += SAMPLE_STEP;
        }
    }

    /**
     * Applies an abstract fight between a caravan and a patrol (see {@link PatrolFight}).
     *
     * @param server     the server
     * @param caravan    the caravan
     * @param patrol     the patrol
     * @param tickNumber the number of the abstract tick
     * @return the result of the fight
     */
    public static PatrolFight fight(MinecraftServer server, MobileGroup caravan, MobileGroup patrol,
            long tickNumber) {
        List<GroupMember> guards = living(caravan, MemberRole.GUARD);
        List<GroupMember> members = living(patrol, null);
        double guardDamage = WorldConfig.GUARD.attackDamage();
        double pillagerDamage = WorldConfig.PILLAGER_ENCOUNTER_DAMAGE.get();
        double caravanStrength = guards.stream()
                .mapToDouble(guard -> PatrolFight.strength(guard.health(), guardDamage)).sum();
        double patrolStrength = members.stream()
                .mapToDouble(member -> PatrolFight.strength(member.health(), pillagerDamage)).sum();
        PatrolFight result = PatrolFight.resolve(caravanStrength, guards.size(), patrolStrength, members.size(),
                WorldConfig.PILLAGER_ENCOUNTER_CARGO_LOSS.get());
        kill(guards, result.guardDeaths());
        kill(members, result.patrolDeaths());
        if (result.cargoLoss() > 0.0) {
            for (Map.Entry<String, Integer> entry : Map.copyOf(caravan.cargo().asMap()).entrySet()) {
                caravan.cargo().remove(entry.getKey(), (int) Math.floor(entry.getValue() * result.cargoLoss()));
            }
            caravan.setPayment(caravan.payment() - (long) Math.floor(caravan.payment() * result.cargoLoss()));
        }
        if (result.caravanLost()) {
            caravan.merchant().ifPresent(merchant -> caravan.onMemberDied(merchant.id()));
        }
        PillagerData.get(server).setDirty();
        MobileGroupData.get(server).setDirty();
        if (patrol.members().stream().noneMatch(GroupMember::isAlive)) {
            PillagerPatrolGroups.regroup(server, patrol, tickNumber);
        }
        FolkholdWorld.LOGGER.debug("Pillager patrol {} met caravan {}: {}", patrol.id(), caravan.id(), result);
        return result;
    }

    /**
     * Checks the caravans with entities, each second. A caravan in a territory gets the free patrol of the camp.
     *
     * @param level the Overworld
     */
    public static void checkMaterialized(ServerLevel level) {
        if (!WorldConfig.PILLAGER_CARAVAN_ENCOUNTERS.get()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (PillagerData.get(server).camps().isEmpty()) {
            return;
        }
        for (MobileGroup caravan : List.copyOf(MobileGroupData.get(server).groups().all())) {
            if (caravan.kind() != GroupKind.CARAVAN || caravan.isFinished()
                    || !GroupMaterializer.isMaterialized(caravan)) {
                continue;
            }
            merchant(level, caravan).ifPresent(merchant -> PillagerTerritories.campAt(server,
                    merchant.blockPosition()).ifPresent(camp -> {
                        if (!hasMet(caravan, camp, level.getGameTime())) {
                            attack(level, caravan, camp, merchant);
                        }
                    }));
        }
    }

    /**
     * An escort ambush is due: the patrol of the camp replaces it when the merchant is in a territory.
     *
     * @param level   the Overworld
     * @param caravan the escorted caravan, with entities
     * @return true if the ambush must not spawn: the caravan met the patrols of the camp in the cooldown, or the
     *         patrol of the camp attacks now. False out of a territory, and when the camp has no free patrol: the
     *         ambush spawns, and the caravan counts as met by the camp.
     */
    public static boolean replacesAmbush(ServerLevel level, MobileGroup caravan) {
        if (!WorldConfig.PILLAGER_CARAVAN_ENCOUNTERS.get()) {
            return false;
        }
        Optional<LivingEntity> merchant = merchant(level, caravan);
        if (merchant.isEmpty()) {
            return false;
        }
        Optional<PillagerCamp> camp = PillagerTerritories.campAt(level.getServer(), merchant.get().blockPosition());
        if (camp.isEmpty()) {
            return false;
        }
        long now = level.getGameTime();
        if (hasMet(caravan, camp.get(), now)) {
            return true;
        }
        if (attack(level, caravan, camp.get(), merchant.get())) {
            return true;
        }
        recordMeeting(caravan, camp.get(), now);
        return false;
    }

    /**
     * Sends the free patrol of a camp to a caravan with entities: the patrol gets its living members as entities
     * ahead of the merchant, and they attack the merchant.
     *
     * @return true if the patrol got entities
     */
    private static boolean attack(ServerLevel level, MobileGroup caravan, PillagerCamp camp, LivingEntity merchant) {
        MinecraftServer server = level.getServer();
        Optional<MobileGroup> patrol = PillagerPatrolGroups.nearestFree(server, camp, merchant.getX(),
                merchant.getZ());
        if (patrol.isEmpty()) {
            return false;
        }
        long now = level.getGameTime();
        if (NO_SPAWN_UNTIL.getOrDefault(caravan.id(), Long.MIN_VALUE) > now) {
            return false;
        }
        Route route = caravan.route();
        double point = Math.min(route.length(), route.progressNear(merchant.getX(), merchant.getZ()) + SPAWN_AHEAD);
        Optional<BlockPos> spawn = AmbushSpawns.find(level, route, point, merchant.blockPosition(), false);
        if (spawn.isEmpty()) {
            NO_SPAWN_UNTIL.put(caravan.id(), now + RETRY_TICKS);
            return false;
        }
        NO_SPAWN_UNTIL.remove(caravan.id());
        int size = (int) patrol.get().members().stream().filter(GroupMember::isAlive).count();
        List<PatrollingMonster> members = PatrolEntities.materialize(level, patrol.get(), spawn.get(),
                merchant.blockPosition(), size);
        if (members.isEmpty()) {
            return false;
        }
        for (PatrollingMonster member : members) {
            member.setTarget(merchant);
        }
        Escorts.track(level, caravan, members);
        recordMeeting(caravan, camp, level.getGameTime());
        FolkholdWorld.LOGGER.debug("Pillager patrol {} attacks caravan {}", patrol.get().id(), caravan.id());
        return true;
    }

    /**
     * True if a caravan met the patrols of a camp in the cooldown.
     *
     * @param caravan the caravan
     * @param camp    the camp
     * @param now     the game time
     * @return true in the cooldown
     */
    public static boolean hasMet(MobileGroup caravan, PillagerCamp camp, long now) {
        Long time = MET.getOrDefault(caravan.id(), Map.of()).get(camp.id());
        long cooldown = WorldConfig.PILLAGER_ENCOUNTER_COOLDOWN_MINUTES.get() * 1200L;
        return time != null && now - time < cooldown;
    }

    /**
     * Forgets the checks and the encounters of a caravan, for example at the end of a test.
     *
     * @param caravanId the id of the caravan
     */
    public static void forget(String caravanId) {
        CHECKED.remove(caravanId);
        MET.remove(caravanId);
        NO_SPAWN_UNTIL.remove(caravanId);
    }

    private static void recordMeeting(MobileGroup caravan, PillagerCamp camp, long now) {
        MET.computeIfAbsent(caravan.id(), id -> new HashMap<>()).put(camp.id(), now);
    }

    private static Optional<LivingEntity> merchant(ServerLevel level, MobileGroup caravan) {
        return caravan.merchant().map(member -> level.getEntity(member.id()))
                .filter(entity -> entity instanceof LivingEntity && entity.isAlive())
                .map(entity -> (LivingEntity) entity);
    }

    /** The living members of a group with a role, or all living members for a null role, weakest first. */
    private static List<GroupMember> living(MobileGroup group, MemberRole role) {
        List<GroupMember> result = new ArrayList<>();
        for (GroupMember member : group.members()) {
            if (member.isAlive() && (role == null || member.role() == role)) {
                result.add(member);
            }
        }
        result.sort(Comparator.comparingDouble(GroupMember::health));
        return result;
    }

    /** The first members of a list die. */
    private static void kill(List<GroupMember> members, int count) {
        for (int i = 0; i < Math.min(count, members.size()); i++) {
            members.get(i).setHealth(0.0F);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        CHECKED.clear();
        MET.clear();
        NO_SPAWN_UNTIL.clear();
    }
}
