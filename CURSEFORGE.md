# HyFixes

Essential bug fixes for Hytale Early Access servers. Prevents crashes and player kicks caused by known issues.

## What This Fixes

### Pickup Item Crash (Critical)
When a player disconnects while picking up an item, the world thread crashes and **kicks ALL players** in that world.
- **Error:** `NullPointerException` in `PickupItemSystem.tick()` - null `targetRef`
- **Fix:** Safely marks corrupted pickup items as finished before they crash the server

### RespawnBlock Crash (Critical)
When a player breaks a bed or sleeping bag, they get **kicked from the server**.
- **Error:** `NullPointerException` in `RespawnBlock$OnRemove` - null `respawnPoints` array
- **Fix:** Initializes the respawn points array before Hytale's buggy code runs

### ProcessingBench Window Crash (Critical)
When a player breaks a campfire or crafting table while another player has it open, they get **kicked from the server**.
- **Error:** `NullPointerException` in `BenchWindow.onClose0` - null `ref` during window close
- **Fix:** Clears the windows map before the crash-causing close handlers run

### Instance Exit Missing Return World (Critical)
When a player exits an instance (dungeon, cave, etc.) and the return world data is corrupted, they get **kicked from the server**.
- **Error:** `IllegalArgumentException` in `InstancesPlugin.exitInstance` - Missing return world
- **Fix:** Tracks player position before entering instances and uses it as fallback destination

### Empty Archetype Entities (Monitoring)
Logs entities with corrupted/empty component data for debugging. These don't crash but indicate world data issues.

### Chunk Memory Bloat (High - v1.2.0+)
Hytale doesn't properly unload chunks when players move away, causing **unbounded memory growth** and eventual OOM crashes.
- **Symptoms:** Server memory grows from 4GB to 14GB+ while players fly around; chunks never decrease
- **Example:** Player loads 5,735 chunks, only 317 are in view radius, 5,400+ stay in memory forever
- **Fix:** `ChunkCleanupSystem` runs on the main thread every 30 seconds to trigger Hytale's internal chunk cleanup
- **Results:** Observed 77% reduction in loaded chunks (942 → 211) after fix
- **Commands:** `/chunkstatus` (view chunk counts), `/chunkunload` (force cleanup)

### GatherObjectiveTask Crash (Critical - v1.3.0)
When a player has a quest/objective and the target entity despawns, the quest system **crashes**.
- **Error:** `NullPointerException` in `GatherObjectiveTask.lambda$setup0$1` - null `ref`
- **Fix:** Monitors objectives each tick via reflection and clears corrupted objectives before crash

### Pickup Item Chunk Protection (Critical - v1.3.0)
Backup protection for pickup item crashes during chunk unload events.
- **Scenario:** Player teleports away rapidly while item is being picked up
- **Fix:** RefSystem intercepts entity removal events and validates targetRef before crash cascade

### HyFixes Status Command (v1.3.0)
New admin command to view comprehensive HyFixes statistics.
- **Command:** `/interactionstatus` (aliases: `/hyfixes`, `/hfs`)
- **Shows:** Crashes prevented by type, memory stats, known unfixable Hytale bugs

## Installation

1. Download `hyfixes-x.x.x.jar`
2. Place in your server's `mods/` folder
3. Restart the server

## Compatibility

- Hytale Early Access (2025+)
- Java 21+
- Server-side only

## Source Code

[GitHub Repository](https://github.com/John-Willikers/hyfixes)
