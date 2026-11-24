package io.github.nicolasgoyon.folkhold.world.group;

/** A loaded chunk that knows if one of its blocks changed since its load (the mixin LevelChunkMixin gives it to the loaded chunks). */
public interface RoadChunk {
    /** True if a block of the chunk changed since the load of the chunk. */
    boolean folkhold_world$blocksChanged();
}
