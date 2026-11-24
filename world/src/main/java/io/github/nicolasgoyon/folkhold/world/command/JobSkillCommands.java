package io.github.nicolasgoyon.folkhold.world.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobSkills;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.admin.AdminActions;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.village.VillageLife;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Commands for the skill of the job posts of a settlement (permission level 2). They help the tuning of the options of
 * the skill (see {@link JobSkills}).
 *
 * <ul>
 *   <li>{@code /folkhold settlement skill <id>}: the experience and the skill level of each post with a job, then of
 *       each active job slot with no post.</li>
 *   <li>{@code /folkhold settlement skill <id> post <pos> <experience>}: sets the experience of the post with its job
 *       sign at the position.</li>
 *   <li>{@code /folkhold settlement skill <id> slot <job> <experience>}: sets the experience of the job slots of a job
 *       id with no post.</li>
 * </ul>
 *
 * <p>The loaded workers get the new skill level at the next check of the heart of their village.
 */
final class JobSkillCommands {
    private static final String KEY = "commands.folkhold_world.skill.";
    /** The highest experience that the commands set. */
    static final int MAX_EXPERIENCE = 1_000_000;
    private static final IntegerArgumentType EXPERIENCE = IntegerArgumentType.integer(0, MAX_EXPERIENCE);

    private JobSkillCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("folkhold")
                .then(Commands.literal("settlement")
                        .requires(source -> source.hasPermission(AdminActions.PERMISSION_LEVEL))
                        .then(Commands.literal("skill")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(SettlementCommands.SETTLEMENT_IDS)
                                        .executes(JobSkillCommands::show)
                                        .then(Commands.literal("post")
                                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("experience", EXPERIENCE)
                                                                .executes(JobSkillCommands::setPost))))
                                        .then(Commands.literal("slot")
                                                .then(Commands.argument("job", ResourceLocationArgument.id())
                                                        .suggests((context, builder) -> SharedSuggestionProvider
                                                                .suggest(settlement(context)
                                                                        .map(JobSkillCommands::slotJobs)
                                                                        .orElse(Set.of()), builder))
                                                        .then(Commands.argument("experience", EXPERIENCE)
                                                                .executes(JobSkillCommands::setSlot))))))));
    }

    private static Optional<Settlement> settlement(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "id");
        return SettlementData.get(context.getSource().getServer()).registry().byId(id);
    }

    /** The posts of a settlement with a job, in the order of the posts. */
    private static List<JobPosts.Post> posts(Settlement settlement) {
        return settlement.jobPosts().orElse(List.of()).stream().filter(post -> !post.jobId().isEmpty()).toList();
    }

    /** The job ids of the active job slots with no post of a settlement. */
    private static Set<String> slotJobs(Settlement settlement) {
        return new LinkedHashSet<>(Economies.activeSlots(settlement).slotJobs());
    }

    /** The value of a row: the skill level and the experience. */
    private static Component skill(int experience) {
        return Component.translatable(KEY + "value", Component.translatable("merchant.level."
                + JobSkills.level(experience)), experience);
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<Settlement> found = settlement(context);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("commands.folkhold_world.settlement.unknown"));
            return 0;
        }
        Settlement settlement = found.get();
        List<JobPosts.Post> posts = posts(settlement);
        Set<String> slots = slotJobs(settlement);
        source.sendSuccess(() -> Component.translatable(KEY + "header", settlement.name()), false);
        if (posts.isEmpty() && slots.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "none"), false);
        }
        for (JobPosts.Post post : posts) {
            int experience = settlement.jobSkills().experience(post);
            source.sendSuccess(() -> Component.translatable(KEY + "post", VillageLife.jobName(post.jobId()),
                    post.x(), post.y(), post.z(), skill(experience)), false);
        }
        for (String jobId : slots) {
            int experience = settlement.jobSkills().slotExperience(jobId);
            source.sendSuccess(() -> Component.translatable(KEY + "slot", VillageLife.jobName(jobId), jobId,
                    skill(experience)), false);
        }
        return posts.size() + slots.size();
    }

    private static int setPost(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Optional<Settlement> found = settlement(context);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("commands.folkhold_world.settlement.unknown"));
            return 0;
        }
        Settlement settlement = found.get();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        int experience = IntegerArgumentType.getInteger(context, "experience");
        Optional<JobPosts.Post> post = posts(settlement).stream()
                .filter(candidate -> candidate.x() == pos.getX() && candidate.y() == pos.getY()
                        && candidate.z() == pos.getZ())
                .findFirst();
        if (post.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "no_post", settlement.name(), pos.getX(), pos.getY(),
                    pos.getZ()));
            return 0;
        }
        settlement.jobSkills().set(JobSkills.Place.of(post.get()), post.get().jobId(), experience);
        SettlementData.get(source.getServer()).setDirty();
        source.sendSuccess(() -> Component.translatable(KEY + "post.set", settlement.name(),
                VillageLife.jobName(post.get().jobId()), pos.getX(), pos.getY(), pos.getZ(), skill(experience)),
                true);
        return 1;
    }

    private static int setSlot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<Settlement> found = settlement(context);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("commands.folkhold_world.settlement.unknown"));
            return 0;
        }
        Settlement settlement = found.get();
        String jobId = ResourceLocationArgument.getId(context, "job").toString();
        int experience = IntegerArgumentType.getInteger(context, "experience");
        if (!slotJobs(settlement).contains(jobId)) {
            source.sendFailure(Component.translatable(KEY + "no_slot", settlement.name(), jobId));
            return 0;
        }
        settlement.jobSkills().setSlot(jobId, experience);
        SettlementData.get(source.getServer()).setDirty();
        source.sendSuccess(() -> Component.translatable(KEY + "slot.set", settlement.name(),
                VillageLife.jobName(jobId), jobId, skill(experience)), true);
        return 1;
    }
}
