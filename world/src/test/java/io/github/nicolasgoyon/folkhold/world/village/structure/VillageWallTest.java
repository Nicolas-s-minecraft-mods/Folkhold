package io.github.nicolasgoyon.folkhold.world.village.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Segment;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import io.github.nicolasgoyon.folkhold.core.sim.village.WaterExit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.junit.jupiter.api.Test;

/** The terrain pieces, the water gates and the piers of the wall of a village. */
class VillageWallTest {
    private static final int BASE = 64;
    private static final int SEA = 63;
    private static final PierBlocks PIER = new PierBlocks(Blocks.SPRUCE_PLANKS.defaultBlockState(),
            Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_FENCE.defaultBlockState());

    /** A square village with a gate on each side. */
    private static WallLayout layout() {
        List<Gate> gates = List.of(new Gate(Side.NORTH, 0), new Gate(Side.EAST, 0), new Gate(Side.SOUTH, 0),
                new Gate(Side.WEST, 0));
        return WallLayout.plan(List.of(new Box(-25, -25, 25, 25)), 4, 40, gates);
    }

    /**
     * On water, a section of the palisade gets no terrain piece: the palisade stands on its foundation. A water gate
     * gets the flat terrain behind it only.
     */
    @Test
    void waterSideHasNoFlatTerrainOutside() {
        WallLayout layout = layout();
        int[] bases = new int[layout.ringSize()];
        Arrays.fill(bases, BASE);
        boolean[] water = new boolean[layout.ringSize()];
        Segment first = layout.segments().get(0);
        water[layout.ringIndex(first.side(), first.from())] = true;
        water[layout.ringIndex(first.side(), first.from() + 1)] = true;
        Map<Gate, Integer> levels = new HashMap<>();
        layout.gates().forEach(gate -> levels.put(gate, BASE));
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();

        List<TerrainPiece> dry = VillageWall.terrain(layout, bases, levels, new boolean[layout.ringSize()], Set.of());
        List<TerrainPiece> wet = VillageWall.terrain(layout, bases, levels, water, Set.of(north));

        assertEquals(dry.size() - 1, wet.size(), "The first section is on water: no terrain piece");
        Box outline = layout.outline();
        BoundingBox gate = wet.stream().map(TerrainPiece::getBoundingBox)
                .filter(box -> box.minX() == -2 && box.maxX() == 2 && box.maxZ() < 0).findFirst().orElseThrow();
        assertEquals(outline.minZ(), gate.minZ(), "A water gate has no flat terrain outside the line");
        assertEquals(outline.minZ() + VillageWall.ENTRANCE, gate.maxZ(), "It has the flat terrain inside");
    }

    /** A gate opens on water when 2 of the 3 points in front of it are on water. */
    @Test
    void gateOnWaterHasWaterInFront() {
        WallLayout layout = layout();
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        int line = layout.outline().minZ();

        assertTrue(VillageWall.isWaterGate(layout, north, (x, z) -> z < line - 3 ? SEA - 1 : SEA + 2, SEA),
                "Water at 4 and 6 blocks: a water gate");
        assertFalse(VillageWall.isWaterGate(layout, north, (x, z) -> z < line - 5 ? SEA - 1 : SEA + 2, SEA),
                "Water at 6 blocks only: a gate on land");
    }

    /**
     * A pier starts after the outer row of the gate and goes over the water to the first position on land. It has 4
     * rows at least and 12 rows at most. Its deck is at the level of the gate.
     */
    @Test
    void pierGoesOverTheWater() {
        WallLayout layout = layout();
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        int line = layout.outline().minZ();

        PierPiece open = VillageWall.pier(layout, north, BASE, (x, z) -> SEA - 1, SEA, PIER);
        PierPiece shore = VillageWall.pier(layout, north, BASE, (x, z) -> z < line - 8 ? SEA + 2 : SEA - 1, SEA,
                PIER);
        PierPiece shallow = VillageWall.pier(layout, north, BASE, (x, z) -> z < line - 3 ? SEA + 2 : SEA - 1, SEA,
                PIER);

        assertEquals(PierPiece.MAX_LENGTH, open.length(), "Open water: the longest pier");
        assertEquals(7, shore.length(), "Land at 9 blocks from the line: 7 rows from the row at 2 blocks");
        assertEquals(PierPiece.MIN_LENGTH, shallow.length(), "Land near the gate: the shortest pier");
        assertEquals(BASE, open.deckY(), "The deck is at the level of the gate");
        BoundingBox box = open.getBoundingBox();
        assertEquals(line - VillageWall.PIER_START, box.maxZ(), "The pier starts after the outer row of the gate");
        assertEquals(-1, box.minX());
        // The steps go along the side of the higher X: the box takes their column.
        assertEquals(2, box.maxX());
        assertTrue(open.hasSteps() && !open.openEnd(), "A pier has steps and rails on its last row");
        assertEquals(SEA - 2, BASE - open.steps() + 1, "The lowest step is one block under the top of the water");
        assertEquals(PierPiece.MAX_LENGTH - open.steps(), open.topStep(), "The steps end at the last row");
    }

    /**
     * The exit of a water gate of the outer wall on large water is a port. An inner wall, a small lake, a narrow lake
     * and a port out of the range of the start chunk give a pier.
     */
    @Test
    void largeWaterOfTheOuterWallGivesAPort() {
        WallLayout layout = layout();
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        int line = layout.outline().minZ();
        BoundingBox all = BoundingBox.infinite();

        StructurePiece port = VillageWall.exit(layout, north, BASE, (x, z) -> SEA - 1, SEA, PIER, all, true)
                .orElseThrow();
        StructurePiece inner = VillageWall.exit(layout, north, BASE, (x, z) -> SEA - 1, SEA, PIER, all, false)
                .orElseThrow();
        StructurePiece lake = VillageWall.exit(layout, north, BASE, (x, z) -> z < line - 30 ? SEA + 2 : SEA - 1, SEA,
                PIER, all, true).orElseThrow();
        StructurePiece narrow = VillageWall.exit(layout, north, BASE, (x, z) -> Math.abs(x) > 10 ? SEA + 2 : SEA - 1,
                SEA, PIER, all, true).orElseThrow();
        StructurePiece cut = VillageWall.exit(layout, north, BASE, (x, z) -> SEA - 1, SEA, PIER,
                new BoundingBox(-5, -1000, -1000, 1000, 1000, 1000), true).orElseThrow();

        assertTrue(port instanceof PortPiece, "Open sea in front of the outer wall: a port");
        assertEquals(new BoundingBox(-PortPiece.QUAY_HALF, BASE - PierPiece.MAX_DEPTH, line - PortPiece.REACH,
                PortPiece.QUAY_HALF, BASE + 2, line - PortPiece.QUAY_FROM), port.getBoundingBox());
        assertTrue(inner instanceof PierPiece, "An inner wall: a pier");
        assertTrue(lake instanceof PierPiece, "A small lake: a pier");
        assertTrue(narrow instanceof PierPiece, "A narrow lake: a pier");
        assertTrue(cut instanceof PierPiece, "A port out of the range: a pier");
    }

    /**
     * The water gate of a wall and the gate of its ring on the same water: the bridge goes from the row after the outer
     * row of the inner gate to the row before the inner row of the ring gate, at the level of the inner gate. With
     * land in the band, or with no water gate on the axis, the ring gate gets no bridge.
     */
    @Test
    void bridgeJoinsTheGatesOfTwoWallsOnTheSameWater() {
        WallLayout layout = layout();
        WallLayout ring = WallLayout.ring(layout, WallLayout.MIN_BAND);
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        Gate ringNorth = ring.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        Gate ringEast = ring.gates().stream().filter(gate -> gate.side() == Side.EAST).findFirst().orElseThrow();
        VillageWall.Wall inner = new VillageWall.Wall(layout, Map.of(north, BASE), Set.of(north), List.of());
        VillageWall.Wall dry = new VillageWall.Wall(layout, Map.of(north, BASE), Set.of(), List.of());
        int line = layout.outline().minZ();
        int ringLine = ring.outline().minZ();

        PierPiece bridge = VillageWall.bridge(ring, ringNorth, inner, (x, z) -> SEA - 1, SEA, PIER).orElseThrow();
        Optional<PierPiece> shore = VillageWall.bridge(ring, ringNorth, inner,
                (x, z) -> z < ringLine + WaterExit.BRIDGE_SHORE + 2 ? SEA + 2 : SEA - 1, SEA, PIER);

        assertEquals(line - VillageWall.PIER_START, bridge.getBoundingBox().maxZ(), "The bridge starts at the gate");
        assertEquals(ringLine + VillageWall.PIER_START, bridge.getBoundingBox().minZ(),
                "The bridge ends at the ring gate");
        assertEquals(WallLayout.MIN_BAND - 3, bridge.length());
        assertEquals(BASE, bridge.deckY());
        assertTrue(bridge.openEnd() && bridge.hasSteps(), "A bridge has steps and no rails at its 2 ends");
        assertTrue(shore.isEmpty(), "Land in the band: no bridge");
        assertTrue(VillageWall.bridge(ring, ringNorth, dry, (x, z) -> SEA - 1, SEA, PIER).isEmpty(),
                "No water gate on the axis: no bridge");
        assertTrue(VillageWall.bridge(ring, ringEast, inner, (x, z) -> SEA - 1, SEA, PIER).isEmpty(),
                "The water gate of the inner wall is on another axis: no bridge");
    }

    /**
     * A pier stops before the first row out of the range of chunks of the start chunk, also under 4 rows. A pier with
     * its first row out of the range is not a piece.
     */
    @Test
    void pierStaysInTheRangeOfTheStartChunk() {
        WallLayout layout = layout();
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        int line = layout.outline().minZ();
        int first = line - VillageWall.PIER_START;

        Optional<PierPiece> cut = VillageWall.pier(layout, north, BASE, (x, z) -> SEA - 1, SEA, PIER,
                new BoundingBox(-100, -100, first - 2, 100, 100, 100));
        Optional<PierPiece> none = VillageWall.pier(layout, north, BASE, (x, z) -> SEA - 1, SEA, PIER,
                new BoundingBox(-100, -100, first + 1, 100, 100, 100));
        BoundingBox range = VillageWall.range(new ChunkPos(2, -3));

        assertEquals(3, cut.orElseThrow().length(), "Rows at 2, 3 and 4 blocks from the line");
        assertTrue(none.isEmpty(), "The first row is out of the range");
        assertEquals(-6 * 16, range.minX());
        assertEquals(11 * 16 - 1, range.maxX());
        assertEquals(-11 * 16, range.minZ());
        assertEquals(6 * 16 - 1, range.maxZ());
    }

    /**
     * The terrain is flat along the palisade, in sections of 2 columns at their highest base, on 2 blocks inside and
     * outside the line. In front of each gate, it is flat at the level of the gate, 6 blocks outside and 3 blocks
     * inside. The terrain of a gate also clears its space.
     */
    @Test
    void terrainIsFlatAlongThePalisadeAndAtTheGates() {
        List<Gate> gates = List.of(new Gate(Side.NORTH, 0), new Gate(Side.EAST, 0), new Gate(Side.SOUTH, 0),
                new Gate(Side.WEST, 0));
        WallLayout layout = WallLayout.plan(List.of(new Box(-25, -25, 25, 25)), 4, 40, gates);
        int[] bases = new int[layout.ringSize()];
        Arrays.fill(bases, BASE);
        Segment first = layout.segments().get(0);
        bases[layout.ringIndex(Side.NORTH, first.from() + 1)] = BASE + 1;
        Map<Gate, Integer> levels = new HashMap<>();
        for (Gate gate : layout.gates()) {
            levels.put(gate, BASE - 2);
        }

        List<TerrainPiece> pieces = VillageWall.terrain(layout, bases, levels, new boolean[layout.ringSize()],
                Set.of());

        int sections = layout.segments().stream().mapToInt(segment -> (segment.length() + 1) / 2).sum();
        assertEquals(sections + 4, pieces.size());
        Box outline = layout.outline();
        TerrainPiece section = pieces.get(0);
        assertEquals(BASE + 1, section.ground(), "A section is at its highest base");
        BoundingBox box = section.getBoundingBox();
        assertEquals(outline.minX() + first.from(), box.minX());
        assertEquals(outline.minX() + first.from() + 1, box.maxX());
        assertEquals(outline.minZ() - 2, box.minZ());
        assertEquals(outline.minZ() + 2, box.maxZ());
        TerrainPiece north = pieces.get(sections);
        BoundingBox gate = north.getBoundingBox();
        assertEquals(BASE - 2, north.ground(), "The terrain of a gate is at the level of the gate");
        assertEquals(new BoundingBox(-2, BASE - 2, outline.minZ() - 6, 2, gate.maxY(), outline.minZ() + 3), gate);
        for (int i = 0; i < pieces.size(); i++) {
            TerrainPiece piece = pieces.get(i);
            assertTrue(piece.getBeardifierBox().equals(piece.getBoundingBox()), "The terrain changes near the box");
            assertEquals(i >= sections, piece.clears(), "Only the terrain of a gate clears its space: piece " + i);
        }
    }

    /**
     * An arm goes from the inner row of a gate to the first piece on its axis that is not a street, and ends at the
     * level of the floor of this piece. A street on the axis does not stop it.
     */
    @Test
    void armGoesThroughTheStreetsToThePlaza() {
        WallLayout layout = layout();
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        int line = layout.outline().minZ();
        VillageWall.Stop plaza = new VillageWall.Stop(new Box(-5, -5, 5, 5), BASE + 3);

        VillageWall.Arm arm = VillageWall.arm(layout, north, List.of(plaza)).orElseThrow();

        assertEquals(0, arm.startX());
        assertEquals(line + 2, arm.startZ(), "The arm starts after the inner row of the gate");
        assertEquals(0, arm.stepX());
        assertEquals(1, arm.stepZ());
        assertEquals(-5 - (line + 2), arm.length(), "The arm stops at the plaza");
        assertEquals(BASE + 3, arm.end().orElseThrow(), "The arm ends at the level of the plaza");
    }

    /** With no piece on its axis, an arm stops at the outline and has no end level. */
    @Test
    void armWithNoPieceStopsAtTheOutline() {
        WallLayout layout = layout();
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();

        VillageWall.Arm arm = VillageWall.arm(layout, north, List.of()).orElseThrow();

        assertTrue(arm.end().isEmpty());
        assertEquals(layout.outline().maxZ() + 1, arm.z(arm.length()), "The last row is on the outline");
    }

    /**
     * An arm off the axis of its street goes along the plots of the street. With the width of a street, a house at the
     * edge of the arm stops it: the arm does not go on the house. With the middle only, the arm goes on the house.
     */
    @Test
    void armOffTheAxisStopsAtAHouseAtItsEdge() {
        WallLayout layout = layout();
        Gate north = layout.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        int line = layout.outline().minZ();
        // A house next to the middle of the arm: its west column is the east edge of the arm.
        VillageWall.Stop house = new VillageWall.Stop(new Box(VillageWall.STREET_HALF_WIDTH, line + 8,
                VillageWall.STREET_HALF_WIDTH + 8, line + 16), BASE + 1);
        VillageWall.Stop plaza = new VillageWall.Stop(new Box(-5, -5, 5, 5), BASE + 3);

        VillageWall.Arm wide = VillageWall.arm(layout, north, List.of(house, plaza), VillageWall.STREET_HALF_WIDTH)
                .orElseThrow();
        VillageWall.Arm middle = VillageWall.arm(layout, north, List.of(house, plaza)).orElseThrow();

        assertEquals(line + 8, wide.z(wide.length()), "The arm stops at the first row of the house");
        assertEquals(BASE + 1, wide.end().orElseThrow(), "The arm ends at the level of the house");
        for (int row = 0; row < wide.length(); row++) {
            assertTrue(wide.z(row) < line + 8, "No row of the arm is on the house: row " + row);
        }
        assertEquals(-5, middle.z(middle.length()), "With the middle only, the arm goes on the house to the plaza");
    }

    /** The row of a plot: the distance from the gate along the arm, in the width of a street only. */
    @Test
    void rowOfAPositionIsItsDistanceAlongTheArm() {
        VillageWall.Arm arm = new VillageWall.Arm(10, 20, 0, 1, 15, java.util.OptionalInt.empty());

        assertEquals(4, arm.row(12, 24, VillageWall.STREET_HALF_WIDTH).orElseThrow(), "A plot on the east edge");
        assertEquals(4, arm.row(8, 24, VillageWall.STREET_HALF_WIDTH).orElseThrow(), "A plot on the west edge");
        assertTrue(arm.row(13, 24, VillageWall.STREET_HALF_WIDTH).isEmpty(), "Out of the width of the street");
        assertTrue(arm.row(10, 19, VillageWall.STREET_HALF_WIDTH).isEmpty(), "Before the first row");
        assertTrue(arm.row(10, 35, VillageWall.STREET_HALF_WIDTH).isEmpty(), "After the last row");
    }

    /**
     * A gate goes to a level that its arm reaches: an arm of 11 rows, with 2 rows on the flat terrain of the gate, goes
     * up or down by 9 blocks at most. A gate in this range, or with an arm with no end, keeps its level.
     */
    @Test
    void gateGoesToALevelThatItsArmReaches() {
        VillageWall.Arm arm = new VillageWall.Arm(0, 0, 0, 1, 11, java.util.OptionalInt.of(BASE));
        VillageWall.Arm open = new VillageWall.Arm(0, 0, 0, 1, 11, java.util.OptionalInt.empty());

        assertEquals(BASE + 9, VillageWall.reachable(BASE + 21, java.util.Optional.of(arm)), "Too high: down");
        assertEquals(BASE - 9, VillageWall.reachable(BASE - 15, java.util.Optional.of(arm)), "Too low: up");
        assertEquals(BASE + 4, VillageWall.reachable(BASE + 4, java.util.Optional.of(arm)), "In the range");
        assertEquals(BASE + 21, VillageWall.reachable(BASE + 21, java.util.Optional.of(open)), "No end");
        assertEquals(BASE + 21, VillageWall.reachable(BASE + 21, java.util.Optional.empty()), "No arm");
    }

    /**
     * The first rows of an arm are on the flat terrain behind the gate: they stay at the level of the gate. The ramp
     * starts after them, and an arm can go up or down by its number of rows minus these rows.
     */
    @Test
    void armKeepsTheLevelOfTheGateOnItsFlatTerrain() {
        int[] heights = {BASE - 5, BASE - 5, BASE - 5, BASE - 5, BASE - 5, BASE - 3};

        int[] levels = VillageWall.levels(BASE, heights);

        assertArrayEquals(new int[] {BASE, BASE, BASE - 1, BASE - 2, BASE - 3, BASE - 3}, levels);
        VillageWall.Arm arm = new VillageWall.Arm(0, 0, 0, 1, 6, java.util.OptionalInt.of(BASE));
        assertEquals(BASE + 4, VillageWall.reachable(BASE + 9, java.util.Optional.of(arm)),
                "6 rows, 2 of them flat: 4 blocks up at most");
    }

    /**
     * A segment with columns that do not keep the palisade (a maritime village): the palisade goes on the runs of kept
     * columns only.
     */
    @Test
    void maritimeSegmentKeepsItsRunsOnLand() {
        WallLayout layout = layout();
        Segment first = layout.segments().get(0);
        boolean[] keep = new boolean[layout.ringSize()];
        java.util.Arrays.fill(keep, true);
        keep[layout.ringIndex(first.side(), first.from() + 2)] = false;
        keep[layout.ringIndex(first.side(), first.from() + 3)] = false;

        List<Segment> runs = VillageWall.runs(layout, first, keep);

        assertEquals(List.of(new Segment(first.side(), first.from(), first.from() + 1),
                new Segment(first.side(), first.from() + 4, first.to())), runs);
        java.util.Arrays.fill(keep, false);
        assertTrue(VillageWall.runs(layout, first, keep).isEmpty(), "No kept column: no palisade");
    }

    /** The cliff blocks cut a segment into its full and its low parts: the parts cover the segment. */
    @Test
    void cliffCutsASegmentIntoParts() {
        WallLayout layout = layout();
        Segment first = layout.segments().get(0);
        boolean[] cliffs = new boolean[layout.ringSize()];
        cliffs[layout.ringIndex(first.side(), first.from() + 2)] = true;
        cliffs[layout.ringIndex(first.side(), first.from() + 3)] = true;

        List<Segment> parts = VillageWall.parts(layout, first, cliffs);

        assertEquals(List.of(new Segment(first.side(), first.from(), first.from() + 1),
                new Segment(first.side(), first.from() + 2, first.from() + 3),
                new Segment(first.side(), first.from() + 4, first.to())), parts);
        assertEquals(List.of(first), VillageWall.parts(layout, first, new boolean[layout.ringSize()]));
    }

    /**
     * The path of a ring gate across the band: it starts 2 blocks inside the ring line, stops at the flat terrain in
     * front of the inner gate, and ends at the level of the inner gate. An inner gate on water gives no path.
     */
    @Test
    void ringGatePathGoesAcrossTheBandToTheInnerGate() {
        WallLayout inner = layout();
        WallLayout ring = WallLayout.ring(inner, WallLayout.MIN_BAND);
        Gate north = ring.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        Gate innerNorth = inner.gates().stream().filter(gate -> gate.side() == Side.NORTH).findFirst().orElseThrow();
        VillageWall.Wall wall = new VillageWall.Wall(inner, Map.of(innerNorth, BASE + 3), Set.of(), List.of());

        VillageWall.Arm arm = VillageWall.bandArm(ring, north, wall).orElseThrow();

        WallLayout.Cell gate = ring.cell(Side.NORTH, ring.along(north));
        assertEquals(gate.x(), arm.startX());
        assertEquals(gate.z() + 2, arm.startZ());
        assertEquals(WallLayout.MIN_BAND - 2 - VillageWall.APPROACH, arm.length());
        int innerLine = inner.outline().minZ();
        assertEquals(innerLine - VillageWall.APPROACH - 1, arm.z(arm.length() - 1),
                "The path stops before the flat terrain of the inner gate");
        assertEquals(BASE + 3, arm.end().getAsInt());
        VillageWall.Wall water = new VillageWall.Wall(inner, Map.of(innerNorth, BASE), Set.of(innerNorth), List.of());
        assertTrue(VillageWall.bandArm(ring, north, water).isEmpty());
    }

    /**
     * A gate template and the terrain piece that clears its space in 2 districts: the ground fill of the gate knows
     * the cleared space, thus it does not fill the passage of the gate.
     */
    @Test
    void groundFillOfAGateKnowsTheClearedSpaceOfAnotherDistrict() {
        BoundingBox gateBox = new BoundingBox(61, BASE, 0, 65, BASE + 6, 4);
        PoolElementStructurePiece gate = new PoolElementStructurePiece(null,
                StructurePoolElement.single("minecraft:empty").apply(StructureTemplatePool.Projection.RIGID),
                new BlockPos(61, BASE, 0), 1, Rotation.NONE, gateBox, LiquidSettings.APPLY_WATERLOGGING);
        TerrainPiece clear = new TerrainPiece(63, 0, 67, 4, BASE, true);
        VillageWall.Wall ring = new VillageWall.Wall(layout(), Map.of(), Set.of(), List.of(gate, clear));

        Map<RingDistricts.District, List<StructurePiece>> districts = VillageWall.districts(List.of(ring),
                new ChunkPos(0, 0));

        List<StructurePiece> gateDistrict = districts.get(new RingDistricts.District(0, 0));
        assertTrue(districts.get(new RingDistricts.District(1, 0)).contains(clear), "The terrain piece is in cell 1");
        GroundFillPiece fill = gateDistrict.stream().filter(GroundFillPiece.class::isInstance)
                .map(GroundFillPiece.class::cast).findFirst().orElseThrow();
        assertEquals(List.of(clear.getBoundingBox()), fill.cleared());
    }
}
