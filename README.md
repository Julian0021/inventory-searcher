# Inventory Searcher

A client-side Fabric mod for Minecraft 26.3. It opens every container in reach without showing its screen, caches the contents and lists everything in one searchable terminal, similar to an ME system.

## Use

Press the **Open Inventory Searcher** key (default: mouse button 4) to scan the containers in reach and open the terminal. Closing the terminal closes the last container too.

- Search by name, `@mod` by mod id, `#text` by tooltip.
- Left click picks up a stack, right click half a stack. Shift + left click moves one stack straight into the inventory. Items that only exist in shulker boxes are taken as a whole box.
- Click the grid with a held stack, or shift + left click one of your own slots, to store it in the nearest chest, barrel or shulker box with room. Ender chests, furnaces and the like are never used for storing.
- The last used container stays open and is only switched when another one is needed.
- Ctrl + R rescans, Esc or the inventory key closes.

## Settings

Open them with the **Settings** button in the terminal, or through Mod Menu if it is installed. They are saved in `config/inventory-searcher.json`.

| Setting | Default | |
|---|---|---|
| Searched containers | chest, trapped chest, barrel, shulker box, ender chest, furnace | Which containers get scanned |
| Rotate | on | Looks at each container before opening it |
| Swing | on | Swings your hand when opening a container |
| Open delay | 0 ticks | Wait between two containers |
| Open timeout | 20 ticks | Skip a container that doesn't open in time |
| Clicks per tick | 2 | Inventory clicks per tick when moving items |
| Shulker contents | on | Also list items inside stored shulker boxes |
| Sort | Count | Count, name or mod |

The key is set in the vanilla controls under *Inventory Searcher*.

## Build

Needs JDK 25.

```sh
./gradlew build
# -> build/libs/inventory-searcher-<version>.jar
```

Requires Fabric Loader and Fabric API.

## License

GPL-3.0, see `LICENSE`.
