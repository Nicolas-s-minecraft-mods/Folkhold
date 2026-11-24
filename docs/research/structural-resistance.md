# Study: structural resistance of the blocks in an event

This document studies how a storm or an earthquake can find the weak parts of a building. Issue: #1025. The model is
**planned**. The code of the events does not use it. The prototype `tools/structural/SupportPrototype.java` gives the
measured numbers.

## Summary

- The model computes a **support budget** for each block, one time for each event, only in the area of the event.
- The **anchors** are the blocks next to natural ground. The budget goes from the anchors through the connected blocks.
  Each step costs a part of the budget. A horizontal step costs more than a vertical step.
- Each material class has a maximum budget: stone carries far, wood less, glass almost nothing.
- A block with a large budget gets a lower chance of damage. A block out of budget, or with no link to an anchor, gets
  the highest chance and takes damage first.
- Measured cost on this machine: 1.3 to 5.8 ms for the normal cases, 32 ms for the worst case. Planned: a visit budget
  for each tick.

## Sources

The links of the sources are in the first column.

| Game or mod | Rule of support | Known costs and problems |
| --- | --- | --- |
| 7 Days to Die [[1]](https://7daystodie.fandom.com/wiki/Max_Load)[[2]](https://7daystodie.wiki.gg/wiki/Mass)[[3]](https://gamever.io/knowledge-base/how-structural-integrity-works-in-7-days-to-die)[[4]](https://community.7daystodie.com/topic/33689-collapses-happening-even-when-structural-integrity-rules-appear-to-be-met/) | Each block has a mass and a max load for each side. Integrity = max load ÷ mass, rounded down (brick: 65 ÷ 13 = 5 blocks) [1]. A block on the bedrock layer through the terrain has vertical support. Vertical support has no height limit. A horizontal chain falls when its mass is above the limit of a face [3]. | One removal can start a cascade [3]. Blocks above the limit can stay until a player walks on them [3]. Players report collapses from the support chain and suspect that the search does not find the best support (player claims, not verified) [4]. |
| Valheim [[5]](https://www.sportskeeda.com/esports/understanding-structural-integrity-valheim)[[6]](https://thunderstore.io/c/valheim/p/Einherjer/LoadBearing/) | Blue pieces touch the ground. Each piece gets its stability from the pieces that it touches, so the stability goes down with the distance. A vertical build is more stable than a horizontal build. Stone and iron poles give full stability to the wood on them [5]. | Values for each material: not verified (the sources give no numbers). The color overlay helps the player to find weak pieces [6]. |
| TerraFirmaCraft [[7]](https://wiki.terrafirmagreg.team/modern/field-guide/ko_kr/mechanics/support_beams.html) | A horizontal support beam supports raw rock in a box of 9 × 5 × 9. Mining raw rock near unsupported raw rock can start a collapse. Rock on a cave roof is naturally supported. | When a collapse starts, supported rock can also fall [7]. Chance of a collapse: not verified (not in the guide). |
| Medieval Engineers [[8]](https://support.keenswh.com/medievalengineers/topic/structural-integrity) | A player describes the system: the weight of the blocks, multipliers for each material and the torque. | The player asks for a rework of the model. Performance problems: not verified (not in the source). |
| Simple Block Physics [[9]](https://modrinth.com/mod/simple-block-physics) | A block with no support below searches for a support. Its range comes from its support strength (hardness by default). The search has 4 iterations by default. | The check runs only on an event: a placement, a step, a break next to it. No chunk scan. It can destroy builds [9]. |
| Block Physics [[10]](https://www.curseforge.com/minecraft/mc-mods/block-physics) | A block reaches a "support" up to a distance from its blast resistance. Fences, walls, chains and glass search for a support above them. Tree blocks have a minimum reach of 3. | Triggers: placement, break, fire, explosion. Performance: not verified (not in the source). |
| Teardown [[11]](https://gmtk.substack.com/p/how-games-do-destruction) | A part falls only when it has no link to the ground. | A large building can hang on one strand of voxels [11]. Internal algorithm: not verified. |
| Red Faction Guerrilla [[11]](https://gmtk.substack.com/p/how-games-do-destruction) | Each layer has a strength and a weight. A layer fails when its strength is below the weight above it. | The computation of the stress takes some time, so a building falls after a delay [11]. |
| Voxel continuity [[12]](https://legalian.github.io/voxel/2018/03/19/voxel-continuity-algorithm.html) | Masks of the links of each octree node. A search only when the masks cannot decide. | A search from each voxel to the ground is too slow [12]. |

Conclusions for Folkhold:

- A rule of distance and budget (Valheim, Block Physics) costs one pass on the blocks. A rule of mass and load
  (7 Days to Die, Red Faction) needs a sum of the loads, and it gives cascades that the player does not expect.
- A check only on a trigger, in a limited area (Simple Block Physics), gives no continuous simulation.
- A link to the ground only (Teardown) lets one block hold a full building. The budget removes a part of this problem.

## Model

**Grid.** The event reads a box: its area plus a margin of 16 blocks. Each column has a window of 40 blocks from its top
block down. The two values are estimates.

**Anchors.** A planned block tag `folkhold_events:ground` gives the natural ground: the tags `crack_surface` and
`crackable`, bedrock and the ores. The natural trees (natural leaves, the trunks below them, the tag `natural_tree`)
also count as ground. A block next to the ground gets the full budget of its class. A stabilizer is an anchor with the
budget 48. A position out of the box, out of the window of its column or in a chunk that does not tick is unknown. The
model assumes support there, so the edge of the area does not make false collapses.

**Propagation.** The budget of a block is `min(cap, budget of the support − loss)`, with the best support of its 6
neighbors. The loss depends on the direction from the support to the block. A bucket queue processes the budgets from
48 down to 0, so it visits each block one time. The work can stop at the end of a tick and continue at the next tick.

| Support class | Blocks | Cap | Loss up (stack) | Loss to a side (span) | Loss down (hang) |
| --- | --- | --- | --- | --- | --- |
| Resistant | stone, bricks, deepslate, concrete | 48 | 1 | 4 | 6 |
| Sturdy | cobblestone, terracotta, mud bricks | 32 | 1 | 4 | 6 |
| Fragile | wood, wool, dirt placed by a player | 24 | 1 | 3 | 6 |
| Shatters | glass, panes, ice, leaves of a player | 6 | 1 | 3 | 6 |

All the values are estimates, for review. The current class tags give the class. The tag `shatters` gives the fourth
class. A block with a better support class "resets" the budget: wood on a stone pillar starts at 24.

**Chance of damage.** The stress is `s = 1 − budget / cap`. The anchor factor is `A = 0.5 + 1.5 × s`, from 0.5 (full
budget) to 2 (no budget). The chance is `min(1, chance of the class × intensity × strength × A)`.

- A block with the budget 0, or with no link to an anchor, gets `A = 2` and takes damage first. The earthquake examines
  these blocks at the start of the shake. The storm puts them first in its selection of candidates.
- The rules of exposure and movement do not change. A block moves or breaks as today.
- The storm never tears up a resistant block (chance 0), also with no budget.
- The computation runs one time. A block that loses its support during the event follows the current rules.

**Stabilizer.** The stabilizer keeps its full protection at 16 blocks or nearer. It is also an anchor: a build linked to
it gets support out of its sphere.

## Worked examples

The prototype prints these budgets (`#` ground, `X` out of budget). Each section is one vertical plane.

Example 1: a stone wall 3 blocks thick and 10 high, with a stone ledge of 12 blocks at the top. The wall loses 1 for
each block up. The ledge loses 4 for each block. The 9th block of the ledge has 3, the last 3 blocks have no budget.

```
 39 39 39 35 31 27 23 19 15 11  7  3  X  X  X
 ...                                            (40 to 47)
 48 48 48
  #  #  #  #  #  #  #  #  #  #  #  #  #  #  #
```

Example 2: a wooden wall of 4 blocks with a wooden overhang of 10 blocks. Wood carries 6 blocks, the last 4 blocks have
no budget. In an earthquake, these 4 blocks get 2 times the chance of the class and take damage first.

```
 21 18 15 12  9  6  3  X  X  X  X
 ...                                (22, 23)
 24
  #  #  #  #  #  #  #  #  #  #  #
```

Example 3: a glass roof of 9 blocks between two stone walls. Glass carries 2 blocks from a support, so the middle 5
blocks have no budget. With a stone pillar in the middle, each span is 4 blocks, and all the glass has a budget.

```
 45  6  3  X  X  X  X  X  3  6 45          45  6  3  3  6 45  6  3  3  6 45
 46                             46          46              46              46
 48 ...                         48          48 ...          48              48
  #  #  #  #  #  #  #  #  #  #  #           #  #  #  #  #  #  #  #  #  #  #
```

Expected losses in an earthquake at the epicenter with strength 1 (prototype, sum of the chances of the exposed blocks):

| House | Exposed blocks | Current rule | With the anchor factor |
| --- | --- | --- | --- |
| Low stone house 5 × 5 × 4 | 73 | 2.19 | 1.25 (1.7 %) |
| Low wooden house 5 × 5 × 4 | 73 | 21.90 | 13.82 (18.9 %) |
| Tall wooden house 5 × 5 × 10 | 169 | 50.70 | 40.93 (24.2 %) |

The tall wooden house loses a larger part of its exposed blocks than the low wooden house. With the factor, a block
with a large budget gets less damage than with the current rule. The chances of the classes can need a new balance.

## Player builds and village buildings

- A village building stands on its foundation. In the prototype village (60 houses), no block is out of budget. The
  walls get a small factor, the roof and the overhang get a larger factor. The repairs of the villages do not change.
- A player build follows the same rules. A tall wooden tower, a long overhang and a large glass roof take more damage.
  A low house of stone takes little damage.
- The model has no mass: a thin stone pillar can hold a large roof. This is a known limit of a cheap model.

## Edge cases

- **Floating build**: no link to an anchor, so the build takes damage first. A stabilizer in the build is an anchor.
  Natural ground in the sky (a block of dirt) is also an anchor. This is a known limit.
- **Bridge**: each block takes the better path, so the middle is the weakest part. An end out of the box has support.
- **Trees**: the natural trees are anchors and do not move, as today. A treehouse on a natural tree has support.
- **Blocks that never move**: the protected blocks, the blocks with a block entity (chests, beds, signs), the attached
  blocks and their supports do not move, as today. They carry support with their class.
- **Fluids**: water and lava give no support.
- **Caves and mines**: a block below the window is unknown, so it gives support. The earthquake collapses do not change.

## What the player can do

- Build low, and use stone for the lower floors. A wooden floor on a stone wall starts at the full wood budget.
- Add pillars or walls: on a support of 4 blocks, a wooden block needs a support at 6 blocks or nearer, and a glass
  block at 2 blocks or nearer.
- Put a stabilizer in the build: full protection at 16 blocks, and an anchor for the blocks farther away.

## Cost

Measured on this machine (Java 21, 2 processors, Xeon 2.1 GHz), 3 runs of the prototype. The time is the median of 40
runs after 20 runs of warm-up. "Scan" is the part of the time for the read of the cells and the anchors.

| Case | Box | Cells read | Blocks visited | Out of budget | Median | Scan | First run |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Earthquake r = 24, 25 houses | 80 × 80 × 40 | 256,000 | 10,275 | 0 | 1.3 to 1.5 ms | 0.8 to 0.9 ms | 4.7 to 12.4 ms |
| Storm r = 48, 60 houses 9 × 9 × 8 | 128 × 128 × 40 | 655,360 | 24,660 | 0 | 4.3 to 5.8 ms | 2.2 to 3.2 ms | 3.8 to 4.6 ms |
| Storm r = 48, large castle | 128 × 128 × 48 | 786,432 | 22,344 | 388 | 3.6 to 3.9 ms | 2.3 to 2.5 ms | 6.4 to 9.4 ms |
| Storm r = 48, solid stone 24 high | 128 × 128 × 40 | 655,360 | 393,216 | 0 | 31 to 32 ms | 13.3 ms | 82 to 130 ms |

- A server tick has 50 ms. A normal case fits in one tick: 12 % of the tick at most. The worst case takes 64 % of the
  tick, too much with the other work of the server.
- Planned: a budget of 16,384 visits for each tick (estimated 0.8 to 1.7 ms from these numbers). The village takes 2
  ticks, the solid stone takes 24 ticks. The announce of an earthquake has 100 ticks, the announce of a storm has 1,200 ticks.
  With no announce, the damage waits for the end of the computation.
- Memory (computed from the array sizes): 3 bytes for each cell, about 2 MB for the storm box.
- Not measured: the read of the block states in the game. The planned reader uses the current pass on the columns.

## Answers to the questions of #1025

1. Resistance: keep the 3 classes of the tags for the chance. The budget adds a fourth class from the tag `shatters`.
2. Height: the vertical loss replaces the height. The count starts at the last support of a better class, so a house on
   a stone platform starts with a full budget.
3. Neighbors: the horizontal paths replace the count of neighbors. No block gets a chance of 0 (factor 0.5 at least).
4. Values: the caps, the losses, the range of the factor (0.5 to 2) and the visits for each tick go in the config.
5. Wind: no term for the height. The vertical loss already gives a higher chance to the high blocks.
6. Stabilizer: full protection in its sphere, and an anchor for the blocks linked to it.

## Questions for the owner

1. Stone carries farther than wood, but real stone is weak in a span. Is this rule right? Proposal: yes, it is clear.
2. Glass carries 2 blocks from a support. Is this too strict for the builds of the players? Proposal: keep it.
3. A floating build takes damage first. Proposal: yes, and the stabilizer protects it.
4. A block with a full budget gets half of the current chance. Proposal: yes, then balance the classes with a GameTest.
5. Does the player need a view of the budget (as the colors of Valheim)? Proposal: an operator command first (part 7).

## Split into issues

| Part | Work | Estimate |
| --- | --- | --- |
| 1 | `SupportGrid` and the propagation in pure Java, with a pause at a visit budget. JUnit with the 3 examples. | 5 h |
| 2 | Support classes: config values, tag `folkhold_events:ground`, class from `BlockClass` and `shatters`. JUnit. | 3 h |
| 3 | Reader: fill the grid from the loaded chunks during the announce, unknown cells, trees, stabilizer anchors. | 6 h |
| 4 | Earthquake: anchor factor, blocks out of budget first. GameTest: the low stone house and the 2 wooden houses. | 5 h |
| 5 | Storm: anchor factor, priority in the selection of candidates. GameTest. | 4 h |
| 6 | Measure the tick time in the GameTest server with a village and a castle. Update `docs/design.md`. | 3 h |
| 7 | Optional: an operator command that shows the budget of the blocks near a position. | 4 h |

Total: 26 h for parts 1 to 6, 30 h with part 7 (estimated).

