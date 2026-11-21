package io.github.nicolasgoyon.folkhold.world.mixin;

/** A loaded chunk that knows if one of its blocks changed since its load (see {@link LevelChunkMixin}). */
public interface RoadChunk {
    /** True if a block of the chunk changed since the load of the chunk. */
    boolean folkhold_world$blocksChanged();
}
