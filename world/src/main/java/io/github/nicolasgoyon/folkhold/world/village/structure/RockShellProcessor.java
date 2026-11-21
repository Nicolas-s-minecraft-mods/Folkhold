package io.github.nicolasgoyon.folkhold.world.village.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * The shell of rock of a carved piece: the walls, the ceilings and the ends of the halls and the tunnels of a cave
 * village. In the template, a structure void marks each position of the shell (see the cave pieces of
 * {@code tools/structures/village.py}). The processor gives each one the rock of the place, from the block of the world
 * at this position:
 *
 * <ul>
 *   <li>A block of a replacement (a rock of the world, for example stone or granite) becomes its dressed block (for
 *       example cobblestone or polished granite).</li>
 *   <li>Another solid block stays: a full block with no block entity that does not fall, for example an ore.</li>
 *   <li>A hole (air, water, lava, a plant), a falling block (sand, gravel), a block with a block entity (a spawner, a
 *       chest) and an infested block become the rock block: {@code deep_rock} under the Y {@code deep_below}, else
 *       {@code rock}.</li>
 * </ul>
 *
 * <p>Thus the shell closes each cave, aquifer and lava pool that crosses a piece, and the carved space stays dry and
 * closed. The springs of the game open only in a rock of the world: the dressed blocks are not of these rocks, thus no
 * spring of water or lava opens in a wall of a hall. The shell is not a part of the template for the builders and the
 * repairs: they skip the structure voids.
 *
 * <p>The processor reads the block of the world only in the box of the placement (the chunk that the generation
 * places). Type {@code folkhold_world:rock_shell}. Fields: {@code rock}, {@code deep_rock}, {@code deep_below} and
 * {@code replacements} (a list of objects with an {@code input} block and an {@code output} block state).
 */
public final class RockShellProcessor extends StructureProcessor {
    /**
     * A replacement of a rock of the world by its dressed block.
     *
     * @param input  the block of the world
     * @param output the block of the shell
     */
    public record Replacement(Block input, BlockState output) {
        public static final Codec<Replacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("input").forGetter(Replacement::input),
                BlockState.CODEC.fieldOf("output").forGetter(Replacement::output))
                .apply(instance, Replacement::new));
    }

    public static final MapCodec<RockShellProcessor> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BlockState.CODEC.fieldOf("rock").forGetter(RockShellProcessor::rock),
            BlockState.CODEC.fieldOf("deep_rock").forGetter(RockShellProcessor::deepRock),
            Codec.INT.fieldOf("deep_below").forGetter(RockShellProcessor::deepBelow),
            Replacement.CODEC.listOf().fieldOf("replacements").forGetter(RockShellProcessor::replacements))
            .apply(instance, RockShellProcessor::new));

    private final BlockState rock;
    private final BlockState deepRock;
    private final int deepBelow;
    private final List<Replacement> replacements;
    private final Map<Block, BlockState> dressed;

    public RockShellProcessor(BlockState rock, BlockState deepRock, int deepBelow, List<Replacement> replacements) {
        this.rock = rock;
        this.deepRock = deepRock;
        this.deepBelow = deepBelow;
        this.replacements = List.copyOf(replacements);
        this.dressed = this.replacements.stream()
                .collect(Collectors.toUnmodifiableMap(Replacement::input, Replacement::output, (a, b) -> a));
    }

    /** The block of a hole of the shell. */
    public BlockState rock() {
        return rock;
    }

    /** The block of a hole of the shell under the Y {@link #deepBelow()}. */
    public BlockState deepRock() {
        return deepRock;
    }

    /** The lowest Y of {@link #rock()}. */
    public int deepBelow() {
        return deepBelow;
    }

    /** The replacements of the rocks of the world. */
    public List<Replacement> replacements() {
        return replacements;
    }

    @Override
    @Nullable
    public StructureBlockInfo process(LevelReader level, BlockPos offset, BlockPos pos, StructureBlockInfo original,
            StructureBlockInfo current, StructurePlaceSettings settings, @Nullable StructureTemplate template) {
        if (!current.state().is(Blocks.STRUCTURE_VOID)) {
            return current;
        }
        BlockPos at = current.pos();
        BoundingBox box = settings.getBoundingBox();
        if (box != null && !box.isInside(at)) {
            // The placement does not place this position: another chunk places it.
            return current;
        }
        BlockState shell = shell(level, at, level.getBlockState(at));
        return shell == null ? null : new StructureBlockInfo(at, shell, null);
    }

    /**
     * The block of the shell at a position.
     *
     * @param level the level
     * @param pos   the position
     * @param world the block of the world at the position
     * @return the block of the shell, or null to keep the block of the world
     */
    @Nullable
    BlockState shell(BlockGetter level, BlockPos pos, BlockState world) {
        BlockState replacement = dressed.get(world.getBlock());
        if (replacement != null) {
            return replacement;
        }
        if (isSolidRock(level, pos, world)) {
            return null;
        }
        return pos.getY() < deepBelow ? deepRock : rock;
    }

    /** True if a block of the world stays in the shell: a full block with no block entity that does not fall. */
    static boolean isSolidRock(BlockGetter level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        return !state.hasBlockEntity() && !(block instanceof Fallable) && !(block instanceof InfestedBlock)
                && state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(level, pos);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructures.ROCK_SHELL.get();
    }
}
