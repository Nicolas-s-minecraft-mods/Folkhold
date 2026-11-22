# Folkhold Balance

## Summary

Trains of linked minecarts, locomotives, storage minecarts, an anvil with no level cap, no Mending in trades, and a limit for the fast nights.

## Description

**Folkhold Balance changes some rules of the game and adds trains.** It works alone: it needs no other Folkhold mod.

### Trains

- **Chain links.** Use a chain on two minecarts to link them. A furnace minecart then pulls a line of wagons. Shears remove a link.
- **Locomotives.** The furnace locomotive and the heavy locomotive pull long trains. They burn coal or blocks of coal, stop at an unpowered powered rail, and show their fuel.
- **Storage minecarts.** A double chest minecart, a shulker box minecart in the 17 colors, and an ender chest minecart.

### Rules of the game

- **No Mending in trades and in fishing.** The server configuration gives the list of the blocked enchantments.
- **An anvil with no level cap.** The anvil never shows "Too Expensive!". Two books of the maximum level give a book of the next level, at a cost that grows fast. A repair has no penalty for the work before it.

### Fast nights (with Slumber)

- With the Slumber mod, the night goes fast when all the players sleep. The game runs the ticks of the night: the furnaces smelt and the crops grow.
- Folkhold Balance limits the extra ticks in each server tick, so that each server tick stays short.
- A clock keeps the night at about 40 seconds of real time (option `nightSeconds`). In a slow world, the time jumps ahead in small steps.

### Train chunk loading

- A server option, off by default: a moving locomotive keeps the chunks of its train loaded. It also loads the chunks in front of it.

Each rule has its options in the configuration of the mod.

### Requirements

- Minecraft 1.21.1, NeoForge 21.1.259 or later, Java 21.
- Optional mod: Slumber (fast nights).
- Client and server.

### License and links

- Code: MIT License. Assets: CC BY 4.0. All the models and the textures are original work.
- Source, issues and documentation: <https://github.com/Nicolas-s-minecraft-mods/Folkhold>
