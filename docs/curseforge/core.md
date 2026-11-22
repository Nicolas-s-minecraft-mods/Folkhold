# Folkhold Core

## Summary

Shared library of the Folkhold mods: the simulation of the world outside the loaded chunks, the regions and the scheduler.

## Description

**Folkhold Core is a library.** It adds no content to the game by itself. Install it when another Folkhold mod needs it.

Folkhold is a set of mods for a single-player world that changes without the player: NPC factions trade, defend their villages and hold territory.

### What the library gives to the other mods

- **An abstract simulation.** It runs the factions, the stocks and the caravans, also in the chunks that the game does not load.
- **Regions and a scheduler.** The work of the simulation is spread over the ticks of the server.
- **A screen framework.** The screens of the Folkhold mods share their look and their controls.
- **A "What's new" screen.** After an update, the game shows the changes of the new version, in English and in French.

### Mods that use it

- Folkhold World: factions, villages, economy, caravans and quests.
- Folkhold Events: earthquakes and storms.

### Requirements

- Minecraft 1.21.1, NeoForge 21.1.259 or later, Java 21.
- Client and server.

### License and links

- Code: MIT License. Assets: CC BY 4.0.
- Source, issues and documentation: <https://github.com/Nicolas-s-minecraft-mods/Folkhold>
