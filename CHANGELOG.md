# Changelog

All notable changes to HyFixes will be documented in this file.

## [1.9.6] - 2026-01-20

### Added

#### Interaction Timeout Configuration (Early Plugin)
- **Target:** `PacketHandler.getOperationTimeoutThreshold()`
- **Bug:** Players get kicked when experiencing network lag during block-breaking or weapon interactions
- **GitHub Issue:** [#25](https://github.com/John-Willikers/hyfixes/issues/25)
- **Error:** `RuntimeException: Client took too long to send clientData!`
- **Root Cause:** Vanilla timeout formula `(ping × 2.0) + 3000ms` is too aggressive for players with unstable connections
- **Fix:** Bytecode transformation replaces timeout calculation with configurable values:
  - Default `baseTimeoutMs`: 6000 (was 3000)
  - Default `pingMultiplier`: 3.0 (was 2.0)
  - Configurable via `mods/hyfixes/config.json`
- **Impact:** Players on unstable connections no longer get randomly kicked during interactions

#### UUIDSystem Null Check During Chunk Unload (Early Plugin)
- **Target:** `EntityStore$UUIDSystem.onEntityRemove()`
- **Bug:** Server crashes with NPE when removing entities during chunk unload
- **GitHub Issue:** [#28](https://github.com/John-Willikers/hyfixes/issues/28)
- **Error:** `NullPointerException: Cannot invoke "UUIDComponent.getUuid()" because "uuidComponent" is null`
- **Root Cause:** Entities can be removed during chunk unload before their UUIDComponent is initialized
- **Fix:** Bytecode transformation injects null check before `getUuid()` call:
  - If null: logs warning, returns early (safe no-op)
  - If not null: continues normal UUID cleanup
- **Impact:** Prevents server crashes during chunk unload operations

#### ArchetypeChunk.copySerializableEntity Fix (Early Plugin)
- **Target:** `ArchetypeChunk.copySerializableEntity()`
- **Bug:** Server crashes with IndexOutOfBoundsException when serializing entities during chunk saving
- **GitHub Issue:** [#29](https://github.com/John-Willikers/hyfixes/issues/29)
- **Error:** `IndexOutOfBoundsException: Index out of range: 11`
- **Root Cause:** Entity archetype/component data can change while serialization is in progress
- **Fix:** Extended existing `ArchetypeChunkTransformer` to wrap `copySerializableEntity()` in try-catch:
  - On exception: logs warning, returns null (skips entity - safe degradation)
- **Impact:** Prevents server crashes during chunk save operations

#### TickingThread.stop() Java 21+ Compatibility (Early Plugin)
- **Target:** `TickingThread.stop()`
- **Bug:** Server crashes when trying to force-stop stuck threads during instance world shutdown
- **GitHub Issue:** [#32](https://github.com/John-Willikers/hyfixes/issues/32)
- **Error:** `UnsupportedOperationException` at `Thread.stop()`
- **Root Cause:** Java 21+ removed `Thread.stop()` - it now throws UnsupportedOperationException
- **Fix:** Bytecode transformation wraps `Thread.stop()` calls in try-catch:
  - On exception: falls back to `Thread.interrupt()`, logs warning
- **Impact:** Instance world shutdown works correctly on Java 21+

#### Universe.removePlayer() Memory Leak Fix (Early Plugin)
- **Target:** `Universe.removePlayer()` async lambda
- **Bug:** Server experiences 20GB+ memory leak when players timeout
- **GitHub Issue:** [#34](https://github.com/John-Willikers/hyfixes/issues/34)
- **Error:** `IllegalStateException: Invalid entity reference!`
- **Root Cause:** Race condition - entity ref invalidated before async cleanup runs, preventing `playerComponent.remove()` from executing. ChunkTracker data (thousands of HLongSet entries) never gets released.
- **Fix:** Bytecode transformation wraps async lambda with try-catch:
  - On `IllegalStateException`: performs fallback cleanup via `playerRef.getChunkTracker().clear()`
  - Logs warning for debugging
  - `finalizePlayerRemoval()` still runs via whenComplete handler
- **Impact:** Prevents massive memory leaks from player timeouts

### Configuration

New transformer toggles in `mods/hyfixes/config.json`:
```json
{
  "transformers": {
    "interactionTimeout": true,
    "uuidSystem": true,
    "tickingThread": true,
    "universeRemovePlayer": true
  },
  "interactionTimeout": {
    "baseTimeoutMs": 6000,
    "pingMultiplier": 3.0
  }
}
```

### Note
This release supersedes v1.9.5 which had a critical startup crash (VerifyError). The bytecode transformer for `Universe.removePlayer()` was incorrectly transforming all lambda methods instead of only the one with `PlayerRef` parameter. This has been fixed by filtering lambdas by descriptor and dynamically detecting the correct parameter slot for instance methods.

---

## [1.6.2] - 2026-01-19

### Fixed

#### BetterMap Compatibility - ChunkUnloadManager Disable Option (Runtime Plugin)
- **Issue:** BetterMap shows black/empty map when HyFixes is installed
- **GitHub Issue:** [#21](https://github.com/John-Willikers/hyfixes/issues/21)
- **Root Cause:** HyFixes' aggressive ChunkUnloadManager was unloading chunks every 30 seconds, destroying the chunk data that BetterMap needs to render the world map. The "ExplorationRadius" mentioned in the issue refers to BetterMap's map exploration feature which relies on chunk data being available.
- **Fix:** Added environment variable to disable ChunkUnloadManager:
  - Set `HYFIXES_DISABLE_CHUNK_UNLOAD=true` in your server environment
  - Or add `-Dhyfixes.disableChunkUnload=true` to JVM args
- **Impact:** BetterMap and other map plugins now work correctly when ChunkUnloadManager is disabled
- **Trade-off:** Disabling ChunkUnloadManager means memory may grow on servers where players explore large areas (the original issue ChunkUnloadManager was designed to fix)

---

## [1.6.1] - 2026-01-19

### Fixed

#### ASM Upgrade for Java 25 Compatibility (Early Plugin)
- **Issue:** HyFixes Early incompatible with plugins compiled for Java 25
- **GitHub Issue:** [#19](https://github.com/John-Willikers/hyfixes/issues/19)
- **Error:** `Unsupported class file major version 69` when loading Java 25 plugins
- **Root Cause:** ASM 9.6 doesn't support Java 25 bytecode (class file version 69)
- **Fix:** Upgraded ASM from 9.6 to **9.8** which adds full Java 25 support
- **Impact:** HyFixes Early can now process bytecode from Java 25 compiled plugins

#### ArchetypeChunk Stale Entity Reference Crash (Early Plugin)
- **Target:** `ArchetypeChunk.getComponent()`
- **Bug:** `IndexOutOfBoundsException: Index out of range: 0` in NPC position cache system
- **GitHub Issue:** [#20](https://github.com/John-Willikers/hyfixes/issues/20)
- **Error:** Server crashes when NPC systems access stale entity references
- **Root Cause:** Entity references become stale (entity removed from chunk) but NPC systems still try to access components
- **Fix:** Bytecode transformation wraps `getComponent()` in try-catch:
  - Catches `IndexOutOfBoundsException` from stale entity access
  - Returns null instead of crashing
  - Logs warning for debugging
- **Impact:** Prevents server crashes from stale NPC entity references

---

## [1.6.0] - 2026-01-18

### Added

#### WorldMapTracker Iterator Crash Fix (Early Plugin)
- **Target:** `WorldMapTracker.unloadImages()`
- **Bug:** `NullPointerException` during chunk unloading from FastUtil `LongOpenHashSet` iterator corruption
- **GitHub Issue:** [#16](https://github.com/John-Willikers/hyfixes/issues/16)
- **Root Cause:** FastUtil's `LongOpenHashSet.iterator().remove()` can corrupt internal state during rehash operations, causing NPE when iterating
- **Fix:** Bytecode transformation wraps `unloadImages()` method in try-catch:
  - Catches `NullPointerException` from iterator corruption
  - Logs warning and returns gracefully instead of crashing
- **Impact:** Prevents server crashes every ~30 minutes on high-population servers (35+ players)

#### Teleporter Limit Fix - BlockCounter Null-Safe Decrement (Early Plugin)
- **Target:** `TrackedPlacement$OnAddRemove.onEntityRemove()`
- **Bug:** BlockCounter not decremented when teleporters are deleted, causing players to hit the 5 teleporter limit permanently
- **GitHub Issue:** [#11](https://github.com/John-Willikers/hyfixes/issues/11)
- **Root Cause:** Original code assumes `TrackedPlacement` component is always non-null on entity removal
- **Fix:** Bytecode transformation adds null checks:
  - If `TrackedPlacement` component is null: logs warning, returns early
  - If `blockName` is null/empty: logs warning, returns early
  - On success: logs the block type that was decremented
- **Impact:** Teleporters can now be deleted and replaced properly

#### /fixcounter Admin Command (Runtime Plugin)
- **Command:** `/fixcounter` (aliases: `/fc`, `/blockcounter`, `/teleporterlimit`)
- **Purpose:** Manually fix BlockCounter values that got out of sync
- **Usage:**
  - `/fixcounter` - Show current teleporter count
  - `/fixcounter list` - List all tracked block counts in current world
  - `/fixcounter reset` - Reset teleporter count to 0
  - `/fixcounter set <value>` - Set teleporter count to specific value
  - `/fixcounter <block> reset` - Reset specific block type
  - `/fixcounter <block> set <value>` - Set specific block type count
- **Note:** Changes persist until server restart or chunk reload

---

## [1.5.1] - 2026-01-18

### Changed

#### World.addPlayer() Retry Loop Fix (Early Plugin)
- **Target:** `World.addPlayer()` method
- **Bug:** `IllegalStateException: Player is already in a world` when entering instance portals
- **GitHub Issue:** [#7](https://github.com/John-Willikers/hyfixes/issues/7)
- **Previous Fix (v1.4.1):** Simply logged warning and continued, bypassing the check entirely
- **New Fix (v1.5.1):** Implements a proper retry loop that:
  - Waits up to 100ms (20 retries × 5ms) for the drain operation to complete
  - If reference clears, logs success with retry count and continues normally
  - If reference doesn't clear after 100ms, throws the original exception
- **Why Better:** Properly handles the race condition while still catching genuine errors where a player shouldn't be added to a world
- **Logs:**
  - On success: `[HyFixes-Early] Race condition RESOLVED after X retries (Xms wait)`
  - On failure: `[HyFixes-Early] Retry failed - player still in world after 100ms, throwing exception`

---

## [1.5.0] - 2026-01-18

### Changed

#### SpawnMarkerEntity Bytecode Transformation - ROOT CAUSE FIX (Early Plugin)
- **Target:** `SpawnMarkerEntity` constructor
- **Bug:** `NullPointerException: Cannot read the array length because "<local15>" is null`
- **Crash location:** `SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove()` at line 166
- **Root cause:** `SpawnMarkerEntity.npcReferences` field is never initialized in constructor, defaults to null
- **Fix:** Bytecode transformation injects field initialization in constructor: `npcReferences = new InvalidatablePersistentRef[0]`
- **Impact:** This is the ROOT CAUSE fix! SpawnMarkerEntity instances now have properly initialized arrays from the start
- **Replaces:** Runtime SpawnMarkerReferenceSanitizer which was fixing **7,853 entities per session**
- **Performance:** Massive improvement - initialization happens once at construction, not every tick

#### SpawnMarkerSystems Transformer Deprecated
- The v1.4.6 `MarkerAddRemoveSystemTransformer` (null check on removal) is superseded by v1.5.0's constructor fix
- Both remain active for defense-in-depth, but the constructor fix prevents null arrays from ever existing

#### Runtime Plugin Changes
- **SpawnMarkerReferenceSanitizer** moved to early plugin in v1.4.0 (bytecode transformation)
- Status command updated to reflect this architecture change

---

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
