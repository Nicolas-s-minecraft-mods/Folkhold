package io.github.nicolasgoyon.folkhold.world.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.nicolasgoyon.folkhold.world.admin.AdminActions;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The debug commands of the world mod (permission level 2), for the visual checks of the playtests:
 *
 * <ul>
 *   <li>{@code /folkhold debug buildings <style> [variant]}: a grid of the buildings of a style and a biome variant
 *       near the source. One row for each building, one column for each step (see {@link DebugBuildings}). The
 *       default variant is {@value DebugBuildings#DEFAULT_VARIANT}. The style {@value DebugBuildings#CAVE} has no
 *       variant.</li>
 *   <li>{@code /folkhold debug buildings compare <building>}: one row with the template of this name in each style
 *       and each biome variant, for example {@code job_baker} or {@code job_baker_step2}.</li>
 * </ul>
 *
 * <p>The grid changes the blocks of its box with no undo: use it in a test world. One grid runs at a time.
 */
public final class DebugCommands {
    private static final String KEY = "commands.folkhold_world.debug.buildings.";

    private DebugCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("folkhold")
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(AdminActions.PERMISSION_LEVEL))
                        .then(Commands.literal("buildings")
                                .then(Commands.literal("compare")
                                        .then(Commands.argument("building", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        DebugBuildings.buildingNames(context.getSource().getLevel()
                                                                .getStructureManager().listTemplates()), builder))
                                                .executes(DebugCommands::compare)))
                                .then(Commands.argument("style", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                DebugBuildings.STYLES, builder))
                                        .executes(context -> style(context, DebugBuildings.DEFAULT_VARIANT))
                                        .then(Commands.argument("variant", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        DebugBuildings.VARIANTS, builder))
                                                .executes(context -> style(context,
                                                        StringArgumentType.getString(context, "variant"))))))));
    }

    private static int style(CommandContext<CommandSourceStack> context, String variant) {
        CommandSourceStack source = context.getSource();
        String style = StringArgumentType.getString(context, "style");
        if (!DebugBuildings.STYLES.contains(style)) {
            source.sendFailure(Component.translatable(KEY + "unknown_style", style,
                    String.join(", ", DebugBuildings.STYLES)));
            return 0;
        }
        if (!DebugBuildings.VARIANTS.contains(variant)
                || (style.equals(DebugBuildings.CAVE) && !variant.equals(DebugBuildings.DEFAULT_VARIANT))) {
            source.sendFailure(Component.translatable(KEY + "unknown_variant", variant, style));
            return 0;
        }
        String folder = DebugBuildings.folder(style, variant);
        List<Map<Integer, ResourceLocation>> rows = DebugBuildings.styleRows(source.getLevel().getStructureManager()
                .listTemplates(), folder);
        return start(source, rows, folder);
    }

    private static int compare(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String building = StringArgumentType.getString(context, "building");
        List<Map<Integer, ResourceLocation>> rows = DebugBuildings.compareRow(source.getLevel().getStructureManager()
                .listTemplates(), building);
        return start(source, rows, building);
    }

    private static int start(CommandSourceStack source, List<Map<Integer, ResourceLocation>> rows, String what) {
        if (rows.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "no_templates", what));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos origin = DebugBuildings.originNear(BlockPos.containing(source.getPosition()));
        boolean started = DebugBuildings.start(level, origin, rows,
                message -> source.sendSuccess(() -> message, false),
                (end, result) -> {
                    BoundingBox box = result.box();
                    switch (end) {
                        case PLACED -> source.sendSuccess(() -> Component.translatable(KEY + "success",
                                result.cells().size(), what, box.minX(), box.minY(), box.minZ(), box.maxX(),
                                box.maxY(), box.maxZ()), true);
                        case TIMEOUT -> source.sendFailure(Component.translatable(KEY + "timeout",
                                DebugBuildings.TIMEOUT_SECONDS));
                    }
                });
        if (!started) {
            source.sendFailure(Component.translatable(KEY + "busy"));
            return 0;
        }
        int templates = rows.stream().mapToInt(Map::size).sum();
        source.sendSuccess(() -> Component.translatable(KEY + "started", templates, what, rows.size()), false);
        return templates;
    }
}
