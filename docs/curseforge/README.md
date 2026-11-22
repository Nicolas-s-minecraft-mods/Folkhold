# CurseForge project pages

This folder has the text of the CurseForge page of each Folkhold mod. Each mod is one CurseForge project. Dependencies
link the projects. Issue #882 tracks the publication.

A file `<module>.md` has the summary and the description of one project. Copy its parts into the fields of the
project. Change the page on CurseForge only through a change of this folder.

## Projects

| Module | Project name | Project id | Class | Categories (the first one is the main category) |
| --- | --- | --- | --- | --- |
| `core` | Folkhold Core | 1821089 | Mods | API and Library |
| `blocks` | Folkhold Blocks | 1821098 | Mods | API and Library, Cosmetic |
| `world` | Folkhold World | 1821107 | Mods | Adventure and RPG, World Gen (Structures), Mobs |
| `balance` | Folkhold Balance | 1821102 | Mods | Technology (Player Transport), Redstone, Utility & QoL |
| `events` | Folkhold Events | 1821115 | Mods | World Gen, Adventure and RPG |

All the projects have these values:

- Game version: Minecraft 1.21.1. Mod loader: NeoForge. Java 21.
- License: MIT License (the code). The page of each project names the license of the assets: CC BY 4.0
  (see [ASSETS_LICENSES.md](../ASSETS_LICENSES.md)).
- Source: <https://github.com/nicolas-goyon/Folkhold>. Issues: <https://github.com/nicolas-goyon/Folkhold/issues>.
- Languages: English and French.
- The logo is `<module>/src/main/resources/folkhold_<module>_logo.png` (512 x 512 pixels, from
  `tools/textures/mod_logos.py`).
- Side: client and server.

## Dependencies

The relations of each CurseForge file. They are the required dependencies of `neoforge.mods.toml` of the module.

| Project | Required dependency | Minimum version |
| --- | --- | --- |
| Folkhold Core | none | |
| Folkhold Blocks | none | |
| Folkhold World | Folkhold Core | the same version |
| Folkhold World | Folkhold Blocks | the same version |
| Folkhold World | GeckoLib | 4.9.3 |
| Folkhold World | Cartofold | 0.39.3 |
| Folkhold World | Confee | 0.2.0 |
| Folkhold Balance | none | |
| Folkhold Events | Folkhold Core | the same version |
| Folkhold Events | Confee | 0.2.0 |

- All the Folkhold mods share one version. Upload the 5 jars of one release together.
- A relation needs a CurseForge project on the other side. GeckoLib and Confee (slug `confee`) are on CurseForge.
  Cartofold must be a CurseForge project before the upload of Folkhold World.
- Confee is required only on the client (`side = "CLIENT"` in `neoforge.mods.toml`). A dedicated server starts
  with no Confee. CurseForge has no side for a relation: the relation is a required dependency.
- NeoForge and Minecraft are not relations: they are the loader and the game version of the file.

## Upload of a release

The workflow Release uploads the 5 jars of a release with `scripts/curseforge_publish.py`. The file
[projects.json](projects.json) gives the id and the slug of each project and the slugs of the projects that it
requires. A test compares these requirements with `neoforge.mods.toml`.

- The repository secret `CURSEFORGE_TOKEN` has the API token of the CurseForge account of the owner. With no secret,
  the workflow uploads nothing.
- Each file has the game versions 1.21.1, NeoForge, Java 21, Client and Server, and the notes of the release as its
  changelog.
- The release type is beta while the major version is 0.
- To see the metadata of the files and send nothing:
  `python3 scripts/curseforge_publish.py <version> <notes file> --dry-run`.

## Screenshots

The owner makes the screenshots in the game. Each project needs 3 screenshots or more that show its content. The
libraries (Core, Blocks) can show the mods that use them.
