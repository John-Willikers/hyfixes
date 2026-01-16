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

### Empty Archetype Entities (Monitoring)
Logs entities with corrupted/empty component data for debugging. These don't crash but indicate world data issues.

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
