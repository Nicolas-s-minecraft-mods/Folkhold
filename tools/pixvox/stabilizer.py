"""Stabilizer: a reinforced foundation stone that protects the blocks near it from the natural events.

Courses of grey stone with dark joints. A copper band goes around the middle of the sides, and copper bolts hold the
band. The top has a copper cross from edge to edge, so a floor of stabilizers shows a grid of copper.
"""
import numpy as np

from _palette import COPPER, STONE
from _patterns import masonry
from pixvox import Block, Volume


def build():
    v = Volume(16)
    joints = masonry(v, STONE, course=4, length=8, offsets=(2, 6), seed=17)
    v.noise(STONE, cells=4, octaves=2, seed=19, mask=~joints, weights=[0, 1, 3, 2, 0])
    sides = np.minimum.reduce([v.X, 15 - v.X, v.Z, 15 - v.Z]) == 0
    top = v.Y == 15

    # The copper band around the middle of the sides: a light upper row and a dark lower row.
    band = sides & (v.Y >= 6) & (v.Y <= 9)
    v.set(band, COPPER[2])
    v.set(band & (v.Y == 9), COPPER[3])
    v.set(band & (v.Y == 6), COPPER[1])
    # A bolt on the band, 2 on each side, away from the vertical edges.
    along = np.where((v.Z == 0) | (v.Z == 15), v.X, v.Z)
    bolt = band & ((v.Y == 7) | (v.Y == 8)) & ((along == 4) | (along == 11))
    v.set(bolt, COPPER[4])

    # The copper cross on the top, with a bolt in the middle.
    cross = top & ((v.X == 7) | (v.X == 8) | (v.Z == 7) | (v.Z == 8))
    v.set(cross, COPPER[2])
    center = top & (v.X >= 6) & (v.X <= 9) & (v.Z >= 6) & (v.Z <= 9)
    v.set(center, COPPER[3])
    v.set(top & (v.X >= 7) & (v.X <= 8) & (v.Z >= 7) & (v.Z <= 8), COPPER[4])
    return Block("stabilizer", v, seamless=False)
