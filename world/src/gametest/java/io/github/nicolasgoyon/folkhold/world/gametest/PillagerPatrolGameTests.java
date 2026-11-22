package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerData;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerPatrolGroups;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerTerritories;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the abstract patrols of the pillager camps. See {@link TestSupport} for the templates and the
 * helpers.
 *
 * <p>The GameTest world has no structures. Each test records a camp at the middle of its arena, and removes it at the
 * end. Each test has its own batch.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PillagerPatrolGameTests {
    private static final BlockPos MIDDLE = new BlockPos(8, 1, 8);
    private static final int TERRITORY_RADIUS = 3;
    private static final int PATROL_SIZE = 3;
    private static final int SWORN_EXTRA = 2;
    private static final int BLOCKS_PER_TICK = 40;

    private PillagerPatrolGameTests() {
    }

    /** Records a camp at the middle of the arena. The camp and its patrols go away at the end. */
    private static PillagerCamp placeCamp(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        BlockPos middle = helper.absolutePos(MIDDLE);
        PillagerCamp camp = new PillagerCamp("camp_patrol_" + middle.getX() + "_" + middle.getZ(), middle.getX(),
                middle.getY(), middle.getZ(), TERRITORY_RADIUS);
        MinecraftServer server = helper.getLevel().getServer();
        PillagerData.get(server).addCamp(camp);
        TestSupport.atTestEnd(helper, () -> PillagerData.get(server).removeCamp(camp.id()));
        return camp;
    }

    /**
     * A camp gets its abstract patrols: 2 patrols of 5 pillagers (the patrol size and the members for a sworn enemy),
     * at the two ends of a diameter of a loop in the territory. The patrols move on each abstract tick, also with no
     * player near, and start the loop again at its end. The save keeps them. When the camp goes away, the patrols go
     * away.
     */
    @GameTest(template = "arena", batch = "pillager_patrol_loop")
    public static void aCampHasAbstractPatrolsOnALoopInItsTerritory(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_PATROLS_PER_CAMP, 2);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_PATROL_SIZE, PATROL_SIZE);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_SWORN_ENEMY_EXTRA, SWORN_EXTRA);
        TestSupport.setConfigForTest(helper, WorldConfig.GROUP_BLOCKS_PER_TICK, BLOCKS_PER_TICK);
        MinecraftServer server = helper.getLevel().getServer();
        PillagerCamp camp = placeCamp(helper);

        List<MobileGroup> patrols = PillagerPatrolGroups.ensure(server, camp);
        helper.assertTrue(patrols.size() == 2, "The camp must have 2 patrols: " + patrols);
        for (MobileGroup patrol : patrols) {
            helper.assertTrue(patrol.kind() == GroupKind.PILLAGER_PATROL, "The kind must be pillager_patrol");
            helper.assertTrue(patrol.originId().equals(camp.id()) && patrol.destinationId().equals(camp.id()),
                    "The origin and the destination must be the camp: " + patrol);
            helper.assertTrue(patrol.state() == GroupState.TRAVELING, "The patrol must travel: " + patrol);
            helper.assertTrue(patrol.members().size() == PATROL_SIZE + SWORN_EXTRA
                    && patrol.members().stream().allMatch(GroupMember::isAlive),
                    "The patrol must have " + (PATROL_SIZE + SWORN_EXTRA) + " living members: " + patrol.members());
            List<Route.Point> points = patrol.route().points();
            helper.assertTrue(points.get(0).equals(points.get(points.size() - 1)),
                    "The route must end at its first point: " + points);
            for (Route.Point point : points) {
                Optional<PillagerCamp> owner = PillagerTerritories.campAt(server,
                        new BlockPos(point.x(), camp.y(), point.z()));
                helper.assertTrue(owner.isPresent() && owner.get().id().equals(camp.id()),
                        "Each point of the route must be in the territory of the camp: " + point);
            }
        }
        double length = patrols.get(0).route().length();
        double gap = Math.abs(patrols.get(1).progress() - patrols.get(0).progress());
        helper.assertTrue(Math.abs(gap - length / 2) < 1.0,
                "The patrols must be at equal distances on the loop: " + gap + " of " + length);
        helper.assertTrue(PillagerPatrolGroups.ensure(server, camp).size() == 2,
                "A second call must not add patrols");

        MobileGroup first = patrols.get(0);
        double start = first.progress();
        PillagerPatrolGroups.update(server, 1);
        helper.assertTrue(Math.abs(first.progress() - (start + BLOCKS_PER_TICK)) < 1e-6,
                "The patrol must move " + BLOCKS_PER_TICK + " blocks on an abstract tick: " + first.progress());
        boolean restarted = false;
        double before = first.progress();
        for (long tick = 2; tick < 2 + Math.ceil(length / BLOCKS_PER_TICK) + 1; tick++) {
            PillagerPatrolGroups.update(server, tick);
            restarted |= first.progress() < before;
            before = first.progress();
        }
        PillagerData data = PillagerData.get(server);
        helper.assertTrue(restarted, "The patrol must start the loop again at its end");
        helper.assertTrue(data.patrols().byId(first.id()).filter(patrol -> patrol == first).isPresent()
                && first.state() == GroupState.TRAVELING, "The patrol must stay after its loop: " + first);

        CompoundTag tag = data.save(new CompoundTag(), server.registryAccess());
        List<MobileGroup> loaded = PillagerData.load(tag, server.registryAccess()).patrolsOf(camp.id());
        helper.assertTrue(loaded.size() == 2, "The save must keep the 2 patrols: " + loaded);
        MobileGroup copy = loaded.stream().filter(patrol -> patrol.id().equals(first.id())).findFirst()
                .orElseThrow();
        helper.assertTrue(copy.kind() == GroupKind.PILLAGER_PATROL
                && Math.abs(copy.progress() - first.progress()) < 1e-6
                && copy.route().points().equals(first.route().points())
                && copy.members().get(0).id().equals(first.members().get(0).id()),
                "The save must keep the kind, the route, the progress and the members: " + copy);

        data.removeCamp(camp.id());
        PillagerPatrolGroups.update(server, 100);
        helper.assertTrue(data.patrolsOf(camp.id()).isEmpty(), "The patrols must go away with their camp");
        helper.succeed();
    }
}
