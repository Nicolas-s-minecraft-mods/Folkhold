package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.CliffSide;
import io.github.nicolasgoyon.folkhold.core.sim.village.HeightGrid;
import io.github.nicolasgoyon.folkhold.core.sim.village.PalisadeHeights;
import io.github.nicolasgoyon.folkhold.core.sim.village.PathRamp;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.core.sim.village.SeaWall;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Segment;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Tower;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallOutline;
import io.github.nicolasgoyon.folkhold.core.sim.village.WaterExit;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots.Plot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The wall of a fortified village: the pieces of the wall around the pieces of the pools, and around the space of a
 * new home on each free plot (see {@link WallLayout} and {@link VillagePlots#reserved}).
 *
 * <ul>
 *   <li>The first wall has a free outline: the rectangle around the pieces, with the high ground of the site cut out
 *       (see {@link WallLayout#free}). On flat ground, it is the rectangle. A ring is the outline of the wall inside it,
 *       larger by the band, with the high ground of the band cut out (see {@link WallLayout#ring}).</li>
 *   <li>A gate goes on each of the 4 axes of the start piece, on the first edge of its side that the axis meets: the
 *       streets go out of the start piece on these axes.</li>
 *   <li>The cliff side (see {@link CliffSide}): where the terrain outside the line goes down steeply, the palisade is
 *       low, and no gate opens. A gate on a cliff moves along its side by 2 blocks at most, or goes away when the wall
 *       keeps another gate.</li>
 *   <li>The structure reads the height of the terrain on the line of the palisade, and at 2 blocks inside and outside
 *       the line. The bases of the columns come from these heights (see {@link PalisadeHeights}).</li>
 *   <li>The number of towers comes from the garrison of the village (see {@link WallLayout#towers(int)} and
 *       {@link WallLayout#withTowers}): a tower next to each gate, then the corners with the largest view (see
 *       {@link #cornerView}), then the towers along the longest free parts of the wall. A corner with no tower is a
 *       plain corner of palisade, with flat terrain around it. The towers of the garrison that have no place on a wall
 *       go to the next ring (see {@link Wall#spareTowers}).</li>
 *   <li>A tower or a gate is a structure template on the highest base under it. The bases near it go up again, so
 *       that the palisade has no high step next to it.</li>
 *   <li>Each segment of palisade is a {@link PalisadePiece}. A {@link VillagePathPiece} with the width of a street goes
 *       from each gate to the plaza, through the streets on the axis: an arm (see {@link Arm}). The arm is a ramp from
 *       the level of the gate to the level of the plaza (see {@link PathRamp}). The houses on the plots of an arm move
 *       up or down to the level of the arm at their plot.</li>
 *   <li>{@link TerrainPiece}s make the terrain flat near the wall: along the palisade, in sections of
 *       {@link #SECTION} columns at their highest base, and in front of the gates, at the level of the gate. The flat
 *       terrain of a gate also clears its space: no terrain above the level of the gate.</li>
 *   <li>A {@link GroundFillPiece} on each tower and each gate fills the small gaps of air under the surface of the
 *       terrain near it.</li>
 *   <li>The water side: the top block of the terrain is water at the sea level or lower (see {@link #isWater}). On
 *       water, the palisade stands on its foundation, with no flat terrain along it. A gate that opens on water gets an
 *       exit in front of it (see {@link WaterExit} and {@link #exit}), and no flat terrain there: a {@link PierPiece}
 *       with steps, or a {@link PortPiece} at a gate of the outer wall on large water. A gate of a ring in front of a
 *       water gate of the inner wall, on the same water, gets a bridge from the inner gate (see {@link #bridge}).</li>
 * </ul>
 */
public final class VillageWall {
    /** The terrain of the near blocks is at this distance from the line, inside and outside. */
    private static final int NEAR_DISTANCE = 2;
    /** The near blocks are in a window of this number of blocks before and after a block of the line. */
    private static final int NEAR_RADIUS = 1;
    /** The ground level of a template is 1 block above its origin: the first layer above the ground. */
    private static final int GROUND_LEVEL_DELTA = 1;
    /** The terrain is flat along the palisade in sections of this number of columns. */
    static final int SECTION = 2;
    /** The flat terrain of the palisade goes this number of blocks inside and outside the line. */
    static final int FLAT_WIDTH = 2;
    /** The flat terrain in front of a gate goes this number of blocks outside the line. */
    static final int APPROACH = 6;
    /** The flat terrain behind a gate goes this number of blocks inside the line. */
    static final int ENTRANCE = 3;
    /** The points in front of a gate are at these distances outside the line. */
    private static final int[] FRONT = {2, 4, 6};
    /** A gate opens on water when this number of the points in front of it are on water, or more. */
    static final int WATER_FRONT = 2;
    /**
     * The first rows of an arm on the flat terrain behind its gate: an arm starts 2 blocks inside the line, and the
     * flat terrain goes {@link #ENTRANCE} blocks inside the line.
     */
    static final int FLAT_ROWS = ENTRANCE - 1;
    /** The number of blocks on each side of the middle of a street: the plots are at this distance. */
    static final int STREET_HALF_WIDTH = 2;
    /** A pier starts at this distance outside the line: after the outer row of the gate. */
    static final int PIER_START = WaterExit.FIRST_ROW;
    /**
     * The exit of a water gate stays under this distance from the line: the longest pier, and the jetties of a port
     * (see {@link #exit}).
     */
    static final int EXIT_REACH = Math.max(PIER_START + PierPiece.MAX_LENGTH, PortPiece.REACH + 1);
    /** The game places the pieces of a structure only in this range of chunks from the start chunk. */
    static final int CHUNK_RANGE = 8;
    /** The eyes of an archer on the platform of a tower are this number of blocks above the base of the tower. */
    static final int TOWER_EYES = 9;
    /** The view of a corner reads the terrain outside it on a grid with this step, in blocks. */
    static final int VIEW_STEP = 8;
    /** The view of a corner reads this number of points of its grid along each side, outside the corner. */
    static final int VIEW_POINTS = 4;

    private VillageWall() {
    }

    /**
     * The pieces of the wall around the pieces of a village, and around the spaces that the village keeps for its
     * growth.
     *
     * @param context  the context of the generation
     * @param heights  the terrain cache of the layout
     * @param village  the pieces of the pools
     * @param reserved the spaces for later buildings, for example the new homes of the free plots
     * @param center   the middle of the start piece
     * @param settings the settings of the wall
     * @param maritime true for a maritime village: the water is the wall, and the palisade stands on the land side
     *                 only (see {@link SeaWall})
     * @return the pieces of the wall
     */
    public static List<StructurePiece> add(Structure.GenerationContext context, TerrainCache heights,
            List<StructurePiece> village, List<BoundingBox> reserved, BlockPos center, WallSettings settings,
            boolean maritime) {
        return build(context, heights, village, reserved, center, settings, maritime, OptionalInt.empty(),
                WallOutline.Ground.FLAT, true).pieces();
    }

    /**
     * The data of a wall: the first wall of a village or a ring.
     *
     * @param layout      the layout of the wall
     * @param gateLevels  the level of each gate that the wall keeps
     * @param waterGates  the gates that open on water and have a pier
     * @param pieces      the pieces of the wall
     * @param spareTowers the towers of the garrison that have no place on this wall and on the walls inside it: the
     *                    next ring gets them, in addition to the towers of its gates
     * @param band        the streets and the buildings of the band inside a ring (see
     *                    {@link io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan#band}): empty for the
     *                    first wall and for a ring with no band plots
     */
    public record Wall(WallLayout layout, Map<Gate, Integer> gateLevels, Set<Gate> waterGates,
            List<StructurePiece> pieces, int spareTowers, List<StructurePiece> band) {
        public Wall {
            gateLevels = Map.copyOf(gateLevels);
            waterGates = Set.copyOf(waterGates);
            pieces = List.copyOf(pieces);
            band = List.copyOf(band);
        }

        /** A wall with no band. */
        public Wall(WallLayout layout, Map<Gate, Integer> gateLevels, Set<Gate> waterGates,
                List<StructurePiece> pieces, int spareTowers) {
            this(layout, gateLevels, waterGates, pieces, spareTowers, List.of());
        }

        /** A wall with no spare towers and no band. */
        public Wall(WallLayout layout, Map<Gate, Integer> gateLevels, Set<Gate> waterGates,
                List<StructurePiece> pieces) {
            this(layout, gateLevels, waterGates, pieces, 0);
        }

        /** This ring with the streets and the buildings of its band. */
        public Wall withBand(List<? extends StructurePiece> pieces) {
            return new Wall(layout, gateLevels, waterGates, this.pieces, spareTowers, List.copyOf(pieces));
        }

        /** The pieces of the wall, then the pieces of its band. */
        public List<StructurePiece> allPieces() {
            List<StructurePiece> all = new ArrayList<>(pieces);
            all.addAll(band);
            return all;
        }
    }

    /**
     * As {@link #add}, with the data of the wall and a number of towers: the rings of the village go around it (see
     * {@link #rings}).
     *
     * @param towers the number of towers of the wall (see {@link WallLayout#towers(int)}), or empty for the towers of
     *               the full layout (see {@link WallLayout#plan}): a tower at each outer corner, and more towers along
     *               the edges at the tower spacing of the settings
     * @param ground the ground of the site: the free outline of the wall cuts out its high ground (see
     *               {@link WallLayout#free}). {@link WallOutline.Ground#FLAT} gives the rectangle
     * @param outer  true when no ring goes around the wall: a water gate on large water gets a port (see
     *               {@link #exit})
     * @return the wall
     */
    public static Wall build(Structure.GenerationContext context, TerrainCache heights, List<StructurePiece> village,
            List<BoundingBox> reserved, BlockPos center, WallSettings settings, boolean maritime, OptionalInt towers,
            WallOutline.Ground ground, boolean outer) {
        List<Box> boxes = village.stream().map(piece -> box(piece.getBoundingBox())).toList();
        List<Box> inside = new ArrayList<>(boxes);
        reserved.forEach(space -> inside.add(box(space)));
        List<Gate> gates = List.of(new Gate(Side.NORTH, center.getX()), new Gate(Side.EAST, center.getZ()),
                new Gate(Side.SOUTH, center.getX()), new Gate(Side.WEST, center.getZ()));
        WallLayout planned = WallLayout.free(inside, settings.margin(), settings.towerSpacing(), gates,
                new Cell(center.getX(), center.getZ()), ground);
        Terrain terrain = new Terrain(heights);
        int seaLevel = context.chunkGenerator().getSeaLevel();
        boolean[] water = new boolean[planned.ringSize()];
        for (int i = 0; i < water.length; i++) {
            Cell cell = planned.ringCell(i);
            water[i] = isWater(terrain.height(cell.x(), cell.z()), seaLevel);
        }
        boolean[] keep = maritime ? SeaWall.keep(water) : allColumns(water.length);
        // The bases of the columns come from the ring only: the gates do not change them.
        int[] bases = bases(planned, terrain);
        List<Stop> stops = stops(village);
        WallLayout gated = withGates(planned, CliffSide.gates(planned,
                gateDrop(planned, bases, terrain, gate -> arm(planned, gate, stops)), keep));
        WallLayout layout = towers.isPresent()
                ? gated.withTowers(towers.getAsInt(), corner -> cornerView(gated, corner.edge(), bases, terrain),
                        tower -> kept(keep, gated.ringIndices(gated.towerBox(tower))))
                : gated;
        int spare = towers.isPresent() ? Math.max(0, towers.getAsInt() - layout.towers().size()) : 0;
        Set<Gate> waterGates = new HashSet<>();
        for (Gate gate : layout.gates()) {
            if (isWaterGate(layout, gate, terrain::height, seaLevel)) {
                waterGates.add(gate);
            }
        }
        StructureTemplateManager templates = context.structureTemplateManager();
        List<StructurePiece> pieces = new ArrayList<>();
        Map<Tower, Integer> towerLevels = new HashMap<>();
        Map<Gate, Integer> gateLevels = new HashMap<>();
        for (Tower tower : layout.towers()) {
            towerLevels.put(tower, flatten(bases, layout.ringIndices(layout.towerBox(tower))));
        }
        Map<Gate, Arm> arms = new HashMap<>();
        for (Gate gate : layout.gates()) {
            Optional<Arm> arm = arm(layout, gate, stops);
            arm.ifPresent(value -> arms.put(gate, value));
            List<Integer> indices = layout.ringIndices(gate);
            int level = reachable(flatten(bases, indices), arm);
            indices.forEach(index -> bases[index] = level);
            gateLevels.put(gate, level);
        }
        int[] smooth = PalisadeHeights.smooth(bases);
        boolean[] cliffs = CliffSide.cliffs(CliffSide.drops(layout, smooth, terrain::height));
        for (Tower tower : layout.towers()) {
            if (kept(keep, layout.ringIndices(layout.towerBox(tower)))) {
                pieces.add(template(templates, settings, settings.tower(),
                        towerOrigin(layout, tower, towerLevels.get(tower)), rotation(tower.side())));
            }
        }
        List<Gate> keptGates = layout.gates().stream().filter(gate -> kept(keep, layout.ringIndices(gate))).toList();
        gateLevels.keySet().retainAll(keptGates);
        for (Gate gate : keptGates) {
            pieces.add(template(templates, settings, settings.gate(), gateOrigin(layout, gate, gateLevels.get(gate)),
                    rotation(gate.side())));
        }
        for (Segment segment : layout.segments()) {
            for (Segment run : runs(layout, segment, keep)) {
                for (Segment part : parts(layout, run, cliffs)) {
                    pieces.add(palisade(layout, part, smooth, settings.palisade(), cliffs));
                }
            }
        }
        for (Gate gate : keptGates) {
            Arm arm = arms.get(gate);
            if (arm != null) {
                int[] levels = levels(arm, gateLevels.get(gate), terrain);
                pieces.add(new VillagePathPiece(arm.startX(), arm.startZ(), arm.stepX(), arm.stepZ(), levels, true,
                        Optional.of(settings.streetStairs())));
                moveHouses(templates, village, arm, levels);
            }
            if (waterGates.contains(gate)) {
                Optional<StructurePiece> exit = exit(layout, gate, gateLevels.get(gate), terrain::height, seaLevel,
                        settings.pier(), range(context.chunkPos()), outer);
                // A water gate with no pier in the range is a land gate: it gets the flat terrain in front of it.
                exit.ifPresentOrElse(pieces::add, () -> waterGates.remove(gate));
            }
        }
        pieces.addAll(terrain(layout, smooth, gateLevels, water, waterGates));
        pieces.addAll(fills(pieces));
        return new Wall(layout, gateLevels, waterGates, pieces, spare);
    }

    /**
     * The rings of a village around its first wall (see {@link WallLayout#rings}). Each ring follows the rules of the
     * first wall: the palisade on the terrain, the towers, the gates, the flat terrain, the water side and the cliff
     * side. On the cliff side, a gate of a ring goes away and does not move (see {@link CliffSide#ringGates}). Each
     * gate of a ring gets a path across the band: a ramp from the ring gate to the level of the gate of the wall inside
     * it (see {@link #bandArm}). The streets and the buildings of a band come from the layout of the village (see
     * {@link Wall#withBand}). A segment of palisade is cut at the borders of the district cells (see {@link RingDistricts}), thus each piece goes to one district. A ring has a tower next to
     * each of its gates, and the spare towers of the wall inside it (see {@link Wall#spareTowers}).
     *
     * @param context  the context of the generation
     * @param heights  the terrain cache of the village
     * @param first    the first wall of the village
     * @param count    the number of rings
     * @param band     the band of each ring, in blocks (see {@link WallLayout#ring})
     * @param settings the settings of the wall
     * @param maritime true for a maritime village: the palisade stands on the land side only
     * @param start    the start chunk of the village
     * @param center   the middle of the start piece: the axes of the gates start there
     * @param ground   the ground of the site: each ring cuts out the high ground of its band (see
     *                 {@link WallLayout#ring(WallLayout, int, Cell, WallOutline.Ground)})
     * @return the rings, from the inside to the outside
     */
    public static List<Wall> rings(Structure.GenerationContext context, TerrainCache heights, Wall first, int count,
            int band, WallSettings settings, boolean maritime, ChunkPos start, BlockPos center,
            WallOutline.Ground ground) {
        Cell middle = new Cell(center.getX(), center.getZ());
        Terrain terrain = new Terrain(heights);
        int seaLevel = context.chunkGenerator().getSeaLevel();
        StructureTemplateManager templates = context.structureTemplateManager();
        List<Wall> rings = new ArrayList<>();
        Wall inner = first;
        for (int ring = 0; ring < count; ring++) {
            WallLayout planned = WallLayout.ring(inner.layout(), band, middle, ground);
            boolean[] water = new boolean[planned.ringSize()];
            for (int i = 0; i < water.length; i++) {
                Cell cell = planned.ringCell(i);
                water[i] = isWater(terrain.height(cell.x(), cell.z()), seaLevel);
            }
            boolean[] keep = maritime ? SeaWall.keep(water) : allColumns(water.length);
            int[] bases = bases(planned, terrain);
            Wall wall = inner;
            WallLayout gated = withGates(planned, CliffSide.ringGates(planned,
                    gateDrop(planned, bases, terrain, gate -> bandArm(planned, gate, wall)), keep));
            ToIntFunction<Tower> view = corner -> cornerView(gated, corner.edge(), bases, terrain);
            Predicate<Tower> allowed = tower -> kept(keep, gated.ringIndices(gated.towerBox(tower)));
            int gateTowers = gated.withTowers(0, view, allowed).towers().size();
            WallLayout layout = gated.withTowers(gateTowers + inner.spareTowers(), view, allowed);
            int spare = inner.spareTowers() - (layout.towers().size() - gateTowers);
            List<StructurePiece> pieces = new ArrayList<>();
            Map<Tower, Integer> towerLevels = new HashMap<>();
            for (Tower tower : layout.towers()) {
                towerLevels.put(tower, flatten(bases, layout.ringIndices(layout.towerBox(tower))));
            }
            List<Gate> keptGates = layout.gates().stream().filter(gate -> kept(keep, layout.ringIndices(gate)))
                    .toList();
            Map<Gate, Integer> gateLevels = new HashMap<>();
            Map<Gate, Arm> arms = new HashMap<>();
            List<PierPiece> bridges = new ArrayList<>();
            for (Gate gate : keptGates) {
                Optional<Arm> arm = bandArm(layout, gate, inner);
                arm.ifPresent(value -> arms.put(gate, value));
                List<Integer> indices = layout.ringIndices(gate);
                // A bridge from the water gate of the inner wall gives its deck level to the gate.
                Optional<PierPiece> bridge = bridge(layout, gate, inner, terrain::height, seaLevel, settings.pier());
                bridge.ifPresent(bridges::add);
                int level = bridge.isPresent() ? bridge.get().deckY() : reachable(flatten(bases, indices), arm);
                indices.forEach(index -> bases[index] = level);
                gateLevels.put(gate, level);
            }
            int[] smooth = PalisadeHeights.smooth(bases);
            boolean[] cliffs = CliffSide.cliffs(CliffSide.drops(layout, smooth, terrain::height));
            for (Tower tower : layout.towers()) {
                if (kept(keep, layout.ringIndices(layout.towerBox(tower)))) {
                    pieces.add(template(templates, settings, settings.tower(),
                            towerOrigin(layout, tower, towerLevels.get(tower)), rotation(tower.side())));
                }
            }
            for (Gate gate : keptGates) {
                pieces.add(template(templates, settings, settings.gate(),
                        gateOrigin(layout, gate, gateLevels.get(gate)), rotation(gate.side())));
            }
            for (Segment segment : layout.segments()) {
                for (Segment run : runs(layout, segment, keep)) {
                    for (Segment part : cut(layout, run, start)) {
                        for (Segment low : parts(layout, part, cliffs)) {
                            pieces.add(palisade(layout, low, smooth, settings.palisade(), cliffs));
                        }
                    }
                }
            }
            Set<Gate> waterGates = new HashSet<>();
            boolean outer = ring == count - 1;
            for (Gate gate : keptGates) {
                Arm arm = arms.get(gate);
                if (arm != null) {
                    pieces.add(new VillagePathPiece(arm.startX(), arm.startZ(), arm.stepX(), arm.stepZ(),
                            levels(arm, gateLevels.get(gate), terrain), true, Optional.of(settings.streetStairs())));
                }
                if (isWaterGate(layout, gate, terrain::height, seaLevel)) {
                    waterGates.add(gate);
                    exit(layout, gate, gateLevels.get(gate), terrain::height, seaLevel, settings.pier(),
                            BoundingBox.infinite(), outer).ifPresent(pieces::add);
                }
            }
            pieces.addAll(bridges);
            pieces.addAll(terrain(layout, smooth, gateLevels, water, waterGates));
            inner = new Wall(layout, gateLevels, waterGates, pieces, spare);
            rings.add(inner);
        }
        return List.copyOf(rings);
    }

    /**
     * The pieces of the rings of a village in each district (see {@link RingDistricts}): a piece goes to the district
     * of the middle of its box. Each district also gets the ground fill of its towers and gates (see {@link #fills}),
     * with the cleared spaces of the gates of all the districts: a gate and its cleared space can be in 2 districts.
     * The streets and the buildings of the bands come after the pieces of the walls of their district, with no ground
     * fill.
     *
     * @param rings the rings
     * @param start the start chunk of the village
     * @return the pieces of each district that has pieces, in the order of the rings
     */
    public static Map<RingDistricts.District, List<StructurePiece>> districts(List<Wall> rings, ChunkPos start) {
        Map<RingDistricts.District, List<StructurePiece>> districts = new LinkedHashMap<>();
        for (Wall ring : rings) {
            ring.pieces().forEach(piece -> districtOf(districts, piece, start).add(piece));
        }
        List<BoundingBox> cleared = cleared(rings.stream().flatMap(ring -> ring.pieces().stream()).toList());
        districts.values().forEach(pieces -> pieces.addAll(fills(pieces, cleared)));
        for (Wall ring : rings) {
            ring.band().forEach(piece -> districtOf(districts, piece, start).add(piece));
        }
        return districts;
    }

    /** The list of the pieces of the district of the middle of the box of a piece. */
    private static List<StructurePiece> districtOf(Map<RingDistricts.District, List<StructurePiece>> districts,
            StructurePiece piece, ChunkPos start) {
        BlockPos middle = piece.getBoundingBox().getCenter();
        return districts.computeIfAbsent(RingDistricts.of(start.x, start.z, middle.getX(), middle.getZ()),
                key -> new ArrayList<>());
    }

    /** The parts of a run of palisade in each district cell. */
    private static List<Segment> cut(WallLayout layout, Segment run, ChunkPos start) {
        Cell first = layout.cell(run.edge(), run.from());
        Cell last = layout.cell(run.edge(), run.to());
        List<Integer> cuts = first.x() != last.x() ? RingDistricts.cuts(start.x, first.x(), last.x())
                : RingDistricts.cuts(start.z, first.z(), last.z());
        List<Segment> parts = new ArrayList<>();
        int from = run.from();
        for (int cut : cuts) {
            parts.add(run.between(from, run.from() + cut - 1));
            from = run.from() + cut;
        }
        parts.add(run.between(from, run.to()));
        return parts;
    }

    /**
     * The path of a gate of a ring across the band: the rows from the ring gate to the flat terrain in front of the
     * gate of the wall inside it ({@link #APPROACH} blocks). The inner gate is on the same axis (see
     * {@link WallLayout#ring}). The distance between the 2 lines is not always the band: the free outline of the first
     * wall can have its gate deeper in the box of the wall, and a ring cuts out the high ground of its band. The path
     * ends at the level of the inner gate. Empty if the inner wall has no gate there or if the inner gate opens on
     * water.
     *
     * @param ring  the ring
     * @param gate  the gate of the ring
     * @param inner the wall inside the ring
     * @return the arm of the path
     */
    static Optional<Arm> bandArm(WallLayout ring, Gate gate, Wall inner) {
        Optional<Gate> innerGate = inner.gateLevels().keySet().stream()
                .filter(candidate -> candidate.side() == gate.side() && !inner.waterGates().contains(candidate))
                .findFirst();
        if (innerGate.isEmpty()) {
            return Optional.empty();
        }
        Side side = gate.side();
        Cell middle = ring.cell(gate.edge(), ring.along(gate));
        Cell inside = inner.layout().cell(innerGate.get().edge(), inner.layout().along(innerGate.get()));
        // The distance across the band, from the line of the ring to the line of the inner gate.
        int distance = side.insideX() == 0 ? Math.abs(inside.z() - middle.z()) : Math.abs(inside.x() - middle.x());
        int length = distance - 2 - APPROACH;
        if (length < 1) {
            return Optional.empty();
        }
        return Optional.of(new Arm(middle.x() + 2 * side.insideX(), middle.z() + 2 * side.insideZ(), side.insideX(),
                side.insideZ(), length, OptionalInt.of(inner.gateLevels().get(innerGate.get()))));
    }

    /**
     * The pieces that fill the gaps under the surface near the towers and the gates: one {@link GroundFillPiece} for
     * each template of the wall. They come after the other pieces, thus they fill the terrain after the blocks of the
     * templates and the foundations. They do not fill the space that the flat terrain of a gate clears.
     */
    static List<GroundFillPiece> fills(List<StructurePiece> pieces) {
        return fills(pieces, cleared(pieces));
    }

    /** The boxes of the terrain pieces that clear their space. */
    private static List<BoundingBox> cleared(List<StructurePiece> pieces) {
        return pieces.stream().filter(piece -> piece instanceof TerrainPiece terrain && terrain.clears())
                .map(StructurePiece::getBoundingBox).toList();
    }

    /** The ground fills of the towers and the gates of some pieces, with the cleared boxes of a larger list. */
    private static List<GroundFillPiece> fills(List<StructurePiece> pieces, List<BoundingBox> cleared) {
        return pieces.stream().filter(PoolElementStructurePiece.class::isInstance)
                .map(piece -> new GroundFillPiece(piece.getBoundingBox(), cleared)).toList();
    }

    /** All the columns of a ring keep the palisade. */
    private static boolean[] allColumns(int size) {
        boolean[] keep = new boolean[size];
        Arrays.fill(keep, true);
        return keep;
    }

    /** True if one column of a list keeps the palisade. */
    private static boolean kept(boolean[] keep, List<Integer> indices) {
        return indices.stream().anyMatch(index -> keep[index]);
    }

    /** The runs of the columns of a segment that keep the palisade. */
    static List<Segment> runs(WallLayout layout, Segment segment, boolean[] keep) {
        List<Segment> runs = new ArrayList<>();
        int start = -1;
        for (int along = segment.from(); along <= segment.to() + 1; along++) {
            boolean on = along <= segment.to() && keep[layout.ringIndex(segment.edge(), along)];
            if (on && start < 0) {
                start = along;
            } else if (!on && start >= 0) {
                runs.add(segment.between(start, along - 1));
                start = -1;
            }
        }
        return runs;
    }

    /**
     * The parts of a segment with the same value of a flag on all their columns, in order. The parts cover the
     * segment. The flag of the cliff blocks cuts the palisade into its full and its low parts (see {@link CliffSide}).
     */
    static List<Segment> parts(WallLayout layout, Segment segment, boolean[] flags) {
        List<Segment> parts = new ArrayList<>();
        int start = segment.from();
        for (int along = segment.from() + 1; along <= segment.to() + 1; along++) {
            if (along > segment.to() || flags[layout.ringIndex(segment.edge(), along)]
                    != flags[layout.ringIndex(segment.edge(), start)]) {
                parts.add(segment.between(start, along - 1));
                start = along;
            }
        }
        return parts;
    }

    /**
     * The drop of a gate at each position of a side (see {@link CliffSide}): the level that the gate gets there, minus
     * the lowest terrain in front of it. The level is the highest base under the gate, in the range of the street of
     * the gate (see {@link #reachable}).
     *
     * @param layout the wall
     * @param bases  the bases of the ring, before the towers and the gates
     * @param terrain the terrain
     * @param arm    the street of a gate
     */
    private static CliffSide.GateDrop gateDrop(WallLayout layout, int[] bases, Terrain terrain,
            Function<Gate, Optional<Arm>> arm) {
        return (edge, along) -> {
            Gate gate = new Gate(layout.side(edge), layout.position(edge, along), edge);
            int level = layout.ringIndices(gate).stream().mapToInt(index -> bases[index]).max().orElseThrow();
            return reachable(level, arm.apply(gate)) - CliffSide.lowestInFront(layout, edge, along, terrain::height);
        };
    }

    /**
     * The view of the outer corner at the start of an edge: the number of points of a grid outside the corner that are
     * lower than the eyes of an archer on a tower of the corner ({@link #TOWER_EYES} blocks above the base of the
     * corner). The grid has {@link #VIEW_POINTS} x {@link #VIEW_POINTS} points, {@link #VIEW_STEP} blocks apart, in the
     * quarter outside the 2 edges of the corner. A corner above the terrain around it sees more points.
     *
     * @param layout  the wall
     * @param edge    the number of the edge that starts at the corner
     * @param bases   the bases of the ring, before the towers and the gates
     * @param terrain the terrain
     * @return the view, from 0 to {@code VIEW_POINTS * VIEW_POINTS}
     */
    private static int cornerView(WallLayout layout, int edge, int[] bases, Terrain terrain) {
        Cell corner = layout.cell(edge, 0);
        Side side = layout.side(edge);
        Side before = layout.side(layout.previous(edge));
        int eyes = bases[layout.ringIndex(edge, 0)] + TOWER_EYES;
        int view = 0;
        for (int i = 1; i <= VIEW_POINTS; i++) {
            for (int j = 1; j <= VIEW_POINTS; j++) {
                int x = corner.x() - VIEW_STEP * (i * side.insideX() + j * before.insideX());
                int z = corner.z() - VIEW_STEP * (i * side.insideZ() + j * before.insideZ());
                if (terrain.height(x, z) < eyes) {
                    view++;
                }
            }
        }
        return view;
    }

    /** A wall with the gates of the cliff side: the same wall when the gates do not change. */
    private static WallLayout withGates(WallLayout layout, List<Gate> gates) {
        return gates.equals(layout.gates()) ? layout : layout.withGates(gates);
    }

    /**
     * True if the top block of the terrain is water: at the sea level or lower. The heights of the terrain before the
     * generation of the chunks have water at the sea level and below it.
     *
     * @param top      the Y of the top block, water included
     * @param seaLevel the sea level of the chunk generator
     * @return true for water
     */
    static boolean isWater(int top, int seaLevel) {
        return top < seaLevel;
    }

    /**
     * True if a gate opens on water: {@link #WATER_FRONT} of the points in front of it are on water, or more.
     *
     * @param layout   the wall
     * @param gate     the gate
     * @param top      the Y of the top block of the terrain at a position (X, Z), water included
     * @param seaLevel the sea level of the chunk generator
     * @return true for a water gate
     */
    static boolean isWaterGate(WallLayout layout, Gate gate, IntBinaryOperator top, int seaLevel) {
        Side side = gate.side();
        Cell middle = layout.cell(gate.edge(), layout.along(gate));
        int count = 0;
        for (int distance : FRONT) {
            if (isWater(top.applyAsInt(middle.x() - side.insideX() * distance, middle.z() - side.insideZ() * distance),
                    seaLevel)) {
                count++;
            }
        }
        return count >= WATER_FRONT;
    }

    /**
     * The pier of a water gate. It starts after the outer row of the gate and goes out over the water, to the first
     * position on land. It has {@link PierPiece#MIN_LENGTH} rows at least and {@link PierPiece#MAX_LENGTH} rows at most.
     * The deck is at the level of the gate. Its steps go down to one block under the top block of the water (see
     * {@link #lowestStep}).
     *
     * @param layout   the wall
     * @param gate     the gate
     * @param level    the level of the gate
     * @param top      the Y of the top block of the terrain at a position (X, Z), water included
     * @param seaLevel the sea level of the chunk generator
     * @param blocks   the blocks of the pier
     * @return the pier
     */
    static PierPiece pier(WallLayout layout, Gate gate, int level, IntBinaryOperator top, int seaLevel,
            PierBlocks blocks) {
        return pier(layout, gate, level, top, seaLevel, blocks, BoundingBox.infinite()).orElseThrow();
    }

    /**
     * As {@link #pier(WallLayout, Gate, int, IntBinaryOperator, int, PierBlocks)}, with a limit: the pier stops
     * before the first row that is not in the limit, also under {@link PierPiece#MIN_LENGTH} rows. The game places
     * the pieces of a structure only in the range of chunks of its start chunk (see {@link #range}).
     *
     * @param limit the box of the blocks that the pier can use, on the horizontal plane
     * @return the pier, or empty if its first row is not in the limit
     */
    static Optional<PierPiece> pier(WallLayout layout, Gate gate, int level, IntBinaryOperator top, int seaLevel,
            PierBlocks blocks, BoundingBox limit) {
        Side side = gate.side();
        Cell middle = layout.cell(gate.edge(), layout.along(gate));
        int outX = -side.insideX();
        int outZ = -side.insideZ();
        int length = 0;
        while (length < PierPiece.MAX_LENGTH) {
            int distance = PIER_START + length;
            int x = middle.x() + outX * distance;
            int z = middle.z() + outZ * distance;
            // The row goes 1 block to each side of its middle, across the pier, and the steps 1 block farther on the
            // side of the higher X or Z.
            int acrossX = Math.abs(outZ);
            int acrossZ = Math.abs(outX);
            if (x - acrossX < limit.minX() || x + 2 * acrossX > limit.maxX() || z - acrossZ < limit.minZ()
                    || z + 2 * acrossZ > limit.maxZ()) {
                break;
            }
            boolean onWater = isWater(top.applyAsInt(middle.x() + outX * distance, middle.z() + outZ * distance),
                    seaLevel);
            if (!onWater && length >= PierPiece.MIN_LENGTH) {
                break;
            }
            length++;
        }
        return length == 0 ? Optional.empty()
                : Optional.of(new PierPiece(middle.x() + outX * PIER_START, middle.z() + outZ * PIER_START, outX,
                        outZ, length, level, blocks, lowestStep(seaLevel), false));
    }

    /**
     * The exit of a water gate (see {@link WaterExit}): a port on large water in front of a gate of the outer wall, else
     * a pier (see {@link #pier(WallLayout, Gate, int, IntBinaryOperator, int, PierBlocks, BoundingBox)}). A port that
     * does not fit in the limit gives its place to a pier. The bridges to the next ring come from the ring (see
     * {@link #bridge}).
     *
     * @param layout   the wall
     * @param gate     the water gate
     * @param level    the level of the gate
     * @param top      the Y of the top block of the terrain at a position (X, Z), water included
     * @param seaLevel the sea level of the chunk generator
     * @param blocks   the blocks of the piers
     * @param limit    the box of the blocks that the exit can use, on the horizontal plane
     * @param outer    true for the outer wall of the village
     * @return the port or the pier, or empty if the first row of the pier is not in the limit
     */
    static Optional<StructurePiece> exit(WallLayout layout, Gate gate, int level, IntBinaryOperator top, int seaLevel,
            PierBlocks blocks, BoundingBox limit, boolean outer) {
        Side side = gate.side();
        Cell middle = layout.cell(gate.edge(), layout.along(gate));
        int outX = -side.insideX();
        int outZ = -side.insideZ();
        IntPredicate water = distance -> isWater(top.applyAsInt(middle.x() + outX * distance,
                middle.z() + outZ * distance), seaLevel);
        int width = waterWidth(middle, outX, outZ, top, seaLevel);
        if (WaterExit.choose(water, width, OptionalInt.empty(), outer) == WaterExit.Kind.PORT) {
            BoundingBox port = PortPiece.boundingBox(middle.x(), middle.z(), outX, outZ, level);
            if (port.minX() >= limit.minX() && port.maxX() <= limit.maxX() && port.minZ() >= limit.minZ()
                    && port.maxZ() <= limit.maxZ()) {
                return Optional.of(new PortPiece(middle.x(), middle.z(), outX, outZ, level, lowestStep(seaLevel),
                        blocks));
            }
        }
        return pier(layout, gate, level, top, seaLevel, blocks, limit).map(StructurePiece.class::cast);
    }

    /**
     * The width of the water across the axis of a gate, at {@link WaterExit#PORT_DEPTH} blocks from the line: the
     * blocks of water side by side on the row, from the axis, {@link WaterExit#PORT_WIDTH} blocks at most. 0 when the
     * axis is not on water there.
     */
    private static int waterWidth(Cell middle, int outX, int outZ, IntBinaryOperator top, int seaLevel) {
        int x = middle.x() + outX * WaterExit.PORT_DEPTH;
        int z = middle.z() + outZ * WaterExit.PORT_DEPTH;
        if (!isWater(top.applyAsInt(x, z), seaLevel)) {
            return 0;
        }
        int acrossX = outZ != 0 ? 1 : 0;
        int acrossZ = outX != 0 ? 1 : 0;
        int width = 1;
        for (int sign : new int[] {-1, 1}) {
            for (int step = 1; step <= WaterExit.PORT_WIDTH / 2; step++) {
                if (!isWater(top.applyAsInt(x + sign * acrossX * step, z + sign * acrossZ * step), seaLevel)) {
                    break;
                }
                width++;
            }
        }
        return width;
    }

    /**
     * The bridge to a gate of a ring from the water gate of the wall inside it, on the same axis, when the water goes
     * from one gate to the other (see {@link WaterExit}). The deck goes from the first row after the outer row of the
     * inner gate to the row before the inner row of the ring gate, at the level of the inner gate. The ring gate takes
     * this level. The bridge has steps in its middle, and no rails on its 2 end rows.
     *
     * @param ring     the ring
     * @param gate     a gate of the ring
     * @param inner    the wall inside the ring
     * @param top      the Y of the top block of the terrain at a position (X, Z), water included
     * @param seaLevel the sea level of the chunk generator
     * @param blocks   the blocks of the piers
     * @return the bridge, or empty when the inner wall has no water gate on the axis or the water does not go to the
     *         ring gate
     */
    public static Optional<PierPiece> bridge(WallLayout ring, Gate gate, Wall inner, IntBinaryOperator top,
            int seaLevel, PierBlocks blocks) {
        Optional<Gate> innerGate = inner.waterGates().stream()
                .filter(candidate -> candidate.side() == gate.side() && candidate.center() == gate.center())
                .findFirst();
        if (innerGate.isEmpty() || !inner.gateLevels().containsKey(innerGate.get())) {
            return Optional.empty();
        }
        Side side = gate.side();
        int outX = -side.insideX();
        int outZ = -side.insideZ();
        Cell from = inner.layout().cell(innerGate.get().edge(), inner.layout().along(innerGate.get()));
        Cell to = ring.cell(gate.edge(), ring.along(gate));
        int distance = (to.x() - from.x()) * outX + (to.z() - from.z()) * outZ;
        int length = distance - 2 * PIER_START + 1;
        IntPredicate water = step -> isWater(top.applyAsInt(from.x() + outX * step, from.z() + outZ * step),
                seaLevel);
        if (length < 1 || WaterExit.choose(water, 0, OptionalInt.of(distance), false) != WaterExit.Kind.BRIDGE) {
            return Optional.empty();
        }
        return Optional.of(new PierPiece(from.x() + outX * PIER_START, from.z() + outZ * PIER_START, outX, outZ,
                length, inner.gateLevels().get(innerGate.get()), blocks, lowestStep(seaLevel), true));
    }

    /** The Y of the lowest step of a deck: one block under the top block of the water. */
    static int lowestStep(int seaLevel) {
        return seaLevel - 2;
    }

    /**
     * The blocks that the pieces of a structure can use on the horizontal plane: the chunks in the range of
     * {@link #CHUNK_RANGE} chunks of the start chunk.
     *
     * @param start the start chunk
     * @return the box, from the lowest Y to the highest Y of the integers
     */
    static BoundingBox range(ChunkPos start) {
        return new BoundingBox((start.x - CHUNK_RANGE) << 4, Integer.MIN_VALUE / 2, (start.z - CHUNK_RANGE) << 4,
                ((start.x + CHUNK_RANGE) << 4) + 15, Integer.MAX_VALUE / 2, ((start.z + CHUNK_RANGE) << 4) + 15);
    }

    /**
     * The pieces that make the terrain flat near the wall: along each segment of palisade, in sections of
     * {@link #SECTION} columns at their highest base, and in front of each gate and behind it, at the level of the
     * gate. On water, the palisade stands on its foundation: a section with all its columns on water gets no terrain
     * piece. A water gate gets the flat terrain behind it only: its pier goes in front of it. A plain corner (a corner
     * with no tower) gets flat terrain at its base in a square around it, {@link #FLAT_WIDTH} blocks in each direction,
     * unless it is on water.
     *
     * @param layout     the wall
     * @param bases      the bases of the ring
     * @param gateLevels the level of each gate
     * @param water      for each position of the ring, true if the line is on water
     * @param waterGates the gates that open on water
     * @return the pieces
     */
    static List<TerrainPiece> terrain(WallLayout layout, int[] bases, Map<Gate, Integer> gateLevels,
            boolean[] water, Set<Gate> waterGates) {
        List<TerrainPiece> pieces = new ArrayList<>();
        for (Segment segment : layout.segments()) {
            Side side = segment.side();
            int edge = segment.edge();
            for (int from = segment.from(); from <= segment.to(); from += SECTION) {
                int to = Math.min(from + SECTION - 1, segment.to());
                int ground = Integer.MIN_VALUE;
                boolean onWater = true;
                for (int along = from; along <= to; along++) {
                    int index = layout.ringIndex(edge, along);
                    ground = Math.max(ground, bases[index]);
                    onWater &= water[index];
                }
                if (!onWater) {
                    pieces.add(across(layout.cell(edge, from), layout.cell(edge, to), side, FLAT_WIDTH, FLAT_WIDTH,
                            ground, false));
                }
            }
        }
        for (int edge = 0; edge < layout.edges(); edge++) {
            int index = layout.ringIndex(edge, 0);
            if (!layout.hasCornerTower(edge) && !water[index]) {
                Cell corner = layout.cell(edge, 0);
                pieces.add(new TerrainPiece(corner.x() - FLAT_WIDTH, corner.z() - FLAT_WIDTH, corner.x() + FLAT_WIDTH,
                        corner.z() + FLAT_WIDTH, bases[index], false));
            }
        }
        for (Gate gate : layout.gates().stream().filter(gateLevels::containsKey).toList()) {
            int middle = layout.along(gate);
            pieces.add(across(layout.cell(gate.edge(), middle - WallLayout.GATE / 2),
                    layout.cell(gate.edge(), middle + WallLayout.GATE / 2), gate.side(),
                    waterGates.contains(gate) ? 0 : APPROACH, ENTRANCE, gateLevels.get(gate), true));
        }
        return pieces;
    }

    /**
     * A terrain piece across the line of a side, between 2 blocks of the line: it goes a number of blocks outside and
     * a number of blocks inside.
     */
    private static TerrainPiece across(Cell first, Cell last, Side side, int outside, int inside, int ground,
            boolean clear) {
        int minX = Math.min(first.x(), last.x());
        int maxX = Math.max(first.x(), last.x());
        int minZ = Math.min(first.z(), last.z());
        int maxZ = Math.max(first.z(), last.z());
        int outX = -side.insideX() * outside;
        int outZ = -side.insideZ() * outside;
        int inX = side.insideX() * inside;
        int inZ = side.insideZ() * inside;
        return new TerrainPiece(minX + Math.min(outX, inX), minZ + Math.min(outZ, inZ), maxX + Math.max(outX, inX),
                maxZ + Math.max(outZ, inZ), ground, clear);
    }

    /** The box of a structure piece on the horizontal plane. */
    static Box box(BoundingBox box) {
        return new Box(box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    /** The bases of the columns of the ring, from the terrain of the line and of the near blocks. */
    private static int[] bases(WallLayout layout, Terrain terrain) {
        int size = layout.ringSize();
        int[] line = new int[size];
        int[] inside = new int[size];
        int[] outside = new int[size];
        for (int i = 0; i < size; i++) {
            Cell cell = layout.ringCell(i);
            Side side = layout.ringSide(i);
            line[i] = terrain.height(cell.x(), cell.z());
            inside[i] = terrain.height(cell.x() + NEAR_DISTANCE * side.insideX(),
                    cell.z() + NEAR_DISTANCE * side.insideZ());
            outside[i] = terrain.height(cell.x() - NEAR_DISTANCE * side.insideX(),
                    cell.z() - NEAR_DISTANCE * side.insideZ());
        }
        return PalisadeHeights.bases(line, PalisadeHeights.near(NEAR_RADIUS, line, inside, outside));
    }

    /** Gives the highest base of some blocks of the ring to all these blocks, and returns it. */
    private static int flatten(int[] bases, List<Integer> indices) {
        int level = Integer.MIN_VALUE;
        for (int index : indices) {
            level = Math.max(level, bases[index]);
        }
        for (int index : indices) {
            bases[index] = level;
        }
        return level;
    }

    /**
     * The turn of the templates of a side. The outside of the templates is to the north: the north side keeps them,
     * and the other sides turn them clockwise.
     */
    static Rotation rotation(Side side) {
        return switch (side) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    /** The origin of the template of a tower: the corner of the tower on the line, at the start of the tower. */
    static BlockPos towerOrigin(WallLayout layout, Tower tower, int level) {
        Cell start = layout.cell(tower.edge(), tower.along());
        return new BlockPos(start.x(), level, start.z());
    }

    /**
     * The origin of the template of a gate. The template is {@link WallLayout#GATE} blocks wide, and its first row is
     * outside the line.
     */
    static BlockPos gateOrigin(WallLayout layout, Gate gate, int level) {
        Side side = gate.side();
        Cell start = layout.cell(gate.edge(), layout.along(gate) - WallLayout.GATE / 2);
        return new BlockPos(start.x() - side.insideX(), level, start.z() - side.insideZ());
    }

    /** A structure piece of a template, on the terrain (projection {@code rigid}), with the processors of the wall. */
    private static PoolElementStructurePiece template(StructureTemplateManager templates, WallSettings settings,
            ResourceLocation id, BlockPos origin, Rotation rotation) {
        StructurePoolElement element = StructurePoolElement.single(id.toString(), settings.processors())
                .apply(StructureTemplatePool.Projection.RIGID);
        return new PoolElementStructurePiece(templates, element, origin, GROUND_LEVEL_DELTA, rotation,
                element.getBoundingBox(templates, origin, rotation), LiquidSettings.APPLY_WATERLOGGING);
    }

    /**
     * The piece of a segment of palisade. The columns go along the side, in the direction of the ring. A segment on
     * cliff blocks has a low palisade: the cliff flag of its first column gives the logs of all its columns.
     */
    private static PalisadePiece palisade(WallLayout layout, Segment segment, int[] bases, PalisadeBlocks blocks,
            boolean[] cliffs) {
        int edge = segment.edge();
        Side side = segment.side();
        Cell start = layout.cell(edge, segment.from());
        int[] columns = new int[segment.length()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = bases[layout.ringIndex(edge, segment.from() + i)];
        }
        int logs = CliffSide.logs(cliffs[layout.ringIndex(edge, segment.from())]);
        return new PalisadePiece(start.x(), start.z(), side.alongX(), side.alongZ(), columns, logs, blocks);
    }

    /**
     * A piece of the pools that stops an arm: a piece that is not a street.
     *
     * @param box   the box of the piece on the horizontal plane
     * @param level the Y of the top block of the floor of the piece: the lowest layer of its box
     */
    record Stop(Box box, int level) {
    }

    /**
     * An arm of a village: the rows of a straight path from a gate to the plaza, through the streets on the axis of the
     * gate. The arm starts after the inner row of the gate, and stops at the first piece on its axis that is not a
     * street, or at the outline of the wall.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one, to the inside of the village
     * @param stepZ  the Z step from a row to the next one, to the inside of the village
     * @param length the number of rows, 1 at least
     * @param end    the level of the piece at the end of the arm, or empty if the arm stops at the outline
     */
    record Arm(int startX, int startZ, int stepX, int stepZ, int length, OptionalInt end) {
        int x(int row) {
            return startX + row * stepX;
        }

        int z(int row) {
            return startZ + row * stepZ;
        }

        /**
         * The row of the arm across a position: the row with the same distance from the gate. Empty if the position is
         * not in the width of a street ({@code halfWidth} blocks on each side of the middle) or not along the arm.
         */
        OptionalInt row(int x, int z, int halfWidth) {
            int along = (x - startX) * stepX + (z - startZ) * stepZ;
            int across = (x - startX) * stepZ + (z - startZ) * stepX;
            if (along < 0 || along >= length || Math.abs(across) > halfWidth) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(along);
        }
    }

    /** The pieces of the pools that stop an arm: all the pieces that are not streets. */
    private static List<Stop> stops(List<StructurePiece> village) {
        List<Stop> stops = new ArrayList<>();
        for (StructurePiece piece : village) {
            if (!isStreet(piece)) {
                BoundingBox box = piece.getBoundingBox();
                stops.add(new Stop(box(box), box.minY()));
            }
        }
        return stops;
    }

    /** True for a street: a piece of the pools that follows the terrain. */
    static boolean isStreet(StructurePiece piece) {
        return piece instanceof PoolElementStructurePiece pool
                && pool.getElement().getProjection() == StructureTemplatePool.Projection.TERRAIN_MATCHING;
    }

    /**
     * The arm of a gate. Empty if the gate is next to a piece that is not a street.
     *
     * @param layout the layout of the wall
     * @param gate   the gate
     * @param stops  the pieces of the pools that are not streets
     */
    static Optional<Arm> arm(WallLayout layout, Gate gate, List<Stop> stops) {
        Side side = gate.side();
        Cell middle = layout.cell(gate.edge(), layout.along(gate));
        int startX = middle.x() + 2 * side.insideX();
        int startZ = middle.z() + 2 * side.insideZ();
        int length = 0;
        OptionalInt end = OptionalInt.empty();
        while (true) {
            int x = startX + length * side.insideX();
            int z = startZ + length * side.insideZ();
            if (!layout.contains(x, z)) {
                break;
            }
            Optional<Stop> stop = stops.stream().filter(candidate -> candidate.box().contains(x, z)).findFirst();
            if (stop.isPresent()) {
                end = OptionalInt.of(stop.get().level());
                break;
            }
            length++;
        }
        if (length == 0) {
            return Optional.empty();
        }
        return Optional.of(new Arm(startX, startZ, side.insideX(), side.insideZ(), length, end));
    }

    /**
     * The level of a gate that its arm can reach: the arm goes up or down by 1 block at most from a row to the next
     * one, from the level of the gate to the level of the piece at its end. A gate higher or lower than this range goes
     * to the nearest level of the range: the flat terrain of the gate clears the terrain above it.
     *
     * @param level the level of the gate from the terrain
     * @param arm   the arm of the gate, or empty
     * @return the level of the gate
     */
    static int reachable(int level, Optional<Arm> arm) {
        if (arm.isEmpty() || arm.get().end().isEmpty()) {
            return level;
        }
        int end = arm.get().end().getAsInt();
        int reach = Math.max(0, arm.get().length() - FLAT_ROWS) * PathRamp.MAX_STEP;
        return Math.max(end - reach, Math.min(end + reach, level));
    }

    /**
     * The levels of the rows of an arm: a ramp from the level of the gate to the level of the piece at the end of the
     * arm (see {@link PathRamp}). Between them, the ramp stays near the terrain.
     */
    private static int[] levels(Arm arm, int gateLevel, Terrain terrain) {
        int[] heights = new int[arm.length()];
        for (int row = 0; row < heights.length; row++) {
            heights[row] = terrain.height(arm.x(row), arm.z(row));
        }
        arm.end().ifPresent(end -> heights[heights.length - 1] = end);
        return levels(gateLevel, heights);
    }

    /**
     * The levels of the rows of an arm from the heights at its rows. The first {@link #FLAT_ROWS} rows are on the flat
     * terrain of the gate: they stay at the level of the gate. The ramp starts at the last of these rows.
     *
     * @param gateLevel the level of the gate
     * @param heights   the height of the terrain at each row, and the level of the piece at the end of the arm at the
     *                  last row
     * @return the level of each row
     */
    static int[] levels(int gateLevel, int[] heights) {
        int flat = Math.min(FLAT_ROWS, heights.length);
        int[] levels = new int[heights.length];
        Arrays.fill(levels, 0, flat, gateLevel);
        if (flat == 0 || flat == heights.length) {
            return levels;
        }
        int[] ramp = PathRamp.levels(gateLevel, Arrays.copyOfRange(heights, flat - 1, heights.length));
        System.arraycopy(ramp, 1, levels, flat, ramp.length - 1);
        return levels;
    }

    /**
     * Moves the houses on the plots of an arm up or down: the connector of a house goes 1 block above the level of the
     * arm at its plot. Thus the door of the house stays at the level of the street.
     */
    private static void moveHouses(StructureTemplateManager templates, List<StructurePiece> village, Arm arm,
            int[] levels) {
        for (Plot plot : VillagePlots.of(templates, village)) {
            if (!plot.built()) {
                continue;
            }
            BlockPos connector = plot.connector();
            OptionalInt row = arm.row(connector.getX(), connector.getZ(), STREET_HALF_WIDTH);
            if (row.isEmpty()) {
                continue;
            }
            // The house stands in front of the connector, or a setback away from it (see VillagePlan.Plot#setback).
            for (int setback = 0; setback <= VillagePlan.MAX_SETBACK; setback++) {
                BlockPos front = plot.front().relative(plot.facing(), setback);
                if (moveHouse(templates, village, plot, front, levels[row.getAsInt()])) {
                    break;
                }
            }
        }
    }

    /**
     * Moves the house with the connector of a plot at a position up or down: its connector goes 1 block above a level.
     *
     * @return true if a house has its connector at the position
     */
    private static boolean moveHouse(StructureTemplateManager templates, List<StructurePiece> village, Plot plot,
            BlockPos front, int level) {
        for (StructurePiece piece : village) {
            if (piece instanceof PoolElementStructurePiece house && !isStreet(house)
                    && house.getBoundingBox().isInside(front.getX(), house.getBoundingBox().minY(), front.getZ())) {
                OptionalInt y = VillagePlots.connectorY(templates, house, plot.target(), front.getX(), front.getZ());
                if (y.isPresent()) {
                    house.move(0, level + 1 - y.getAsInt(), 0);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The height of the terrain that the wall reads before the generation of the chunks (see {@link Terrain}). The
     * tests of the cliff side use it.
     *
     * @param heights the terrain cache of the layout
     * @return the Y of the top block of the terrain at a position (X, Z)
     */
    public static IntBinaryOperator heights(TerrainCache heights) {
        return new Terrain(heights)::height;
    }

    /**
     * The height of the terrain before the generation of the chunks: the Y of the top block. The structure reads the
     * heights at the corners of a grid of {@link TerrainCache#GRID} blocks only, and interpolates between the corners
     * (see {@link HeightGrid}). The corners are grid columns of the terrain cache of the layout: one noise object for
     * each chunk calculates them (see {@link TerrainCache}). The noise of the vanilla terrain also has cells of 4
     * blocks: the interpolation stays near the height of the column.
     */
    private record Terrain(TerrainCache heights) {
        int height(int x, int z) {
            return HeightGrid.interpolate(x, z, TerrainCache.GRID, (cornerX, cornerZ) -> heights.surface(cornerX,
                    cornerZ) - 1);
        }
    }
}
