package io.github.nicolasgoyon.folkhold.world.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobSkills;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the commands of the abstract simulation.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CommandGameTests {
    /** Grid of the test positions in blocks, so that the test settlements are far from each other. */
    private static final int GRID = 256;

    private CommandGameTests() {
    }

    private static void run(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }

    /** The commands set the level and a stock, and run a cycle. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "commands_settlement")
    public static void commandsChangeTheSettlement(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String faction = FactionData.get(server).factions().get(0).id();
        int x = 800 * GRID + GRID / 2;
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), x >> 4, 0, faction, "Commandton", x,
                100);
        settlement.setBiome("minecraft:plains");
        SettlementData.get(server).registry().add(settlement);

        run(server, "folkhold settlement level " + settlement.id() + " 7");
        run(server, "folkhold settlement stock " + settlement.id() + " minecraft:emerald_block 33");
        int stock = settlement.economy().stock().count("minecraft:emerald_block");
        run(server, "folkhold sim cycle 1");

        helper.assertTrue(settlement.level() >= 6, "The level must be 7, then lose at most one level, not "
                + settlement.level());
        helper.assertTrue(stock == 33, "The stock must be 33, not " + stock);
        helper.assertTrue(settlement.economy().isInitialized(), "The cycle must start the economy of the settlement");
        helper.succeed();
    }

    /**
     * The skill commands set the experience of the post with its job sign at a position. A position with no post and
     * a job with no active job slot change nothing.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "commands_skill")
    public static void skillCommandsSetTheExperienceOfAPost(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String faction = FactionData.get(server).factions().get(0).id();
        int x = 801 * GRID + GRID / 2;
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), x >> 4, 0, faction, "Skillton", x, 100);
        settlement.setBiome("minecraft:plains");
        JobPosts.Post farm = new JobPosts.Post("folkhold_world:farmer", x, 64, 100, 0);
        JobPosts.Post empty = new JobPosts.Post("", x + 4, 64, 100, 0);
        settlement.setJobPosts(Optional.of(List.of(farm, empty)));
        SettlementData.get(server).registry().add(settlement);

        run(server, "folkhold settlement skill " + settlement.id() + " post " + x + " 64 100 150");
        int set = settlement.jobSkills().experience(farm);
        run(server, "folkhold settlement skill " + settlement.id() + " post " + (x + 4) + " 64 100 70");
        run(server, "folkhold settlement skill " + settlement.id() + " post " + (x + 1) + " 64 100 70");
        run(server, "folkhold settlement skill " + settlement.id() + " slot folkhold_world:mason 70");
        int shown = runCount(server, "folkhold settlement skill " + settlement.id());

        helper.assertTrue(set == 150, "The post must have 150 experience, not " + set);
        helper.assertTrue(JobSkills.level(set) == 4, "150 experience must give the level Expert");
        helper.assertTrue(settlement.jobSkills().posts().size() == 1,
                "Only the post with a job must have a skill: " + settlement.jobSkills().posts());
        helper.assertTrue(settlement.jobSkills().slotExperience("folkhold_world:mason") == 0,
                "A job with no active job slot must keep 0 experience");
        helper.assertTrue(shown >= 1, "The command must show the post: " + shown);
        helper.succeed();
    }

    /** Runs a command and gives its result, or -1 for a command that is not valid. */
    private static int runCount(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException exception) {
            return -1;
        }
    }
}
