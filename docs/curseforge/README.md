# CurseForge project pages

This folder has the text of the CurseForge page of each Folkhold mod. Each mod is one CurseForge project. Dependencies
link the projects. Issue #882 tracks the publication.

A file `<module>.md` has the summary and the description of one project. Copy its parts into the fields of the
project. Change the page on CurseForge only through a change of this folder.

## Projects

| Module | Project name | Logo | Categories | Side |
| --- | --- | --- | --- | --- |
| `core` | Folkhold Core | `core/src/main/resources/folkhold_core_logo.png` | API and Library | Client and server |
| `blocks` | Folkhold Blocks | `blocks/src/main/resources/folkhold_blocks_logo.png` | API and Library, Cosmetic | Client and server |
| `world` | Folkhold World | `world/src/main/resources/folkhold_world_logo.png` | Adventure and RPG, World Gen, Mobs | Client and server |
| `balance` | Folkhold Balance | `balance/src/main/resources/folkhold_balance_logo.png` | Technology, Redstone, Tweaks | Client and server |
| `events` | Folkhold Events | `events/src/main/resources/folkhold_events_logo.png` | World Gen, Adventure and RPG | Client and server |

All the projects have these values:

- Game version: Minecraft 1.21.1. Mod loader: NeoForge. Java 21.
- License: MIT License (the code). The page of each project names the license of the assets: CC BY 4.0
  (see [ASSETS_LICENSES.md](../ASSETS_LICENSES.md)).
- Source: <https://github.com/nicolas-goyon/Folkhold>. Issues: <https://github.com/nicolas-goyon/Folkhold/issues>.
- Languages: English and French.
- The logo comes from `tools/textures/mod_logos.py` (512 x 512 pixels).

## Dependencies

The relations of each CurseForge file. They are the required dependencies of `neoforge.mods.toml` of the module.

| Project | Required dependency | Minimum version |
| --- | --- | --- |
| Folkhold Core | none | |
| Folkhold Blocks | none | |
| Folkhold World | Folkhold Core | the same version |
| Folkhold World | Folkhold Blocks | the same version |
| Folkhold World | GeckoLib | 4.9.3 |
| Folkhold World | Cartofold | 0.33.12 |
| Folkhold Balance | none | |
| Folkhold Events | Folkhold Core | the same version |

- All the Folkhold mods share one version. Upload the 5 jars of one release together.
- A relation needs a CurseForge project on the other side. GeckoLib is on CurseForge. Cartofold must be a
  CurseForge project before the upload of Folkhold World.
- NeoForge and Minecraft are not relations: they are the loader and the game version of the file.

## Screenshots

The owner makes the screenshots in the game. Each project needs 3 screenshots or more that show its content. The
libraries (Core, Blocks) can show the mods that use them.
