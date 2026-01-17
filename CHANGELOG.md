# Changelog

All notable changes to HyFixes will be documented in this file.

## [1.4.6] - 2026-01-17

### Added

#### SpawnMarkerSystems Null npcReferences Fix (Early Plugin)
- **Target:** `SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove()`
- **Bug:** `NullPointerException: Cannot read the array length because "<local15>" is null`
- **Crash location:** `SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove()` at line 166
- **Root cause:** `SpawnMarkerEntity.getNpcReferences()` can return null, but the code tries to iterate over it without checking
- **Fix:** Bytecode transformation adds null check after `getNpcReferences()` call - returns early if null
- **Impact:** Replaces runtime SpawnMarkerReferenceSanitizer which was fixing **7,853 entities per session**
- **Performance:** Much more efficient - only runs on entity removal, not every tick

---

## [1.4.5] - 2026-01-17

### Fixed

#### ChunkCleanupSystem Thread Warning Spam (Runtime Plugin)
- **Problem:** ChunkCleanupSystem was logging 1000+ warnings when instance worlds (dungeons) were active
- **Root cause:** `invalidateLoadedChunks()` internally touches instance world threads, causing thread assertion failures
- **Details:** When players are in instances like `Forgotten_Temple` or `Portals_Taiga`, the cleanup method's internal calls cross thread boundaries
- **Fix:** Now detects thread assertion errors and logs only once, tracking count silently
- **Impact:** Clean logs - no more spam, thread errors tracked in `/hyfixes` status command

---

## [1.4.4] - 2026-01-17

### Fixed

#### BeaconSpawnController Transformer Bug (Early Plugin)
- **Problem:** v1.4.2 transformer was checking the wrong variable for null
- **Root cause:** The method signature is `createRandomSpawnJob(ComponentAccessor accessor)` - the `spawn` variable is a LOCAL variable, not a parameter
- **Details:** The original fix checked parameter var 1 (ComponentAccessor), but `spawn` is assigned inside the method from `getRandomSpawn()` call
- **Fix:** Now detects method calls returning `RoleSpawnParameters`, then injects null check after the result is stored to a local variable
- **Impact:** Actually prevents the BeaconSpawnController crash now (was ineffective in v1.4.2)

---

## [1.4.3] - 2026-01-17

### Added

#### BlockComponentChunk Duplicate Fix (Early Plugin)
- **Target:** `BlockComponentChunk.addEntityReference()`
- **Bug:** `IllegalArgumentException: Duplicate block components at: [position]`
- **Crash location:** `BlockComponentChunk.addEntityReference()` at line 329
- **Root cause:** When interacting with teleporters, Hytale sometimes tries to add a block component entity reference that already exists
- **Fix:** Bytecode transformation makes `addEntityReference()` idempotent - logs warning and returns instead of throwing
- **Impact:** Prevents player kicks when interacting with teleporters
- **Related:** [GitHub Issue #8](https://github.com/John-Willikers/hyfixes/issues/8)

---

## [1.4.2] - 2026-01-17

### Added

#### BeaconSpawnController Null Spawn Fix (Early Plugin)
- **Target:** `BeaconSpawnController.createRandomSpawnJob()`
- **Bug:** `NullPointerException: Cannot invoke "RoleSpawnParameters.getId()" because "spawn" is null`
- **Crash location:** `BeaconSpawnController.createRandomSpawnJob()` at line 110
- **Root cause:** Spawn beacons in volcanic/cave biomes can have misconfigured or missing spawn types, passing null `RoleSpawnParameters` to createRandomSpawnJob()
- **Fix:** Bytecode transformation adds null check at method entry - returns null gracefully instead of crashing
- **Impact:** Prevents server crashes when players explore volcanic/red cave biomes with spawn beacon issues

---

## [1.4.1] - 2026-01-17

### Fixed

#### ChunkCleanupSystem "Store is currently processing!" Error
- **Root Cause:** Our own `ChunkCleanupSystem` was calling `waitForLoadingChunks()` from within a system tick
- **The Problem:** This caused 100+ "Store is currently processing!" errors because the Store's task queue contains operations that try to modify entities while we're still inside `Store.tick()`
- **The Fix:** Removed `waitForLoadingChunks()` call from the system - only `invalidateLoadedChunks()` is called now (which works safely)
- **Impact:** Eliminates chunk loading errors that were appearing in server logs

#### Instance Portal Issue After Plugin Removal (Issue #7 Update)
- **Root Cause Found:** `InstancePositionTracker` was unconditionally modifying `DrainPlayerFromWorldEvent` to override Hytale's return destination, even when Hytale already had a valid one
- **The Problem:** This event modification was causing state corruption that persisted even after HyFixes was removed from the server
- **The Fix:** Now only modifies the event if Hytale's return world is actually null/missing (true fallback behavior)

### Added

#### World.addPlayer() Race Condition Fix (Early Plugin)
- **Target:** `World.addPlayer(PlayerRef, Transform, Boolean, Boolean)`
- **Bug:** `IllegalStateException: Player is already in a world` when entering instance portals
- **Crash location:** `World.addPlayer()` at line 1008
- **Root cause:** Hytale's async instance teleport code has a race condition where it tries to add player to the new instance world before removing them from their old world
- **Fix:** Bytecode transformation replaces the exception throw with a warning log and continues gracefully
- **Previously:** This was attempted with runtime event handlers but couldn't be fixed at plugin level

### Disabled (Runtime Plugin)

#### InstanceTeleportSanitizer
- **Disabled:** The race condition monitor for instance portals has been disabled in the runtime plugin
- **Reason:** The fix is now handled properly by bytecode transformation in the early plugin
- **Note:** If you're only using the runtime plugin without the early plugin, you may still experience instance teleport issues

### Technical Details

**Before (problematic):**
```java
// Always overwrote Hytale's return destination
event.setWorld(returnWorld);
event.setTransform(savedPos.getTransform());
```

**After (safe fallback):**
```java
// Only intervene if Hytale has no valid return destination
World existingDestination = event.getWorld();
if (existingDestination != null) {
    return; // Don't interfere - Hytale has valid destination
}
// Only set our fallback if Hytale's destination is null
event.setWorld(returnWorld);
```

---

## [1.4.0] - 2026-01-17

### Added

#### HyFixes Early Plugin (Bytecode Transformation)
- **New `hyfixes-early.jar`** - A bootstrap/early plugin that uses ASM bytecode transformation to fix bugs deep in Hytale's core that cannot be patched at runtime
- Place in `earlyplugins/` folder and set `ACCEPT_EARLY_PLUGINS=1` or press Enter at startup

#### InteractionChain Sync Buffer Overflow Fix
- **Target:** `InteractionChain.putInteractionSyncData()`
- **Bug:** Data silently dropped when sync data arrives out of order, causing 400-2500+ errors per session
- **Symptoms:** Combat damage not registering, food SFX missing, shield blocking failing, tool interactions broken
- **Fix:** Completely replaces method to expand buffer backwards instead of dropping data
- Previously documented as "unfixable at plugin level" in HYTALE_CORE_BUGS.md - now fixed via bytecode transformation!

#### InteractionChain Sync Position Gap Fix
- **Target:** `InteractionChain.updateSyncPosition()`
- **Bug:** Throws `IllegalArgumentException: Temp sync data sent out of order` and kicks player
- **Fix:** Replaces method to handle gaps gracefully instead of throwing exceptions

### Changed

#### Documentation Reorganization
- **New `BUGS_FIXED.md`** - Detailed technical documentation for all 14 bugs we fix
- **Updated `README.md`** - Clean overview explaining both plugins and how they work
- **Updated `CURSEFORGE.md`** - Focused on installation, troubleshooting, and support
- Added Discord support link: https://discord.gg/u5R7kuuGXU
- Added GitHub Issues link for bug reports

#### CI/CD Improvements
- Workflow now builds both runtime and early plugins
- Creates bundle ZIP with both plugins + installation instructions
- Releases include three downloads: `hyfixes.jar`, `hyfixes-early.jar`, `hyfixes-bundle-vX.X.X.zip`
- Discord webhook notification on new releases

### Technical Details

The early plugin uses ASM 9.6 to transform `InteractionChain.class` at load time:
- `PutSyncDataMethodVisitor` - Replaces `putInteractionSyncData()` with buffer expansion logic
- `UpdateSyncPositionMethodVisitor` - Replaces `updateSyncPosition()` with gap-tolerant logic
- Transformation logged at startup for verification

---

## [1.3.10] - 2026-01-17

### Added

#### Instance Teleport Race Condition Fix (Issue #7)
- **New `InstanceTeleportSanitizer`** - Monitors and handles instance portal race conditions
- GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/7
- Error: `IllegalStateException: Player is already in a world`
- Crash location: `World.addPlayer()` at line 1008, called from `InstancesPlugin.teleportPlayerToLoadingInstance()`
- Uses event-based monitoring (AddPlayerToWorldEvent, DrainPlayerFromWorldEvent)
- Tracks instance transitions and detects when players are added to instance worlds without proper drain
- Status visible in `/interactionstatus` command

### Technical Details
- Hooks into `AddPlayerToWorldEvent` to detect instance world entries
- Hooks into `DrainPlayerFromWorldEvent` to track proper player removal sequences
- Detects race condition when add event occurs without corresponding drain event
- Logs warnings when race conditions are detected for Hytale bug reporting
- Tracks pending transitions to coordinate drain/add sequence

### The Bug Explained
When entering an instance portal (e.g., Forgotten Temple), Hytale's async code has a race condition:
1. Instance world is created/loaded
2. Hytale tries to add player to instance world via `World.addPlayer()`
3. But player hasn't been removed from their current world yet
4. `World.addPlayer()` checks if player is already in a world and throws `IllegalStateException`

---

## [1.3.9] - 2026-01-17

### Added

#### ChunkTracker Invalid PlayerRef Crash Fix (Issue #6)
- **New `ChunkTrackerSanitizer`** - Prevents world crash when ChunkTracker has invalid PlayerRefs
- GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/6
- Error: `NullPointerException: Cannot invoke "Ref.getStore()" because the return value of "PlayerRef.getReference()" is null`
- Crash location: `ChunkTracker.tryUnloadChunk()` at line 532
- Uses reflection-based API discovery to find ChunkTracker components
- Validates PlayerRefs and removes invalid ones before Hytale's code can crash
- Status visible in `/interactionstatus` command

### Technical Details
- Queries for `ChunkTracker` component on Player entities
- Discovers `PlayerRef.getReference()` method via reflection
- Scans all fields in ChunkTracker for Collections/Maps containing PlayerRefs
- Removes entries where `getReference()` returns null (player disconnected)
- Prevents crash before `PlayerChunkTrackerSystems$UpdateSystem` can iterate invalid refs

---

## [1.3.8] - 2026-01-17

### Added

#### SpawnMarker Null NPC References Crash Fix (Issue #5)
- **New `SpawnMarkerReferenceSanitizer`** - Prevents world crash from null `npcReferences` array
- GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/5
- Error: `NullPointerException: Cannot read the array length because "<local15>" is null`
- Crash location: `SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove()` at line 166
- Uses reflection-based API discovery to find SpawnMarkerEntity components
- Validates npcReferences array each tick and sets to empty array if null
- Status visible in `/interactionstatus` command

### Technical Details
- Queries for `SpawnMarkerEntity` component on spawn marker entities
- Discovers `getNpcReferences()` and `setNpcReferences()` methods via reflection
- Creates empty `InvalidatablePersistentRef[]` array when null is detected
- Prevents crash before `MarkerAddRemoveSystem.onEntityRemove()` can iterate null array

---

## [1.3.7] - 2026-01-17

### Added

#### SpawnBeacon Null Parameter Crash Fix (Issue #4)
- **New `SpawnBeaconSanitizer`** - Prevents server crash from null `RoleSpawnParameters`
- GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/4
- Error: `NullPointerException: Cannot invoke "RoleSpawnParameters.getId()" because "spawn" is null`
- Crash location: `BeaconSpawnController.createRandomSpawnJob()` at line 110
- Uses reflection-based API discovery to find spawn beacon components
- Validates and removes null spawn entries before Hytale's code can crash on them
- Status visible in `/interactionstatus` command

### Technical Details
- Queries for `BeaconSpawnController` component on spawn beacon entities
- Discovers spawns collection via reflection (field or getter method)
- Removes null entries from List/Collection/Map spawn storage
- Validates spawn objects by checking if `getId()` returns null

---

## [1.3.6] - 2026-01-17

### Changed

#### More Aggressive Client Timeout Protection
- **Lowered timeout threshold from 2500ms to 2000ms** in InteractionManagerSanitizer
- Gives a full 1 second buffer before Hytale's ~3 second kick (was 500ms)
- Should catch more "Client took too long to send clientData" errors before players get kicked
- Based on log analysis showing 6 player kicks in a 14-minute session

---

## [1.3.5] - 2026-01-17

### Removed

#### `/tp` Command Override (Fixed by Hytale)
- **Removed `TeleportCommand.java`** - Hytale team fixed the teleport argument order
- The original fix was for backwards argument order in Hytale's `/tp` command
- No longer needed as Hytale's built-in command now works correctly

---

## [1.3.4] - 2026-01-17

### Added

#### `/who` Command Fix (PR #2 by GameHugo)
- **Community Contribution!** Thanks to [@GameHugo](https://github.com/GameHugo) for this fix!
- Fixes bug where `/who` command showed last joined player as player count instead of actual player list
- New `WhoCommand.java` implementing the corrected `/who` command
- Uses `AbstractAsyncCommand` for proper async world iteration
- Properly validates refs before accessing player data
- Handles both display names and raw usernames

### Code Quality
- Removed unused imports from contributed code
- Replaced `assert` statement with proper null handling for safety

---

## [1.3.3] - 2026-01-17

### Fixed

#### GatherObjectiveTaskSanitizer API Discovery Fix
- **Fixed incorrect class name lookups** - Sanitizer was searching for classes that don't exist:
  - Old: `ObjectiveComponent`, `PlayerObjectives`, `ObjectiveManager`
  - New: `ObjectiveDataStore`, `GatherObjectiveTask`
- Updated API discovery to use correct Hytale package paths:
  - `com.hypixel.hytale.builtin.adventure.objectives.ObjectiveDataStore`
  - `com.hypixel.hytale.builtin.adventure.objectives.task.GatherObjectiveTask`
- Added field introspection to find tasks collection and target ref fields
- Enhanced logging to show all discovered methods and fields for debugging
- Now properly validates tasks within ObjectiveDataStore component

### Added

#### Client Timeout Protection (InteractionManagerSanitizer)
- **New feature:** Proactively detect and cancel chains waiting too long for client data
- Prevents player kicks from "Client took too long to send clientData" errors
- Error: `RuntimeException: Client took too long to send clientData` at `InteractionChain.java:207`
- Tracks chains in WAITING_FOR_CLIENT_DATA state and removes them after 2500ms (before Hytale's ~3 second kick)
- Uses reflection to discover CallState enum and chain state field
- New stat "Timeouts Prevented" shown in `/interactionstatus` command

### Technical Details
- Discovery now finds component type from ObjectiveDataStore
- Checks both methods (`getTasks`, `getActiveTasks`) and fields (`tasks`, `activeTasks`)
- Logs all fields and methods on discovered classes for future debugging
- Status command now shows detailed discovery results
- InteractionManagerSanitizer now tracks chain waiting times in ConcurrentHashMap
- Client timeout threshold set to 2500ms (configurable via constant)

---

## [1.3.2] - 2026-01-17

### Fixed

#### Critical Bug Fix
- **CraftingManagerSanitizer** - Fixed logic that was breaking ALL workbench crafting
  - Previous behavior: Returned `false` (no window open) when WindowManager couldn't be found
  - This caused the sanitizer to clear bench references while players were actively crafting
  - New behavior: Returns `true` (assume window IS open) when WindowManager can't be determined
  - Better to miss clearing a stale ref than to break all crafting functionality

### Technical Details
- Changed default return value in `hasBenchWindowOpen()` from `false` to `true`
- Fail-safe logic: If we can't detect window state, assume player IS using a bench

---

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
