#!/usr/bin/env python3
"""Generate the structure files of the faction village and the data files of its structure.

Output:
- world/src/main/resources/data/folkhold_world/structure/village/*.nbt: the pieces of the village structure.
- world/src/main/resources/data/folkhold_world/structure/village/<variant>/*.nbt: the pieces of the other biome
  variants (see VARIANTS).
- world/src/main/resources/data/folkhold_world/structure/village/<culture>/[<variant>/]*.nbt: the pieces of the style
  of a culture (see STYLES), with the same biome variants.
- world/src/main/resources/data/folkhold_world/worldgen/: the structure (structure/village.json), the template pools
  (template_pool/village/) and the processor lists (processor_list/village/) of each variant.
- world/src/main/resources/data/folkhold_world/tags/worldgen/biome/has_village.json: the biomes of the village, from
  the biomes of the variants.
- world/src/gametest/resources/data/folkhold_world/structure/village/center.nbt: the compound of the GameTests.

    python3 tools/structures/village.py

The village structure (type folkhold_world:fortified_village) places the pieces of the plan of its tier (the street
pieces and the buildings on their plots, see VillagePlan), then it adds the palisade, the towers and the gates around
them. The jigsaw connectors give the positions of the plots and the pools of the pieces. Pieces:
- plaza.nbt: the start piece. The village heart, 4 braziers, the alarm bell, the hall sign, the quest board and lamp
  posts. 4 jigsaw connectors for the streets: the street to the north has the plot of the bank.
- street.nbt, street_bank.nbt, street_end.nbt: the parts of a street. The paths follow the terrain. A segment has a
  plot on each side, and a connector for the end of the street. The end has a lamp post on each edge. The plan places
  the first house-workshops on the plots of the first piece of the street to the south.
- street_next.nbt, street_cross.nbt, street_side.nbt: the pieces of the planned streets, in the pools
  village/streets_next and village/streets_side (see street_cross). The connectors of the other pieces do not target
  these pools.
- house_*.nbt: houses for the plots. Each house has a porch row (z = 0) with its jigsaw connector.
- bank.nbt: the village bank. Its walls and its chests resist explosions.
- job_<id>.nbt: the building of a job (see JOBS), in the pool village/jobs. Each building has the job sign of its job.
- job_<id>_step2.nbt, job_<id>_step3.nbt: the steps of a house-workshop that grows in place, with a second and a third
  storey (see add_storey), in the pools village/jobs_step2 and village/jobs_step3.
- cliff_house_2.nbt, cliff_house_3.nbt: the cliff houses of 2 and 3 storeys, in the pool village/cliff_houses (see
  cliff_house). The back wall stands against a rock face, and each storey has its own door.
- market.nbt: the market square, in the pool village/market. It takes the plot of one house, and it has the market
  sign in front of the market post (see market).
- tower.nbt: a tower of the palisade, with the stair along the palisade. The outside of the template is to the west
  and to the north: the shape of a corner tower. The towers between the corners use the same template.
- gate.nbt: a gate of 3 blocks in the palisade. The outside of the template is to the north.
The palisade comes from code: it follows the terrain.
- cave/*.nbt: the pieces of the cave village (see CAVE_PIECES): the main hall (plaza.nbt), the tunnels of the
  streets, the tunnel and the gate of the entrance, and a carved room for each building. The variant of the cave
  village in the structure has the pieces of the folder cave (see cave_variant).
  The shell of rock of a cave piece comes from the processor folkhold_world:rock_shell (see ROCK_SHELL).

The compound of the GameTests (center.nbt) is a village in one piece: palisade, 4 corner towers, 2 gates, paths, a
plaza with the village heart and 4 house plots.

Conventions: y = 0 is the ground layer (foundations, floors, paths). y = 1 is the first layer above ground.
The compound gets a foundation of FOUNDATION layers under its ground layer (see with_foundation).
"""
import dataclasses
import functools
import json
import os
import random
import sys
from collections.abc import Callable

sys.path.insert(0, os.path.dirname(__file__))
from nbt import Template  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "world", "src")
DATA = os.path.join(ROOT, "main", "resources", "data", "folkhold_world")
OUT = os.path.join(DATA, "structure", "village")
WORLDGEN = os.path.join(DATA, "worldgen")
TEST_OUT = os.path.join(ROOT, "gametest", "resources", "data", "folkhold_world", "structure", "village")

# The namespace of the structure, the pools and the jigsaw names, and the namespace of the Folkhold blocks.
NS = "folkhold_world"
BLOCKS = "folkhold_blocks"
WATCH_POST = f"{BLOCKS}:watch_post"
HEART = f"{BLOCKS}:village_heart"
HOUSE_POOL = f"{NS}:village/houses"
BANK_POOL = f"{NS}:village/bank"
BANK_CHEST = f"{BLOCKS}:bank_chest"
BANK_WALL = f"{BLOCKS}:bank_wall"
PLOT = f"{NS}:plot"
HOUSE_FRONT = f"{NS}:house_front"
# The jigsaw names and the pools of the streets.
STREET = f"{NS}:street"
STREET_NEXT = f"{NS}:street_next"
PLAZA_EXIT = f"{NS}:plaza_exit"
STREET_ENDS_POOL = f"{NS}:village/street_ends"
# The pools of the planned streets (see street_cross). No connector of the jigsaw layout targets them.
NEXT_STREETS_POOL = f"{NS}:village/streets_next"
SIDE_STREETS_POOL = f"{NS}:village/streets_side"
FIRST_STREETS_POOL = f"{NS}:village/streets_first"
BANK_STREETS_POOL = f"{NS}:village/streets_bank"
# The village heart gives the banner of the faction to each banner of the village.
BANNER = "minecraft:white_banner"
WALL_BANNER = "minecraft:white_wall_banner"

LOG_Y = {"axis": "y"}


def stairs(facing, half="bottom"):
    return {"facing": facing, "half": half, "shape": "straight", "waterlogged": "false"}


def wall_torch(facing):
    """A wall torch. facing: the direction of the torch, away from the block that holds it."""
    return "minecraft:wall_torch", {"facing": facing}


# ---------------------------------------------------------------- shapes of the connected blocks

# A template stores the block state, and the game does not update the shape of a block from a template. connect gives
# each fence, wall and pane of a template the state that the game gives it from its neighbors (FenceBlock, WallBlock,
# IronBarsBlock). Variant.apply calls it after the block replacement of the variant.
SIDES = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
# The side on the left of a direction (Direction.getCounterClockWise), and the opposite side of a side.
LEFT = {"north": "west", "west": "south", "south": "east", "east": "north"}
TOWARD = {"north": "south", "south": "north", "west": "east", "east": "west"}
AIR = ("minecraft:air", {})


def is_fence(name):
    return name.endswith("_fence")


def is_gate(name):
    return name.endswith("_fence_gate")


def is_wall(name):
    """True for a block of the tag minecraft:walls. A block of a mod with a name in _wall (bank_wall) is not a wall."""
    return name.startswith("minecraft:") and name.endswith("_wall")


def is_pane(name):
    """True for an IronBarsBlock: the glass panes and the iron bars."""
    return name.endswith("_pane") or name.endswith("iron_bars")


def box(x1, y1, z1, x2, y2, z2):
    """A box of a collision shape, in 1/16 of a block (Block.box)."""
    return x1, y1, z1, x2, y2, z2


FULL = (box(0, 0, 0, 16, 16, 16),)
# The blocks with no collision shape (plants, torches, banners, signs, rails, pressure plates, cobwebs).
NO_COLLISION = ("minecraft:air", "minecraft:cave_air", "minecraft:allium", "minecraft:cornflower",
                "minecraft:dandelion", "minecraft:oxeye_daisy", "minecraft:poppy", "minecraft:fern", "minecraft:short_grass",
                "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:wheat", "minecraft:carrots",
                "minecraft:potatoes", "minecraft:beetroots", "minecraft:attached_melon_stem",
                "minecraft:attached_pumpkin_stem", "minecraft:sugar_cane", "minecraft:sweet_berry_bush",
                "minecraft:cobweb", "minecraft:rail", "minecraft:torch", "minecraft:wall_torch")
NO_COLLISION_SUFFIXES = ("_banner", "_sign", "_pressure_plate", "_sapling")
# The blocks with a shape that is not full and that has no full face, with the boxes that touch the bottom of the
# block. These boxes give the bottom face of the block (see covers). A block with no box here has no box on its
# bottom face.
PARTIAL = {
    "minecraft:chest": (box(1, 0, 1, 15, 14, 15),),
    "minecraft:anvil": (box(2, 0, 2, 14, 4, 14),),
    "minecraft:decorated_pot": (box(1, 0, 1, 15, 16, 15),),
    "minecraft:flower_pot": (box(5, 0, 5, 11, 6, 11),),
    "minecraft:campfire": (box(0, 0, 0, 16, 7, 16),),
    "minecraft:stonecutter": (box(0, 0, 0, 16, 9, 16),),
    "minecraft:farmland": (box(0, 0, 0, 16, 15, 16),),
    "minecraft:dirt_path": (box(0, 0, 0, 16, 15, 16),),
    "minecraft:cake": (box(1, 0, 1, 15, 8, 15),),
    "minecraft:lectern": (box(0, 0, 0, 16, 2, 16), box(4, 2, 4, 12, 14, 12)),
    "minecraft:candle_cake": (box(1, 0, 1, 15, 8, 15),),
    "minecraft:cauldron": (), "minecraft:water_cauldron": (), "minecraft:grindstone": (),
    "minecraft:bell": (), "minecraft:chain": (), "minecraft:lightning_rod": (), "minecraft:cocoa": (),
    "folkhold_blocks:brazier": (box(1, 0, 1, 15, 24, 15),),
    "folkhold_blocks:quest_board": (),
}
# The doors: the box of a door by its facing, when it is closed (DoorBlock).
DOOR = {"south": box(0, 0, 0, 16, 16, 3), "north": box(0, 0, 13, 16, 16, 16),
        "west": box(13, 0, 0, 16, 16, 16), "east": box(0, 0, 0, 3, 16, 16)}


def half_box(side, y1, y2, also=None):
    """The box of the half of a block on a side, from y1 to y2. also: a second side, for the quarter of the block on
    the 2 sides."""
    x1, z1, x2, z2 = 0, 0, 16, 16
    for s in (side, also):
        if s == "north":
            z2 = 8
        elif s == "south":
            z1 = 8
        elif s == "west":
            x2 = 8
        elif s == "east":
            x1 = 8
    return box(x1, y1, z1, x2, y2, z2)


def cross(post, arm, props, height):
    """The collision shape of a fence, a pane or a wall (CrossCollisionBlock, WallBlock): a post of 2 * post in the
    middle, and an arm of 2 * arm to each connected side."""
    lo, hi, alo, ahi = 8 - post, 8 + post, 8 - arm, 8 + arm
    arms = {"north": box(alo, 0, 0, ahi, height, ahi), "south": box(alo, 0, alo, ahi, height, 16),
            "west": box(0, 0, alo, ahi, height, ahi), "east": box(alo, 0, alo, 16, height, ahi)}
    boxes = [box(lo, 0, lo, hi, height, hi)] if props.get("up", "true") == "true" else []
    return tuple(boxes + [arms[side] for side in SIDES if props.get(side, "false") not in ("false", "none")])


def collision(name, props):
    """The collision shape of a block state, as boxes (see box), as in the game (getCollisionShape). For the blocks of
    the templates, it also gives the full faces (isFaceSturdy) and the bottom face under which a wall side is tall
    (WallBlock). A block that the rules do not know is a full block."""
    if is_wall(name):
        return cross(4, 3, props, 24)
    if is_fence(name):
        return cross(2, 2, props, 24)
    if is_pane(name):
        return cross(1, 1, props, 16)
    if is_gate(name):
        if props.get("open") == "true":
            return ()
        return (box(0, 0, 6, 16, 24, 10) if props["facing"] in ("north", "south") else box(6, 0, 0, 10, 24, 16),)
    if name.endswith("_slab"):
        return {"bottom": (box(0, 0, 0, 16, 8, 16),), "top": (box(0, 8, 0, 16, 16, 16),)}.get(props["type"], FULL)
    if name.endswith("_stairs"):
        facing, shape = props["facing"], props.get("shape", "straight")
        y1, y2 = (8, 16) if props["half"] == "bottom" else (0, 8)
        slab = box(0, 0, 0, 16, 8, 16) if props["half"] == "bottom" else box(0, 8, 0, 16, 16, 16)
        left, right = LEFT[facing], TOWARD[LEFT[facing]]
        steps = {"straight": [half_box(facing, y1, y2)],
                 "outer_left": [half_box(facing, y1, y2, left)], "outer_right": [half_box(facing, y1, y2, right)],
                 "inner_left": [half_box(facing, y1, y2), half_box(left, y1, y2)],
                 "inner_right": [half_box(facing, y1, y2), half_box(right, y1, y2)]}[shape]
        return (slab, *steps)
    if name.endswith("_door"):
        facing = props["facing"]
        if props["open"] == "true":
            facing = LEFT[facing] if props["hinge"] == "right" else TOWARD[LEFT[facing]]
        return (DOOR[facing],)
    if name.endswith("_carpet"):
        return (box(0, 0, 0, 16, 1, 16),)
    if name.endswith("_bed"):
        return (box(0, 0, 0, 3, 3, 3), box(0, 0, 13, 3, 3, 16), box(13, 0, 0, 16, 3, 3), box(13, 0, 13, 16, 3, 16))
    if name == "minecraft:lantern":
        return () if props.get("hanging") == "true" else (box(5, 0, 5, 11, 7, 11),)
    if name == "minecraft:composter":
        return tuple(box(*b) for b in ((0, 0, 0, 16, 2, 16), (0, 0, 0, 2, 16, 16), (14, 0, 0, 16, 16, 16),
                                       (0, 0, 0, 16, 16, 2), (0, 0, 14, 16, 16, 16)))
    if name.startswith("folkhold_blocks:building_sign_") or name == "folkhold_blocks:job_sign":
        return (box(0, 0, 6, 16, 24, 10) if props["facing"] in ("north", "south") else box(6, 0, 0, 10, 24, 16),)
    if name in PARTIAL:
        return PARTIAL[name]
    if name in NO_COLLISION or name.endswith(NO_COLLISION_SUFFIXES) or name.startswith("minecraft:potted_"):
        return ()
    return FULL


def face(boxes, side):
    """The cells (1/16 x 1/16) of a face of a block that its boxes cover. side: north, south, west, east or down."""
    cells = set()
    for x1, y1, z1, x2, y2, z2 in boxes:
        y2 = min(y2, 16)
        if side == "down" and y1 == 0:
            cells.update((x, z) for x in range(x1, x2) for z in range(z1, z2))
        elif side == "north" and z1 == 0 or side == "south" and z2 == 16:
            cells.update((x, y) for x in range(x1, x2) for y in range(y1, y2))
        elif side == "west" and x1 == 0 or side == "east" and x2 == 16:
            cells.update((z, y) for z in range(z1, z2) for y in range(y1, y2))
    return cells


def is_sturdy(state, side):
    """True if the face of a block on a side is full (BlockState.isFaceSturdy with SupportType.FULL)."""
    return len(face(collision(*state), side)) == 16 * 16


def is_exception(name):
    """True for the blocks that a fence, a wall or a pane does not connect to (Block.isExceptionForConnection)."""
    return (name.endswith("_leaves") or name.endswith("shulker_box")
            or name in ("minecraft:barrier", "minecraft:carved_pumpkin", "minecraft:jack_o_lantern",
                        "minecraft:melon", "minecraft:pumpkin"))


# The test boxes of a wall on the bottom face of the block above it (WallBlock): the post and the 4 sides.
WALL_TESTS = {None: (7, 7, 9, 9), "north": (7, 0, 9, 9), "south": (7, 7, 9, 16), "west": (0, 7, 9, 9),
              "east": (7, 7, 16, 9)}
# The blocks over a wall that raise its post (the block tag minecraft:wall_post_override).
WALL_POST_OVERRIDE = ("minecraft:torch", "minecraft:soul_torch", "minecraft:redstone_torch", "minecraft:tripwire")


def raises_post(name):
    """True for a block of the tag minecraft:wall_post_override: the standing torches, the tripwire, the standing and
    wall signs (not the hanging signs), the banners and the pressure plates."""
    if not name.startswith("minecraft:"):
        return False
    return (name in WALL_POST_OVERRIDE or name.endswith("_banner") or name.endswith("_pressure_plate")
            or name.endswith("_sign") and not name.endswith("_hanging_sign"))


def covers(above, side=None):
    """True if the bottom face of the block above a wall covers the test box of a side of the wall (side), or of its
    post (no side), as in the game (WallBlock.isCovered). above is the name and the properties of the block above the
    wall. A full face (a full block, a bottom slab, a carpet) covers all the test boxes."""
    x1, z1, x2, z2 = WALL_TESTS[side]
    cells = face(collision(*above), "down")
    return all((x, z) in cells for x in range(x1, x2) for z in range(z1, z2))


def connects(block, other, side):
    """True if a fence, a wall or a pane (block: its name) connects to the block on a side (other: the name and the
    properties of the neighbor)."""
    name, props = other
    sturdy = not is_exception(name) and is_sturdy(other, TOWARD[side])
    if is_pane(block):
        return sturdy or is_pane(name) or is_wall(name)
    # A fence gate connects across its sides (FenceGateBlock.connectsToDirection).
    gate = is_gate(name) and (props["facing"] in ("north", "south")) == (side in ("west", "east"))
    if is_wall(block):
        return sturdy or is_wall(name) or is_pane(name) or gate
    # A wooden fence connects to a wooden fence, and the nether brick fence to the nether brick fence.
    same = is_fence(name) and ("nether_brick" in name) == ("nether_brick" in block)
    return sturdy or same or gate


def state_at(t, x, y, z):
    """The name and the properties of a block of the template, as in the generation: a position with no block is air,
    and a jigsaw block is its final state. A structure void (the shell of a cave piece, see ROCK_SHELL) is air: the
    builders and the tests of the shapes skip it."""
    entry = t.blocks.get((x, y, z))
    if not entry:
        return AIR
    (name, props), nbt = entry
    if name == "minecraft:structure_void":
        return AIR
    if name == "minecraft:jigsaw":
        final, _, rest = nbt["final_state"].partition("[")
        return final, dict(part.split("=") for part in rest.rstrip("]").split(",") if part)
    return name, dict(props)


def wall_state(t, x, y, z, name):
    """The state of a wall from its neighbors (WallBlock.updateShape): the sides, then the post."""
    above = state_at(t, x, y + 1, z)
    props = {}
    for side, (dx, dz) in SIDES.items():
        joined = connects(name, state_at(t, x + dx, y, z + dz), side)
        props[side] = ("tall" if covers(above, side) else "low") if joined else "none"
    none = {side: props[side] == "none" for side in SIDES}
    if is_wall(above[0]) and above[1]["up"] == "true":
        up = True
    elif all(none.values()) or none["north"] != none["south"] or none["east"] != none["west"]:
        up = True
    elif props["north"] == props["south"] == "tall" or props["east"] == props["west"] == "tall":
        up = False
    else:
        up = raises_post(above[0]) or covers(above)
    props["up"] = "true" if up else "false"
    return props


def connect(t):
    """Gives each fence, wall and pane of the template the state that the game gives it from its neighbors in the
    template. A position with no block is air. The walls go from the top to the bottom: the shape of a wall depends on
    the wall above it."""
    shaped = sorted((pos for pos, ((name, _), _) in t.blocks.items()
                     if is_fence(name) or is_wall(name) or is_pane(name)), key=lambda p: (-p[1], p))
    for x, y, z in shaped:
        (name, old), nbt = t.blocks[(x, y, z)]
        if is_wall(name):
            props = wall_state(t, x, y, z, name)
        else:
            props = {side: "true" if connects(name, state_at(t, x + dx, y, z + dz), side) else "false"
                     for side, (dx, dz) in SIDES.items()}
        props["waterlogged"] = dict(old).get("waterlogged", "false")
        t.set(x, y, z, name, props, nbt)
    return t


def cobble(rng):
    return "minecraft:mossy_cobblestone" if rng.random() < 0.2 else "minecraft:cobblestone"


# ---------------------------------------------------------------- center (walled compound)

SIZE = 39
HEIGHT = 14
MID = SIZE // 2  # 19
GATE = range(MID - 1, MID + 2)  # 18..20
TOWER = 5
# The platform of a tower: the archer post is in this layer, and the archer stands on it.
PLATFORM_Y = 7
# The corner posts of a tower go up to this layer. The roof starts above it.
POST_TOP_Y = 10


def tower(t, rng, tx, tz):
    """Corner tower of 5 x 5 blocks with its lower corner at (tx, tz).

    - The base: a cobblestone course, then spruce planks, with a spruce log at each corner. The corner logs go up to
      the roof. A beam of stripped spruce logs is around the platform.
    - The platform: the archer post in the middle, a low parapet of slabs (the archer shoots over it), and a roof of
      dark oak on the corner posts.
    - The stair: 2 rows of stairs along the palisade, from the ground of the compound to an opening in the parapet
      (see tower_stair). Mobs walk up the stairs with no jump.
    - A small room in the base, with a door to the compound.
    """
    west_side = tx == 0
    north_side = tz == 0
    inner_x = tx + TOWER - 1 if west_side else tx
    # The 2 rows of the stair, next to the palisade, and the row of the door, away from the stair.
    stair_rows = (tz + 1, tz + 2) if north_side else (tz + 3, tz + 2)
    door_z = tz + 3 if north_side else tz + 1
    for dx in range(TOWER):
        for dz in range(TOWER):
            x, z = tx + dx, tz + dz
            t.set(x, 0, z, cobble(rng))
            ring = dx in (0, TOWER - 1) or dz in (0, TOWER - 1)
            corner = dx in (0, TOWER - 1) and dz in (0, TOWER - 1)
            for y in range(1, PLATFORM_Y):
                if corner:
                    t.set(x, y, z, "minecraft:spruce_log", LOG_Y)
                elif ring:
                    t.set(x, y, z, cobble(rng) if y <= 2 else "minecraft:spruce_planks")
                else:
                    t.set(x, y, z, "minecraft:air")
            if corner:
                for y in range(PLATFORM_Y, POST_TOP_Y + 1):
                    t.set(x, y, z, "minecraft:spruce_log", LOG_Y)
                continue
            if ring:
                axis = "x" if dz in (0, TOWER - 1) else "z"
                t.set(x, PLATFORM_Y, z, "minecraft:stripped_spruce_log", {"axis": axis})
                # Low parapet: the archer shoots over it.
                t.set(x, PLATFORM_Y + 1, z, "minecraft:spruce_slab", {"type": "bottom", "waterlogged": "false"})
            else:
                t.set(x, PLATFORM_Y, z, "minecraft:spruce_planks")
                t.set(x, PLATFORM_Y + 1, z, "minecraft:air")
            for y in range(PLATFORM_Y + 2, POST_TOP_Y + 1):
                t.set(x, y, z, "minecraft:air")
    tower_roof(t, tx, tz)
    # Arrow slits in the two outer faces.
    outer_x = tx if west_side else tx + TOWER - 1
    outer_z = tz if north_side else tz + TOWER - 1
    t.set(outer_x, 4, tz + 1, "minecraft:air")
    t.set(outer_x, 4, tz + 3, "minecraft:air")
    t.set(tx + 2, 4, outer_z, "minecraft:air")
    # Door to the compound (2 blocks high) in the inner face along x, and a barrel in the room.
    t.set(inner_x, 1, door_z, "minecraft:air")
    t.set(inner_x, 2, door_z, "minecraft:air")
    t.set(tx + 2, 1, stair_rows[0], "minecraft:barrel", {"facing": "up", "open": "false"})
    tower_stair(t, rng, inner_x, west_side, stair_rows)
    # Archer post in the middle of the platform: the archer stands on it.
    t.set(tx + 2, PLATFORM_Y, tz + 2, WATCH_POST)
    # Light of the room, under the archer post.
    t.set(tx + 2, PLATFORM_Y - 1, tz + 2, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})
    # Light of the platform: a lantern under the roof, in the inner corner (the archer shoots to the outside).
    lamp_x = tx + 3 if west_side else tx + 1
    lamp_z = tz + 3 if north_side else tz + 1
    t.set(lamp_x, POST_TOP_Y + 1, lamp_z, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})
    # Light above the door, on the side of the compound.
    door_side = "east" if west_side else "west"
    t.set(inner_x + (1 if west_side else -1), 3, door_z, *wall_torch(door_side))


def roof_stairs(dx, dz, low, high):
    """The dark oak stairs of a ring of a pyramid roof, at (dx, dz) of the ring from low to high.

    Each stair faces the middle of the roof. A corner of the ring is an outer corner: its high quarter is toward the
    middle.
    """
    west, east, north, south = dx == low, dx == high, dz == low, dz == high
    if north and west:
        facing, shape = "south", "outer_left"
    elif north and east:
        facing, shape = "south", "outer_right"
    elif south and west:
        facing, shape = "north", "outer_right"
    elif south and east:
        facing, shape = "north", "outer_left"
    elif west:
        facing, shape = "east", "straight"
    elif east:
        facing, shape = "west", "straight"
    elif north:
        facing, shape = "south", "straight"
    else:
        facing, shape = "north", "straight"
    return "minecraft:dark_oak_stairs", {"facing": facing, "half": "bottom", "shape": shape, "waterlogged": "false"}


def tower_roof(t, tx, tz):
    """Pyramid roof of dark oak on the corner posts of a tower: a ring of 5 x 5, then a ring of 3 x 3 around a slab.

    The slab is in the middle of the top ring, not above it: a builder on a scaffolding in the tower reaches it.
    """
    for ring in range(2):
        y = POST_TOP_Y + 1 + ring
        low, high = ring, TOWER - 1 - ring
        for dx in range(low, high + 1):
            for dz in range(low, high + 1):
                if dx in (low, high) or dz in (low, high):
                    t.set(tx + dx, y, tz + dz, *roof_stairs(dx, dz, low, high))
                else:
                    t.set(tx + dx, y, tz + dz, "minecraft:air")
    t.set(tx + 2, POST_TOP_Y + 2, tz + 2, "minecraft:dark_oak_slab", {"type": "bottom", "waterlogged": "false"})
    t.set(tx + 2, POST_TOP_Y + 3, tz + 2, "minecraft:air")


# The number of stairs from the ground of the compound to the platform of a tower.
STAIR_STEPS = PLATFORM_Y


def tower_stair(t, rng, inner_x, west_side, rows):
    """Straight stair of 2 rows along the palisade, from the ground of the compound to the platform of a tower.

    The stair k (1 to STAIR_STEPS) is in layer k, on a base of cobblestone and spruce planks. The highest stair is
    next to the tower, and an opening in the parapet leads to the platform. Mobs walk up the stairs with no jump,
    and nothing is above the stairs. A path is at the foot of the stair.
    """
    toward = -1 if west_side else 1
    facing = "west" if west_side else "east"
    for z in rows:
        for k in range(1, STAIR_STEPS + 1):
            x = inner_x - toward * (STAIR_STEPS + 1 - k)
            for y in range(1, k):
                t.set(x, y, z, cobble(rng) if y == 1 else "minecraft:spruce_planks")
            t.set(x, k, z, "minecraft:spruce_stairs", stairs(facing))
            for y in range(k + 1, k + 4):
                t.set(x, y, z, "minecraft:air")
        t.set(inner_x - toward * (STAIR_STEPS + 1), 0, z, "minecraft:dirt_path")
        # The opening in the parapet, above the beam of the platform.
        t.set(inner_x, PLATFORM_Y + 1, z, "minecraft:air")


def in_tower_stair(x, z):
    """True if (x, z) is under a stair of a tower: no torch on the palisade there."""
    rows = (1, 2, SIZE - 3, SIZE - 2)
    span = range(TOWER, TOWER + STAIR_STEPS)
    return z in rows and (x in span or SIZE - 1 - x in span)


def palisade_column(t, rng, x, z, pointed):
    t.set(x, 0, z, cobble(rng))
    for y in range(1, 5):
        t.set(x, y, z, "minecraft:spruce_log", LOG_Y)
    if pointed:
        t.set(x, 5, z, "minecraft:spruce_fence")


def gate(t, rng, z, facing):
    """Gate of 3 blocks in the wall at line z, with fence gates and a lintel."""
    for x in GATE:
        t.set(x, 0, z, "minecraft:dirt_path")
        t.set(x, 1, z, "minecraft:spruce_fence_gate",
              {"facing": facing, "in_wall": "false", "open": "false", "powered": "false"})
        t.set(x, 2, z, "minecraft:air")
        t.set(x, 3, z, "minecraft:air")
        t.set(x, 4, z, "minecraft:spruce_planks")
        t.set(x, 5, z, "minecraft:spruce_slab", {"type": "bottom", "waterlogged": "false"})
    for x in (GATE[0] - 1, GATE[-1] + 1):
        t.set(x, 0, z, cobble(rng))
        for y in range(1, 6):
            t.set(x, y, z, "minecraft:stripped_spruce_log", LOG_Y)
        t.set(x, 6, z, "minecraft:lantern", {"hanging": "false", "waterlogged": "false"})


def lamp_post(t, x, z):
    t.set(x, 1, z, "minecraft:spruce_fence")
    t.set(x, 2, z, "minecraft:spruce_fence")
    t.set(x, 3, z, "minecraft:lantern", {"hanging": "false", "waterlogged": "false"})


def palisade_torches(t):
    """Wall torches on the inner face of the palisade, between the towers and the gates, not under the stairs."""
    for i in range(TOWER + 2, SIZE - TOWER - 1, 6):
        if i in GATE or i in (GATE[0] - 1, GATE[-1] + 1):
            continue
        if not in_tower_stair(i, 1):
            t.set(i, 3, 1, *wall_torch("south"))
            t.set(i, 3, SIZE - 2, *wall_torch("north"))
        t.set(1, 3, i, *wall_torch("east"))
        t.set(SIZE - 2, 3, i, *wall_torch("west"))


def with_margin(t, margin):
    """A copy of the template, moved by the margin in x and z, in a template that is larger by 2 × margin."""
    sx, sy, sz = t.size
    out = Template(sx + 2 * margin, sy, sz + 2 * margin)
    for (x, y, z), entry in t.blocks.items():
        out.blocks[(x + margin, y, z + margin)] = entry
    return out


FOUNDATION = 3
SOLID_GROUND = ("minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:stone_bricks",
                "minecraft:chiseled_stone_bricks")


def with_foundation(t, margin):
    """A copy of the template on a foundation of FOUNDATION layers, under the compound (not under the margin).

    The ground layer of the compound becomes complete: grass blocks between the paths and the buildings. Under
    the ground layer, the foundation is dirt, and cobblestone under the stone blocks (walls, towers, plaza). The
    foundation fills the holes of the terrain under the village. The structure starts FOUNDATION blocks lower, so
    the ground layer stays at the level of the terrain.
    """
    sx, sy, sz = t.size
    out = Template(sx, sy + FOUNDATION, sz)
    for (x, y, z), entry in t.blocks.items():
        out.blocks[(x, y + FOUNDATION, z)] = entry
    for x in range(margin, sx - margin):
        for z in range(margin, sz - margin):
            ground = out.get(x, FOUNDATION, z)
            if ground is None:
                out.set(x, FOUNDATION, z, "minecraft:grass_block", {"snowy": "false"})
            below = "minecraft:cobblestone" if ground in SOLID_GROUND else "minecraft:dirt"
            for y in range(FOUNDATION):
                out.set(x, y, z, below)
    return out


def outer_banners(t):
    """Wall banners on the outer face of the palisade. t is the template with a margin of 1 block."""
    last = SIZE + 1  # the margin line on the far side
    spots = [GATE[0] - 1, GATE[-1] + 1, 10, SIZE - 11]
    for i in spots:
        c = i + 1  # palisade coordinate in the template with the margin
        t.set(c, 4, 0, WALL_BANNER, {"facing": "north"})
        t.set(c, 4, last, WALL_BANNER, {"facing": "south"})
    for i in (10, MID, SIZE - 11):
        c = i + 1
        t.set(0, 4, c, WALL_BANNER, {"facing": "west"})
        t.set(last, 4, c, WALL_BANNER, {"facing": "east"})


def path_block(rng):
    roll = rng.random()
    if roll < 0.1:
        return "minecraft:gravel"
    if roll < 0.2:
        return "minecraft:coarse_dirt"
    return "minecraft:dirt_path"


def center():
    rng = random.Random(1)
    t = Template(SIZE, HEIGHT, SIZE)
    # Clear the inside: trees and terrain above ground.
    t.fill(1, 1, 1, SIZE - 2, HEIGHT - 1, SIZE - 2, "minecraft:air")

    # Palisade between the towers.
    for i in range(TOWER, SIZE - TOWER):
        pointed = i % 2 == 0
        for (x, z) in ((i, 0), (i, SIZE - 1), (0, i), (SIZE - 1, i)):
            palisade_column(t, rng, x, z, pointed)

    for tx in (0, SIZE - TOWER):
        for tz in (0, SIZE - TOWER):
            tower(t, rng, tx, tz)

    gate(t, rng, 0, "north")
    gate(t, rng, SIZE - 1, "south")

    # Paths: north-south through the gates, east-west to the house plots.
    for z in range(1, SIZE - 1):
        for x in GATE:
            t.set(x, 0, z, path_block(rng))
    for x in range(TOWER, SIZE - TOWER):
        for z in GATE:
            t.set(x, 0, z, path_block(rng))

    # Plaza with the village heart and 4 braziers.
    lo, hi = MID - 5, MID + 5
    for x in range(lo, hi + 1):
        for z in range(lo, hi + 1):
            border = x in (lo, hi) or z in (lo, hi)
            t.set(x, 0, z, "minecraft:stone_bricks" if border else cobble(rng))
    t.set(MID, 0, MID, "minecraft:chiseled_stone_bricks")
    t.set(MID, 1, MID, HEART)
    # Alarm bell on the plaza, next to the paths. The heart rings it in an alert.
    t.set(MID - 4, 0, MID - 2, "minecraft:stone_bricks")
    t.set(MID - 4, 1, MID - 2, "minecraft:bell", {"attachment": "floor", "facing": "north", "powered": "false"})
    for dx in (-3, 3):
        for dz in (-3, 3):
            t.set(MID + dx, 0, MID + dz, "minecraft:stone_bricks")
            t.set(MID + dx, 1, MID + dz, f"{BLOCKS}:brazier", {"lit": "false"})

    # Village hall sign on the plaza, between the heart and the north gate, out of the paths.
    building_sign(t, MID + 2, 1, MID - 4, "hall")
    # Quest board at the west end of the east-west path, facing east to the plaza: 3 columns x 3 rows across the
    # path. The columns go in the clockwise direction of the facing (south for east).
    for column in range(3):
        for row in range(3):
            t.set(TOWER - 1, 1 + row, MID - 1 + column, f"{BLOCKS}:quest_board",
                  {"facing": "east", "column": str(column), "row": str(row), "papers": "0"})
    for (x, z) in ((MID - 2, 7), (MID + 2, 7), (MID - 2, SIZE - 8), (MID + 2, SIZE - 8),
                   (7, MID - 2), (7, MID + 2), (SIZE - 8, MID - 2), (SIZE - 8, MID + 2)):
        lamp_post(t, x, z)
    palisade_torches(t)

    # House plots: jigsaw connectors next to the north-south path. Houses go into the free space.
    for z in (9, SIZE - 10):
        west_pool = BANK_POOL if z == 9 else HOUSE_POOL
        t.jigsaw(MID - 2, 1, z, "west_up", PLOT, HOUSE_FRONT, west_pool, "minecraft:air")
        t.jigsaw(MID + 2, 1, z, "east_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
    t = with_margin(t, 1)
    outer_banners(t)
    return with_foundation(t, 1)


# ---------------------------------------------------------------- pieces of the village structure

PLAZA = 15
PLAZA_MID = PLAZA // 2  # 7
PLAZA_HEIGHT = 6
# The streets are 5 blocks wide: a path of 3 blocks between two edges of terrain.
STREET_WIDTH = 5
STREET_LENGTH = 13
STREET_END_LENGTH = 5
# A house is 9 blocks wide: 4 blocks on each side of its connector.
HOUSE_HALF_WIDTH = 4


def plaza():
    """The start piece: the plaza of the village heart, with a connector for a street on each side."""
    rng = random.Random(11)
    t = Template(PLAZA, PLAZA_HEIGHT, PLAZA)
    t.fill(0, 1, 0, PLAZA - 1, PLAZA_HEIGHT - 1, PLAZA - 1, "minecraft:air")
    lo, hi = 2, PLAZA - 3
    for x in range(PLAZA):
        for z in range(PLAZA):
            if lo <= x <= hi and lo <= z <= hi:
                border = x in (lo, hi) or z in (lo, hi)
                t.set(x, 0, z, "minecraft:stone_bricks" if border else cobble(rng))
            elif abs(x - PLAZA_MID) <= 1 or abs(z - PLAZA_MID) <= 1:
                t.set(x, 0, z, path_block(rng))
            else:
                t.set(x, 0, z, "minecraft:grass_block", {"snowy": "false"})
    t.set(PLAZA_MID, 0, PLAZA_MID, "minecraft:chiseled_stone_bricks")
    t.set(PLAZA_MID, 1, PLAZA_MID, HEART)
    # Alarm bell, next to the street to the west. The heart rings it in an alert.
    t.set(PLAZA_MID - 4, 0, PLAZA_MID - 2, "minecraft:stone_bricks")
    t.set(PLAZA_MID - 4, 1, PLAZA_MID - 2, "minecraft:bell",
          {"attachment": "floor", "facing": "north", "powered": "false"})
    for dx in (-3, 3):
        for dz in (-3, 3):
            t.set(PLAZA_MID + dx, 0, PLAZA_MID + dz, "minecraft:stone_bricks")
            t.set(PLAZA_MID + dx, 1, PLAZA_MID + dz, f"{BLOCKS}:brazier", {"lit": "false"})
    building_sign(t, PLAZA_MID + 2, 1, PLAZA_MID - 4, "hall")
    # Quest board in the south-east part of the plaza, facing west to the heart: 3 columns x 3 rows. The columns go
    # in the clockwise direction of the facing (north for west).
    for column in range(3):
        for row in range(3):
            t.set(hi, 1 + row, hi - column, f"{BLOCKS}:quest_board",
                  {"facing": "west", "column": str(column), "row": str(row), "papers": "0"})
    for (x, z) in ((1, 1), (PLAZA - 2, 1), (1, PLAZA - 2)):
        lamp_post(t, x, z)
    # The connectors of the streets, in the middle of each side. The street to the north has the plot of the bank. The
    # plan places the first house-workshops on the plots of the street to the south.
    last = PLAZA - 1
    t.jigsaw(PLAZA_MID, 0, 0, "north_up", PLAZA_EXIT, STREET, BANK_STREETS_POOL, "minecraft:dirt_path")
    t.jigsaw(PLAZA_MID, 0, last, "south_up", PLAZA_EXIT, STREET, FIRST_STREETS_POOL, "minecraft:dirt_path")
    t.jigsaw(0, 0, PLAZA_MID, "west_up", PLAZA_EXIT, STREET, FIRST_STREETS_POOL, "minecraft:dirt_path")
    t.jigsaw(last, 0, PLAZA_MID, "east_up", PLAZA_EXIT, STREET, FIRST_STREETS_POOL, "minecraft:dirt_path")
    return t


def street_path(t, rng, length):
    """The path of a street: 3 blocks wide, between two edges where the terrain stays."""
    for z in range(length):
        for x in range(1, STREET_WIDTH - 1):
            t.set(x, 0, z, path_block(rng))


def street(bank=False, next_street=False):
    """A segment of street along z, with a plot on each side and a connector for the end of the street.

    The segment follows the terrain (projection terrain_matching). The segment to the north has the plot of the bank
    on its west side. The other plots target the pool of the houses: the plan chooses the building of each plot (see
    VillagePlanPieces). With next_street, the connector at the end of the segment targets the pool streets_next: the
    street continues (see street_cross).
    """
    rng = random.Random(12 if bank else 13)
    t = Template(STREET_WIDTH, 4, STREET_LENGTH)
    street_path(t, rng, STREET_LENGTH)
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:dirt_path")
    t.jigsaw(mid, 0, STREET_LENGTH - 1, "south_up", STREET_NEXT, STREET,
             NEXT_STREETS_POOL if next_street else STREET_ENDS_POOL, "minecraft:dirt_path")
    # The plots are near the end of the segment: the houses of 2 streets side by side do not touch.
    plot_z = STREET_LENGTH - 1 - HOUSE_HALF_WIDTH
    west = BANK_POOL if bank else HOUSE_POOL
    t.jigsaw(0, 1, plot_z, "west_up", PLOT, HOUSE_FRONT, west, "minecraft:air")
    t.jigsaw(STREET_WIDTH - 1, 1, plot_z, "east_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
    # A lamp post at the far end, on the east edge.
    lamp_post(t, STREET_WIDTH - 1, STREET_LENGTH - 1)
    return t


def street_end():
    """The end of a street: a short path with a lamp post on each edge. It has no connector for another segment.

    The village structure adds the path from the end of the street to the gate.
    """
    rng = random.Random(14)
    t = Template(STREET_WIDTH, 4, STREET_END_LENGTH)
    street_path(t, rng, STREET_END_LENGTH)
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:dirt_path")
    lamp_post(t, 0, STREET_END_LENGTH - 1)
    lamp_post(t, STREET_WIDTH - 1, STREET_END_LENGTH - 1)
    return t


# The pieces of the planned streets. The frontage of a style is the width of its widest home along the street, and its
# depth is the depth of its deepest home: 9 and 9 for the pieces of all the cultures and the maritime pieces, 13 and 13
# for Sunreach, 17 and 17 for Valeholm (see PIECES and STYLES). The 2 ends of a crossroad and the end of a side street
# have PLOT_MARGIN blocks with no home next to them. The homes on the plots of a street segment go past its ends:
# - A home of 13 blocks (Sunreach) on the plot z = 8 of a segment of 13 blocks covers z = 2..14: 2 blocks past the end.
# - A side long house (17 blocks) on a Norse segment of 21 blocks: on the plot z = 6, it covers z = -2..14, 2 blocks
#   before the start. On the plot z = 16, it covers z = 8..24, 4 blocks past the end.
# Thus the plan of the village obeys these rules for the Norse segments:
# - No side long house on the plot z = 16 of a segment with a piece after it.
# - A side long house on the plot z = 6 of a segment and a home on the plot z = 16 of the same segment overlap: the
#   segment uses only one of the 2 plots on that side. A side long house on the plot z = 6 also goes into the piece
#   before the segment: that piece has no home in its last 2 blocks.
PLOT_MARGIN = 2


def street_cross(frontage):
    """A crossroad: a street along z with a side street to the west and to the east, and a plot in each corner.

    - The street has 2 arms of frontage + PLOT_MARGIN blocks, and the crossing (STREET_WIDTH blocks) between them. The
      path of the side streets goes through the 2 edges of the crossing.
    - A plot on each side of each arm, next to the crossing (4 plots): a home of the full frontage of the style does
      not go in front of the crossing. The margin at the 2 ends keeps the homes of the next pieces away.
    - Connectors: the start of the street to the north (STREET), the next street to the south (pool streets_next),
      and the 2 side streets to the west and to the east (pool streets_side).
    """
    arm = frontage + PLOT_MARGIN
    length = 2 * arm + STREET_WIDTH
    rng = random.Random(17)
    t = Template(STREET_WIDTH, 4, length)
    street_path(t, rng, length)
    for z in range(arm + 1, arm + STREET_WIDTH - 1):
        for x in (0, STREET_WIDTH - 1):
            t.set(x, 0, z, path_block(rng))
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:dirt_path")
    t.jigsaw(mid, 0, length - 1, "south_up", STREET_NEXT, STREET, NEXT_STREETS_POOL, "minecraft:dirt_path")
    t.jigsaw(0, 0, arm + mid, "west_up", STREET_NEXT, STREET, SIDE_STREETS_POOL, "minecraft:dirt_path")
    t.jigsaw(STREET_WIDTH - 1, 0, arm + mid, "east_up", STREET_NEXT, STREET, SIDE_STREETS_POOL, "minecraft:dirt_path")
    for z in (arm - 1 - frontage // 2, arm + STREET_WIDTH + frontage // 2):
        t.jigsaw(0, 1, z, "west_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
        t.jigsaw(STREET_WIDTH - 1, 1, z, "east_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
    lamp_post(t, 0, arm)
    lamp_post(t, STREET_WIDTH - 1, arm + STREET_WIDTH - 1)
    return t


def street_side(depth):
    """The start of a side street, from a side of a crossroad: depth + PLOT_MARGIN blocks with no plot. The homes of
    the crossroad (depth blocks deep) are on its 2 sides. The connector at the end targets the pool streets_next. A
    lamp post is at the end, on the east edge."""
    rng = random.Random(18)
    length = depth + PLOT_MARGIN
    t = Template(STREET_WIDTH, 4, length)
    street_path(t, rng, length)
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:dirt_path")
    t.jigsaw(mid, 0, length - 1, "south_up", STREET_NEXT, STREET, NEXT_STREETS_POOL, "minecraft:dirt_path")
    lamp_post(t, STREET_WIDTH - 1, length - 1)
    return t


# The tower piece: the tower of 5 x 5 blocks and its stair of STAIR_STEPS stairs and a path, along the north side.
TOWER_PIECE_LENGTH = TOWER + STAIR_STEPS + 1


def tower_piece():
    """A tower of the palisade with its stair. The outside of the template is to the west and to the north.

    The village structure turns the template for each side. The palisade continues along the north side of the
    stair (z = 0, from x = TOWER) and, at a corner, along the west side of the tower (x = 0, from z = TOWER).
    """
    rng = random.Random(15)
    t = Template(TOWER_PIECE_LENGTH, HEIGHT, TOWER)
    tower(t, rng, 0, 0)
    return t


GATE_WIDTH = 5
GATE_DEPTH = 3
GATE_HEIGHT = 7


def gate_piece():
    """A gate of 3 blocks in the palisade, with its 2 posts. The outside of the template is to the north (z = 0).

    The palisade line is z = 1. The path goes through the gate, from z = 0 to z = 2.
    """
    rng = random.Random(16)
    t = Template(GATE_WIDTH, GATE_HEIGHT, GATE_DEPTH)
    line = 1
    for x in range(1, GATE_WIDTH - 1):
        for z in range(GATE_DEPTH):
            t.set(x, 0, z, "minecraft:dirt_path")
            for y in range(1, 4):
                t.set(x, y, z, "minecraft:air")
        t.set(x, 1, line, "minecraft:spruce_fence_gate",
              {"facing": "north", "in_wall": "false", "open": "false", "powered": "false"})
        t.set(x, 4, line, "minecraft:spruce_planks")
        t.set(x, 5, line, "minecraft:spruce_slab", {"type": "bottom", "waterlogged": "false"})
    for x in (0, GATE_WIDTH - 1):
        t.set(x, 0, line, cobble(rng))
        for y in range(1, 6):
            t.set(x, y, line, "minecraft:stripped_spruce_log", LOG_Y)
        t.set(x, 6, line, "minecraft:lantern", {"hanging": "false", "waterlogged": "false"})
    return t


# ---------------------------------------------------------------- stone wall

# The stone wall of a village of the village tier and higher: the tower, the gate and the columns of the palisade in
# stone. A stone piece has the shape of its wooden piece: the same blocks with a collision shape, the same stairs and
# the same watch post. Thus the builders change a wooden piece into its stone piece block by block, and the archers,
# the villagers and the raiders walk on it as on the wooden piece. The biome variants change the stone: stone bricks,
# sandstone in the desert and mud bricks in the swamp.
# A pair (name, ()) is a block with no properties (see plain).
STONE = {
    "minecraft:spruce_log": ("minecraft:stone_bricks", ()),
    "minecraft:stripped_spruce_log": ("minecraft:stone_bricks", ()),
    "minecraft:spruce_planks": "minecraft:stone_bricks",
    "minecraft:spruce_slab": "minecraft:stone_brick_slab",
    "minecraft:spruce_stairs": "minecraft:stone_brick_stairs",
}


def stone(t):
    """The template in stone: each block of STONE changes to its stone block. The other blocks stay: the cobblestone
    base, the fence gates, the dark oak roof, the lights and the watch post."""
    out = Template(*t.size)
    for pos, ((name, props), nbt) in t.blocks.items():
        new = STONE.get(name, name)
        out.blocks[pos] = ((new[0], ()) if isinstance(new, tuple) else (new, props), nbt)
    return out


def stone_tower_piece():
    """The stone tower: the tower of the palisade (see tower_piece) in stone."""
    return stone(tower_piece())


def stone_gate_piece():
    """The stone gate: the gate of the palisade (see gate_piece) in stone, with its wooden fence gates."""
    return stone(gate_piece())


def check_stone_pieces():
    """Each stone piece has the shape of its wooden piece: at each position, the two blocks have the same collision
    shape (see collision). Stairs and slabs keep their properties."""
    for wooden, stone_piece in ((tower_piece(), stone_tower_piece()), (gate_piece(), stone_gate_piece())):
        assert wooden.size == stone_piece.size
        assert wooden.blocks.keys() == stone_piece.blocks.keys()
        for pos, ((name, props), _) in wooden.blocks.items():
            (other, other_props), _ = stone_piece.blocks[pos]
            same = collision(name, dict(props)) == collision(other, dict(other_props))
            assert same, f"The stone block {other} at {pos} has another shape than {name}"


# ---------------------------------------------------------------- houses

W = 9   # x: 0..8
D = 9   # z: 0 = porch, 1..8 = body
H = 10


def building_sign(t, x, y, z, kind, facing="north"):
    """The building sign of a kind (see tools/pixvox/building_signs.py)."""
    t.set(x, y, z, f"{BLOCKS}:building_sign_{kind}", {"facing": facing})


def house_shell(rng, wall_lower, wall_upper, sign, door=True, wide_front=False, depth=D):
    """Timber frame house: cobblestone base, frame of stripped spruce logs, gable roof over the porch.

    The building sign of the kind stands on the porch, next to the east porch post. With no sign kind, the porch has
    no sign. The body goes from z = 1 to z = depth - 1. A depth less than D leaves the back of the plot empty.
    """
    t = Template(W, H, D)
    t.fill(0, 1, 0, W - 1, H - 1, D - 1, "minecraft:air")
    # Porch and floor.
    for x in range(W):
        t.set(x, 0, 0, "minecraft:cobblestone" if x in (0, W - 1) else "minecraft:spruce_planks")
        for z in range(1, depth):
            edge = x in (0, W - 1) or z in (1, depth - 1)
            t.set(x, 0, z, cobble(rng) if edge else "minecraft:spruce_planks")
    # Walls.
    for x in range(W):
        for z in range(1, depth):
            if not (x in (0, W - 1) or z in (1, depth - 1)):
                continue
            corner = x in (0, W - 1) and z in (1, depth - 1)
            for y in range(1, 5):
                if corner or y == 4:
                    t.set(x, y, z, "minecraft:stripped_spruce_log",
                          LOG_Y if corner else {"axis": "x" if z in (1, depth - 1) else "z"})
                elif y == 1:
                    t.set(x, y, z, wall_lower(rng))
                else:
                    t.set(x, y, z, wall_upper(rng))
    # Windows.
    for z in (3, 6):
        if z < depth - 1:
            t.set(0, 2, z, "minecraft:glass_pane")
            t.set(W - 1, 2, z, "minecraft:glass_pane")
    for x in (2, 6):
        t.set(x, 2, depth - 1, "minecraft:glass_pane")
    # Front opening.
    mid = W // 2
    if wide_front:
        for x in (mid - 1, mid, mid + 1):
            for y in (1, 2, 3):
                t.set(x, y, 1, "minecraft:air")
    elif door:
        t.set(mid, 1, 1, "minecraft:spruce_door",
              {"facing": "north", "half": "lower", "hinge": "left", "open": "false", "powered": "false"})
        t.set(mid, 2, 1, "minecraft:spruce_door",
              {"facing": "north", "half": "upper", "hinge": "left", "open": "false", "powered": "false"})
        t.set(mid, 3, 1, "minecraft:stripped_spruce_log", {"axis": "x"})
    # Porch posts.
    for x in (0, W - 1):
        for y in range(1, 5):
            t.set(x, y, 0, "minecraft:spruce_fence")
    # Porch light: 2 wall torches on the front wall.
    for x in (mid - 2, mid + 2):
        t.set(x, 3, 0, *wall_torch("north"))
    # Gable roof (ridge along z), over the porch.
    for step in range(5):
        y = 5 + step
        left, right = step, W - 1 - step
        for z in range(depth):
            if left < right:
                t.set(left, y, z, "minecraft:dark_oak_stairs", stairs("east"))
                t.set(right, y, z, "minecraft:dark_oak_stairs", stairs("west"))
            elif left == right:
                t.set(left, y, z, "minecraft:dark_oak_slab", {"type": "bottom", "waterlogged": "false"})
        # Gable walls under the roof, at the front and the back of the body.
        for x in range(left + 1, right):
            for z in (1, depth - 1):
                t.set(x, y, z, wall_upper(rng))
    if sign:
        building_sign(t, W - 2, 1, 0, sign)
    # Jigsaw connector in the porch, facing out (north).
    t.jigsaw(mid, 1, 0, "north_up", HOUSE_FRONT, "minecraft:empty", "minecraft:empty", "minecraft:air")
    return t


def calcite(_rng):
    return "minecraft:calcite"


def lantern(t, x, y, z):
    t.set(x, y, z, "minecraft:lantern", {"hanging": "false", "waterlogged": "false"})


def bed(t, x, z_foot, color="blue", facing="south"):
    """A bed with the foot at (x, z_foot) and the head 1 block in the direction of facing: toward the back wall for
    "south", toward the east wall for "east"."""
    dx, dz = SIDES[facing]
    t.set(x, 1, z_foot, f"minecraft:{color}_bed", {"facing": facing, "part": "foot", "occupied": "false"})
    t.set(x + dx, 1, z_foot + dz, f"minecraft:{color}_bed", {"facing": facing, "part": "head", "occupied": "false"})


def cottage():
    rng = random.Random(2)
    t = house_shell(rng, cobble, calcite, "house")
    # 3 beds: each bed gives a place to an inhabitant of the village. A free floor block between 2 beds lets a
    # villager walk to the head of each bed.
    for x in (1, 3, 5):
        bed(t, x, 6, "red")
    t.set(6, 1, 7, "minecraft:crafting_table")
    t.set(7, 1, 6, "minecraft:chest", {"facing": "west", "type": "single", "waterlogged": "false"})
    t.set(4, 0, 4, "minecraft:spruce_planks")
    t.set(4, 1, 4, "minecraft:red_carpet")
    t.set(7, 1, 2, "minecraft:flower_pot")
    lantern(t, 1, 1, 2)
    lantern(t, 7, 1, 7)
    return t


def barracks():
    rng = random.Random(3)
    t = house_shell(rng, cobble, lambda r: "minecraft:spruce_planks", "barracks")
    for x in (1, 3, 5, 7):
        bed(t, x, 6, "blue")
    t.set(7, 1, 2, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(1, 1, 2, BANNER, {"rotation": "4"})
    lantern(t, 4, 1, 4)
    return t


# The job sign of the blacksmith, on its house-workshop (the piece job_blacksmith).
BLACKSMITH = ("blacksmith", "minecraft:anvil")


def forge(home=False):
    """The forge: an open front, an anvil, a blast furnace, a furnace, a smithing table, a grindstone (the work block
    of the vanilla weaponsmith) and a water cauldron to cool the metal. The building sign of the forge stands next to
    the east porch post. The forge has no beds and no job sign: it is a building of the village, not a home with a job
    post.

    With home, the forge is the house-workshop of the blacksmith (the piece job_blacksmith): the building sign is the
    house sign, the job sign stands next to the west porch post, and 2 beds stand on the side walls (see home_beds)."""
    rng = random.Random(4)
    t = house_shell(rng, cobble, cobble, "house" if home else "forge", wide_front=True)
    if home:
        job_sign(t, 1, 1, 0, *BLACKSMITH)
    t.set(2, 1, 5, "minecraft:anvil", {"facing": "east"})
    t.set(5, 1, 7, "minecraft:blast_furnace", {"facing": "north", "lit": "false"})
    t.set(6, 1, 7, "minecraft:furnace", {"facing": "north", "lit": "false"})
    t.set(7, 1, 7, "minecraft:smithing_table")
    t.set(1, 1, 7, "minecraft:grindstone", {"face": "floor", "facing": "north"})
    t.set(7, 1, 3, "minecraft:water_cauldron", {"level": "3"})
    lantern(t, 1, 1, 2)
    if home:
        home_beds(t, (1, 4), (7, 4))
    return t


def storehouse():
    rng = random.Random(5)
    t = house_shell(rng, cobble, lambda r: "minecraft:spruce_planks", "storehouse")
    # Each barrel has a free floor block next to it: a villager can walk to it.
    for (x, z) in ((1, 7), (2, 6), (1, 6), (6, 6), (7, 6)):
        t.set(x, 1, z, "minecraft:barrel", {"facing": "up", "open": "false"})
    for (x, z) in ((6, 7), (7, 7), (5, 7)):
        t.set(x, 1, z, "minecraft:hay_block", LOG_Y)
    t.set(6, 2, 7, "minecraft:hay_block", LOG_Y)
    t.set(1, 1, 3, "minecraft:composter", {"level": "0"})
    lantern(t, 4, 1, 4)
    return t


def bank():
    """The bank: a small stone treasury, different from the houses.

    - Walls, flat roof and crenellated parapet of bank wall blocks: they resist explosions.
    - Front portico (z = 0..1) with two columns, an entablature and the bank sign.
    - Door in the middle of the front wall, narrow windows with iron bars.
    - Inside: a counter with iron bars (the teller windows) and the vault with the 4 bank chests on the back wall.
    """
    t = Template(W, H, D)
    t.fill(0, 1, 0, W - 1, H - 1, D - 1, "minecraft:air")
    front, back = 2, D - 1
    roof = 6
    # Floors: the portico, then the hall of the bank.
    for x in range(W):
        for z in range(0, front):
            t.set(x, 0, z, "minecraft:polished_deepslate")
        for z in range(front, D):
            edge = x in (0, W - 1) or z in (front, back)
            t.set(x, 0, z, BANK_WALL if edge else "minecraft:deepslate_tiles")
    # Walls and flat roof.
    for x in range(W):
        for z in range(front, D):
            if x in (0, W - 1) or z in (front, back):
                for y in range(1, roof):
                    t.set(x, y, z, BANK_WALL)
            t.set(x, roof, z, BANK_WALL)
    # Crenellated parapet.
    for x in range(W):
        for z in range(front, D):
            if (x in (0, W - 1) or z in (front, back)) and (x + z) % 2 == 0:
                t.set(x, roof + 1, z, BANK_WALL)
    # Door and windows.
    mid = W // 2
    t.set(mid, 1, front, "minecraft:dark_oak_door",
          {"facing": "north", "half": "lower", "hinge": "left", "open": "false", "powered": "false"})
    t.set(mid, 2, front, "minecraft:dark_oak_door",
          {"facing": "north", "half": "upper", "hinge": "left", "open": "false", "powered": "false"})
    for x in (2, W - 3):
        t.set(x, 2, front, "minecraft:iron_bars")
        t.set(x, 3, front, "minecraft:iron_bars")
    for z in (4, 6):
        for x in (0, W - 1):
            t.set(x, 2, z, "minecraft:iron_bars")
            t.set(x, 3, z, "minecraft:iron_bars")
    # Portico: two columns, the entablature, the parapet edge, lights and banners.
    for x in (1, W - 2):
        t.set(x, 1, 0, "minecraft:chiseled_deepslate")
        for y in range(2, roof - 1):
            t.set(x, y, 0, "minecraft:polished_basalt", LOG_Y)
    for x in range(W):
        for z in range(0, front):
            t.set(x, roof - 1, z, "minecraft:polished_deepslate")
        t.set(x, roof, 0, "minecraft:deepslate_tile_slab", {"type": "bottom", "waterlogged": "false"})
        t.set(x, roof, 1, "minecraft:deepslate_tile_slab", {"type": "bottom", "waterlogged": "false"})
    for x in (2, W - 3):
        t.set(x, roof - 2, 1, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})
    for x in (mid - 1, mid + 1):
        t.set(x, 3, 1, WALL_BANNER, {"facing": "north"})
    building_sign(t, W - 3, 1, 0, "bank")
    # Inside: carpet to the counter, the counter with the teller windows, the vault.
    for z in range(front + 1, 5):
        t.set(mid, 1, z, "minecraft:red_carpet")
    for x in range(1, W - 1):
        if x != mid:
            t.set(x, 1, 5, "minecraft:polished_deepslate_slab", {"type": "top", "waterlogged": "false"})
            t.set(x, 2, 5, "minecraft:iron_bars")
    for x in (2, 3, 5, 6):
        t.set(x, 1, back - 1, BANK_CHEST)
    lantern(t, 1, 1, front + 1)
    lantern(t, W - 2, 1, front + 1)
    t.set(mid, roof - 1, 6, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})
    # Jigsaw connector in the portico, facing out (north).
    t.jigsaw(mid, 1, 0, "north_up", HOUSE_FRONT, "minecraft:empty", "minecraft:empty", "minecraft:air")
    return t


# ---------------------------------------------------------------- Valeholm style (Norse)

# A deep long house: the gable end and the door face the street (z = 0 is the porch), the body goes deep into the plot.
LW = 9    # x: 0..8
LD = 17   # z: 0 = porch, 1..16 = body
LH = 16
# A side long house: the long side and the door face the street (z = 0 is the porch), the body goes along the street.
SL = 17   # x: 0..16
SD = 10   # z: 0 = porch, 1..9 = body
# The walls go up to this layer. The roof starts above it.
LONG_WALL_TOP = 3
# A Norse street: a segment of 21 blocks with 2 plots on each side, for the long houses.
NORSE_STREET_LENGTH = 21
NORSE_PLOTS = (6, 16)


def steep_roof_top(across, width):
    """The layer of the top block of a steep roof over a row across the house: the roof goes up by 2 blocks for each
    block to the ridge."""
    k = min(across, width - 1 - across)
    return LONG_WALL_TOP + 2 + 2 * k


def unlit_campfire(facing="north"):
    return "minecraft:campfire", {"facing": facing, "lit": "false", "signal_fire": "false", "waterlogged": "false"}


def long_house_shell(rng, sign, wall_lower, door=True, wide_front=False, depth=LD, hearth=True):
    """A deep Norse long house: a base of cobblestone, walls of horizontal spruce logs between posts of stripped spruce
    logs, a steep roof of dark oak with a ridge along the house, and carved horns at the ends of the ridge.

    The building sign of the kind stands on the porch, next to the east porch post. With hearth, a hearth of stone
    bricks is in the middle of the body, with a campfire that is not lit. The body goes from z = 1 to z = depth - 1. A
    depth less than LD leaves the back of the plot empty.

    The ridge is a row of planks with no slab on it, and the middle horn stands on these planks. A builder stands on a
    scaffolding in the house to place the ridge (see ConstructionPlanner). A slab on the ridge has no position to stand
    for a builder: a builder does not stand on the 1-block strips of the roof stairs.
    """
    t = Template(LW, LH, LD)
    t.fill(0, 1, 0, LW - 1, LH - 1, LD - 1, "minecraft:air")
    mid = LW // 2
    # Porch and floor.
    for x in range(LW):
        t.set(x, 0, 0, "minecraft:cobblestone" if x in (0, LW - 1) else "minecraft:spruce_planks")
        for z in range(1, depth):
            edge = x in (0, LW - 1) or z in (1, depth - 1)
            t.set(x, 0, z, cobble(rng) if edge else "minecraft:spruce_planks")
    # Walls: posts at the corners and every 4 blocks along the sides, logs between them.
    for x in range(LW):
        for z in range(1, depth):
            side = x in (0, LW - 1)
            end = z in (1, depth - 1)
            if not (side or end):
                continue
            post = (side and end) or (side and (z - 1) % 4 == 0) or (end and x == mid)
            for y in range(1, LONG_WALL_TOP + 1):
                if post:
                    t.set(x, y, z, "minecraft:stripped_spruce_log", LOG_Y)
                elif y == 1:
                    t.set(x, y, z, wall_lower(rng))
                else:
                    t.set(x, y, z, "minecraft:spruce_log", {"axis": "z" if side else "x"})
    # Small windows on the sides.
    for z in (3, 7, 11, 15):
        if z < depth - 1:
            for x in (0, LW - 1):
                t.set(x, 2, z, "minecraft:glass_pane")
    # Front opening.
    if wide_front:
        for x in (mid - 1, mid, mid + 1):
            for y in (1, 2):
                t.set(x, y, 1, "minecraft:air")
    elif door:
        t.set(mid, 1, 1, "minecraft:spruce_door",
              {"facing": "north", "half": "lower", "hinge": "left", "open": "false", "powered": "false"})
        t.set(mid, 2, 1, "minecraft:spruce_door",
              {"facing": "north", "half": "upper", "hinge": "left", "open": "false", "powered": "false"})
        t.set(mid, 3, 1, "minecraft:stripped_spruce_log", {"axis": "x"})
    # Porch posts.
    for x in (0, LW - 1):
        for y in range(1, LONG_WALL_TOP + 1):
            t.set(x, y, 0, "minecraft:spruce_fence")
    # Porch light: 2 wall torches on the front wall.
    for x in (mid - 2, mid + 2):
        t.set(x, 2, 0, *wall_torch("north"))
    # Steep roof (ridge along z), over the porch.
    for x in range(LW):
        top = steep_roof_top(x, LW)
        for z in range(depth):
            t.set(x, top - 1, z, "minecraft:dark_oak_planks")
            if x != mid:
                t.set(x, top, z, "minecraft:dark_oak_stairs", stairs("east" if x < mid else "west"))
        # Gable walls under the roof, at the front and the back of the body.
        if 0 < x < LW - 1:
            for y in range(LONG_WALL_TOP + 1, top - 1):
                for z in (1, depth - 1):
                    t.set(x, y, z, "minecraft:spruce_planks")
    # Carved horns at the 2 ends of the ridge.
    for z in (0, depth - 1):
        for x in (mid - 1, mid + 1):
            t.set(x, steep_roof_top(x, LW) + 1, z, "minecraft:spruce_fence")
            t.set(x, steep_roof_top(x, LW) + 2, z, "minecraft:spruce_fence")
        t.set(mid, steep_roof_top(mid, LW), z, "minecraft:spruce_fence")
    # Long hearth in the middle of the body, and a lantern under the ridge.
    middle = depth // 2
    if hearth:
        for z in range(middle - 2, middle + 3):
            t.set(mid, 0, z, "minecraft:stone_bricks")
        t.set(mid, 1, middle, *unlit_campfire())
    t.set(mid, steep_roof_top(mid, LW) - 2, middle + 4 if hearth else middle, "minecraft:lantern",
          {"hanging": "true", "waterlogged": "false"})
    building_sign(t, LW - 2, 1, 0, sign)
    # Jigsaw connector in the porch, facing out (north).
    t.jigsaw(mid, 1, 0, "north_up", HOUSE_FRONT, "minecraft:empty", "minecraft:empty", "minecraft:air")
    return t


def side_house_shell(rng, sign, wall_lower):
    """A side Norse long house: the same walls and roof as a deep long house, with the ridge along the street and the
    door in the middle of the long side. A small porch with 2 posts is in front of the door. The hearth is in the
    middle of the body, along the ridge. The ridge is a row of planks with no slab on it, as on a deep long house.
    """
    t = Template(SL, LH, SD)
    t.fill(0, 1, 1, SL - 1, LH - 1, SD - 1, "minecraft:air")
    mid = SL // 2
    ridge = (SD + 1) // 2   # the body is z = 1..SD - 1
    # Porch in front of the door.
    for x in range(mid - 3, mid + 4):
        t.set(x, 0, 0, "minecraft:cobblestone" if x in (mid - 3, mid + 3) else "minecraft:spruce_planks")
        for y in range(1, 4):
            t.set(x, y, 0, "minecraft:air")
    # Floor.
    for x in range(SL):
        for z in range(1, SD):
            edge = x in (0, SL - 1) or z in (1, SD - 1)
            t.set(x, 0, z, cobble(rng) if edge else "minecraft:spruce_planks")
    # Walls.
    for x in range(SL):
        for z in range(1, SD):
            long_side = z in (1, SD - 1)
            end = x in (0, SL - 1)
            if not (long_side or end):
                continue
            post = (long_side and end) or (long_side and x % 4 == 0) or (end and z == ridge)
            for y in range(1, LONG_WALL_TOP + 1):
                if post:
                    t.set(x, y, z, "minecraft:stripped_spruce_log", LOG_Y)
                elif y == 1:
                    t.set(x, y, z, wall_lower(rng))
                else:
                    t.set(x, y, z, "minecraft:spruce_log", {"axis": "x" if long_side else "z"})
    # Windows on the long sides.
    for x in (2, 6, SL - 7, SL - 3):
        for z in (1, SD - 1):
            if x not in (mid - 1, mid, mid + 1):
                t.set(x, 2, z, "minecraft:glass_pane")
    # Door in the middle of the front side.
    t.set(mid, 1, 1, "minecraft:spruce_door",
          {"facing": "north", "half": "lower", "hinge": "left", "open": "false", "powered": "false"})
    t.set(mid, 2, 1, "minecraft:spruce_door",
          {"facing": "north", "half": "upper", "hinge": "left", "open": "false", "powered": "false"})
    t.set(mid, 3, 1, "minecraft:stripped_spruce_log", {"axis": "x"})
    for x in (mid - 3, mid + 3):
        for y in range(1, LONG_WALL_TOP + 1):
            t.set(x, y, 0, "minecraft:spruce_fence")
    for x in (mid - 2, mid + 2):
        t.set(x, 2, 0, *wall_torch("north"))
    # Steep roof (ridge along x). The eave goes over the porch.
    for z in range(1, SD):
        top = steep_roof_top(z - 1, SD - 1)
        for x in range(SL):
            t.set(x, top - 1, z, "minecraft:dark_oak_planks")
            if z != ridge:
                t.set(x, top, z, "minecraft:dark_oak_stairs", stairs("south" if z < ridge else "north"))
        if 1 < z < SD - 1:
            for y in range(LONG_WALL_TOP + 1, top - 1):
                for x in (0, SL - 1):
                    t.set(x, y, z, "minecraft:spruce_planks")
    for x in range(SL):
        t.set(x, LONG_WALL_TOP + 1, 0, "minecraft:dark_oak_stairs", stairs("south"))
    # Carved horns at the 2 ends of the ridge.
    for x in (0, SL - 1):
        for z in (ridge - 1, ridge + 1):
            t.set(x, steep_roof_top(z - 1, SD - 1) + 1, z, "minecraft:spruce_fence")
            t.set(x, steep_roof_top(z - 1, SD - 1) + 2, z, "minecraft:spruce_fence")
        t.set(x, steep_roof_top(ridge - 1, SD - 1), ridge, "minecraft:spruce_fence")
    # Long hearth along the ridge.
    for x in range(mid - 3, mid + 4):
        t.set(x, 0, ridge, "minecraft:stone_bricks")
    t.set(mid, 1, ridge, *unlit_campfire("east"))
    t.set(mid + 4, steep_roof_top(ridge - 1, SD - 1) - 2, ridge, "minecraft:lantern",
          {"hanging": "true", "waterlogged": "false"})
    building_sign(t, mid + 2, 1, 0, sign)
    # Jigsaw connector in the porch, facing out (north).
    t.jigsaw(mid, 1, 0, "north_up", HOUSE_FRONT, "minecraft:empty", "minecraft:empty", "minecraft:air")
    return t


def norse_cottage():
    rng = random.Random(12)
    t = long_house_shell(rng, "house", cobble)
    # 3 beds: each bed gives a place to an inhabitant of the village.
    for x in (1, 2, LW - 2):
        bed(t, x, LD - 4, "blue")
    t.set(LW - 2, 1, 3, "minecraft:chest", {"facing": "west", "type": "single", "waterlogged": "false"})
    t.set(1, 1, 3, "minecraft:crafting_table")
    t.set(LW - 2, 1, 9, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(1, 1, 9, "minecraft:loom", {"facing": "east"})
    lantern(t, 1, 1, 6)
    return t


def norse_cottage_side():
    rng = random.Random(22)
    t = side_house_shell(rng, "house", cobble)
    # 3 beds along the back wall, with a free floor block next to the head of each bed.
    for x in (2, 4, SL - 3):
        bed(t, x, SD - 3, "blue")
    t.set(1, 1, 2, "minecraft:crafting_table")
    t.set(SL - 2, 1, 2, "minecraft:chest", {"facing": "west", "type": "single", "waterlogged": "false"})
    t.set(SL - 2, 1, SD - 2, "minecraft:barrel", {"facing": "up", "open": "false"})
    lantern(t, 1, 1, SD - 2)
    return t


def norse_barracks():
    rng = random.Random(13)
    t = long_house_shell(rng, "barracks", cobble)
    for x in (1, 2, LW - 3, LW - 2):
        bed(t, x, LD - 4, "red")
    for z in (3, 5):
        t.set(1, 1, z, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(LW - 2, 1, 3, BANNER, {"rotation": "4"})
    for z in (6, 10):
        t.set(1, 1, z, "minecraft:spruce_fence")
        t.set(LW - 2, 1, z, "minecraft:spruce_fence")
    lantern(t, 1, 1, 8)
    return t


def norse_forge():
    rng = random.Random(14)
    t = long_house_shell(rng, "forge", cobble, wide_front=True)
    forge_tools(t, LW, LD)
    return t


def forge_tools(t, width, depth):
    """The tools of a forge in a house of width x depth blocks with an open front: an anvil, a blast furnace, a
    furnace, a smithing table, a grindstone (the work block of the vanilla weaponsmith), a water cauldron and a
    lantern. The Valeholm forge (norse_forge) and the blacksmith of the styles (style_smithy) use these tools. The
    places of the beds of a house-workshop (the beds of WorkshopStyle) stay free."""
    t.set(2, 1, 4, "minecraft:anvil", {"facing": "east"})
    t.set(width - 2, 1, depth - 3, "minecraft:blast_furnace", {"facing": "west", "lit": "false"})
    t.set(width - 2, 1, depth - 4, "minecraft:furnace", {"facing": "west", "lit": "false"})
    t.set(1, 1, depth - 3, "minecraft:smithing_table")
    t.set(1, 1, depth - 4, "minecraft:grindstone", {"face": "floor", "facing": "east"})
    t.set(width - 2, 1, 3, "minecraft:water_cauldron", {"level": "3"})
    lantern(t, 1, 1, 7)


def norse_storehouse():
    rng = random.Random(15)
    t = long_house_shell(rng, "storehouse", cobble)
    for z in range(LD - 6, LD - 1):
        t.set(1, 1, z, "minecraft:barrel", {"facing": "up", "open": "false"})
        t.set(LW - 2, 1, z, "minecraft:barrel", {"facing": "up", "open": "false"})
    for (x, z) in ((1, 3), (1, 4), (LW - 2, 3)):
        t.set(x, 1, z, "minecraft:hay_block", LOG_Y)
    t.set(1, 2, 3, "minecraft:hay_block", LOG_Y)
    t.set(LW - 2, 1, 4, "minecraft:composter", {"level": "0"})
    lantern(t, 1, 1, 7)
    return t


def norse_storehouse_side():
    rng = random.Random(25)
    t = side_house_shell(rng, "storehouse", cobble)
    for x in range(1, 6):
        t.set(x, 1, SD - 2, "minecraft:barrel", {"facing": "up", "open": "false"})
    for x in range(SL - 6, SL - 1):
        t.set(x, 1, SD - 2, "minecraft:hay_block", LOG_Y)
    t.set(1, 1, 2, "minecraft:composter", {"level": "0"})
    lantern(t, SL - 2, 1, 2)
    return t


def norse_street(bank=False, next_street=False):
    """A Norse street along z: a segment of NORSE_STREET_LENGTH blocks with 2 plots on each side (NORSE_PLOTS), for
    the long houses. The segment to the north has the plot of the bank on its west side, near the plaza. The other
    plots target the pool of the houses: the plan chooses the building of each plot (see VillagePlanPieces). With
    next_street, the connector at the end of the segment targets the pool streets_next (see street_cross)."""
    rng = random.Random(32 if bank else 33)
    t = Template(STREET_WIDTH, 4, NORSE_STREET_LENGTH)
    street_path(t, rng, NORSE_STREET_LENGTH)
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:dirt_path")
    t.jigsaw(mid, 0, NORSE_STREET_LENGTH - 1, "south_up", STREET_NEXT, STREET,
             NEXT_STREETS_POOL if next_street else STREET_ENDS_POOL, "minecraft:dirt_path")
    for i, z in enumerate(NORSE_PLOTS):
        west = BANK_POOL if bank and i == 0 else HOUSE_POOL
        t.jigsaw(0, 1, z, "west_up", PLOT, HOUSE_FRONT, west, "minecraft:air")
        t.jigsaw(STREET_WIDTH - 1, 1, z, "east_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
    lamp_post(t, STREET_WIDTH - 1, NORSE_PLOTS[0] + 5)
    lamp_post(t, 0, NORSE_STREET_LENGTH - 1)
    return t

# ---------------------------------------------------------------- Sunreach style (desert and Mediterranean)

# A flat house: the door faces the street (z = 0 is the porch with its awning).
FW = 9    # x: 0..8
FD = 11   # z: 0 = porch, 1..10 = body
# A courtyard house: rooms around a courtyard open to the sky.
CW = 13   # x: 0..12
CD = 13   # z: 0 = porch, 1..12 = body
FH = 7
# The walls go up to this layer. The flat roof is the layer above it, and the parapet the layer above the roof.
FLAT_WALL_TOP = 3


def awning(t, mid):
    """A striped awning of wool over the porch, on 2 posts."""
    for x in range(mid - 2, mid + 3):
        t.set(x, FLAT_WALL_TOP, 0, "minecraft:orange_wool" if (x - mid) % 2 == 0 else "minecraft:white_wool")
    for x in (mid - 2, mid + 2):
        for y in range(1, FLAT_WALL_TOP):
            t.set(x, y, 0, "minecraft:spruce_fence")


def flat_house_shell(rng, sign, width, depth, wide_front=False, courtyard=False):
    """A Sunreach house: walls of smooth sandstone with pillars of cut sandstone at the corners, a band of terracotta,
    a flat roof with a parapet, small windows with iron bars and a striped awning over the door.

    With a courtyard, the rooms go around a courtyard open to the sky, with a basin in the middle: the inner walls have
    an opening on each side.
    """
    t = Template(width, FH, depth)
    t.fill(0, 1, 1, width - 1, FH - 1, depth - 1, "minecraft:air")
    mid = width // 2
    # Porch.
    for x in range(mid - 2, mid + 3):
        t.set(x, 0, 0, "minecraft:smooth_sandstone")
        for y in range(1, FLAT_WALL_TOP + 1):
            t.set(x, y, 0, "minecraft:air")
    # Floor.
    for x in range(width):
        for z in range(1, depth):
            edge = x in (0, width - 1) or z in (1, depth - 1)
            t.set(x, 0, z, "minecraft:sandstone" if edge else "minecraft:smooth_sandstone")
    # Outer walls.
    for x in range(width):
        for z in range(1, depth):
            if not (x in (0, width - 1) or z in (1, depth - 1)):
                continue
            corner = x in (0, width - 1) and z in (1, depth - 1)
            for y in range(1, FLAT_WALL_TOP + 1):
                if corner:
                    t.set(x, y, z, "minecraft:cut_sandstone")
                elif y == FLAT_WALL_TOP:
                    t.set(x, y, z, "minecraft:terracotta")
                else:
                    t.set(x, y, z, "minecraft:smooth_sandstone")
    # Courtyard: inner walls with an opening on each side, and a basin.
    inner = None
    if courtyard:
        inner = (4, 5, width - 5, depth - 5)   # min x, min z, max x, max z of the open court: rooms of 2 blocks
        x0, z0, x1, z1 = inner
        for x in range(x0 - 1, x1 + 2):
            for z in range(z0 - 1, z1 + 2):
                wall = x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)
                if not wall:
                    continue
                opening = x == (x0 + x1) // 2 or z == (z0 + z1) // 2
                for y in range(1, FLAT_WALL_TOP + 1):
                    if opening and y < FLAT_WALL_TOP:
                        continue
                    t.set(x, y, z, "minecraft:terracotta" if y == FLAT_WALL_TOP else "minecraft:smooth_sandstone")
        cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                t.set(x, 0, z, "minecraft:cut_sandstone")
        t.set(cx, 0, cz, "minecraft:smooth_sandstone")
        t.set(cx, 1, cz, "minecraft:water_cauldron", {"level": "3"})
        for x, z in ((x0, z0), (x1, z1)):
            t.set(x, 1, z, "minecraft:potted_cactus")
    # Windows with iron bars.
    for z in range(3, depth - 2, 4):
        for x in (0, width - 1):
            t.set(x, 2, z, "minecraft:iron_bars")
    for x in (2, width - 3):
        t.set(x, 2, depth - 1, "minecraft:iron_bars")
    # Front opening.
    if wide_front:
        for x in (mid - 1, mid, mid + 1):
            for y in (1, 2):
                t.set(x, y, 1, "minecraft:air")
    else:
        t.set(mid, 1, 1, "minecraft:spruce_door",
              {"facing": "north", "half": "lower", "hinge": "left", "open": "false", "powered": "false"})
        t.set(mid, 2, 1, "minecraft:spruce_door",
              {"facing": "north", "half": "upper", "hinge": "left", "open": "false", "powered": "false"})
    awning(t, mid)
    # Flat roof and parapet. The courtyard stays open.
    for x in range(width):
        for z in range(1, depth):
            if inner and inner[0] <= x <= inner[2] and inner[1] <= z <= inner[3]:
                continue
            t.set(x, FLAT_WALL_TOP + 1, z, "minecraft:smooth_sandstone")
            if x in (0, width - 1) or z in (1, depth - 1):
                if (x + z) % 2 == 0:
                    t.set(x, FLAT_WALL_TOP + 2, z, "minecraft:sandstone_wall")
                else:
                    t.set(x, FLAT_WALL_TOP + 2, z, "minecraft:smooth_sandstone_slab",
                          {"type": "bottom", "waterlogged": "false"})
    # Lights: on the front wall, next to the door. An open front has no wall there: the torches go outside the awning.
    for x in ((mid - 3, mid + 3) if wide_front else (mid - 1, mid + 1)):
        t.set(x, 2, 0, *wall_torch("north"))
    building_sign(t, mid + 1, 1, 0, sign)
    # Jigsaw connector in the porch, facing out (north).
    t.jigsaw(mid, 1, 0, "north_up", HOUSE_FRONT, "minecraft:empty", "minecraft:empty", "minecraft:air")
    return t


def sun_cottage():
    rng = random.Random(42)
    t = flat_house_shell(rng, "house", FW, FD)
    # 3 beds: each bed gives a place to an inhabitant of the village. A free floor block next to the head of each bed
    # lets a villager walk to it.
    for x in (1, 3, FW - 2):
        bed(t, x, FD - 3, "orange")
    t.set(1, 1, 2, "minecraft:crafting_table")
    t.set(FW - 2, 1, 2, "minecraft:chest", {"facing": "west", "type": "single", "waterlogged": "false"})
    t.set(1, 1, 5, "minecraft:decorated_pot", {"cracked": "false", "facing": "east", "waterlogged": "false"})
    t.set(FW - 2, 1, 5, "minecraft:flower_pot")
    lantern(t, 1, 1, 4)
    lantern(t, FW - 2, 1, 6)
    return t


def sun_cottage_courtyard():
    rng = random.Random(43)
    t = flat_house_shell(rng, "house", CW, CD, courtyard=True)
    # 3 beds in the back rooms, with a free floor block next to the head of each bed.
    for x in (1, 3, CW - 2):
        bed(t, x, CD - 3, "orange")
    t.set(1, 1, 2, "minecraft:crafting_table")
    t.set(CW - 2, 1, 2, "minecraft:chest", {"facing": "west", "type": "single", "waterlogged": "false"})
    t.set(1, 1, 6, "minecraft:decorated_pot", {"cracked": "false", "facing": "east", "waterlogged": "false"})
    lantern(t, 1, 1, 4)
    lantern(t, CW - 2, 1, 6)
    return t


def sun_barracks():
    rng = random.Random(44)
    t = flat_house_shell(rng, "barracks", CW, CD, courtyard=True)
    # A free floor block next to the head of each bed lets a villager walk to it.
    for x in (1, 3, CW - 4, CW - 2):
        bed(t, x, CD - 3, "red")
    t.set(1, 1, 2, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(CW - 2, 1, 2, BANNER, {"rotation": "4"})
    t.set(1, 1, 6, "minecraft:barrel", {"facing": "up", "open": "false"})
    lantern(t, 1, 1, 4)
    lantern(t, CW - 2, 1, 6)
    return t


def sun_forge():
    rng = random.Random(45)
    t = flat_house_shell(rng, "forge", FW, FD, wide_front=True)
    t.set(2, 1, 4, "minecraft:anvil", {"facing": "east"})
    t.set(FW - 2, 1, FD - 2, "minecraft:blast_furnace", {"facing": "west", "lit": "false"})
    t.set(FW - 2, 1, FD - 3, "minecraft:furnace", {"facing": "west", "lit": "false"})
    t.set(1, 1, FD - 2, "minecraft:smithing_table")
    t.set(1, 1, FD - 3, "minecraft:grindstone", {"face": "floor", "facing": "east"})
    t.set(FW - 2, 1, 3, "minecraft:water_cauldron", {"level": "3"})
    lantern(t, 1, 1, 6)
    return t


def sun_storehouse():
    rng = random.Random(46)
    t = flat_house_shell(rng, "storehouse", CW, CD, courtyard=True)
    for x in range(1, 5):
        t.set(x, 1, CD - 2, "minecraft:barrel", {"facing": "up", "open": "false"})
    for x in range(CW - 5, CW - 1):
        t.set(x, 1, CD - 2, "minecraft:decorated_pot", {"cracked": "false", "facing": "north", "waterlogged": "false"})
    for z in (2, 3):
        t.set(1, 1, z, "minecraft:hay_block", LOG_Y)
    t.set(CW - 2, 1, 2, "minecraft:composter", {"level": "0"})
    lantern(t, 1, 1, 5)
    return t

# ---------------------------------------------------------------- maritime pieces

def weathered(rng):
    """The lower wall of a maritime building: mossy cobblestone and stone. The processor of the maritime variants
    replaces the stone by the mossy stone of Immersive Weathering, when the mod is present."""
    return "minecraft:mossy_cobblestone" if rng.random() < 0.6 else "minecraft:stone"


def planks(_rng):
    return "minecraft:spruce_planks"


def stone_base(t):
    """A row of stone slabs on the porch, between the posts: the quay of a maritime building."""
    for x in range(1, W - 1):
        if t.get(x, 0, 0) == "minecraft:spruce_planks":
            t.set(x, 0, 0, "minecraft:stone_slab", {"type": "double", "waterlogged": "false"})


def iron_windows(t):
    """Iron bars in the side windows: the processor of the maritime variants rusts them, when Immersive Weathering is
    present."""
    for z in (3, 6):
        t.set(0, 2, z, "minecraft:iron_bars")
        t.set(W - 1, 2, z, "minecraft:iron_bars")


def fishing_hut():
    """The home of a family of fishers: 2 beds, the barrels of the catch, a smoker and the nets on the walls. The job sign
    of the fisher stands next to the west porch post: the hut is also the building of the fisher."""
    rng = random.Random(21)
    t = house_shell(rng, weathered, planks, "house")
    iron_windows(t)
    # A free floor block between the 2 beds lets a villager walk to the head of each bed.
    for x in (1, 3):
        bed(t, x, 6, "cyan")
    t.set(7, 1, 7, "minecraft:smoker", {"facing": "north", "lit": "false"})
    t.set(6, 1, 7, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(7, 1, 5, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(7, 1, 2, "minecraft:water_cauldron", {"level": "3"})
    # The nets: cobwebs high on the walls, over the free floor: on the back wall between the 2 beds and east of the
    # lantern, and on the west wall in front of the beds. A player on the floor walks under them. No net is over a bed
    # or another block that a player stands on.
    for x, z in ((2, 7), (5, 7), (1, 4)):
        t.set(x, 3, z, "minecraft:cobweb")
    lantern(t, 4, 1, 7)
    lantern(t, 1, 1, 2)
    stone_base(t)
    job_sign(t, 1, 1, 0, "fisher", "minecraft:cod")
    return t


def fish_market():
    """The fish market: an open hall with the stalls of the catch."""
    rng = random.Random(22)
    t = house_shell(rng, weathered, planks, "market", wide_front=True)
    iron_windows(t)
    # The stalls: a counter of slabs, with the barrels and the water tanks of the catch.
    for x in (1, 2, 6, 7):
        t.set(x, 1, 4, "minecraft:spruce_slab", {"type": "top", "waterlogged": "false"})
    for x in (1, 7):
        t.set(x, 1, 5, "minecraft:water_cauldron", {"level": "3"})
    for (x, z) in ((1, 7), (2, 7), (6, 7), (7, 7)):
        t.set(x, 1, z, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(4, 1, 7, "minecraft:smoker", {"facing": "north", "lit": "false"})
    t.set(2, 1, 5, "minecraft:blue_carpet")
    t.set(6, 1, 5, "minecraft:blue_carpet")
    lantern(t, 4, 1, 4)
    stone_base(t)
    return t


def smokehouse():
    """The smokehouse: the smokers and the racks of the fish of the village."""
    rng = random.Random(23)
    t = house_shell(rng, weathered, weathered, "storehouse")
    iron_windows(t)
    for x in (2, 4, 6):
        t.set(x, 1, 7, "minecraft:smoker", {"facing": "north", "lit": "false"})
    # The racks: chains from the roof beam, over the smokers.
    for x in (2, 4, 6):
        t.set(x, 3, 6, "minecraft:chain", {"axis": "y", "waterlogged": "false"})
    # The barrels in the back corners have a free floor block in front of them: a villager can walk to them.
    for (x, z) in ((1, 7), (7, 7), (1, 5), (7, 5)):
        t.set(x, 1, z, "minecraft:barrel", {"facing": "up", "open": "false"})
    t.set(1, 1, 2, "minecraft:composter", {"level": "0"})
    lantern(t, 4, 1, 3)
    stone_base(t)
    return t

# ---------------------------------------------------------------- job buildings

JOB_SIGN = f"{BLOCKS}:job_sign"
# A job building: a shed on the street (z = 0 is the porch, z = 1..SHED_DEPTH - 1 is the body) and a garden behind it,
# with a fence around the garden.
SHED_DEPTH = 5
GARDEN_Z = range(SHED_DEPTH, D - 1)
GARDEN_X = range(1, W - 1)
# The water of a field, in the middle of the garden.
WATER = (W // 2, SHED_DEPTH + 1)
GRASS = ("minecraft:grass_block", {"snowy": "false"})
FARMLAND = ("minecraft:farmland", {"moisture": "7"})


# The beds of a house-workshop: each job building is the home of a household that does the job. Each job building has
# HOME_BEDS beds, and each bed gives a place to an inhabitant of the village (see VillageBeds).
HOME_BEDS = 2
HOME_BED_COLOR = "brown"


def home_beds(t, *feet, facing="south"):
    """The HOME_BEDS beds of a house-workshop: the foot (x, z) of each bed, and the direction of its head. A foot
    (x, z, facing) gives the direction of its bed. A bed stands next to a free floor block, so that an inhabitant can
    go to it."""
    assert len(feet) == HOME_BEDS, feet
    for foot in feet:
        bed(t, foot[0], foot[1], HOME_BED_COLOR, foot[2] if len(foot) > 2 else facing)


def job_sign(t, x, y, z, job, icon, facing="north"):
    """The job sign of a building: the job id (the name of the job file) and the icon item (see JobSignBlockEntity)."""
    t.set(x, y, z, JOB_SIGN, {"facing": facing}, {"id": JOB_SIGN, "job": job, "icon": icon})


def block(entry):
    """The name and the properties of a block: a name alone, or a pair (name, properties)."""
    return entry if isinstance(entry, tuple) else (entry, None)


def job_building(rng, job, icon, ground, plants, work=("minecraft:composter", {"level": "0"})):
    """The house-workshop of a job of the fields and the gardens: a timber frame shed and a garden behind it.

    - The shed: the house shell (see house_shell) with a body of SHED_DEPTH - 1 rows, a door on the street and a back
      door to the garden. The job sign stands on the porch next to the west porch post, and the house sign next to the
      east porch post.
    - The work blocks: the work block of the vanilla profession of the job (a composter for the farmer) in the west
      half of the shed, and a barrel next to the door.
    - The home: 2 beds side by side in the east half of the shed, with the heads to the east. The path from the door to
      the back door (x = 4) goes along the feet of the beds.
    - The garden: ground(x, z) gives the ground block of each position, and plants(x, z) gives the block above it, or
      None. A fence goes around the garden, with a lantern on each back corner.
    """
    t = house_shell(rng, cobble, planks, "house", depth=SHED_DEPTH)
    mid = W // 2
    back = SHED_DEPTH - 1
    for y, half in ((1, "lower"), (2, "upper")):
        t.set(mid, y, back, "minecraft:spruce_door",
              {"facing": "south", "half": half, "hinge": "left", "open": "false", "powered": "false"})
    t.set(mid, 3, back, "minecraft:stripped_spruce_log", {"axis": "x"})
    t.set(1, 1, back - 1, *work)
    t.set(mid - 1, 1, 2, "minecraft:barrel", {"facing": "up", "open": "false"})
    # The lantern stands on the barrel: the head of each bed has a free floor block next to it.
    lantern(t, mid - 1, 2, 2)
    home_beds(t, (mid + 1, 2), (mid + 1, 3), facing="east")
    job_sign(t, 1, 1, 0, job, icon)
    for x in range(W):
        for z in range(SHED_DEPTH, D):
            if x in GARDEN_X and z in GARDEN_Z:
                t.set(x, 0, z, *block(ground(x, z)))
                plant = plants(x, z)
                if plant:
                    t.set(x, 1, z, *block(plant))
            else:
                t.set(x, 0, z, cobble(rng))
                t.set(x, 1, z, "minecraft:spruce_fence")
    for x in (0, W - 1):
        lantern(t, x, 2, D - 1)
    return t


# The water of a field: a waterlogged slab. The slab holds the water on uneven ground, and the foundation goes under
# it. Farmland and sugar cane get their water from the slab.
WATER_SLAB = ("minecraft:spruce_slab", {"type": "bottom", "waterlogged": "true"})


def field_ground(x, z, water=WATER):
    """Farmland around the water of the field, and a path of coarse dirt from the back door to the water: a builder
    stands on the path to add the water."""
    if (x, z) == water:
        return WATER_SLAB
    return "minecraft:coarse_dirt" if x == water[0] else FARMLAND


def crop(name, rng, top=7):
    """A crop of a random age, near the end of its growth."""
    return name, {"age": str(rng.randint(top - 3, top))}


def farm():
    """The building of the farmer: a field of wheat, carrots, potatoes and beetroots around the water, and hay bales
    in the shed."""
    rng = random.Random(51)
    rows = {1: "wheat", 2: "wheat", 3: "carrots", 5: "potatoes", 6: "beetroots", 7: "beetroots"}

    def plants(x, z):
        if x == WATER[0]:
            return None
        return crop(f"minecraft:{rows[x]}", rng, 3 if rows[x] == "beetroots" else 7)

    t = job_building(rng, "farmer", "minecraft:wheat", field_ground, plants)
    for (x, y) in ((1, 1), (2, 1), (1, 2)):
        t.set(x, y, 2, "minecraft:hay_block", LOG_Y)
    return t


def garden():
    """The building of the gardener: pumpkins and melons on their stems, sugar cane next to the water, and sweet
    berry bushes on the side edges, out of the path.

    The sugar cane is 1 block high, with no block next to it: a builder places it before the water, and no later
    block next to it makes it check its water.
    """
    rng = random.Random(52)
    mid, row = WATER
    stems = {(2, row - 1), (2, row + 1), (W - 3, row - 1), (W - 3, row + 1)}

    def ground(x, z):
        if x == mid:
            return field_ground(x, z)
        # A fruit on farmland makes the farmland dirt: the fruits stand on grass.
        return FARMLAND if (x, z) in stems else GRASS

    def plants(x, z):
        side = x - mid
        fruit = "pumpkin" if side < 0 else "melon"
        if z == row:
            if abs(side) == 3:
                return "minecraft:sweet_berry_bush", {"age": "3"}
            if abs(side) == 1:
                return "minecraft:sugar_cane", {"age": "0"}
            return None
        if abs(side) == 3:
            return f"minecraft:{fruit}"
        if abs(side) == 2:
            return f"minecraft:attached_{fruit}_stem", {"facing": "west" if side < 0 else "east"}
        return None

    t = job_building(rng, "gardener", "minecraft:pumpkin", ground, plants)
    t.set(1, 1, 2, "minecraft:pumpkin")
    t.set(2, 1, 2, "minecraft:melon")
    return t


def jungle_garden():
    """The building of the gardener of the jungle: cocoa pods on 2 posts of jungle logs, melons and ferns."""
    rng = random.Random(53)
    mid = W // 2
    posts = ((2, WATER[1]), (W - 3, WATER[1]))

    def ground(x, z):
        if (x, z) == WATER:
            return WATER_SLAB
        return FARMLAND if x == mid else GRASS

    def plants(x, z):
        if (x, z) == WATER:
            return None
        if (x, z) in posts:
            return "minecraft:jungle_log", LOG_Y
        if x == mid:
            return "minecraft:attached_melon_stem", {"facing": "west" if z < WATER[1] else "east"}
        if (x - mid, z - WATER[1]) in ((-1, -1), (1, 1)):
            return "minecraft:melon"
        if abs(x - mid) == 2:
            return None
        return "minecraft:fern" if rng.random() < 0.5 else None

    t = job_building(rng, "gardener_jungle", "minecraft:cocoa_beans", ground, plants)
    # The posts: 3 jungle logs high, with a cocoa pod on each side. A pod faces its log.
    for px, pz in posts:
        for y in (2, 3):
            t.set(px, y, pz, "minecraft:jungle_log", LOG_Y)
        for side, (dx, dz) in SIDES.items():
            t.set(px + dx, 2, pz + dz, "minecraft:cocoa", {"age": str(rng.randint(1, 2)), "facing": TOWARD[side]})
    t.set(1, 1, 2, "minecraft:melon")
    return t


FLOWERS = ("minecraft:dandelion", "minecraft:poppy", "minecraft:cornflower")


def forage_ground(x, z, width=W):
    """The ground of the yard of the forager in a building of this width: podzol in the west half, grass in the east
    half, and a path of coarse dirt in the middle."""
    if x == width // 2:
        return "minecraft:coarse_dirt"
    return "minecraft:podzol" if x < width // 2 else GRASS


def forage_plants(x, z, width=W):
    """The finds of the forager in a building of this width: mushrooms on the podzol and wild flowers on the grass."""
    if x == width // 2:
        return None
    if x < width // 2:
        return "minecraft:brown_mushroom" if (x + z) % 2 == 0 else "minecraft:red_mushroom"
    return FLOWERS[(x + z) % len(FLOWERS)]


def forager_hut():
    """The building of the forager: mushrooms on podzol, wild flowers on grass, and the finds in a barrel."""
    rng = random.Random(54)
    t = job_building(rng, "forager", "minecraft:red_mushroom", forage_ground, forage_plants)
    t.set(1, 1, 2, "minecraft:moss_block")
    t.set(2, 1, 2, "minecraft:barrel", {"facing": "up", "open": "false"})
    return t


def apiary():
    """The building of the beekeeper: 2 beehives among the flowers. A lit campfire under each hive gives the smoke that
    calms the bees."""
    rng = random.Random(55)
    hives = ((2, WATER[1]), (W - 3, WATER[1]))
    flowers = ("minecraft:dandelion", "minecraft:poppy", "minecraft:cornflower", "minecraft:oxeye_daisy",
               "minecraft:allium")

    def plants(x, z):
        if (x, z) in hives:
            return "minecraft:campfire", {"facing": "south", "lit": "true", "signal_fire": "false",
                                          "waterlogged": "false"}
        return flowers[rng.randrange(len(flowers))] if (x + z) % 2 == 0 else None

    t = job_building(rng, "beekeeper", "minecraft:honeycomb", lambda x, z: GRASS, plants)
    for x, z in hives:
        t.set(x, 2, z, "minecraft:beehive", {"facing": "south", "honey_level": str(rng.randint(2, 5))})
    return t


def job_house(rng, job, icon, wall_upper, beds, wide_front=False):
    """The house-workshop of a job in a house: the house shell with a door on the street, or an open shop front. The
    job sign stands on the porch next to the west porch post, and the house sign next to the east porch post. beds
    gives the foot of each bed of the home (see home_beds): the head is 1 block to the back wall."""
    t = house_shell(rng, cobble, wall_upper, "house", wide_front=wide_front)
    home_beds(t, *beds)
    job_sign(t, 1, 1, 0, job, icon)
    return t


def oven(t, x):
    """A brick oven on the back wall: a furnace in bricks, with a chimney of bricks through the roof."""
    z = D - 2
    t.set(x, 1, z, "minecraft:furnace", {"facing": "north", "lit": "false"})
    for dx in (-1, 0, 1):
        t.set(x + dx, 2, z, "minecraft:bricks")
        if dx:
            t.set(x + dx, 1, z, "minecraft:bricks")
    for y in range(3, H):
        t.set(x + 1, y, z, "minecraft:bricks")


BARREL = ("minecraft:barrel", {"facing": "up", "open": "false"})
TOP_SLAB = ("minecraft:spruce_slab", {"type": "top", "waterlogged": "false"})


def bakery():
    """The building of the baker: a brick oven, a crafting table to knead the dough, barrels of flour, the wheat on
    hay bales and a cake on the counter."""
    rng = random.Random(56)
    t = job_house(rng, "baker", "minecraft:bread", planks, ((1, 3), (7, 3)))
    oven(t, 4)
    t.set(1, 1, 7, "minecraft:crafting_table")
    for x, z in ((1, 5), (2, 7)):
        t.set(x, 1, z, *BARREL)
    for x, y, z in ((7, 1, 7), (7, 1, 6), (7, 2, 7)):
        t.set(x, y, z, "minecraft:hay_block", LOG_Y)
    t.set(7, 1, 2, *TOP_SLAB)
    t.set(7, 2, 2, "minecraft:cake", {"bites": "0"})
    lantern(t, 1, 1, 2)
    return t


def pastry_shop():
    """The building of the pastry cook: an open shop front, a counter with cakes, a brick oven, pumpkins and barrels
    of sugar."""
    rng = random.Random(57)
    t = job_house(rng, "pastry_cook", "minecraft:pumpkin_pie", calcite, ((2, 5), (6, 4)), wide_front=True)
    oven(t, 4)
    # The counter behind the open front, with a free passage in the middle.
    for x in (1, 2, 6, 7):
        t.set(x, 1, 3, *TOP_SLAB)
    for x in (2, 6):
        t.set(x, 2, 3, "minecraft:candle_cake", {"lit": "false"})
    for x, y, z in ((1, 1, 7), (2, 1, 7), (1, 2, 7)):
        t.set(x, y, z, "minecraft:pumpkin")
    for x, z in ((7, 7), (7, 6)):
        t.set(x, 1, z, *BARREL)
    lantern(t, 1, 1, 5)
    return t


def butchery():
    """The building of the butcher: 2 smokers (the work block of the vanilla butcher), a block of coal, a chopping
    block, and a rack of chains on a beam across the room. The chains leave 2 blocks of space under them."""
    rng = random.Random(58)
    t = job_house(rng, "butcher", "minecraft:cooked_beef", planks, ((1, 4), (7, 4)))
    for x in (5, 6):
        t.set(x, 1, 7, "minecraft:smoker", {"facing": "north", "lit": "false"})
    t.set(7, 1, 7, "minecraft:coal_block")
    for x in range(1, W - 1):
        t.set(x, 4, 5, "minecraft:stripped_spruce_log", {"axis": "x"})
    for x in (2, 3, 6):
        t.set(x, 3, 5, "minecraft:chain", {"axis": "y", "waterlogged": "false"})
    t.set(2, 1, 3, "minecraft:stripped_oak_log", LOG_Y)
    for x, z in ((1, 7), (1, 6)):
        t.set(x, 1, z, *BARREL)
    t.set(7, 1, 3, "minecraft:water_cauldron", {"level": "3"})
    lantern(t, 1, 1, 2)
    return t


def pen_gate(t, width, depth):
    """The gate of a pen: a fence gate in the middle of the back edge of the yard. A player brings animals into the
    pen through it. The animals and the villagers do not open a fence gate."""
    t.set(width // 2, 1, depth - 1, "minecraft:spruce_fence_gate",
          {"facing": "south", "in_wall": "false", "open": "false", "powered": "false"})


def ranch():
    """The building of the rancher: a shed with a smoker (the work block of the vanilla butcher) and a pen behind it,
    with hay and a water trough. The doors of the shed and the fence keep the animals in the pen.

    The template has no animals: a template with entities adds them again at each placement (the village structure
    and each construction of the builders). The heart of the village puts the animals into the pen (VillagePens in
    the world mod), and a player can bring animals into the pen through the gate of the pen (see pen_gate).
    """
    rng = random.Random(59)

    def ground(x, z):
        return "minecraft:coarse_dirt" if (x, z) == (W // 2, SHED_DEPTH) else GRASS

    def plants(x, z):
        # The hay and the trough are not next to the fence: a fence connects to a full block.
        if (x, z) == (2, SHED_DEPTH + 1):
            return "minecraft:hay_block", LOG_Y
        if (x, z) == (W - 3, SHED_DEPTH + 1):
            return "minecraft:water_cauldron", {"level": "3"}
        return None

    t = job_building(rng, "rancher", "minecraft:lead", ground, plants,
                     work=("minecraft:smoker", {"facing": "east", "lit": "false"}))
    t.set(1, 1, 2, "minecraft:hay_block", LOG_Y)
    pen_gate(t, W, D)
    return t


def fisher_hut():
    """The building of the fisher: a shed with barrels (the work block of the vanilla fisherman), the nets and a
    tub, and a yard with a pond for the live catch. The pond is a row of waterlogged slabs, with a path of coarse
    dirt next to it: a builder stands on the path to add the water."""
    rng = random.Random(60)
    pond = range(2, W - 2)
    row = D - 2

    def ground(x, z):
        if z == row and x in pond:
            return WATER_SLAB
        return "minecraft:coarse_dirt" if z == row - 1 or x == W // 2 else GRASS

    def plants(x, z):
        return BARREL if (x, z) in ((2, SHED_DEPTH), (W - 3, SHED_DEPTH)) else None

    t = job_building(rng, "fisher", "minecraft:cod", ground, plants, work=BARREL)
    t.set(1, 1, 2, "minecraft:water_cauldron", {"level": "3"})
    # The nets: cobwebs high on the east side wall, over the free floor next to the heads of the beds. A player on the
    # floor walks under them. No net is over a block that a player stands on (the barrel, the tub).
    for z in (2, 3):
        t.set(W - 2, 3, z, "minecraft:cobweb")
    return t


# The woods of the jobs of the wood: the wood of each job id.
LUMBERJACKS = {"lumberjack": "oak", **{f"lumberjack_{wood}": wood for wood in (
    "acacia", "birch", "cherry", "dark_oak", "jungle", "mangrove", "spruce")}}
SAWYERS = {"sawyer": "oak", "sawyer_spruce": "spruce"}
# The potted sapling of each wood: a sapling in a pot does not grow into a tree.
POTTED = {"mangrove": "minecraft:potted_mangrove_propagule"}


def job_material(t, x, y, z, name, props=None):
    """A block of the material of the job. The biome variants do not change it: the lumberjack of spruce keeps its
    spruce logs in the plains, and the mason keeps its stone bricks in the desert."""
    t.set(x, y, z, name, props)
    t.fixed.add((x, y, z))


def lumber_yard(job, wood):
    """The building of a lumberjack: the shed with a grindstone for the axes and a potted sapling, and a yard with
    piles of logs of the wood of the job and a chopping block.

    All the lumberjacks have this shape. Only the wood of the logs, of the chopping block and of the sapling changes.
    The yard has no sapling in the ground: a sapling grows into a tree.
    """
    log = f"minecraft:{wood}_log"
    rng = random.Random(61)

    def ground(x, z):
        return GRASS if x in (1, 2, W - 2) else "minecraft:coarse_dirt"

    t = job_building(rng, job, log, ground, lambda x, z: None,
                     work=("minecraft:grindstone", {"face": "floor", "facing": "north"}))
    t.set(1, 1, 2, POTTED.get(wood, f"minecraft:potted_{wood}_sapling"))
    # The west pile: 2 rows of logs along the fence, the row on the fence 2 logs high.
    for z in GARDEN_Z:
        for x, top in ((1, 2), (2, 1)):
            for y in range(1, top + 1):
                job_material(t, x, y, z, log, {"axis": "z"})
    # The chopping block: a log on its end. The split wood: stripped logs in the back corner.
    job_material(t, W - 3, 1, WATER[1], log, LOG_Y)
    for x, y in ((W - 3, 1), (W - 2, 1), (W - 2, 2)):
        job_material(t, x, y, D - 2, f"minecraft:stripped_{wood}_log", {"axis": "x"})
    t.set(W - 2, 1, SHED_DEPTH, POTTED.get(wood, f"minecraft:potted_{wood}_sapling"))
    return t


def sawmill(job, wood):
    """The building of a sawyer: a house with a saw bench (a stonecutter between the logs and the slabs), a pile of
    logs, a stack of planks, a crafting table and a barrel.

    All the sawyers have this shape. Only the wood of the logs, the planks and the slabs changes.
    """
    rng = random.Random(62)
    t = job_house(rng, job, f"minecraft:{wood}_planks", planks, ((2, 3), (6, 3)))
    bench = 5
    for x in (2, 3):
        job_material(t, x, 1, bench, f"minecraft:{wood}_log", {"axis": "x"})
    t.set(4, 1, bench, "minecraft:stonecutter", {"facing": "north"})
    for x in (5, 6):
        job_material(t, x, 1, bench, f"minecraft:{wood}_slab", {"type": "bottom", "waterlogged": "false"})
    # The logs to saw (west) and the planks (east) on the back wall. The middle of the room stays free.
    for x, y in ((1, 1), (2, 1), (3, 1), (1, 2), (2, 2)):
        job_material(t, x, y, D - 2, f"minecraft:{wood}_log", {"axis": "x"})
    for x, y, z in ((6, 1, D - 2), (7, 1, D - 2), (7, 2, D - 2), (7, 1, D - 3)):
        job_material(t, x, y, z, f"minecraft:{wood}_planks")
    t.set(1, 1, 3, "minecraft:crafting_table")
    t.set(W - 2, 1, 2, *BARREL)
    lantern(t, 1, 1, 2)
    lantern(t, W - 2, 1, 3)
    return t


def mine():
    """The building of the miner: the shed with a smithing table (the work block of the vanilla toolsmith), and a
    yard with a rock face at the back. A timber frame in the rock face makes the mouth of the mine, with a lantern under
    the beam. A rail goes from the back door to the mouth. A pile of cobblestone stands next to the rail.

    The rock face has stone, andesite and ores. The cobblestone of the pile stays cobblestone in all the variants.
    """
    rng = random.Random(63)
    mid = W // 2

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == mid else "minecraft:gravel"

    def plants(x, z):
        return ("minecraft:rail", {"shape": "north_south", "waterlogged": "false"}) if x == mid else None

    t = job_building(rng, "miner", "minecraft:iron_pickaxe", ground, plants, work=("minecraft:smithing_table", None))
    rock = {1: ("minecraft:stone", "minecraft:andesite"), 2: ("minecraft:coal_ore", "minecraft:stone"),
            W - 3: ("minecraft:iron_ore", "minecraft:stone"), W - 2: ("minecraft:andesite", "minecraft:copper_ore")}
    for x, column in rock.items():
        for y, name in enumerate(column, 1):
            t.set(x, y, D - 2, name)
    # The mouth: 2 posts and a beam in the rock face, and the rock behind it in place of the back fence.
    for x in (mid - 1, mid + 1):
        for y in (1, 2):
            t.set(x, y, D - 2, "minecraft:spruce_log", LOG_Y)
    for x in (mid - 1, mid, mid + 1):
        t.set(x, 3, D - 2, "minecraft:spruce_log", {"axis": "x"})
        for y in (1, 2, 3):
            t.set(x, y, D - 1, "minecraft:stone")
    t.set(mid, 2, D - 2, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})
    for x, z in ((1, SHED_DEPTH), (2, SHED_DEPTH), (1, SHED_DEPTH + 1)):
        job_material(t, x, 1, z, "minecraft:cobblestone")
    t.set(1, 1, 2, *BARREL)
    return t


def dig_yard():
    """The building of the digger: the shed with a stonecutter (the work block of the vanilla mason), and a yard with
    heaps of sand, gravel, clay and dirt along the fence. A path of coarse dirt goes from the back door to the back
    fence."""
    rng = random.Random(64)
    heaps = {(1, D - 2): "sand", (2, D - 2): "sand", (1, D - 3): "sand", (3, D - 2): "gravel",
             (W - 4, D - 2): "clay", (W - 3, D - 2): "dirt", (W - 2, D - 2): "dirt", (W - 2, D - 3): "gravel"}

    def plants(x, z):
        return f"minecraft:{heaps[(x, z)]}" if (x, z) in heaps else None

    t = job_building(rng, "digger", "minecraft:iron_shovel", lambda x, z: "minecraft:coarse_dirt", plants,
                     work=("minecraft:stonecutter", {"facing": "east"}))
    t.set(1, 2, D - 2, "minecraft:sand")
    t.set(W - 2, 2, D - 2, "minecraft:dirt")
    t.set(1, 1, 2, *BARREL)
    return t


def stone_bricks(_rng):
    return "minecraft:stone_bricks"


def mason_workshop():
    """The building of the mason: a house of stone bricks with a stonecutter (the work block of the vanilla mason) and
    a block of stone to cut, a brick kiln on the back wall, and stacks of stone bricks, bricks, smooth stone and
    terracotta. The stacks of stone bricks stay stone bricks in all the variants."""
    rng = random.Random(65)
    t = job_house(rng, "mason", "minecraft:stone_bricks", stone_bricks, ((1, 4), (6, 4)))
    oven(t, 4)
    for x, y, z in ((1, 1, D - 2), (1, 2, D - 2), (2, 1, D - 2), (1, 1, D - 3)):
        job_material(t, x, y, z, "minecraft:stone_bricks")
    for x, y, z in ((W - 2, 1, D - 2), (W - 2, 2, D - 2)):
        t.set(x, y, z, "minecraft:bricks")
    t.set(W - 3, 1, D - 2, "minecraft:smooth_stone")
    t.set(W - 2, 1, D - 3, "minecraft:terracotta")
    t.set(2, 1, 4, "minecraft:stonecutter", {"facing": "east"})
    t.set(2, 1, 3, "minecraft:stone")
    t.set(W - 2, 1, 3, *BARREL)
    lantern(t, 1, 1, 2)
    lantern(t, W - 2, 1, 5)
    return t


# The metals of the smelters: the raw blocks of the ores, and the weighted pressure plate that shows the ingots.
SMELTERS = {
    "smelter": ("minecraft:iron_ingot", ("minecraft:raw_iron_block", "minecraft:raw_copper_block"),
                "minecraft:heavy_weighted_pressure_plate"),
    "gold_smelter": ("minecraft:gold_ingot", ("minecraft:raw_gold_block", "minecraft:raw_gold_block"),
                     "minecraft:light_weighted_pressure_plate"),
}


def smeltery(job):
    """The building of a smelter: a brick house with 2 blast furnaces (the work block of the vanilla armorer) and a
    furnace in a brick oven, the raw ore and a block of coal, a water cauldron to cool the metal, and the ingots on a
    counter. The smelter also makes glass: its building has a barrel of sand.

    All the smelters have this shape. Only the ore and the ingots change: the weighted pressure plates show the
    ingots (iron for the smelter, gold for the smelter of gold).
    """
    icon, ores, plate = SMELTERS[job]
    rng = random.Random(66)
    t = job_house(rng, job, icon, lambda r: "minecraft:bricks", ((1, 3), (7, 4)))
    oven(t, 4)
    for x in (2, W - 3):
        t.set(x, 1, D - 2, "minecraft:blast_furnace", {"facing": "north", "lit": "false"})
    for z, ore in zip((D - 2, D - 3), ores):
        t.set(1, 1, z, ore)
    t.set(1, 1, D - 4, "minecraft:coal_block")
    if job == "smelter":
        t.set(W - 2, 1, 2, *BARREL)
    t.set(W - 2, 1, D - 3, "minecraft:water_cauldron", {"level": "3"})
    for x in (W - 3, W - 2):
        t.set(x, 1, 3, *TOP_SLAB)
        t.set(x, 2, 3, plate, {"power": "0"})
    lantern(t, 1, 1, 2)
    return t


def tinker_workshop():
    """The building of the tinker: a house with a smithing table (the work block of the vanilla toolsmith), blocks of
    iron and copper, a crafting table, a short track of rails, and a counter with lanterns and a lightning rod (the
    goods of the job)."""
    rng = random.Random(67)
    t = job_house(rng, "tinker", "minecraft:bucket", planks, ((2, 5), (7, 2)))
    t.set(W - 3, 1, D - 2, "minecraft:smithing_table")
    t.set(1, 1, D - 2, "minecraft:iron_block")
    t.set(1, 2, D - 2, "minecraft:waxed_copper_block")
    t.set(1, 1, D - 3, "minecraft:waxed_copper_block")
    t.set(2, 1, D - 2, "minecraft:crafting_table")
    t.set(W - 2, 1, D - 2, *BARREL)
    for z in range(3, 6):
        t.set(1, 1, z, "minecraft:rail", {"shape": "north_south", "waterlogged": "false"})
    for x in (W - 3, W - 2):
        t.set(x, 1, 4, *TOP_SLAB)
    lantern(t, W - 3, 2, 4)
    t.set(W - 2, 2, 4, "minecraft:lightning_rod", {"facing": "up", "powered": "false", "waterlogged": "false"})
    lantern(t, 1, 1, 2)
    lantern(t, W - 2, 2, D - 2)
    return t


def fletcher_range():
    """The building of the fletcher: the shed with a fletching table (the work block of the vanilla fletcher), and a
    yard with 2 targets on hay bales and a heap of gravel (the flint of the arrows). A path of coarse dirt goes from
    the back door to the back fence. The targets and the hay bales are not next to the fence."""
    rng = random.Random(68)
    targets = (2, W - 3)
    row = SHED_DEPTH + 1

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == W // 2 else GRASS

    def plants(x, z):
        if x in targets and z == row:
            return "minecraft:hay_block", LOG_Y
        return "minecraft:gravel" if (x, z) == (1, D - 2) else None

    t = job_building(rng, "fletcher", "minecraft:arrow", ground, plants, work=("minecraft:fletching_table", None))
    for x in targets:
        t.set(x, 2, row, "minecraft:target", {"power": "0"})
    t.set(1, 1, 2, *BARREL)
    return t


def carpenter_workshop():
    """The building of the carpenter: a house with 2 crafting tables, a bench of oak slabs, a stack of oak planks, and
    chests and barrels (the goods of the job). The oak planks and the oak slabs stay oak in all the variants."""
    rng = random.Random(69)
    t = job_house(rng, "carpenter", "minecraft:crafting_table", planks, ((2, 5), (6, 5)))
    t.set(2, 1, 4, "minecraft:crafting_table")
    t.set(W - 3, 1, 4, "minecraft:crafting_table")
    for x in (3, W - 2):
        job_material(t, x, 1, 4, "minecraft:oak_slab", {"type": "top", "waterlogged": "false"})
    for x, y, z in ((W - 2, 1, D - 2), (W - 2, 2, D - 2), (W - 3, 1, D - 2), (W - 2, 1, D - 3)):
        job_material(t, x, y, z, "minecraft:oak_planks")
    for x in (1, 2):
        t.set(x, 1, D - 2, "minecraft:chest", {"facing": "north", "type": "single", "waterlogged": "false"})
    t.set(W - 4, 1, D - 2, *BARREL)
    lantern(t, 1, 1, 2)
    lantern(t, W - 2, 1, 2)
    return t


def tannery():
    """The building of the tanner: the shed with a cauldron (the work block of the vanilla leatherworker), and a yard
    with 2 vats of water (water cauldrons) and a rack of fences with hides (brown carpets) on it. A path of coarse dirt
    goes from the back door to the back fence. The vats are not next to the fence."""
    rng = random.Random(70)
    vats = ((2, SHED_DEPTH + 1), (3, SHED_DEPTH + 1))
    rack = ((W - 3, SHED_DEPTH + 1), (W - 3, D - 2))

    def ground(x, z):
        return "minecraft:coarse_dirt" if x in (W // 2, W - 3) else GRASS

    def plants(x, z):
        if (x, z) in vats:
            return "minecraft:water_cauldron", {"level": "3"}
        return "minecraft:spruce_fence" if (x, z) in rack else None

    t = job_building(rng, "tanner", "minecraft:leather", ground, plants, work=("minecraft:cauldron", None))
    for x, z in rack:
        t.set(x, 2, z, "minecraft:brown_carpet")
    t.set(1, 1, 2, *BARREL)
    return t


LOOM = ("minecraft:loom", {"facing": "north"})
WATER_CAULDRON = ("minecraft:water_cauldron", {"level": "3"})


def sheepfold():
    """The building of the shepherd: the shed with a loom (the work block of the vanilla shepherd) and bales of wool,
    and a pen behind it with hay, a water trough and a salt lick (a block of calcite). The doors of the shed and the
    fence keep the sheep in the pen. The template has no animals (see ranch). The hay, the trough and the salt lick are
    not next to the fence."""
    rng = random.Random(71)
    row = SHED_DEPTH + 1

    def ground(x, z):
        return "minecraft:coarse_dirt" if (x, z) == (W // 2, SHED_DEPTH) else GRASS

    def plants(x, z):
        return {(2, row): ("minecraft:hay_block", LOG_Y), (W - 3, row): WATER_CAULDRON,
                (W // 2, row): "minecraft:calcite"}.get((x, z))

    t = job_building(rng, "shepherd", "minecraft:shears", ground, plants, work=LOOM)
    t.set(1, 1, 2, "minecraft:white_wool")
    t.set(1, 2, 2, "minecraft:white_wool")
    t.set(2, 1, 2, "minecraft:white_wool")
    pen_gate(t, W, D)
    return t


def weaving_house():
    """The building of the weaver: a house with 2 looms (the work block of the vanilla shepherd), stacks of white wool,
    a crafting table for the beds, a barrel, and a counter with rolls of cloth (white carpets)."""
    rng = random.Random(72)
    t = job_house(rng, "weaver", "minecraft:white_carpet", planks, ((1, 3), (2, 3)))
    for x in (2, W - 3):
        t.set(x, 1, D - 2, *LOOM)
    for x, y, z in ((1, 1, D - 2), (1, 2, D - 2), (1, 1, D - 3)):
        t.set(x, y, z, "minecraft:white_wool")
    t.set(W - 2, 1, D - 2, "minecraft:crafting_table")
    t.set(W - 2, 1, D - 4, *BARREL)
    for x in (W - 3, W - 2):
        t.set(x, 1, 4, *TOP_SLAB)
        t.set(x, 2, 4, "minecraft:white_carpet")
    lantern(t, 1, 1, 2)
    lantern(t, W - 2, 1, 2)
    return t


def dye_works():
    """The building of the dyer: the shed with a loom (the work block of the vanilla shepherd) and bales of dyed wool,
    and a yard with beds of dandelions, poppies and cornflowers (the colors of the dyes) and 2 dye vats (water
    cauldrons). A path of coarse dirt goes from the back door to the back fence. The vats are not next to the fence."""
    rng = random.Random(73)
    flowers = {1: "minecraft:dandelion", 2: "minecraft:poppy", W - 3: "minecraft:poppy", W - 2: "minecraft:cornflower"}
    vats = ((3, SHED_DEPTH + 1), (W - 4, SHED_DEPTH + 1))

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == W // 2 else GRASS

    def plants(x, z):
        return WATER_CAULDRON if (x, z) in vats else flowers.get(x)

    t = job_building(rng, "dyer", "minecraft:red_dye", ground, plants, work=LOOM)
    t.set(1, 1, 2, "minecraft:red_wool")
    t.set(1, 2, 2, "minecraft:yellow_wool")
    t.set(2, 1, 2, "minecraft:blue_wool")
    return t


def paper_mill():
    """The building of the papermaker: a house with a lectern (the work block of the vanilla librarian), 2 vats of
    pulp (water cauldrons), barrels of sugar cane, a crafting table, bookshelves, and a counter with sheets of paper
    to dry (white carpets). The path from the door to the lectern stays free."""
    rng = random.Random(74)
    t = job_house(rng, "papermaker", "minecraft:paper", planks, ((1, 3, "east"), (7, 4)))
    t.set(W // 2, 1, D - 3, "minecraft:lectern", {"facing": "north", "has_book": "false", "powered": "false"})
    for x in (3, W - 4):
        t.set(x, 1, D - 2, *WATER_CAULDRON)
    for z in (D - 3, D - 4):
        t.set(1, 1, z, *BARREL)
    t.set(2, 1, D - 2, "minecraft:crafting_table")
    for x, y, z in ((W - 2, 1, D - 2), (W - 2, 2, D - 2), (W - 2, 1, D - 3)):
        t.set(x, y, z, "minecraft:bookshelf")
    for x in (1, 2):
        t.set(x, 1, 4, *TOP_SLAB)
        t.set(x, 2, 4, "minecraft:white_carpet")
    lantern(t, 1, 1, 2)
    lantern(t, W - 2, 1, 2)
    return t


# ---------------------------------------------------------------- homes that grow in place

# A house-workshop of the town and city tiers grows in place: the builders add a storey to it (see
# VillageGrowth). The piece of step 1 is the house-workshop of the job (HOME_BEDS beds). The piece of step 2 has a
# second storey and 4 beds, and the piece of step 3 has a third storey and 6 beds. Each step keeps the blocks of the
# step before at the same positions, except the roof and the blocks of the new stairs: the job sign, the house sign,
# the work blocks, the beds and the door do not move. Thus the builders add blocks and remove few. The pieces of the
# steps have the size of the plot of the step 1 piece and a greater height.
#
# The new storey has the walls of the storey below and the roof of the house on it (see add_storey). A stair in the
# storey below goes up to the new floor, through a hole in it. The style of the house gives the height of a storey
# (see StoreyStyle).
# The beds of each step of a home.
STEP_BEDS = {1: HOME_BEDS, 2: 2 * HOME_BEDS, 3: 3 * HOME_BEDS}
# The work blocks of the reach check (TemplateReach in the GameTests): the job sites of the vanilla professions, the
# containers and the work tables, the cauldrons and the anvils.
REACH_WORK_BLOCKS = {f"minecraft:{name}" for name in (
    "barrel", "chest", "trapped_chest", "crafting_table", "furnace", "blast_furnace", "smoker", "smithing_table",
    "grindstone", "loom", "lectern", "fletching_table", "stonecutter", "composter", "cartography_table",
    "brewing_stand", "cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "anvil", "chipped_anvil",
    "damaged_anvil")}
# A step between 2 positions of the reach walk: the 4 sides, as (dx, dz) and the facing of a stair that goes up there.
STEP_SIDES = (("north", 0, -1), ("south", 0, 1), ("west", -1, 0), ("east", 1, 0))


# The stairs and the positions of a bed that the search of a new storey tries at most: the search stays short, and the
# first positions along the walls fit in most homes.
STAIR_TRIALS = 400
BED_TRIALS = 8


@dataclasses.dataclass(frozen=True)
class StoreyStyle:
    """The storeys of the homes of a style.

    - wall_top: the layer of the top of the walls of the ground floor.
    - storey: the height of a storey. The floor of the new storey is at the layer storey (step 2) or 2 * storey
      (step 3), and its walls are the walls of the ground floor, storey blocks higher.
    - roof_at_floor: true for a flat roof on the walls: the roof is the floor of the new storey, and a copy of the roof
      goes on the new storey. Else the roof stands above the floor of the new storey, and goes up with the storey.
    - floor: the block of a new floor. stair, support: the block of a step of the stair, and the block under it.
    - porch: the block of a row over the porch at step 2, or None.
    """
    wall_top: int
    storey: int
    roof_at_floor: bool
    floor: str
    stair: str
    support: str
    porch: tuple | None


SLAB_ROW = ("minecraft:dark_oak_slab", {"type": "bottom", "waterlogged": "false"})
# The storeys of the base style (see house_shell): a beam at the top of the walls holds the floor of the new storey.
BASE_STOREY = StoreyStyle(4, 4, False, "minecraft:spruce_planks", "minecraft:spruce_stairs", "minecraft:spruce_planks",
                          SLAB_ROW)


def walk(state):
    """How a villager walks through a block or on it, as BlockKinds.walk: "through" a block with no collision, a
    carpet or a wooden door, "on" a block with a top of 1 block or lower, else "blocked"."""
    name, props = state
    boxes = collision(name, props)
    if not boxes or name.endswith("_carpet") or name.endswith("_door") and name != "minecraft:iron_door":
        return "through"
    return "blocked" if max(b[4] for b in boxes) > 16 else "on"


def reach_free(t, pos):
    """A villager walks through the block at the position: not a work block, not a bed (see TemplateReach.free)."""
    name, props = state_at(t, *pos)
    return name not in REACH_WORK_BLOCKS and not name.endswith("_bed") and walk((name, props)) == "through"


def unreachable(t):
    """The work blocks and the bed heads of a template that a walk from the front row (z = 0, at the height of the
    connector, y = 1) does not reach, as TemplateReach.unreachable in the GameTests (see reach)."""
    reached = reach(t)
    missing = []
    for (x, y, z), ((name, props), _) in t.blocks.items():
        head = name.endswith("_bed") and dict(props).get("part") == "head"
        if (name in REACH_WORK_BLOCKS or head) and not near(reached, x, y, z):
            missing.append(f"{name} {x} {y} {z}")
    return sorted(missing)


def near(reached, x, y, z):
    """True if a reached position is next to the block, with the feet or the head at the layer of the block."""
    return any((x + dx, y, z + dz) in reached or (x + dx, y - 1, z + dz) in reached for _, dx, dz in STEP_SIDES)


def reach(t):
    """The positions of a template that a walk from the front row reaches. A step goes 1 block to a side, at the same
    level or 1 block higher or lower. A step 1 block higher needs a free block 2 blocks above the start."""
    sx, sy, sz = t.size

    def standable(p):
        x, y, z = p
        return (0 <= x < sx and 0 <= z < sz and reach_free(t, p) and reach_free(t, (x, y + 1, z))
                and walk(state_at(t, x, y - 1, z)) == "on")

    reached = set()
    todo = [(x, 1, 0) for x in range(sx) if reach_free(t, (x, 1, 0)) and reach_free(t, (x, 2, 0))]
    reached.update(todo)
    while todo:
        x, y, z = todo.pop()
        for _, dx, dz in STEP_SIDES:
            for dy in (-1, 0, 1):
                nxt = (x + dx, y + dy, z + dz)
                if nxt not in reached and (dy <= 0 or reach_free(t, (x, y + 2, z))) and standable(nxt):
                    reached.add(nxt)
                    todo.append(nxt)
    return reached


def bed_count(t):
    return sum(1 for (name, props), _ in t.blocks.values()
               if name.endswith("_bed") and dict(props).get("part") == "head")


def shell_depth(t, style):
    """The depth of the house of a house-workshop: the back wall is the last row with a full top of the walls. The
    yard behind a shed has no wall there."""
    width = t.size[0]
    rows = [z for z in range(1, t.size[2])
            if all(state_at(t, x, style.wall_top, z)[0] != "minecraft:air" for x in range(width))]
    return max(rows) + 1


def is_wall_block(name):
    """True for a block of a wall that the new storey copies: not air, not a door, not a work block."""
    return name != "minecraft:air" and "door" not in name and name not in REACH_WORK_BLOCKS


def stair_runs(t, style, base, depth, holes):
    """The stairs that go up from a storey to the new floor at the layer base: storey - 1 cells, each a side of the
    one before, then a cell of the new floor. t is the piece before the new storey. Each cell of the stair is free
    from the floor of the storey up to the new floor, and stands on the floor of the storey. The runs along the walls
    come first."""
    width = t.size[0]
    floor = base - style.storey
    count = style.storey - 1
    cells = [(x, z) for z in range(2, depth - 1) for x in range(1, width - 1)]

    def free(x, z):
        if (x, z) in holes or walk(state_at(t, x, floor, z)) != "on":
            return False
        if any(state_at(t, x, y, z)[0] != "minecraft:air" for y in range(floor + 1, base)):
            return False
        top = state_at(t, x, base, z)[0]
        return top == "minecraft:air" or top == style.floor

    def walls(x, z):
        return (x in (1, width - 2)) + (z in (2, depth - 2))

    runs = []

    def extend(run, facings):
        if len(run) == count + 1:
            land = run[-1]
            if land in cells and land not in holes:
                runs.append((-sum(walls(*c) for c in run[:-1]), tuple(run[:-1]), tuple(facings), land))
            return
        for facing, dx, dz in STEP_SIDES:
            nxt = (run[-1][0] + dx, run[-1][1] + dz)
            if nxt in run:
                continue
            if len(run) < count and not (nxt in cells and free(*nxt)):
                continue
            extend(run + [nxt], facings + [facing])

    for c1 in cells:
        if free(*c1):
            extend([c1], [])
    runs.sort()
    return [run[1:] for run in runs]


def add_storey(t, step, style):
    """The piece of a step of a home: the piece of the step before with a new storey on it (see StoreyStyle). The
    roof of the step before goes up on the new storey. The walls of the new storey are the walls of the ground floor,
    with a wall block in the place of a door, an opening or a work block. A chimney goes up through the new storey to
    the new roof. A stair in the storey below goes up to the new floor, and HOME_BEDS beds stand on it. The stair and
    the beds go to the first positions where a walk from the porch reaches each work block and each bed (see
    unreachable)."""
    sx, sy, sz = t.size
    depth = shell_depth(t, style)
    base = style.storey * (step - 1)
    below = base - style.storey
    roof_from = base if style.roof_at_floor else base + 1
    out = Template(sx, sy + style.storey, sz)
    # The chimneys: a column of blocks in the house through the floor of the new storey.
    chimneys = {}
    for z in range(2, depth - 1):
        for x in range(1, sx - 1):
            here, above = state_at(t, x, base, z)[0], state_at(t, x, base + 1, z)[0]
            if here != "minecraft:air" and above != "minecraft:air":
                top = max(y for y in range(base, sy) if state_at(t, x, y, z)[0] != "minecraft:air")
                chimneys[(x, z)] = (t.blocks[(x, base + 1, z)], top)
    for pos, entry in t.blocks.items():
        if pos[1] <= base or pos[2] >= depth:
            out.blocks[pos] = entry
    out.fixed = {pos for pos in t.fixed if pos[1] <= base or pos[2] >= depth}
    out.fill(0, base + 1, 0, sx - 1, sy + style.storey - 1, depth - 1, "minecraft:air")
    # The roof goes up with the new storey.
    for (x, y, z), entry in t.blocks.items():
        if y >= roof_from and z < depth and (x, z) not in chimneys and entry[0][0] != "minecraft:air":
            if z > 0 or y > style.wall_top:
                out.blocks[(x, y + style.storey, z)] = entry
    if step == 2 and style.porch:
        for x in range(sx):
            out.set(x, style.wall_top + 1, 0, *style.porch)
    # The floor of the new storey.
    for z in range(2, depth - 1):
        for x in range(1, sx - 1):
            if state_at(out, x, base, z)[0] == "minecraft:air":
                out.set(x, base, z, style.floor)
    # The walls of the new storey: the walls of the ground floor. A door, an opening or a work block gets the block of
    # its row, and the first row gets the blocks of the second row.
    for x in range(sx):
        for z in range(1, depth):
            if not (x in (0, sx - 1) or z in (1, depth - 1)):
                continue
            for y in range(1, style.wall_top + 1):
                source = y + 1 if y == 1 and style.wall_top > 2 else y
                name = state_at(t, x, y, z)[0]
                corner = x in (0, sx - 1) and z in (1, depth - 1)
                entry = t.blocks.get((x, y if corner else source, z))
                if entry is None or not is_wall_block(entry[0][0]) or not is_wall_block(name):
                    entry = row_block(t, x, source, z, sx, depth)
                out.blocks[(x, base + y, z)] = entry
    # The chimneys go up through the new storey and the new roof.
    for (x, z), (entry, top) in chimneys.items():
        for y in range(base + 1, top + style.storey + 1):
            out.blocks[(x, y, z)] = entry
    taken = set(chimneys)
    for run, facings, land in stair_runs(t, style, base, depth, taken)[:STAIR_TRIALS]:
        trial = Template(*out.size)
        trial.blocks = dict(out.blocks)
        for height, (cell, facing) in enumerate(zip(run, facings), start=1):
            for y in range(below + 1, below + height):
                trial.set(cell[0], y, cell[1], style.support)
            trial.set(cell[0], below + height, cell[1], style.stair, stairs(facing))
            trial.set(cell[0], base, cell[1], "minecraft:air")
        # In a low storey, the head of a villager that steps onto the stair goes through the new floor: the cell
        # behind the first step also gets a hole.
        entry = {side: (dx, dz) for side, dx, dz in STEP_SIDES}[facings[0]]
        behind = (run[0][0] - entry[0], run[0][1] - entry[1])
        if below + 3 >= base:
            if behind in taken or state_at(trial, behind[0], base, behind[1])[0] != style.floor:
                continue
            trial.set(behind[0], base, behind[1], "minecraft:air")
        if unreachable(trial):
            continue
        if place_beds(trial, base, depth, taken | set(run) | {land, behind}) is None:
            continue
        light_storey(trial, base, depth)
        trial.fixed = set(out.fixed)
        return trial
    raise ValueError(f"no stair and no beds for the step {step}")


def row_block(t, x, y, z, width, depth):
    """The most common wall block of the row of a wall at a layer: the block of a wall in the place of a door or an
    opening."""
    if z in (1, depth - 1) and x not in (0, width - 1):
        row = [(i, y, z) for i in range(1, width - 1)]
    else:
        row = [(x, y, i) for i in range(2, depth - 1)]
    counts = {}
    for pos in row:
        entry = t.blocks.get(pos)
        if entry and is_wall_block(entry[0][0]) and "pane" not in entry[0][0] and "bars" not in entry[0][0]:
            counts[entry] = counts.get(entry, 0) + 1
    if not counts:
        return (("minecraft:spruce_planks", ()), None)
    return max(sorted(counts, key=repr), key=counts.get)


def place_beds(t, base, depth, taken):
    """Places HOME_BEDS beds on the floor at the layer base, at the first positions where the walk from the porch
    reaches each bed head and each work block. Returns the template, or None when no position fits."""
    y = base + 1
    width = t.size[0]
    cells = [(x, z) for z in range(depth - 2, 1, -1) for x in range(1, width - 1)]
    for _ in range(HOME_BEDS):
        placed = False
        trials = 0
        reached = reach(t)
        for foot in cells:
            if trials >= BED_TRIALS:
                break
            for facing, dx, dz in STEP_SIDES:
                head = (foot[0] + dx, foot[1] + dz)
                if head not in cells or foot in taken or head in taken:
                    continue
                if any(state_at(t, c[0], y, c[1])[0] != "minecraft:air"
                       or state_at(t, c[0], y + 1, c[1])[0] != "minecraft:air"
                       or walk(state_at(t, c[0], base, c[1])) != "on" for c in (foot, head)):
                    continue
                if not near(reached, head[0], y, head[1]):
                    continue
                t.set(foot[0], y, foot[1], f"minecraft:{HOME_BED_COLOR}_bed",
                      {"facing": facing, "part": "foot", "occupied": "false"})
                t.set(head[0], y, head[1], f"minecraft:{HOME_BED_COLOR}_bed",
                      {"facing": facing, "part": "head", "occupied": "false"})
                trials += 1
                if not unreachable(t):
                    taken |= {foot, head}
                    placed = True
                    break
                t.set(foot[0], y, foot[1], "minecraft:air")
                t.set(head[0], y, head[1], "minecraft:air")
            if placed:
                break
        if not placed:
            return None
    return t


def light_storey(t, base, depth):
    """A wall torch on each side wall of the new storey, at the height of the head: no monster spawns in it."""
    y = base + 2
    width = t.size[0]
    for x, facing in ((1, "east"), (width - 2, "west")):
        for z in (depth // 2, depth // 2 - 1, depth // 2 + 1):
            if state_at(t, x, y, z)[0] == "minecraft:air" and state_at(t, x, y - 1, z)[0] == "minecraft:air":
                t.set(x, y, z, *wall_torch(facing))
                break


@functools.cache
def home_step(culture, job, step):
    """The piece of a step of the house-workshop of a job in the style of a culture (None for the base style), or
    None when the home of the job does not grow in place: no stair and no beds fit in it."""
    jobs, style = STEP_STYLES[culture]
    if step == 1:
        return jobs[job]()
    before = home_step(culture, job, step - 1)
    if before is None:
        return None
    try:
        return add_storey(before, step, style)
    except ValueError:
        return None


def check_home_step(culture, job, step):
    """Stops the script when the piece of a step of a home changes a block of the step before below the new storey,
    or has not the beds of its step, or has a work block or a bed with no walk from the porch."""
    style = STEP_STYLES[culture][1]
    before, grown = home_step(culture, job, step - 1), home_step(culture, job, step)
    base = style.storey * (step - 1)
    name = f"{culture or 'base'} job_{job}_step{step}"
    for pos, entry in before.blocks.items():
        if pos[1] > base or entry[0][0] == "minecraft:air" or grown.blocks.get(pos) == entry:
            continue
        # Only the stair changes blocks of the step before: its steps and the hole in the new floor.
        now = grown.blocks.get(pos, (("minecraft:air", ()), None))[0][0]
        if now not in (style.stair, style.support, "minecraft:air") or pos[1] <= base - style.storey:
            raise SystemExit(f"{name} changes the block {entry[0][0]} at {pos}")
    if bed_count(grown) != STEP_BEDS[step]:
        raise SystemExit(f"{name} has {bed_count(grown)} beds, not {STEP_BEDS[step]}")
    problems = unreachable(grown)
    if problems:
        raise SystemExit(f"{name}: no walk to {problems}")


# ---------------------------------------------------------------- house-workshops of the Valeholm style

# A Norse house-workshop is a deep long house of LW x LD blocks: it fits each plot of a Norse street, as a long house.
# The jobs of the fields, the gardens, the animals, the lumberjacks, the miner, the digger, the fletcher, the tanner,
# the shepherd and the dyer have a short long house on the street (z = 1..NORSE_SHED - 1) and a fenced yard behind it.
NORSE_SHED = 9
NORSE_YARD_Z = range(NORSE_SHED, LD - 1)
NORSE_YARD_X = range(1, LW - 1)
# The water of a Norse field, in the middle of the yard: it gives water to all the farmland of the yard.
NORSE_WATER = (LW // 2, NORSE_SHED + 3)
# The beds of a Norse long house: on the 2 side walls, behind the hearth, with the heads to the back wall.
NORSE_BEDS = ((1, 10), (LW - 2, 10))
COMPOSTER = ("minecraft:composter", {"level": "0"})


def norse_yard_workshop(rng, job, icon, ground, plants, work=COMPOSTER):
    """The house-workshop of a job with a yard in the Valeholm style (the beekeeper, the fisher, and the jobs with a
    yard of style_jobs: the farmer, the gardeners, the forager, the rancher, the lumberjacks, the miner, the digger,
    the fletcher, the tanner, the shepherd and the dyer): a short long house on the street and a fenced yard behind
    it.

    - The house: the long house shell (see long_house_shell) with no hearth, a door on the street and a back door to
      the yard. The job sign stands on the porch next to the west porch post, and the house sign next to the east
      porch post.
    - The work blocks: the work block of the vanilla profession of the job in the west back corner, and a barrel next
      to the door.
    - The home: 2 beds side by side in the east half, with the heads to the east. The path from the door to the back
      door (x = 4) goes along the feet of the beds.
    - The yard: ground(x, z) gives the ground block of each position, and plants(x, z) gives the block above it, or
      None. A fence goes around the yard, with a lantern on each back corner.

    The caller adds the blocks of the job.
    """
    t = long_house_shell(rng, "house", cobble, depth=NORSE_SHED, hearth=False)
    mid = LW // 2
    back = NORSE_SHED - 1
    for y, half in ((1, "lower"), (2, "upper")):
        t.set(mid, y, back, "minecraft:spruce_door",
              {"facing": "south", "half": half, "hinge": "left", "open": "false", "powered": "false"})
    t.set(mid, 3, back, "minecraft:stripped_spruce_log", {"axis": "x"})
    t.set(1, 1, back - 1, *work)
    t.set(mid - 1, 1, 2, *BARREL)
    lantern(t, LW - 2, 1, 2)
    home_beds(t, (mid + 1, back - 3), (mid + 1, back - 2), facing="east")
    job_sign(t, 1, 1, 0, job, icon)
    for x in range(LW):
        for z in range(NORSE_SHED, LD):
            if x in NORSE_YARD_X and z in NORSE_YARD_Z:
                t.set(x, 0, z, *block(ground(x, z)))
                plant = plants(x, z)
                if plant:
                    t.set(x, 1, z, *block(plant))
            else:
                t.set(x, 0, z, cobble(rng))
                t.set(x, 1, z, "minecraft:spruce_fence")
    for x in (0, LW - 1):
        lantern(t, x, 2, LD - 1)
    return t


def norse_apiary():
    """The Valeholm building of the beekeeper: 2 beehives on stands of stripped logs among the flowers, and a path of
    coarse dirt. The yard has no campfire: a campfire burns, and the heart takes it as a brazier."""
    rng = random.Random(65)
    mid, row = NORSE_WATER
    hives = ((2, row), (LW - 3, row))
    flowers = FLOWERS + ("minecraft:oxeye_daisy", "minecraft:allium")

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == mid else GRASS

    def plants(x, z):
        if (x, z) in hives:
            return "minecraft:stripped_spruce_log", LOG_Y
        if x == mid or (x + z) % 2:
            return None
        return flowers[rng.randrange(len(flowers))]

    t = norse_yard_workshop(rng, "beekeeper", "minecraft:honeycomb", ground, plants)
    for x, z in hives:
        t.set(x, 2, z, "minecraft:beehive", {"facing": "south", "honey_level": str(rng.randint(2, 5))})
    t.set(1, 1, 2, *BARREL)
    return t


def norse_fisher_hut():
    """The Valeholm building of the fisher: a house with barrels (the work block of the vanilla fisherman) and a tub,
    and a yard with a pond for the live catch. The pond is a row of waterlogged slabs, with a path of coarse dirt next
    to it: a builder stands on the path to add the water."""
    rng = random.Random(67)
    pond = range(2, LW - 2)
    row = LD - 3

    def ground(x, z):
        if z == row and x in pond:
            return WATER_SLAB
        return "minecraft:coarse_dirt" if z == row - 1 or x == LW // 2 else GRASS

    def plants(x, z):
        return BARREL if (x, z) in ((2, NORSE_SHED), (LW - 3, NORSE_SHED)) else None

    t = norse_yard_workshop(rng, "fisher", "minecraft:cod", ground, plants, work=BARREL)
    t.set(1, 1, 2, *WATER_CAULDRON)
    return t


def norse_workshop(rng, job, icon, beds=NORSE_BEDS, wide_front=False, wall_lower=cobble):
    """The house-workshop of a job in a Valeholm long house (the baker, the pastry cook, the butcher, and the jobs
    with no yard of style_jobs: the sawyers, the mason, the smelters, the blacksmith, the tinker, the carpenter, the
    weaver and the papermaker): the long house shell with the hearth (see long_house_shell), a door on the street or
    an open shop front. wall_lower gives the blocks of the lowest layer of the walls. The job sign stands on the porch
    next to the west porch post, and the house sign next to the east porch post. beds gives the foot of each bed of the
    home (see home_beds): the beds stand on the side walls behind the hearth (NORSE_BEDS). The caller adds the blocks
    of the job."""
    t = long_house_shell(rng, "house", wall_lower, wide_front=wide_front)
    home_beds(t, *beds)
    job_sign(t, 1, 1, 0, job, icon)
    return t


def norse_oven(t):
    """A brick oven on the back wall of a long house: a furnace in bricks, with a chimney of bricks through the steep
    roof. The chimney takes the place of the roof blocks, so the roof has no hole. The oven is east of the middle: the
    chimney does not touch the carved horns of the ridge."""
    x, z = LW // 2 + 1, LD - 2
    t.set(x, 1, z, "minecraft:furnace", {"facing": "north", "lit": "false"})
    for dx in (-1, 0, 1):
        t.set(x + dx, 2, z, "minecraft:bricks")
        if dx:
            t.set(x + dx, 1, z, "minecraft:bricks")
    for y in range(3, steep_roof_top(x + 1, LW) + 2):
        t.set(x + 1, y, z, "minecraft:bricks")


def norse_bakery():
    """The Valeholm building of the baker: a brick oven, a crafting table to knead the dough, barrels of flour, the
    wheat on hay bales and a cake on the counter."""
    rng = random.Random(68)
    t = norse_workshop(rng, "baker", "minecraft:bread")
    norse_oven(t)
    t.set(1, 1, LD - 2, "minecraft:crafting_table")
    for x, z in ((1, LD - 4), (2, LD - 2)):
        t.set(x, 1, z, *BARREL)
    for x, y, z in ((LW - 2, 1, LD - 2), (LW - 2, 1, LD - 3), (LW - 2, 2, LD - 3)):
        t.set(x, y, z, "minecraft:hay_block", LOG_Y)
    t.set(LW - 2, 1, 4, *TOP_SLAB)
    t.set(LW - 2, 2, 4, "minecraft:cake", {"bites": "0"})
    lantern(t, 1, 1, 2)
    return t


def norse_pastry_shop():
    """The Valeholm building of the pastry cook: an open shop front, a counter with cakes, a brick oven, pumpkins and
    barrels of sugar."""
    rng = random.Random(69)
    t = norse_workshop(rng, "pastry_cook", "minecraft:pumpkin_pie", wide_front=True)
    norse_oven(t)
    # The counter behind the open front, with a free passage in the middle.
    for x in (1, 2, LW - 3, LW - 2):
        t.set(x, 1, 3, *TOP_SLAB)
    for x in (2, LW - 3):
        t.set(x, 2, 3, "minecraft:candle_cake", {"lit": "false"})
    for x, y in ((1, 1), (2, 1), (2, 2)):
        t.set(x, y, LD - 2, "minecraft:pumpkin")
    for z in (LD - 3, LD - 4):
        t.set(LW - 2, 1, z, *BARREL)
    lantern(t, 1, 1, 5)
    return t


def norse_butchery():
    """The Valeholm building of the butcher: 2 smokers (the work block of the vanilla butcher), a block of coal, a
    chopping block, and a rack of chains on a beam across the room. The chains leave 2 blocks of space under them."""
    rng = random.Random(70)
    t = norse_workshop(rng, "butcher", "minecraft:cooked_beef")
    for x in (5, 6):
        t.set(x, 1, LD - 2, "minecraft:smoker", {"facing": "north", "lit": "false"})
    t.set(LW - 2, 1, LD - 2, "minecraft:coal_block")
    for x in range(1, LW - 1):
        t.set(x, 4, 12, "minecraft:stripped_spruce_log", {"axis": "x"})
    for x in (2, 3, 6):
        t.set(x, 3, 12, "minecraft:chain", {"axis": "y", "waterlogged": "false"})
    t.set(2, 1, 4, "minecraft:stripped_oak_log", LOG_Y)
    for z in (LD - 2, LD - 3):
        t.set(1, 1, z, *BARREL)
    t.set(LW - 2, 1, 4, *WATER_CAULDRON)
    lantern(t, 1, 1, 2)
    return t


# ---------------------------------------------------------------- house-workshops of the styles of the cultures

RAIL = ("minecraft:rail", {"shape": "north_south", "waterlogged": "false"})


@dataclasses.dataclass(frozen=True)
class SmelteryLayout:
    """The places of the blocks of a smeltery of a style (see style_smeltery). The back row is the row in front of the
    back wall.

    - furnaces: the x of the 2 blast furnaces on the back row.
    - ore_x: the x of the 2 blocks of raw ore on the back row and the row in front of it.
    - coal: (x, z) of the block of coal.
    - cauldron: (x, z) of the water cauldron. The barrel of sand of the smelter is 1 block in front of it (z - 1).
    - lanterns: (x, y, z) of each lantern after the lantern next to the door.
    """
    furnaces: tuple[int, int]
    ore_x: int
    coal: tuple[int, int]
    cauldron: tuple[int, int]
    lanterns: tuple[tuple[int, int, int], ...]


@dataclasses.dataclass(frozen=True)
class WorkshopStyle:
    """The style of the house-workshops of a culture (VALEHOLM, SUNREACH): the values that the builders of the styles
    (see style_jobs) take from the style.

    - width, depth: the size of a piece along the street and away from the street.
    - shed: the first row of the yard of a house-workshop with a yard. The house is z = 1..shed - 1.
    - water: (x, z) of the water of a field, in the middle of the yard.
    - seed: the builders add it to their seeds, so that each style has its own random blocks.
    - yard_workshop(rng, job, icon, ground, plants, work): the house and the yard of a job with a yard.
    - workshop(rng, job, icon, beds, wide_front, wall_lower): the house of a job with no yard.
    - oven(t): a brick oven with a chimney on the back wall of a house with no yard.
    - beds: the foot of each home bed of a house with no yard (see home_beds).
    - sawmill_lantern: (x, y, z) of the lantern of a sawmill after the lantern next to the door.
    - mason_cutter: (x, z) of the stonecutter of the mason. The block of stone to cut is in front of it (z - 1).
    - mason_lantern: (x, y, z) of the lantern of the mason after the lantern next to the door.
    - smeltery: the places of the blocks of a smeltery (see SmelteryLayout).
    """
    width: int
    depth: int
    shed: int
    water: tuple[int, int]
    seed: int
    yard_workshop: Callable[..., Template]
    workshop: Callable[..., Template]
    oven: Callable[[Template], None]
    beds: tuple[tuple[int, int], ...]
    sawmill_lantern: tuple[int, int, int]
    mason_cutter: tuple[int, int]
    mason_lantern: tuple[int, int, int]
    smeltery: SmelteryLayout


def style_farm(s):
    """The building of the farmer in the style s: a field of wheat, carrots, potatoes and beetroots around the water,
    and hay bales in the house."""
    rng = random.Random(s.seed + 61)
    rows = {1: "wheat", 2: "wheat", 3: "carrots", 5: "potatoes", 6: "beetroots", 7: "beetroots"}

    def plants(x, z):
        if x == s.water[0]:
            return None
        return crop(f"minecraft:{rows[x]}", rng, 3 if rows[x] == "beetroots" else 7)

    t = s.yard_workshop(rng, "farmer", "minecraft:wheat", functools.partial(field_ground, water=s.water), plants)
    for x, y in ((1, 1), (2, 1), (1, 2)):
        t.set(x, y, 2, "minecraft:hay_block", LOG_Y)
    return t


def style_garden(s):
    """The building of the gardener in the style s: 2 rows of pumpkins and melons on their stems, and sugar cane next
    to the water. The sugar cane is 1 block high, with no block next to it (see garden)."""
    rng = random.Random(s.seed + 62)
    mid, row = s.water
    stems = (row - 2, row + 2)

    def ground(x, z):
        if x == mid:
            return field_ground(x, z, s.water)
        return FARMLAND if abs(x - mid) == 2 and z in stems else GRASS

    def plants(x, z):
        side = x - mid
        if z == row and abs(side) == 1:
            return "minecraft:sugar_cane", {"age": "0"}
        if z not in stems:
            return None
        fruit = "pumpkin" if side < 0 else "melon"
        if abs(side) == 3:
            return f"minecraft:{fruit}"
        if abs(side) == 2:
            return f"minecraft:attached_{fruit}_stem", {"facing": "west" if side < 0 else "east"}
        return None

    t = s.yard_workshop(rng, "gardener", "minecraft:pumpkin", ground, plants)
    t.set(1, 1, 2, "minecraft:pumpkin")
    t.set(2, 1, 2, "minecraft:melon")
    return t


def style_jungle_garden(s):
    """The building of the gardener of the jungle in the style s: cocoa pods on 2 posts of jungle logs, 2 melons on
    their stems next to the water, and ferns along the sides of the yard."""
    rng = random.Random(s.seed + 63)
    mid, row = s.water
    posts = ((2, row), (s.width - 3, row))
    melons = {(mid, row - 1): "west", (mid, row + 1): "east"}

    def ground(x, z):
        if (x, z) == s.water:
            return WATER_SLAB
        if x == mid:
            return FARMLAND if (x, z) in melons else "minecraft:coarse_dirt"
        return GRASS

    def plants(x, z):
        if (x, z) in posts:
            return "minecraft:jungle_log", LOG_Y
        if (x, z) in melons:
            return "minecraft:attached_melon_stem", {"facing": melons[(x, z)]}
        if (x - mid, z - row) in ((-1, -1), (1, 1)):
            return "minecraft:melon"
        if abs(x - mid) == 3 and z != row:
            return "minecraft:fern" if rng.random() < 0.5 else None
        return None

    t = s.yard_workshop(rng, "gardener_jungle", "minecraft:cocoa_beans", ground, plants)
    # The posts: 3 jungle logs high, with a cocoa pod on each side. A pod faces its log.
    for px, pz in posts:
        for y in (2, 3):
            t.set(px, y, pz, "minecraft:jungle_log", LOG_Y)
        for side, (dx, dz) in SIDES.items():
            t.set(px + dx, 2, pz + dz, "minecraft:cocoa", {"age": str(rng.randint(1, 2)), "facing": TOWARD[side]})
    t.set(1, 1, 2, "minecraft:melon")
    return t


def style_forager_hut(s):
    """The building of the forager in the style s: mushrooms on podzol, wild flowers on grass (see forage_plants),
    and the finds in a barrel."""
    rng = random.Random(s.seed + 64)
    t = s.yard_workshop(rng, "forager", "minecraft:red_mushroom", functools.partial(forage_ground, width=s.width),
                        functools.partial(forage_plants, width=s.width))
    t.set(1, 1, 2, *BARREL)
    t.set(2, 1, 2, "minecraft:moss_block")
    return t


def style_ranch(s):
    """The building of the rancher in the style s: a house with a smoker (the work block of the vanilla butcher) and a
    pen behind it, with hay and a water trough. The doors of the house and the edge of the yard keep the animals in
    the pen. The template has no animals (see ranch)."""
    rng = random.Random(s.seed + 66)
    row = s.shed + 2
    # A hay bale in the first row of the pen, and a hay bale in the back row of a deep pen. The hay and the trough are
    # not next to the edge of the yard: a fence or a wall connects to a full block.
    hay = {(2, row)} | ({(2, s.depth - 3)} if s.depth - 3 > row + 1 else set())

    def ground(x, z):
        return "minecraft:coarse_dirt" if (x, z) == (s.width // 2, s.shed) else GRASS

    def plants(x, z):
        if (x, z) in hay:
            return "minecraft:hay_block", LOG_Y
        if (x, z) == (s.width - 3, row):
            return WATER_CAULDRON
        return None

    t = s.yard_workshop(rng, "rancher", "minecraft:lead", ground, plants,
                        work=("minecraft:smoker", {"facing": "east", "lit": "false"}))
    t.set(1, 1, 2, "minecraft:hay_block", LOG_Y)
    pen_gate(t, s.width, s.depth)
    return t


def style_lumber_yard(s, job, wood):
    """The building of a lumberjack in the style s: a house with a grindstone for the axes and a potted sapling, and a
    yard with a pile of logs of the wood of the job along the west side, a chopping block and the split wood (see
    lumber_yard).

    All the lumberjacks have this shape. Only the wood of the logs, of the chopping block and of the sapling changes.
    """
    log = f"minecraft:{wood}_log"
    sapling = POTTED.get(wood, f"minecraft:potted_{wood}_sapling")
    rng = random.Random(s.seed + 71)

    def ground(x, z):
        return GRASS if x in (1, 2, s.width - 2) else "minecraft:coarse_dirt"

    t = s.yard_workshop(rng, job, log, ground, lambda x, z: None,
                        work=("minecraft:grindstone", {"face": "floor", "facing": "north"}))
    t.set(1, 1, 2, sapling)
    # The west pile: 2 rows of logs along the side of the yard, the row on the side 2 logs high.
    for z in range(s.shed + 1, s.depth - 2):
        for x, top in ((1, 2), (2, 1)):
            for y in range(1, top + 1):
                job_material(t, x, y, z, log, {"axis": "z"})
    # The chopping block: a log on its end. The split wood: stripped logs in the east back corner.
    job_material(t, s.width - 3, 1, s.water[1], log, LOG_Y)
    for x, y in ((s.width - 3, 1), (s.width - 2, 1), (s.width - 2, 2)):
        job_material(t, x, y, s.depth - 2, f"minecraft:stripped_{wood}_log", {"axis": "x"})
    t.set(s.width - 2, 1, s.shed, sapling)
    return t


def style_sawmill(s, job, wood):
    """The building of a sawyer in the style s: a house with a saw bench across the room behind the beds (a
    stonecutter between the logs and the slabs), a pile of logs and a stack of planks on the back wall, a crafting
    table and a barrel (see sawmill).

    All the sawyers have this shape. Only the wood of the logs, the planks and the slabs changes.
    """
    rng = random.Random(s.seed + 72)
    t = s.workshop(rng, job, f"minecraft:{wood}_planks", s.beds)
    w, back = s.width, s.depth - 2
    bench = s.depth - 4
    for x in (2, 3):
        job_material(t, x, 1, bench, f"minecraft:{wood}_log", {"axis": "x"})
    t.set(4, 1, bench, "minecraft:stonecutter", {"facing": "north"})
    for x in (5, 6):
        job_material(t, x, 1, bench, f"minecraft:{wood}_slab", {"type": "bottom", "waterlogged": "false"})
    # The logs to saw (west) and the planks (east) on the back wall. The passages at the ends of the bench stay free.
    for x, y in ((1, 1), (2, 1), (3, 1), (1, 2), (2, 2)):
        job_material(t, x, y, back, f"minecraft:{wood}_log", {"axis": "x"})
    for x, y, z in ((w - 3, 1, back), (w - 2, 1, back), (w - 2, 2, back), (w - 2, 1, back - 1)):
        job_material(t, x, y, z, f"minecraft:{wood}_planks")
    t.set(1, 1, 3, "minecraft:crafting_table")
    t.set(w - 2, 1, 2, *BARREL)
    lantern(t, 1, 1, 2)
    lantern(t, *s.sawmill_lantern)
    return t


def style_mine(s):
    """The building of the miner in the style s: a house with a smithing table (the work block of the vanilla
    toolsmith), and a yard with a rock face at the back (see mine). A timber frame in the rock face makes the mouth of
    the mine, with a lantern under the beam. A rail goes from the back door to the mouth. A pile of cobblestone stands
    next to the rail. The cobblestone of the pile stays cobblestone in all the variants."""
    rng = random.Random(s.seed + 73)
    w = s.width
    mid = w // 2
    face = s.depth - 2

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == mid else "minecraft:gravel"

    def plants(x, z):
        return RAIL if x == mid else None

    t = s.yard_workshop(rng, "miner", "minecraft:iron_pickaxe", ground, plants,
                        work=("minecraft:smithing_table", None))
    # The rock face: 3 blocks high, with stone at its foot on the 2 sides.
    rock = {1: ("minecraft:stone", "minecraft:andesite", "minecraft:stone"),
            2: ("minecraft:coal_ore", "minecraft:stone", "minecraft:andesite"),
            w - 3: ("minecraft:iron_ore", "minecraft:stone", "minecraft:stone"),
            w - 2: ("minecraft:andesite", "minecraft:copper_ore", "minecraft:stone")}
    for x, column in rock.items():
        for y, name in enumerate(column, 1):
            t.set(x, y, face, name)
    for x in (1, w - 2):
        t.set(x, 1, face - 1, "minecraft:stone")
    # The mouth: 2 posts and a beam in the rock face, and the rock behind it in place of the back edge of the yard.
    for x in (mid - 1, mid + 1):
        for y in (1, 2):
            t.set(x, y, face, "minecraft:spruce_log", LOG_Y)
    for x in (mid - 1, mid, mid + 1):
        t.set(x, 3, face, "minecraft:spruce_log", {"axis": "x"})
        for y in (1, 2, 3):
            t.set(x, y, s.depth - 1, "minecraft:stone")
    t.set(mid, 2, face, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})
    for x, z in ((1, s.shed), (2, s.shed), (1, s.shed + 1)):
        job_material(t, x, 1, z, "minecraft:cobblestone")
    t.set(1, 1, 2, *BARREL)
    return t


def style_dig_yard(s):
    """The building of the digger in the style s: a house with a stonecutter (the work block of the vanilla mason),
    and a yard with heaps of sand, gravel, clay and dirt along the sides (see dig_yard). A heap of clay and a heap of
    sand stand in the row in front of the middle of the yard. A path of coarse dirt goes from the back door to the back
    of the yard."""
    rng = random.Random(s.seed + 74)
    w, back = s.width, s.depth - 2
    front = s.water[1] - 1
    heaps = {(1, back): "sand", (2, back): "sand", (1, back - 1): "sand", (3, back): "gravel",
             (w - 4, back): "clay", (w - 3, back): "dirt", (w - 2, back): "dirt", (w - 2, back - 1): "gravel",
             (1, front): "clay", (w - 2, front): "sand"}

    def plants(x, z):
        return f"minecraft:{heaps[(x, z)]}" if (x, z) in heaps else None

    t = s.yard_workshop(rng, "digger", "minecraft:iron_shovel", lambda x, z: "minecraft:coarse_dirt", plants,
                        work=("minecraft:stonecutter", {"facing": "east"}))
    t.set(1, 2, back, "minecraft:sand")
    t.set(w - 2, 2, back, "minecraft:dirt")
    t.set(1, 1, 2, *BARREL)
    return t


def style_mason(s):
    """The building of the mason in the style s: a house with a stonecutter (the work block of the vanilla mason) and
    a block of stone to cut in front of it, a brick kiln with a chimney (the oven of the style), and stacks of stone
    bricks, bricks, smooth stone and terracotta on the back wall (see mason_workshop). In a long house, the lowest layer
    of the walls is stone bricks. The stacks of stone bricks stay stone bricks in all the variants."""
    rng = random.Random(s.seed + 75)
    t = s.workshop(rng, "mason", "minecraft:stone_bricks", s.beds, wall_lower=stone_bricks)
    s.oven(t)
    w, back = s.width, s.depth - 2
    for x, y, z in ((1, 1, back), (1, 2, back), (2, 1, back), (1, 1, back - 1)):
        job_material(t, x, y, z, "minecraft:stone_bricks")
    for y in (1, 2):
        t.set(w - 2, y, back, "minecraft:bricks")
    t.set(w - 2, 1, back - 1, "minecraft:smooth_stone")
    t.set(w - 2, 1, back - 2, "minecraft:terracotta")
    cx, cz = s.mason_cutter
    t.set(cx, 1, cz, "minecraft:stonecutter", {"facing": "east"})
    t.set(cx, 1, cz - 1, "minecraft:stone")
    t.set(w - 2, 1, 3, *BARREL)
    lantern(t, 1, 1, 2)
    lantern(t, *s.mason_lantern)
    return t


def style_smeltery(s, job):
    """The building of a smelter in the style s: a house with 2 blast furnaces (the work block of the vanilla armorer)
    next to a furnace in a brick oven with a chimney (the oven of the style), the raw ore and a block of coal, a water
    cauldron to cool the metal, and the ingots on a counter (see smeltery). In a long house, the lowest layer of the
    walls is bricks. The smelter also makes glass: its building has a barrel of sand in front of the cauldron.

    All the smelters have this shape. Only the ore and the ingots change.
    """
    icon, ores, plate = SMELTERS[job]
    rng = random.Random(s.seed + 76)
    layout = s.smeltery
    t = s.workshop(rng, job, icon, s.beds, wall_lower=lambda r: "minecraft:bricks")
    s.oven(t)
    back = s.depth - 2
    for x in layout.furnaces:
        t.set(x, 1, back, "minecraft:blast_furnace", {"facing": "north", "lit": "false"})
    for z, ore in zip((back, back - 1), ores):
        t.set(layout.ore_x, 1, z, ore)
    t.set(layout.coal[0], 1, layout.coal[1], "minecraft:coal_block")
    cx, cz = layout.cauldron
    t.set(cx, 1, cz, *WATER_CAULDRON)
    if job == "smelter":
        t.set(cx, 1, cz - 1, *BARREL)
    for x in (s.width - 3, s.width - 2):
        t.set(x, 1, 3, *TOP_SLAB)
        t.set(x, 2, 3, plate, {"power": "0"})
    lantern(t, 1, 1, 2)
    for pos in layout.lanterns:
        lantern(t, *pos)
    return t


def style_smithy(s):
    """The building of the blacksmith in the style s: a forge (see forge_tools) as a house-workshop, with an open
    front."""
    rng = random.Random(s.seed + 77)
    t = s.workshop(rng, *BLACKSMITH, s.beds, wide_front=True)
    forge_tools(t, s.width, s.depth)
    return t


def style_tinker(s):
    """The building of the tinker in the style s: a house with a smithing table (the work block of the vanilla
    toolsmith), blocks of iron and copper, a crafting table, a barrel, a short track of rails, and a counter with a
    lantern and a lightning rod (the goods of the job). See tinker_workshop."""
    rng = random.Random(s.seed + 78)
    t = s.workshop(rng, "tinker", "minecraft:bucket", s.beds)
    w, back = s.width, s.depth - 2
    t.set(w - 3, 1, back, "minecraft:smithing_table")
    t.set(1, 1, back, "minecraft:iron_block")
    t.set(1, 2, back, "minecraft:waxed_copper_block")
    t.set(1, 1, back - 1, "minecraft:waxed_copper_block")
    t.set(2, 1, back, "minecraft:crafting_table")
    t.set(w - 2, 1, back, *BARREL)
    # The track: 3 rails at most along the west wall. It stops in front of the west bed.
    for z in range(3, min(6, s.beds[0][1])):
        t.set(1, 1, z, *RAIL)
    for x in (w - 3, w - 2):
        t.set(x, 1, 4, *TOP_SLAB)
    lantern(t, w - 3, 2, 4)
    t.set(w - 2, 2, 4, "minecraft:lightning_rod", {"facing": "up", "powered": "false", "waterlogged": "false"})
    lantern(t, 1, 1, 2)
    lantern(t, w - 2, 1, back - 2)
    return t


def style_fletcher_range(s):
    """The building of the fletcher in the style s: a house with a fletching table (the work block of the vanilla
    fletcher), and a yard with 2 targets on hay bales at the back and a heap of gravel (the flint of the arrows). A path
    of coarse dirt goes from the back door to the back of the yard. The targets are not next to the edge of the yard
    (see fletcher_range)."""
    rng = random.Random(s.seed + 79)
    targets = (2, s.width - 3)
    row = s.depth - 3

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == s.width // 2 else GRASS

    def plants(x, z):
        if x in targets and z == row:
            return "minecraft:hay_block", LOG_Y
        return "minecraft:gravel" if (x, z) == (1, s.depth - 2) else None

    t = s.yard_workshop(rng, "fletcher", "minecraft:arrow", ground, plants, work=("minecraft:fletching_table", None))
    for x in targets:
        t.set(x, 2, row, "minecraft:target", {"power": "0"})
    t.set(1, 1, 2, *BARREL)
    return t


def style_carpenter(s):
    """The building of the carpenter in the style s: a house with 2 crafting tables and a bench of oak slabs near the
    door, a stack of oak planks, 2 chests and a barrel on the back wall (see carpenter_workshop). The oak planks and the
    oak slabs stay oak in all the variants."""
    rng = random.Random(s.seed + 80)
    t = s.workshop(rng, "carpenter", "minecraft:crafting_table", s.beds)
    w, back = s.width, s.depth - 2
    for x in (2, w - 3):
        t.set(x, 1, 4, "minecraft:crafting_table")
    for x in (3, w - 2):
        job_material(t, x, 1, 4, "minecraft:oak_slab", {"type": "top", "waterlogged": "false"})
    for x, y, z in ((w - 2, 1, back), (w - 2, 2, back), (w - 3, 1, back), (w - 2, 1, back - 1)):
        job_material(t, x, y, z, "minecraft:oak_planks")
    for x in (2, 3):
        t.set(x, 1, back, "minecraft:chest", {"facing": "north", "type": "single", "waterlogged": "false"})
    t.set(1, 1, back, *BARREL)
    lantern(t, 1, 1, 2)
    lantern(t, w - 2, 1, 2)
    return t


def style_tannery(s):
    """The building of the tanner in the style s: a house with a cauldron (the work block of the vanilla
    leatherworker), and a yard with 2 vats of water (water cauldrons) and a rack of fences with hides (brown carpets)
    on it. A path of coarse dirt goes from the back door to the back of the yard. The vats and the rack are not next to
    the edge of the yard (see tannery): the rack has 3 fences, or 2 fences in a short yard."""
    rng = random.Random(s.seed + 81)
    vats = ((2, s.shed + 2), (3, s.shed + 2))
    rack = tuple((s.width - 3, z) for z in range(s.shed + 2, min(s.shed + 5, s.depth - 2)))

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == s.width // 2 else GRASS

    def plants(x, z):
        if (x, z) in vats:
            return WATER_CAULDRON
        return "minecraft:spruce_fence" if (x, z) in rack else None

    t = s.yard_workshop(rng, "tanner", "minecraft:leather", ground, plants, work=("minecraft:cauldron", None))
    for x, z in rack:
        t.set(x, 2, z, "minecraft:brown_carpet")
    t.set(1, 1, 2, *BARREL)
    return t


def style_sheepfold(s):
    """The building of the shepherd in the style s: a house with a loom (the work block of the vanilla shepherd) and
    bales of wool, and a pen behind it with 2 hay bales, a water trough and a salt lick (a block of calcite) in the
    first row and in the back row of the pen. The doors of the house and the edge of the yard keep the sheep in the
    pen. The template has no animals (see ranch). The hay, the trough and the salt lick are not next to the edge of
    the yard."""
    rng = random.Random(s.seed + 82)
    row, last = s.shed + 2, s.depth - 3
    pen = {(2, row): ("minecraft:hay_block", LOG_Y), (2, last): ("minecraft:hay_block", LOG_Y),
           (s.width - 3, row): WATER_CAULDRON, (s.width - 3, last): "minecraft:calcite"}

    def ground(x, z):
        return "minecraft:coarse_dirt" if (x, z) == (s.width // 2, s.shed) else GRASS

    t = s.yard_workshop(rng, "shepherd", "minecraft:shears", ground, lambda x, z: pen.get((x, z)), work=LOOM)
    t.set(1, 1, 2, "minecraft:white_wool")
    t.set(1, 2, 2, "minecraft:white_wool")
    t.set(2, 1, 2, "minecraft:white_wool")
    pen_gate(t, s.width, s.depth)
    return t


def style_weaving_house(s):
    """The building of the weaver in the style s: a house with 2 looms (the work block of the vanilla shepherd) and
    stacks of white wool on the back wall, a crafting table for the beds, a barrel, and a counter with rolls of cloth
    (white carpets) near the door (see weaving_house)."""
    rng = random.Random(s.seed + 83)
    t = s.workshop(rng, "weaver", "minecraft:white_carpet", s.beds)
    w, back = s.width, s.depth - 2
    for x in (2, w - 3):
        t.set(x, 1, back, *LOOM)
    for x, y, z in ((1, 1, back), (1, 2, back), (1, 1, back - 1)):
        t.set(x, y, z, "minecraft:white_wool")
    t.set(w - 2, 1, back, "minecraft:crafting_table")
    t.set(w - 2, 1, back - 2, *BARREL)
    for x in (w - 3, w - 2):
        t.set(x, 1, 4, *TOP_SLAB)
        t.set(x, 2, 4, "minecraft:white_carpet")
    lantern(t, 1, 1, 2)
    lantern(t, w - 2, 1, 2)
    return t


def style_dye_works(s):
    """The building of the dyer in the style s: a house with a loom (the work block of the vanilla shepherd) and bales
    of dyed wool, and a yard with beds of dandelions, poppies and cornflowers (the colors of the dyes) along the sides
    and 2 dye vats (water cauldrons). A path of coarse dirt goes from the back door to the back of the yard. The vats
    are not next to the edge of the yard (see dye_works)."""
    rng = random.Random(s.seed + 84)
    w = s.width
    flowers = {1: "minecraft:dandelion", 2: "minecraft:poppy", w - 3: "minecraft:poppy", w - 2: "minecraft:cornflower"}
    vats = ((3, s.shed + 2), (w - 4, s.shed + 2))

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == w // 2 else GRASS

    def plants(x, z):
        return WATER_CAULDRON if (x, z) in vats else flowers.get(x)

    t = s.yard_workshop(rng, "dyer", "minecraft:red_dye", ground, plants, work=LOOM)
    t.set(1, 1, 2, "minecraft:red_wool")
    t.set(1, 2, 2, "minecraft:yellow_wool")
    t.set(2, 1, 2, "minecraft:blue_wool")
    return t


def style_paper_mill(s):
    """The building of the papermaker in the style s: a house with a lectern (the work block of the vanilla librarian)
    between 2 vats of pulp (water cauldrons), barrels of sugar cane, a crafting table, bookshelves, and a counter with
    sheets of paper to dry (white carpets) near the door (see paper_mill). A free block stays next to each work
    block."""
    rng = random.Random(s.seed + 85)
    t = s.workshop(rng, "papermaker", "minecraft:paper", s.beds)
    w, back = s.width, s.depth - 2
    mid = w // 2
    t.set(mid, 1, back - 1, "minecraft:lectern", {"facing": "north", "has_book": "false", "powered": "false"})
    for x in (mid - 1, mid + 1):
        t.set(x, 1, back, *WATER_CAULDRON)
    t.set(1, 1, back, "minecraft:crafting_table")
    for z in (back - 1, back - 2):
        t.set(1, 1, z, *BARREL)
    for x, y, z in ((w - 2, 1, back), (w - 2, 2, back), (w - 2, 1, back - 1)):
        t.set(x, y, z, "minecraft:bookshelf")
    for x in (1, 2):
        t.set(x, 1, 4, *TOP_SLAB)
        t.set(x, 2, 4, "minecraft:white_carpet")
    lantern(t, 1, 1, 2)
    lantern(t, w - 2, 1, 2)
    return t


def style_jobs(s):
    """The house-workshops of the style s from the builders of the styles, by job: all the jobs of JOBS except the
    beekeeper, the fisher, the baker, the pastry cook and the butcher. Each style has builders of its own for these
    5 jobs."""
    return {
        "farmer": functools.partial(style_farm, s),
        "gardener": functools.partial(style_garden, s),
        "gardener_jungle": functools.partial(style_jungle_garden, s),
        "forager": functools.partial(style_forager_hut, s),
        "rancher": functools.partial(style_ranch, s),
        **{job: functools.partial(style_lumber_yard, s, job, wood) for job, wood in LUMBERJACKS.items()},
        **{job: functools.partial(style_sawmill, s, job, wood) for job, wood in SAWYERS.items()},
        "miner": functools.partial(style_mine, s),
        "digger": functools.partial(style_dig_yard, s),
        "mason": functools.partial(style_mason, s),
        **{job: functools.partial(style_smeltery, s, job) for job in SMELTERS},
        "blacksmith": functools.partial(style_smithy, s),
        "tinker": functools.partial(style_tinker, s),
        "fletcher": functools.partial(style_fletcher_range, s),
        "carpenter": functools.partial(style_carpenter, s),
        "tanner": functools.partial(style_tannery, s),
        "shepherd": functools.partial(style_sheepfold, s),
        "weaver": functools.partial(style_weaving_house, s),
        "dyer": functools.partial(style_dye_works, s),
        "papermaker": functools.partial(style_paper_mill, s),
    }


# The Valeholm style of the house-workshops: Norse long houses of LW x LD blocks (see WorkshopStyle).
VALEHOLM = WorkshopStyle(
    width=LW, depth=LD, shed=NORSE_SHED, water=NORSE_WATER, seed=0, yard_workshop=norse_yard_workshop,
    workshop=norse_workshop, oven=norse_oven, beds=NORSE_BEDS,
    sawmill_lantern=(LW - 2, 1, 4), mason_cutter=(2, 4), mason_lantern=(LW - 2, 1, 6),
    smeltery=SmelteryLayout(furnaces=(2, 3), ore_x=1, coal=(1, LD - 4), cauldron=(LW - 2, LD - 3), lanterns=()))

# The house-workshops of the Valeholm style, by job: each job of JOBS has a piece of the style.
NORSE_JOBS = {
    **style_jobs(VALEHOLM),
    "beekeeper": norse_apiary,
    "fisher": norse_fisher_hut,
    "baker": norse_bakery,
    "pastry_cook": norse_pastry_shop,
    "butcher": norse_butchery,
}


# ---------------------------------------------------------------- house-workshops of the Sunreach style

# A Sunreach house-workshop is FW blocks wide, as the flat houses of the style, and CD blocks deep, as the courtyard
# houses of the style. The farmer, the gardeners, the forager, the beekeeper, the rancher, the fisher, the lumberjacks,
# the miner, the digger, the fletcher, the tanner, the shepherd and the dyer have a flat house on the street
# (z = 1..SUN_SHED - 1) and a walled yard behind it.
SUN_SHED = 7
SUN_YARD_Z = range(SUN_SHED, CD - 1)
SUN_YARD_X = range(1, FW - 1)
# The water of a Sunreach field, in the middle of the yard: it gives water to all the farmland of the yard.
SUN_WATER = (FW // 2, SUN_SHED + 2)
# The low wall around a Sunreach yard. The wall connects to its neighbors as in the game (see connect).
SUN_WALL = "minecraft:sandstone_wall"


def sun_workshop_shell(rng, depth=CD, wide_front=False):
    """The shell of a Sunreach house-workshop: the flat house shell (see flat_house_shell) with a body from z = 1 to
    z = depth - 1, in a template of FW x FH x CD blocks. The house sign stands on the porch east of the door, and the
    job sign of the caller west of the door."""
    shell = flat_house_shell(rng, "house", FW, depth, wide_front=wide_front)
    t = Template(FW, FH, CD)
    t.fill(0, 1, 0, FW - 1, FH - 1, CD - 1, "minecraft:air")
    t.blocks.update(shell.blocks)
    return t


def sun_yard_workshop(rng, job, icon, ground, plants, work=COMPOSTER):
    """The house-workshop of a job with a yard in the Sunreach style (the beekeeper, the fisher, and the jobs with a
    yard of style_jobs: the farmer, the gardeners, the forager, the rancher, the lumberjacks, the miner, the digger,
    the fletcher, the tanner, the shepherd and the dyer): a flat house on the street and a walled yard behind it.

    - The house: the flat house shell with a door on the street and a back door to the yard. The job sign stands on
      the porch west of the door, and the house sign east of the door.
    - The work blocks: the work block of the vanilla profession of the job in the west back corner, and a barrel next
      to the door.
    - The home: 2 beds side by side in the east half, with the heads to the east. The path from the door to the back
      door (x = 4) goes along the feet of the beds.
    - The yard: ground(x, z) gives the ground block of each position, and plants(x, z) gives the block above it, or
      None. A low sandstone wall goes around the yard, with a lantern on a pillar at each back corner.

    The caller adds the blocks of the job.
    """
    t = sun_workshop_shell(rng, SUN_SHED)
    mid = FW // 2
    back = SUN_SHED - 1
    for y, half in ((1, "lower"), (2, "upper")):
        t.set(mid, y, back, "minecraft:spruce_door",
              {"facing": "south", "half": half, "hinge": "left", "open": "false", "powered": "false"})
    t.set(1, 1, back - 1, *work)
    t.set(mid - 1, 1, 2, *BARREL)
    lantern(t, FW - 2, 1, 2)
    home_beds(t, (mid + 1, back - 3), (mid + 1, back - 2), facing="east")
    job_sign(t, mid - 1, 1, 0, job, icon)
    for x in range(FW):
        for z in range(SUN_SHED, CD):
            if x in SUN_YARD_X and z in SUN_YARD_Z:
                t.set(x, 0, z, *block(ground(x, z)))
                plant = plants(x, z)
                if plant:
                    t.set(x, 1, z, *block(plant))
            else:
                t.set(x, 0, z, "minecraft:sandstone")
                pillar = x in (0, FW - 1) and z == CD - 1
                t.set(x, 1, z, "minecraft:cut_sandstone" if pillar else SUN_WALL)
    for x in (0, FW - 1):
        lantern(t, x, 2, CD - 1)
    return t


DECORATED_POT = ("minecraft:decorated_pot", {"cracked": "false", "facing": "south", "waterlogged": "false"})


def sun_apiary():
    """The Sunreach building of the beekeeper: 2 beehives on the ground among the flowers, a path of coarse dirt, and
    pots of honey in the house. A walk in the yard reaches each hive. The yard has no campfire: a campfire burns, and
    the heart takes it as a brazier."""
    rng = random.Random(85)
    mid, row = SUN_WATER
    hives = ((2, row), (FW - 3, row))
    flowers = FLOWERS + ("minecraft:oxeye_daisy", "minecraft:allium")

    def ground(x, z):
        return "minecraft:coarse_dirt" if x == mid else GRASS

    def plants(x, z):
        if (x, z) in hives:
            return "minecraft:beehive", {"facing": "south", "honey_level": str(rng.randint(2, 5))}
        if x == mid or (x + z) % 2:
            return None
        return flowers[rng.randrange(len(flowers))]

    t = sun_yard_workshop(rng, "beekeeper", "minecraft:honeycomb", ground, plants)
    for x in (1, 2):
        t.set(x, 1, 2, *DECORATED_POT)
    return t


def sun_fisher_hut():
    """The Sunreach building of the fisher: a house with barrels (the work block of the vanilla fisherman) and a tub,
    and a yard with a pond for the live catch. The pond is a row of waterlogged slabs, with a path of coarse dirt next
    to it: a builder stands on the path to add the water."""
    rng = random.Random(87)
    pond = range(2, FW - 2)
    row = CD - 2

    def ground(x, z):
        if z == row and x in pond:
            return WATER_SLAB
        return "minecraft:coarse_dirt" if z == row - 1 or x == FW // 2 else GRASS

    def plants(x, z):
        return BARREL if (x, z) in ((2, SUN_SHED), (FW - 3, SUN_SHED)) else None

    t = sun_yard_workshop(rng, "fisher", "minecraft:cod", ground, plants, work=BARREL)
    t.set(1, 1, 2, *WATER_CAULDRON)
    # The nets: cobwebs high on the side wall, over the free floor between the tub and the barrel. A player on the
    # floor walks under them. No net is over a block that a player stands on (the barrel, the tub).
    for z in (3, 4):
        t.set(1, 3, z, "minecraft:cobweb")
    return t


def sun_workshop(rng, job, icon, beds, wide_front=False, wall_lower=None):
    """The house-workshop of a job in a Sunreach flat house of FW x CD blocks (the baker, the pastry cook, the butcher,
    and the jobs with no yard of style_jobs: the sawyers, the mason, the smelters, the blacksmith, the tinker, the
    carpenter, the weaver and the papermaker), with a door on the street or an open shop front. The job sign
    stands on the porch west of the door, and the house sign east of the door. beds gives the foot of each bed of the
    home (see home_beds): the head is 1 block to the back wall. The walls of a flat house have no lower layer of other
    blocks: the workshop does not use wall_lower. The caller adds the blocks of the job."""
    t = sun_workshop_shell(rng, wide_front=wide_front)
    home_beds(t, *beds)
    job_sign(t, FW // 2 - 1, 1, 0, job, icon)
    return t


def sun_oven(t):
    """A brick oven on the back wall of a Sunreach house: a furnace in bricks, with a chimney of bricks through the
    flat roof."""
    x, z = FW // 2, CD - 2
    t.set(x, 1, z, "minecraft:furnace", {"facing": "north", "lit": "false"})
    for dx in (-1, 0, 1):
        t.set(x + dx, 2, z, "minecraft:bricks")
        if dx:
            t.set(x + dx, 1, z, "minecraft:bricks")
    for y in range(3, FH):
        t.set(x + 1, y, z, "minecraft:bricks")


def sun_bakery():
    """The Sunreach building of the baker: a brick oven, a crafting table to knead the dough, barrels of flour, the
    wheat on hay bales, pots of grain and a cake on the counter."""
    rng = random.Random(88)
    t = sun_workshop(rng, "baker", "minecraft:bread", ((1, 3), (FW - 2, 3)))
    sun_oven(t)
    t.set(1, 1, CD - 2, "minecraft:crafting_table")
    for x, z in ((1, CD - 4), (2, CD - 2)):
        t.set(x, 1, z, *BARREL)
    for x, y, z in ((FW - 2, 1, CD - 2), (FW - 2, 1, CD - 3), (FW - 2, 2, CD - 2)):
        t.set(x, y, z, "minecraft:hay_block", LOG_Y)
    for z in (6, 7):
        t.set(FW - 2, 1, z, *DECORATED_POT)
    t.set(FW - 2, 1, 2, *TOP_SLAB)
    t.set(FW - 2, 2, 2, "minecraft:cake", {"bites": "0"})
    lantern(t, 1, 1, 2)
    lantern(t, 1, 1, 7)
    return t


def sun_pastry_shop():
    """The Sunreach building of the pastry cook: an open shop front, a counter with cakes, a brick oven, pumpkins and
    barrels of sugar."""
    rng = random.Random(89)
    t = sun_workshop(rng, "pastry_cook", "minecraft:pumpkin_pie", ((1, 5), (FW - 2, 5)), wide_front=True)
    sun_oven(t)
    # The counter behind the open front, with a free passage in the middle.
    for x in (1, 2, FW - 3, FW - 2):
        t.set(x, 1, 3, *TOP_SLAB)
    for x in (2, FW - 3):
        t.set(x, 2, 3, "minecraft:candle_cake", {"lit": "false"})
    for x, y in ((1, 1), (2, 1), (1, 2)):
        t.set(x, y, CD - 2, "minecraft:pumpkin")
    for z in (CD - 2, CD - 3):
        t.set(FW - 2, 1, z, *BARREL)
    lantern(t, 1, 1, 8)
    lantern(t, FW - 2, 1, 8)
    return t


def sun_butchery():
    """The Sunreach building of the butcher: 2 smokers (the work block of the vanilla butcher), a block of coal, a
    chopping block, and a rack of chains under the roof. The chains leave 2 blocks of space under them."""
    rng = random.Random(90)
    t = sun_workshop(rng, "butcher", "minecraft:cooked_beef", ((1, 4), (FW - 2, 4)))
    for x in (5, 6):
        t.set(x, 1, CD - 2, "minecraft:smoker", {"facing": "north", "lit": "false"})
    t.set(FW - 2, 1, CD - 2, "minecraft:coal_block")
    for x in (2, 3, 6):
        t.set(x, FLAT_WALL_TOP, 8, "minecraft:chain", {"axis": "y", "waterlogged": "false"})
    t.set(2, 1, 3, "minecraft:stripped_oak_log", LOG_Y)
    for z in (CD - 2, CD - 3):
        t.set(1, 1, z, *BARREL)
    t.set(FW - 2, 1, 3, *WATER_CAULDRON)
    lantern(t, 1, 1, 2)
    lantern(t, 1, 1, 7)
    return t


# The beds of a Sunreach workshop with no yard of style_jobs (the sawyers, the mason, the smelters, the blacksmith, the
# tinker, the carpenter, the weaver and the papermaker): on the 2 side walls, in the middle of the room. The front of
# the room stays free for the counter and the tools, and the back of the room for the work.
SUN_CRAFT_BEDS = ((1, 5), (FW - 2, 5))

# The Sunreach style of the house-workshops: flat houses of FW x CD blocks (see WorkshopStyle).
SUNREACH = WorkshopStyle(
    width=FW, depth=CD, shed=SUN_SHED, water=SUN_WATER, seed=20, yard_workshop=sun_yard_workshop,
    workshop=sun_workshop, oven=sun_oven, beds=SUN_CRAFT_BEDS,
    sawmill_lantern=(FW - 2, 1, 3), mason_cutter=(2, 3), mason_lantern=(FW - 2, 1, 8),
    smeltery=SmelteryLayout(furnaces=(1, 2), ore_x=FW - 2, coal=(FW - 3, CD - 2), cauldron=(FW - 2, CD - 4),
                            lanterns=((1, 1, 8),)))

# The house-workshops of the Sunreach style, by job: each job of JOBS has a piece of the style.
SUNREACH_JOBS = {
    **style_jobs(SUNREACH),
    "beekeeper": sun_apiary,
    "fisher": sun_fisher_hut,
    "baker": sun_bakery,
    "pastry_cook": sun_pastry_shop,
    "butcher": sun_butchery,
}


# ---------------------------------------------------------------- market

# The market square takes the plot of one house. x goes along the street, z goes away from the street: z = 0 is the
# street side, with the jigsaw connector house_front in the middle. Each layout has:
# - size: (width along the street, depth).
# - post: (x, z) of the market post, in the middle of the free part of the square.
# - stalls: (x, z, facing) of each stall: the corner with the lowest x and z, and the direction of the counter.
# The caravan yard is in the corner at the west of the street side (see caravan_yard).
MARKET_LAYOUTS = {
    # Base, Sunreach and maritime streets: 15 blocks along the street, 9 blocks deep (the depth of a house of 9 x 9).
    "wide": {
        "size": (15, 9),
        "post": (7, 4),
        "stalls": ((1, 5, "east"), (11, 1, "west"), (11, 5, "west")),
    },
    # Valeholm streets: 9 blocks along the street, 15 blocks deep (inside the space of a long house of 9 x 17).
    "deep": {
        "size": (9, 15),
        "post": (4, 7),
        "stalls": ((1, 11, "north"), (5, 11, "north")),
    },
}
MARKET_HEIGHT = 6
# The wool of the awning of each stall, in the order of the stalls of the layout.
AWNINGS = ("red", "yellow", "blue", "lime")
# The direction of a counter: the step (dx, dz) from the middle of the stall to the counter.
COUNTER_SIDES = {"east": (1, 0), "west": (-1, 0), "north": (0, -1)}


def stall(t, x0, z0, facing, color):
    """A stall of 3 x 3 blocks: 4 fence posts at the corners, an awning of wool stripes, a counter of a top slab with a
    lantern on it, and a barrel in the middle of the stall.

    The counter is in the middle of the side of the stall in the direction facing. The barrel is not next to a post:
    a fence connects to a barrel."""
    for x in (x0, x0 + 2):
        for z in (z0, z0 + 2):
            for y in (1, 2):
                t.set(x, y, z, "minecraft:spruce_fence")
    dx, dz = COUNTER_SIDES[facing]
    for x in range(x0, x0 + 3):
        for z in range(z0, z0 + 3):
            # The white stripe goes from the counter to the back of the stall.
            stripe = (x == x0 + 1) if dx == 0 else (z == z0 + 1)
            t.set(x, 3, z, f"minecraft:{'white' if stripe else color}_wool")
    mx, mz = x0 + 1, z0 + 1
    t.set(mx + dx, 1, mz + dz, *TOP_SLAB)
    lantern(t, mx + dx, 2, mz + dz)
    t.set(mx, 1, mz, *BARREL)


def caravan_yard(t):
    """The caravan yard: the corner at the west of the street side (x = 0 to 3, z = 1 to 3), out of the middle of the
    square. The mules come in from the street.

    - A hitching rail of 3 fences on the west edge (x = 0), with a lantern on each end.
    - The place of the mules: x = 1 and 2, z = 1 to 3.
    - A water trough (a water cauldron) and a hay bale at x = 3. They are not next to a fence."""
    for z in (1, 2, 3):
        t.set(0, 1, z, "minecraft:spruce_fence")
    for z in (1, 3):
        lantern(t, 0, 2, z)
    t.set(3, 1, 1, *WATER_CAULDRON)
    t.set(3, 1, 3, "minecraft:hay_block", LOG_Y)


def market(layout):
    """The market square: the place where the workers of the village trade, and where a caravan stops. It takes the
    plot of one house (see MARKET_LAYOUTS).

    - The square is paved: cobblestone with a border of stone bricks. It is open on all the sides, and the side on the
      street (z = 0) is free.
    - The market post stands in the free middle of the square: a log with a lantern on it, on chiseled stone bricks.
      The market sign stands on the street side of the post and faces the street. The heart finds the market by this
      sign: the market post is behind it (tag folkhold_world:market_posts).
    - The stalls (see stall) along the sides of the free middle. The counters face the middle.
    - The free middle: 7 x 7 blocks around the market post, for the workers.
    - The caravan yard in a corner of the street side (see caravan_yard).
    - A lamp post at each corner.
    """
    spec = MARKET_LAYOUTS[layout]
    width, depth = spec["size"]
    rng = random.Random(81)
    t = Template(width, MARKET_HEIGHT, depth)
    t.fill(0, 1, 0, width - 1, MARKET_HEIGHT - 1, depth - 1, "minecraft:air")
    for x in range(width):
        for z in range(depth):
            border = x in (0, width - 1) or z in (0, depth - 1)
            t.set(x, 0, z, "minecraft:stone_bricks" if border else cobble(rng))
    px, pz = spec["post"]
    t.set(px, 0, pz, "minecraft:chiseled_stone_bricks")
    for y in (1, 2, 3):
        t.set(px, y, pz, "minecraft:stripped_spruce_log", LOG_Y)
    lantern(t, px, 4, pz)
    building_sign(t, px, 1, pz - 1, "market")
    for (x0, z0, facing), color in zip(spec["stalls"], AWNINGS):
        stall(t, x0, z0, facing, color)
    caravan_yard(t)
    for x, z in ((0, 0), (width - 1, 0), (0, depth - 1), (width - 1, depth - 1)):
        lamp_post(t, x, z)
    t.jigsaw(width // 2, 1, 0, "north_up", HOUSE_FRONT, "minecraft:empty", "minecraft:empty", "minecraft:air")
    return t


# ---------------------------------------------------------------- fields and mills

# The pieces of the land outside the palisade: the fields and the windmill. The growth builds them outside the palisade
# (pools village/fields and village/mills of each variant). The front of a piece is z = 0: a row of ground with the
# building sign, the gate and the door. The pieces have no jigsaw connector.


@dataclasses.dataclass(frozen=True)
class LandStyle:
    """The materials of the fields and of the windmill of a culture (see LAND). The biome variants replace these
    blocks as in the other pieces (see Variant).

    - fence, gate: the fence around a field and the fence gate on its front side.
    - path: the ground of the front row and of the paths of a field.
    - base(rng): a block of the base course of the windmill.
    - wall, corner: the walls of the windmill and its corner logs (axis y).
    - roof: the stairs of the roof of the windmill. The top of the roof is a slab of the same wood.
    - frame, cloth: the arms of the sails (a fence block) and the cloth of the sails.
    """
    fence: str
    gate: str
    path: str
    base: Callable[[random.Random], str]
    wall: str
    corner: str
    roof: str
    roof_slab: str
    frame: str
    cloth: str


LAND = LandStyle(
    fence="minecraft:spruce_fence",
    gate="minecraft:spruce_fence_gate",
    path="minecraft:coarse_dirt",
    base=cobble,
    wall="minecraft:spruce_planks",
    corner="minecraft:spruce_log",
    roof="minecraft:dark_oak_stairs",
    roof_slab="minecraft:dark_oak_slab",
    frame="minecraft:spruce_fence",
    cloth="minecraft:white_wool",
)


@dataclasses.dataclass(frozen=True)
class FieldLayout:
    """The size of a field, the places of its water and of its paths.

    - width: the size along the front side, with the fence.
    - waters: the x of each block of water, on the middle row of the field. Each block of farmland is 4 blocks or less
      from a block of water on the X axis and on the Z axis: the farmland stays wet.
    - paths: the x of each path from the front to the back of the field. Each crop is 2 blocks or less from a path or
      from the outside of the fence on the X axis: a builder reaches it (see Reach.DEFAULT in the core mod).
    - rows: the crop of each x of the field, from x = 1. A row of beetroots has the top age 3.
    """
    width: int
    waters: tuple[int, ...]
    paths: tuple[int, ...]
    rows: tuple[str, ...]


# The depth of a field: the front row (z = 0), the fence (z = 1 and z = FIELD_DEPTH - 1) and 9 rows of farmland. The
# middle row has the water. The first row inside the fence is a path from the gate to the paths of the field.
FIELD_DEPTH = 12
FIELD_MIDDLE = 6
FIELD_FIRST_ROW = 2
FIELD_HEIGHT = 3
FIELDS = {
    "small": FieldLayout(11, (5,), (3, 7), ("wheat", "wheat", "wheat", "carrots", "carrots", "carrots", "wheat",
                                            "potatoes", "potatoes")),
    "large": FieldLayout(17, (4, 12), (3, 8, 13), ("wheat", "wheat", "wheat", "wheat", "wheat", "beetroots",
                                                   "wheat", "wheat", "wheat", "carrots", "wheat", "wheat", "wheat",
                                                   "wheat", "wheat")),
}


def field(style, size):
    """A field of wheat and other crops (see FIELDS), with a fence around it.

    - The front row (z = 0): the ground of the style, the farm sign next to the gate.
    - The fence: on the edges of the field (z = 1 to FIELD_DEPTH - 1), with a fence gate in the middle of the front
      side and a lantern on each corner post.
    - The farmland: the crops of the rows of the layout, near the end of their growth. The water of each column of
      water is a waterlogged slab in the middle row (see WATER_SLAB).
    - The paths: the first row inside the fence goes from the gate to each path of the layout, and each path goes to
      the back fence. A builder stands on a path to place the crops and the water, and a player walks along the paths.
    """
    spec = FIELDS[size]
    width = spec.width
    if len(spec.rows) != width - 2:
        raise SystemExit(f"The field {size} needs a crop for each of its {width - 2} columns")
    rng = random.Random(91 + width)
    mid = width // 2
    t = Template(width, FIELD_HEIGHT, FIELD_DEPTH)
    t.fill(0, 1, 0, width - 1, FIELD_HEIGHT - 1, FIELD_DEPTH - 1, "minecraft:air")
    waters = {(x, FIELD_MIDDLE) for x in spec.waters}
    first = range(min(spec.paths + (mid,)), max(spec.paths + (mid,)) + 1)
    for x in range(width):
        t.set(x, 0, 0, style.path if x == mid else GRASS[0], None if x == mid else GRASS[1])
        for z in range(1, FIELD_DEPTH):
            edge = x in (0, width - 1) or z in (1, FIELD_DEPTH - 1)
            if edge:
                t.set(x, 0, z, *GRASS)
                t.set(x, 1, z, style.fence)
            elif (x, z) in waters:
                t.set(x, 0, z, *WATER_SLAB)
            elif x in spec.paths or (z == FIELD_FIRST_ROW and x in first):
                t.set(x, 0, z, style.path)
            else:
                t.set(x, 0, z, *FARMLAND)
                name = spec.rows[x - 1]
                t.set(x, 1, z, *crop(f"minecraft:{name}", rng, 3 if name == "beetroots" else 7))
    t.set(mid, 1, 1, style.gate, {"facing": "north", "in_wall": "false", "open": "false", "powered": "false"})
    for x in (0, width - 1):
        for z in (1, FIELD_DEPTH - 1):
            lantern(t, x, 2, z)
    building_sign(t, mid + 2, 1, 0, "farm")
    return t


# The windmill: a tower of MILL_BODY x MILL_BODY blocks behind the front row, with its sails on the front side.
MILL_WIDTH = 9
MILL_BODY = 7
MILL_DEPTH = MILL_BODY + 1
# The top layer of the walls, the layer of the hub of the sails, and the length of an arm of the sails.
MILL_WALL_TOP = 9
MILL_HUB_Y = 9
MILL_ARM = 4
MILL_HEIGHT = MILL_WALL_TOP + 5


def windmill(style):
    """The windmill: the mill of the grain of the fields.

    - The tower: a base course of the style, walls of planks with a log at each corner, a door in the middle of the
      front wall, windows on the sides and a pyramid roof (see roof_stairs) with a slab in its top ring.
    - The room: the grindstone of the miller on the floor, barrels of flour and hay bales, and a lantern on a barrel.
    - The sails: 4 arms of fences around the hub (a log on the front wall, axis z), in the front row. Each arm has a
      cloth of 3 blocks on one side, as the blades of a windmill. The lowest arm ends above the door.
    - The mill sign stands on the front row, east of the door.
    """
    rng = random.Random(97)
    mid = MILL_WIDTH // 2
    t = Template(MILL_WIDTH, MILL_HEIGHT, MILL_DEPTH)
    t.fill(0, 1, 0, MILL_WIDTH - 1, MILL_HEIGHT - 1, MILL_DEPTH - 1, "minecraft:air")
    x0, x1 = 1, MILL_WIDTH - 2
    z0, z1 = 1, MILL_DEPTH - 1
    for x in range(MILL_WIDTH):
        t.set(x, 0, 0, style.path if x == mid else GRASS[0], None if x == mid else GRASS[1])
        for z in range(z0, z1 + 1):
            inside = x0 < x < x1 and z0 < z < z1
            if not x0 <= x <= x1:
                t.set(x, 0, z, *GRASS)
                continue
            t.set(x, 0, z, style.wall if inside else style.base(rng))
            if inside:
                continue
            corner = x in (x0, x1) and z in (z0, z1)
            for y in range(1, MILL_WALL_TOP + 1):
                if corner:
                    t.set(x, y, z, style.corner, LOG_Y)
                elif y == 1:
                    t.set(x, y, z, style.base(rng))
                else:
                    t.set(x, y, z, style.wall)
    # The roof: rings of stairs from the top of the walls, and a slab on the top.
    for ring in range(MILL_BODY // 2):
        y = MILL_WALL_TOP + 1 + ring
        low, high = ring, MILL_BODY - 1 - ring
        for dx in range(low, high + 1):
            for dz in range(low, high + 1):
                if dx in (low, high) or dz in (low, high):
                    t.set(x0 + dx, y, z0 + dz, style.roof, roof_stairs(dx, dz, low, high)[1])
    # The slab is in the middle of the top ring, not above it: a builder on a scaffolding in the room reaches it.
    t.set(mid, MILL_WALL_TOP + MILL_BODY // 2, z0 + MILL_BODY // 2, style.roof_slab,
          {"type": "bottom", "waterlogged": "false"})
    # The door, and the windows on the sides and at the back.
    for y, half in ((1, "lower"), (2, "upper")):
        t.set(mid, y, z0, "minecraft:spruce_door",
              {"facing": "north", "half": half, "hinge": "left", "open": "false", "powered": "false"})
    for y in (3, 6):
        for z in (z0 + 2, z1 - 2):
            t.set(x0, y, z, "minecraft:glass_pane")
            t.set(x1, y, z, "minecraft:glass_pane")
        t.set(mid, y, z1, "minecraft:glass_pane")
    # The room: the grindstone, the flour and the grain.
    # The middle of the room stays free for the scaffolding of the builders under the top of the roof.
    t.set(mid - 2, 1, z0 + 3, "minecraft:grindstone", {"face": "floor", "facing": "east"})
    t.set(x0 + 1, 1, z1 - 1, *BARREL)
    t.set(x0 + 1, 1, z1 - 2, *BARREL)
    t.set(x1 - 1, 1, z1 - 1, "minecraft:hay_block", LOG_Y)
    t.set(x1 - 1, 2, z1 - 1, "minecraft:hay_block", LOG_Y)
    t.set(x1 - 1, 1, z1 - 2, "minecraft:hay_block", LOG_Y)
    lantern(t, x0 + 1, 2, z1 - 1)
    # The sails: the hub, the arms and the cloth. Each cloth is on the side of its arm in the order of the rotation.
    t.set(mid, MILL_HUB_Y, 0, "minecraft:stripped_spruce_log", {"axis": "z"})
    for step in range(1, MILL_ARM + 1):
        t.set(mid, MILL_HUB_Y + step, 0, style.frame)
        t.set(mid, MILL_HUB_Y - step, 0, style.frame)
        t.set(mid + step, MILL_HUB_Y, 0, style.frame)
        t.set(mid - step, MILL_HUB_Y, 0, style.frame)
        if step > 1:
            t.set(mid + 1, MILL_HUB_Y + step, 0, style.cloth)
            t.set(mid - 1, MILL_HUB_Y - step, 0, style.cloth)
            t.set(mid + step, MILL_HUB_Y - 1, 0, style.cloth)
            t.set(mid - step, MILL_HUB_Y + 1, 0, style.cloth)
    building_sign(t, mid + 2, 1, 0, "mill")
    return t


# The job buildings: the piece job_<id> of each job, in the pool village/jobs of each variant. The id is the name of
# the job file.
JOBS = {
    "farmer": farm,
    "gardener": garden,
    "gardener_jungle": jungle_garden,
    "forager": forager_hut,
    "beekeeper": apiary,
    "baker": bakery,
    "pastry_cook": pastry_shop,
    "rancher": ranch,
    "butcher": butchery,
    "fisher": fisher_hut,
    **{job: functools.partial(lumber_yard, job, wood) for job, wood in LUMBERJACKS.items()},
    **{job: functools.partial(sawmill, job, wood) for job, wood in SAWYERS.items()},
    "miner": mine,
    "digger": dig_yard,
    "mason": mason_workshop,
    **{job: functools.partial(smeltery, job) for job in SMELTERS},
    "blacksmith": functools.partial(forge, home=True),
    "tinker": tinker_workshop,
    "fletcher": fletcher_range,
    "carpenter": carpenter_workshop,
    "tanner": tannery,
    "shepherd": sheepfold,
    "weaver": weaving_house,
    "dyer": dye_works,
    "papermaker": paper_mill,
}
# The house-workshops of the styles of the cultures, by culture and job.
STYLE_JOBS = {"valeholm": NORSE_JOBS, "sunreach": SUNREACH_JOBS}
# The cultures whose style has a house-workshop for each job of JOBS. Add a culture here when its style is complete.
COMPLETE_STYLES = ("valeholm", "sunreach")


def check_style_jobs():
    """Stops the script when a style has a piece for a job that is not in JOBS, or when a complete style (see
    COMPLETE_STYLES) has no piece for a job of JOBS."""
    for culture, jobs in STYLE_JOBS.items():
        unknown = sorted(jobs.keys() - JOBS.keys())
        if unknown:
            raise SystemExit(f"The style {culture} has pieces for jobs that are not in JOBS: {unknown}")
        missing = sorted(JOBS.keys() - jobs.keys())
        if culture in COMPLETE_STYLES and missing:
            raise SystemExit(f"Each job needs a house-workshop of the style {culture}. No piece for: {missing}")


check_style_jobs()

# The jobs whose house-workshop of the base style grows in place (see add_storey): the house-workshops in a house shell
# of the full depth D. The house-workshops with a shed and a yard (see job_building) have no space for a stair: their
# shed has 2 rows, and the yard holds the work of the job.
# The storeys of the styles of the cultures: the Norse long house has walls of 3 blocks and a steep roof above the
# new floor, and the Sunreach house has a flat roof on the walls, which is the floor of the new storey.
NORSE_STOREY = StoreyStyle(LONG_WALL_TOP, LONG_WALL_TOP, False, "minecraft:spruce_planks", "minecraft:spruce_stairs",
                           "minecraft:spruce_planks", SLAB_ROW)
SUN_STOREY = StoreyStyle(FLAT_WALL_TOP, FLAT_WALL_TOP + 1, True, "minecraft:smooth_sandstone",
                         "minecraft:smooth_sandstone_stairs", "minecraft:smooth_sandstone", None)
# The house-workshops and the storeys of each style: the base style (None) and the styles of the cultures.
STEP_STYLES = {None: (JOBS, BASE_STOREY), "valeholm": (NORSE_JOBS, NORSE_STOREY),
               "sunreach": (SUNREACH_JOBS, SUN_STOREY)}
# The steps of the homes that grow in place, after the step 1 piece.
HOME_STEPS = (2, 3)
# The jobs whose house-workshop grows in place in each style: the house-workshops with a stair and beds in each step
# (see add_storey). A shed of 2 rows has no space for a stair.
HOME_STEP_JOBS = {culture: tuple(job for job in jobs if all(home_step(culture, job, step) for step in HOME_STEPS))
                  for culture, (jobs, _) in STEP_STYLES.items()}
# The pieces of the steps of the homes of each style: job_<id>_step<step>, in the pool jobs_step<step>.
STEP_PIECES = {culture: {f"job_{job}_step{step}": functools.partial(home_step, culture, job, step)
                         for step in HOME_STEPS for job in jobs}
               for culture, jobs in HOME_STEP_JOBS.items()}


def check_home_steps():
    for culture, jobs in HOME_STEP_JOBS.items():
        for step in HOME_STEPS:
            for job in jobs:
                check_home_step(culture, job, step)


# ---------------------------------------------------------------- cliff houses

# A cliff house is a tall house that stands against a rock face (see the pool cliff_houses). It has the plot of a home
# of the base style (W x D) and a storey of CLIFF_STOREY blocks:
# - x = 0..CLIFF_BODY - 1: the body of the house. z = 0 is the porch row, z = 1 the front wall, z = D - 1 the back wall.
#   The back wall is a full wall of stone bricks, with no window and no door: it stands against the rock.
# - x = CLIFF_STAIR: an outside stair of stone along the east wall, 1 block up for each block, from the porch row.
# - x = CLIFF_STAIR + 1: the parapet of the stair, with a rail.
# Each storey has its own door: the front door of the ground floor, and a door in the east wall of each upper storey,
# next to the step of the stair at the level of its floor. Each storey has 2 beds and a lantern. The cliff houses do
# not grow in place.
CLIFF_STOREY = 4
CLIFF_BODY = 7
CLIFF_STAIR = CLIFF_BODY
CLIFF_STOREYS = (2, 3)
CLIFF_BACK = "minecraft:stone_bricks"
CLIFF_BEDS = 2
CLIFF_BED_COLOR = "cyan"


def cliff_bed(t, x, y, z_foot):
    """A bed with the foot at (x, y, z_foot) and the head 1 block to the south, against the back wall."""
    t.set(x, y, z_foot, f"minecraft:{CLIFF_BED_COLOR}_bed", {"facing": "south", "part": "foot", "occupied": "false"})
    t.set(x, y, z_foot + 1, f"minecraft:{CLIFF_BED_COLOR}_bed",
          {"facing": "south", "part": "head", "occupied": "false"})


def cliff_door_row(storey):
    """The row (z) of the door of an upper storey in the east wall: the step of the stair at this row has its top at
    the floor of the storey."""
    return CLIFF_STOREY * storey - 1


def door(t, x, y, z, facing, hinge="left"):
    """A spruce door of 2 blocks, with its lower half at (x, y, z)."""
    for half, dy in (("lower", 0), ("upper", 1)):
        t.set(x, y + dy, z, "minecraft:spruce_door",
              {"facing": facing, "half": half, "hinge": hinge, "open": "false", "powered": "false"})


def cliff_walls(t, rng, storey):
    """The walls of a storey: a frame of stripped spruce logs, a lower row of stones on the ground floor, plaster
    (calcite) above it, and the back wall of stone bricks. The top layer is a beam of logs that holds the next
    floor."""
    base = CLIFF_STOREY * storey
    last = CLIFF_BODY - 1
    for x in range(CLIFF_BODY):
        for z in range(1, D):
            if not (x in (0, last) or z in (1, D - 1)):
                continue
            for y in range(base + 1, base + CLIFF_STOREY + 1):
                if z == D - 1:
                    t.set(x, y, z, CLIFF_BACK)
                elif x in (0, last) and z == 1:
                    t.set(x, y, z, "minecraft:stripped_spruce_log", LOG_Y)
                elif y == base + CLIFF_STOREY:
                    t.set(x, y, z, "minecraft:stripped_spruce_log", {"axis": "x" if z == 1 else "z"})
                elif storey == 0 and y == 1:
                    t.set(x, y, z, cobble(rng))
                else:
                    t.set(x, y, z, "minecraft:calcite")
    # The floor of the storey above: planks inside the beam.
    for x in range(1, last):
        for z in range(2, D - 1):
            t.set(x, base + CLIFF_STOREY, z, "minecraft:spruce_planks")
    # Windows: 2 in the front wall and 2 in the west wall. The east wall is behind the stair.
    y = base + 2
    for x in (1, last - 1):
        t.set(x, y, 1, "minecraft:glass_pane")
    for z in (3, 6):
        t.set(0, y, z, "minecraft:glass_pane")


def cliff_rooms(t, storey):
    """The beds, the work blocks and the light of a storey. The door of each storey has a free block inside it."""
    y = CLIFF_STOREY * storey + 1
    last = CLIFF_BODY - 2
    if storey == 0:
        cliff_bed(t, 1, y, 6)
        cliff_bed(t, last, y, 6)
        t.set(3, y, 7, "minecraft:crafting_table")
        t.set(last, y, 3, "minecraft:chest", {"facing": "west", "type": "single", "waterlogged": "false"})
        lantern(t, 1, y, 3)
    elif cliff_door_row(storey) < D - 2:
        # The door is near the front: the beds stand against the back wall.
        cliff_bed(t, 1, y, 6)
        cliff_bed(t, last, y, 6)
        t.set(1, y, 3, "minecraft:barrel", {"facing": "up", "open": "false"})
        lantern(t, 3, y, 7)
    else:
        # The door is near the back: its row stays free on the east side.
        cliff_bed(t, 1, y, 6)
        cliff_bed(t, 3, y, 6)
        t.set(1, y, 3, "minecraft:chest", {"facing": "south", "type": "single", "waterlogged": "false"})
        lantern(t, last, y, 3)


def cliff_stair(t, storeys):
    """The outside stair along the east wall, from the porch row to the floor of the top storey: a step of stone
    stairs on a support of stone, a parapet with a rail, and a pier at the end with a rail on it."""
    top = cliff_door_row(storeys - 1)
    rail = CLIFF_STAIR + 1
    for z in range(top + 1):
        for y in range(1, z + 1):
            t.set(CLIFF_STAIR, y, z, "minecraft:cobblestone")
        t.set(CLIFF_STAIR, z + 1, z, "minecraft:cobblestone_stairs", stairs("south"))
        for y in range(1, z + 2):
            t.set(rail, y, z, "minecraft:cobblestone")
        t.set(rail, z + 2, z, "minecraft:spruce_fence")
    end = top + 1
    for x in (CLIFF_STAIR, rail):
        for y in range(1, top + 2):
            t.set(x, y, end, "minecraft:cobblestone")
        t.set(x, top + 2, end, "minecraft:spruce_fence")


def cliff_roof(t, storeys):
    """A gable roof of dark oak over the body, with the ridge along z, from the front to the back wall. It goes 1 block
    over the porch row. The gable of the back wall is stone bricks."""
    first = CLIFF_STOREY * storeys + 1
    last = CLIFF_BODY - 1
    for step in range(CLIFF_BODY // 2 + 1):
        y = first + step
        left, right = step, last - step
        for z in range(D):
            if left < right:
                t.set(left, y, z, "minecraft:dark_oak_stairs", stairs("east"))
                t.set(right, y, z, "minecraft:dark_oak_stairs", stairs("west"))
            elif left == right:
                t.set(left, y, z, "minecraft:dark_oak_slab", {"type": "bottom", "waterlogged": "false"})
        for x in range(left + 1, right):
            t.set(x, y, 1, "minecraft:calcite")
            t.set(x, y, D - 1, CLIFF_BACK)


def cliff_house(storeys):
    """A cliff house of 2 or 3 storeys (see CLIFF_STOREY): CLIFF_BEDS beds on each storey, the house sign on the
    porch, and a door on each storey."""
    rng = random.Random(40 + storeys)
    height = CLIFF_STOREY * storeys + CLIFF_BODY // 2 + 2
    t = Template(W, height, D)
    t.fill(0, 1, 0, W - 1, height - 1, D - 1, "minecraft:air")
    last = CLIFF_BODY - 1
    # The porch, the floor, and the base of the stair.
    for x in range(W):
        for z in range(D):
            if x >= CLIFF_STAIR:
                t.set(x, 0, z, "minecraft:cobblestone")
            elif z == 0:
                t.set(x, 0, z, "minecraft:cobblestone" if x in (0, last) else "minecraft:spruce_planks")
            else:
                edge = x in (0, last) or z in (1, D - 1)
                t.set(x, 0, z, cobble(rng) if edge else "minecraft:spruce_planks")
    for storey in range(storeys):
        cliff_walls(t, rng, storey)
    # The doors: the front door of the ground floor, and a door in the east wall of each upper storey.
    mid = last // 2
    door(t, mid, 1, 1, "north")
    t.set(mid, 3, 1, "minecraft:stripped_spruce_log", {"axis": "x"})
    for storey in range(1, storeys):
        y = CLIFF_STOREY * storey + 1
        door(t, last, y, cliff_door_row(storey), "east")
        t.set(last, y + 2, cliff_door_row(storey), "minecraft:stripped_spruce_log", {"axis": "z"})
    for storey in range(storeys):
        cliff_rooms(t, storey)
    # The porch: a post at the west end, a roof of slabs at the level of the first beam, 2 torches and the house sign.
    for y in range(1, CLIFF_STOREY):
        t.set(0, y, 0, "minecraft:spruce_fence")
    for x in range(CLIFF_BODY):
        t.set(x, CLIFF_STOREY, 0, *SLAB_ROW)
    for x in (1, last - 1):
        t.set(x, 3, 0, *wall_torch("north"))
    building_sign(t, last - 1, 1, 0, "house")
    cliff_stair(t, storeys)
    cliff_roof(t, storeys)
    t.jigsaw(W // 2, 1, 0, "north_up", HOUSE_FRONT, "minecraft:empty", "minecraft:empty", "minecraft:air")
    return t


def check_cliff_house(name, t, storeys):
    """Stops the script when a cliff house has not the beds of its storeys, has an opening in its back wall, or has a
    door, a work block or a bed with no walk from the porch."""
    if bed_count(t) != CLIFF_BEDS * storeys:
        raise SystemExit(f"{name} has {bed_count(t)} beds, not {CLIFF_BEDS * storeys}")
    for x in range(CLIFF_BODY):
        for y in range(1, CLIFF_STOREY * storeys + 1):
            back = state_at(t, x, y, D - 1)[0]
            if back != CLIFF_BACK:
                raise SystemExit(f"{name} has {back} in the back wall at {x} {y}")
    problems = unreachable(t)
    reached = reach(t)
    doors = sorted(pos for pos, ((block, props), _) in t.blocks.items()
                   if block.endswith("_door") and dict(props).get("half") == "lower")
    if len(doors) != storeys:
        problems.append(f"{len(doors)} doors")
    problems += [f"door {pos}" for pos in doors if not near(reached, *pos)]
    if problems:
        raise SystemExit(f"{name}: no walk to {problems}")


CLIFF_PIECES = {f"cliff_house_{storeys}": functools.partial(cliff_house, storeys) for storeys in CLIFF_STOREYS}


def check_cliff_houses():
    for storeys in CLIFF_STOREYS:
        name = f"cliff_house_{storeys}"
        check_cliff_house(name, CLIFF_PIECES[name](), storeys)


PIECES = {
    "plaza": plaza,
    "street": street,
    "street_bank": lambda: street(bank=True),
    "street_end": street_end,
    "street_next": lambda: street(next_street=True),
    "street_cross": lambda: street_cross(W),
    "street_side": lambda: street_side(D),
    "tower": tower_piece,
    "gate": gate_piece,
    "stone/tower": stone_tower_piece,
    "stone/gate": stone_gate_piece,
    "house_cottage": cottage,
    "house_barracks": barracks,
    "house_forge": forge,
    "house_storehouse": storehouse,
    "bank": bank,
    "market": functools.partial(market, "wide"),
    **{f"job_{job}": build for job, build in JOBS.items()},
    **{f"field_{size}": functools.partial(field, LAND, size) for size in FIELDS},
    "mill": functools.partial(windmill, LAND),
    **CLIFF_PIECES,
}


# The styles of the cultures: the pieces that a culture changes. The other pieces are the pieces of PIECES. The
# villages of a culture with no style here use PIECES.
STYLES = {
    "valeholm": {
        "street": norse_street,
        "street_bank": lambda: norse_street(bank=True),
        "street_next": lambda: norse_street(next_street=True),
        "street_cross": lambda: street_cross(SL),
        "street_side": lambda: street_side(LD),
        "house_cottage": norse_cottage,
        "house_cottage_side": norse_cottage_side,
        "house_barracks": norse_barracks,
        "house_forge": norse_forge,
        "house_storehouse": norse_storehouse,
        "house_storehouse_side": norse_storehouse_side,
        "market": functools.partial(market, "deep"),
        **{f"job_{job}": build for job, build in NORSE_JOBS.items()},
    },
    "sunreach": {
        "street_cross": lambda: street_cross(CW),
        "street_side": lambda: street_side(CD),
        "house_cottage": sun_cottage,
        "house_cottage_courtyard": sun_cottage_courtyard,
        "house_barracks": sun_barracks,
        "house_forge": sun_forge,
        "house_storehouse": sun_storehouse,
        **{f"job_{job}": build for job, build in SUNREACH_JOBS.items()},
    },
}
# The pieces of the maritime villages: the village of a site on the coast or on an island, in all the cultures. The
# other pieces are the pieces of PIECES.
MARITIME = {
    "house_fishing_hut": fishing_hut,
    "house_fish_market": fish_market,
    "house_smokehouse": smokehouse,
}
# The pieces of the pool "houses" of the maritime villages, with their weights: the buildings of the fishing port
# (MARITIME) and the houses of HOUSES. Each piece of MARITIME and of HOUSES is in this pool, thus each template that the
# script writes for a maritime variant is in a pool (VillageTemplateGameTests.eachVillageTemplateIsInAPool).
MARITIME_HOUSES = [(3, "house_fishing_hut"), (1, "house_fish_market"), (1, "house_smokehouse"),
                   (2, "house_cottage"), (1, "house_barracks"), (1, "house_forge"), (1, "house_storehouse")]
# The blocks of Immersive Weathering in the maritime pieces, when the mod is present (see OptionalReplaceProcessor).
WEATHERED = {
    "minecraft:stone": "immersive_weathering:mossy_stone",
    "minecraft:stone_slab": "immersive_weathering:mossy_stone_slab",
    "minecraft:iron_bars": "immersive_weathering:rusted_iron_bars",
}

# The houses of the pool of the houses of each style, with their weights. The jigsaw tries the houses in a random
# order with these weights: a house that touches another piece goes to the next house, and a plot with no house that
# fits stays free. The styles that are not here use HOUSES.
HOUSES = [(3, "house_cottage"), (2, "house_barracks"), (1, "house_forge"), (2, "house_storehouse")]
STYLE_HOUSES = {
    "valeholm": [(2, "house_cottage"), (2, "house_cottage_side"), (2, "house_barracks"), (1, "house_forge"),
                 (1, "house_storehouse"), (1, "house_storehouse_side")],
    "sunreach": [(3, "house_cottage"), (2, "house_cottage_courtyard"), (2, "house_barracks"), (1, "house_forge"),
                 (2, "house_storehouse")],
}

# The pieces of the GameTests.
TEST_PIECES = {
    "center": center,
}


# ---------------------------------------------------------------- cave village

# The pieces of the cave village (folder village/cave): a village in the rock of a mountain. The main hall is the plaza
# with the village heart, the tunnels are the streets and the carved rooms are the buildings on the plots. The cave
# pieces have one style for all the cultures and all the biomes.
#
# A carved piece has 3 kinds of positions:
# - air: the generation removes the rock there (the carved space).
# - the shell (ROCK_SHELL): each position next to the carved space where the piece has no block. The processor
#   folkhold_world:rock_shell (list village/cave/rock) gives each one the rock of the place: a dressed block for the
#   rock of the world (cobblestone for stone, polished granite for granite, see DRESSED_ROCK), the block of the world
#   for another solid block (an ore), and cobblestone (cobbled deepslate under Y 0) for a hole: air, water or lava. The
#   shell is not a block of the piece for the builders and the repairs (ConstructionTargets skips the structure voids).
#   The springs of the game do not open in a dressed block: no spring of water or lava opens in a wall of a hall.
# - no block: the world stays.
#
# Each block on a floor of the carved space has a block light of 1 or more: no monster spawns in the dark (see
# cave_lit). The lanterns hang from blocks of the piece, not from the shell: the builders need a block to hold them.
ROCK_SHELL = "minecraft:structure_void"
# The dressed blocks of the rocks of the world in the shell. The other solid blocks stay (see RockShellProcessor).
DRESSED_ROCK = {
    "minecraft:stone": "minecraft:cobblestone",
    "minecraft:deepslate": "minecraft:cobbled_deepslate",
    "minecraft:granite": "minecraft:polished_granite",
    "minecraft:diorite": "minecraft:polished_diorite",
    "minecraft:andesite": "minecraft:polished_andesite",
    "minecraft:tuff": "minecraft:polished_tuff",
    "minecraft:calcite": "minecraft:polished_diorite",
    "minecraft:dirt": "minecraft:coarse_dirt",
    "minecraft:snow_block": "minecraft:cobblestone",
    "minecraft:packed_ice": "minecraft:cobblestone",
}
CAVE_ROCK = "minecraft:cobblestone"
CAVE_DEEP_ROCK = "minecraft:cobbled_deepslate"
# The height of the main hall (CaveSite.HALL): the air is y = 1..CAVE_HALL, and the shell is at y = CAVE_HALL + 1.
CAVE_HALL = 6
# The height of a tunnel: the air is y = 1..TUNNEL_HEIGHT.
TUNNEL_HEIGHT = 4
# The carved height of the front row of a room (z = 0) and of an open part of a room, in blocks above the floor. The
# opening of a plot in the wall of a tunnel has this height.
CAVE_PORCH = 3
# The length of the tunnel of the entrance, of the gate piece and the width of the gate piece.
TUNNEL_LENGTH = 6
CAVE_GATE_WIDTH = 9
CAVE_GATE_DEPTH = 4
CAVE_GATE_HEIGHT = 7
# The block that holds a hanging lantern under the shell of a ceiling.
LANTERN_HOLDER = "minecraft:chiseled_stone_bricks"
TUNNELS_POOL = f"{NS}:village/tunnels"

# The light of the blocks that give light (Block.lightEmission): the blocks of the pieces.
LIGHTS = {"minecraft:lantern": 15, "minecraft:torch": 14, "minecraft:wall_torch": 14, "minecraft:soul_lantern": 10,
          "minecraft:glowstone": 15, "minecraft:sea_lantern": 15, "minecraft:jack_o_lantern": 15}
# The blocks that stop the light in the check of the pieces, with the full blocks: the slabs and the stairs stop it on
# some faces, and the check takes no risk.
DARK_BLOCKS = ("minecraft:farmland", "minecraft:dirt_path")


def cave_state(t, x, y, z):
    """The block of a position for the light and the spawns: the shell and a position with no block are the rock of
    the world, a full block."""
    entry = t.blocks.get((x, y, z))
    if not entry or entry[0][0] == ROCK_SHELL:
        return "minecraft:stone", {}
    return state_at(t, x, y, z)


def lets_light(state):
    name, props = state
    return (collision(name, props) != FULL and name not in DARK_BLOCKS and not name.endswith("_slab")
            and not name.endswith("_stairs"))


def light_of(state):
    name, props = state
    if name.endswith("campfire") and props.get("lit") == "true":
        return 15
    return LIGHTS.get(name, 0)


def block_light(t):
    """The block light at each position of a template, from the lights of the template only (a flood fill of 1 level
    for each block)."""
    sx, sy, sz = t.size
    light = {}
    queue = []
    for (x, y, z) in t.blocks:
        level = light_of(cave_state(t, x, y, z))
        if level > 0:
            light[(x, y, z)] = level
            queue.append((x, y, z))
    while queue:
        nxt = []
        for (x, y, z) in queue:
            level = light[(x, y, z)] - 1
            if level <= 0:
                continue
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                n = (x + dx, y + dy, z + dz)
                if not t.inside(*n) or light.get(n, 0) >= level or not lets_light(cave_state(t, *n)):
                    continue
                light[n] = level
                nxt.append(n)
        queue = nxt
    return light


def top_is_full(state):
    """True if the top face of a block is full: a monster can stand on it."""
    cells = set()
    for x1, _, z1, x2, y2, z2 in collision(*state):
        if y2 >= 16:
            cells.update((x, z) for x in range(x1, x2) for z in range(z1, z2))
    return len(cells) == 16 * 16


def spawn_places(t):
    """The positions of the template where a monster can spawn: a free position (air or a block with no collision)
    in the piece, on a block with a full top face. The shell and the positions with no block are rock."""
    places = []
    for (x, y, z), ((name, _), _) in t.blocks.items():
        if y == 0 or name == ROCK_SHELL:
            continue
        state = cave_state(t, x, y, z)
        if collision(*state) == () and not light_of(state) and top_is_full(cave_state(t, x, y - 1, z)):
            places.append((x, y, z))
    return sorted(places)


def hanging_lantern(t, x, y, z):
    """A lantern that hangs from the block above it. A shell or a position with no block above gets LANTERN_HOLDER."""
    entry = t.blocks.get((x, y + 1, z))
    if not entry or entry[0][0] == ROCK_SHELL:
        t.set(x, y + 1, z, LANTERN_HOLDER)
    t.set(x, y, z, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})


def lantern_place(t, x, y, z):
    """The place of a hanging lantern for a dark position: the highest free position of the column at 2 blocks or more
    above the position, with no block between them, in the column or in a column next to it. None if there is
    none."""
    sx, sy, sz = t.size
    for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)):
        cx, cz = x + dx, z + dz
        if not t.inside(cx, y, cz) or (cx, y, cz) not in t.blocks or collision(*cave_state(t, cx, y, cz)) != ():
            continue
        best = None
        for cy in range(y + 1, sy - 1):
            if (cx, cy, cz) not in t.blocks or cave_state(t, cx, cy, cz)[0] != "minecraft:air":
                break
            above = t.blocks.get((cx, cy + 1, cz))
            if cy >= y + 2 and (not above or above[0][0] == ROCK_SHELL or is_sturdy(cave_state(t, cx, cy + 1, cz),
                                                                                  "down")):
                best = cy
        if best is not None:
            return cx, best, cz
    return None


def cave_lit(t, name):
    """Adds hanging lanterns until each spawn place of the template has a block light of 1 or more (see
    spawn_places). Stops the script when a dark place has no place for a lantern."""
    while True:
        light = block_light(t)
        dark = [p for p in spawn_places(t) if light.get(p, 0) < 1]
        if not dark:
            return t
        place = lantern_place(t, *dark[0])
        if place is None:
            raise SystemExit(f"The cave piece {name} has a dark place with no place for a lantern: {dark[0]}")
        hanging_lantern(t, *place)


def rock_shell(t, carved):
    """Adds the shell around the open positions: each position with no block next to an open position, in the
    template. An open position is a carved position, or a block with no collision (a torch, a sign, a crop): a mob or
    the water goes through it."""
    open_places = set(carved) | {pos for pos, ((name, props), _) in t.blocks.items()
                                 if name != ROCK_SHELL and collision(*state_at(t, *pos)) == ()}
    for (x, y, z) in open_places:
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            n = (x + dx, y + dy, z + dz)
            if t.inside(*n) and n not in open_places and n not in t.blocks:
                t.set(*n, ROCK_SHELL)
    return t


def is_free(t, x, y, z):
    return state_at(t, x, y, z)[0] == "minecraft:air"


def carve(t):
    """The carved room of a building template (a house, a house-workshop, the bank, the market): the blocks of the
    building, the carved air around them and the shell.

    - The carved air: each air position of the template under the highest block of its column, or CAVE_PORCH blocks
      or less above the floor (a yard, the square of the market). The front row (z = 0) has CAVE_PORCH blocks of air:
      the height of the opening of the plot in the wall of the tunnel.
    - The sides (x = 0, the last x), the back (the last z) and the top layer have no carved air: the shell there
      closes the room. The front row opens on the tunnel.
    - The other air positions of the template have no block: the rock of the world stays above the roof.
    """
    sx, sy, sz = t.size
    out = Template(sx, sy, sz)
    out.fixed = set(t.fixed)
    carved = set()
    for x in range(sx):
        for z in range(sz):
            top = max((y for y in range(sy) if not is_free(t, x, y, z)), default=0)
            limit = CAVE_PORCH if z == 0 else max(top - 1, CAVE_PORCH)
            for y in range(1, min(limit, sy - 2) + 1):
                if is_free(t, x, y, z) and x not in (0, sx - 1) and z != sz - 1:
                    carved.add((x, y, z))
    for pos, entry in t.blocks.items():
        if entry[0][0] != "minecraft:air":
            out.blocks[pos] = entry
    for pos in carved:
        if pos not in out.blocks:
            out.set(*pos, "minecraft:air")
    return rock_shell(out, carved)


def carved(t):
    """The carved positions of a hand-made cave piece: its air and its jigsaw blocks with air as the final state."""
    return {pos for pos in t.blocks if is_free(t, *pos) and t.blocks[pos][0][0] != ROCK_SHELL}


def cave_floor(rng):
    roll = rng.random()
    if roll < 0.15:
        return "minecraft:cracked_stone_bricks"
    if roll < 0.35:
        return "minecraft:cobblestone"
    return "minecraft:stone_bricks"


def cave_hall():
    """The main hall: the plaza of the cave village, with the village heart, the braziers, the alarm bell, the hall sign,
    the quest board and the lanterns, as the plaza. The hall is PLAZA x PLAZA blocks and CAVE_HALL blocks high. An
    opening of a tunnel is in the middle of each side: the tunnel of the entrance to the west, the streets of the plan
    to the north, the south and the east."""
    rng = random.Random(91)
    t = Template(PLAZA, CAVE_HALL + 2, PLAZA)
    mid, last = PLAZA_MID, PLAZA - 1
    lo, hi = 2, PLAZA - 3
    for x in range(PLAZA):
        for z in range(PLAZA):
            if lo <= x <= hi and lo <= z <= hi:
                border = x in (lo, hi) or z in (lo, hi)
                t.set(x, 0, z, "minecraft:stone_bricks" if border else cobble(rng))
            elif abs(x - mid) <= 1 or abs(z - mid) <= 1:
                t.set(x, 0, z, cave_floor(rng))
            else:
                t.set(x, 0, z, "minecraft:polished_andesite")
    t.fill(1, 1, 1, last - 1, CAVE_HALL, last - 1, "minecraft:air")
    for a in range(mid - 1, mid + 2):
        for y in range(1, TUNNEL_HEIGHT + 1):
            for x, z in ((a, 0), (a, last), (0, a), (last, a)):
                t.set(x, y, z, "minecraft:air")
    t.set(mid, 0, mid, "minecraft:chiseled_stone_bricks")
    t.set(mid, 1, mid, HEART)
    t.set(mid - 4, 0, mid - 2, "minecraft:stone_bricks")
    t.set(mid - 4, 1, mid - 2, "minecraft:bell", {"attachment": "floor", "facing": "north", "powered": "false"})
    for dx in (-3, 3):
        for dz in (-3, 3):
            t.set(mid + dx, 0, mid + dz, "minecraft:stone_bricks")
            t.set(mid + dx, 1, mid + dz, f"{BLOCKS}:brazier", {"lit": "false"})
    building_sign(t, mid + 2, 1, mid - 4, "hall")
    for column in range(3):
        for row in range(3):
            t.set(hi, 1 + row, hi - column, f"{BLOCKS}:quest_board",
                  {"facing": "west", "column": str(column), "row": str(row), "papers": "0"})
    for (x, z) in ((1, 1), (last - 1, 1), (1, last - 1)):
        lamp_post(t, x, z)
    for (x, z) in ((mid, 3), (mid, last - 3), (3, mid), (last - 3, mid)):
        hanging_lantern(t, x, CAVE_HALL, z)
    t.jigsaw(mid, 0, 0, "north_up", PLAZA_EXIT, STREET, BANK_STREETS_POOL, "minecraft:stone_bricks")
    t.jigsaw(mid, 0, last, "south_up", PLAZA_EXIT, STREET, FIRST_STREETS_POOL, "minecraft:stone_bricks")
    t.jigsaw(0, 0, mid, "west_up", PLAZA_EXIT, STREET, TUNNELS_POOL, "minecraft:stone_bricks")
    t.jigsaw(last, 0, mid, "east_up", PLAZA_EXIT, STREET, FIRST_STREETS_POOL, "minecraft:stone_bricks")
    return rock_shell(t, carved(t))


def cave_tunnel(length, rng, plots=(), exits=(), closed=False):
    """A tunnel along z: STREET_WIDTH blocks wide, with a floor, the air of 3 blocks between 2 walls of shell, and the
    shell of the ceiling at y = TUNNEL_HEIGHT + 1.

    - plots: the z of each plot. The wall on each side has an opening of the porch of a room there: W - 2 blocks wide
      and CAVE_PORCH blocks high.
    - exits: the first z of each side exit. The walls have an opening of 3 blocks there, with the height of the tunnel.
    - closed: the last row (z = length - 1) is a wall of shell: the end of a street.
    """
    t = Template(STREET_WIDTH, TUNNEL_HEIGHT + 2, length)
    last = STREET_WIDTH - 1
    for z in range(length):
        for x in range(STREET_WIDTH):
            t.set(x, 0, z, cave_floor(rng) if 0 < x < last else "minecraft:stone_bricks")
    t.fill(1, 1, 0, last - 1, TUNNEL_HEIGHT, length - (2 if closed else 1), "minecraft:air")
    half = (W - 2) // 2
    for plot in plots:
        for z in range(plot - half, plot + half + 1):
            for x in (0, last):
                for y in range(1, CAVE_PORCH + 1):
                    t.set(x, y, z, "minecraft:air")
    for first in exits:
        for z in range(first, first + 3):
            for x in (0, last):
                for y in range(1, TUNNEL_HEIGHT + 1):
                    t.set(x, y, z, "minecraft:air")
    return t


def cave_lanterns(t, *rows):
    for z in rows:
        hanging_lantern(t, STREET_WIDTH // 2, TUNNEL_HEIGHT, z)


def cave_street(bank=False, next_street=False):
    """A tunnel segment with a plot on each side: the tunnel of street (see street). The plan places the rooms on the
    plots."""
    rng = random.Random(92 if bank else 93)
    plot_z = STREET_LENGTH - 1 - HOUSE_HALF_WIDTH
    t = cave_tunnel(STREET_LENGTH, rng, plots=(plot_z,))
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:stone_bricks")
    t.jigsaw(mid, 0, STREET_LENGTH - 1, "south_up", STREET_NEXT, STREET,
             NEXT_STREETS_POOL if next_street else STREET_ENDS_POOL, "minecraft:stone_bricks")
    t.jigsaw(0, 1, plot_z, "west_up", PLOT, HOUSE_FRONT, BANK_POOL if bank else HOUSE_POOL, "minecraft:air")
    t.jigsaw(STREET_WIDTH - 1, 1, plot_z, "east_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
    cave_lanterns(t, 3, STREET_LENGTH - 4)
    return rock_shell(t, carved(t))


def cave_street_cross():
    """A crossroad of tunnels: the tunnel of street_cross, with a side exit to the west and to the east and a plot in
    each corner."""
    frontage = W
    arm = frontage + PLOT_MARGIN
    length = 2 * arm + STREET_WIDTH
    rng = random.Random(94)
    plots = (arm - 1 - frontage // 2, arm + STREET_WIDTH + frontage // 2)
    t = cave_tunnel(length, rng, plots=plots, exits=(arm + 1,))
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:stone_bricks")
    t.jigsaw(mid, 0, length - 1, "south_up", STREET_NEXT, STREET, NEXT_STREETS_POOL, "minecraft:stone_bricks")
    t.jigsaw(0, 0, arm + mid, "west_up", STREET_NEXT, STREET, SIDE_STREETS_POOL, "minecraft:stone_bricks")
    t.jigsaw(STREET_WIDTH - 1, 0, arm + mid, "east_up", STREET_NEXT, STREET, SIDE_STREETS_POOL,
             "minecraft:stone_bricks")
    for z in plots:
        t.jigsaw(0, 1, z, "west_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
        t.jigsaw(STREET_WIDTH - 1, 1, z, "east_up", PLOT, HOUSE_FRONT, HOUSE_POOL, "minecraft:air")
    cave_lanterns(t, 4, arm + mid, length - 5)
    return rock_shell(t, carved(t))


def cave_street_side():
    """The start of a side tunnel, from a side of a crossroad: the tunnel of street_side, with no plot."""
    rng = random.Random(95)
    length = D + PLOT_MARGIN
    t = cave_tunnel(length, rng)
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:stone_bricks")
    t.jigsaw(mid, 0, length - 1, "south_up", STREET_NEXT, STREET, NEXT_STREETS_POOL, "minecraft:stone_bricks")
    cave_lanterns(t, length // 2)
    return rock_shell(t, carved(t))


def cave_street_end():
    """The end of a tunnel: STREET_END_LENGTH blocks, closed by a wall of shell."""
    rng = random.Random(96)
    t = cave_tunnel(STREET_END_LENGTH, rng, closed=True)
    t.jigsaw(STREET_WIDTH // 2, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty",
             "minecraft:stone_bricks")
    cave_lanterns(t, 1)
    return rock_shell(t, carved(t))


def cave_entrance_tunnel():
    """A segment of the tunnel of the entrance: TUNNEL_LENGTH blocks with no plot, from the gate to the main hall."""
    rng = random.Random(97)
    t = cave_tunnel(TUNNEL_LENGTH, rng)
    mid = STREET_WIDTH // 2
    t.jigsaw(mid, 0, 0, "north_up", STREET, "minecraft:empty", "minecraft:empty", "minecraft:stone_bricks")
    t.jigsaw(mid, 0, TUNNEL_LENGTH - 1, "south_up", STREET_NEXT, STREET, TUNNELS_POOL, "minecraft:stone_bricks")
    cave_lanterns(t, TUNNEL_LENGTH // 2)
    return rock_shell(t, carved(t))


def cave_gate():
    """The entrance gate of the cave village, in the rock face. The outside is to the north (z = 0).

    - z = 0: the porch, a niche of 7 x 4 blocks in the rock face with a lamp post on each side.
    - z = 1: the facade of stone bricks, with the gate of 3 fence gates in the middle and the banner above it. The
      fence gates are the gate of the village for the raids, as the gate of a palisade.
    - z = 2..3: the start of the tunnel to the main hall: the connector at the back targets the tunnels.
    """
    rng = random.Random(98)
    t = Template(CAVE_GATE_WIDTH, CAVE_GATE_HEIGHT, CAVE_GATE_DEPTH)
    mid = CAVE_GATE_WIDTH // 2
    last = CAVE_GATE_WIDTH - 1
    for x in range(CAVE_GATE_WIDTH):
        t.set(x, 0, 0, "minecraft:stone_bricks")
    t.fill(1, 1, 0, last - 1, TUNNEL_HEIGHT, 0, "minecraft:air")
    for x in (1, last - 1):
        lamp_post(t, x, 0)
    for x in range(1, last):
        for y in range(0, CAVE_GATE_HEIGHT - 1):
            corner = x in (1, last - 1) or y == CAVE_GATE_HEIGHT - 2
            t.set(x, y, 1, "minecraft:chiseled_stone_bricks" if corner and y % 2 == 1 else "minecraft:stone_bricks")
    for x in range(mid - 1, mid + 2):
        t.set(x, 0, 1, "minecraft:stone_bricks")
        t.set(x, 1, 1, "minecraft:spruce_fence_gate",
              {"facing": "north", "in_wall": "false", "open": "false", "powered": "false"})
        for y in (2, 3):
            t.set(x, y, 1, "minecraft:air")
    t.set(mid, 4, 0, WALL_BANNER, {"facing": "north"})
    for z in (2, 3):
        for x in range(mid - 2, mid + 3):
            t.set(x, 0, z, cave_floor(rng) if abs(x - mid) <= 1 else "minecraft:stone_bricks")
        t.fill(mid - 1, 1, z, mid + 1, TUNNEL_HEIGHT, z, "minecraft:air")
    hanging_lantern(t, mid, TUNNEL_HEIGHT, 2)
    t.jigsaw(mid, 0, CAVE_GATE_DEPTH - 1, "south_up", STREET_NEXT, STREET, TUNNELS_POOL, "minecraft:stone_bricks")
    return rock_shell(t, carved(t))


def cave_room(build):
    """The carved room of a building of PIECES (see carve)."""
    return carve(build())


# The pieces of the cave village: the hand-made pieces, and a carved room for each building of PIECES (the houses, the
# bank, the market and the house-workshops).
CAVE_PIECES = {
    "plaza": cave_hall,
    "street": cave_street,
    "street_bank": lambda: cave_street(bank=True),
    "street_end": cave_street_end,
    "street_next": lambda: cave_street(next_street=True),
    "street_cross": cave_street_cross,
    "street_side": cave_street_side,
    "tunnel": cave_entrance_tunnel,
    "gate": cave_gate,
    **{name: functools.partial(cave_room, PIECES[name])
       for name in ("house_cottage", "house_barracks", "house_forge", "house_storehouse", "bank", "market")},
    **{f"job_{job}": functools.partial(cave_room, PIECES[f"job_{job}"]) for job in JOBS},
}


# ---------------------------------------------------------------- biome variants

# The blocks of a wood that the pieces use.
WOOD_BLOCKS = ("planks", "log", "fence", "fence_gate", "door", "slab", "stairs")


def wood(old, new):
    """The replacements of the blocks of a wood by the blocks of another wood: planks, logs, stripped logs, fences,
    fence gates, doors, slabs and stairs."""
    blocks = {f"minecraft:{old}_{kind}": f"minecraft:{new}_{kind}" for kind in WOOD_BLOCKS}
    blocks[f"minecraft:stripped_{old}_log"] = f"minecraft:stripped_{new}_log"
    return blocks


def plain(name):
    """The replacement by a block with no properties, for example a log (axis) by a stone."""
    return name, ()


# The desert: sandstone for the wood and the stones of the base variant, and jungle wood for the doors and the fence
# gates. A sandstone wall replaces a fence.
DESERT = {
    "minecraft:spruce_planks": "minecraft:smooth_sandstone",
    "minecraft:spruce_log": plain("minecraft:cut_sandstone"),
    "minecraft:stripped_spruce_log": plain("minecraft:cut_sandstone"),
    "minecraft:spruce_slab": "minecraft:smooth_sandstone_slab",
    "minecraft:spruce_stairs": "minecraft:smooth_sandstone_stairs",
    "minecraft:spruce_fence": plain("minecraft:sandstone_wall"),
    "minecraft:spruce_fence_gate": "minecraft:jungle_fence_gate",
    "minecraft:spruce_door": "minecraft:jungle_door",
    "minecraft:cobblestone": "minecraft:sandstone",
    "minecraft:mossy_cobblestone": "minecraft:sandstone",
    "minecraft:stone_bricks": "minecraft:cut_sandstone",
    "minecraft:chiseled_stone_bricks": "minecraft:chiseled_sandstone",
    "minecraft:cobblestone_stairs": "minecraft:sandstone_stairs",
    "minecraft:mossy_cobblestone_stairs": "minecraft:sandstone_stairs",
    "minecraft:stone_brick_stairs": "minecraft:sandstone_stairs",
    "minecraft:stone_brick_slab": "minecraft:cut_sandstone_slab",
    "minecraft:stone_brick_wall": "minecraft:sandstone_wall",
}
# The swamp: mangrove wood, and mud bricks for the stones of the base variant.
SWAMP = {
    **wood("spruce", "mangrove"),
    "minecraft:cobblestone": "minecraft:mud_bricks",
    "minecraft:mossy_cobblestone": "minecraft:packed_mud",
    "minecraft:stone_bricks": "minecraft:mud_bricks",
    "minecraft:cobblestone_stairs": "minecraft:mud_brick_stairs",
    "minecraft:mossy_cobblestone_stairs": "minecraft:mud_brick_stairs",
    "minecraft:stone_brick_stairs": "minecraft:mud_brick_stairs",
    "minecraft:stone_brick_slab": "minecraft:mud_brick_slab",
    "minecraft:stone_brick_wall": "minecraft:mud_brick_wall",
}


class Variant:
    """A biome variant of the village: the same pieces, with other materials.

    The base variant has no replacements: its pieces, pools and processor lists are in the folders of the village. The
    files of another variant are in a subfolder with the name of the variant. The jigsaw connectors of its pieces use
    its pools.

    A replacement is the name of a block with the same properties, or a pair (name, properties) with other properties
    (see plain).
    """

    def __init__(self, name, biomes, blocks, culture=None, maritime=False, cave=False):
        self.name = name
        self.biomes = biomes
        self.blocks = blocks
        self.culture = culture
        self.maritime = maritime
        self.cave = cave

    @property
    def base(self):
        return not self.blocks

    def of(self, culture):
        """The same variant for the style of a culture."""
        return Variant(self.name, self.biomes, self.blocks, culture)

    def at_sea(self):
        """The same variant for a maritime village."""
        return Variant(self.name, self.biomes, self.blocks, maritime=True)

    @property
    def pieces(self):
        """The pieces of the variant: the pieces of PIECES, with the pieces of its style. The cave variant has the cave
        pieces only (see CAVE_PIECES)."""
        if self.cave:
            return CAVE_PIECES
        return {**PIECES, **STEP_PIECES[self.culture], **(MARITIME if self.maritime else STYLES.get(self.culture, {}))}

    def path(self, name):
        """The path of a file of the village for this variant, with no namespace and no extension. The files of the
        style of a culture are in a folder with the name of the culture, and the files of a maritime village are in the
        folder "maritime". The files of the cave village are in the folder "cave"."""
        parts = ["village"]
        if self.culture:
            parts.append(self.culture)
        if self.maritime:
            parts.append("maritime")
        if self.cave:
            parts.append("cave")
        if not self.base:
            parts.append(self.name)
        return "/".join(parts + [name])

    def id(self, name):
        """The id of a file of the village for this variant: a piece, a pool or a processor list."""
        return f"{NS}:{self.path(name)}"

    def replace(self, name, props):
        """The name and the properties of a block in this variant."""
        new = self.blocks.get(name, name)
        return new if isinstance(new, tuple) else (new, props)

    def block(self, name):
        return self.replace(name, None)[0]

    def state(self, name, props=None):
        """A block state as in the JSON files of the game."""
        name, props = self.replace(name, props)
        state = {"Name": name}
        if props:
            state["Properties"] = props
        return state

    def apply(self, t):
        """The template with the materials of this variant. The jigsaw connectors use the pools of the variant. The
        fixed positions of the template (see job_material) keep their blocks. Each fence, wall and pane gets the state
        that the game gives it from its neighbors after the block replacement (see connect)."""
        out = Template(*t.size)
        prefix = f"{NS}:village/"
        for pos, ((name, props), nbt) in t.blocks.items():
            if nbt is not None and nbt.get("id") == "minecraft:jigsaw" and nbt["pool"].startswith(prefix):
                nbt = dict(nbt, pool=self.id(nbt["pool"][len(prefix):]))
            out.blocks[pos] = ((name, props) if pos in t.fixed else self.replace(name, props), nbt)
        return connect(out)


# The variants of the village, with their biomes. The tag of the biomes of the village has the biomes of all the
# variants. In a biome of the tag that no other variant has, the village uses the base variant.
BASE = Variant("taiga", ["minecraft:taiga", "minecraft:snowy_taiga", "minecraft:snowy_plains", "minecraft:windswept_hills",
                        "minecraft:windswept_forest", "minecraft:windswept_gravelly_hills", "minecraft:grove",
                        "minecraft:snowy_slopes"], {})
VARIANTS = (
    BASE,
    Variant("plains", ["minecraft:plains", "minecraft:sunflower_plains", "minecraft:meadow",
                       "minecraft:cherry_grove", "minecraft:forest"],
            wood("spruce", "oak")),
    Variant("birch", ["minecraft:birch_forest"], wood("spruce", "birch")),
    Variant("savanna", ["minecraft:savanna"], wood("spruce", "acacia")),
    Variant("desert", ["minecraft:desert"], DESERT),
    Variant("swamp", ["minecraft:swamp", "minecraft:mangrove_swamp"], SWAMP),
)

# The cave village: the cave pieces, in all the biomes and the cultures (see CAVE_PIECES).
CAVE = Variant("cave", [], {}, cave=True)

LOG_UP = {"axis": "y"}


def element(variant, piece, projection, processors):
    return {
        "element_type": "minecraft:single_pool_element",
        "location": variant.id(piece),
        "projection": projection,
        "processors": variant.id(processors),
    }


def pool(elements, fallback="minecraft:empty"):
    return {"fallback": fallback, "elements": [{"weight": weight, "element": e} for weight, e in elements]}


def pools(v):
    """The template pools of a variant, by name."""
    if v.cave:
        return cave_pools(v)

    def house(piece):
        return element(v, piece, "rigid", "foundation")

    def street(piece):
        return element(v, piece, "terrain_matching", "street")

    houses = MARITIME_HOUSES if v.maritime else STYLE_HOUSES.get(v.culture, HOUSES)

    return {
        "plaza": pool([(1, house("plaza"))]),
        "houses": pool([(weight, house(piece)) for weight, piece in houses]
                       + [(1, {"element_type": "minecraft:empty_pool_element"})]),
        "homes": pool([(1, house("house_cottage"))]),
        "bank": pool([(1, house("bank"))]),
        "jobs": pool([(1, house(f"job_{job}")) for job in JOBS]),
        **{f"jobs_step{step}": pool([(1, house(f"job_{job}_step{step}")) for job in HOME_STEP_JOBS[v.culture]])
           for step in HOME_STEPS},
        "market": pool([(1, house("market"))]),
        "cliff_houses": pool([(1, house(name)) for name in CLIFF_PIECES]),
        "fields": pool([(1, house(f"field_{size}")) for size in FIELDS]),
        "mills": pool([(1, house("mill"))]),
        "streets_first": pool([(1, street("street"))], v.id("street_ends")),
        "streets_bank": pool([(1, street("street_bank"))], v.id("street_ends")),
        "street_ends": pool([(1, street("street_end"))]),
        "streets_next": pool([(2, street("street_next")), (1, street("street_cross"))], v.id("street_ends")),
        "streets_side": pool([(1, street("street_side"))], v.id("street_ends")),
    }


def cave_pools(v):
    """The template pools of the cave village, by name: the pools of a variant, with the cave pieces. All the pieces
    are rigid, with the processor list rock (see ROCK_SHELL). The pools of the land (fields, mills) are empty: a cave
    village has no land outside a palisade. The pool cliff_houses is empty: the rooms of a cave village are carved
    rooms. The pool tunnels has the segments of the tunnel of the entrance, and the
    pool gates the entrance gate. The seal of a free plot is a piece of the structure (CaveSealPiece), with no
    template."""
    def piece(name):
        return element(v, name, "rigid", "rock")

    return {
        "plaza": pool([(1, piece("plaza"))]),
        "houses": pool([(weight, piece(name)) for weight, name in HOUSES]
                       + [(1, {"element_type": "minecraft:empty_pool_element"})]),
        "homes": pool([(1, piece("house_cottage"))]),
        "bank": pool([(1, piece("bank"))]),
        "jobs": pool([(1, piece(f"job_{job}")) for job in JOBS]),
        **{f"jobs_step{step}": pool([]) for step in HOME_STEPS},
        "market": pool([(1, piece("market"))]),
        "cliff_houses": pool([]),
        "fields": pool([]),
        "mills": pool([]),
        "streets_first": pool([(1, piece("street"))], v.id("street_ends")),
        "streets_bank": pool([(1, piece("street_bank"))], v.id("street_ends")),
        "street_ends": pool([(1, piece("street_end"))]),
        "streets_next": pool([(2, piece("street_next")), (1, piece("street_cross"))], v.id("street_ends")),
        "streets_side": pool([(1, piece("street_side"))], v.id("street_ends")),
        "tunnels": pool([(1, piece("tunnel"))], v.id("street_ends")),
        "gates": pool([(1, piece("gate"))]),
    }


def processor_lists(v):
    """The processor lists of a variant, by name: the foundation of the buildings, and the streets. The cave village
    has the list rock: the shell of the cave pieces (see ROCK_SHELL)."""
    if v.cave:
        return {"rock": {"processors": [{
            "processor_type": f"{NS}:rock_shell",
            "rock": {"Name": CAVE_ROCK},
            "deep_rock": {"Name": CAVE_DEEP_ROCK},
            "deep_below": 0,
            "replacements": [{"input": rock, "output": {"Name": dressed}} for rock, dressed in DRESSED_ROCK.items()],
        }]}}
    planks = v.state("minecraft:spruce_planks")
    street_rules = [{
        "input_predicate": {"predicate_type": "minecraft:block_match", "block": path},
        "location_predicate": {"predicate_type": "minecraft:block_match", "block": "minecraft:water"},
        "output_state": planks,
    } for path in ("minecraft:dirt_path", "minecraft:gravel", "minecraft:coarse_dirt")]
    weathered = [{
        "processor_type": f"{NS}:optional_replace",
        "replacements": [{"input": block, "output": out} for block, out in WEATHERED.items()],
    }] if v.maritime else []
    return {
        "foundation": {"processors": weathered + [{
            "processor_type": f"{NS}:foundation",
            "block": v.state("minecraft:cobblestone"),
            "max_depth": 32,
            "stilt": v.state("minecraft:spruce_log", LOG_UP),
        }]},
        "street": {"processors": [
            {"processor_type": "minecraft:gravity", "heightmap": "WORLD_SURFACE_WG", "offset": -1},
            {"processor_type": "minecraft:rule", "rules": street_rules},
        ]},
    }


# The stairs of the streets and of the paths on a slope, for each style: the stone of the culture. The maritime
# villages have the stone bricks of their quays. The desert and the swamp change the stone (see DESERT and SWAMP).
STREET_STAIRS = {"valeholm": "minecraft:mossy_cobblestone_stairs", "sunreach": "minecraft:sandstone_stairs"}


def street_stairs(v):
    """The stair block of the streets of a variant."""
    if v.maritime:
        return v.state("minecraft:stone_brick_stairs")
    return v.state(STREET_STAIRS.get(v.culture, "minecraft:cobblestone_stairs"))


def village_settings(v):
    """The settings of the village that change with the variant: the start pool, the wall and the homes."""
    return {
        "start_pool": v.id("plaza"),
        "wall": {
            "margin": 4,
            "tower_spacing": 40,
            "tower": v.id("tower"),
            "gate": v.id("gate"),
            "processors": v.id("foundation"),
            "palisade": {
                "log": v.state("minecraft:spruce_log", LOG_UP),
                "base": v.state("minecraft:cobblestone"),
                "point": v.state("minecraft:spruce_fence"),
                "foundation": v.state("minecraft:cobblestone"),
            },
            "pier": {
                "deck": v.state("minecraft:spruce_planks"),
                "post": v.state("minecraft:spruce_log", LOG_UP),
                "rail": v.state("minecraft:spruce_fence"),
                "stairs": v.state("minecraft:spruce_stairs",
                                  {"facing": "north", "half": "bottom", "shape": "straight", "waterlogged": "false"}),
            },
            "street_stairs": street_stairs(v),
            "stone": {
                "tower": v.id("stone/tower"),
                "gate": v.id("stone/gate"),
                "palisade": {
                    "log": v.state("minecraft:stone_bricks"),
                    "base": v.state("minecraft:cobblestone"),
                    "point": v.state("minecraft:stone_brick_wall"),
                    "foundation": v.state("minecraft:cobblestone"),
                },
            },
        },
        "homes": v.id("homes"),
    }


def all_variants():
    """The variants of all the cultures, then the variants of the styles of the cultures, then the maritime
    variants."""
    return (list(VARIANTS) + [v.of(culture) for culture in STYLES for v in VARIANTS]
            + [v.at_sea() for v in VARIANTS])


def cave_variant(base):
    """The variant of the cave village: the cave pieces in all the biomes. A cave site selects it (see CaveVillage).
    The wall of the variant has the cave gate and no stone wall. The cave village has no palisade and no tower: the
    other settings of the wall are the settings of the base variant."""
    wall = {key: value for key, value in base["wall"].items() if key != "stone"}
    return {"biomes": [], "start_pool": CAVE.id("plaza"), "wall": {**wall, "gate": CAVE.id("gate")},
            "homes": CAVE.id("homes"), "cave": True}


def structure():
    """The village structure: the base variant, then the list of the other variants with their biomes. A variant of
    the style of a culture has the culture. The base variant of a style has no biome: it is the variant of the culture
    in the biomes of no other variant of the culture. The maritime variants are for a site on the coast. The last
    variant is the cave village (see cave_variant)."""
    base = village_settings(BASE)
    variants = []
    for v in all_variants():
        if v.base and not v.culture and not v.maritime:
            continue
        settings = village_settings(v)
        entry = {"biomes": [] if v.base else v.biomes, "start_pool": settings["start_pool"],
                 "wall": settings["wall"], "homes": settings["homes"]}
        if v.culture:
            entry["culture"] = v.culture
        if v.maritime:
            entry["maritime"] = True
        variants.append(entry)
    variants.append(cave_variant(base))
    return {
        "type": f"{NS}:fortified_village",
        "biomes": f"#{NS}:has_village",
        "step": "surface_structures",
        "spawn_overrides": {},
        "terrain_adaptation": "beard_thin",
        "start_pool": base["start_pool"],
        "max_distance_from_center": 110,
        "wall": base["wall"],
        "homes": base["homes"],
        "variants": variants,
    }


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as out:
        out.write(json.dumps(data, indent=2) + "\n")
    print("wrote", os.path.normpath(path))


def main():
    check_home_steps()
    check_cliff_houses()
    check_stone_pieces()
    for v in all_variants() + [CAVE]:
        folder = os.path.join(DATA, "structure", *v.path("").split("/"))
        os.makedirs(folder, exist_ok=True)
        for name, build in v.pieces.items():
            path = os.path.join(folder, *(name + ".nbt").split("/"))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            t = build()
            v.apply(cave_lit(t, name) if v.cave else t).write(path)
            print("wrote", os.path.normpath(path))
        for name, data in pools(v).items():
            write_json(os.path.join(WORLDGEN, "template_pool", *v.path(name).split("/")) + ".json", data)
        for name, data in processor_lists(v).items():
            write_json(os.path.join(WORLDGEN, "processor_list", *v.path(name).split("/")) + ".json", data)
    write_json(os.path.join(WORLDGEN, "structure", "village.json"), structure())
    write_json(os.path.join(DATA, "tags", "worldgen", "biome", "has_village.json"),
               {"values": [biome for v in VARIANTS for biome in v.biomes]})
    os.makedirs(TEST_OUT, exist_ok=True)
    for name, build in TEST_PIECES.items():
        path = os.path.join(TEST_OUT, name + ".nbt")
        connect(build()).write(path)
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()
