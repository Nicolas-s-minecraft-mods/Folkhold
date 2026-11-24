# Getting started

This guide explains how to prepare a computer for Folkhold development, build the mods and test them in the game.

The goal is a clean host machine. The JDK, Gradle, packwiz and the Minecraft development files stay in a Docker container and in one Docker volume. To remove everything, remove the container image and the volume (see [Clean up](#clean-up)).

## What runs where

| Task | Where | Tool |
| --- | --- | --- |
| Edit code | Host, connected to the container | VS Code + Dev Containers (recommended) |
| Build, unit tests, datagen, translation check | Container | Gradle wrapper (`./gradlew`) |
| Dedicated server for tests, with debugger | Container (ports 25565 and 5005) | `./gradlew :dev:runServer` |
| GameTests | Container | `./gradlew :dev:runGameTestServer` |
| Modpack (add, update, export) | Container | packwiz |
| Game client | Host | Prism Launcher (it manages its own Java) |

The game client cannot run in the container, because the container has no display and no GPU. Most of the Folkhold logic runs on the server, so the server in the container plus a normal client on the host covers most tests.

## Prerequisites

Install these tools on the host. You do not need a JDK on the host.

| Tool | Why | Link |
| --- | --- | --- |
| Git | Clone and commit | https://git-scm.com/downloads |
| Docker Desktop (WSL 2 back end on Windows) | Runs the dev container | https://www.docker.com/products/docker-desktop/ |
| VS Code + extension "Dev Containers" | Editor connected to the container | https://code.visualstudio.com/ |
| Prism Launcher | Game client for tests | https://prismlauncher.org/ |

Docker Desktop settings: give Docker at least 8 GB of memory. The first Gradle build decompiles Minecraft and needs memory.

Other IDEs: IntelliJ IDEA can also open a dev container. The support depends on the edition and the version. See the JetBrains documentation. All commands in this guide also work from a terminal with `scripts/dev.ps1` (Windows) or `scripts/dev.sh` (Linux, macOS).

## First setup

1. Clone the repository:

   ```powershell
   git clone https://github.com/Nicolas-s-minecraft-mods/Folkhold.git
   cd Folkhold
   ```

   For faster builds on Windows, clone the repository in the WSL file system (for example `\\wsl$\Ubuntu\home\<user>\Folkhold`). You can also use the VS Code command "Dev Containers: Clone Repository in Container Volume". Files on the Windows file system (`C:\...`) are slow in a Linux container.

2. Open the folder in VS Code.

3. Run the command **Dev Containers: Reopen in Container**. The first time, Docker builds the image (approximately 2 minutes).

4. In the terminal of VS Code (it runs in the container), build the project:

   ```bash
   ./gradlew build
   ```

   The first build downloads Gradle, NeoForge and Minecraft, then decompiles Minecraft. It can take 5 to 15 minutes. The next builds take less than one minute. The files stay in the Docker volume `folkhold-gradle-cache`.

5. Run the GameTests:

   ```bash
   ./gradlew :dev:runGameTestServer
   ```

   The server starts, runs all GameTests, then stops. The result is in the console. The GameTest server uses the folder `dev/run-gametest`, with a new world for each run. It does not change the world of the dev server, `dev/run/world`. Each run also starts with the default options. The GameTest server does not watch the options files (`disableConfigWatcher = true` in `dev/run-gametest/config/fml.toml`). A GameTest sets an option in memory, and a reload of the file does not change this value.

   To run one shard of the GameTests, as a job of the CI does, give the shard: `./gradlew :dev:runGameTestServer -PgametestShard=2/4`. See "GameTest shards" in "Continuous integration and releases".

Without VS Code, use the scripts from a terminal on the host:

```powershell
.\scripts\dev.ps1                       # shell in the container
.\scripts\dev.ps1 ./gradlew build       # one command in the container
```

The scripts check that Docker runs. On Windows, if the computer has Docker Desktop but Docker Desktop does not run, `dev.ps1` starts it, runs the command, then stops Docker Desktop again. To keep it running, set `$env:FOLKHOLD_KEEP_DOCKER = '1'` before the command.

## Project layout

| Folder | Content |
| --- | --- |
| `core/` | Mod `folkhold_core`: shared library. Pure-Java simulation code goes in `core/src/main/java/.../core/sim`. |
| `blocks/` | Mod `folkhold_blocks`: library of the blocks, with the behavior of similar vanilla blocks and no game logic. No dependency. |
| `world/` | Mod `folkhold_world`: factions, villages, economy, quests. Depends on `core` and `blocks`. |
| `balance/` | Mod `folkhold_balance`: limit of the fast nights of Slumber. Planned: farms, villagers, chunk loading. No dependency. |
| `events/` | Mod `folkhold_events`: earthquakes, collapses. Depends on `core`. |
| `dev/` | No code. Loads all mods in one game instance (client, server, GameTest server). |
| `modpack/` | packwiz modpack. |
| `scripts/` | Container shortcuts, client deployment, modpack export, versions, build of the site. |
| `tools/` | Generators (for example structure files). |
| `docs/` | Design document and guides. |
| `site/` | Template, texts and static files of the site of the project (see [Site of the project](#site-of-the-project)). |

Each mod has:

- `src/main/java/io/github/nicolasgoyon/folkhold/<module>/`: Java code.
- `src/main/resources/assets/<mod_id>/lang/en_us.json` and `fr_fr.json`: translations.
- `src/main/templates/META-INF/neoforge.mods.toml`: mod metadata. Gradle replaces the `${...}` values.
- `src/test/java/`: JUnit tests. In `world`, the tests run with the game runtime (registries and mods loaded, no server): a test that reads the config loads it with `TestConfig.loadWorldConfig()`. Pure logic, data and codecs go here. A test that needs a world is a GameTest.
- `src/gametest/java/` and `src/gametest/resources/`: GameTests and their test structures. The dev project loads them with the mod in its runs. The jar of the mod does not contain them.
- `gradle.properties`: `mod_id`, `mod_name`, `mod_description`.

The world mod also contains the optional data pack of the test world, in `world/src/main/resources/datapacks/test_world/` (see [Test world](#test-world)).

The shared versions are in the root `gradle.properties`.

## Daily commands

Run these commands in the container.

| Command | Result |
| --- | --- |
| `./gradlew build` | Compiles with the lints and Error Prone, runs the JUnit tests, the format check and the translation check, makes the jars in `<module>/build/libs/`. |
| `./gradlew test` | JUnit tests only. For `core`, the coverage report of JaCoCo is in `core/build/reports/jacoco/test/html/index.html`. |
| `./gradlew :core:test --tests '*RegionPosTest'` | One test class. |
| `./gradlew spotlessApply` | Fixes the format of the sources (imports, trailing spaces, final newline). |
| `./gradlew checkTranslations` | Checks that `en_us.json` and `fr_fr.json` have the same keys. |
| `./gradlew :dev:runGameTestServer` | Runs all GameTests in a server, in a new world (folder `dev/run-gametest`). |
| `./gradlew :dev:runServer` | Dedicated server with all mods. |

## Test in the game

### 1. Prepare a Prism Launcher instance (one time)

1. In Prism Launcher, click **Add Instance**.
2. Select **Minecraft 1.21.1** and the loader **NeoForge 21.1.259** (the value of `neo_version` in `gradle.properties`).
3. Name the instance `Folkhold-dev`.
4. Start the instance one time, then close the game. Prism creates the game folder.

You can also import the `.mrpack` file that the CI makes (see [Modpack](#modpack)).

### 2. Copy the mods into the instance

On the host, after `./gradlew build`:

```powershell
.\scripts\deploy-client.ps1 -Instance "$env:APPDATA\PrismLauncher\instances\Folkhold-dev"
```

The client and the server must have the same mod versions. Run this script after each build.

### 3. Start the dev server

First time only: read the Minecraft EULA (https://aka.ms/MinecraftEULA). If you agree, create the file `dev/run/eula.txt` with this line:

```
eula=true
```

Then start the server, from the container:

```bash
./gradlew :dev:runServer -PdebugPort=5005
```

Or from a terminal on the host:

```powershell
docker compose up server
```

### 4. Connect

Start `Folkhold-dev` in Prism Launcher. Click **Multiplayer**, then add the server `localhost`.

### 5. Debug

- **Server (container)**: start the server with `-PdebugPort=5005`. In VS Code, run the debug configuration **Attach to dev server (port 5005)**. The breakpoints in the Folkhold code then stop the server.
- **Client (Prism on the host)**: in Prism Launcher, open the instance settings, **Java**, **JVM arguments**, and add:

  ```
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006
  ```

  Start the instance. In VS Code, run **Attach to Prism client on the host (port 5006)**.

Hot reload: while the debugger has a connection to the game, VS Code can replace the body of a changed method without a restart. Changes to class structure (new fields, new methods) need a restart.

## Test world

The data pack "Folkhold test world" adds the world type "Folkhold Test". It is a flat world (plains, no trees) with one faction village next to the spawn point. The village starts in the chunk (10, 0), about 160 blocks to the east of the spawn point. The largest village of the start levels leaves free space around the spawn point. It is a normal faction village: heart, garrison, bank, quest board and settlement in the simulation. A test of the villages starts with no search for a village.

The data pack is part of `folkhold_world` and is off by default. It also changes the placement of the faction villages for all the world types: one village only, in the chunk (10, 0). Turn it on only for a test world.

In the client:

1. Click **Singleplayer**, then **Create New World**.
2. In the tab **More**, click **Data Packs**. Move "Folkhold test world" to the column **Selected**, then click **Done**.
3. In the tab **World**, click **World Type** until the button has the text "Folkhold Test".
4. Click **Create New World**.

On the dev server, the world type and the data pack apply when the server creates a new world. Stop the server, remove the folder `dev/run/world`, then set these two lines in `dev/run/server.properties`:

```properties
level-type=folkhold_world:test
initial-enabled-packs=vanilla,mod/folkhold_world:datapacks/test_world
```

The garrison of the village depends on the level of the village. The healers come from the level 5, the riders from the level 9, and the faction golems from the level 13. To see them, change the level of the village with a command. `/folkhold settlement list` gives the id of the village, then `/folkhold settlement level <id> 17` makes it a capital. The new units come when a player is near the village heart.

To try the construction plans, run `/folkhold construction place` and select `village/house_cottage`. Look at a free area: the ghost of the house follows your view. Press R to turn it and M to mirror it, then press Enter to build it. The command `/folkhold construction build folkhold_world:village/house_cottage 2` also builds the house at your position. Near a faction village, select the speed "Builders". The builders of the village put fences around the work zone, then build the house block by block during the day. Stay out of the fences, else the builders stop. The builders take the materials from the stock of the village: for the rare blocks, the village gives delivery quests. `/folkhold construction sites` shows the sites of the builders and their missing materials. To repair a house of the village, keep its blocks with `/folkhold construction capture <from> <to>`. Break some blocks, then run `/folkhold construction restore`. The section "Construction" of [design.md](design.md) gives the rules of the plans.

To check the building templates of the village, use the debug grid in a test world. The command changes the blocks of its area and has no undo.

- `/folkhold debug buildings <style> [variant]` (operators): the styles are `base`, `maritime`, `sunreach`, `valeholm` and `cave`. The variants are `taiga` (the default), `plains`, `birch`, `savanna`, `desert` and `swamp`. The style `cave` has no variant. The grid has one row for each building of the style. The buildings are the homes, the job houses, the market, the bank, the fields, the mill and the cliff houses. It has one column for each step of a building (step 1, 2 and 3).
- `/folkhold debug buildings compare <building>` (operators): one row with the same template in each style and each variant, for example `job_baker` or `job_baker_step2`.
- The grid starts 3 blocks to the east and 3 blocks to the south of you. The rows go to the south, and the columns go to the east. 5 free blocks separate the buildings. The ground is flat grass at the level under your feet. In front of each building (on its north side), a sign gives the template name. A second sign gives the beds, the job posts, the size and the step.
- The grid of a style is about 45 blocks wide and 600 to 1,000 blocks long. The command loads the chunks, makes the ground flat, then places 2 templates in each tick. The chat shows the progress. One grid runs at a time.

To see a repair, break 3 blocks of the palisade of a faction village with builders. In 60 seconds or less, the builders put fences around the broken blocks, then repair them during the day. `/folkhold construction sites` shows the repair site.

To see the work of the builders while the chunks are not loaded, start a site with the builders during the day. Then go far away from the village. `/folkhold construction sites` shows the steps of work that wait. Come back: the site applies these steps, then the builders go on.

To see the handyman of a faction village at work, stand near the plaza of the village. Run `/time set 11000`: the handyman lights the 4 braziers of the plaza before the night. Run `/time set 0`: in the morning, the handyman puts out the braziers.

## Translations

The mods support English and French. Each text that the player sees needs one key in `en_us.json` and in `fr_fr.json`. `./gradlew build` fails when a key is missing in one file. See [CONTRIBUTING.md](../CONTRIBUTING.md#translations).

The site of the project also has one file for each language (see [Site of the project](#site-of-the-project)).

## Structures

The structure files of the village (`world/src/main/resources/data/folkhold_world/structure/village/`) come from a script. The script also writes the data files of the village structure. These files are the structure (`worldgen/structure/village.json`), its template pools, its processor lists and the tag of its biomes. Do not edit these files by hand. Change the script, then run it in the container:

```bash
python3 tools/structures/village.py
```

The village has:

- a plaza (`plaza.nbt`), the start piece. It has the village heart, 4 braziers (`folkhold_blocks:brazier`), the alarm bell, the quest board, the sign of the hall and lamp posts. A jigsaw connector on each side goes to a street.
- streets (`street.nbt`, `street_bank.nbt`, `street_end.nbt`): a segment with a plot on each side, then a short end with 2 lamp posts. The paths follow the terrain.
- house pieces (`house_*.nbt`) and the bank (`bank.nbt`) for the plots. A plot can stay empty.
- a tower (`tower.nbt`) and a gate (`gate.nbt`) for the wall. Each tower has a watch post (archer post) on a platform with a roof. A stair of 2 rows along the palisade goes up to the platform.

The structure adds the wall around the pieces: the towers at the corners and between them, a gate on each side and the palisade. The palisade comes from code: it follows the terrain (see the design document).

Each biome variant has the same pieces with other materials (`VARIANTS` in the script). The pieces, pools and processor lists of the base variant (spruce) are in the folders of the village. The files of another variant are in a subfolder with its name, for example `structure/village/plains/`. The biomes of the variants give the biomes of the village (`tags/worldgen/biome/has_village.json`): to add a biome, add it to a variant in the script.

The GameTests use a village in one piece (`world/src/gametest/resources/data/folkhold_world/structure/village/center.nbt`): palisade, 4 corner towers, 2 gates, a plaza and 4 plots. The script makes it too.

To see a village in the game: `/locate structure folkhold_world:village`, then `/tp` to the position.

## Textures from a script

Scripts make the textures of the villager and zombie villager professions, the armband of the faction golem and the map decoration icons (world mod). The scripts need Pillow and PixVox: PixVox writes the PNG files with the same bytes on all machines. The container has the two. Outside the container, install them first: `pip install -r tools/pixvox/requirements.txt`. Change a script, then run it:

```bash
python3 tools/textures/villager_professions.py
python3 tools/textures/golem_armband.py
python3 tools/textures/mod_logos.py
python3 tools/textures/map_decorations.py
```

A script does not write a PNG file when its pixels do not change.

## Blocks from PixVox

The blocks of the library `blocks` come from the descriptions in `tools/pixvox`: village heart, watch post, bank chest, bank wall, brazier, building signs, quest board and stabilizer. [PixVox](https://github.com/nicolas-goyon/PixVox) makes them: a description is a volume of voxels in Python, and PixVox reads the texture of each face on the volume. PixVox renders each block as the game does, checks the export, and makes a review board. The container has PixVox. Change a description, then run the export:

```bash
python3 tools/pixvox/export.py            # models/block/<model>.json and textures/block/<model>/
python3 tools/pixvox/export.py --review   # also the review boards, in build/pixvox
```

A description can make more than one model. The brazier has a lit model (`brazier_lit`) and a model that does not burn (`brazier`). The building signs have a model for each kind of building (`building_sign_<kind>`). The quest board has a model for the board, a model for each paper and a model with all the papers for the item. The block states and the item models do not come from PixVox. Two descriptions also run as scripts and write the other resources of their blocks:

- `building_signs.py`: the block states, the item models, the loot tables, the tag and the names of the signs.
- `quest_board.py`: the multipart block state of the 9 parts of the board, the model of the other parts and the item model.

```bash
python3 tools/pixvox/building_signs.py
python3 tools/pixvox/quest_board.py
```

Look at the review board of each changed model (`build/pixvox/<model>.review.png`) before the commit. An animated model also has an animation board (`<model>.anim.png`). Outside the container, install PixVox first: `pip install -r tools/pixvox/requirements.txt`.

## Test structures

The GameTest templates `empty`, `arena`, `arena_large` and `arena_huge` are boxes of air. The tests build their floor and their walls. The script writes a template:

```bash
python3 tools/make_test_structure.py world/src/gametest/resources/data/folkhold_world/structure/empty.nbt
python3 tools/make_test_structure.py world/src/gametest/resources/data/folkhold_world/structure/arena.nbt --size 16 4 16
python3 tools/make_test_structure.py world/src/gametest/resources/data/folkhold_world/structure/arena_large.nbt --size 32 4 32
python3 tools/make_test_structure.py world/src/gametest/resources/data/folkhold_world/structure/arena_huge.nbt --size 56 4 56
```

The workflow **Generated files** runs all the scripts of `tools/`. It fails when their output differs from the repository. Commit the output of a script with the script.

To replace a generated house with a house that you build by hand:

1. Build the house in creative mode.
2. Save it with a structure block (name `folkhold_world:village/house_<name>`).
3. Copy the file from the world folder (`generated/folkhold_world/structure/`) to the `village` folder.
4. Add it to `worldgen/template_pool/village/houses.json`.

The porch row (z = 0) must contain the jigsaw block (name `folkhold_world:house_front`, facing out).

## Cartofold dependency

The world mod requires Cartofold. Gradle downloads the jar of the GitHub Release of Cartofold (`cartofold_version` in `gradle.properties`) with no token. Before the compilation, the task `verifyCartofold` compares the sha256 of the jar with the hash in `modpack/mods/cartofold.pw.toml`.

Gradle sends a HEAD request before the download. Behind some proxies, this request fails with an error 401. Then use a local folder:

```bash
scripts/fetch-cartofold.sh ~/cartofold    # downloads with GET and checks the sha256
./gradlew build -PcartofoldDir=$HOME/cartofold
```

To use the folder for each build, put `cartofoldDir=/home/dev/cartofold` (an absolute path) in `~/.gradle/gradle.properties`. After a change of `cartofold_version`, run the script again.

The published POM of `folkhold_world` (GitHub Packages) has a runtime dependency on `io.github.nicolasgoyon.cartofold:cartofold`. A project that uses `folkhold_world` must declare the ivy repository of `gradle/repositories.gradle`, or the GitHub Packages repository of Cartofold (token with `read:packages`).

## Confee dependency

The world mod and the events mod require Confee on the client: Confee gives the config screen of their client options. Gradle downloads the NeoForge jar of the GitHub Release of Confee with no token (`gradle/repositories.gradle`). `confee_version` in `gradle.properties` is the version of the mod, and `confee_release` is the tag of the release. Confee is not on Modrinth and has no Maven repository. Before the compilation, the task `verifyConfee` (`gradle/confee.gradle`) compares the sha256 of the jar with the hash in `modpack/mods/confee.pw.toml`.

The published POM of `folkhold_world` and `folkhold_events` has a runtime dependency on `fr.heetstudio.confee:confee`. A project that uses these mods must declare the ivy repository of `gradle/repositories.gradle`.

## Modpack

The modpack is in `modpack/` (packwiz). Use packwiz in the container:

```bash
cd modpack
packwiz modrinth add sodium      # add a mod from Modrinth
packwiz update --all             # update all mods
packwiz pin <mod>                # stop updates of a mod
```

After a change of the mods, check their required dependencies. The script downloads each mod jar of the modpack and reads its required dependencies and its embedded mods. It fails when a required mod is not in the modpack, or when its version is not in the range. The option `--jars` adds the Folkhold jars of a folder. The CI runs this check in the job **Modpack**.

```bash
python3 scripts/modpack_deps.py --cache build/modpack-cache
```

To make a `.mrpack` file with the Folkhold mods inside:

```bash
./gradlew build
scripts/export-modpack.sh build/folkhold-dev.mrpack
```

The CI makes the same file for each build (artifact `folkhold-modpack`), with the version of the build (see [Continuous integration and releases](#continuous-integration-and-releases)).

### Dev instance that updates itself

After each green build of a push on `develop`, the site of the project contains the dev pack: <https://nicolas-s-minecraft-mods.github.io/Folkhold/dev/pack.toml>. A Prism Launcher instance updates itself from this pack before each start of the game:

1. In Prism Launcher, select **Add Instance**, then **Import**.
2. Paste this URL: <https://nicolas-s-minecraft-mods.github.io/Folkhold/dev/folkhold-dev-instance.zip>. Select **OK**.
3. Launch the instance. packwiz-installer installs the mods of the latest green build of `develop`, then the game opens.

At each launch, packwiz-installer gets the latest build. It removes the jars of the earlier build. You do not import the instance again.

- packwiz-installer does not change the version of NeoForge of the instance. When `modpack/pack.toml` changes the version of NeoForge, change it in the instance: **Edit**, **Version**, select NeoForge, then **Change version**.
- Use the dev instance for test worlds. A build of `develop` can change the data of a world.
- `scripts/dev-instance.sh` makes the instance: `scripts/dev-instance.sh build/folkhold-dev-instance.zip <URL of pack.toml>`. `scripts/export-modpack.sh` writes the pack with a third argument: `scripts/export-modpack.sh build/folkhold-dev.mrpack <version> build/dev-pack`.

## Site of the project

The site of the project is on GitHub Pages: <https://nicolas-s-minecraft-mods.github.io/Folkhold/>. It shows the downloads of the latest release and the notes of all the releases, in English and in French. `scripts/pages.py` builds the site from these files and from the releases of the GitHub API:

| File | Content |
| --- | --- |
| `site/page.html` | Template of the page of each language. The script replaces `$page_title` with the text of the key `page.title`. |
| `site/lang/en_us.json`, `site/lang/fr_fr.json` | Texts of the pages. A text can contain HTML. The script gives the value of a field in braces, for example `{version}`. |
| `site/static/` | Files that go to the root of the site with no change, for example `style.css`. |

The English page is `index.html`. The French page is `fr/index.html`. The notes come from the GitHub Releases, in English. The versions of Minecraft, NeoForge and GeckoLib come from `gradle.properties` at the tag of the latest release.

To build the site on your computer, run these commands. Then open `build/site/index.html` in a browser.

```bash
git fetch --tags
python3 scripts/pages.py --repo Nicolas-s-minecraft-mods/Folkhold
```

With no token in `GH_TOKEN`, the GitHub API accepts 60 requests per hour. The script uses 2 requests when the repository has 100 releases or fewer.

To add a language, add `site/lang/<language>.json` with all the keys of `en_us.json`. The value of `language.code` gives the folder of the page. The tests of `scripts/pages.py` fail when a key is missing in one language file.

## Continuous integration and releases

- Each push on `develop` and each pull request runs the workflow **Build**. Each job of the workflow has one task, and the checks of a pull request show the job that failed:

  | Job | Needs | Task |
  | --- | --- | --- |
  | **Version** | | Calculates the version of the build. |
  | **Build** | Version | Compiles the mods, runs the JUnit tests, the format check and the translation check. Uploads the jars (artifact `folkhold-mods`) and the coverage report of `core` (artifact `folkhold-core-coverage`). When the job fails, it uploads the Gradle log (artifact `folkhold-build-logs`). |
  | **GameTest shard 1/4** to **4/4** | Version | Each job compiles the mods and runs the GameTest server with one shard of the GameTests (`-PgametestShard=<i>/4`). It writes the number of tests and the time as a notice. It uploads the names of its tests (artifact `folkhold-gametest-shard-<i>`), and the logs when it fails (artifact `folkhold-gametest-logs-<i>`). |
  | **GameTests** | GameTest shard 1/4 to 4/4 | The required check of the GameTests. It passes when each shard passes, and when each test ran in one shard and only one (`scripts/check_gametest_shards.py`). |
  | **Modpack** | Build | Checks the required dependencies of the mods of the modpack (`scripts/modpack_deps.py`). Then makes the `.mrpack` file from the jars of the job **Build** (artifact `folkhold-modpack`). |
  | **Dev pack** | GameTests, Modpack | For a push on `develop` only: makes the dev pack and the dev instance (artifact `folkhold-dev-pack`). |

- **GameTest shards** (`GameTestShards` in the GameTests of core): the system property `folkhold.gametest.shard` (Gradle property `gametestShard`) gives the shard of a run. With no shard, the server runs all the tests.
  - A shard gets whole batches (the tests of one batch run together). The batches go to the shards by their measured time (`core/src/gametest/resources/folkhold_gametest_times.properties`), the longest first, each to the shard with the smallest total time. A batch that the file does not have counts 1 s. Thus a new test runs in one shard, and the shards stay near the same time. To measure again, take the times between the lines `Running test batch` of a full run.
  - Each run writes `dev/run-gametest/shard-report/all-tests.txt` (all the tests) and `shard-tests.txt` (the tests of the run). The job GameTests compares them.
  - Each shard job writes its failed required tests to `shard-report/failed-tests.txt` (`scripts/check_gametest_shards.py failures`), with an error annotation and a line of the job summary for each test. The job GameTests shows the failed tests of all the shards in its annotations and its summary.
  - The tests of the earlier batches use the same cells as a later test. The framework removes only the blocks in the structure of the later test. A test that scans an area larger than its structure (for example the scan of a village heart) first removes the blocks of the earlier tests in this area (`TestSupport.clearEarlierBlocks`). Thus the result of a test does not depend on the batches before it in its shard.
  - The tests of one batch start at the same time, in cells side by side. At its start, a test removes the units, the monsters and the villagers of the earlier tests near its cell (`TestSupport.sweep`). It also puts their settlements in ruins (`TestSupport.retireEarlierSettlements`). These two steps keep the entities and the settlements in the cells of the other running tests (`TestSupport.otherRunningAreas`). The test `TestSupportGameTests` runs 3 copies in one batch and checks this rule.
  - Some tests check the village of a position out of their area. Such a test first removes the hearts of the earlier tests near this position (`TestSupport.removeEarlierHearts`). The registry of the hearts keeps a heart of an earlier test, and this heart can own the position. A raid test does the same near its heart. The vanilla raid center is the middle of the claimed points of interest near the heart, also the points of earlier tests. The raid is at the village of the nearest heart to this center (`RaidShelterGameTests`).
  - A test of a share of random results uses a random source with a fixed seed when the code accepts one (for example `QuestSystem.offerDeliveries`). Then the test gives the same result on each run. A test with entities (for example the raid trials) cannot fix the seed of the entities. Such a test checks the majority of several trials, and its comment gives the probability of a wrong majority.
  - The faction of a new settlement is the owner of its position, and the position of a test changes with its shard. A culture can give extra units to a garrison (`culture_garrison` in the level table). Thus a test that counts the units of a garrison first gives its settlement a faction of a known culture (for example `GuardStationGameTests`).
  - A test that puts a villager in a bed gives it the ticket of the bed. The bed is also its home (memory `HOME`), as in a village. With no home, the vanilla activity `REST` gives the villager a walk target, and the villager walks in its bed.
  - A test with villagers or units checks that they tick (`TestSupport.assertTicks`). A villager that the level does not tick does not move, and the test can then pass with no check. The wall tests (`WallAccessGameTests`) use this check.
  - Measured times of the workflow Build for a pull request (#998): before the shards, 10 to 12 minutes (the job GameTests: about 2 minutes of compile and server start, then 8.7 minutes of tests).

- "Run workflow" on the workflow **Build** has the input `release`. With this input, the run uses the version of the release and also makes the Maven files of the mods (artifact `folkhold-maven`). The run publishes nothing: use it to test a release build.
- The shared steps of the workflows are composite actions in `.github/actions/`:
  - `setup-gradle`: JDK 21 and Gradle.
  - `setup-packwiz`: Go and packwiz.
  - `version`: the version of a build or of a release.
- The workflow **Generated files** runs the tests of the scripts of `scripts/` and checks the output of the scripts of `tools/`.
- Each build has a version from `scripts/version.py`:
  - A push on `develop`: the version of the commit, then `-dev.` and the short commit. Example: `0.32.0-dev.13bcc38`.
  - A pull request: the version after the merge, then `-pr.`, the number of the pull request and the short commit. Example: `0.32.1-pr.472.abc1234`. For a pull request from `develop` to `main`, the first part is the version of the release.
  - A release (push on `main`): the version of the release.
- A build of a push on `develop` also makes the dev pack (artifact `folkhold-dev-pack`, see [Dev instance that updates itself](#dev-instance-that-updates-itself)).
- The workflow **Pull request** checks the title of a pull request to `develop` or in a stack on `develop` (bump marker, see [CONTRIBUTING.md](../CONTRIBUTING.md#versions-and-releases)). It also gives the version after the merge. For a pull request to `main`, it checks that the source branch is `develop` and gives the version and the notes of the release.
- A change to `.devcontainer/` runs the workflow **Dev container**: it builds the image and checks the tools.
- The workflow **Pages** publishes the site of the project to GitHub Pages from `develop` (see [Site of the project](#site-of-the-project)). The site also has `version.json` with the version of the latest release. The version badge of the README reads this file, with no call to the GitHub API by shields.io. The site also has the dev pack of the latest green build of a push on `develop`, in `dev/`. The workflow runs after each green build of a push on `develop`, and after each release. On a pull request that changes the site, the workflow builds the site and does not deploy it. The artifact `github-pages` of the run contains the site.
- To make a release, merge `develop` into `main`: a pull request from `develop` to `main`, with "Create a merge commit". The workflow **Release** calls the workflow **Build** (`workflow_call`). The jobs of the build run with the version of the release, from the bump markers of the commits. The job **Build** also writes the Maven files of the mods (artifact `folkhold-maven`). When all the jobs of the build pass, the job **Publish** tags the commit of `develop`. Then it creates a GitHub Release with the jars and the `.mrpack` file of the build. Then it uploads the Maven files to GitHub Packages (`io.github.nicolasgoyon.folkhold:<mod_id>`, see `scripts/maven_publish.py`). The release does not build the mods again, so the GitHub Release and GitHub Packages have the same jars. When the tag of the version exists, the workflow does not build and makes no release. All the builds use the same packwiz commit, so two runs on the same commit give the same `.mrpack` content. The job **CurseForge** then uploads the same jars to the CurseForge project of each mod (`scripts/curseforge_publish.py`, see [curseforge/README.md](curseforge/README.md)). It needs the repository secret `CURSEFORGE_TOKEN`. A refused module does not stop the upload of the other modules, and the job then fails with the names of the refused modules. The manual workflow **CurseForge** uploads the jars of an existing GitHub Release for selected modules (see [curseforge/README.md](curseforge/README.md#upload-of-some-modules-of-an-existing-release)). After the release, the workflow starts the workflow **Pages**.

## Clean up

Remove all Folkhold development files from the host:

```powershell
docker compose down --rmi local
docker volume rm folkhold-gradle-cache
```

If you use VS Code dev containers, also remove the container and the image `vsc-folkhold-...` in Docker Desktop.

## Troubleshooting

| Problem | Cause | Fix |
| --- | --- | --- |
| `./gradlew: not found` or `bad interpreter: /bin/sh^M` | Windows line endings (CRLF) in `gradlew` | The file `.gitattributes` prevents this for new clones. For an old clone: `git rm --cached -r . && git reset --hard` (commit your changes first). |
| The build stops with `OutOfMemoryError` | Not enough memory for Docker | Give Docker Desktop at least 8 GB. |
| `port is already allocated` | A server already runs | Stop the other server (`docker compose down`). |
| The client cannot connect: "mod mismatch" | Different jars on the client and on the server | Run `scripts/deploy-client.ps1` after each build. |
| `Could not HEAD 'https://objects.githubusercontent.com/…'`, status 401 | The HEAD request to the Cartofold release fails behind a proxy | See [Cartofold dependency](#cartofold-dependency). |
| The server stops: "You need to agree to the EULA" | No `dev/run/eula.txt` | See [Start the dev server](#3-start-the-dev-server). |

## Without a container (alternative)

If you prefer to run the game client from the IDE (`./gradlew :dev:runClient`), you need a JDK 21 on the host. IntelliJ IDEA can download one (File, Project Structure, SDK, Download JDK) into `~/.jdks`. Gradle then uses `~/.gradle` on the host (several GB). To clean up, delete these two folders.
