package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.village.VillageHearts;
import io.github.nicolasgoyon.folkhold.world.village.VillageRaids;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the village of a raid (see {@link VillageRaids#heartOf}). */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RaidHeartGameTests {
    /** The heart of the village where the raid starts. */
    private static final BlockPos HOME_HEART = new BlockPos(8, 1, 28);
    /** The heart of the other village, 32 blocks to the east. */
    private static final BlockPos OTHER_HEART = new BlockPos(40, 1, 28);
    /** The size of the arena, in blocks. */
    private static final int ARENA = 56;
    /** The number of claimed beds near the other heart. */
    private static final int OTHER_BEDS = 12;
    /** The hearts of earlier tests this far from the test are removed, in blocks. */
    private static final double EARLIER_HEARTS_RADIUS = 160.0;

    private RaidHeartGameTests() {
    }

    /**
     * Two hearts near each other: the raid goes to the village where it starts. A player starts a raid at the heart of
     * a village. The vanilla center of the raid is the middle of the claimed points of interest near the player. Most
     * of these points are beds near the other heart, thus the vanilla center is nearer to the other heart. The raid
     * stays at the village of its start.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 40, batch = "raid_heart_two_villages")
    public static void raidGoesToTheVillageOfItsStart(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        ServerLevel level = helper.getLevel();
        BlockPos home = helper.absolutePos(HOME_HEART);
        BlockPos other = helper.absolutePos(OTHER_HEART);
        TestSupport.removeEarlierHearts(helper, home, EARLIER_HEARTS_RADIUS);
        List<BlockPos> beds = new ArrayList<>();
        beds.add(placeBed(helper, new BlockPos(8, 1, 20)));
        for (int i = 0; i < OTHER_BEDS; i++) {
            beds.add(placeBed(helper, new BlockPos(34 + 2 * (i % 6), 1, i < 6 ? 20 : 36)));
        }
        for (BlockPos bed : beds) {
            helper.assertTrue(level.getPoiManager().take(type -> type.is(PoiTypes.HOME), (type, pos) -> pos.equals(bed),
                    bed, 1).isPresent(), "The bed at " + bed.toShortString() + " must have a free ticket");
        }
        helper.setBlock(HOME_HEART, ModBlocks.VILLAGE_HEART.get());
        helper.setBlock(OTHER_HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHearts.register(level, home);
        VillageHearts.register(level, other);
        Raid[] raid = new Raid[1];
        TestSupport.atTestEnd(helper, () -> {
            if (raid[0] != null) {
                raid[0].stop();
            }
            beds.forEach(bed -> level.getPoiManager().release(bed));
            VillageHearts.unregister(level, home);
            VillageHearts.unregister(level, other);
            helper.setBlock(HOME_HEART, Blocks.AIR);
            helper.setBlock(OTHER_HEART, Blocks.AIR);
        });
        ServerPlayer player = TestSupport.mockPlayer(helper);
        raid[0] = level.getRaids().createOrExtendRaid(player, home);
        level.getServer().getPlayerList().remove(player);
        helper.assertTrue(raid[0] != null, "The test must start a raid");
        BlockPos center = raid[0].getCenter();
        helper.assertTrue(center.distSqr(other) < center.distSqr(home), "The vanilla center of the raid must be "
                + "nearer to the other heart: center " + center.toShortString() + ", heart " + home.toShortString()
                + ", other heart " + other.toShortString());
        Optional<BlockPos> heart = VillageRaids.heartOf(raid[0], level);
        helper.assertTrue(heart.equals(Optional.of(home)), "The raid must go to the village where it starts: heart "
                + heart.map(BlockPos::toShortString).orElse("none") + ", expected " + home.toShortString());
        helper.succeed();
    }

    /** Places a bed along the X axis with its head at a position, to the east, and gives the position of its head. */
    private static BlockPos placeBed(GameTestHelper helper, BlockPos head) {
        helper.setBlock(head.west(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD));
        return helper.absolutePos(head);
    }
}
