package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PatrolClock;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.tick.IntervalCheck;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The patrols of the pillager faction.
 *
 * <ul>
 *   <li>Each {@link #CHECK_TICKS} ticks, the server checks the players of the Overworld. A spectator is not
 *       checked. A player in a pillager territory discovers its camp: the maps show the territory.</li>
 *   <li>A player who stays in a pillager territory for {@code pillagers.patrolDelaySeconds} gets a patrol from the
 *       camp. The next patrol for this player comes after {@code pillagers.patrolCooldownSeconds} (see
 *       {@link PatrolClock}).</li>
 *   <li>The patrol is the abstract patrol of the camp that is the nearest to the player, if it travels and has no
 *       entities (see {@link PillagerPatrolGroups}). It gets its entities near the player (see
 *       {@link PatrolEntities}). When the camp has no such patrol, the patrol leaves at the next check. No other
 *       pillagers spawn for the player.</li>
 *   <li>The patrol spawns {@code pillagers.spawnDistance} blocks from the player, in the direction of the camp, or at
 *       the camp when the player is nearer to it. The position is on the surface, in a loaded chunk, not in a fluid.
 *       When no position is good, the patrol leaves at the next check.</li>
 *   <li>The members are vanilla pillagers. The first member is a patrol leader with the ominous banner. The members
 *       walk to the player with the vanilla patrol goal. While the player is in the territory, the members get the
 *       new position of the player at each check. The vanilla AI attacks the player at a short distance. Far from the
 *       players, the patrol is abstract again (see {@link PatrolEntities}).</li>
 *   <li>The number of members that the player gets comes from the tier of the player with the pillagers (see
 *       {@link PillagerReputation#patrolSize}).</li>
 *   <li>A player who kills an illager in a pillager territory loses {@code pillagers.killReputation} reputation with
 *       the pillagers.</li>
 * </ul>
 *
 * <p>The clocks and the link between a player and its patrol are in memory: a server restart starts the clocks
 * again.
 */
public final class PillagerPatrols {
    /** The tag of the members of a patrol. */
    public static final String MEMBER_TAG = "folkhold_world.pillager_patrol";
    /** The server checks the players each this number of ticks. */
    static final int CHECK_TICKS = 20;
    private static final IntervalCheck CHECK = new IntervalCheck(CHECK_TICKS);
    private static final Map<UUID, PatrolClock> CLOCKS = new HashMap<>();
    /** The members of the last patrol of each player. */
    private static final Map<UUID, List<UUID>> PATROLS = new HashMap<>();

    private PillagerPatrols() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        if (!CHECK.isDue(overworld.getGameTime())) {
            return;
        }
        Set<UUID> followers = new HashSet<>();
        for (ServerPlayer player : List.copyOf(overworld.players())) {
            if (check(overworld, player)) {
                followers.addAll(PATROLS.getOrDefault(player.getUUID(), List.of()));
            }
        }
        PatrolEncounters.checkMaterialized(overworld);
        PatrolEntities.update(overworld, followers);
    }

    /**
     * Checks one player.
     *
     * @return true if the player is in a pillager territory: the members of its patrol walk to it
     */
    private static boolean check(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        Optional<PillagerCamp> camp = player.isSpectator() || !player.isAlive()
                ? Optional.empty()
                : PillagerTerritories.campAt(server, player.blockPosition());
        camp.ifPresent(found -> PillagerData.get(server).discover(player.getUUID(), found.id()));
        if (camp.isPresent()) {
            follow(level, player);
        }
        int baseSize = WorldConfig.PILLAGER_PATROL_SIZE.get();
        PatrolClock clock = CLOCKS.computeIfAbsent(player.getUUID(), key -> new PatrolClock());
        long delay = WorldConfig.PILLAGER_PATROL_DELAY_SECONDS.get() * 20L;
        long cooldown = WorldConfig.PILLAGER_PATROL_COOLDOWN_SECONDS.get() * 20L;
        if (!clock.update(camp.isPresent() && baseSize > 0, level.getGameTime(), delay, cooldown)) {
            return camp.isPresent();
        }
        Optional<MobileGroup> patrol = PillagerPatrolGroups.nearestFree(server, camp.get(), player.getX(),
                player.getZ());
        Optional<BlockPos> spawn = spawnPosition(level, camp.get(), player.blockPosition());
        if (patrol.isEmpty() || spawn.isEmpty()) {
            clock.cancelPatrol();
            return true;
        }
        int size = PillagerReputation.patrolSize(PillagerFaction.tier(server, player.getUUID()), baseSize,
                WorldConfig.PILLAGER_SWORN_ENEMY_EXTRA.get());
        List<PatrollingMonster> members = PatrolEntities.materialize(level, patrol.get(), spawn.get(),
                player.blockPosition(), size);
        if (members.isEmpty()) {
            clock.cancelPatrol();
            return true;
        }
        PATROLS.put(player.getUUID(), members.stream().map(Entity::getUUID).toList());
        return true;
    }

    /** The members of the patrol of a player walk to the new position of the player. */
    private static void follow(ServerLevel level, ServerPlayer player) {
        for (UUID id : PATROLS.getOrDefault(player.getUUID(), List.of())) {
            if (level.getEntity(id) instanceof PatrollingMonster member && member.isAlive()
                    && member.getTarget() == null) {
                member.setPatrolTarget(player.blockPosition());
            }
        }
    }

    /**
     * The spawn position of a patrol for a player.
     *
     * @param level  the Overworld
     * @param camp   the camp of the patrol
     * @param player the position of the player
     * @return the position on the surface, or empty if the chunk is not loaded or the surface is a fluid
     */
    static Optional<BlockPos> spawnPosition(ServerLevel level, PillagerCamp camp, BlockPos player) {
        int distance = WorldConfig.PILLAGER_SPAWN_DISTANCE.get();
        Vec3 toCamp = new Vec3(camp.x() - player.getX(), 0, camp.z() - player.getZ());
        BlockPos column;
        if (toCamp.length() <= distance) {
            column = new BlockPos(camp.x(), camp.y(), camp.z());
        } else {
            Vec3 step = toCamp.normalize().scale(distance);
            column = BlockPos.containing(player.getX() + step.x, player.getY(), player.getZ() + step.z);
        }
        return surface(level, column);
    }

    /** The surface of a column: the first free block above the ground, or empty in a fluid or an unloaded chunk. */
    static Optional<BlockPos> surface(ServerLevel level, BlockPos column) {
        if (!level.isLoaded(column)) {
            return Optional.empty();
        }
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.below()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ground);
    }

    /**
     * Spawns a patrol of pillagers and sends it to a target. The first member is a patrol leader with the ominous
     * banner. The members get the tag {@link #MEMBER_TAG}.
     *
     * <p>Other systems can use this method, for example an ambush on a route: the target is a point of the route, and
     * the caller can then give an entity target to the members.
     *
     * @param level  the level
     * @param spawn  the spawn position, on the surface
     * @param target the target of the patrol
     * @param size   the number of members
     * @return the members that spawned
     */
    public static List<PatrollingMonster> send(ServerLevel level, BlockPos spawn, BlockPos target, int size) {
        List<PatrollingMonster> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            spawnMember(level, spawn, members.size(), target, member -> { }).ifPresent(members::add);
        }
        return members;
    }

    /**
     * Spawns one member of a patrol and sends it to a target. The member with the index 0 is a patrol leader with the
     * ominous banner. The member gets the tag {@link #MEMBER_TAG}.
     *
     * @param level  the level
     * @param spawn  the spawn position of the patrol, on the surface
     * @param index  the index of the member in the patrol: it sets the position near the spawn position
     * @param target the target of the patrol
     * @param setup  changes the member before it joins the level, for example its UUID
     * @return the member, or empty if it did not spawn
     */
    static Optional<PatrollingMonster> spawnMember(ServerLevel level, BlockPos spawn, int index, BlockPos target,
            Consumer<Pillager> setup) {
        Pillager member = EntityType.PILLAGER.create(level);
        if (member == null) {
            return Optional.empty();
        }
        BlockPos pos = surface(level, spawn.offset(index % 3 - 1, 0, index / 3)).orElse(spawn);
        if (index == 0) {
            member.setPatrolLeader(true);
        }
        member.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        EventHooks.finalizeMobSpawn(member, level, level.getCurrentDifficultyAt(pos), MobSpawnType.PATROL, null);
        member.setPatrolTarget(target);
        member.addTag(MEMBER_TAG);
        setup.accept(member);
        return level.addFreshEntity(member) ? Optional.of(member) : Optional.empty();
    }

    /** A player kills an illager in a pillager territory: the reputation with the pillagers goes down. */
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)
                || !event.getEntity().getType().is(EntityTypeTags.ILLAGER)
                || !(event.getEntity().level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (PillagerTerritories.campAt(level.getServer(), event.getEntity().blockPosition()).isPresent()) {
            PillagerFaction.apply(level.getServer(), player.getUUID(), WorldConfig.PILLAGER_KILL_REPUTATION.get());
        }
    }

    /**
     * The members of the last patrol of a player.
     *
     * @param player the id of the player
     * @return the ids of the members
     */
    public static List<UUID> patrol(UUID player) {
        return PATROLS.getOrDefault(player, List.of());
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CLOCKS.remove(event.getEntity().getUUID());
        PATROLS.remove(event.getEntity().getUUID());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        CHECK.reset();
        CLOCKS.clear();
        PATROLS.clear();
    }
}
