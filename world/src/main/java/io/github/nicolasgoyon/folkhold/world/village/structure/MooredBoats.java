package io.github.nicolasgoyon.folkhold.world.village.structure;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The moored boats of the piers and of the ports (see {@link PierPiece} and {@link PortPiece}). A boat is a vanilla
 * boat of the wood of the deck, on the water next to a deck. The piece places the boat once, at the generation of the
 * chunk of the boat: a player can take it, and the repairs do not put it back.
 */
final class MooredBoats {
    private MooredBoats() {
    }

    /**
     * The Y of the top block of the water of a column, from the level of a deck down: a water source with air above
     * it. Empty when the column has another block first, or no water in {@link Decks#MAX_DEPTH} blocks.
     *
     * @param level the level
     * @param x     the X of the column
     * @param deckY the Y of the deck
     * @param z     the Z of the column
     * @return the Y of the top block of the water, or empty
     */
    static Optional<Integer> waterTop(WorldGenLevel level, int x, int deckY, int z) {
        for (int y = deckY; y > deckY - Decks.MAX_DEPTH; y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.isAir()) {
                continue;
            }
            boolean source = state.is(Blocks.WATER) && state.getFluidState().isSource();
            return source && level.getBlockState(new BlockPos(x, y + 1, z)).isAir() ? Optional.of(y)
                    : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Places a boat on the water of a column, when the column is in the box of the generation and has open water
     * (see {@link #waterTop}).
     *
     * @param level  the level
     * @param box    the box of the generation
     * @param x      the X of the column
     * @param deckY  the Y of the deck next to the column
     * @param z      the Z of the column
     * @param facing the direction of the bow of the boat
     * @param deck   the deck block: its wood gives the type of the boat
     * @return true if the boat is in the level
     */
    static boolean moor(WorldGenLevel level, BoundingBox box, int x, int deckY, int z, Direction facing,
            BlockState deck) {
        if (!box.isInside(x, deckY, z)) {
            return false;
        }
        Optional<Integer> top = waterTop(level, x, deckY, z);
        if (top.isEmpty()) {
            return false;
        }
        Boat boat = new Boat(level.getLevel(), x + 0.5, top.get() + 1.0, z + 0.5);
        boat.setVariant(type(deck.getBlock()));
        boat.setYRot(facing.toYRot());
        boat.yRotO = boat.getYRot();
        return level.addFreshEntity(boat);
    }

    /** The type of boat of a deck block: the wood of the planks, or oak for another block. */
    static Boat.Type type(Block deck) {
        for (Boat.Type type : Boat.Type.values()) {
            if (type.getPlanks() == deck) {
                return type;
            }
        }
        return Boat.Type.OAK;
    }
}
