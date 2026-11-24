package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MemberRole;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.group.GroupBoats;
import io.github.nicolasgoyon.folkhold.world.group.GroupMaterializer;
import io.github.nicolasgoyon.folkhold.world.group.MobileGroupData;
import io.github.nicolasgoyon.folkhold.world.group.SmallShips;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the boats of the mobile groups. The arena has a pool of water between two banks. See
 * {@link TestSupport} for the templates.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BoatGameTests {
    private static final BlockPos START = new BlockPos(1, 2, 8);
    /** The water is between these X values, and between Z 3 and Z 13. */
    private static final int WATER_MIN_X = 4;
    private static final int WATER_MAX_X = 12;

    private BoatGameTests() {
    }

    /** A floor, two banks of stone at Y 1, and water at Y 1 between them. The banks close the pool. */
    private static void buildPool(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        for (int x = 0; x < TestSupport.ARENA_SIZE; x++) {
            for (int z = 0; z < TestSupport.ARENA_SIZE; z++) {
                boolean water = x >= WATER_MIN_X && x <= WATER_MAX_X && z >= 3 && z <= 13;
                helper.setBlock(new BlockPos(x, 1, z), water ? Blocks.WATER : Blocks.STONE);
            }
        }
    }

    /**
     * The merchant and the guards cross the pool in boats, the mule swims. At the end of the water segment, the
     * members get out on the other bank, and the boats disappear.
     */
    @GameTest(template = "arena", timeoutTicks = 600, batch = "group_boats")
    public static void caravanCrossesWaterInBoats(GameTestHelper helper) {
        buildPool(helper);
        BlockPos start = helper.absolutePos(START);
        Route route = new Route(List.of(new Route.Point(start.getX(), start.getZ()),
                new Route.Point(start.getX() + 3, start.getZ()), new Route.Point(start.getX() + 11, start.getZ()),
                new Route.Point(start.getX() + 30, start.getZ())),
                List.of(Route.Surface.LAND, Route.Surface.WATER, Route.Surface.LAND));
        MobileGroup group = new MobileGroup(UUID.randomUUID().toString(), GroupKind.CARAVAN, "test_faction",
                "test_origin", "test_destination", route);
        group.setState(GroupState.TRAVELING);
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.MERCHANT, 20.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.PACK_ANIMAL, 20.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, 30.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, 30.0F));
        MobileGroupData.get(helper.getLevel().getServer()).groups().add(group);
        GroupMaterializer.keep(group.id(), true);
        GroupMaterializer.materialize(helper.getLevel(), group);
        Entity merchant = helper.getLevel().getEntity(group.members().get(0).id());
        Entity guard = helper.getLevel().getEntity(group.members().get(2).id());
        AtomicBoolean crossed = new AtomicBoolean();
        AABB arena = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(TestSupport.ARENA_SIZE, 8,
                TestSupport.ARENA_SIZE);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(GroupBoats.isCrossing(group.id())
                        && merchant.getVehicle() instanceof Boat && guard.getVehicle() instanceof Boat,
                        "The merchant and the guard must get into boats"))
                .thenExecute(() -> crossed.set(true))
                .thenWaitUntil(() -> helper.assertTrue(!GroupBoats.isCrossing(group.id()),
                        "The crossing must end. Merchant at " + merchant.position()))
                .thenExecute(() -> {
                    boolean onShore = !merchant.isPassenger() && !guard.isPassenger()
                            && merchant.getX() >= start.getX() + WATER_MAX_X - START.getX();
                    int boats = helper.getLevel().getEntitiesOfClass(Boat.class, arena).size();
                    GroupMaterializer.keep(group.id(), false);
                    GroupMaterializer.dematerialize(helper.getLevel(), group, false);
                    MobileGroupData.get(helper.getLevel().getServer()).groups().remove(group.id());
                    helper.assertTrue(crossed.get(), "The group must cross in boats");
                    helper.assertTrue(onShore, "The members must get out on the other bank: " + merchant.position());
                    helper.assertTrue(boats == 0, "The boats must disappear: " + boats);
                })
                .thenSucceed();
    }

    /**
     * With Small Ships: the group crosses the pool in one ship, with the merchant, the guards and the mule on board.
     * At the end of the water segment, the members get out on the other bank, and the ship disappears. With no Small
     * Ships, this test does not exist: the test above covers the vanilla boats.
     */
    @GameTestGenerator
    public static List<TestFunction> smallShipCrossings() {
        if (!SmallShips.isLoaded()) {
            return List.of();
        }
        return List.of(new TestFunction("group_ships", "boatgametests.caravancrossesinonesmallship",
                FolkholdWorld.MOD_ID + ":arena", Rotation.NONE, 600, 0L, true, false, 1, 1, false,
                BoatGameTests::caravanCrossesInOneShip));
    }

    private static void caravanCrossesInOneShip(GameTestHelper helper) {
        buildPool(helper);
        BlockPos start = helper.absolutePos(START);
        Route route = new Route(List.of(new Route.Point(start.getX(), start.getZ()),
                new Route.Point(start.getX() + 3, start.getZ()), new Route.Point(start.getX() + 11, start.getZ()),
                new Route.Point(start.getX() + 30, start.getZ())),
                List.of(Route.Surface.LAND, Route.Surface.WATER, Route.Surface.LAND));
        MobileGroup group = new MobileGroup(UUID.randomUUID().toString(), GroupKind.CARAVAN, "test_faction",
                "test_origin", "test_destination", route);
        group.setState(GroupState.TRAVELING);
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.MERCHANT, 20.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.PACK_ANIMAL, 20.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, 30.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, 30.0F));
        MobileGroupData.get(helper.getLevel().getServer()).groups().add(group);
        GroupMaterializer.keep(group.id(), true);
        GroupMaterializer.materialize(helper.getLevel(), group);
        List<Entity> members = group.members().stream().map(member -> helper.getLevel().getEntity(member.id()))
                .toList();
        Entity merchant = members.get(0);
        AABB arena = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(TestSupport.ARENA_SIZE, 8,
                TestSupport.ARENA_SIZE);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    Entity ship = merchant.getVehicle();
                    helper.assertTrue(ship != null && BuiltInRegistries.ENTITY_TYPE.getKey(ship.getType())
                            .getNamespace().equals(SmallShips.MOD_ID), "The merchant must get into a ship: " + ship);
                    helper.assertTrue(members.stream().allMatch(member -> member.getVehicle() == ship),
                            "All the members must be in the ship: " + members.stream()
                                    .map(member -> String.valueOf(member.getVehicle())).toList());
                })
                .thenWaitUntil(() -> helper.assertTrue(!GroupBoats.isCrossing(group.id()),
                        "The crossing must end. Merchant at " + merchant.position()))
                .thenExecute(() -> {
                    boolean onShore = members.stream().noneMatch(Entity::isPassenger)
                            && merchant.getX() >= start.getX() + WATER_MAX_X - START.getX();
                    int boats = helper.getLevel().getEntitiesOfClass(Boat.class, arena).size();
                    GroupMaterializer.keep(group.id(), false);
                    GroupMaterializer.dematerialize(helper.getLevel(), group, false);
                    MobileGroupData.get(helper.getLevel().getServer()).groups().remove(group.id());
                    helper.assertTrue(onShore, "The members must get out on the other bank: " + merchant.position());
                    helper.assertTrue(boats == 0, "The ship must disappear: " + boats);
                })
                .thenSucceed();
    }
}
