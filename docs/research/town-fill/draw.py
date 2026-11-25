#!/usr/bin/env python3
"""Draw top-view diagrams of walled town growth strategies.

The script builds one final town per strategy on a square grid and records
snapshots at 20 %, 40 %, 60 %, 80 % and 100 % of the final building count.
It writes one PNG per stage, one contact sheet per strategy and two
overview sheets into the img/ folder next to this script.

Usage: python3 draw.py
Needs: numpy, scipy, Pillow.
"""

import copy
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

# ---------------------------------------------------------------------------
# Global parameters
# ---------------------------------------------------------------------------
G = 304            # grid size in cells
C = 152            # grid center
PX = 4             # pixels per cell -> 1200 px map
SEED = 7
N_FINAL = 56       # final building count, same for all strategies
STAGES = [0.20, 0.40, 0.60, 0.80, 1.00]
STAGE_COUNTS = [round(N_FINAL * p) for p in STAGES]
CORE = 40          # half size of the first wall
PLAZA = 10         # half size of the central plaza
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "img")

FREE, ROAD, BLD, WALL, PLAZ, NOBUILD, SPECIAL = range(7)

# Land use codes for the raster
LU_COUNTRY_FIELD, LU_COUNTRY_MEADOW, LU_BARE, LU_GARDEN, LU_FIELD, \
    LU_ORCHARD, LU_VINE, LU_PASTURE, LU_CEMETERY, LU_POND, LU_FAIR, \
    LU_VERGE = range(12)

COL = {
    LU_COUNTRY_FIELD: (234, 229, 206),
    LU_COUNTRY_MEADOW: (230, 234, 212),
    LU_BARE: (214, 200, 166),
    LU_GARDEN: (170, 208, 140),
    LU_FIELD: (232, 212, 120),
    LU_ORCHARD: (190, 218, 150),
    LU_VINE: (214, 202, 168),
    LU_PASTURE: (204, 226, 164),
    LU_CEMETERY: (186, 194, 178),
    LU_POND: (140, 184, 216),
    LU_FAIR: (230, 214, 178),
    LU_VERGE: (222, 210, 178),
}
PAT = {  # pattern colour for textured land uses
    LU_COUNTRY_FIELD: (226, 219, 192),
    LU_FIELD: (204, 182, 90),
    LU_ORCHARD: (92, 140, 70),
    LU_VINE: (120, 96, 140),
    LU_CEMETERY: (120, 126, 112),
    LU_FAIR: (205, 186, 146),
}
C_WATER = (140, 184, 216)
C_MAIN = (196, 170, 118)
C_STREET = (210, 190, 146)
C_TRACK = (200, 186, 150)
C_PLAZA = (240, 218, 146)
C_BLD = (156, 74, 53)
C_BLD_LINE = (84, 38, 26)
C_CHURCH = (106, 90, 150)
C_CHURCH_LINE = (56, 46, 92)
C_WALL = (70, 70, 70)
C_TOWER = (40, 40, 40)
C_GATE = (200, 60, 40)
C_PLOT = (150, 118, 80)

FONT_DIR = "/usr/share/fonts/truetype/dejavu/"


def font(size, bold=False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    return ImageFont.truetype(os.path.join(FONT_DIR, name), size)


# ---------------------------------------------------------------------------
# Town model
# ---------------------------------------------------------------------------
class Slot:
    """A possible building place along a street."""

    def __init__(self, rect, back, road, t, jit):
        self.rect = rect      # (x0, y0, x1, y1), half-open, in cells
        self.back = back      # garden or yard behind the building
        self.road = road      # name of the street that gives access
        self.t = t            # distance along the street from its start
        self.jit = jit        # random value for tie breaks
        self.used = False
        self.plot = None      # full plot rect (strategy 4 only)
        self.is_back = False  # back-lot building (strategy 4 only)

    @property
    def cx(self):
        return (self.rect[0] + self.rect[2]) / 2

    @property
    def cy(self):
        return (self.rect[1] + self.rect[3]) / 2


class Town:
    def __init__(self, seed):
        self.rng = random.Random(seed)
        self.occ = np.zeros((G, G), np.uint8)
        self.roads = []        # dict(name, rect, kind, p0, p1)
        self.walls = []        # dict(segs=[(xa, ya, xb, yb)])
        self.encl = []         # enclosure rects
        self.buildings = []    # dict(rect, back, kind)
        self.specials = []     # dict(rect, code, label)
        self.slots = []
        self.plots = []        # plot rects to outline (strategy 4)
        self.reserved = np.zeros((G, G), bool)   # land kept for a future wall
        self.active = set()    # lazy streets that have a building
        self.landmode = "productive"
        self.core_rect = (C - CORE, C - CORE, C + CORE, C + CORE)

    # -- marking helpers ---------------------------------------------------
    def _mark(self, rect, code, only_free=True):
        x0, y0, x1, y1 = [int(v) for v in rect]
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(G, x1), min(G, y1)
        if x0 >= x1 or y0 >= y1:
            return
        sub = self.occ[y0:y1, x0:x1]
        if only_free:
            sub[(sub == FREE) | (sub == NOBUILD)] = code
        else:
            sub[:] = code

    def add_road(self, name, p0, p1, width, kind, slots=True, gap=2,
                 sizes=None, depth_garden=8, lazy=False, start=1):
        (xa, ya), (xb, yb) = p0, p1
        h = ya == yb
        lo = width // 2
        if h:
            rect = (min(xa, xb), ya - lo, max(xa, xb), ya - lo + width)
        else:
            rect = (xa - lo, min(ya, yb), xa - lo + width, max(ya, yb))
        road = dict(name=name, rect=rect, kind=kind, p0=p0, p1=p1, lazy=lazy)
        self.roads.append(road)
        x0, y0, x1, y1 = rect
        sub = self.occ[max(0, y0):min(G, y1), max(0, x0):min(G, x1)]
        sub[sub != WALL] = ROAD
        if slots:
            self.slots += self.make_slots(
                road, gap=gap, sizes=sizes, depth_garden=depth_garden,
                lane_every=3 if kind == "main" else None, start=start)
        return road

    def make_slots(self, road, gap=2, sizes=None, depth_garden=8,
                   lane_every=None, start=1, avoid=False):
        sizes = sizes or [((9, 9), .55), ((13, 9), .15), ((9, 13), .15),
                          ((13, 13), .15)]
        (xa, ya), (xb, yb) = road["p0"], road["p1"]
        x0, y0, x1, y1 = road["rect"]
        h = ya == yb
        length = abs(xb - xa) if h else abs(yb - ya)
        sgn = 1 if (xb - xa if h else yb - ya) > 0 else -1
        out = []
        # both sides use the same size sequence, so that the gaps (lanes)
        # face each other and a cross street or a wall can pass through
        seq_seed = self.rng.random()
        for side in (-1, 1):
            rs = random.Random(seq_seed)
            t = start + rs.randint(0, 3)
            k = 0
            while t < length - 8:
                r = rs.random()
                acc = 0
                for (w, d), p in sizes:
                    acc += p
                    if r <= acc:
                        break
                if t + w > length:
                    break
                if h:
                    a = xa + sgn * t
                    ax0, ax1 = (a, a + w) if sgn > 0 else (a - w, a)
                    if side < 0:
                        rect = (ax0, y0 - 1 - d, ax1, y0 - 1)
                        back = (ax0, y0 - 1 - d - depth_garden, ax1, y0 - 1 - d)
                    else:
                        rect = (ax0, y1 + 1, ax1, y1 + 1 + d)
                        back = (ax0, y1 + 1 + d, ax1, y1 + 1 + d + depth_garden)
                else:
                    a = ya + sgn * t
                    ay0, ay1 = (a, a + w) if sgn > 0 else (a - w, a)
                    if side < 0:
                        rect = (x0 - 1 - d, ay0, x0 - 1, ay1)
                        back = (x0 - 1 - d - depth_garden, ay0, x0 - 1 - d, ay1)
                    else:
                        rect = (x1 + 1, ay0, x1 + 1 + d, ay1)
                        back = (x1 + 1 + d, ay0, x1 + 1 + d + depth_garden, ay1)
                if avoid:
                    ax0_, ay0_, ax1_, ay1_ = rect
                    sub = self.occ[max(0, ay0_):ay1_, max(0, ax0_):ax1_]
                    if np.any(sub == ROAD) or np.any(
                            self.reserved[max(0, ay0_):ay1_, max(0, ax0_):ax1_]):
                        t += 1
                        continue
                out.append(Slot(rect, back, road["name"], t + w / 2,
                                self.rng.random()))
                k += 1
                extra = 2 if rs.random() < .25 else 0
                if lane_every and k % lane_every == 0:
                    extra = 7
                t += w + gap + extra
        return out

    def add_wall_rect(self, rect, open_side=None, inner_margin=2,
                      outer_clear=7):
        x0, y0, x1, y1 = rect
        segs = [(x0, y0, x1, y0), (x1, y0, x1, y1), (x0, y1, x1, y1),
                (x0, y0, x0, y1)]
        if open_side is not None:
            segs = [s for i, s in enumerate(segs) if i != open_side]
        self.walls.append(dict(segs=segs))
        self.encl.append(rect)
        for (xa, ya, xb, yb) in segs:
            band = (min(xa, xb) - 1, min(ya, yb) - 1,
                    max(xa, xb) + 2, max(ya, yb) + 2)
            self._mark(band, WALL)
        # no-build zones: a strip along each wall, and a clear zone outside
        # the outermost walls only (inner walls lose their clear zone)
        self.occ[self.occ == NOBUILD] = FREE
        inside = self.inside_mask()
        wallm = self.occ == WALL
        near_in = ndimage.binary_dilation(wallm, iterations=inner_margin)
        near_out = ndimage.binary_dilation(wallm, iterations=outer_clear)
        zone = near_in | (near_out & ~inside)
        sel = zone & (self.occ == FREE)
        self.occ[sel] = NOBUILD

    def inside_mask(self):
        m = np.zeros((G, G), bool)
        for (x0, y0, x1, y1) in self.encl:
            m[max(0, y0):min(G, y1), max(0, x0):min(G, x1)] = True
        return m

    def add_special(self, rect, code, label=None, mark=True):
        self.specials.append(dict(rect=rect, code=code, label=label))
        if mark:
            self._mark(rect, SPECIAL)

    # -- buildings -----------------------------------------------------------
    def fits(self, rect):
        x0, y0, x1, y1 = rect
        if x0 < 1 or y0 < 1 or x1 > G - 1 or y1 > G - 1:
            return False
        if np.any(self.occ[y0:y1, x0:x1] != FREE):
            return False
        if np.any(self.reserved[y0:y1, x0:x1]):
            return False
        ring = self.occ[y0 - 1:y1 + 1, x0 - 1:x1 + 1]
        return not np.any((ring == BLD) | (ring == WALL))

    def place(self, slot, kind="house"):
        self.buildings.append(dict(rect=slot.rect, back=slot.back, kind=kind))
        self._mark(slot.rect, BLD, only_free=False)
        slot.used = True
        self.active.add(slot.road)

    def valid_slots(self):
        return [s for s in self.slots if not s.used and self.fits(s.rect)]

    def density(self, rect, exclude=None):
        x0, y0, x1, y1 = rect
        area = (x1 - x0) * (y1 - y0)
        if exclude:
            ex0, ey0, ex1, ey1 = exclude
            area -= (ex1 - ex0) * (ey1 - ey0)
        b = 0
        for bd in self.buildings:
            bx0, by0, bx1, by1 = bd["rect"]
            ix = max(0, min(x1, bx1) - max(x0, bx0))
            iy = max(0, min(y1, by1) - max(y0, by0))
            inter = ix * iy
            if exclude:
                ix = max(0, min(ex1, bx1) - max(ex0, bx0))
                iy = max(0, min(ey1, by1) - max(ey0, by0))
                inter -= ix * iy
            b += inter
        return b / max(1, area)

    def snapshot(self, label):
        s = copy.copy(self)
        s.occ = self.occ.copy()
        s.roads = list(self.roads)
        s.walls = copy.deepcopy(self.walls)
        s.encl = list(self.encl)
        s.buildings = list(self.buildings)
        s.specials = list(self.specials)
        s.plots = list(self.plots)
        s.active = set(self.active)
        s.slots = []
        s.rng = None
        s.label = label
        return s


def stage_label(n):
    return f"{round(100 * STAGES[STAGE_COUNTS.index(n)])} %"


def inside_rect(rect, outer):
    return (rect[0] >= outer[0] and rect[1] >= outer[1]
            and rect[2] <= outer[2] and rect[3] <= outer[3])


def center_of(rect):
    return ((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2)


# ---------------------------------------------------------------------------
# Base town: plaza, four main roads, first wall, core ring street, church
# ---------------------------------------------------------------------------
MAIN_DIRS = {"E": (1, 0), "W": (-1, 0), "N": (0, -1), "S": (0, 1)}


def base_town(seed, core_wall=True):
    t = Town(seed)
    t._mark((C - PLAZA, C - PLAZA, C + PLAZA, C + PLAZA), PLAZ,
            only_free=False)
    t.add_road("E", (C + PLAZA, C), (G, C), 5, "main")
    t.add_road("W", (C - PLAZA, C), (0, C), 5, "main")
    t.add_road("N", (C, C - PLAZA), (C, 0), 5, "main")
    t.add_road("S", (C, C + PLAZA), (C, G), 5, "main")
    # slots that face the plaza
    for name, p0, p1 in [("pN", (C - PLAZA, C - PLAZA - 1), (C + PLAZA, C - PLAZA - 1)),
                         ("pS", (C - PLAZA, C + PLAZA), (C + PLAZA, C + PLAZA))]:
        road = dict(name=name, rect=(p0[0], p0[1], p1[0], p1[1] + 1),
                    kind="virtual", p0=p0, p1=p1)
        t.slots += t.make_slots(road, gap=1)
    r = 25
    add_ring(t, "core", r, r, r, r, 3, "street")
    if core_wall:
        t.add_wall_rect(t.core_rect)
    # church on the north side of the plaza
    church = Slot((C + 3, C - PLAZA - 15, C + 16, C - PLAZA - 2), None,
                  "pN", 0, 0)
    t.place(church, kind="church")
    t.add_special((C + 3, C - PLAZA - 15 - 0, C + 16, C - PLAZA - 2),
                  LU_BARE, mark=False)
    return t


def add_ring(t, name, rE, rN, rW, rS, width, kind, slots=True, skip=(),
             lazy=False):
    """Add a rectangular ring street with one half extent per side."""
    x0, x1, y0, y1 = C - rW, C + rE, C - rN, C + rS
    segs = {"n": ((x0, y0), (x1, y0)), "e": ((x1, y0), (x1, y1)),
            "s": ((x1, y1), (x0, y1)), "w": ((x0, y1), (x0, y0))}
    for k, (p0, p1) in segs.items():
        if k in skip:
            continue
        ext0 = list(p0)
        ext1 = list(p1)
        t.add_road(f"{name}_{k}", tuple(ext0), tuple(ext1), width, kind,
                   slots=slots, lazy=lazy)


# ---------------------------------------------------------------------------
# Strategy 0: current state ("empty enclosures")
# ---------------------------------------------------------------------------
BANDS = [40, 76, 112, 146]
QUOTA = [10, 13, 16, 17]         # buildings per enclosure (sum = 56)


def strat_current(seed):
    t = base_town(seed)
    t.landmode = "empty"
    for r in BANDS[1:]:
        t.add_wall_rect((C - r, C - r, C + r, C + r))
    # core: closest slots first
    cand = [s for s in t.valid_slots() if inside_rect(s.rect, t.core_rect)]
    cand.sort(key=lambda s: max(abs(s.cx - C), abs(s.cy - C)) + s.jit * 4)
    placed = 1  # church
    for s in cand:
        if placed >= QUOTA[0]:
            break
        if t.fits(s.rect):
            t.place(s)
            placed += 1
    # bands: the ring street grows clockwise from the east gate
    for b in range(1, 4):
        r = (BANDS[b - 1] + BANDS[b]) // 2
        tmp = Town(seed + b)
        tmp.occ = t.occ.copy()
        path = [((C + r, C), (C + r, C + r)), ((C + r, C + r), (C - r, C + r)),
                ((C - r, C + r), (C - r, C - r)), ((C - r, C - r), (C + r, C - r)),
                ((C + r, C - r), (C + r, C))]
        cum = 0
        ring_slots = []
        for i, (p0, p1) in enumerate(path):
            road = dict(name=f"r{b}_{i}", kind="street", p0=p0, p1=p1,
                        rect=_road_rect(p0, p1, 4))
            for s in tmp.make_slots(road):
                s.t += cum
                ring_slots.append(s)
            cum += abs(p1[0] - p0[0]) + abs(p1[1] - p0[1])
        # roads must be marked before slots are tested
        for i, (p0, p1) in enumerate(path):
            rr = _road_rect(p0, p1, 4)
            sub = t.occ[rr[1]:rr[3], rr[0]:rr[2]]
            sub[sub == FREE] = ROAD
        ring_slots.sort(key=lambda s: s.t)
        n = 0
        tmax = 0
        for s in ring_slots:
            if n >= QUOTA[b]:
                break
            if t.fits(s.rect):
                t.place(s)
                n += 1
                tmax = s.t + 8
        # unmark the part of the ring street that is not built
        for i, (p0, p1) in enumerate(path):
            rr = _road_rect(p0, p1, 4)
            sub = t.occ[rr[1]:rr[3], rr[0]:rr[2]]
            sub[sub == ROAD] = FREE
        cum = 0
        for i, (p0, p1) in enumerate(path):
            seg = abs(p1[0] - p0[0]) + abs(p1[1] - p0[1])
            if cum >= tmax:
                break
            frac = min(1.0, (tmax - cum) / seg)
            q1 = (round(p0[0] + (p1[0] - p0[0]) * frac),
                  round(p0[1] + (p1[1] - p0[1]) * frac))
            t.add_road(f"r{b}_{i}", p0, q1, 4, "street", slots=False)
            cum += seg
    # main roads are re-marked so that they stay on top
    return [t.snapshot("100 %")]


def _road_rect(p0, p1, width):
    (xa, ya), (xb, yb) = p0, p1
    lo = width // 2
    if ya == yb:
        return (min(xa, xb), ya - lo, max(xa, xb) + (width - lo), ya - lo + width)
    return (xa - lo, min(ya, yb), xa - lo + width, max(ya, yb) + (width - lo))


# ---------------------------------------------------------------------------
# Strategy 1: one wall, then suburbs, then a new wall when the inside is full
# ---------------------------------------------------------------------------
ROAD_WEIGHT = {"E": 1.0, "W": 1.35, "N": 1.75, "S": 2.2}


def dist_out(slot, outer):
    """Distance from the outer wall along the main road of the slot."""
    x, y = slot.cx, slot.cy
    d = parent_dir(slot.road) or slot.road
    if d == "E":
        return x - outer[2]
    if d == "W":
        return outer[0] - x
    if d == "N":
        return outer[1] - y
    return y - outer[3]


def axis_score(s):
    dx, dy = abs(s.cx - C), abs(s.cy - C)
    return min(dx, dy) + 0.25 * max(dx, dy) + s.jit * 6


S1_WALLS = [dict(e=94, w=82, n=80, s=88), dict(e=140, w=126, n=124, s=132)]
S1_SIDE = [
    {"E": [(56, (-1, 1)), (72, (-1,))],
     "W": [(55, (-1, 1)), (68, (-1, 1))],
     "N": [(55, (-1, 1)), (67, (-1, 1))],
     "S": [(58, (-1,)), (72, (-1,))]},
    {"E": [(106, (-1, 1)), (122, (-1, 1))], "W": [(96, (-1, 1)), (112, (-1, 1))],
     "N": [(94, (-1, 1)), (110, (-1, 1))], "S": [(102, (-1, 1)), (118, (-1, 1))]},
]


def reserve_wall(t, off):
    """Keep a strip of land free where a later wall will stand."""
    x0, y0, x1, y1 = C - off["w"], C - off["n"], C + off["e"], C + off["s"]
    m = np.zeros((G, G), bool)
    m[max(0, y0 - 8):y1 + 8, max(0, x0 - 8):x1 + 8] = True
    m[y0 + 3:y1 - 3, x0 + 3:x1 - 3] = False
    t.reserved |= m


def add_side_streets(t, plan, length=40):
    for d, items in plan.items():
        for g, sides in items:
            for sgn in sides:
                if d in "EW":
                    x = C + g if d == "E" else C - g
                    p0, p1 = (x, C + sgn * 3), (x, C + sgn * (3 + length))
                else:
                    y = C + g if d == "S" else C - g
                    p0, p1 = (C + sgn * 3, y), (C + sgn * (3 + length), y)
                t.add_road(f"x{d}{g}{sgn}", p0, p1, 3, "street", lazy=True,
                           start=12)


def parent_dir(road):
    if road in MAIN_DIRS:
        return road
    if road.startswith("x"):
        return road[1]
    return None


def lateral(s):
    d = parent_dir(s.road)
    return abs(s.cy - C) if d in "EW" else abs(s.cx - C)


def strat_s1(seed):
    t = base_town(seed)
    for off in S1_WALLS:
        reserve_wall(t, off)
    for plan in S1_SIDE:
        add_side_streets(t, plan)
    # a street along the inside of the second wall ("rue du rempart")
    w = S1_WALLS[0]
    add_ring(t, "ramp", w["e"] - 7, w["n"] - 7, w["w"] - 7, w["s"] - 7, 4,
             "street", lazy=True)
    # houses along the main roads outside the core avoid the planned streets
    t.slots = [s for s in t.slots if not (s.road in MAIN_DIRS and
               not inside_rect(s.rect, t.core_rect))]
    for r in t.roads:
        if r["name"] in MAIN_DIRS:
            t.slots += [s for s in t.make_slots(r, avoid=True)
                        if not inside_rect(s.rect, t.core_rect)]
    # specials in the first suburbs: a livestock market and a convent
    t.add_special((C + 61, C + 5, C + 80, C + 24), LU_FAIR, "marché")
    t.add_special((C + 8, C + 52, C + 34, C + 75), LU_GARDEN, "couvent")
    t.add_special((C + 23, C + 54, C + 32, C + 64), LU_CEMETERY, mark=False)
    t.hidden_specials = [sp for sp in t.specials]
    t.specials = []
    thr = [0.19, 0.15, 0.12]      # density that counts as "full enough"
    trig = [10, 18]               # houses outside that trigger a new wall
    snaps = []
    while len(t.buildings) < N_FINAL:
        outer = t.encl[-1]
        level = len(t.walls) - 1
        dens = t.density(outer)
        valid = t.valid_slots()
        inside = [s for s in valid if inside_rect(s.rect, outer)]
        outside = [s for s in valid if parent_dir(s.road)
                   and not inside_rect(s.rect, outer)]
        if (dens < thr[level] and len(inside) >= 2) or not outside:
            s = min(inside, key=axis_score)
        else:
            s = min(outside, key=lambda s: dist_out(s, outer)
                    * ROAD_WEIGHT[parent_dir(s.road)] + lateral(s) * 0.8
                    + s.jit * 8)
        t.place(s)
        n_out = sum(1 for b in t.buildings if not inside_rect(b["rect"], outer))
        if n_out >= 3 and t.hidden_specials:
            for sp in t.hidden_specials:
                t.specials.append(sp)
            t.hidden_specials = []
            t.place(Slot((C + 10, C + 55, C + 23, C + 68), None, "S", 0, 0),
                    kind="church")
        if level < len(trig) and n_out >= trig[level]:
            off = S1_WALLS[level]
            t.add_wall_rect((C - off["w"], C - off["n"], C + off["e"],
                             C + off["s"]))
            m = np.zeros((G, G), bool)
            m[C - off["n"] - 8:C + off["s"] + 8, C - off["w"] - 8:C + off["e"] + 8] = True
            t.reserved &= ~m | ~reserved_ring(off)
        if len(t.buildings) in STAGE_COUNTS:
            snaps.append(t.snapshot(stage_label(len(t.buildings))))
    return snaps


def reserved_ring(off):
    x0, y0, x1, y1 = C - off["w"], C - off["n"], C + off["e"], C + off["s"]
    m = np.zeros((G, G), bool)
    m[max(0, y0 - 8):y1 + 8, max(0, x0 - 8):x1 + 8] = True
    m[y0 + 3:y1 - 3, x0 + 3:x1 - 3] = False
    return m


# ---------------------------------------------------------------------------
# Strategy 2: large fixed walls, ribbons inside, productive land in between
# ---------------------------------------------------------------------------
def strat_s2(seed):
    t = base_town(seed)
    snaps = []
    rings = {}
    for b in range(1, 4):
        r = (BANDS[b - 1] + BANDS[b]) // 2
        rings[b] = r
    band = 0
    counts = [1, 0, 0, 0]
    while len(t.buildings) < N_FINAL:
        if counts[band] >= QUOTA[band]:
            band += 1
            nb = len(t.buildings)
            open_band_s2(t, band, rings[band])
            counts[band] += len(t.buildings) - nb
        valid = t.valid_slots()
        lo, hi = BANDS[band - 1] if band else 0, BANDS[band]
        cand = []
        for s in valid:
            m = max(abs(s.cx - C), abs(s.cy - C))
            if not (lo < m < hi):
                continue
            if band == 0:
                cand.append((axis_score(s), s))
            elif s.road in MAIN_DIRS:
                cand.append(((m - lo) * ROAD_WEIGHT[s.road] + s.jit * 6, s))
            elif s.road.startswith(f"b{band}"):
                # ring track: only near the crossing with a main road
                d = min(abs(s.cx - C), abs(s.cy - C))
                if d < 34:
                    cand.append((d * 1.3 + 10 + s.jit * 6, s))
        if not cand:
            cand = [(max(abs(s.cx - C), abs(s.cy - C)) + s.jit, s)
                    for s in valid
                    if lo < max(abs(s.cx - C), abs(s.cy - C)) < hi]
        sc, s = min(cand, key=lambda c: c[0])
        t.place(s)
        counts[band] += 1
        if len(t.buildings) in STAGE_COUNTS:
            snaps.append(t.snapshot(stage_label(len(t.buildings))))
    return snaps


def open_band_s2(t, b, r):
    R = BANDS[b]
    t.add_wall_rect((C - R, C - R, C + R, C + R))
    skip = {1: (), 2: ("n",), 3: ("w",)}[b]
    add_ring(t, f"b{b}", r, r, r, r, 3, "track", skip=skip)
    if b == 1:
        t.add_special((C - 72, C - 72, C - 62, C - 62), LU_CEMETERY, "cimetière")
        t.add_special((C + 62, C + 62, C + 73, C + 73), LU_POND, "vivier")
    if b == 2:
        t.add_special((C + 28, C - 109, C + 70, C - 80), LU_GARDEN, "couvent")
        t.place(Slot((C + 31, C - 105, C + 44, C - 92), None, "x", 0, 0),
                kind="church")
        t.add_special((C + 50, C - 105, C + 66, C - 93), LU_CEMETERY, mark=False)
    if b == 3:
        t.add_special((C - 143, C + 10, C - 115, C + 44), LU_FAIR,
                      "champ de foire")
        t.add_special((C + 116, C + 116, C + 125, C + 125), LU_POND)


# ---------------------------------------------------------------------------
# Strategy 3: star growth, each suburb gets its own wall ("walled suburbs")
# ---------------------------------------------------------------------------
LOBE_HALF = 33


def strat_s3(seed):
    t = base_town(seed)
    snaps = []
    lobes = {}          # dir -> list of rects
    trig = 5
    thr_core, thr_lobe = 0.25, 0.20
    while len(t.buildings) < N_FINAL:
        valid = t.valid_slots()
        core_c = [s for s in valid if inside_rect(s.rect, t.core_rect)]
        s = None
        if t.density(t.core_rect) < thr_core and core_c:
            s = min(core_c, key=axis_score)
        else:
            lobe_c = []
            for d, rects in lobes.items():
                for rc in rects:
                    if t.density(rc) < thr_lobe:
                        lobe_c += [x for x in valid if inside_rect(x.rect, rc)]
            if lobe_c:
                s = min(lobe_c, key=lambda x: abs(x.cx - C) + abs(x.cy - C)
                        + x.jit * 6)
            else:
                out = [x for x in valid if x.road in MAIN_DIRS
                       and not t.inside_mask()[int(x.cy), int(x.cx)]]
                s = min(out, key=lambda x: dist_out_star(x, lobes)
                        * ROAD_WEIGHT[x.road] + x.jit * 8)
        t.place(s)
        for d in MAIN_DIRS:
            end = lobe_end(lobes, d)
            ribbon = [b for b in t.buildings if on_ribbon(b["rect"], d, end)]
            if len(ribbon) >= trig:
                add_lobe(t, lobes, d, ribbon)
        if len(t.buildings) in STAGE_COUNTS:
            snaps.append(t.snapshot(stage_label(len(t.buildings))))
    return snaps


def lobe_end(lobes, d):
    if d not in lobes:
        return CORE
    rc = lobes[d][-1]
    return {"E": rc[2] - C, "W": C - rc[0], "N": C - rc[1], "S": rc[3] - C}[d]


def dist_out_star(s, lobes):
    return abs(s.cx - C) + abs(s.cy - C) - lobe_end(lobes, s.road)


def on_ribbon(rect, d, end):
    cx, cy = center_of(rect)
    if d == "E":
        return cx - C > end and abs(cy - C) < LOBE_HALF
    if d == "W":
        return C - cx > end and abs(cy - C) < LOBE_HALF
    if d == "N":
        return C - cy > end and abs(cx - C) < LOBE_HALF
    return cy - C > end and abs(cx - C) < LOBE_HALF


def add_lobe(t, lobes, d, ribbon, margin=9):
    end = lobe_end(lobes, d)
    h = LOBE_HALF
    if d == "E":
        far = max(b["rect"][2] for b in ribbon) + margin
        rc, side = (C + end, C - h, far, C + h), 3
        lanes = [((C + end + 2, C - 20), (far - 3, C - 20)),
                 ((C + end + 2, C + 20), (far - 3, C + 20))]
    elif d == "W":
        far = min(b["rect"][0] for b in ribbon) - margin
        rc, side = (far, C - h, C - end, C + h), 1
        lanes = [((C - end - 2, C - 20), (far + 3, C - 20)),
                 ((C - end - 2, C + 20), (far + 3, C + 20))]
    elif d == "N":
        far = min(b["rect"][1] for b in ribbon) - margin
        rc, side = (C - h, far, C + h, C - end), 2
        lanes = [((C - 20, C - end - 2), (C - 20, far + 3)),
                 ((C + 20, C - end - 2), (C + 20, far + 3))]
    else:
        far = max(b["rect"][3] for b in ribbon) + margin
        rc, side = (C - h, C + end, C + h, far), 0
        lanes = [((C - 20, C + end + 2), (C - 20, far - 3)),
                 ((C + 20, C + end + 2), (C + 20, far - 3))]
    # the side that touches the previous wall stays open
    t.add_wall_rect(rc, open_side=side)
    lobes.setdefault(d, []).append(rc)
    for i, (p0, p1) in enumerate(lanes):
        t.add_road(f"lane{d}{len(lobes[d])}{i}", p0, p1, 3, "street",
                   sizes=[((9, 9), .7), ((13, 9), .3)])


# ---------------------------------------------------------------------------
# Strategy 4: one planned outer wall sized on the final population, plots
# ---------------------------------------------------------------------------
def strat_s4(seed):
    t = base_town(seed)
    snaps = []
    thr_core = 0.21
    planned = False
    target = 0.18
    while len(t.buildings) < N_FINAL:
        valid = t.valid_slots()
        core_c = [s for s in valid if inside_rect(s.rect, t.core_rect)]
        if not planned and (t.density(t.core_rect) >= thr_core or not core_c):
            n_rest = N_FINAL - len(t.buildings)
            area = n_rest * 103 / target + (2 * CORE) ** 2
            R = int(math.ceil(math.sqrt(area) / 2))
            plan_s4(t, R)
            planned = True
            valid = t.valid_slots()
        if not planned:
            s = min(core_c, key=axis_score)
        else:
            cand = [s for s in valid if s.plot is not None
                    and (not s.is_back or s.front.used)]
            s = min(cand, key=lambda s: s.score)
        t.place(s)
        if len(t.buildings) in STAGE_COUNTS:
            snaps.append(t.snapshot(stage_label(len(t.buildings))))
    return snaps


def plan_s4(t, R):
    t.add_wall_rect((C - R, C - R, C + R, C + R))
    t.R4 = R
    # the clear zone outside the old wall is given back to building
    m = t.occ == NOBUILD
    core_out = np.ones((G, G), bool)
    core_out[C - CORE - 2:C + CORE + 2, C - CORE - 2:C + CORE + 2] = False
    big_in = np.zeros((G, G), bool)
    big_in[C - R + 3:C + R - 3, C - R + 3:C + R - 3] = True
    t.occ[m & core_out & big_in] = FREE
    rr = R - 17
    add_ring(t, "p", rr, rr, rr, rr, 4, "street", slots=False)
    plot_depth = rr - 2 - (CORE + 3)
    mask = np.zeros((G, G), bool)
    roads = [r for r in t.roads if r["name"].startswith("p_")]
    roads += [r for r in t.roads if r["name"] in MAIN_DIRS]
    rng = random.Random(SEED + 40)
    lim = R - 4
    for road in roads:
        tmp = Town(rng.randint(0, 10 ** 6))
        tmp.rng = rng
        # one plot = a front building, then a garden; a back building may
        # come later (back-lot infill)
        for s in tmp.make_slots(road, gap=0, sizes=[((11, plot_depth), .6),
                                                     ((15, plot_depth), .4)]):
            px0, py0, px1, py1 = s.rect
            px0, py0 = max(px0, C - lim), max(py0, C - lim)
            px1, py1 = min(px1, C + lim), min(py1, C + lim)
            if road["name"] in MAIN_DIRS:
                m_ = max(abs(s.cx - C), abs(s.cy - C))
                if not (CORE + 4 < m_ < rr - 4):
                    continue
            if min(px1 - px0, py1 - py0) < 9 or max(px1 - px0, py1 - py0) < 11:
                continue
            sub = t.occ[py0:py1, px0:px1]
            if np.any((sub != FREE)) or np.any(mask[py0:py1, px0:px1]):
                continue
            mask[py0:py1, px0:px1] = True
            plot = (px0, py0, px1, py1)
            t.plots.append(plot)
            front, back = split_plot(plot, road)
            base_sc = axis_score_xy(*center_of(front)) + rng.random() * 6
            f = Slot(front, None, road["name"], 0, 0)
            f.plot, f.score = plot, base_sc
            t.slots.append(f)
            if back is not None:
                b = Slot(back, None, road["name"], 0, 0)
                b.plot, b.score, b.is_back, b.front = plot, base_sc + 45, True, f
                t.slots.append(b)


def axis_score_xy(x, y):
    dx, dy = abs(x - C), abs(y - C)
    return min(dx, dy) + 0.25 * max(dx, dy)


def split_plot(rect, road):
    """Front building at the street, back building at the rear of the plot.

    The buildings keep one cell free on each side of the plot.
    """
    x0, y0, x1, y1 = rect
    h = road["p0"][1] == road["p1"][1]
    rx0, ry0, rx1, ry1 = road["rect"]
    if h:
        w, depth = x1 - x0, y1 - y0
        d = min(9 if w <= 11 else 13, depth)
        bx0, bx1 = x0 + 1, x1 - 1
        if y1 <= ry0:   # plot above the street
            front = (bx0, y1 - d, bx1, y1)
            back = (bx0, y0, bx1, y0 + 9)
        else:
            front = (bx0, y0, bx1, y0 + d)
            back = (bx0, y1 - 9, bx1, y1)
    else:
        w, depth = y1 - y0, x1 - x0
        d = min(9 if w <= 11 else 13, depth)
        by0, by1 = y0 + 1, y1 - 1
        if x1 <= rx0:
            front = (x1 - d, by0, x1, by1)
            back = (x0, by0, x0 + 9, by1)
        else:
            front = (x0, by0, x0 + d, by1)
            back = (x1 - 9, by0, x1, by1)
    if depth < d + 2 + 9:
        back = None
    return front, back


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------
def tile_type(tx, ty):
    h = (tx * 73856093) ^ (ty * 19349663) ^ (SEED * 83492791)
    r = (h % 1000) / 1000
    if r < .30:
        return LU_FIELD
    if r < .50:
        return LU_ORCHARD
    if r < .60:
        return LU_VINE
    return LU_PASTURE


def land_use(t):
    inside = t.inside_mask()
    lu = np.full((G, G), LU_COUNTRY_MEADOW, np.uint8)
    yy, xx = np.mgrid[0:G, 0:G]
    tiles = ((xx // 38) * 7 + (yy // 38) * 3 + SEED) % 5
    lu[tiles < 2] = LU_COUNTRY_FIELD
    if t.landmode == "empty":
        lu[inside] = LU_BARE
    else:
        TS = 24
        tt = np.zeros((G, G), np.uint8)
        for ty in range(0, G, TS):
            for tx in range(0, G, TS):
                tt[ty:ty + TS, tx:tx + TS] = tile_type(tx // TS, ty // TS)
        lu[inside] = tt[inside]
        core = np.zeros((G, G), bool)
        x0, y0, x1, y1 = t.core_rect
        core[y0:y1, x0:x1] = True
        lu[core] = LU_GARDEN
        # verges along roads stay bare
        roads = np.zeros((G, G), bool)
        for r in visible_roads(t):
            if r["kind"] == "track" or (r.get("lazy")
                                        and r["name"] not in t.active):
                continue
            x0, y0, x1, y1 = [max(0, min(G, v)) for v in r["rect"]]
            roads[y0:y1, x0:x1] = True
        near = ndimage.binary_dilation(roads, iterations=2) & inside
        lu[near] = LU_VERGE
        for b in t.buildings:
            if b["back"]:
                x0, y0, x1, y1 = [max(0, min(G, v)) for v in b["back"]]
                lu[y0:y1, x0:x1] = LU_GARDEN
        for rc in t.plots:
            x0, y0, x1, y1 = rc
            lu[y0:y1, x0:x1] = LU_GARDEN
    for sp in t.specials:
        x0, y0, x1, y1 = sp["rect"]
        lu[y0:y1, x0:x1] = sp["code"]
    return lu


def visible_roads(t):
    inside = t.inside_mask()
    out = []
    for r in t.roads:
        if r.get("lazy") and r["name"] not in t.active:
            cx, cy = center_of(r["rect"])
            if not inside[int(cy), int(cx)]:
                continue
        out.append(r)
    return out


def raster(t):
    lu = land_use(t)
    img = np.zeros((G, G, 3), np.uint8)
    for code, col in COL.items():
        img[lu == code] = col
    yy, xx = np.mgrid[0:G, 0:G]
    stripes = ((xx // 24 + yy // 24) % 2 == 0)
    pat = {
        LU_FIELD: np.where(stripes, xx % 3 == 0, yy % 3 == 0),
        LU_COUNTRY_FIELD: np.where(((xx // 38 + yy // 38) % 2) == 0,
                                   xx % 4 == 0, yy % 4 == 0),
        LU_ORCHARD: (xx % 4 == 1) & (yy % 4 == 1),
        LU_VINE: (yy % 3 == 0) & (xx % 2 == 0),
        LU_CEMETERY: (xx % 4 == 1) & (yy % 4 == 1),
        LU_FAIR: (xx + yy) % 5 == 0,
    }
    for code, m in pat.items():
        img[(lu == code) & m] = PAT[code]
    # moat: water strip just outside the walls
    wallm = t.occ == WALL
    inside = t.inside_mask()
    moat = (ndimage.binary_dilation(wallm, iterations=5)
            & ~ndimage.binary_dilation(wallm, iterations=2) & ~inside)
    img[moat] = C_WATER
    for r in visible_roads(t):
        x0, y0, x1, y1 = [max(0, min(G, v)) for v in r["rect"]]
        col = {"main": C_MAIN, "street": C_STREET, "track": C_TRACK}[r["kind"]]
        if r["kind"] == "main":
            continue
        if r.get("lazy") and r["name"] not in t.active:
            # a planned street with no house yet is only a dirt path
            if x1 - x0 > y1 - y0:
                y0, y1 = y0 + 1, y0 + 2
            else:
                x0, x1 = x0 + 1, x0 + 2
            col = C_TRACK
        img[y0:y1, x0:x1] = col
    for r in t.roads:
        if r["kind"] == "main":
            x0, y0, x1, y1 = [max(0, min(G, v)) for v in r["rect"]]
            img[y0:y1, x0:x1] = C_MAIN
    img[C - PLAZA:C + PLAZA, C - PLAZA:C + PLAZA] = C_PLAZA
    return Image.fromarray(img).resize((G * PX, G * PX), Image.NEAREST)


def gates_of(t):
    gates = []
    for w in t.walls:
        for (xa, ya, xb, yb) in w["segs"]:
            for r in t.roads:
                if r["kind"] not in ("main",):
                    continue
                x0, y0, x1, y1 = r["rect"]
                if ya == yb:  # horizontal wall, vertical road
                    if y0 <= ya <= y1 and min(xa, xb) < x0 and x1 < max(xa, xb) \
                            and (x1 - x0) < (y1 - y0):
                        gates.append(("h", (x0 + x1) / 2, ya, x1 - x0))
                else:
                    if x0 <= xa <= x1 and min(ya, yb) < y0 and y1 < max(ya, yb) \
                            and (y1 - y0) < (x1 - x0):
                        gates.append(("v", xa, (y0 + y1) / 2, y1 - y0))
    return gates


def draw_walls(d, t):
    gates = gates_of(t)
    th = 3 * PX
    for w in t.walls:
        for (xa, ya, xb, yb) in w["segs"]:
            X0, Y0 = min(xa, xb) * PX, min(ya, yb) * PX
            X1, Y1 = max(xa, xb) * PX, max(ya, yb) * PX
            # cut holes at gates
            cuts = []
            for (o, gx, gy, gw) in gates:
                if ya == yb and o == "h" and gy == ya and X0 <= gx * PX <= X1:
                    cuts.append((gx * PX - (gw / 2 + 1) * PX, gx * PX + (gw / 2 + 1) * PX))
                if xa == xb and o == "v" and gx == xa and Y0 <= gy * PX <= Y1:
                    cuts.append((gy * PX - (gw / 2 + 1) * PX, gy * PX + (gw / 2 + 1) * PX))
            cuts.sort()
            if ya == yb:
                pos = X0 - th / 2
                for a, b in cuts + [(X1 + th / 2, X1 + th / 2)]:
                    if a > pos:
                        d.rectangle([pos, Y0 - th / 2, a, Y0 + th / 2], fill=C_WALL)
                    pos = b
            else:
                pos = Y0 - th / 2
                for a, b in cuts + [(Y1 + th / 2, Y1 + th / 2)]:
                    if a > pos:
                        d.rectangle([X0 - th / 2, pos, X0 + th / 2, a], fill=C_WALL)
                    pos = b
            # towers at the ends and along the wall
            L = max(X1 - X0, Y1 - Y0)
            n = max(1, int(L / (30 * PX)))
            for i in range(n + 1):
                f = i / n
                tx = X0 + (X1 - X0) * f
                ty = Y0 + (Y1 - Y0) * f
                if any(abs((gx if ya == yb else gy) * PX - (tx if ya == yb else ty))
                       < 9 * PX and (gy == ya if ya == yb else gx == xa)
                       for (o, gx, gy, gw) in gates):
                    continue
                s = 3.2 * PX
                d.rectangle([tx - s, ty - s, tx + s, ty + s], fill=C_TOWER)
    for (o, gx, gy, gw) in gates:
        s = 3.4 * PX
        off = (gw / 2 + 2.5) * PX
        if o == "h":
            pts = [(gx * PX - off, gy * PX), (gx * PX + off, gy * PX)]
        else:
            pts = [(gx * PX, gy * PX - off), (gx * PX, gy * PX + off)]
        for (px, py) in pts:
            d.rectangle([px - s, py - s, px + s, py + s], fill=C_TOWER)
        # red bar across the road: the gate, where guards stand
        if o == "h":
            d.rectangle([gx * PX - (gw / 2) * PX, gy * PX - 1.0 * PX,
                         gx * PX + (gw / 2) * PX, gy * PX + 1.0 * PX], fill=C_GATE)
        else:
            d.rectangle([gx * PX - 1.0 * PX, gy * PX - (gw / 2) * PX,
                         gx * PX + 1.0 * PX, gy * PX + (gw / 2) * PX], fill=C_GATE)


def render_map(t):
    im = raster(t).convert("RGB")
    d = ImageDraw.Draw(im)
    d.rectangle([(C - PLAZA) * PX, (C - PLAZA) * PX, (C + PLAZA) * PX - 1,
                 (C + PLAZA) * PX - 1], outline=(168, 140, 85), width=2)
    for rc in t.plots:
        x0, y0, x1, y1 = rc
        d.rectangle([x0 * PX, y0 * PX, x1 * PX - 1, y1 * PX - 1],
                    outline=C_PLOT, width=1)
    for sp in t.specials:
        if sp["code"] == LU_CEMETERY:
            x0, y0, x1, y1 = sp["rect"]
            for cx in range(x0 + 2, x1 - 1, 4):
                for cy in range(y0 + 2, y1 - 1, 4):
                    X, Y = cx * PX + 2, cy * PX + 2
                    d.line([X - 4, Y, X + 4, Y], fill=(90, 90, 90), width=2)
                    d.line([X, Y - 5, X, Y + 5], fill=(90, 90, 90), width=2)
        if sp["code"] in (LU_FAIR, LU_POND, LU_GARDEN, LU_CEMETERY):
            x0, y0, x1, y1 = sp["rect"]
            d.rectangle([x0 * PX, y0 * PX, x1 * PX - 1, y1 * PX - 1],
                        outline=(120, 105, 80), width=2)
    for b in t.buildings:
        x0, y0, x1, y1 = b["rect"]
        fill, line = (C_CHURCH, C_CHURCH_LINE) if b["kind"] == "church" \
            else (C_BLD, C_BLD_LINE)
        d.rectangle([x0 * PX, y0 * PX, x1 * PX - 1, y1 * PX - 1], fill=fill,
                    outline=line, width=2)
    draw_walls(d, t)
    f = font(22, bold=True)
    for sp in t.specials:
        if sp["label"]:
            x0, y0, x1, y1 = sp["rect"]
            X, Y = (x0 + x1) / 2 * PX, y1 * PX + 4
            d.text((X, Y), sp["label"], font=f, fill=(30, 30, 30),
                   anchor="ma", stroke_width=3, stroke_fill=(255, 255, 255))
    return im


LEGEND = [
    ("wall", "Mur d'enceinte"), ("tower", "Tour"), ("gate", "Porte (garde)"),
    ("water", "Fossé, étang"), ("plaza", "Place centrale"),
    ("main", "Route principale"), ("street", "Rue"), ("track", "Chemin"),
    ("bld", "Bâtiment"), ("church", "Église, couvent"),
    ("garden", "Jardin, cour"), ("field", "Champ"), ("orchard", "Verger"),
    ("vine", "Vigne"), ("pasture", "Pré, pâture"),
    ("cemetery", "Cimetière"), ("fair", "Marché, foire"),
    ("bare", "Sol nu intra-muros"), ("plot", "Parcelle prévue"),
    ("country", "Campagne"),
]


def swatch(d, kind, x, y, s):
    box = [x, y, x + s, y + s]
    if kind == "wall":
        d.rectangle([x, y + s / 2 - 5, x + s, y + s / 2 + 5], fill=C_WALL)
    elif kind == "tower":
        d.rectangle([x + 4, y + 4, x + s - 4, y + s - 4], fill=C_TOWER)
    elif kind == "gate":
        d.rectangle([x, y + 3, x + 8, y + s - 3], fill=C_TOWER)
        d.rectangle([x + s - 8, y + 3, x + s, y + s - 3], fill=C_TOWER)
        d.rectangle([x + 8, y + s / 2 - 3, x + s - 8, y + s / 2 + 3], fill=C_GATE)
    elif kind == "water":
        d.rectangle(box, fill=C_WATER)
    elif kind == "plaza":
        d.rectangle(box, fill=C_PLAZA, outline=(168, 140, 85), width=2)
    elif kind == "main":
        d.rectangle([x, y + 6, x + s, y + s - 6], fill=C_MAIN)
    elif kind == "street":
        d.rectangle([x, y + 9, x + s, y + s - 9], fill=C_STREET)
    elif kind == "track":
        d.rectangle([x, y + 11, x + s, y + s - 11], fill=C_TRACK)
    elif kind == "bld":
        d.rectangle(box, fill=C_BLD, outline=C_BLD_LINE, width=2)
    elif kind == "church":
        d.rectangle(box, fill=C_CHURCH, outline=C_CHURCH_LINE, width=2)
    elif kind == "plot":
        d.rectangle(box, fill=COL[LU_GARDEN], outline=C_PLOT, width=2)
        d.line([x + s / 2, y, x + s / 2, y + s], fill=C_PLOT, width=2)
    else:
        code = {"garden": LU_GARDEN, "field": LU_FIELD, "orchard": LU_ORCHARD,
                "vine": LU_VINE, "pasture": LU_PASTURE, "cemetery": LU_CEMETERY,
                "fair": LU_FAIR, "bare": LU_BARE,
                "country": LU_COUNTRY_FIELD}[kind]
        d.rectangle(box, fill=COL[code])
        if code in PAT:
            pc = PAT[code]
            if code in (LU_FIELD, LU_COUNTRY_FIELD):
                for i in range(2, s, 5):
                    d.line([x + i, y, x + i, y + s], fill=pc, width=2)
            elif code in (LU_ORCHARD, LU_CEMETERY):
                for i in range(4, s, 8):
                    for j in range(4, s, 8):
                        d.rectangle([x + i, y + j, x + i + 3, y + j + 3], fill=pc)
            elif code == LU_VINE:
                for j in range(2, s, 6):
                    d.line([x, y + j, x + s, y + j], fill=pc, width=2)
            elif code == LU_FAIR:
                for i in range(3, s - 2, 6):
                    d.line([x + i, y + 2, x + 2, y + i], fill=pc, width=2)
        d.rectangle(box, outline=(150, 150, 150), width=1)


def legend_image(width, cols=4, size=20):
    f = font(size)
    s = int(size * 1.4)
    rows = math.ceil(len(LEGEND) / cols)
    rh = s + 14
    im = Image.new("RGB", (width, rows * rh + 24), (250, 248, 242))
    d = ImageDraw.Draw(im)
    cw = (width - 20) / cols
    for i, (k, label) in enumerate(LEGEND):
        c, r = i % cols, i // cols
        x, y = 14 + c * cw, 12 + r * rh
        swatch(d, k, x, y, s)
        d.text((x + s + 10, y + s / 2), label, font=f, fill=(30, 30, 30),
               anchor="lm")
    return im


def stats(t):
    outer = t.encl[-1] if t.encl else t.core_rect
    if len(t.walls) and all(len(w["segs"]) == 4 for w in t.walls):
        dens = t.density(outer)
    else:
        m = t.inside_mask()
        area = m.sum()
        bm = np.zeros((G, G), bool)
        for b in t.buildings:
            x0, y0, x1, y1 = b["rect"]
            bm[y0:y1, x0:x1] = True
        dens = (bm & m).sum() / max(1, area)
    m = t.inside_mask()
    n_out = sum(1 for b in t.buildings
                if not m[int(center_of(b["rect"])[1]), int(center_of(b["rect"])[0])])
    return (f"{len(t.buildings)} bâtiments, {len(t.walls)} enceinte(s), "
            f"{n_out} hors les murs, bâti/surface close : {100*dens:.0f} %")


def header(width, title, sub, size=30):
    f1, f2 = font(size, bold=True), font(int(size * .68))
    h = int(size * 2.6)
    im = Image.new("RGB", (width, h), (250, 248, 242))
    d = ImageDraw.Draw(im)
    d.text((16, 10), title, font=f1, fill=(20, 20, 20))
    d.text((16, 14 + size * 1.25), sub, font=f2, fill=(70, 70, 70))
    return im


def stack(images, bg=(250, 248, 242)):
    w = max(i.width for i in images)
    h = sum(i.height for i in images)
    out = Image.new("RGB", (w, h), bg)
    y = 0
    for i in images:
        out.paste(i, (0, y))
        y += i.height
    return out


def grid_sheet(panels, cols, cell_w):
    rows = math.ceil(len(panels) / cols)
    ph = max(p.height for p in panels)
    out = Image.new("RGB", (cols * cell_w + (cols + 1) * 10,
                            rows * ph + (rows + 1) * 10), (250, 248, 242))
    for i, p in enumerate(panels):
        c, r = i % cols, i // cols
        out.paste(p, (10 + c * (cell_w + 10), 10 + r * (ph + 10)))
    return out


def panel(t, title, sub, w=590):
    m = render_map(t).resize((w, w), Image.LANCZOS)
    return stack([header(w, title, sub, size=22), m])


STRATS = {
    "s1": ("Stratégie 1 – Noyau muré, faubourgs, nouveau mur quand c'est plein",
           strat_s1),
    "s2": ("Stratégie 2 – Grandes enceintes fixes, terres cultivées dedans",
           strat_s2),
    "s3": ("Stratégie 3 – Croissance en étoile, faubourgs murés à part",
           strat_s3),
    "s4": ("Stratégie 4 – Enceinte planifiée à la taille finale, parcelles",
           strat_s4),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    legend_full = legend_image(1200)
    # current state
    cur = strat_current(SEED)[0]
    title = "État actuel – « enclos vides »"
    im = stack([header(1200, title, stats(cur)), render_map(cur), legend_full])
    im.save(os.path.join(OUT, "s0_actuel.png"))
    print("s0", stats(cur))
    for b in range(4):
        R = BANDS[b]
        inner = (C - BANDS[b - 1], C - BANDS[b - 1], C + BANDS[b - 1],
                 C + BANDS[b - 1]) if b else None
        print("  band", b, f"{100*cur.density((C-R, C-R, C+R, C+R), inner):.1f} %")
    all_snaps = {}
    for key, (title, fn) in STRATS.items():
        snaps = fn(SEED)
        assert len(snaps) == len(STAGES), (key, len(snaps))
        all_snaps[key] = snaps
        panels = []
        for i, s in enumerate(snaps):
            sub = f"Étape {i+1} : {s.label} de la population finale – {stats(s)}"
            im = stack([header(1200, title, sub), render_map(s), legend_full])
            im.save(os.path.join(OUT, f"{key}_etape{i+1}.png"))
            panels.append(panel(s, f"Étape {i+1} – {s.label}", stats(s)))
            print(key, i + 1, stats(s))
        panels.append(legend_panel(590, panels[0].height))
        sheet = grid_sheet(panels, 3, 590)
        sheet = stack([header(sheet.width, title,
                              "Même graine, même ville finale : 20 %, 40 %, "
                              "60 %, 80 % et 100 % de la population finale"),
                       sheet])
        sheet.save(os.path.join(OUT, f"{key}_planche.png"))
    # overview sheets
    for i, lab in [(1, "40"), (4, "100")]:
        panels = []
        if lab == "100":
            panels.append(panel(cur, "État actuel", stats(cur)))
        for key, (title, fn) in STRATS.items():
            s = all_snaps[key][i]
            panels.append(panel(s, SHORT[key], stats(s)))
        if lab == "100":
            panels.append(legend_panel(590, panels[0].height))
            sheet = grid_sheet(panels, 3, 590)
            parts = [sheet]
        else:
            sheet = grid_sheet(panels, 2, 590)
            parts = [sheet, legend_image(sheet.width, size=17)]
        sheet = stack([header(sheet.width, f"Comparaison à {lab} % "
                              "de la population finale",
                              "Même nombre final de bâtiments (56) "
                              "pour toutes les stratégies")] + parts)
        sheet.save(os.path.join(OUT, f"comparaison_{lab}.png"))


SHORT = {
    "s1": "S1 – Noyau, faubourgs, nouveau mur",
    "s2": "S2 – Grandes enceintes cultivées",
    "s3": "S3 – Étoile, faubourgs murés",
    "s4": "S4 – Enceinte planifiée, parcelles",
}


def legend_panel(w, h):
    im = Image.new("RGB", (w, h), (250, 248, 242))
    d = ImageDraw.Draw(im)
    d.text((16, 14), "Légende", font=font(22, bold=True), fill=(20, 20, 20))
    leg = legend_image(w, cols=2, size=17)
    im.paste(leg, (0, 52))
    return im


if __name__ == "__main__":
    main()
