# Folkhold modpack

packwiz modpack for Minecraft 1.21.1 and NeoForge 21.1.259.

- `pack.toml`: pack definition (Minecraft and NeoForge versions).
- `index.toml`: list of files with their hashes. packwiz updates it (`packwiz refresh`).
- `mods/*.pw.toml`: one metafile for each mod (download URL, hash, update source).
- `add-base-mods.sh`: adds the base mods of the design document (run one time).

Use packwiz in the dev container. See [Getting started](../docs/getting-started.md#modpack).

## Rules

- Each update goes through a branch, a test start of the game and a test world before the merge.
- Pin the beta and unofficial mods (`packwiz pin <mod>`).
- Cartofold comes from the GitHub Release until it is public on Modrinth (`packwiz url add`). To change its version, remove `mods/cartofold.pw.toml` and add the URL of the new jar again.
- Confee comes from its GitHub Release (`packwiz url add`), pinned to the version of `confee_version` in `gradle.properties`. It is not on Modrinth. It is client only (`side = "client"`). To change its version, remove `mods/confee.pw.toml`, add the URL of the new jar again, and change `confee_version` and `confee_release`.
- The Folkhold mods are not in `mods/`. The export script (`scripts/export-modpack.sh`) adds the built jars to the `.mrpack` file, and to the dev pack of the site (see [Getting started](../docs/getting-started.md#dev-instance-that-updates-itself)).
