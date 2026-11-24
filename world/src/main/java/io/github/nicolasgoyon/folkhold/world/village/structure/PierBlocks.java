package io.github.nicolasgoyon.folkhold.world.village.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks of a pier, of a bridge and of a port (see {@link PierPiece} and {@link PortPiece}).
 *
 * @param deck   the blocks of the deck
 * @param post   the blocks of the posts under the deck, down to the floor of the water
 * @param rail   the blocks of the rails on the two sides of the deck
 * @param stairs the blocks of the steps from the deck down into the water. Data with no stairs gets
 *               {@link #DEFAULT_STAIRS}
 */
public record PierBlocks(BlockState deck, BlockState post, BlockState rail, BlockState stairs) {
    /** The steps of data with no stairs. */
    public static final BlockState DEFAULT_STAIRS = Blocks.SPRUCE_STAIRS.defaultBlockState();

    public static final Codec<PierBlocks> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockState.CODEC.fieldOf("deck").forGetter(PierBlocks::deck),
            BlockState.CODEC.fieldOf("post").forGetter(PierBlocks::post),
            BlockState.CODEC.fieldOf("rail").forGetter(PierBlocks::rail),
            BlockState.CODEC.optionalFieldOf("stairs", DEFAULT_STAIRS).forGetter(PierBlocks::stairs))
            .apply(instance, PierBlocks::new));

    /** The blocks of a pier with the default steps. */
    public PierBlocks(BlockState deck, BlockState post, BlockState rail) {
        this(deck, post, rail, DEFAULT_STAIRS);
    }

    /** The blocks as NBT data, for the save of a structure piece. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Deck", NbtUtils.writeBlockState(deck));
        tag.put("Post", NbtUtils.writeBlockState(post));
        tag.put("Rail", NbtUtils.writeBlockState(rail));
        tag.put("Stairs", NbtUtils.writeBlockState(stairs));
        return tag;
    }

    /** The blocks from NBT data (see {@link #save()}). Data with no stairs gets {@link #DEFAULT_STAIRS}. */
    public static PierBlocks load(HolderGetter<Block> blocks, CompoundTag tag) {
        return new PierBlocks(NbtUtils.readBlockState(blocks, tag.getCompound("Deck")),
                NbtUtils.readBlockState(blocks, tag.getCompound("Post")),
                NbtUtils.readBlockState(blocks, tag.getCompound("Rail")),
                tag.contains("Stairs") ? NbtUtils.readBlockState(blocks, tag.getCompound("Stairs")) : DEFAULT_STAIRS);
    }
}
