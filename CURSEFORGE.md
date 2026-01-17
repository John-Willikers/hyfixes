# HyFixes

Essential bug fixes for Hytale Early Access servers. Prevents crashes and player kicks caused by known issues in Hytale's core systems.

## Support

**Need help?** Join our Discord for community support!

**Discord:** https://discord.gg/u5R7kuuGXU

**Found a bug?** Report it on GitHub:

**GitHub Issues:** https://github.com/John-Willikers/hyfixes/issues

---

## Installation

HyFixes consists of two plugins that work together:

### Runtime Plugin (Required)

The main plugin that fixes most crashes.

1. Download `hyfixes.jar`
2. Place in your server's `mods/` folder
3. Restart the server

### Early Plugin (Recommended)

Fixes deep networking bugs that cause combat/interaction desync.

1. Download `hyfixes-early.jar`
2. Place in your server's `earlyplugins/` folder
3. Start the server with one of these options:
   - Set environment variable: `ACCEPT_EARLY_PLUGINS=1`
   - OR press Enter when you see the early plugins warning at startup

---

## What Gets Fixed

### Runtime Plugin

- **Pickup Item Crash** - World thread crash when player disconnects while picking up item
- **RespawnBlock Crash** - Player kicked when breaking bed/sleeping bag
- **ProcessingBench Crash** - Player kicked when bench is destroyed while open
- **Instance Exit Crash** - Player kicked when exiting dungeon with corrupted data
- **Chunk Memory Bloat** - Server runs out of memory from unloaded chunks
- **CraftingManager Crash** - Player kicked when opening crafting bench
- **InteractionManager Crash** - Player kicked during certain interactions
- **Quest Objective Crash** - Quest system crashes when target despawns
- **SpawnMarker Crash** - World crash during entity spawning

### Early Plugin (Bytecode Fixes)

- **Sync Buffer Overflow** - Fixes combat/food/tool desync (400-2500 errors per session)
- **Sync Position Gap** - Fixes "out of order" exception that kicks players
- **Instance Portal Race** - Fixes "player already in world" crash when entering portals
- **Null SpawnController** - Fixes world crashes when spawn beacons load
- **Null Spawn Parameters** - Fixes world crashes in volcanic/cave biomes
- **Duplicate Block Components** - Fixes player kicks when using teleporters
- **Null npcReferences** - Fixes world crashes when spawn markers are removed

---

## Verifying Installation

### Runtime Plugin

Look for these messages in your server log at startup:

```
[HyFixes|P] Plugin enabled - HyFixes vX.X.X
[HyFixes|P] [PickupItemSanitizer] Active - monitoring for corrupted pickup items
[HyFixes|P] [ChunkCleanupSystem] Active on MAIN THREAD
```

### Early Plugin

Look for these messages in your server log at startup (6 transformers):

```
[HyFixes-Early] Transforming InteractionChain class...
[HyFixes-Early] InteractionChain transformation COMPLETE!

[HyFixes-Early] Transforming World class...
[HyFixes-Early] World transformation COMPLETE!

[HyFixes-Early] Transforming SpawnReferenceSystems$BeaconAddRemoveSystem...
[HyFixes-Early] SpawnReferenceSystems transformation COMPLETE!

[HyFixes-Early] Transforming BeaconSpawnController...
[HyFixes-Early] BeaconSpawnController transformation COMPLETE!

[HyFixes-Early] Transforming BlockComponentChunk...
[HyFixes-Early] BlockComponentChunk transformation COMPLETE!

[HyFixes-Early] Transforming SpawnReferenceSystems$MarkerAddRemoveSystem...
[HyFixes-Early] MarkerAddRemoveSystem transformation COMPLETE!
```

---

## Admin Commands

| Command | Description |
|---------|-------------|
| `/hyfixes` | Show HyFixes statistics and crash prevention counts |
| `/chunkstatus` | Show loaded chunk counts and memory info |
| `/chunkunload` | Force immediate chunk cleanup |

Aliases: `/hfs`, `/interactionstatus`

---

## Troubleshooting

### Plugin not loading

1. Check that the JAR is in the correct folder:
   - Runtime plugin: `mods/hyfixes.jar`
   - Early plugin: `earlyplugins/hyfixes-early.jar`

2. Check server logs for errors during startup

3. Verify Java 21+ is installed: `java -version`

### Early plugin warning at startup

This is normal! Hytale shows a security warning for early plugins because they can modify game code. HyFixes only modifies the specific buggy methods to fix them.

To bypass the warning:
- Set `ACCEPT_EARLY_PLUGINS=1` environment variable
- OR press Enter when prompted

### Still seeing crashes

1. Check which version of HyFixes you have: `/hyfixes`
2. Update to the latest version
3. Report the crash on GitHub with:
   - Full server log showing the error
   - Steps to reproduce (if known)
   - HyFixes version

---

## Compatibility

- **Hytale:** Early Access (2025+)
- **Java:** 21 or higher
- **Side:** Server-side only (no client install needed)

---

## More Information

For detailed technical documentation, source code, and contribution guidelines:

**GitHub Repository:** https://github.com/John-Willikers/hyfixes

**Full Bug Documentation:** https://github.com/John-Willikers/hyfixes/blob/main/BUGS_FIXED.md

---

## Support the Project

- Star the repo on GitHub
- Report bugs you encounter
- Join our Discord community
- Share HyFixes with other server admins!

**Discord:** https://discord.gg/u5R7kuuGXU
