package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * The entities of the abstract patrols of the pillager camps (see {@link PillagerPatrolGroups}).
 *
 * <ul>
 *   <li>A patrol gets entities when its camp sends it to a player (see {@link PillagerPatrols}). The entities are
 *       vanilla pillagers: one for each member that the player gets, from the first living members. Each entity has
 *       the UUID and the health of its member, and the id of its patrol ({@link #PATROL_TAG}). The first entity is the
 *       patrol leader. The other living members stay abstract.</li>
 *   <li>While a patrol has entities, the abstract simulation does not move it. Each second, the health of the
 *       entities sets the health of the members, and the position of the first living entity sets the progress of the
 *       patrol on its loop.</li>
 *   <li>While its player is in the territory, the patrol walks to the player (see {@link PillagerPatrols}). Else the
 *       members walk on the loop: their patrol target is {@link #LOOP_LOOKAHEAD} blocks ahead on the loop.</li>
 *   <li>When no player is in {@code groups.materializeDistance} plus {@code groups.dematerializeMargin} blocks of
 *       the entities, the members get the state of their entities and the entities go away. The patrol is abstract
 *       again. This also occurs when the chunk of an entity unloads, and when the server stops.</li>
 *   <li>A dead entity records the death of its member. When all members are dead, the patrol waits at its camp for
 *       {@code pillagers.patrolRespawnMinutes}, with new members (see {@link PillagerPatrolGroups#regroup}).</li>
 *   <li>An entity of a patrol that loads from a chunk while its patrol has no entities does not join the level. So
 *       the entities of a patrol do not duplicate.</li>
 * </ul>
 */
public final class PatrolEntities {
    /** Key in the persistent data of an entity: the id of its patrol. */
    public static final String PATROL_TAG = "folkhold_world:pillager_patrol";
    /** Distance in blocks from the progress of a patrol to the patrol target of its members on the loop. */
    static final double LOOP_LOOKAHEAD = 16.0;

    /** The UUIDs of the entities of each patrol with entities, by patrol id. Not saved. */
    private static final Map<String, Set<UUID>> MATERIALIZED = new HashMap<>();

    private PatrolEntities() {
    }

    /** True if the patrol has entities now. */
    public static boolean isMaterialized(MobileGroup patrol) {
        return MATERIALIZED.containsKey(patrol.id());
    }

    /**
     * The UUIDs of the entities of a patrol.
     *
     * @param patrolId the id of the patrol
     * @return the UUIDs, or an empty set for a patrol with no entities
     */
    public static Set<UUID> entities(String patrolId) {
        return Set.copyOf(MATERIALIZED.getOrDefault(patrolId, Set.of()));
    }

    /**
     * Creates the entities of a patrol at a position and sends them to a target.
     *
     * @param level  the Overworld
     * @param patrol the patrol, with no entities
     * @param spawn  the spawn position, on the surface
     * @param target the patrol target of the members
     * @param size   the maximum number of entities
     * @return the entities that spawned. Empty if the patrol has entities or no living member.
     */
    public static List<PatrollingMonster> materialize(ServerLevel level, MobileGroup patrol, BlockPos spawn,
            BlockPos target, int size) {
        if (isMaterialized(patrol) || patrol.state() != GroupState.TRAVELING) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        MATERIALIZED.put(patrol.id(), ids);
        List<PatrollingMonster> members = new ArrayList<>();
        for (GroupMember member : patrol.members()) {
            if (members.size() >= size) {
                break;
            }
            if (!member.isAlive()) {
                continue;
            }
            Entity stale = level.getEntity(member.id());
            if (stale != null) {
                stale.discard();
            }
            ids.add(member.id());
            Optional<PatrollingMonster> entity = PillagerPatrols.spawnMember(level, spawn, members.size(), target,
                    mob -> {
                        mob.setUUID(member.id());
                        mob.getPersistentData().putString(PATROL_TAG, patrol.id());
                        mob.setPersistenceRequired();
                        mob.setHealth(Math.min(member.health(), mob.getMaxHealth()));
                    });
            if (entity.isPresent()) {
                members.add(entity.get());
            } else {
                ids.remove(member.id());
            }
        }
        if (members.isEmpty()) {
            MATERIALIZED.remove(patrol.id());
            return List.of();
        }
        PillagerData.get(level.getServer()).setDirty();
        return members;
    }

    /**
     * Checks the patrols with entities: writes back their state, sends them back to their loop, and removes the
     * entities with no player near.
     *
     * @param level      the Overworld
     * @param withTarget the UUIDs of the entities that walk to a player now
     */
    static void update(ServerLevel level, Set<UUID> withTarget) {
        MinecraftServer server = level.getServer();
        PillagerData data = PillagerData.get(server);
        double removeDistance = WorldConfig.GROUP_MATERIALIZE_DISTANCE.get()
                + WorldConfig.GROUP_DEMATERIALIZE_MARGIN.get();
        List<ServerPlayer> players = level.players().stream().filter(player -> !player.isSpectator()).toList();
        Iterator<Map.Entry<String, Set<UUID>>> iterator = MATERIALIZED.entrySet().iterator();
        List<MobileGroup> toRemove = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<String, Set<UUID>> entry = iterator.next();
            Optional<MobileGroup> found = data.patrols().byId(entry.getKey());
            if (found.isEmpty()) {
                discard(level, entry.getValue());
                iterator.remove();
                continue;
            }
            MobileGroup patrol = found.get();
            LivingEntity leader = sync(level, patrol, entry.getValue());
            if (patrol.members().stream().noneMatch(GroupMember::isAlive)) {
                discard(level, entry.getValue());
                iterator.remove();
                PillagerPatrolGroups.regroup(server, patrol, SettlementData.get(server).tickNumber());
                continue;
            }
            if (leader == null || nearestPlayer(players, leader) > removeDistance) {
                toRemove.add(patrol);
                continue;
            }
            walkTheLoop(level, patrol, leader, entry.getValue(), withTarget);
        }
        for (MobileGroup patrol : toRemove) {
            dematerialize(level, patrol, true);
        }
    }

    /** The members with no target and no player to follow walk on the loop. */
    private static void walkTheLoop(ServerLevel level, MobileGroup patrol, LivingEntity leader, Set<UUID> ids,
            Set<UUID> withTarget) {
        Route route = patrol.route();
        if (route.length() <= 0.0) {
            return;
        }
        Route.Position ahead = route.positionAt((patrol.progress() + LOOP_LOOKAHEAD) % route.length());
        int x = (int) Math.floor(ahead.x());
        int z = (int) Math.floor(ahead.z());
        BlockPos target = level.hasChunk(x >> 4, z >> 4)
                ? level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z))
                : new BlockPos(x, leader.getBlockY(), z);
        for (UUID id : ids) {
            if (!withTarget.contains(id) && level.getEntity(id) instanceof PatrollingMonster member
                    && member.isAlive() && member.getTarget() == null) {
                member.setPatrolTarget(target);
            }
        }
    }

    /**
     * Copies the state of the entities of a patrol to the patrol: the health of the members, and the progress from
     * the position of the first living entity.
     *
     * @return the first living entity, or null
     */
    private static LivingEntity sync(ServerLevel level, MobileGroup patrol, Set<UUID> ids) {
        LivingEntity leader = null;
        for (UUID id : ids) {
            Optional<GroupMember> member = patrol.member(id);
            if (member.isEmpty() || !member.get().isAlive()
                    || !(level.getEntity(id) instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            member.get().setHealth(living.getHealth());
            if (leader == null) {
                leader = living;
                patrol.setProgress(patrol.route().progressNear(living.getX(), living.getZ()));
            }
        }
        PillagerData.get(level.getServer()).setDirty();
        return leader;
    }

    /**
     * Writes back the state of the members of a patrol and removes its entities.
     *
     * @param level     the Overworld
     * @param patrol    the patrol
     * @param writeBack true to write back the health of the members and the progress of the patrol
     */
    public static void dematerialize(ServerLevel level, MobileGroup patrol, boolean writeBack) {
        Set<UUID> ids = MATERIALIZED.get(patrol.id());
        if (ids == null) {
            return;
        }
        if (writeBack) {
            sync(level, patrol, ids);
        }
        MATERIALIZED.remove(patrol.id());
        discard(level, ids);
        PillagerData.get(level.getServer()).setDirty();
        FolkholdWorld.LOGGER.debug("Pillager patrol abstract again: {}", patrol);
    }

    /**
     * Removes the entities of a patrol with no write back, for example when its camp goes away.
     *
     * @param level    the Overworld
     * @param patrolId the id of the patrol
     */
    static void forget(ServerLevel level, String patrolId) {
        Set<UUID> ids = MATERIALIZED.remove(patrolId);
        if (ids != null) {
            discard(level, ids);
        }
    }

    private static void discard(ServerLevel level, Set<UUID> ids) {
        for (UUID id : ids) {
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
    }

    private static double nearestPlayer(List<ServerPlayer> players, Entity entity) {
        double nearest = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            double dx = player.getX() - entity.getX();
            double dz = player.getZ() - entity.getZ();
            nearest = Math.min(nearest, Math.sqrt(dx * dx + dz * dz));
        }
        return nearest;
    }

    /** The id of the patrol of an entity, or null. */
    static String patrolId(Entity entity) {
        return entity.getPersistentData().contains(PATROL_TAG) ? entity.getPersistentData().getString(PATROL_TAG)
                : null;
    }

    /** An entity of a patrol with no entities does not join the level. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        String patrolId = patrolId(event.getEntity());
        if (patrolId == null) {
            return;
        }
        Set<UUID> ids = MATERIALIZED.get(patrolId);
        if (ids == null || !ids.contains(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    /** When the chunk of an entity of a patrol unloads, the whole patrol is abstract again. */
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity entity = event.getEntity();
        String patrolId = patrolId(entity);
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (patrolId == null || reason == null || !reason.shouldSave() || !MATERIALIZED.containsKey(patrolId)) {
            return;
        }
        PillagerData.get(level.getServer()).patrols().byId(patrolId).ifPresent(patrol -> {
            if (entity instanceof LivingEntity living) {
                patrol.member(entity.getUUID()).ifPresent(member -> member.setHealth(living.getHealth()));
            }
            dematerialize(level, patrol, true);
        });
    }

    /** A dead entity of a patrol records the death of its member. */
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        String patrolId = patrolId(event.getEntity());
        if (patrolId == null) {
            return;
        }
        PillagerData data = PillagerData.get(level.getServer());
        data.patrols().byId(patrolId).flatMap(patrol -> patrol.member(event.getEntity().getUUID()))
                .ifPresent(member -> {
                    member.setHealth(0.0F);
                    data.setDirty();
                });
    }

    /** Before the server saves the world, all patrols are abstract again. */
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel level = event.getServer().overworld();
        PillagerData data = PillagerData.get(event.getServer());
        for (String patrolId : List.copyOf(MATERIALIZED.keySet())) {
            Optional<MobileGroup> patrol = data.patrols().byId(patrolId);
            if (patrol.isPresent()) {
                dematerialize(level, patrol.get(), true);
            } else {
                forget(level, patrolId);
            }
        }
        MATERIALIZED.clear();
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        MATERIALIZED.clear();
    }
}
