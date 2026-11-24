package io.github.nicolasgoyon.folkhold.world.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.world.admin.AdminActions;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerPatrolGroups;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Commands of the pillager faction (operators only):
 * <ul>
 *   <li>{@code /folkhold pillagers patrols}: the abstract patrols of the camps, with their state, their position on
 *       their loop and their living members.</li>
 * </ul>
 */
public final class PillagerCommands {
    private static final String KEY = "commands.folkhold_world.pillagers.";

    private PillagerCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("folkhold")
                .then(Commands.literal("pillagers")
                        .requires(source -> source.hasPermission(AdminActions.PERMISSION_LEVEL))
                        .then(Commands.literal("patrols").executes(PillagerCommands::listPatrols))));
    }

    private static int listPatrols(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<MobileGroup> patrols = PillagerPatrolGroups.all(source.getServer());
        source.sendSuccess(() -> Component.translatable(KEY + "patrols.header", patrols.size()), false);
        for (MobileGroup patrol : patrols) {
            Route.Position position = patrol.position();
            long alive = patrol.members().stream().filter(GroupMember::isAlive).count();
            source.sendSuccess(() -> Component.translatable(KEY + "patrols.entry", patrol.id(),
                    Component.translatable("commands.folkhold_world.caravan.state." + patrol.state().serializedName()),
                    Math.round(patrol.progress()), Math.round(patrol.route().length()), Math.round(position.x()),
                    Math.round(position.z()), alive, patrol.members().size()), false);
        }
        return patrols.size();
    }
}
