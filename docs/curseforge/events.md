# Folkhold Events

## Summary

Natural events: earthquakes that shake and crack the buildings, and storms with wind, flying blocks and lightning.

## Description

**Folkhold Events adds natural events to the world.** An event comes with a warning, does damage that depends on the materials, and stops.

### Earthquakes

- The ground rumbles before the shake. The camera shakes near the epicenter (an option sets the strength).
- Wooden buildings lose more blocks than stone buildings. Glass breaks, stone bricks crack, and cracks open in the ground.
- Falling blocks hurt. The animals run away from the epicenter.
- Signs, chests, beds and torches stay in place.

### Storms

- The wind rises for a minute before the storm, with rain and a dark sky.
- The wind pushes the players and the animals under the open sky. Under a roof, it does not push.
- The wind tears blocks from the wind side of wooden buildings. Stone holds.
- Lightning strikes in the area of the storm. A lightning rod takes the strikes.

### Stabilizer

- A stabilizer is a block of reinforced foundations from Folkhold Blocks. Its recipe has 5 copper ingots and 4 stone blocks.
- No event changes a block at 16 blocks or nearer from a stabilizer. A stabilizer in the floor protects a house of 2 or 3 floors.
- The stabilizer does not stop the falling blocks, the flying blocks, the lightning or the wind.

### Control

- A natural earthquake or storm comes near a player, in loaded chunks only. Each type of event has its own time between 2 events.
- After a natural event, its region has no other natural event for 7 to 15 game days. A storm and an earthquake do not occur in the same place in the same night.
- The operators start and stop the events with `/folkhold earthquake` and `/folkhold storm`.
- The server configuration sets the frequency, the strength and the damage of each event, and the size of the regions.
- The Config button opens the client options (camera shake) and the server options.

### Requirements

- Minecraft 1.21.1, NeoForge 21.1.259 or later, Java 21.
- Required mod: Folkhold Core. The client also needs Confee: it shows the options screen.
- Optional mod: Folkhold Blocks (the stabilizer).
- Client and server.

### License and links

- Code: MIT License. Assets: CC BY 4.0.
- Source, issues and documentation: <https://github.com/Nicolas-s-minecraft-mods/Folkhold>
