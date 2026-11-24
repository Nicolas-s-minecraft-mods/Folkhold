package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.PlanGround;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSize;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLine;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallOutline;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.WallZones;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The rings of a village at runtime: the next ring of a village around its outer wall, from the saved pieces of the
 * village and the terrain of the world. The ring has the layout of the rings of the generation (see
 * {@link VillageWall#rings}): the same outline (see {@link WallLayout#ring}), the same towers and gates, and the
 * palisade heights on the terrain.
 *
 * <ul>
 *   <li>The walls of a village come from the pieces of its reference (see {@link #walls}): the columns of the
 *       palisade, the towers and the gates give the blocks of the lines. Each closed line is a wall (see
 *       {@link WallLine}), with its gates at the gate pieces and their levels. The outer wall is the wall with the
 *       largest box.</li>
 *   <li>A tower piece covers {@link WallLayout#TOWER} blocks of the line after its origin, along its edge. A corner
 *       tower also covers the last blocks of the edge before it: the line before the tower is not on its edge.</li>
 *   <li>A line that is not closed gives no wall: for example the line of a maritime village, with no palisade on the
 *       water side.</li>
 *   <li>The next ring (see {@link #next}): the tier of the level of the village gives its rings. A village with fewer
 *       walls than the first wall and these rings gets one more ring around its outer wall, with the band of its style
 *       and the tier. The ring has a tower next to each gate, and the towers of the garrison of the tier that the walls
 *       do not have.</li>
 *   <li>The high ground of the ring: the terrain more than {@link PlanGround#HIGH_RISE} blocks above the plaza.</li>
 * </ul>
 */
public final class RingPlans {
    /** The name of the template of a gate of the wall, the last part of its id. */
    public static final String GATE = "gate";

    private RingPlans() {
    }

    /**
     * The walls of a village from the pieces of its reference: one wall for each closed line, the wall with the largest
     * box first.
     *
     * @param reference    the reference of the village
     * @param towerSpacing the tower spacing of the wall settings of the village
     * @param height       the Y of the top block of the terrain at a position (X, Z)
     * @param seaLevel     the sea level: a gate in front of water is a water gate
     * @return the walls, with no pieces and no spare towers
     */
    public static List<VillageWall.Wall> walls(VillageReference reference, int towerSpacing, IntBinaryOperator height,
            int seaLevel) {
        Set<Cell> cells = new HashSet<>();
        for (VillageReference.Palisade palisade : reference.palisades()) {
            for (int i = 0; i < palisade.bases().size(); i++) {
                cells.add(new Cell(palisade.start().getX() + palisade.stepX() * i,
                        palisade.start().getZ() + palisade.stepZ() * i));
            }
        }
        // The middle block of each gate on the line, with the level of the gate.
        Map<Cell, Integer> gateLevels = new HashMap<>();
        List<VillageReference.Piece> towers = new ArrayList<>();
        for (VillageReference.Piece piece : reference.pieces()) {
            Side side = side(piece.rotation());
            if (WallZones.isTower(piece.template())) {
                towers.add(piece);
                for (int k = 0; k < WallLayout.TOWER; k++) {
                    cells.add(along(piece.origin(), side, k));
                }
            } else if (isGate(piece)) {
                Cell line = new Cell(piece.origin().getX() + side.insideX(), piece.origin().getZ() + side.insideZ());
                for (int k = 0; k < WallLayout.GATE; k++) {
                    cells.add(new Cell(line.x() + side.alongX() * k, line.z() + side.alongZ() * k));
                }
                gateLevels.put(new Cell(line.x() + side.alongX() * (WallLayout.GATE / 2),
                        line.z() + side.alongZ() * (WallLayout.GATE / 2)), piece.origin().getY());
            }
        }
        // A corner tower: the line does not go on before the tower, on its edge. It covers the end of the edge before it.
        Set<Cell> corners = new HashSet<>();
        for (VillageReference.Piece tower : towers) {
            Side side = side(tower.rotation());
            if (!cells.contains(along(tower.origin(), side, -1))) {
                Side before = side.previous();
                for (int k = 1; k < WallLayout.TOWER; k++) {
                    corners.add(along(tower.origin(), before, -k));
                }
            }
        }
        cells.addAll(corners);
        List<VillageWall.Wall> walls = new ArrayList<>();
        for (Set<Cell> loop : WallLine.loops(cells)) {
            Optional<List<Cell>> outline = WallLine.corners(loop);
            if (outline.isEmpty()) {
                continue;
            }
            List<Cell> middles = gateLevels.keySet().stream().filter(loop::contains).toList();
            WallLayout layout = WallLayout.traced(outline.get(), towerSpacing, middles);
            Map<Gate, Integer> levels = new HashMap<>();
            Set<Gate> water = new HashSet<>();
            for (Gate gate : layout.gates()) {
                Integer gateLevel = gateLevels.get(layout.cell(gate.edge(), layout.along(gate)));
                if (gateLevel != null) {
                    levels.put(gate, gateLevel);
                    if (VillageWall.isWaterGate(layout, gate, height, seaLevel)) {
                        water.add(gate);
                    }
                }
            }
            walls.add(new VillageWall.Wall(layout, levels, water, List.of()));
        }
        return walls;
    }

    /**
     * The next ring of a village (see the rules of the class).
     *
     * @param templates     the structure templates
     * @param reference     the reference of the village
     * @param variant       the variant of the village structure
     * @param level         the level of the village: its tier gives the rings
     * @param start         the start chunk of the village: the palisade is cut at the borders of its districts
     * @param center        the middle of the plaza, on its surface
     * @param height        the Y of the top block of the terrain at a position (X, Z), water included
     * @param seaLevel      the sea level
     * @return the ring with its pieces, or empty when the village has all the rings of its tier, or when its outer wall
     *         is not a closed line
     */
    public static Optional<VillageWall.Wall> next(StructureTemplateManager templates, VillageReference reference,
            FortifiedVillageStructure.Variant variant, int level, ChunkPos start, BlockPos center,
            IntBinaryOperator height, int seaLevel) {
        return planned(templates, reference, variant, level, start, center, height, seaLevel)
                .map(Planned::ring);
    }

    /**
     * The next ring of a village with the wall inside it.
     *
     * @param inner the outer wall of the village: the wall inside the new ring
     * @param ring  the new ring with its pieces
     * @param index the number of the ring: 0 for the first ring outside the first wall
     */
    public record Planned(VillageWall.Wall inner, VillageWall.Wall ring, int index) {
    }

    /** As {@link #next}, with the wall inside the ring and the number of the ring. */
    public static Optional<Planned> planned(StructureTemplateManager templates, VillageReference reference,
            FortifiedVillageStructure.Variant variant, int level, ChunkPos start, BlockPos center,
            IntBinaryOperator height, int seaLevel) {
        VillageSize size = VillageSizes.forLevel(level);
        WallSettings settings = variant.wall();
        List<VillageWall.Wall> walls = walls(reference, settings.towerSpacing(), height, seaLevel);
        if (walls.isEmpty() || walls.size() > size.rings()) {
            return Optional.empty();
        }
        VillageWall.Wall outer = walls.get(0);
        long towers = reference.pieces().stream().filter(piece -> WallZones.isTower(piece.template())).count();
        int spare = (int) Math.max(0, FortifiedVillageStructure.towers(size) - towers);
        VillageWall.Wall inner = new VillageWall.Wall(outer.layout(), outer.gateLevels(), outer.waterGates(),
                List.of(), spare);
        int plaza = center.getY() - 1;
        WallOutline.Ground ground = (x, z) -> height.applyAsInt(x, z) - plaza > PlanGround.HIGH_RISE;
        return Optional.of(new Planned(outer, VillageWall.rings(templates, seaLevel, height, inner, 1,
                FortifiedVillageStructure.bandOf(size, variant), settings, variant.maritime(), start, center, ground)
                .get(0), walls.size() - 1));
    }

    /**
     * The height of the terrain of the world: the Y of the top block with no trees and no plants. The water is the top
     * block on water. The chunk of the position must be loaded.
     *
     * @param level the level
     * @return the Y of the top block at a position (X, Z)
     */
    public static IntBinaryOperator worldHeight(ServerLevel level) {
        return (x, z) -> {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
            while (pos.getY() > level.getMinBuildHeight()) {
                BlockState state = level.getBlockState(pos);
                boolean natural = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                        || (state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty());
                if (!natural) {
                    break;
                }
                pos.move(0, -1, 0);
            }
            return pos.getY();
        };
    }

    /** True for a gate piece of a wall: the last part of its id is {@value #GATE}. */
    static boolean isGate(VillageReference.Piece piece) {
        String path = piece.template().getPath();
        return path.endsWith("/" + GATE) && !path.contains("/cave/");
    }

    /** The side of the edge of a tower or a gate from the turn of its template (see {@link VillageWall#rotation}). */
    static Side side(Rotation rotation) {
        return switch (rotation) {
            case NONE -> Side.NORTH;
            case CLOCKWISE_90 -> Side.EAST;
            case CLOCKWISE_180 -> Side.SOUTH;
            case COUNTERCLOCKWISE_90 -> Side.WEST;
        };
    }

    /** The block of the line at a distance from a block, along the edge of a side. */
    private static Cell along(BlockPos origin, Side side, int distance) {
        return new Cell(origin.getX() + side.alongX() * distance, origin.getZ() + side.alongZ() * distance);
    }
}
