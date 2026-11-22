package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.cartofold.api.LayerContent;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.admin.AdminActions;
import io.github.nicolasgoyon.folkhold.world.admin.AdminMap;
import io.github.nicolasgoyon.folkhold.world.group.MobileGroupData;
import io.github.nicolasgoyon.folkhold.world.network.AdminActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the work of the admin map (see {@link AdminMap}) and of the teleport to a group of the admin map.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdminMapWorkGameTests {
    /** The number of operators with the admin map in the test of the work. */
    private static final int OPERATORS = 2;
    /** The number of checks of the admin map in the test of the work. The time comes from the second half. */
    private static final int CHECKS = 20;
    /** The Z of the routes of the groups of the test of the work: far from the other tests. */
    private static final int FAR_Z = 26_000_000;

    private AdminMapWorkGameTests() {
    }

    /**
     * With more settlements and groups than the limit of the markers of a layer, a check with no change does not send
     * the layer again: the layer of each operator stays the same. The layer has the limit of markers. The log line
     * {@code Admin map work:} gives the mean time of the last half of the checks, and the number of layers that
     * changed.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "admin_map_work")
    public static void manyMarkersDoNotSendTheLayerAtEachCheck(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MobileGroupData data = MobileGroupData.get(server);
        List<MobileGroup> groups = new ArrayList<>();
        for (int i = 0; i < LayerContent.MAX_ELEMENTS + 100; i++) {
            groups.add(group(i * 16, FAR_Z));
        }
        groups.forEach(group -> data.groups().add(group));
        List<ServerPlayer> operators = new ArrayList<>();
        for (int i = 0; i < OPERATORS; i++) {
            ServerPlayer operator = operator(helper);
            operators.add(operator);
            AdminActions.handle(operator, new AdminActionPayload("map", "", 0));
        }
        List<LayerContent> first = operators.stream().map(player -> AdminMap.shown(player).orElseThrow()).toList();
        int changed = 0;
        long start = 0L;
        for (int i = 0; i < CHECKS; i++) {
            if (i == CHECKS / 2) {
                start = System.nanoTime();
            }
            List<LayerContent> before = operators.stream().map(player -> AdminMap.shown(player).orElseThrow())
                    .toList();
            AdminMap.check(server);
            for (int j = 0; j < OPERATORS; j++) {
                if (AdminMap.shown(operators.get(j)).orElseThrow() != before.get(j)) {
                    changed++;
                }
            }
        }
        long nanos = System.nanoTime() - start;
        FolkholdWorld.LOGGER.info("Admin map work: {} groups, {} operators, {} checks, {} ms for each of the last {} "
                + "checks, {} layers changed", groups.size(), OPERATORS, CHECKS, nanos / 1_000_000.0 / (CHECKS / 2),
                CHECKS / 2, changed);
        groups.forEach(group -> data.groups().remove(group.id()));
        operators.forEach(player -> logOut(server, player));

        helper.assertTrue(first.get(0).markers().size() == LayerContent.MAX_ELEMENTS,
                "The layer must have the limit of markers: " + first.get(0).markers().size());
        helper.assertTrue(changed == 0, "A check with no change must keep the layers: " + changed + " changed");
        helper.succeed();
    }

    /** A teleport to a group that does not exist gives the result {@code group_unknown}, and the player stays. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "admin_teleport_group")
    public static void teleportToAnUnknownGroupIsRefused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = operator(helper);
        BlockPos start = player.blockPosition();

        AdminActions.Result result = AdminActions.teleportToGroup(player, UUID.randomUUID().toString());

        boolean unknown = result.message().getContents() instanceof TranslatableContents contents
                && contents.getKey().endsWith("group_unknown");
        BlockPos after = player.blockPosition();
        logOut(server, player);
        helper.assertTrue(!result.success() && unknown, "The result must be group_unknown: " + result.message());
        helper.assertTrue(after.equals(start), "The player must stay at " + start + ", not " + after);
        helper.succeed();
    }

    /** A teleport to a known group puts the player at the position of the group, on the surface. */
    @GameTest(template = "empty", timeoutTicks = TestSupport.WORKER_TICKS, batch = "admin_teleport_group")
    public static void teleportToAGroupMovesThePlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = operator(helper);
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        MobileGroup group = group(target.getX(), target.getZ());
        MobileGroupData.get(server).groups().add(group);
        int x = (int) Math.floor(group.position().x());
        int z = (int) Math.floor(group.position().z());

        AdminActions.Result result = AdminActions.teleportToGroup(player, group.id());

        helper.assertTrue(result.success(), "The teleport must start: " + result.message());
        TestSupport.thenWaitForWorker(helper, helper.startSequence(),
                () -> player.blockPosition().getX() == x && player.blockPosition().getZ() == z,
                () -> "The player must be at " + x + " " + z + ", not " + player.blockPosition())
                .thenExecute(() -> {
                    MobileGroupData.get(server).groups().remove(group.id());
                    logOut(server, player);
                })
                .thenSucceed();
    }

    /** A caravan on a straight route of 10 blocks to the east, at its start. */
    private static MobileGroup group(int x, int z) {
        return new MobileGroup(UUID.randomUUID().toString(), GroupKind.CARAVAN, "test_faction", "test_origin",
                "test_destination", new Route(List.of(new Route.Point(x, z), new Route.Point(x + 10, z))));
    }

    /** A mock player who is an operator. {@link #logOut} removes the player and the operator entry. */
    private static ServerPlayer operator(GameTestHelper helper) {
        ServerPlayer player = TestSupport.mockPlayer(helper);
        helper.getLevel().getServer().getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(),
                AdminActions.PERMISSION_LEVEL, false));
        return player;
    }

    private static void logOut(MinecraftServer server, ServerPlayer player) {
        server.getPlayerList().getOps().remove(player.getGameProfile());
        server.getPlayerList().remove(player);
    }
}
