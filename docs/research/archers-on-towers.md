# Study: firing positions of the archers on the towers

This document studies how a ranged NPC on a high post selects a position to shoot at targets below it. It applies the
results to the towers of the Folkhold villages. Issue: #1142. The document changes no code.

## Summary

- An archer on its tower shoots only a target that it gets from the alert of another unit. It does not find a target on
  the ground by itself. The cause is in the target selection of the unit, not in the walk on the platform.
- The games of the study select a position with a query: make candidate points, remove the points with no line of fire,
  then give a score to each remaining point. A tower platform has about 40 candidate points, so a query is cheap.
- The fortifications of the Middle Ages had the same dead zone at the foot of the wall. Hoardings and machicolations let
  the defenders shoot down. Flanking towers shoot along the foot of the next wall.
- Selected option: the archer selects its targets and its firing positions with one query on the platform of its post,
  and keeps a target while a position of the platform has a line of fire to it. The templates do not change.

## The problem in Folkhold

The tower template (`tools/structures/village.py`, `tower`) has a floor of 3 x 3 blocks. A ring of bottom slabs on a
beam is the parapet. The post is in the middle of the floor. The archer can step on the slabs, and an edge spot of a
slab puts half of its body over the drop (`PlatformLookoutGoal`).

These values come from the code and from a GameTest measure of #1142 (`ArcherTowerGameTests`):

- The eyes of the archer at the post are 1.16 blocks above the top of the parapet. The parapet is 2.5 blocks from the
  middle of the post. From the post, a target on the ground is hidden when it is less than about 12 blocks from the
  outer face of the tower. The measure agrees: no line from the post at 10 blocks, a line at 16 blocks.
- The vanilla target goal (`NearestAttackableTargetGoal`) searches for monsters in a box of 4 blocks above and below the
  unit. The eyes of the archer are about 8.7 blocks above the ground. Thus the archer does not find any monster on the
  ground, also with a clear line.
- The vanilla target goal also needs a line of sight from the current position of the unit. It drops a target after
  60 ticks (3 seconds) with no line of sight.
- A target from an alert (`UnitHurtByTargetGoal`) has no such limits. The lookout goal then works: the archer walks to
  an edge spot and shoots.

Measure before the change, 20 target positions around the tower, a husk that does not move, a bow:

| How the target comes | Line from a spot of the platform | Line from the post | Hits |
| --- | --- | --- | --- |
| From 17 blocks away, then to its position | 20 of 20 | 2 of 20 | 0 of 20 |
| Directly at its position | 20 of 20 | 2 of 20 | 0 of 20 |
| Directly at its position, given by an alert | 20 of 20 | 2 of 20 | 18 of 20 |

The 2 positions with no hit in the last row are 16 blocks from the tower. They are out of the attack radius
(16 blocks from the archer). The first hit comes 41 to 106 ticks after the alert.

## Findings

### Position selection is a query

- Killzone scores the waypoints near the NPC with a weighted sum of factors [1]. The attack example gives 40 points for
  a line of fire to the main threat with partial cover, 20 for proximity and 10 for the preferred range.
- The Tactical Point System of CryEngine generates the candidate points, removes points with conditions, then gives a
  score with weights [3]. It runs the cheap tests first and the ray tests last, because the ray tests are the
  most expensive step.
- The Environment Query System of Unreal Engine has the same parts: generators, a trace test that filters, and a
  distance test that filters or scores [6].
- Game AI Pro has chapters on this architecture and on natural position selection [13, 14]. (Not verified: the
  chapters did not open. The titles and the authors come from the index of the site.)
- Utility systems give a score to each option and select the highest score [11].

### The line of fire

- Van der Sterren notes that a line of sight between two waypoints often equals a line of fire [12].
- Killzone keeps a precomputed table of the lines of fire for each waypoint, because the AI needs hundreds of checks
  [1]. The table is pessimistic about cover.
- Folkhold has no static waypoints on a tower. The blocks of a village change (repairs, stone walls). Thus a ray test at
  the time of the query is better than a table.

### Firing positions and memory of the target

- In Halo 2, designers place firing positions [4]. To break cover, the AI goes to a firing position with a clear line of
  sight to the presumed position of the target.
- Halo 2 keeps a memory of each target, with its last seen position [4]. The AI makes its decisions from this memory,
  not only from what it sees now.
- In F.E.A.R., the sensors keep a list of the cover nodes near the AI [5]. The AI attacks from a cover node.

### Peek and shoot, and the dead zone at the foot of a wall

- Battlements give a defender a merlon to hide behind. The defender shows itself to shoot, then goes back behind
  the parapet [10]. Some loopholes point down to command the foot of the wall [10].
- Hoardings are wooden galleries on the outside of the wall. They let the defenders shoot down to the foot of the wall
  [8]. Machicolations are galleries of stone with the same purpose [7, 9]. They let the defenders stay behind cover, so
  they do not lean over the parapet [7].
- Towers that come out of the wall let the defenders shoot along the face and the foot of the wall [9].

## Options for Folkhold

| Option | What changes | Cost | Old worlds |
| --- | --- | --- | --- |
| A. Query for targets | The target goal of an archer on a post searches the full height. It accepts and keeps a target with a line of fire from a spot of the platform. | About 4 agent-hours | Yes |
| B. Score of the spots | The lookout goal selects the spot with a line of fire nearest to the archer, not nearest to the post. | About 1 agent-hour | Yes |
| C. Precomputed lines of fire | A table of the lines of fire for each spot of each post, as Killzone. | About 8 agent-hours | Yes |
| D. Machicolations | Openings in the floor at the edge of the platform, and a shot down through them. | About 10 agent-hours, 48 templates | No |
| E. Post at the parapet | The template puts the post next to the outer parapet. | About 3 agent-hours, 48 templates | No |

- Option C is not necessary: a platform has about 40 spots, so a query costs 40 ray tests or less for each target.
- Options D and E change the templates. The towers of the existing worlds keep their blocks. Option E does not remove
  the dead zone at the foot of the tower. It only moves the dead zone.
- Flanking fire comes with option A: an archer on the next tower sees the foot of a tower, and can take that target.

## Selected option

Options A and B, in one change. Reasons:

- The measure shows that a spot of the platform has a line of fire to all the 20 positions. The behavior is the cause,
  not the templates. The templates do not change.
- The change works in the existing worlds.
- The query follows the order of the Tactical Point System: the cheap tests first (the distance, the line from the
  current position), then the ray tests from the spots.
- The memory of the target follows Halo 2: the archer keeps a target while a spot has a line of fire to it.
- A target with no line of fire from any spot is in a true dead zone. The archer does not take it, and it selects
  another target. The guards on the ground handle the target in the dead zone.

## Result of the selected option

The same measure after the change (`ArcherTowerGameTests.towerArcherFiringPositions`). A line of fire counts when the
target is also in the attack radius from the position of the archer.

| How the target comes | Line of fire in range from the platform | Hits before | Hits after |
| --- | --- | --- | --- |
| From 17 blocks away, then to its position | 18 of 20 | 0 | 18 |
| Directly at its position | 18 of 20 | 0 | 18 |
| Directly at its position, given by an alert | 18 of 20 | 18 | 18 |

- The first hit comes 35 to 84 ticks after the start of a case.
- The archer never leaves the platform.
- A husk in a closed box at the foot of the tower is in a true dead zone. The archer does not take it, also from an
  alert. It takes a farther husk and hits it (`towerArcherTakesATargetOutOfTheDeadZone`).

## Sources

1. Straatman, van der Sterren, Beij. Killzone's AI: dynamic procedural combat tactics. GDC Europe 2005. https://www.guerrilla-games.com/media/News/Files/gdce05_killzone_ai.pdf
2. Guerrilla Games. Killzone's AI: dynamic procedural tactics (summary page). https://www.guerrilla-games.com/read/killzones-ai-dynamic-procedural-tactics
3. Crytek. Tactical Point System, CryEngine 3 documentation. https://www.cryengine.com/docs/static/engines/cryengine-3/categories/1638401/pages/1605720
4. Isla. Handling Complexity in the Halo 2 AI. GDC 2005. https://gamedeveloper.com/programming/gdc-2005-proceeding-handling-complexity-in-the-i-halo-2-i-ai
5. Orkin. Three States and a Plan: the AI of F.E.A.R. GDC 2006. https://pages.cs.wisc.edu/~dyer/cs540/handouts/gdc2006_orkin_jeff_fear.pdf
6. Epic Games. EQS node reference: tests. https://dev.epicgames.com/documentation/unreal-engine/eqs-node-reference-tests-in-unreal-engine
7. Machicolation. Wikipedia. https://en.wikipedia.org/wiki/Machicolation
8. Hoarding (castle). Wikipedia. https://en.wikipedia.org/wiki/Hoarding_(castle)
9. Military technology: the castle. Britannica. https://www.britannica.com/technology/military-technology/The-castle
10. Battlement. Wikipedia. https://en.wikipedia.org/wiki/Battlement
11. Utility system. Wikipedia. https://en.wikipedia.org/wiki/Utility_system
12. van der Sterren. Terrain reasoning for 3D action games. https://gamedeveloper.com/programming/terrain-reasoning-for-3d-action-games
13. Jack. Tactical Position Selection: an architecture and query language. Game AI Pro, chapter 26 (not verified). http://www.gameaipro.com/GameAIPro/GameAIPro_Chapter26_Tactical_Position_Selection.pdf
14. Johnson. Taming spatial queries: tips for natural position selection. Game AI Pro Online Edition 2021, chapter 5 (not verified). http://www.gameaipro.com/GameAIProOnlineEdition2021/GameAIProOnlineEdition2021_Chapter05_Taming_Spatial_Queries_Tips_for_Natural_Position_Selection.pdf
15. Minecraft 1.21.1 code: `TargetGoal` (60 ticks of memory) and `NearestAttackableTargetGoal` (search box of 4 blocks up and down), read in the decompiled sources of NeoForge 21.1.259.
