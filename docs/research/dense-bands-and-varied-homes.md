# Design note: dense ring bands and homes of different sizes

This note studies 2 wishes of the owner for the faction villages. Issues: #1146 and #1149. Related issue: #974. The note
changes no code.

- **#1146.** In a town, a city or a capital, the homes of a band stand only along the ring street. The owner wants homes
  in all the space between 2 walls, as in a medieval town. The large villages look a little empty.
- **#1149.** The owner wants homes of different sizes and shapes, and a village that is less a grid.
- **#974.** Taller homes on the same footprint, with tiers. The cliff houses with a door on each storey exist already.

Each number in this note has a mark: **(measured)** or **(estimated)**. A measured number comes from the code, from the
templates or from the local script below. An estimated number comes from a calculation on paper.

## Summary

- The band has the depth of 2 rows of homes and the ring street (measured). Thus in depth, the band is full already.
- The band is empty along its length. The plan stops the ring street when the band has its plots. In a town, the ring
  street covers 222 of about 480 blocks of the ring (measured, base style). In the outer ring of a capital, it covers 170
  of about 992 blocks (measured).
- The buildings cover 12 % to 13 % of the band of a town (measured). They cover 4 % to 7 % of the bands of a capital
  (measured). A hamlet covers 12 % to 19 % of the space in its wall (measured). The owner likes the hamlets.
- The ring street of the band can hold 2 to 13 times the plots of the band (measured). Thus the first and cheapest step
  is a ring street on all the ring, with buildings on all its plots.
- The beds rule gives the plots of a band. More plots with homes give more beds than the level needs. The
  owner must decide what stands on the new plots.
- Each style has 1 to 3 footprints of homes (measured). The base style has only 9 x 9 homes.
- Recommendation: first a full ring street (option 0, about 12 h), then small and large homes (option C, about 20 h),
  then lanes in the bands (option A, about 14 h). The second row with alleys (option B, about 20 h) comes last or never.

## Current state

### Method of the measure

A local script calls the plan code of `develop` (`VillagePlan.plan` and `VillagePlan.band`). It does not run Gradle, and
it is not in the repository.

- Flat ground, good terrain on each plot, 8 seeds (0 to 7). The numbers are the mean of the 8 seeds.
- The core plan has the plots of the core (14 for a town, a city and a capital) and the core radius (64 blocks). A
  hamlet and a village have all their plots in the maximum radius.
- The inner wall is the rectangle around the plaza, the pieces and the plots of the core, with the margin of 4 blocks,
  in cells of 4 blocks. This rectangle is near the free outline on flat ground. It does not have the gate passage of 17
  blocks (`WallLayout.GATE_PASSAGE`). Thus the real areas can be a little larger.
- Each ring is the inner rectangle, larger by the band on each side. The band area is the area of the ring rectangle
  minus the area of the inner rectangle.
- A built plot is a plot that is not a spare plot. It holds a home, a house-workshop or a building of the level.
- The cover is the sum of the boxes of the home of the style on the built plots, divided by the area. The street cover
  uses the boxes of the street pieces.
- The capacity is the number of plots of the band plan with a target of 400 plots. It is the number of plots that the
  ring street can hold.
- The ring length is the perimeter of the middle line of the ring street. The street length is the sum of the lengths of
  the street pieces of the band plan.
- The maritime style has the street pieces and the homes of the base style. Thus its numbers are the numbers of the base
  style.

### Band depth

`VillagePlan.bandWidth` gives the band: 4 free blocks, 2 home depths, the street (5 blocks) and 6 free blocks, minus 1.

| Style | Home of the style | Band | Inner side | Outer side |
|---|---|---|---|---|
| Base, maritime | 9 x 9 | 32 (measured) | 4 free + 9 home | 9 home + 6 free |
| Sunreach | 9 x 13 | 40 (measured) | 4 free + 13 home | 13 home + 6 free |
| Valeholm | 9 x 17 | 48 (measured) | 4 free + 17 home | 17 home + 6 free |

The band holds one row of homes on each side of the ring street. No space is free for a second row or for a lane.

### Plots, built plots and cover of each band

The local script gives all the values of this table.

| Tier | Style | Band | Area | Plots | Built plots | Cover of the buildings | Cover of the streets | Street length | Ring length | Capacity | Cover at capacity |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Town | base | 1 | 15744 | 28 | 24 | 12.3 % | 7.1 % | 222 | 480 | 58 | 29.8 % |
| Town | Sunreach | 1 | 22560 | 28 | 24 | 12.4 % | 4.9 % | 222 | 552 | 75 | 38.9 % |
| Town | Valeholm | 1 | 28608 | 28 | 24 | 12.8 % | 3.6 % | 208 | 584 | 89 | 47.6 % |
| City | base | 1 | 15744 | 19 | 16 | 8.2 % | 5.4 % | 170 | 480 | 58 | 29.8 % |
| City | base | 2 | 23936 | 20 | 17 | 5.8 % | 3.6 % | 170 | 736 | 100 | 33.8 % |
| City | Sunreach | 1 | 22560 | 19 | 16 | 8.3 % | 3.8 % | 170 | 552 | 75 | 38.9 % |
| City | Sunreach | 2 | 35360 | 20 | 17 | 5.6 % | 2.4 % | 170 | 872 | 122 | 40.4 % |
| City | Valeholm | 1 | 28608 | 19 | 16 | 8.6 % | 3.6 % | 208 | 584 | 89 | 47.6 % |
| City | Valeholm | 2 | 47040 | 20 | 17 | 5.5 % | 2.2 % | 208 | 968 | 160 | 52.0 % |
| Capital | base | 1 | 15744 | 15 | 13 | 6.7 % | 4.6 % | 144 | 480 | 58 | 29.8 % |
| Capital | base | 2 | 23936 | 18 | 15 | 5.1 % | 3.3 % | 157 | 736 | 100 | 33.8 % |
| Capital | base | 3 | 32128 | 19 | 16 | 4.0 % | 2.6 % | 170 | 992 | 138 | 34.8 % |
| Capital | Sunreach | 1 | 22560 | 15 | 13 | 6.7 % | 3.2 % | 144 | 552 | 75 | 38.9 % |
| Capital | Sunreach | 2 | 35360 | 18 | 15 | 5.0 % | 2.2 % | 157 | 872 | 122 | 40.4 % |
| Capital | Sunreach | 3 | 48160 | 19 | 16 | 3.9 % | 1.8 % | 170 | 1192 | 170 | 41.3 % |
| Capital | Valeholm | 1 | 28608 | 15 | 13 | 7.0 % | 3.6 % | 208 | 584 | 89 | 47.6 % |
| Capital | Valeholm | 2 | 47040 | 18 | 15 | 4.9 % | 2.2 % | 208 | 968 | 160 | 52.0 % |
| Capital | Valeholm | 3 | 65472 | 19 | 16 | 3.7 % | 1.6 % | 208 | 1352 | 243 | 56.7 % |

The areas are in square blocks, the lengths in blocks. The cover at capacity counts all the plots as built plots.

For comparison, the space in the first wall (measured with the same script):

| Tier | Style | Area in the first wall | Cover of the buildings |
|---|---|---|---|
| Hamlet, and core of a town, a city or a capital | base | 7797 | 12.5 % |
| Hamlet, and core of a town, a city or a capital | Sunreach | 9417 | 14.9 % |
| Hamlet, and core of a town, a city or a capital | Valeholm | 9801 | 18.7 % |
| Village | base | 10961 | 17.7 % |
| Village | Sunreach | 13545 | 20.7 % |
| Village | Valeholm | 16905 | 21.7 % |

Findings:

- The band plots come from `band_plots` in `village_sizes.json`: 28 for a town, 19 and 20 for a city, 15, 18 and 19 for
  a capital (measured). The plan grows the ring street from the 8 starts near the axes, and stops at this target.
- Thus the ring street covers 46 % of the ring in a town (measured, base style). It covers 17 % of the outer ring of a
  capital (measured, base style).
  The corners of the band stay empty.
- The outer bands are longer, but they get the same number of plots. Thus the cover goes down from the inner band to
  the outer band.
- The ring street can hold 58 to 243 plots (measured). This is 2.1 times the plots of a town band (base) and 12.8
  times the plots of the outer band of a Valeholm capital.
- The plan of all the capacity plots costs little. The script makes all the plans of this note (8 seeds, 15 pairs of a
  tier and a style, the capacity plans included) in 1.6 s on the machine of the agent (measured).

### Plot rules that give the grid

These values come from `StreetStyle` and `VillagePlan` (measured from the code).

- A street is straight and 5 blocks wide. The base segment is 13 blocks long, with 1 plot on each side. Thus 2 base homes
  of 9 blocks have a gap of 4 blocks.
- The Valeholm segment is 21 blocks long, with 2 plot rows. The crossroads are 27 (base), 35 (Sunreach) and 43
  (Valeholm) blocks long.
- Each plot gets a random setback of 0 to 2 blocks and a random shift of 0 or 1 block. A wide home gets neither.
- All the homes of a band have the footprint of the home of the style. A band has no wide home and no crossroad.

### Footprints of the templates

These values are the sizes of the templates in `world/src/main/resources/data/folkhold_world/structure/village`
(measured). The structure script `tools/structures/village.py` writes them. The size is the box of the template: width
along the street x depth, then the height.

| Style | Homes and house-workshops | Steps 2 and 3 (same footprint) | Other homes | Wide buildings | Cliff houses |
|---|---|---|---|---|---|
| Base | 9 x 9, height 10 | height 14 and 18 | cottage, barracks, forge, storehouse: 9 x 9 | market 15 x 9 | 9 x 9, height 13 (2 storeys) and 17 (3 storeys) |
| Maritime | 9 x 9, height 10 | height 14 and 18 | fishing hut, smokehouse, fish market: 9 x 9 | market 15 x 9 | as the base style |
| Sunreach | 9 x 13, height 7 | height 11 and 15 | cottage and forge 9 x 11, courtyard houses 13 x 13 | market 15 x 9 | 9 x 9, height 10 and 14 |
| Valeholm | 9 x 17, height 16 | height 19 and 22 | side long houses 17 x 10 | market 9 x 15 | 9 x 9, height 17 and 21 |

- The base and maritime homes have 1 footprint: 9 x 9 (measured). The Sunreach homes have 3 footprints, and the Valeholm
  homes have 2 (measured).
- The bank is 9 x 9 and the mill is 9 x 8 in all the styles (measured).
- Each style has 34 job pieces of step 1 (measured). In the base and maritime styles, 13 of them have the steps 2 and 3
  (measured). In the Sunreach and Valeholm styles, all 34 have them (measured).
- A step 2 home has 4 beds, and a step 3 home has 6 beds (see "Homes that grow in place" in `docs/design.md`). Thus the
  tiers of #974 exist already for the house-workshops.

### Beds and plots

The beds rule gives the homes that the top level of the tier needs. The measure of `docs/design.md` gives, for the base
style:

| Tier | Plots | Buildings | Home beds | Beds of the level |
|---|---|---|---|---|
| Town | 42 | 36 | 74 | 61 |
| City | 53 | 45 | 82 | 81 |

The home beds are near the beds of the level. Thus a band with more homes gives more beds than the level
needs. Each option below must say what stands on the new plots.

## Options

Each sketch shows one band of the base style, as a cut across a part of the ring. The inner wall is at the top and the
ring is at the bottom. `H` is a home, `s` is a small home, `L` is a large home, `=` is a wall, `-` is a street, `|` is a
lane, `:` is an alley, `'` is a yard, and `.` is free terrain.

The estimates are in agent-hours, with a tolerance of 50 %. They do not include the checks of the owner in the game. No
agent can run the game client.

### Option 0: a ring street on all the ring

```
 =================================================================  inner wall
 . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .  4 free
  [ H ]   [ H ]   [ g ]   [ H ]   [ w ]   [ H ]   [ g ]   [ H ]     9
 -----------------------------------------------------------------  ring street, 5
  [ H ]   [ g ]   [ H ]   [ H ]   [ g ]   [ w ]   [ H ]   [ H ]     9
 . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .  6 free
 =================================================================  ring
```

`g` is a garden or a shed, `w` is a well or a woodpile: a small building with no bed.

- **What the player sees.** The ring street goes all around the band. Each plot along it has a building. The corners
  are not empty. The band looks as the streets of the core.
- **Plan.** The band plan grows the ring street up to the capacity of the band. The plots after the target get a new use:
  an outbuilding with no bed. The spare plots stay, for the growth.
- **Templates.** 3 outbuildings for each style, on the footprint of the home of the style: a fenced garden, a shed with
  a woodpile, a well with a small yard. The structure script writes the biome variants.
- **Guard paths.** The units guard the posts inside the gates (see "NPC types" in `docs/design.md`). The paths across the
  band keep their 5 rows. The ring street gives the units a way along all the band. No measure exists.
- **Caravans.** A caravan walks the path across the band to the market of the core. The path does not change.
- **Growth.** The growth uses the spare plots of the core, then of the bands. The spare share does not change. The
  outbuildings are not free plots.
- **Raids.** The raiders walk to the gates, then across the band to the heart. More buildings give more cover to the
  archers and to the raiders. No measure exists.
- **Old worlds.** A new plan version. A ring district of an old world keeps its rules. The near-village rule and the
  ring outline do not change, because the band depth does not change.
- **Cost of the world.** The number of pieces of a band goes up 2 to 13 times (measured from the capacity). The time of
  the generation, the scan of the heart and the repairs read more pieces. No measure of this cost exists.
- **Estimate.** 12 h (estimated).

| Issue | Content | Estimate |
|---|---|---|
| 0.1 | Band plan up to the capacity, the use "outbuilding", the plan version, JUnit tests | 5 h (estimated) |
| 0.2 | Outbuilding pieces in `village.py`, 4 styles and their biome variants, checks of the script | 5 h (estimated) |
| 0.3 | Measures of the tiers, the count of the pieces and the time of the ring districts, `docs/design.md` | 2 h (estimated) |

### Option A: lanes from the ring street to the 2 walls

```
 =================================================================  inner wall
 . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .  4 free
  [ H ] | | [ H ]     [ H ] | | [ H ]     [ H ] | | [ H ]           9
  [ H ] | | [ H ]     [ H ] | | [ H ]     [ H ] | | [ H ]           9
 ----------+-------------------+-------------------+--------------  ring street, 5
  [ H ] | | [ H ]     [ H ] | | [ H ]     [ H ] | | [ H ]           9
  [ H ] | | [ H ]     [ H ] | | [ H ]     [ H ] | | [ H ]           9
 . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .  6 free
 =================================================================  ring
```

- **What the player sees.** Short lanes go from the ring street toward each wall. Homes stand on the 2 sides of each
  lane. The band is full from wall to wall. Each lane ends at a closed end before the wall.
- **Plan.** The band needs space for 2 plots along a lane on each side. The band of the base style goes from 32 to 50
  blocks (estimated). The sum is 4 + 18 + 5 + 18 + 6 - 1. Sunreach goes to 66 and Valeholm to 82 (estimated). The ring street
  gets crossroads, as the streets of the core. Their side exits are the lanes: a side piece, a segment and a street end.
  The planner has this rule for the core. The band plan uses it with a limit on the lane length.
- **Templates.** None. The lanes use the street pieces of the style.
- **Density.** A lane module is 23 blocks along the ring (9 + 5 + 9). It holds 4 homes on each side of the ring street
  (estimated). Thus 100 blocks of ring hold about 35 homes, and the cover is about 56 % (estimated). A ring street with
  no lane holds about 15 homes on 100 blocks (estimated).
- **Guard paths.** The lanes are dead ends. A unit that chases a target walks more corners. The paths across the band
  keep their rows.
- **Caravans.** No change: the caravans use the paths across the band.
- **Growth.** The lanes give spare plots far from the ring street. The builders walk farther.
- **Raids.** The raiders do not need the lanes. A wider band moves the outer gates farther from the heart. The waves
  spawn 40 to 56 blocks from the heart (`spawnMinDistance`, `spawnMaxDistance`). Thus a wave of a town can spawn in a
  band, with or without lanes (estimated).
- **Old worlds.** A new plan version. The band depth changes. Thus the rings, the near-village rule and the territory
  radius change. The ring districts must stay in the grid of 5 x 5 districts of 8 chunks: about 320 blocks from the start
  chunk (measured from `RingDistricts`: 20 chunks on each side). A Valeholm capital with bands of 82 blocks reaches about 296 blocks from the plaza (estimated). This is near
  the limit.
- **Slopes.** A lane crosses the band, often up a slope. Its plots can be cliff plots, with the cliff houses of 2 and 3
  storeys.
- **Estimate.** 14 h (estimated).

| Issue | Content | Estimate |
|---|---|---|
| A.1 | Band depth for the lanes, the plan version, the check of the range of the ring districts and of the territory radius | 5 h (estimated) |
| A.2 | Crossroads and lanes in the band plan, the limit of the lane length, the closed ends before the walls | 5 h (estimated) |
| A.3 | Measures of the tiers and of the mountain sites, JUnit tests of the bands, `docs/design.md` | 4 h (estimated) |

### Option B: a second row behind the first row, with yards and alleys

```
 =================================================================  inner wall
 . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .  4 free
  [ H ][ H ]  [ H ][ H ]  [ H ][ H ]  [ H ][ H ]  [ H ][ H ]        9 back row
 : : : : : : : : : : : : : : : : : : : : : : : : : : : : : : : :  alley, 3
  ' ' ' ' ' '|' ' ' ' ' '|' ' ' ' ' '|' ' ' ' ' '|' ' ' ' ' '      yards, 2
  [ H ][ H ] | [ H ][ H ]| [ H ][ H ]| [ H ][ H ]| [ H ][ H ]        9 front row
 -----------------------------------------------------------------  ring street, 5
   (the same rows, as a mirror, on the outer side)
 =================================================================  ring
```

- **What the player sees.** 2 rows of homes on each side of the ring street. A narrow alley runs behind the front row.
  The back row faces the alley. Small yards stand between the 2 rows. Passages join the alley and the ring street.
- **Plan.** The band of the base style goes from 32 to 64 blocks (estimated). The sum is 4 + 25 + 5 + 25 + 6 - 1.
  Each side is 9 + 2 + 3 + 9 + 2 blocks. The plan adds a back plot behind each front plot, and an alley along the band.
  A passage replaces a front plot after each group of plots.
- **Templates.** An alley piece of 3 blocks and a passage piece for each style. Yard pieces with fences and small plants.
  The back homes use the homes of the style.
- **Density.** 100 blocks of ring hold about 31 homes on the 2 sides, less the passages, and the cover is about 39 %
  (estimated).
- **Guard paths.** The alleys are 3 blocks wide. A faction golem fits, but a horse with a rider does not turn well
  there. No measure exists.
- **Caravans.** No change: the caravans use the paths across the band.
- **Growth.** The back plots are spare plots for the growth. The builders reach them by the alley.
- **Raids.** The alleys give the raiders a second way along the band. The raiders go to the heart, so the effect is
  small (estimated).
- **Old worlds.** As option A: the band depth changes, with a new plan version. A Valeholm band is about 96 blocks
  (estimated: each side 17 + 2 + 3 + 17 + 2). A Valeholm capital then reaches about 338 blocks from the plaza
  (estimated). This is out of the range of the ring districts. Such a capital needs 2 rings, or a band with the second row on one side only.
- **Estimate.** 20 h (estimated).

| Issue | Content | Estimate |
|---|---|---|
| B.1 | Alley and passage pieces in `village.py`, 4 styles | 4 h (estimated) |
| B.2 | Back plots and alleys in the band plan, the plan version | 6 h (estimated) |
| B.3 | Passages, yards, band depth, check of the range of the ring districts | 5 h (estimated) |
| B.4 | Measures, JUnit tests, `docs/design.md` | 5 h (estimated) |

### Option C: plots of 2 or 3 sizes, packed along the street

```
 =================================================================  inner wall
 . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .  4 free
 [s] [ H ] [  L  ]  [s][s] [ H ]  [ L ''''] [ H ] [s]  [  L  ]      7 to 11
                             (an L shape: a home with a yard in its corner)
 -----------------------------------------------------------------  ring street, 5
 [ H ] [s] [  L  ] [ H ][ H ] [s]  [  L  ] [s][s] [ H ]  [ H ]      7 to 11
 . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .  6 free
 =================================================================  ring
```

- **What the player sees.** Small, medium and large homes along each street. The gaps between the homes change. Some
  homes have an L shape with a yard. The streets look less regular, in the core and in the bands.
- **Plan.** A plot has a size from a list: small, medium (the home of the style) and large. The plan packs the plots
  along each edge of a street piece, with a gap of 1 to 3 blocks. It chooses the sizes with random weights. The plot
  rows of `StreetStyle` become a packing rule. The setback and the shift stay.
- **Proposed sizes (estimated).** Base and maritime: small 7 x 7, medium 9 x 9, large 11 x 11. Sunreach: small 7 x 9,
  medium 9 x 13, large 13 x 13 (the courtyard houses). Valeholm: small 7 x 11, medium 9 x 17, large 17 x 10 (the side
  long houses). An L shape is a large plot with a corner of yard.
- **Templates.** A small home and a large home for each style, with their biome variants. The house-workshops keep the
  medium footprint. Thus `PlotUses` puts a job post only on a medium plot or a large plot.
- **Density.** A plot and its gap take a mean of 11 blocks along the street, in place of 13 (estimated). This gives about
  18 % more plots on the same street (estimated).
- **Beds.** A small home has fewer beds. Thus the village needs more small homes for the same beds. More plots with the
  same beds rule is the base of a dense look.
- **Guard paths, caravans, raids.** No change: the streets do not change.
- **Growth.** The growth needs a rule for the size of a new home on a free plot. A small free plot holds only a small
  home.
- **Old worlds.** A new plan version. The rings do not change, because the band depth does not change, if the large
  plots are not deeper than the home of the style.
- **Slopes.** A small plot fits more often on a slope. This helps the narrow plans of the mountain sites (#1011).
- **Estimate.** 20 h with rectangles only, 24 h with the L shapes (estimated).

| Issue | Content | Estimate |
|---|---|---|
| C.1 | Small homes in `village.py`, 4 styles, the beds rules of the script | 5 h (estimated) |
| C.2 | Large homes in `village.py`, 4 styles | 5 h (estimated) |
| C.3 | Packing of the plots along a street in `VillagePlan`, the plan version, JUnit tests | 6 h (estimated) |
| C.4 | Sizes in `PlotUses` and in the growth, measures, `docs/design.md` | 4 h (estimated) |
| C.5 | L shapes: a plot with 2 boxes, the overlap test of the plan | 4 h (estimated, optional) |

### Combinations

- **0 and C.** Option 0 fills the length of the ring. Option C gives the variety. The band depth does not change. This
  pair touches no ring rule. Total about 30 h (estimated, 2 h of shared measures).
- **A and C.** The lanes give the space, and the small homes fill the lanes. Narrow homes on short lanes give the look of
  a medieval town. Total about 32 h after option 0 (estimated).
- **B and C.** The back row holds small homes with yards. This is the look of the medieval plots with a long yard behind
  the house. Total about 38 h after option 0 (estimated).
- **Taller homes (#974).** A taller home has more beds on one plot. Thus the village needs fewer plots, and the bands
  look emptier. The tall homes fit the core and the first band, where the village is dense. The small homes of option C
  fit the outer bands. The steps 2 and 3 of the house-workshops exist already.

### Comparison

| Option | Band depth | New templates | Cover of a town band, base | Ring rules | Estimate |
|---|---|---|---|---|---|
| Current | 32 | - | 12.3 % (measured) | - | - |
| 0 | 32 | 3 outbuildings for each style | about 30 % (measured at capacity) | no change | 12 h (estimated) |
| A | 50 | none | about 56 % (estimated) | change | 14 h (estimated) |
| B | 64 | alley, passage, yards | about 39 % (estimated) | change | 20 h (estimated) |
| C | 32 | small and large homes | about 35 % with option 0 (estimated) | no change | 20 h to 24 h (estimated) |

## Recommendation

1. **Option 0 first.** It removes the main cause of #1146: the ring street stops at the target. It changes no ring rule and
   no band depth. Issue 0.1 can ship first, with the homes of the style on all the plots, if the owner accepts homes
   with no household. Then issue 0.2 adds the outbuildings. Cost: 5 h for the first visible change, 12 h in total
   (estimated).
2. **Option C next.** It answers #1149 in all the tiers, also in the hamlets and the villages. It starts with the small
   homes (C.1 and C.3), then the large homes (C.2). The L shapes (C.5) come last.
3. **Option A after a playtest of 0 and C.** If the bands still look thin, the lanes give the medieval look. The band
   depth changes, so the owner must accept larger rings.
4. **Option B only on request.** It costs the most, and its Valeholm band does not fit the range of the ring districts.
5. **#974 in parallel.** The tiers of the house-workshops exist. The design of #974 can give the beds of the small and
   large homes of option C.

Each step adds a plan version. Thus the villages of the old worlds do not change.

## Questions for the owner

1. **What stands on the new plots of a band?** The beds rule gives fewer homes than the band holds. Proposed answer:
   outbuildings with no bed (gardens, sheds, wells) on most new plots. A few homes with no household are also good.
   The population of the level does not change, because more villagers cost more server time.
2. **Which cover do you want in a band?** The hamlets that you like cover 12 % to 19 % (measured). Proposed answer: about
   30 % in all the bands (option 0), and more with the lanes later.
3. **Can the rings become larger?** Options A and B need a deeper band. Proposed answer: no for the first steps. Keep
   the band depth, and decide after a playtest of options 0 and C.
4. **How many sizes of homes, and how many beds?** Proposed answer: 3 sizes for each style. The small home has 2 beds and
   1 storey. The medium home is the home of the style. The large home has 4 beds.
5. **Do you want L shapes in the first set?** Proposed answer: no. Rectangles of 3 sizes first, the L shapes in a later
   issue.
6. **Do the hamlets and the villages also get the sizes of option C?** Proposed answer: yes. The packing rule is the same
   for the core and the bands, and the hamlets get variety too.
7. **Where do the taller homes of #974 go?** Proposed answer: in the core and the first band. The outer bands get the
   small homes, so the town is dense and tall at its middle.
8. **Is a change for the new worlds only good?** Proposed answer: yes, with a new plan version, as for the versions 2 to
   6.

## Limits of the note

- The measure uses flat ground, good terrain on each plot and the rectangle of the inner wall. A real site has a free
  outline, cut corners and bad plots. Thus the real capacity can be lower.
- The script does not measure the time of the generation, the count of the pieces in the ring districts or the scan of
  the heart. These costs are not known for a full band.
- The densities of the options are calculations on paper. No script measures them.
- The effect on the units, the raids and the caravans is not measured.
- No agent can run the game client. The look of each option is not verified in the game.
