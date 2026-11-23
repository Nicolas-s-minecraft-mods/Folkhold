package io.github.nicolasgoyon.folkhold.world.quest;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MemberRole;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.quest.EscortDifficulty;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestGenerator;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardRule;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.tick.IntervalCheck;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.ItemIds;
import io.github.nicolasgoyon.folkhold.world.group.GroupMaterializer;
import io.github.nicolasgoyon.folkhold.world.group.MobileGroupData;
import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload;
import io.github.nicolasgoyon.folkhold.world.pillager.PatrolEncounters;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerTerritories;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.settlement.SimulationProfiler;
import io.github.nicolasgoyon.folkhold.world.settlement.SimulationTicker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The escort quests: the player protects a real caravan to its destination.
 *
 * <ul>
 *   <li>When the trade makes a caravan, its village offers an escort quest. The caravan waits at the village until a
 *       player accepts, or until the end of the offer. Then it leaves.</li>
 *   <li>The escorted caravan keeps its entities while the player is in the escort distance.</li>
 *   <li>When the player is farther than the escort distance, the caravan stops. When the player stays away longer
 *       than a delay, the quest fails and the caravan continues as an abstract group.</li>
 *   <li>Each escort has ambushes of pillagers at random points of the route. The difficulty sets their number and
 *       their size.</li>
 *   <li>Difficulty ({@link EscortDifficulties}): the level of the origin village, the length of the route and a
 *       pillager camp near the origin or the destination give the difficulty of the escort. The difficulty gives the
 *       extra guards of the caravan, the number of ambushes, the pillagers, the vindicators and the ravager of each
 *       ambush, the helmet of the raiders and the factor of the reward.</li>
 *   <li>An ambush at a point in a pillager territory: the patrol of the camp attacks in place of the ambush, and the
 *       caravan gets no second attack from this camp (see {@link PatrolEncounters#replacesAmbush}).</li>
 *   <li>Success: the caravan arrives and its merchant is alive. The reward comes from the value of the cargo.</li>
 *   <li>Failure: the merchant dies, the player stays away too long, the player raids the caravan, or the deadline
 *       ends. The time of an escort comes from the length of its route ({@link #escortMinutes}).</li>
 *   <li>The raiders of an ambush or of a patrol appear at a position with a walk path to the merchant (see
 *       {@link AmbushSpawns}). The merchant waits while they live. It continues when no raider is near it for a
 *       delay (see {@link #attackers}).</li>
 *   <li>Each raider of an attack keeps the id of its caravan ({@link #ATTACK_TAG}). When a raider loads again, for
 *       example after a restart of the server, it joins the attack of its caravan again (see
 *       {@link #onEntityJoin}).</li>
 *   <li>Each second, the player of an escort gets the state of the caravan in the quest HUD, with the number of
 *       raiders of the attack.</li>
 * </ul>
 */
public final class Escorts {
    /** Key in the persistent data of an ambush raider. */
    public static final String AMBUSH_TAG = "folkhold_world:ambush";
    /** Key in the persistent data of a raider that attacks a caravan: the id of the caravan. */
    public static final String ATTACK_TAG = "folkhold_world:attack";
    private static final int CHECK_INTERVAL = 20;
    /** The check of the escorts, one time in each {@link #CHECK_INTERVAL} of game time. */
    private static final IntervalCheck CHECK = new IntervalCheck(CHECK_INTERVAL);
    /** The minutes that the time of an escort adds to the travel: the departure, the stops, the ambushes. */
    private static final long EXTRA_MINUTES = 10L;
    /** The ambushes are between these shares of the route. */
    private static final double AMBUSH_FIRST = 0.2;
    private static final double AMBUSH_LAST = 0.9;
    /** Distance in blocks in front of the merchant where an ambush appears. */
    private static final double AMBUSH_AHEAD = 16.0;

    /** Game time when the player of each escort went away, by quest id. Not saved. */
    private static final Map<String, Long> AWAY_SINCE = new HashMap<>();
    /** The progress of the remaining ambushes of each escort, by quest id. Not saved. */
    private static final Map<String, Deque<Double>> AMBUSHES = new HashMap<>();
    /**
     * The raiders that attack each caravan with entities, by caravan id. Not saved: the raiders keep the id of their
     * caravan, and a raider that loads joins the attack again.
     */
    private static final Map<String, Attack> ATTACKS = new HashMap<>();

    /**
     * The raiders that attack a caravan, and the last game time with a raider near the merchant.
     *
     * @param raiders  the UUIDs of the raiders
     * @param lastNear the last game time with a raider near the merchant
     */
    private record Attack(Set<UUID> raiders, long lastNear) {
    }

    /** The last game time near the merchant of an attack that starts at the load of a raider: no time. */
    private static final long NEVER_NEAR = Long.MIN_VALUE / 2;

    private Escorts() {
    }

    /**
     * Offers an escort quest for a new caravan. The caravan waits at its village until the end of the offer.
     *
     * @param server     the server
     * @param caravan    the caravan, in the state waiting
     * @param origin     the village of the caravan
     * @param tickNumber the number of the abstract tick
     * @return the quest, or an empty value if the escort offers are off or no definition of the need
     *         {@link QuestNeed#CARAVAN} makes offers in the village (see {@link QuestDefinitions})
     */
    public static Optional<Quest> offer(MinecraftServer server, MobileGroup caravan, Settlement origin,
            long tickNumber) {
        Optional<QuestDefinition> definition = QuestDefinitions.matching(QuestNeed.CARAVAN, origin.level()).stream()
                .findFirst();
        if (!WorldConfig.QUEST_ESCORT_OFFERS.get() || definition.isEmpty()) {
            return Optional.empty();
        }
        QuestDefinition.Values values = definition.get().values();
        RewardRule rule = QuestRewards.choose(definition.get());
        int wait = WorldConfig.QUEST_ESCORT_WAIT_TICKS.get();
        caravan.setDepartureTick(tickNumber + wait);
        long now = server.overworld().getGameTime();
        long offerEnd = now + (long) wait * SimulationTicker.settings().intervalTicks();
        int difficulty = difficulty(server, caravan, origin);
        EscortDifficulty.Level level = EscortDifficulties.get().level(difficulty);
        float guardHealth = (float) WorldConfig.GUARD.health();
        for (int i = 0; i < level.guards(); i++) {
            caravan.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, guardHealth));
        }
        long reward = Math.max(1, Math.round(caravan.payment() * level.rewardFactor()
                * rule.factor().orElse(values.rewardFactor().orElse(WorldConfig.QUEST_ESCORT_REWARD_SHARE.get()))));
        long minutes = escortMinutes(caravan.route().length(),
                values.durationMinutes().orElse(WorldConfig.QUEST_ESCORT_MINUTES.get()));
        Quest quest = new Quest(UUID.randomUUID().toString(), QuestKind.ESCORT, origin.id(), origin.factionId(),
                caravan.id(), 0, reward, values.reputation().orElse(WorldConfig.QUEST_ESCORT_REPUTATION.get()),
                QuestGenerator.minTier((double) caravan.payment(), WorldConfig.QUEST_ESCORT_REWARD_SHARE.get(),
                        QuestSystem.settings(definition.get())), offerEnd,
                minutes * SharedConstants.TICKS_PER_MINUTE, definition.get().id());
        quest.setDifficulty(difficulty);
        QuestRewards.apply(quest, definition.get(), rule, origin);
        QuestData data = QuestData.get(server);
        data.quests().add(quest);
        data.setDirty();
        return Optional.of(quest);
    }

    /**
     * The time of an escort: the length of the route at {@link WorldConfig#QUEST_ESCORT_BLOCKS_PER_MINUTE}, plus
     * {@link #EXTRA_MINUTES}, and at least {@link WorldConfig#QUEST_ESCORT_MINUTES}.
     *
     * @param routeLength the length of the route, in blocks
     * @return the time, in minutes
     */
    public static long escortMinutes(double routeLength) {
        return escortMinutes(routeLength, WorldConfig.QUEST_ESCORT_MINUTES.get());
    }

    /**
     * The time of an escort with a minimum: the length of the route at
     * {@link WorldConfig#QUEST_ESCORT_BLOCKS_PER_MINUTE}, plus {@link #EXTRA_MINUTES}, and at least the minimum.
     *
     * @param routeLength the length of the route, in blocks
     * @param minimum     the minimum time, in minutes
     * @return the time, in minutes
     */
    public static long escortMinutes(double routeLength, long minimum) {
        long travel = (long) Math.ceil(routeLength / WorldConfig.QUEST_ESCORT_BLOCKS_PER_MINUTE.get()) + EXTRA_MINUTES;
        return Math.max(minimum, travel);
    }

    /** The accepted escort quests. */
    public static List<Quest> active(MinecraftServer server) {
        List<Quest> result = new ArrayList<>();
        for (Quest quest : QuestData.get(server).quests().all()) {
            if (quest.kind() == QuestKind.ESCORT && quest.state() == QuestState.ACCEPTED) {
                result.add(quest);
            }
        }
        return result;
    }

    /** An accepted escort: the caravan leaves now and keeps its entities. */
    public static void onAccepted(ServerPlayer player, Quest quest) {
        if (quest.kind() != QuestKind.ESCORT) {
            return;
        }
        MinecraftServer server = player.server;
        caravan(server, quest).ifPresent(group -> {
            if (group.state() == GroupState.WAITING) {
                group.setState(GroupState.TRAVELING);
            }
            GroupMaterializer.keep(group.id(), true);
            AMBUSHES.put(quest.id(), plan(quest, group));
            MobileGroupData.get(server).setDirty();
        });
    }

    /** An escort that ends (completed, failed or abandoned) releases its caravan. */
    public static void onEnded(MinecraftServer server, Quest quest) {
        if (quest.kind() != QuestKind.ESCORT) {
            return;
        }
        AWAY_SINCE.remove(quest.id());
        AMBUSHES.remove(quest.id());
        caravan(server, quest).ifPresent(group -> {
            ATTACKS.remove(group.id());
            GroupMaterializer.keep(group.id(), false);
            if (group.state() == GroupState.STOPPED) {
                group.setState(GroupState.TRAVELING);
            }
            MobileGroupData.get(server).setDirty();
        });
    }

    /**
     * A caravan arrives or is lost in the simulation, before the simulation removes it. Its accepted escort
     * succeeds if the caravan arrives with its merchant alive. Else it fails.
     *
     * @param server the server
     * @param group  the caravan
     */
    public static void onGroupFinished(MinecraftServer server, MobileGroup group) {
        for (Quest quest : active(server)) {
            if (!quest.target().equals(group.id())) {
                continue;
            }
            boolean merchantAlive = group.merchant().filter(merchant -> merchant.isAlive()).isPresent();
            if (group.state() == GroupState.ARRIVED && merchantAlive) {
                QuestBoards.completeAndNotify(server, quest);
            } else {
                QuestBoards.fail(server, quest);
            }
        }
    }

    /**
     * A player raids a caravan: the escort of this player on this caravan fails.
     *
     * @param server the server
     * @param player the player
     * @param group  the caravan
     */
    public static void onRaid(MinecraftServer server, ServerPlayer player, MobileGroup group) {
        for (Quest quest : active(server)) {
            if (quest.target().equals(group.id()) && player.getUUID().equals(quest.playerId())) {
                QuestBoards.fail(server, quest);
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        if (CHECK.isDue(now)) {
            SimulationProfiler.time("escorts (each second)", () -> update(server, now));
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        CHECK.reset();
        AWAY_SINCE.clear();
        AMBUSHES.clear();
        ATTACKS.clear();
    }

    /**
     * Checks all escorts now: end of the offers of the caravans that left, success, failure, stop and departure of
     * the caravan, ambushes.
     *
     * @param server the server
     * @param now    the game time
     */
    public static void update(MinecraftServer server, long now) {
        ServerLevel level = server.overworld();
        QuestData data = QuestData.get(server);
        ATTACKS.keySet().removeIf(id -> MobileGroupData.get(server).groups().byId(id).isEmpty());
        for (Quest quest : List.copyOf(data.quests().all())) {
            if (quest.kind() == QuestKind.ESCORT && quest.state() == QuestState.OFFERED
                    && caravan(server, quest).filter(group -> group.state() == GroupState.WAITING).isEmpty()) {
                data.quests().withdraw(quest.id());
                data.setDirty();
            }
        }
        for (Quest quest : active(server)) {
            Optional<MobileGroup> found = caravan(server, quest);
            if (found.isEmpty() || found.get().state() == GroupState.LOST) {
                QuestBoards.fail(server, quest);
                continue;
            }
            MobileGroup group = found.get();
            if (group.state() == GroupState.ARRIVED) {
                if (group.merchant().filter(merchant -> merchant.isAlive()).isPresent()) {
                    QuestBoards.completeAndNotify(server, quest);
                } else {
                    QuestBoards.fail(server, quest);
                }
                continue;
            }
            ServerPlayer player = quest.playerId() == null ? null
                    : server.getPlayerList().getPlayer(quest.playerId());
            if (player != null && player.level() == level
                    && distance(level, player, group) <= WorldConfig.QUEST_ESCORT_DISTANCE.get()) {
                AWAY_SINCE.remove(quest.id());
                if (attackers(level, group, now) > 0) {
                    if (group.state() == GroupState.TRAVELING) {
                        group.setState(GroupState.STOPPED);
                    }
                } else if (group.state() == GroupState.STOPPED) {
                    group.setState(GroupState.TRAVELING);
                }
                GroupMaterializer.keep(group.id(), true);
                ambushes(level, quest, group);
            } else {
                if (group.state() == GroupState.TRAVELING) {
                    group.setState(GroupState.STOPPED);
                }
                long away = AWAY_SINCE.computeIfAbsent(quest.id(), id -> now);
                if (now - away > WorldConfig.QUEST_ESCORT_AWAY_SECONDS.get() * 20L) {
                    QuestBoards.fail(server, quest);
                    continue;
                }
            }
            MobileGroupData.get(server).setDirty();
            if (player != null) {
                QuestBoards.sendLog(player);
            }
        }
    }

    /**
     * The state of the caravan of an accepted escort, for the quest HUD of its player.
     *
     * @param server the server
     * @param quest  the escort quest
     * @param player the player of the quest
     * @return the state, or empty if the caravan does not exist
     */
    public static Optional<QuestLogPayload.Escort> hudState(MinecraftServer server, Quest quest, ServerPlayer player) {
        Optional<MobileGroup> found = caravan(server, quest);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MobileGroup group = found.get();
        ServerLevel level = server.overworld();
        int health = -1;
        int maxHealth = -1;
        Optional<Entity> merchant = group.merchant().map(member -> level.getEntity(member.id()));
        if (merchant.isPresent() && merchant.get() instanceof LivingEntity living) {
            health = (int) Math.ceil(living.getHealth());
            maxHealth = (int) Math.ceil(living.getMaxHealth());
        }
        int distance = player.level() == level ? (int) Math.round(distance(level, player, group)) : Integer.MAX_VALUE;
        Long since = AWAY_SINCE.get(quest.id());
        int awaySeconds = since == null ? -1 : (int) Math.max(0,
                WorldConfig.QUEST_ESCORT_AWAY_SECONDS.get() - (level.getGameTime() - since) / 20L);
        Attack attack = ATTACKS.get(group.id());
        int raiders = attack == null ? 0 : (int) attack.raiders().stream().map(level::getEntity)
                .filter(entity -> entity != null && entity.isAlive()).count();
        return Optional.of(new QuestLogPayload.Escort((int) Math.round(group.progress()),
                (int) Math.round(group.route().length()), health, maxHealth, Math.min(distance, 99_999),
                WorldConfig.QUEST_ESCORT_DISTANCE.get(), awaySeconds, raiders));
    }

    /**
     * The entity of the merchant of the caravan of an escort, if it exists.
     *
     * @param server the server
     * @param quest  the escort quest
     * @return the entity, or empty
     */
    public static Optional<Entity> merchantEntity(MinecraftServer server, Quest quest) {
        ServerLevel level = server.overworld();
        return caravan(server, quest).flatMap(MobileGroup::merchant)
                .map(member -> level.getEntity(member.id()))
                .filter(Entity::isAlive);
    }

    /**
     * The position of the caravan of an escort: its merchant if it has entities, else its position on the route.
     *
     * @param server the server
     * @param quest  the escort quest
     * @return the position, or empty if the caravan does not exist
     */
    public static Optional<BlockPos> position(MinecraftServer server, Quest quest) {
        Optional<Entity> merchant = merchantEntity(server, quest);
        if (merchant.isPresent()) {
            return Optional.of(merchant.get().blockPosition());
        }
        return caravan(server, quest).map(group -> {
            Route.Position position = group.position();
            int x = (int) Math.floor(position.x());
            int z = (int) Math.floor(position.z());
            return new BlockPos(x, Settlements.seaLevel(server.overworld()), z);
        });
    }

    /**
     * Makes an ambush of pillagers in front of the merchant of a caravan with entities: the pillagers, the
     * vindicators and the ravager of a difficulty level. The raiders attack the merchant. They do not join a raid,
     * and they wear the helmet of the level. The raiders appear at a position with a walk path to the merchant (see
     * {@link AmbushSpawns}). With no such position, the ambush does not occur. The merchant waits for the end of the
     * ambush (see {@link #track}).
     *
     * @param level  the level
     * @param group  the caravan
     * @param spec   the difficulty level
     * @param seed   the seed of the number of pillagers and of the positions
     * @return the raiders
     */
    public static List<Mob> spawnAmbush(ServerLevel level, MobileGroup group, EscortDifficulty.Level spec,
            long seed) {
        List<Mob> raiders = new ArrayList<>();
        Optional<Entity> merchant = group.merchant().map(member -> level.getEntity(member.id()));
        if (merchant.isEmpty() || !(merchant.get() instanceof LivingEntity target)) {
            return raiders;
        }
        Route route = group.route();
        double point = Math.min(route.length(), route.progressNear(target.getX(), target.getZ()) + AMBUSH_AHEAD);
        Optional<BlockPos> spawn = AmbushSpawns.find(level, route, point, target.blockPosition(), spec.ravager());
        if (spawn.isEmpty()) {
            return raiders;
        }
        SplittableRandom random = new SplittableRandom(seed);
        List<EntityType<? extends Mob>> types = new ArrayList<>();
        int pillagers = spec.pillagers(random);
        for (int i = 0; i < pillagers; i++) {
            types.add(EntityType.PILLAGER);
        }
        for (int i = 0; i < spec.vindicators(); i++) {
            types.add(EntityType.VINDICATOR);
        }
        if (spec.ravager()) {
            types.add(EntityType.RAVAGER);
        }
        Optional<Item> helmet = spec.helmet().isEmpty() ? Optional.empty()
                : ItemIds.item(spec.helmet());
        for (EntityType<? extends Mob> type : types) {
            // Each raider appears near the good position, on a position with the same checks and nearly the same
            // level. Else it appears at the good position.
            BlockPos near = AmbushSpawns.surface(level, spawn.get().getX() + random.nextInt(-2, 3),
                    spawn.get().getZ() + random.nextInt(-2, 3));
            BlockPos pos = Math.abs(near.getY() - spawn.get().getY()) <= 1
                    && AmbushSpawns.check(level, near, type == EntityType.RAVAGER) == AmbushSpawns.Check.GOOD
                    ? near : spawn.get();
            Mob mob = type.create(level);
            if (mob == null) {
                continue;
            }
            mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
            if (mob instanceof Raider raider) {
                raider.setCanJoinRaid(false);
                raider.setPatrolLeader(false);
            }
            if (helmet.isPresent() && type != EntityType.RAVAGER) {
                mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helmet.get()));
                mob.setDropChance(EquipmentSlot.HEAD, 0.0F);
            }
            mob.getPersistentData().putBoolean(AMBUSH_TAG, true);
            mob.setTarget(target);
            level.addFreshEntity(mob);
            raiders.add(mob);
        }
        track(level, group, raiders);
        return raiders;
    }

    /**
     * The difficulty of the escort of a caravan (see {@link EscortDifficulty#difficulty}): the level of the origin, the
     * length of the route, and a pillager camp near the origin or the destination.
     *
     * @param server  the server
     * @param caravan the caravan
     * @param origin  the origin village
     * @return the difficulty, 1 or more
     */
    public static int difficulty(MinecraftServer server, MobileGroup caravan, Settlement origin) {
        EscortDifficulty difficulties = EscortDifficulties.get();
        int distance = difficulties.campDistance();
        boolean nearCamp = distance > 0 && (PillagerTerritories.nearestCamp(server,
                new BlockPos(origin.x(), 0, origin.z()), distance).isPresent()
                || SettlementData.get(server).registry().byId(caravan.destinationId())
                        .filter(destination -> PillagerTerritories.nearestCamp(server,
                                new BlockPos(destination.x(), 0, destination.z()), distance).isPresent())
                        .isPresent());
        return difficulties.difficulty(origin.level(), caravan.route().length(), nearCamp);
    }

    /** The caravan of an escort quest. */
    public static Optional<MobileGroup> caravan(MinecraftServer server, Quest quest) {
        return MobileGroupData.get(server).groups().byId(quest.target());
    }

    /**
     * The ambushes of a caravan with entities, when it passes their points of the route. In a pillager territory, the
     * patrol of the camp replaces the ambush (see {@link PatrolEncounters#replacesAmbush}).
     *
     * @param level the Overworld
     * @param quest the escort quest
     * @param group the caravan
     */
    public static void ambushes(ServerLevel level, Quest quest, MobileGroup group) {
        if (!GroupMaterializer.isMaterialized(group)) {
            return;
        }
        Deque<Double> plan = AMBUSHES.computeIfAbsent(quest.id(), id -> plan(quest, group));
        EscortDifficulty.Level spec = EscortDifficulties.get().level(quest.difficulty());
        while (!plan.isEmpty() && group.progress() >= plan.peekFirst()) {
            double point = plan.pollFirst();
            if (PatrolEncounters.replacesAmbush(level, group)) {
                continue;
            }
            spawnAmbush(level, group, spec, quest.id().hashCode() ^ Double.doubleToLongBits(point));
        }
    }

    /**
     * The progress of the ambushes of an escort: the number of ambushes of its difficulty, at random points between
     * 20 % and 90 % of the route. The id of the quest selects them, so the plan is the same after a restart. The points
     * behind the caravan are not in the plan.
     */
    public static Deque<Double> plan(Quest quest, MobileGroup group) {
        SplittableRandom random = new SplittableRandom(quest.id().hashCode());
        int count = EscortDifficulties.get().level(quest.difficulty()).ambushes(random);
        double length = group.route().length();
        List<Double> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(length * (AMBUSH_FIRST + random.nextDouble() * (AMBUSH_LAST - AMBUSH_FIRST)));
        }
        points.sort(Double::compare);
        Deque<Double> plan = new ArrayDeque<>();
        for (double point : points) {
            if (point > group.progress()) {
                plan.add(point);
            }
        }
        return plan;
    }

    /**
     * Records the raiders that attack a caravan with entities: an ambush or a patrol of a camp. The merchant of an
     * escort waits while they live (see {@link #attackers}).
     *
     * @param level   the level
     * @param group   the caravan
     * @param raiders the raiders
     */
    public static void track(ServerLevel level, MobileGroup group, Collection<? extends Entity> raiders) {
        if (raiders.isEmpty()) {
            return;
        }
        Attack old = ATTACKS.get(group.id());
        Set<UUID> ids = new HashSet<>(old == null ? Set.of() : old.raiders());
        raiders.forEach(raider -> {
            ids.add(raider.getUUID());
            raider.getPersistentData().putString(ATTACK_TAG, group.id());
        });
        ATTACKS.put(group.id(), new Attack(ids, level.getGameTime()));
    }

    /**
     * A raider of an attack that loads in the Overworld joins the attack of its caravan again: after a restart of the
     * server, or when its chunk loads again. The caravan must have an accepted escort. An attack that starts at the
     * load has no time near the merchant: the merchant waits only when a raider is near it (see {@link #attackers}).
     *
     * @param event the event. The listener does not receive a canceled event.
     */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob raider)
                || level != level.getServer().overworld()) {
            return;
        }
        String groupId = raider.getPersistentData().getString(ATTACK_TAG);
        if (groupId.isEmpty() || active(level.getServer()).stream().noneMatch(quest -> quest.target().equals(groupId))) {
            return;
        }
        Attack old = ATTACKS.get(groupId);
        Set<UUID> ids = new HashSet<>(old == null ? Set.of() : old.raiders());
        ids.add(raider.getUUID());
        ATTACKS.put(groupId, new Attack(ids, old == null ? NEVER_NEAR : old.lastNear()));
    }

    /**
     * The number of living raiders that attack a caravan, for the wait of the merchant. The attack ends when all its
     * raiders are dead or unloaded, or when no raider is at {@link WorldConfig#QUEST_ESCORT_AMBUSH_DISTANCE} blocks
     * or less from the merchant for {@link WorldConfig#QUEST_ESCORT_AMBUSH_SECONDS}. Then the method gives 0. With no
     * merchant entity, the method gives 0 and the attack stays: after a restart, the raiders can load before the
     * merchant. A living raider with no target attacks the merchant: a raider that loads again has no target.
     *
     * @param level the level
     * @param group the caravan
     * @param now   the game time
     * @return the number of living raiders of the attack, or 0 with no attack
     */
    public static int attackers(ServerLevel level, MobileGroup group, long now) {
        Attack attack = ATTACKS.get(group.id());
        if (attack == null) {
            return 0;
        }
        Optional<Entity> merchant = group.merchant().map(member -> level.getEntity(member.id()));
        List<Entity> living = new ArrayList<>();
        for (UUID id : attack.raiders()) {
            Entity raider = level.getEntity(id);
            if (raider != null && raider.isAlive()) {
                living.add(raider);
                if (raider instanceof Mob mob && mob.getTarget() == null && merchant.isPresent()
                        && merchant.get() instanceof LivingEntity target && target.isAlive()) {
                    mob.setTarget(target);
                }
            }
        }
        if (living.isEmpty()) {
            ATTACKS.remove(group.id());
            return 0;
        }
        if (merchant.isEmpty()) {
            return 0;
        }
        double distance = WorldConfig.QUEST_ESCORT_AMBUSH_DISTANCE.get();
        boolean near = living.stream().anyMatch(raider -> raider.distanceToSqr(merchant.get()) <= distance * distance);
        long lastNear = near ? now : attack.lastNear();
        if (now - lastNear >= WorldConfig.QUEST_ESCORT_AMBUSH_SECONDS.get() * 20L) {
            ATTACKS.remove(group.id());
            return 0;
        }
        Set<UUID> ids = new HashSet<>();
        living.forEach(raider -> ids.add(raider.getUUID()));
        ATTACKS.put(group.id(), new Attack(ids, lastNear));
        return living.size();
    }

    /** Horizontal distance from a player to a caravan: to its merchant if it has entities. */
    private static double distance(ServerLevel level, ServerPlayer player, MobileGroup group) {
        double x;
        double z;
        Optional<Entity> merchant = group.merchant().map(member -> level.getEntity(member.id()));
        if (merchant.isPresent()) {
            x = merchant.get().getX();
            z = merchant.get().getZ();
        } else {
            Route.Position position = group.position();
            x = position.x();
            z = position.z();
        }
        double dx = player.getX() - x;
        double dz = player.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
