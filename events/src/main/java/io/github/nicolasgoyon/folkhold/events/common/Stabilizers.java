package io.github.nicolasgoyon.folkhold.events.common;

import io.github.nicolasgoyon.folkhold.events.block.EventBlockTags;
import io.github.nicolasgoyon.folkhold.events.config.EventsConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Finds the stabilizers near the area of an event: the blocks of the tag {@code folkhold_events:stabilizer}.
 *
 * <ul>
 *   <li>The search reads only the chunks that are loaded and complete. No chunk loads for the search. A
 *       stabilizer in a loaded chunk that does not tick also protects the blocks near it.</li>
 *   <li>The search first reads the palette of each section of 16 × 16 × 16 blocks. It reads the blocks of a section
 *       only when its palette has a stabilizer. Thus the search costs little in an area with no stabilizer.</li>
 * </ul>
 */
public final class Stabilizers {
    private Stabilizers() {
    }

    /**
     * The stabilizers that can protect a block in a circular area, with the radius of the config
     * ({@link EventsConfig#STABILIZER_RADIUS}).
     *
     * @param level      the level
     * @param center     the center of the area
     * @param areaRadius the horizontal radius of the area, in blocks
     * @return the zone of the stabilizers, or {@link StabilizerZone#EMPTY} with a radius of 0
     */
    public static StabilizerZone near(ServerLevel level, BlockPos center, int areaRadius) {
        int radius = EventsConfig.STABILIZER_RADIUS.get();
        if (radius <= 0) {
            return StabilizerZone.EMPTY;
        }
        int reach = areaRadius + radius;
        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - reach);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + reach);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - reach);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + reach);
        List<StabilizerZone.Point> found = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk != null) {
                    scanChunk(chunk, found);
                }
            }
        }
        return found.isEmpty() ? StabilizerZone.EMPTY : new StabilizerZone(found, radius);
    }

    private static void scanChunk(LevelChunk chunk, List<StabilizerZone.Point> found) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(EventBlockTags.STABILIZER))) {
                continue;
            }
            int baseX = chunk.getPos().getMinBlockX();
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            int baseZ = chunk.getPos().getMinBlockZ();
            for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
                for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                    for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                        if (section.getBlockState(x, y, z).is(EventBlockTags.STABILIZER)) {
                            found.add(new StabilizerZone.Point(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
    }
}
