# Study: greedy and step-by-step generation of villages on terrain

This document studies the algorithms that grow a procedural settlement step by step on real terrain. It describes the Folkhold
village plan from the code, gives a short state of the art, compares each method with the constraints of Folkhold and
ranks proposals for Folkhold. Issue: #1070. The document changes no code.

## Summary

- The Folkhold plan is a greedy best-first growth from the plaza. An open end of a street with the lowest priority gets
  the next piece. The priority is the distance to the middle plus 2 blocks for each block of height difference with the
  plaza. A flood of the noise heights gives the region that the plan can use. The plan never goes back on a choice.
- This family of methods is the right family for Folkhold. It is deterministic, cheap, and it uses fixed pieces. The
  methods of the literature that give the best villages on slopes (Emilien et al. 2012, Galin et al. 2010) also grow
  step by step with a cost function. Their cost is a sum along a path, and their roads are free curves.
- The open problems do not come from the greedy order. They come from 3 local rules:
  - The first piece of each axis street does not obey the rules of the ground (#1012). The street of the bank causes
    most of the climb of the first wall.
  - Each street piece follows the surface with the projection `terrain_matching`, and the jigsaw junctions shape the
    terrain at the ends of the pieces. Nothing gives a street a planned profile, except the arms of the gates (#799).
  - The Valeholm street pieces are 21 blocks long. They cause all 6 narrow plans of the mountain measure (#1011).
- The ranked proposals start with cheap, local changes: a short Valeholm segment (about 5 agent-hours), a better place
  for the roles of the first pieces (about 5 agent-hours), and a planned profile for each street (about 8 agent-hours).
- A redesign with a street network on the contour lines and plots along it is possible inside the constraints. It costs
  about 60 to 100 agent-hours and needs new pieces for each culture.

## The current algorithm

This section describes the code of `develop` at the date of the study. The numbers come from the code and from the
measures in `docs/design.md`.

### Start of a village

1. The structure set gives a start chunk. `FortifiedVillageStructure.findGenerationPoint` puts the plaza (15 x 15
   blocks, a random rotation) on the surface of the noise at its middle (`TerrainCache`). The cache reads the noise
   columns on a grid of 4 blocks.
2. The site check (`VillageSite`) reads a grid of 3 x 3 points, 32 blocks apart. A mean slope of more than 40 % makes a
   cave village (`CaveVillage`). Deep water on more than 45 % of the points makes a maritime village.
3. The culture map and the biome at the middle give the variant: base, Sunreach, Valeholm or maritime pieces.
4. The structure check and `/locate` read only this start. `StartCheck` calculates it on a worker thread for the
   discovery of the settlements.

### Shift of the heart

`HeartSite.search` runs before the plan, on the worker thread of the generation or of the discovery (#1005, PR #1043).

- The positions are on a grid of 16 blocks, at 48 blocks or less (`heartShift`, server option). The shift and the plan
  radius stay in `max_distance_from_center` (110 blocks).
- A cheap score reads a square of 5 x 5 points, 16 blocks apart. It counts the points at 10 blocks or less from the level
  of the position, then it takes the lowest sum of the slopes. A flat start does not search.
- The 3 best positions and the start get the flood of the core with the strict rise. The plaza moves to the largest
  region when it is 20 % larger than the region of the start or more.
- Measure (`measureHeartShift`, seed 783, 20 mountain sites): 11 plazas move. The mean region goes from 626 to 767 grid
  points. The search costs 1169 ms on average and 1739 ms at most. The check of the start stays at 32 to 45 ms.
- A world flag (`folkhold_village_heart.dat`) keeps the villages of older worlds at their start.

### Ground of the plan

`PlanGround` reads the surface of the noise at the grid columns, 4 blocks apart.

| Rule | Value | Code |
|---|---|---|
| Step between 2 neighbor points | 4 blocks at most (a ramp of 1 block for each block) | `MAX_STEP` |
| Rise of the core above or below the plaza | 10 blocks (strict) or 16 blocks (high) | `MAX_RISE`, `HIGH_RISE` |
| Level of a plot | each point 6 blocks or less from the street at the connector | `MAX_PLOT_RISE` |
| Cost of a position | 2 blocks for each block of height difference with the plaza | `RISE_COST` |
| Cost on deep water | 16 blocks | `WATER_COST` |
| Slack of the wall line | 6 blocks (a cell of 4 blocks and the line of 2 blocks) | `OUTLINE_SLACK` |
| Cliff plot | face 7 blocks or more above the street (10 or more: 3 storeys) | `CLIFF_FACE`, `HIGH_CLIFF_FACE` |

- The region of the core is a flood from the grid point of the plaza. The flood crosses a step of 4 blocks or less, in
  the rise. Water does not stop it.
- The outline rule (`lineFits`) accepts a new box when each point near it, and not near an older box, is in the rise.
  Thus the free outline of the first wall stays off high ground.
- The rule sets (`VillagePlan.Rules`) go from strict to weak:

| Rule set | Rise | Region and cost | Outline rule | Level of the plots | Used by |
|---|---|---|---|---|---|
| `ALL` | 10 | yes | yes | yes | core, band |
| `HIGH` | 16 | yes | yes | yes | core |
| `NO_LEVEL` | 16 | yes | yes | no | core |
| `REGION` | 16 | yes | no | no | band |
| `NONE` | - | no | no | no | band |

- The core tries `ALL`, `HIGH` and `NO_LEVEL` in this order. It keeps the first plan with all its plots, homes and wide
  plots. Else it keeps the best plan of the 3. Thus a village on small ground is narrower (decision of the owner on #969).

### Greedy growth of the plan

`VillagePlan.Planner` grows the plan of the core from the 4 sides of the plaza.

1. The first pieces of the streets to the north and to the south come first. Their plots have roles: the bank (north),
   the first job posts (south). These first pieces do not obey the rules of the ground (`Planner.first`).
2. Each open end goes into a list. At each step, the open end with the lowest priority gets a piece:
   - The priority is the distance to the middle plus the cost of the ground at the open end.
   - A side street counts at 40 % of this value, or at 25 % in a quarter with no plot of a side street.
   - The second pieces of 2 opposite axis streets come first, as crossroads.
3. The piece is a segment or a crossroad. A side exit gets a side piece with no plot, then a segment. An axis street
   gets a crossroad 1 time in 3, and a side street 1 time in 3. A street of level 2 gets no crossroad.
4. A piece fits when its box and the boxes of its street ends are in the radius, in the flood region, free, and inside
   the outline rule. When no piece fits, the open end closes and keeps a street end of 5 blocks.
5. Each new piece adds its plots. A plot tries a cliff plot, then the area with a random setback (0 to 2 blocks) and
   shift (0 or 1 block), then the area with no setback. The level rule and the plot terrain decide if the plot is good.
6. When the home of the style does not fit, `shallowSlot` tries a wide home that is less deep. For Valeholm, it is the
   side long house (17 x 10) in place of the long house (9 x 17).
7. The growth stops when it has the plot target and enough good plots. It adds 4 pieces at most for missing wide plots.
8. The plan makes 8 layouts with different random values (`ATTEMPTS`). It keeps the layout with the smallest shortfall,
   then the fewest homes on bad plots, the fewest missing wide plots, the best share of side streets and the smallest
   radius.

The growth is greedy: it never removes a piece. The cost is local to the open end. It is not a sum along the path from
the plaza, as in a shortest-path method. The 8 random layouts and the 3 rule sets give a small search around the greedy
choice.

### Sizes of the pieces

The structure script (`tools/structures/village.py`) writes the pieces with the values of `StreetStyle`. Each street is 5
blocks wide: a path of 3 blocks and 2 edges of terrain. A street end is 5 blocks long.

| Style | Segment (plot rows) | Crossroad (plot rows) | Side piece | Home of the style | Wide homes |
|---|---|---|---|---|---|
| Base, maritime | 13 (z = 8) | 27 (z = 6, 20) | 11 | 9 x 9 | market 15 x 9 |
| Sunreach | 13 (z = 8) | 35 (z = 8, 26) | 15 | 9 x 13 | market 15 x 9, courtyard house 13 x 13 (3) |
| Valeholm | 21 (z = 6, 16) | 43 (z = 10, 32) | 19 | 9 x 17 (long house) | side long house 17 x 10 (2) |

| Tier | Plots | Core plots | Band plots | Maximum radius | Core radius | Rings |
|---|---|---|---|---|---|---|
| Hamlet | 14 | - | - | 64 | - | 0 |
| Village | 28 | - | - | 80 | - | 0 |
| Town | 42 | 14 | 28 | 96 | 64 | 1 |
| City | 53 | 14 | 19, 20 | 106 | 64 | 2 |
| Capital | 66 | 14 | 15, 18, 19 | 106 | 64 | 3 |

### Pieces in the world

- `VillagePlanPieces` makes the jigsaw pieces of the plan. A street piece has the projection `terrain_matching`. Its
  origin is at the noise surface one block before its start. Each column of its blocks then goes to the surface of the
  world (`GravityProcessor`).
- A building is a rigid piece at the noise surface of its connector. Each connection adds 2 jigsaw junctions.
- The terrain adaptation is `beard_thin`, as in a vanilla village (see "Vanilla jigsaw villages" below).

### Wall, gates, rings and bands

- The first wall has a free outline (`WallOutline`, `WallLayout.free`). It starts from the rectangle around the pieces
  of the core with a margin of 4 blocks, in cells of 4 blocks. It cuts out the high cells that touch the edge. High means
  more than the rise of the plan above the plaza (10 or 16 blocks).
- A gate is on each of the 4 axes of the plaza. Each arm, from a gate to the plaza, is a ramp with steps of 1 block at
  most (`PathRamp`). The street stairs (`StreetStepsPiece`) put a stair on each step of 1 block. A step of 2 blocks or
  more gets no stair.
- The palisade follows the terrain with steps of 1 block at most (`PalisadeHeights`). A drop of 8 blocks or more is a
  cliff side with a low palisade (`CliffSide`).
- A town, a city or a capital has rings. The band of each ring is 24 to 48 blocks, by style. It holds a row of homes, a
  ring street and a row of homes (`VillagePlan.band`). Each ring is in districts of 8 x 8 chunks (`RingDistricts`,
  `VillageRings`), each a structure with its own start. A world flag (`folkhold_village_rings.dat`) and the held
  villages (`RingDistrictHolds`) protect older worlds.

### Measures of the open problems

| Measure | Value | Source |
|---|---|---|
| Core plans at 20 mountain sites (seed 783) | 60: `ALL` 43, `HIGH` 16, `NO_LEVEL` 1 | `measureMountainPlans` |
| First walls that climb | 15 free lines, 16 rectangles, sum of the climbs 1045 | #1012 |
| Source of the climb | the bank street (22 to 217 blocks), its street end (56 to 125), the east and west first pieces (18 to 95) | #1012 |
| Narrow plans | 6, all Valeholm villages of level 8, 51 missing plots and 51 missing homes | `measureMountainPlans` |
| Worst narrow plan | 13 of 28 plots, 6 home beds, 3 of 9 job posts | `docs/design.md` |
| Home beds, job posts | 2729, 534 | `measureMountainPlans` |
| Path columns with a step of 2 blocks or more | vanilla: 11 lower, 16 higher of 3649. Tectonic: 11 and 15 of 2678 | #799 |
| Place of these steps | at the ends of the street pieces along the street, and at the plaza | #799 |
| Buildings on a foundation of 4 blocks or more | vanilla: 45 of 160. Tectonic: 30 of 133 | #799 |

#1012 also measures variants of the roles of the first pieces. No variant gives a clear gain. Fewer walls climb only
with fewer plots, and the sum of the climbs goes up. #969 shows that the Valeholm street pieces are the limit at the
mountain sites, not the depth of the homes.

## State of the art

### Growth from a seed with a cost function

Region growth and priority floods grow a set of cells from a seed. A priority queue holds the border cells, and the cell
with the best value comes next. Priority-Flood (Barnes, Lehman and Mulla) floods a digital elevation model from its
edges with such a queue. It runs in O(n) for integer heights and O(n log n) in general. It is the same structure as the
flood of `PlanGround` and the open ends of the plan.

A greedy growth is cheap and stable for a seed. It has no global view: an early choice can block a better plan. The
usual answers are restarts with other random values, a beam of several plans, or a cost that is a sum along the path. A
summed cost makes the growth a Dijkstra search from the plaza. A climb then costs more far from the plaza than near it.

### Cost surfaces and terrain-aware roads

Galin, Peytavie, Maréchal and Guérin (2010) find a road between 2 points with A* on a grid. The cost is a weighted sum of
slope, curvature, water and vegetation. Transfer functions give an infinite cost to a slope or a depth over a limit. Masks
of k neighbors give more directions than the 8 grid neighbors. Bridges and tunnels are long arcs with their own cost.
The road then becomes a smooth curve, and the terrain gets a cut and a fill with a blend profile. The paper reports less
than 1 second for a grid of 100 x 100.

For Folkhold, the important points are the slope cost along the path and the cut and fill profile of the road. A street
with a planned profile is the direct answer to the steps of #799.

### Road-first methods: L-systems, tensor fields, agents

- **L-systems.** Parish and Müller (2001) grow highways and streets with an extended L-system. Global goals (patterns,
  population density) propose a segment. Local constraints (water, parks, elevation, crossings) change or refuse it.
  Then they split the blocks into lots and make the buildings. The course notes of Aliaga (2011) describe a recursive
  split of the lots along their longest edges.
- **Tensor fields.** Chen, Esch, Wonka, Müller and Zhang (2008) guide the streets with a tensor field. The major and
  minor eigenvectors give 2 families of streets. Basis fields give grids, radial patterns and streets along boundaries.
  For a height field, the minor eigenvector follows the gradient, so the major streets follow the contour lines. The
  user edits the field. "The key idea of this paper is to use tensor fields to guide the generation of street networks."
- **Agents.** Lechner, Watson, Wilensky and Felsen grow a city with agents on a grid in NetLogo. Extender agents find
  land with no road and trace a road back to the network, and they avoid large changes of elevation. Connector agents
  add a link when the path on the network is too long. Developer agents put buildings where they raise the value of the
  land.

These methods give free street shapes. Folkhold pieces are straight, axis-aligned and of fixed length. A road-first
method fits Folkhold only when its streets are cut into such pieces.

### Parcel and plot methods

Vanegas, Kelly, Weber, Halatsch, Aliaga and Müller (2012) split a city block into parcels with 2 methods.

- **OBB split:** a recursive cut of the parcel along the minor axis of its oriented bounding box, until the area or the
  front width is in its bounds. A probability controls the street access of each parcel.
- **Straight skeleton:** the skeleton of the block gives strips along each street. Rays from the street then cut the
  strips. Each parcel faces a street.
- All the parcels of their examples generate in less than 3 seconds. A local edit takes 1 to 10 ms.

Emilien et al. (2012) grow the parcels from the road: each parcel spreads along the road, then into the land with an
anisotropic cost that has a slope term.

Folkhold has the inverse model: fixed homes and fixed plots on fixed street pieces. A split of the free land is useful
only for the yards, the spare plots and the space between the plots.

### Settlement simulation methods

- **Villages on arbitrary terrains.** Emilien, Bernhardt, Peytavie, Cani and Galin (2012) simulate the growth of a
  village. A scenario of events adds buildings of a type. A random candidate position gets an interest value from
  weighted terms: neighbors, worship, road access, slope, water, fortification and dominant height. An acceptance test
  keeps it with a probability of this value. Each new building connects to the roads with the cost of Galin et al. Then
  parcels grow, and a grammar makes the geometry. The authors "progressively generate settlement seeds and the roads
  that connect them". The skeleton of their examples takes 4 to 7 minutes, and each village type has about 150
  parameters.
- **Medieval town generators.** The open source of the Medieval Fantasy City Generator of Watabou (`TownGeneratorOS`,
  Haxe, GPL-3.0) makes a Voronoi diagram from points on a spiral. The patches nearest to the middle are the city. The
  wall is the outline of these patches, smoothed. A gate is a vertex of the wall that 2 inner patches share. A street
  goes from each gate to the middle on the edges of the patches. The generator has no terrain.

These methods give the most believable villages on slopes. They need free shapes and minutes of time. Their interest
terms are a good list of terms for a Folkhold cost.

### Constraint methods

- **Model synthesis.** Merrell (2007) takes a small example model of pieces on a 3D grid. 2 pieces can touch in a
  direction only when they touch so in the example. The method fills the grid by blocks and refills a block after a
  failure.
- **Wave function collapse.** The repository of mxgmn generalizes this idea. It collapses the cell with the lowest
  entropy, then propagates the constraints. A contradiction stops the run with no output.
- **Answer set programming.** Smith and Mateas (2011) describe a design space as an answer set program. A solver gives
  only artifacts that obey all the hard constraints. A 6 x 6 maze takes about 250 ms, and larger problems take much more
  time.

These methods give hard guarantees on local rules, for example "no step of 2 blocks between 2 street pieces". They do
not follow a cost on terrain well, and their time is hard to bound. A failure or a time limit on a worker thread is not
acceptable for a village that the discovery already knows.

### Minecraft: the GDMC competition and vanilla villages

- **GDMC.** Salge, Green, Canaan and Togelius (2018) start a competition for settlements on a given Minecraft map. The
  judges score 4 criteria from 0 to 10: adaptivity, functionality, evocative narrative and aesthetics (Salge et al.
  2021, "Impressions"). Adaptivity is the change of the settlement with the map. The judges find terrain adaptation the
  hardest part. One judge asks for paths that are easy to walk, not only possible.
- **Entries of the first year** (Salge et al. 2021, first year report). The winner samples 5000 positions and scores
  their flatness and their distance to other structures. Its roads use A* and refuse steps of 2 blocks or more. Another
  entry marks each step of 2 blocks or more as an edge and makes plots where no edge is. An entry that levels all the
  land scores low on adaptation.
- **Decentralised iterative planning.** van der Staaij et al. (2023) start at the flattest spot. Agents add roads,
  houses, walls and farms in turns. Each agent tests a random subset of the valid positions with its own fitness. The
  city wall uses a weighted A*. A map of 256 x 256 blocks takes about 2 minutes.
- **A simple entry.** Yates (2021) places the buildings with a Poisson disc and joins them with A* paths. The positions
  do not read the heights, so the buildings stand on pillars.
- **Vanilla jigsaw villages** (Minecraft 1.21.1 source, `JigsawPlacement`, `Beardifier`). The jigsaw places pieces
  from a queue, with shuffled connectors, a test of collision and a maximum depth (6 for `village_plains`). It reads no
  cost of the terrain. A street with `terrain_matching` puts each column on the surface of the world. A rigid piece adds
  its box to the beard. The beard adds a density near the box, with a Gaussian kernel of radius 12 blocks. Each jigsaw
  junction also adds a beard at its level, at 0.4 of the weight of a box. A street piece is not rigid, so only its
  junctions shape the terrain.

The beard explains a part of #799. The terrain near a junction between 2 street pieces goes to the level of this
junction. The street between 2 junctions follows the noise. This hypothesis is not verified.

### Walls that follow the terrain

- A convex hull around the pieces gives the shortest closed line, with no view of the terrain. An alpha shape (CGAL
  manual) goes from the points to the convex hull when its parameter grows. Thus it follows a concave plan.
- The Folkhold free outline is an orthogonal cousin of the alpha shape: cells of 4 blocks, high cells cut out, and
  squares of 2 x 2 and 3 x 3 cells as the size of the shape.
- A wall on a slope costs its length times the climb. van der Staaij et al. route their wall with a weighted A*.
  Emilien et al. use the walls as a fixed term in the interest and as an infinite cost for the parcels.
- A wall on a contour line climbs 0 blocks. Such a wall needs gates and towers that are not on the 4 axes of the plaza.

### Vertical density on small footprints

- The cliff houses of Folkhold stand against a rock face on a plot of 9 x 9 blocks. They have 2 or 3 storeys and 4 or 6
  beds.
- Wormald (2024) lists the usual answers to a slope: split levels, steps that follow the gradient, a lower level in the
  ground with a cut on one side, and terraces on the roofs. Split levels can "reduce the amount of excavation required".
- Emilien et al. merge houses side by side into terraced houses.
- The second entry of the first GDMC year has large houses of several floors.

For #1011, a taller piece on the same footprint gives more beds with no new plot. A cellar or a lower level on the
downhill side uses the slope that the plan refuses today.

## Fit with the constraints of Folkhold

The hard constraints of Folkhold:

1. **Fixed pieces.** The jigsaw pieces have fixed sizes. Streets are straight, 5 blocks wide and axis-aligned.
2. **Plan before the chunks.** The plan reads only the noise heights (`TerrainCache`), not the blocks.
3. **Same seed, same result.** The plan is deterministic for the world seed and the start chunk.
4. **Cost on a worker thread.** The search of the heart costs about 1.2 s for a mountain site. A new method must stay in
   this order of time.
5. **Old worlds.** A structure start keeps its pieces in the save of its start chunk. A change of the plan thus changes
   only the villages of new chunks. A change of the position of the heart, of the first wall or of the rings needs a
   world flag or a plan version: the discovery and the ring districts read the plan before the chunks generate.
6. **4 cultures.** Base, maritime, Sunreach and Valeholm have different piece sizes.
7. **Tiers.** 5 tiers, with rings and bands from the town tier.

| Method | Needs | Gives | Cost | Fixed pieces | Plan before chunks | Same seed | Worker time | Old worlds | Cultures and tiers |
|---|---|---|---|---|---|---|---|---|---|
| Greedy growth with a local cost (current) | height grid, piece sizes | compact plan on the flat side | low, about 1 s | yes | yes | yes | yes | flag only for a new heart or wall | yes |
| Growth with a summed cost (Dijkstra, priority flood) | height grid | plan that avoids far climbs | low | yes | yes | yes, with a stable order of ties | yes | as above | yes |
| Beam or restarts of the greedy growth | the current planner | fewer narrow plans | linear in the beam | yes | yes | yes | 2 to 8 times the plan | as above | yes |
| A* street with a slope cost (Galin et al.) | height grid, cost terms | streets and paths on easy slopes | medium | only when cut into pieces | yes | yes | yes for a few paths | as above | yes |
| Street profile with cut and fill | height grid, street plan | no steps of 2 blocks on streets | low | yes, with a block piece | yes | yes | yes | no flag | yes |
| L-system roads (Parish and Müller) | goals, constraints | dense free networks | medium | poor: free angles | yes | yes | yes | flag | hard |
| Tensor field streets (Chen et al.) | field from heights | streets on contour lines | medium | poor without new corner pieces | yes | yes | yes | flag | new pieces for each culture |
| Agent-based growth (Lechner et al., van der Staaij et al.) | agents, maps | believable mixed layouts | high, minutes | partial | yes | yes, with a seeded order | no | flag | hard |
| Settlement simulation (Emilien et al.) | interest terms, many parameters | believable villages on slopes | high, minutes | no | yes | yes | no | flag | hard |
| Parcel split (Vanegas et al.) | block polygons | parcels with street access | low | no: the plots have fixed sizes | yes | yes | yes | flag | not useful |
| Voronoi town (Watabou) | none | wards, wall, gates | low | no | yes | yes | yes | flag | no terrain |
| WFC, model synthesis | adjacency rules | local consistency | unbounded | yes | yes | yes | no: failures | flag | rules for each culture |
| Answer set programming | logic program, solver | hard guarantees | high | yes | yes | depends on the solver | no | flag | a large program |
| Taller pieces on the same footprint | new templates | more beds on fewer plots | content work | yes | yes | yes | yes | no flag | 4 sets of pieces |

The table shows 2 groups. The methods with free shapes (L-systems, tensor fields, simulations, parcels) give the best
layouts, but they need new pieces or minutes of time. The methods with fixed pieces and a local cost fit all the
constraints. Thus the proposals keep the greedy growth and change its local rules first.

## Proposals

The proposals are in the order of gain for the cost. Each estimate is in agent-hours, with a tolerance of 50 %. Each
change of the plan changes only the villages of new chunks. The measures are `measureMountainPlans`, `measureHeartShift`
and `VillageGroundGameTests.measureGroundAroundThePaths`.

### 1. A short Valeholm segment on small ground (#1011, #969)

- **Problem.** All 6 narrow plans of the mountain measure are Valeholm villages of level 8. The Valeholm segment is 21
  blocks long, and #969 shows that this length is the limit, not the depth of the homes.
- **Idea.** A Valeholm segment of 13 blocks with one plot row on each side. The greedy growth tries it when the segment
  of 21 blocks does not fit, as `shallowSlot` tries a less deep home.
- **Code.** `tools/structures/village.py`: a `street_next` of 13 blocks for the Norse pieces, in each Valeholm variant.
  `StreetStyle`: a short segment and its plot rows. `VillagePlan.Planner`: a new kind or a second length for `SEGMENT`.
  `VillagePlanPieces.street` selects the element by its length already.
- **Risk.** The side long house (17 x 10) does not fit a short segment. Old worlds: no flag (the heart and the wall rules
  do not change).
- **Measure.** Narrow plans, missing plots and homes, home beds and job posts of `measureMountainPlans`. The tier measures
  must not change on good ground.
- **Estimate.** 5 h.

### 2. The roles of the first pieces on good ground (#1012)

- **Problem.** The first piece of the bank street climbs 22 to 217 blocks at the climbing sites. Its closed street end
  climbs 56 to 125 blocks.
- **Idea.**
  - The plan gives the bank role to the axis street with the best ground, in the plan itself. The plaza does not turn.
    `VillagePlanPieces.street` then puts `streets_bank` on this piece.
  - A first piece with no role obeys the rules of the ground.
  - A closed end on high ground gets no street end. The path stops at the last piece.
- **Code.** `VillagePlan.Planner.first` and `role`, `VillagePlanPieces.street`. The class `RoleStreets` of the branch
  `feat/1012-first-pieces-ground` gives the score of an axis street.
- **Risk.** The bank and the first job posts move to another side of the plaza. The scan of the heart reads the signs,
  so it is not sensitive to the side. The variants of #1012 show that the gain can be small. Old worlds: the first wall
  changes, so the held villages (`RingDistrictHolds`) protect the rings. The heart flag is not necessary.
- **Measure.** The sum of the climbs, not only the count of walls that climb. Also the missing plots, beds and posts.
  Add the sum to the log line of `measureMountainPlans`.
- **Estimate.** 5 h.

### 3. A planned profile for each street (#799)

- **Problem.** 27 path columns of 3649 (vanilla) and 26 of 2678 (Tectonic) have a step of 2 blocks or more. The steps are
  at the ends of the street pieces.
- **Idea.** First, test the hypothesis of the junctions: the plan removes the junctions between 2 street pieces and
  measures again (1 h). Then give each street the profile of an arm: a ramp with steps of 1 block at most (`PathRamp`),
  near the noise surface. The street gets a cut and a fill of 1 block on each side, as Galin et al. blend a road.
- **Code.** `VillagePlanPieces` (the levels of the street pieces), `StreetStepsPiece` or a new piece with no template for
  the cut and fill, `VillagePathPiece` (shared code with the arms). A house on a plot moves to the level of its street at
  its connector, as on an arm.
- **Risk.** A house moves up or down with its street: its foundation can grow on a slope. The arms and the band paths
  must keep their own profiles.
- **Measure.** `measureGroundAroundThePaths`: the steps of 2 blocks or more must go to 0. The count of buildings on a
  foundation of 4 blocks or more must not grow.
- **Estimate.** 8 h (1 h for the test of the junctions).

### 4. A summed cost and a small beam in the greedy growth (#1012, #1011)

- **Problem.** The cost of an open end reads only its own height. A street can reach a far flat terrace across a climb.
  An early choice can block the plan, and 8 random layouts do not always find a complete plan.
- **Idea.** The cost of an open end is the cost of its parent plus the climb of the new piece. This gives a Dijkstra
  order from the plaza. Then a beam of 2 or 3 plans keeps the best partial plans after each piece, with the order of `BETTER`.
- **Code.** `VillagePlan.Planner.priority` and `cost`, and `VillagePlan.best`. The ties need a stable order for the same
  seed.
- **Risk.** The plan on flat ground must not change: the cost there is 0. A beam multiplies the time of the plan. Old
  worlds: as proposal 2.
- **Measure.** `measureMountainPlans` (narrow plans, sum of the climbs), the time of the layout in the tier measures, and
  `measureHeartShift` for the total time.
- **Estimate.** 6 h.

### 5. Taller homes on the same footprint (#1011, #974, #970)

- **Problem.** A narrow village has fewer beds and job posts than its level needs. The worst plan has 6 of 41 beds.
- **Idea.** Each style gets a home with one more storey on the footprint of its home of the style. When the plan has
  fewer plots than its tier needs, `PlotUses.leastBeds` selects the taller homes first. A house on the downhill side of
  a street can get a lower level under the street, open on the slope. The cliff houses (#663 to #665) are a model.
- **Code.** `tools/structures/village.py` (4 styles, the bed rules of `check_bed_heads`), `PlotUses`,
  `VillagePlanPieces.home`.
- **Risk.** It depends on the design of the building tiers (#974): the owner decides the tiers and their beds. A job post
  with 2 jobs in one building changes the economy.
- **Measure.** Home beds and job posts of the narrow plans in `measureMountainPlans`.
- **Estimate.** 10 h after the decision on #974 (2 h for the plan rule, 8 h for the pieces).

### 6. A plan version for the generation (all the proposals)

- **Problem.** Each change of the heart or of the first wall adds a world flag (`folkhold_village_heart.dat`,
  `folkhold_village_rings.dat`).
- **Idea.** One saved number: the version of the plan at the creation of the world. Each rule of the plan reads it.
- **Code.** A saved data of the overworld, `HeartSearch`, `VillageRings`, `VillagePlan` options.
- **Risk.** The old flags must keep their meaning. A version needs a decision of the owner on the support of old worlds.
- **Measure.** The GameTests of the old worlds (`OldWorldRingGameTests`, the heart flag test).
- **Estimate.** 3 h.

### 7. Gates off the axes and band paths with A* (#1012, #799)

- **Problem.** A gate is only on the 4 axes of the plaza. Thus the line of the wall must cross the axes, also on a slope.
- **Idea.** A gate goes to the position of its side with the smallest climb. Its arm and its band path follow an A* path
  with a slope cost, cut into fixed street pieces.
- **Code.** `WallLayout`, `VillageWall`, `PathRamp`, `VillagePathPiece`, `RingPlans` (runtime rings).
- **Risk.** The raids, the exits and the rings read the gates. Old worlds: the first wall changes (as proposal 2).
- **Measure.** The climb of the walls and of the rings in `measureMountainPlans`, `mountainWallsHaveNoGateOnACliff`.
- **Estimate.** 12 h.

### 8. Redesign: a street network on the contour lines

- **Problem.** The 4 axis streets and the straight pieces give a cross plan. On a mountain side, the best street follows
  a contour line, and the homes stand on terraces along it.
- **Idea.**
  - A field from the noise heights gives the direction of the contour lines, as the height field of Chen et al.
  - The main street follows a contour line in straight pieces with corner pieces of 90 degrees. The side streets climb
    the slope with ramps or stairs.
  - The plots go along the streets with the current plot rules. The interest terms of Emilien et al. (slope, water,
    height) give the order of the plots.
  - The wall follows the outer contour of the plan with a weighted A*, and the gates are where the streets meet it.
- **Code.** A new planner in `core/.../sim/village`, corner and short pieces for each culture in `village.py`, a new
  wall layout, new bands for the rings, and the measures.
- **Risk.** High. The rings, the bands, the raids, the repairs and the scan of the heart read the current layout. The
  4 cultures need new pieces. A plan version (proposal 6) is necessary.
- **Measure.** All the village measures, and a new measure of the length of the streets on a slope.
- **Estimate.** 60 to 100 h.

## Limits of the study

- The full text of Parish and Müller (2001) was not open: the page of the SIGGRAPH archive gives only the abstract.
  The description of the lots comes from the course notes of Aliaga.
- The devlog of Watabou does not describe the algorithm. The study reads the open source (`TownGeneratorOS`), which
  is an older version of the generator.
- The page of the wave function collapse gives no date and no full name of its author.
- The study did not open the original paper of the alpha shapes. The CGAL manual gives the definition.
- The study read summaries of the papers from a web tool, not each page of each paper. The numbers of the sources come
  from these summaries.
- The tools refused no search and no page.
- The cause of the steps of #799 (the junctions of the street pieces) is a hypothesis from the source of the game. No
  measure checks it.
- The estimates do not include the measures of the owner in game. No agent can run the game client.

## Sources

| Source | Authors | Year | URL |
|---|---|---|---|
| Procedural Modeling of Cities (SIGGRAPH archive page, abstract) | Y. I. H. Parish, P. Müller | 2001 | https://history.siggraph.org/?p=116923 |
| Urban Layouts and Road Networks (SIGGRAPH course notes) | D. G. Aliaga | 2011 | https://www.cs.purdue.edu/cgvlab/urban/sg_2011_course/umc_SG11_02_urban_layouts.pdf |
| Interactive Procedural Street Modeling | G. Chen, G. Esch, P. Wonka, P. Müller, E. Zhang | 2008 | https://web.engr.oregonstate.edu/~zhange/images/street_sig08.pdf |
| Procedural City Modeling | T. Lechner, B. Watson, U. Wilensky, M. Felsen | about 2003 (no date on the page) | https://ccl.northwestern.edu/testsite/rp/cities/papers/midgraph.cities.pdf |
| Procedural Generation of Roads | E. Galin, A. Peytavie, N. Maréchal, E. Guérin | 2010 | https://perso.liris.cnrs.fr/egalin/Articles/2010-roads.pdf |
| Procedural Generation of Villages on Arbitrary Terrains | A. Emilien, A. Bernhardt, A. Peytavie, M.-P. Cani, E. Galin | 2012 | https://perso.liris.cnrs.fr/egalin/Articles/2012-villages.pdf |
| Procedural Generation of Parcels in Urban Modeling | C. A. Vanegas, T. Kelly, B. Weber, J. Halatsch, D. G. Aliaga, P. Müller | 2012 | https://www.cs.purdue.edu/cgvlab/papers/aliaga/eg2012.pdf |
| Priority-Flood: An Optimal Depression-Filling and Watershed-Labeling Algorithm for Digital Elevation Models | R. Barnes, C. Lehman, D. Mulla | 2014 (arXiv 2015) | https://arxiv.org/abs/1511.04463 |
| Example-Based Model Synthesis | P. Merrell | 2007 | https://paulmerrell.org/wp-content/uploads/2021/06/model_synthesis.pdf |
| Example-based model synthesis (bibliographic record) | P. Merrell | 2007 | https://researchr.org/publication/Merrell07 |
| WaveFunctionCollapse (repository) | mxgmn | no date on the page | https://github.com/mxgmn/WaveFunctionCollapse |
| Answer Set Programming for Procedural Content Generation: A Design Space Approach | A. M. Smith, M. Mateas | 2011 | https://course.ccs.neu.edu/cs5150f14/readings/smith_asp4pcg.pdf |
| Generative Design in Minecraft (GDMC), Settlement Generation Competition | C. Salge, M. C. Green, R. Canaan, J. Togelius | 2018 | https://arxiv.org/abs/1803.09853 |
| The AI Settlement Generation Challenge in Minecraft: First Year Report | C. Salge, M. C. Green, R. Canaan, F. Skwarski, R. Fritsch, A. Brightmoore, S. Ye, C. Cao, J. Togelius | 2021 | https://arxiv.org/pdf/2103.14950 |
| Impressions of the GDMC AI Settlement Generation Challenge in Minecraft | C. Salge et al. (18 authors) | 2021 | https://arxiv.org/pdf/2108.02955 |
| Believable Minecraft Settlements by Means of Decentralised Iterative Planning | A. van der Staaij, J. Prins, V. L. Prins, J. Poelsma, T. Smit, M. Müller-Brockhausen, M. Preuss | 2023 | https://arxiv.org/pdf/2309.10871 |
| The Use of Poisson Disc Distribution and A* Pathfinding for Procedural Content Generation in Minecraft | C. Yates | 2021 | https://www.cs.mun.ca/~dchurchill/publications/pdf/theses/ChristopherYates_Thesis_Honours.pdf |
| TownGeneratorOS (source of the Medieval Fantasy City Generator) | watabou | no date on the page | https://github.com/watabou/TownGeneratorOS |
| Some answers and comments (devlog) | watabou | no date on the page | https://watabou.itch.io/medieval-fantasy-city-generator/devlog/1579/some-answers-and-comments |
| 2D Alpha Shapes (CGAL manual) | T. K. F. Da | current manual | https://doc.cgal.org/latest/Alpha_shapes_2/index.html |
| How to Build on a Sloped Terrain? 5 Modern Residential Projects Shaped For Their Environments With Split-Level Exteriors | J. Wormald | 2024 | https://www.archdaily.com/1015061 |
| Minecraft 1.21.1 source (NeoForm decompile): `JigsawPlacement`, `Beardifier`, `StructureTemplatePool.Projection`, `GravityProcessor`, `village_plains.json` | Mojang | 2024 | local decompile of the dev environment |
