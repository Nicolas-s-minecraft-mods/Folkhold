#!/usr/bin/env python3
"""Generate the logo of each Folkhold mod.

Output, for each module (core, blocks, world, balance, events):
- <module>/src/main/resources/folkhold_<module>_logo.png

    python3 tools/textures/mod_logos.py

A logo is a pixel drawing of 16 x 16 cells on a colored field with a darker border, as a PNG of 512 x 512 pixels.
The mod list of the game shows it (`logoFile` in `neoforge.mods.toml`), and the project page of each mod uses it
(see docs/curseforge/).

- core: the village heart, a crystal on a stone base.
- blocks: a block seen from a corner.
- world: a tower of the palisade with a banner.
- balance: a minecart on its rails.
- events: a storm cloud with a lightning bolt over cracked ground.

Requires Pillow and PixVox (tools/pixvox/requirements.txt). A PNG file is not written when its pixels do not change
(png_file.write_png).
"""
import os

from PIL import Image

from png_file import write_png

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
# The width of the border of the field, in pixels.
BORDER = 20
# The size of the image, the number of cells on each side, and the margin around the drawing, in pixels.
SIZE, GRID, MARGIN = 512, 16, 64
CELL = (SIZE - 2 * MARGIN) // GRID

PALETTE = {
    ".": None,
    "k": (27, 28, 34),      # outline
    "w": (240, 236, 222),   # light
    "s": (150, 150, 156),   # stone
    "S": (96, 98, 108),     # dark stone
    "b": (122, 82, 48),     # wood
    "B": (82, 54, 32),      # dark wood
    "l": (176, 128, 78),    # light wood
    "g": (104, 168, 86),    # grass
    "G": (62, 116, 60),     # dark grass
    "r": (196, 62, 54),     # red banner
    "R": (136, 38, 40),     # dark red
    "y": (246, 204, 72),    # yellow
    "o": (232, 142, 48),    # orange
    "c": (92, 208, 204),    # cyan
    "C": (44, 140, 150),    # dark cyan
    "i": (188, 196, 206),   # iron
    "I": (118, 128, 142),   # dark iron
    "p": (150, 104, 196),   # purple
}

LOGOS = {
    # The village heart: a crystal on a stone base.
    "core": ((38, 52, 74), (24, 32, 48), [
        "................",
        ".......kk.......",
        "......kwck......",
        ".....kwccCk.....",
        "....kwcccCCk....",
        "...kwccccCCCk...",
        "...kcccccCCCk...",
        "....kcccCCCk....",
        ".....kccCCk.....",
        "......kcCk......",
        ".......kk.......",
        "....kkkkkkkk....",
        "...ksssssssSk...",
        "..kssssssssSSk..",
        "..kSSSSSSSSSSk..",
        "..kkkkkkkkkkkk..",
    ]),
    # A block: a cube seen from a corner.
    "blocks": ((74, 58, 44), (48, 36, 28), [
        "................",
        ".......kk.......",
        ".....kkllkk.....",
        "...kkllllllkk...",
        ".kkllllllllllkk.",
        ".kbkkllllllkkBk.",
        ".kbbbkkllkkBBBk.",
        ".kbbbbbkkBBBBBk.",
        ".kbbbbbkBBBBBBk.",
        ".kbbbbbkBBBBBBk.",
        ".kbbbbbkBBBBBBk.",
        ".kbbbbbkBBBBBBk.",
        ".kkkbbbkBBBBkkk.",
        "....kkbkBBkk....",
        "......kkkk......",
        "................",
    ]),
    # A tower of the palisade with a banner, on grass.
    "world": ((70, 110, 150), (40, 68, 100), [
        ".......k........",
        ".......krrr.....",
        ".......krrRr....",
        ".......krrr.....",
        ".......k........",
        "...kkkkkkkkkk...",
        "...kBBBBBBBBk...",
        "....kbkbbkbk....",
        "....kbbbbbbk....",
        ".k.kkbbkkbbkk.k.",
        "kbkbkbbkkbbkbkbk",
        "kbkbkbbbbbbkbkbk",
        "kbkbkbkkkkbkbkbk",
        "kbkbkbkBBkbkbkbk",
        "kgkgkgkBBkgkgkgk",
        "GGGGGGGGGGGGGGGG",
    ]),
    # A minecart on its rails.
    "balance": ((96, 78, 54), (62, 48, 32), [
        "................",
        "................",
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kiiiiiiiiiiiik.",
        ".kiIIIIIIIIIIik.",
        ".kiIyyIooIyyIik.",
        ".kiIyyIooIyyIik.",
        ".kiIIIIIIIIIIik.",
        "..kiiiiiiiiiik..",
        "...kkkkkkkkkk...",
        "...kSk....kSk...",
        "...kkk....kkk...",
        "iiiiiiiiiiiiiiii",
        "kbkkbkkbkkbkkbkk",
        "................",
    ]),
    # A storm: a cloud, a lightning bolt and cracked ground.
    "events": ((58, 54, 84), (34, 32, 54), [
        "................",
        "....kkkk........",
        "..kksssskkkk....",
        ".ksssssssSSSkk..",
        "kssssssSSSSSSSk.",
        "kSSSSSSSSSSSSSk.",
        ".kkkkkkykkkkkk..",
        "......kyyk......",
        ".....kyyk.......",
        "....kyyyyk......",
        ".....kkyyk......",
        "......kyk.......",
        "......kyk.......",
        "GGGGGkGkyGGkGGGG",
        "bbbbkbbkbbkbbbbb",
        "BBBkBBBBkBBBkBBB",
    ]),
}


def logo(name):
    """Return the image of the logo of a module."""
    field, edge, rows = LOGOS[name]
    if len(rows) != GRID or any(len(row) != GRID for row in rows):
        raise ValueError(f"The drawing of {name} is not {GRID} x {GRID}")
    image = Image.new("RGBA", (SIZE, SIZE), edge + (255,))
    image.paste(field + (255,), (BORDER, BORDER, SIZE - BORDER, SIZE - BORDER))
    for gy, row in enumerate(rows):
        for gx, key in enumerate(row):
            color = PALETTE[key]
            if color is not None:
                left, top = MARGIN + gx * CELL, MARGIN + gy * CELL
                image.paste(color + (255,), (left, top, left + CELL, top + CELL))
    return image


def main():
    for name in LOGOS:
        out = os.path.join(ROOT, name, "src", "main", "resources", f"folkhold_{name}_logo.png")
        if write_png(out, logo(name)):
            print("Wrote", os.path.relpath(out))
        else:
            print("Same pixels, not written:", os.path.relpath(out))


if __name__ == "__main__":
    main()
