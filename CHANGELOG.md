# Changelog

All notable changes to HyFixes will be documented in this file.

## [1.3.1] - 2026-01-17

### Added

#### New Bug Fixes
- **CraftingManagerSanitizer** - Prevents player kick when opening processing benches with stale state
  - Uses reflection-based API discovery to monitor CraftingManager component on players
  - Clears stale bench references before `setBench()` can throw IllegalArgumentException
  - Error: `Bench blockType is already set! Must be cleared (close UI)` at `CraftingManager.java:157`

- **InteractionManagerSanitizer** - Prevents player kick when opening crafttables at specific locations
  - GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/1
  - Validates all interaction chains each tick for null context or invalid refs
  - Removes corrupted chains before `TickInteractionManagerSystem` can crash on them
  - Error: `NullPointerException` in `InteractionSystems$TickInteractionManagerSystem`

#### Documentation
- **HYTALE_CORE_BUGS.md** - Comprehensive documentation of Hytale core bugs that cannot be fixed at plugin level
  - InteractionChain Sync Buffer Overflow (with decompiled bytecode analysis)
  - Missing Replacement Interactions
  - Client/Server Interaction Desync
  - World Task Queue Silent NPE
  - Includes reproduction steps and suggested fixes for Hytale developers

### Technical Details
- Total active bug fixes increased from 10 to 12
- New systems use EntityTickingSystem pattern on Player entities
- CraftingManagerSanitizer uses runtime reflection for component discovery
- InteractionManagerSanitizer validates InteractionChain context and refs

---

## [1.3.0] - 2026-01-16

### Added

#### New Bug Fixes
- **GatherObjectiveTaskSanitizer** - Prevents NPE crash when quest/objective target entities are destroyed
  - Uses reflection-based API discovery to monitor player objectives each tick
  - Validates Refs stored in objective tasks before Hytale's code can crash on null refs
  - Observed 4 crashes in sister server logs that this fix now prevents

- **PickupItemChunkHandler** - Backup protection for pickup items during chunk unload events
  - RefSystem that intercepts entity removal events (including chunk unloads)
  - Validates targetRef before the removal cascade can trigger a crash
  - Acts as secondary protection layer to the existing PickupItemSanitizer

#### New Features
- **InteractionChainMonitor** - Tracks HyFixes statistics and documents unfixable Hytale core bugs
  - Records crash prevention counts by type
  - Logs periodic 5-minute summaries
  - Documents known unfixable issues for Hytale developer reporting

- **`/interactionstatus` command** (aliases: `/hyfixes`, `/hfs`)
  - Shows comprehensive HyFixes statistics dashboard
  - Displays crashes prevented by each sanitizer
  - Shows memory management statistics
  - Lists known unfixable Hytale core bugs

### Technical Details
- Total active bug fixes increased from 7 to 10
- New systems use EntityTickingSystem and RefSystem patterns
- GatherObjectiveTaskSanitizer uses runtime reflection for component discovery

### Known Unfixable Issues (Hytale Core Bugs)
These bugs are documented for reporting to Hytale developers:
- **InteractionChain Overflow**: ~408 errors per 35-minute session
- **Missing Replacement Interactions**: ~8 errors per session
- **Client/Server Interaction Desync**: ~27 warnings per session
- **Generic Task Queue NPE**: ~10 errors per session

---

## [1.2.2] - 2026-01-16

### Fixed
- **ChunkCleanupSystem** now runs on main server thread
  - Fixes `InvocationTargetException` that occurred when calling cleanup methods from background threads
  - Chunk cleanup methods must be called from the main thread for proper synchronization

### Changed
- ChunkUnloadManager now delegates cleanup execution to ChunkCleanupSystem
- Improved chunk cleanup reliability and stability

---

## [1.2.0] - 2026-01-15

### Added
- **ChunkUnloadManager** - Aggressively unloads unused chunks to prevent memory bloat
  - Uses reflection to discover Hytale's internal chunk management APIs
  - Runs cleanup every 30 seconds
  - Observed 77% reduction in loaded chunks (942 → 211)

- **`/chunkstatus` command** - Shows current chunk counts and cleanup system status
- **`/chunkunload` command** - Forces immediate chunk cleanup

### Fixed
- Server memory no longer grows unbounded when players fly around
- Chunks now properly unload when players move away

---

## [1.1.0] - 2026-01-14

### Added
- **InstancePositionTracker** - Prevents kick when exiting instances with missing return world
  - Tracks player position before entering instances
  - Uses saved position as fallback destination if return world is corrupted

- **EmptyArchetypeSanitizer** - Monitors entities with invalid state (empty archetypes)
  - Logs entities with corrupted/empty component data for debugging
  - Checks for NaN/Infinite positions

---

## [1.0.0] - 2026-01-13

### Added
- **PickupItemSanitizer** - Prevents world thread crash from corrupted pickup items
  - Fixes null `targetRef` crash in `PickupItemSystem.tick()`
  - Marks corrupted pickup items as finished before they crash the server

- **RespawnBlockSanitizer** - Prevents crash when breaking respawn blocks
  - Fixes null `respawnPoints` array crash in `RespawnBlock$OnRemove`
  - Initializes respawn points array before Hytale's buggy code runs

- **ProcessingBenchSanitizer** - Prevents crash when breaking benches with open windows
  - Fixes null `ref` crash in `BenchWindow.onClose0`
  - Clears windows map before crash-causing close handlers run
