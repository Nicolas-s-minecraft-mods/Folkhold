package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MemberRole;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PatrolFight;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.group.GroupMaterializer;
import io.github.nicolasgoyon.folkhold.world.group.MobileGroupData;
import io.github.nicolasgoyon.folkhold.world.pillager.PatrolEncounters;
import io.github.nicolasgoyon.folkhold.world.pillager.PatrolEntities;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerData;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerPatrolGroups;
import io.github.nicolasgoyon.folkhold.world.quest.Escorts;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the encounters between the caravans and the abstract patrols of the pillager camps. See
 * {@link TestSupport} for the templates and the helpers.
 *
 * <p>The GameTest world has no structures. Each test records a camp with one patrol at the middle of its arena, and
 * removes the camp, the caravan and the entities at the end. Each test has its own batch.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PatrolEncounterGameTests {
    private static final BlockPos MIDDLE = new BlockPos(8, 1, 8);
    private static final int TERRITORY_RADIUS = 3;
    private static final int PATROL_SIZE = 3;
    private static final int SWORN_EXTRA = 2;
    private static final double PILLAGER_DAMAGE = 2.5;
    private static final double CARGO_LOSS = 0.5;
    private static final int BREAD = 20;
    private static final long PAYMENT = 20;
    private static final float GUARD_HEALTH = 30.0F;

    private PatrolEncounterGameTests() {
    }

    /** The values of the patrols and of the encounters for each test. */
    private static void configure(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_PATROLS_PER_CAMP, 1);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_PATROL_SIZE, PATROL_SIZE);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_SWORN_ENEMY_EXTRA, SWORN_EXTRA);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_CARAVAN_ENCOUNTERS, true);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_ENCOUNTER_DAMAGE, PILLAGER_DAMAGE);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_ENCOUNTER_CARGO_LOSS, CARGO_LOSS);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_ENCOUNTER_COOLDOWN_MINUTES, 10);
    }

    /** Records a camp at the middle of the arena, with its patrol. The camp goes away at the end. */
    private static MobileGroup placeCampWithPatrol(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        BlockPos middle = helper.absolutePos(MIDDLE);
        PillagerCamp camp = new PillagerCamp("camp_encounter_" + middle.getX() + "_" + middle.getZ(), middle.getX(),
                middle.getY(), middle.getZ(), TERRITORY_RADIUS);
        MinecraftServer server = helper.getLevel().getServer();
        PillagerData.get(server).addCamp(camp);
        MobileGroup patrol = PillagerPatrolGroups.ensure(server, camp).get(0);
        TestSupport.atTestEnd(helper, () -> {
            PillagerData.get(server).patrolsOf(camp.id())
                    .forEach(group -> PatrolEntities.dematerialize(helper.getLevel(), group, false));
            PillagerData.get(server).removeCamp(camp.id());
        });
        return patrol;
    }

    /**
     * A traveling caravan on a route through the middle of the arena, with a merchant, a mule, some guards, 20 bread
     * and a payment of 20. The caravan goes away at the end.
     */
    private static MobileGroup caravan(GameTestHelper helper, int guards) {
        BlockPos start = helper.absolutePos(new BlockPos(1, 1, MIDDLE.getZ()));
        Route route = new Route(List.of(new Route.Point(start.getX(), start.getZ()),
                new Route.Point(start.getX() + 14, start.getZ())));
        MobileGroup group = new MobileGroup(UUID.randomUUID().toString(), GroupKind.CARAVAN, "test_faction",
                "test_origin", "test_destination", route);
        group.setPayment(PAYMENT);
        group.cargo().set("minecraft:bread", BREAD);
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.MERCHANT, 20.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.PACK_ANIMAL, 20.0F));
        for (int i = 0; i < guards; i++) {
            group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, GUARD_HEALTH));
        }
        group.setRouteReady(true);
        group.setState(GroupState.TRAVELING);
        group.setProgress(route.length() / 2);
        MinecraftServer server = helper.getLevel().getServer();
        MobileGroupData.get(server).groups().add(group);
        TestSupport.atTestEnd(helper, () -> {
            GroupMaterializer.keep(group.id(), false);
            GroupMaterializer.dematerialize(helper.getLevel(), group, false);
            MobileGroupData.get(server).groups().remove(group.id());
            PatrolEncounters.forget(group.id());
        });
        return group;
    }

    private static long living(MobileGroup group, MemberRole role) {
        return group.members().stream().filter(member -> member.isAlive() && member.role() == role).count();
    }

    /**
     * The acceptance test of the abstract encounters. A caravan with no entities on a route through the territory
     * meets the patrol of the camp on the abstract tick. Its 2 guards lose to the 5 pillagers: the guards die, some
     * pillagers die, and the caravan keeps half of its cargo and of its payment. The merchant lives and the caravan
     * travels on. A second check in the cooldown gives no second fight.
     */
    @GameTest(template = "arena", batch = "patrol_encounter_abstract")
    public static void anAbstractCaravanInATerritoryMeetsThePatrolOfTheCamp(GameTestHelper helper) {
        configure(helper);
        MinecraftServer server = helper.getLevel().getServer();
        MobileGroup patrol = placeCampWithPatrol(helper);
        MobileGroup caravan = caravan(helper, 2);
        double guardStrength = 2 * PatrolFight.strength(GUARD_HEALTH, WorldConfig.GUARD.attackDamage());
        double patrolStrength = (PATROL_SIZE + SWORN_EXTRA) * PatrolFight.strength(24.0, PILLAGER_DAMAGE);
        PatrolFight expected = PatrolFight.resolve(guardStrength, 2, patrolStrength, PATROL_SIZE + SWORN_EXTRA,
                CARGO_LOSS);
        helper.assertFalse(expected.caravanWins(), "With the test values, the patrol must be stronger");

        int fights = PatrolEncounters.checkAbstract(server, 1);

        helper.assertTrue(fights == 1, "The caravan must meet the patrol one time: " + fights);
        helper.assertTrue(living(caravan, MemberRole.GUARD) == 0, "The guards must die: " + caravan.members());
        long pillagers = patrol.members().stream().filter(GroupMember::isAlive).count();
        helper.assertTrue(pillagers == PATROL_SIZE + SWORN_EXTRA - expected.patrolDeaths() && pillagers > 0,
                "Some pillagers must die: " + pillagers + " alive");
        helper.assertTrue(caravan.cargo().count("minecraft:bread") == BREAD / 2,
                "The caravan must keep half of its cargo: " + caravan.cargo().asMap());
        helper.assertTrue(caravan.payment() == PAYMENT / 2, "The caravan must keep half of its payment");
        helper.assertTrue(caravan.merchant().orElseThrow().isAlive() && caravan.state() == GroupState.TRAVELING,
                "The merchant must live and the caravan must travel: " + caravan);
        helper.assertTrue(PatrolEncounters.checkAbstract(server, 2) == 0 && caravan.members().stream()
                .filter(member -> member.role() == MemberRole.MERCHANT).allMatch(GroupMember::isAlive),
                "A second check in the cooldown must give no fight");
        helper.succeed();
    }

    /**
     * Strong guards beat the patrol in an abstract fight: all pillagers die, and the patrol waits at its camp for new
     * members. The caravan keeps its cargo and loses some guards.
     */
    @GameTest(template = "arena", batch = "patrol_encounter_guards_win")
    public static void strongGuardsBeatTheAbstractPatrol(GameTestHelper helper) {
        configure(helper);
        MinecraftServer server = helper.getLevel().getServer();
        MobileGroup patrol = placeCampWithPatrol(helper);
        MobileGroup caravan = caravan(helper, 4);

        int fights = PatrolEncounters.checkAbstract(server, 1);

        helper.assertTrue(fights == 1, "The caravan must meet the patrol one time: " + fights);
        MobileGroup after = PillagerData.get(server).patrols().byId(patrol.id()).orElseThrow();
        helper.assertTrue(after.state() == GroupState.WAITING
                && after.members().stream().allMatch(GroupMember::isAlive),
                "The beaten patrol must wait at its camp with new members: " + after);
        long guards = living(caravan, MemberRole.GUARD);
        helper.assertTrue(guards > 0 && guards < 4, "The caravan must lose some guards: " + guards);
        helper.assertTrue(caravan.cargo().count("minecraft:bread") == BREAD && caravan.payment() == PAYMENT,
                "The caravan must keep its cargo and its payment");
        helper.succeed();
    }

    /**
     * The acceptance test of the escorts. An escorted caravan with entities in a territory gets the patrol of the
     * camp: the pillagers of the patrol attack the merchant. When the caravan then passes the ambush points of its
     * escort, no ambush spawns: the caravan gets no double attack.
     */
    @GameTest(template = "arena", batch = "patrol_encounter_escort")
    public static void anEscortedCaravanInATerritoryMeetsThePatrolAndNoSecondAmbush(GameTestHelper helper) {
        configure(helper);
        ServerLevel level = helper.getLevel();
        MobileGroup patrol = placeCampWithPatrol(helper);
        MobileGroup caravan = caravan(helper, 1);
        caravan.setProgress(0.0);
        GroupMaterializer.keep(caravan.id(), true);
        helper.assertTrue(GroupMaterializer.materialize(level, caravan), "The caravan must get its entities");
        Entity merchant = level.getEntity(caravan.merchant().orElseThrow().id());
        helper.assertTrue(merchant != null, "The merchant must be in the level");
        Quest quest = new Quest(UUID.randomUUID().toString(), QuestKind.ESCORT, "test_origin", "test_faction",
                caravan.id(), 0, 1, 1, ReputationTier.NEUTRAL, 0, 0);
        quest.setDifficulty(1);
        Escorts.ambushes(level, quest, caravan);
        helper.assertTrue(!Escorts.plan(quest, caravan).isEmpty(), "The escort must have an ambush on its route");

        PatrolEncounters.checkMaterialized(level);

        helper.assertTrue(PatrolEntities.isMaterialized(patrol), "The patrol must get its entities");
        List<UUID> entities = List.copyOf(PatrolEntities.entities(patrol.id()));
        helper.assertTrue(entities.size() == PATROL_SIZE + SWORN_EXTRA,
                "All members of the patrol must come: " + entities.size());
        for (UUID id : entities) {
            helper.assertTrue(level.getEntity(id) instanceof Pillager pillager && pillager.getTarget() == merchant,
                    "Each pillager must attack the merchant");
        }

        caravan.setProgress(caravan.route().length());
        Escorts.ambushes(level, quest, caravan);
        PatrolEncounters.checkMaterialized(level);

        List<Mob> ambush = level.getEntitiesOfClass(Mob.class, new AABB(helper.absolutePos(MIDDLE)).inflate(64),
                mob -> mob.getPersistentData().getBoolean(Escorts.AMBUSH_TAG));
        ambush.forEach(Entity::discard);
        helper.assertTrue(ambush.isEmpty(), "No ambush must spawn after the patrol: " + ambush.size());
        helper.assertTrue(PatrolEntities.entities(patrol.id()).size() == entities.size(),
                "The patrol must not come a second time");
        helper.succeed();
    }
}
