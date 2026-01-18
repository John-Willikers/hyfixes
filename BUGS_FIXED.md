# HyFixes - Bugs Fixed

This document contains detailed technical information about every bug that HyFixes patches. For a high-level overview, see [README.md](README.md).

> **Found a bug?** Report it on [GitHub Issues](https://github.com/John-Willikers/hyfixes/issues)
>
> **Need help?** Join our [Discord](https://discord.gg/u5R7kuuGXU)

---

## Runtime Plugin Fixes

These bugs are fixed by the standard HyFixes plugin (`hyfixes.jar`) which runs at server runtime.

---

### 1. Pickup Item Null TargetRef Crash

**Severity:** Critical - Crashes entire world thread, disconnects ALL players

**The Bug:**

Hytale's `PickupItemSystem.tick()` at line 69 calls:
```java
getTargetRef().isValid()
```
without null-checking `getTargetRef()` first.

When a player disconnects or an entity despawns while an item is being picked up, `targetRef` becomes null. This causes:

```
java.lang.NullPointerException: Cannot invoke
    "com.hypixel.hytale.component.Ref.isValid()" because "targetRef" is null
    at PickupItemSystem.tick(PickupItemSystem.java:69)
```

**Impact:** The world thread crashes, immediately disconnecting every player in that world.

**The Fix:**

`PickupItemSanitizer` runs each server tick and checks all `PickupItemComponent` entities. If it finds one with a null `targetRef`, it marks the component as "finished" before `PickupItemSystem` can crash on it. The item is then safely cleaned up by the normal pickup system.

---

### 2. RespawnBlock Null RespawnPoints Crash

**Severity:** Critical - Kicks the player who breaks the block

**The Bug:**

Hytale's `RespawnBlock$OnRemove.onEntityRemove()` at line 106 iterates:
```java
for (int i = 0; i < respawnPoints.length; i++)
```
without null-checking `respawnPoints` first.

When a player breaks a respawn block (bed, sleeping bag, etc.) and their `PlayerWorldData.getRespawnPoints()` returns null, the server crashes:

```
java.lang.NullPointerException: Cannot read the array length because "respawnPoints" is null
    at RespawnBlock$OnRemove.onEntityRemove(RespawnBlock.java:106)
```

**Impact:** The player who broke the block is immediately kicked from the server.

**The Fix:**

`RespawnBlockSanitizer` is a `RefSystem` that hooks into the `RespawnBlock` component lifecycle. When a respawn block is about to be removed (not unloaded), it:

1. Gets the owner's UUID from the `RespawnBlock` component
2. Looks up the player's `PlayerWorldData`
3. If `getRespawnPoints()` is null, initializes it to an empty array

This runs before Hytale's `RespawnBlock$OnRemove` system, preventing the crash.

---

### 3. ProcessingBench Window NPE Crash

**Severity:** Critical - Kicks the player who had the window open

**The Bug:**

Hytale's `ProcessingBenchState.onDestroy()` at line 709 calls:
```java
WindowManager.closeAndRemoveAll()
```
which triggers window close handlers that try to access block data that's already being destroyed.

When a player breaks a processing bench (campfire, crafting table, etc.) while another player has it open, the window close callbacks fail with:

```
java.lang.NullPointerException: Cannot invoke "...Ref.getStore()" because "ref" is null
    at BenchWindow.onClose0(BenchWindow.java:83)
    at ProcessingBenchWindow.onClose0(ProcessingBenchWindow.java:246)
```

**Impact:** The player who had the bench window open is immediately kicked from the server.

**The Fix:**

`ProcessingBenchSanitizer` is a `RefSystem` that hooks into the `ProcessingBenchState` component lifecycle. When a processing bench is about to be removed (not unloaded), it:

1. Gets the windows map from the `ProcessingBenchState` component
2. Clears the windows map before `onDestroy()` runs
3. The `closeAndRemoveAll()` call finds an empty map and safely does nothing

This prevents the crash cascade from ever starting.

---

### 4. ExitInstance Missing Return World Crash

**Severity:** Critical - Kicks the player who tried to exit the instance

**The Bug:**

Hytale's `InstancesPlugin.exitInstance()` at line 493 throws:
```java
throw new IllegalArgumentException("Missing return world");
```
when the return world reference is null or invalid.

When a player exits an instance (dungeon, cave, etc.) and the return world data is corrupted or missing:

```
java.lang.IllegalArgumentException: Missing return world
    at InstancesPlugin.exitInstance(InstancesPlugin.java:493)
    at ExitInstanceInteraction.firstRun(ExitInstanceInteraction.java:44)
```

**Impact:** The player who tried to exit the instance is immediately kicked from the server.

**The Fix:**

`InstancePositionTracker` is an event listener that hooks into world transfer events. It:

1. Saves the player's position when they leave a normal world to enter an instance
2. When the player exits the instance, it sets the destination to the saved position
3. If the return world was corrupted, the player still gets teleported back to where they were

This ensures players always have a valid destination when exiting instances.

---

### 5. Empty Archetype Entity Monitoring

**Severity:** Low - Informational logging only (no crash)

**The Bug:**

Hytale's `EntityChunkLoadingSystem` logs SEVERE errors when it encounters entities with empty archetypes:
```
[EntityChunk$EntityChunkLoadingSystem] Empty archetype entity holder:
EntityHolder{archetype=Archetype{componentTypes=[]}, components=[]}
```

These occur due to:
- Codec deserialization failures during save/load
- Data corruption in chunk storage
- World generation issues creating invalid entities

**Impact:** No crash - Hytale already excludes these entities from the world. The SEVERE log is intentional to flag data corruption.

**The Fix:**

`EmptyArchetypeSanitizer` monitors entities during tick and checks for invalid states like NaN/Infinite positions.

**Note:** We cannot intercept empty archetype entities before they're logged because they have no components to query. Hytale's own system already handles them by excluding them from the world. The logs indicate where chunk data may be corrupted.

---

### 6. Chunk Memory Bloat (v1.2.0+)

**Severity:** High - Causes unbounded memory growth and eventual OOM crashes

**The Bug:**

Hytale does not properly unload chunks when players move away from them. Chunks accumulate in memory indefinitely:

- Player flies in a straight line, loads 5,735+ chunks
- Only ~300 chunks are within view radius ("active")
- The remaining 5,400+ "orphan" chunks stay cached in memory
- Memory grows unbounded: 4GB to 14GB+ while idle

**Impact:** Server eventually runs out of memory and crashes, or GC pauses become severe.

**The Fix:**

`ChunkUnloadManager` uses reflection to discover Hytale's internal chunk management APIs:
- `ChunkStore.waitForLoadingChunks()` - Syncs chunk loading state
- `ChunkLightingManager.invalidateLoadedChunks()` - Triggers chunk cleanup

`ChunkCleanupSystem` is an `EntityTickingSystem` that runs these methods on the **main server thread** every 30 seconds, avoiding the `InvocationTargetException` that occurs when calling them from background threads.

**Results:** Chunks now properly cycle - observed 942 to 211 chunks (77% reduction) after players move away.

**Admin Commands:**
- `/chunkstatus` - Shows current chunk counts and cleanup system status
- `/chunkunload` - Forces immediate chunk cleanup

---

### 7. GatherObjectiveTask Null Ref Crash (v1.3.0)

**Severity:** Critical - Crashes quest/objective processing

**The Bug:**

Hytale's `GatherObjectiveTask.lambda$setup0$1()` at line 65 calls:
```java
store.getComponent(ref, ...)
```
where `ref` can be null if the target entity was destroyed.

```
java.lang.NullPointerException: Cannot invoke "com.hypixel.hytale.component.Ref.validate()"
because "ref" is null
    at com.hypixel.hytale.component.Store.__internal_getComponent(Store.java:1222)
    at GatherObjectiveTask.lambda$setup0$1(GatherObjectiveTask.java:65)
```

**Impact:** Quest/objective tasks fail when their target entity is destroyed.

**The Fix:**

`GatherObjectiveTaskSanitizer` monitors player objectives each tick using reflection to discover and validate objective component refs. If a ref is null or invalid, it attempts to clear/cancel the objective before the crash occurs.

---

### 8. Pickup Item Chunk Unload Protection (v1.3.0)

**Severity:** Critical - Backup protection for edge cases

**The Bug:**

In rare cases, a player teleporting away can cause pickup items to crash if the `PickupItemSanitizer` tick doesn't run before the chunk unload cascade.

**The Fix:**

`PickupItemChunkHandler` is a `RefSystem` that acts as a backup to the primary `PickupItemSanitizer`. It intercepts entity removal events (including chunk unloads) and validates targetRef before the removal cascade can trigger a crash.

---

### 9. CraftingManager Bench Already Set Crash (v1.3.1)

**Severity:** Critical - Kicks the player who tried to open the bench

**The Bug:**

Hytale's `CraftingManager.setBench()` at line 157 throws:
```java
throw new IllegalArgumentException("Bench blockType is already set! Must be cleared (close UI).");
```
when a player tries to open a processing bench while the CraftingManager already has a bench reference set.

This can happen when:
- Player's previous bench interaction didn't properly clean up
- Player rapidly opens multiple benches
- Race condition during bench window opening

```
java.lang.IllegalArgumentException: Bench blockType is already set! Must be cleared (close UI).
    at CraftingManager.setBench(CraftingManager.java:157)
    at BenchWindow.onOpen0(BenchWindow.java:67)
    at ProcessingBenchWindow.onOpen0(ProcessingBenchWindow.java:197)
```

**Impact:** The player who tried to open the bench is immediately kicked from the server.

**The Fix:**

`CraftingManagerSanitizer` is an `EntityTickingSystem` that monitors Player entities each tick. It uses reflection to discover and check the `CraftingManager` component. If a player has a stale bench reference (bench set but no window open), it clears the reference before it can cause a crash.

---

### 10. InteractionManager NPE Crash (v1.3.1)

**Severity:** Critical - Kicks the player who tried to interact

**GitHub Issue:** https://github.com/John-Willikers/hyfixes/issues/1

**The Bug:**

When a player opens a crafttable at specific locations, the `InteractionManager` can end up with chains containing null context data. When `TickInteractionManagerSystem` tries to tick these chains, it throws a NullPointerException and **kicks the player**.

```
[SEVERE] [InteractionSystems$TickInteractionManagerSystem] Exception while ticking entity interactions! Removing!
java.lang.NullPointerException
```

**Impact:** The player who tried to open the crafttable is immediately kicked from the server with "Player removed from world!"

**The Fix:**

`InteractionManagerSanitizer` is an `EntityTickingSystem` that validates all interaction chains each tick. It uses reflection to:

1. Access the `InteractionManager` component on each player
2. Iterate through all active chains
3. Check if context is null or owningEntity ref is invalid
4. Remove corrupted chains before they can cause a crash

This runs before `TickInteractionManagerSystem` can process the invalid chains.

---

### 11. SpawnMarker Null NpcReferences Crash (v1.3.5)

**Severity:** Critical - Crashes world thread during entity spawning

**The Bug:**

Hytale's `SpawnMarkerEntity` can have a null `npcReferences` array, causing crashes when the spawning system tries to iterate over it.

**The Fix:**

`SpawnMarkerReferenceSanitizer` monitors SpawnMarkerEntity components and initializes null npcReferences arrays to empty arrays before they can cause crashes.

---

### 12. ChunkTracker Invalid PlayerRef Crash (v1.3.6)

**Severity:** Critical - Crashes during chunk tracking operations

**The Bug:**

When a player disconnects, their `ChunkTracker` component can retain an invalid `PlayerRef` that causes crashes when accessed.

**The Fix:**

`ChunkTrackerSanitizer` validates PlayerRef references in ChunkTracker components and handles invalid refs gracefully.

---

## Early Plugin Fixes (Bytecode Transformation)

These bugs are fixed by the HyFixes Early Plugin (`hyfixes-early.jar`) which uses bytecode transformation at class load time. This allows fixing bugs deep in Hytale's core that cannot be patched at runtime.

---

### 13. InteractionChain Sync Buffer Overflow (v1.0.0-early)

**Severity:** Critical - Causes combat/food/tool desync, player kicks

**The Bug:**

Hytale's `InteractionChain.putInteractionSyncData()` drops sync data when it arrives out of order:

```java
// Original buggy code
int adjustedIndex = index - tempSyncDataOffset;
if (adjustedIndex < 0) {
    LOGGER.severe("Attempted to store sync data at " + index + ". Offset: " + tempSyncDataOffset);
    return;  // DATA SILENTLY DROPPED!
}
```

This causes 400-2,500+ errors per session and results in:
- Combat damage not registering
- Food consumption sounds missing
- Shield blocking not working
- Tool interactions failing

**Impact:** Severe gameplay desync, and in some cases player disconnects.

**The Fix:**

The early plugin completely replaces the method body with fixed logic:

```java
// Fixed logic
int adjustedIndex = index - tempSyncDataOffset;
if (adjustedIndex < 0) {
    // EXPAND BUFFER instead of dropping data
    int expansion = -adjustedIndex;
    for (int i = 0; i < expansion; i++) {
        tempSyncData.add(0, null);  // prepend nulls
    }
    tempSyncDataOffset = tempSyncDataOffset + adjustedIndex;  // adjust offset
    adjustedIndex = 0;
}
// Continue with normal processing...
```

---

### 14. InteractionChain Sync Position Gap (v1.0.0-early)

**Severity:** Critical - Throws exception and kicks player

**The Bug:**

Hytale's `InteractionChain.updateSyncPosition()` throws an exception when sync positions have gaps:

```java
// Original buggy code
if (tempSyncDataOffset == index) {
    tempSyncDataOffset = index + 1;
} else if (index > tempSyncDataOffset) {
    throw new IllegalArgumentException("Temp sync data sent out of order: " + index + " " + tempSyncData.size());
}
```

Error seen in logs:
```
java.lang.IllegalArgumentException: Temp sync data sent out of order: 3 2
    at InteractionChain.updateSyncPosition(InteractionChain.java:550)
```

**Impact:** Player is immediately kicked from the server.

**The Fix:**

The early plugin replaces the method to handle gaps gracefully:

```java
// Fixed logic - accept any index >= offset
if (index >= tempSyncDataOffset) {
    tempSyncDataOffset = index + 1;
}
// silently ignore index < offset (already processed)
```

---

### 15. World.addPlayer() Instance Teleport Race Condition (v1.4.1-early)

**Severity:** Critical - Kicks the player trying to enter an instance portal

**The Bug:**

GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/7

Hytale's `World.addPlayer()` throws an exception when a player enters an instance portal but hasn't been fully removed from their previous world yet:

```java
// Original buggy code at World.java:1008
if (playerRef.getReference() != null) {
    throw new IllegalStateException("Player is already in a world");
}
```

Error seen in logs:
```
java.lang.IllegalStateException: Player is already in a world
    at World.addPlayer(World.java:1008)
    at InstancesPlugin.teleportPlayerToLoadingInstance(InstancesPlugin.java:403)
```

**Root Cause:**

Hytale's `InstancesPlugin.teleportPlayerToLoadingInstance()` uses async/CompletableFuture code that has a race condition:
1. Instance world is created/loaded
2. Hytale tries to add player to instance world via `World.addPlayer()`
3. But player hasn't been drained from their current world yet
4. `World.addPlayer()` checks if player reference is non-null and throws

**Impact:** Player is immediately kicked with "Failed to send player to instance world" error.

**The Fix (v1.5.1 - Retry Loop):**

The early plugin transforms `World.addPlayer()` to use a retry loop that waits for the drain operation to complete:

```java
// Fixed - retry loop waits for race condition to resolve
if (playerRef.getReference() != null) {
    // Wait up to 100ms (20 retries × 5ms) for drain to complete
    for (int i = 0; i < 20 && playerRef.getReference() != null; i++) {
        Thread.sleep(5);
    }
    if (playerRef.getReference() != null) {
        // Still set after 100ms - this is a real error, not a race condition
        throw new IllegalStateException("Player is already in a world");
    }
    System.out.println("[HyFixes-Early] Race condition RESOLVED after " + i + " retries");
}
```

The bytecode transformation intercepts the `ATHROW` instruction for the "Player is already in a world" exception and replaces it with a retry loop. This gives the async drain operation time to complete (up to 100ms) while still catching genuine errors where a player truly shouldn't be added to a world.

---

### 16. SpawnReferenceSystems Null SpawnController Crash (v1.4.1-early)

**Severity:** Critical - Crashes world thread when spawn beacons load

**The Bug:**

GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/4

Hytale's `SpawnReferenceSystems$BeaconAddRemoveSystem.onEntityAdded()` calls `getSpawnController()` without null-checking the result:

```java
// Original buggy code
SpawnController controller = getSpawnController(componentAccessor);
controller.registerBeacon(ref);  // CRASH if controller is null!
```

Error seen in logs:
```
java.lang.NullPointerException: Cannot invoke "SpawnController.registerBeacon(Ref)"
because "spawnController" is null
    at SpawnReferenceSystems$BeaconAddRemoveSystem.onEntityAdded(SpawnReferenceSystems.java:84)
```

**Root Cause:**

Spawn beacons can reference spawn controllers that don't exist or haven't loaded yet, causing `getSpawnController()` to return null.

**Impact:** World thread crashes when loading chunks with orphan spawn beacons.

**The Fix:**

The early plugin injects a null check after the `getSpawnController()` call:

```java
// Fixed - check for null before using
SpawnController controller = getSpawnController(componentAccessor);
if (controller == null) {
    System.out.println("[HyFixes-Early] WARNING: null spawnController, skipping beacon registration");
    return;
}
controller.registerBeacon(ref);
```

---

### 17. BeaconSpawnController Null Spawn Parameters Crash (v1.4.2-early, fixed v1.4.4)

**Severity:** Critical - Crashes world thread in volcanic/cave biomes

**The Bug:**

GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/4

Hytale's `BeaconSpawnController.createRandomSpawnJob()` calls `getRandomSpawn()` which can return null when spawn types are misconfigured:

```java
// Original buggy code
RoleSpawnParameters spawn = getRandomSpawn(accessor);
spawn.getId();  // CRASH if spawn is null!
```

Error seen in logs:
```
java.lang.NullPointerException: Cannot invoke "RoleSpawnParameters.getId()"
because "spawn" is null
    at BeaconSpawnController.createRandomSpawnJob(BeaconSpawnController.java:110)
```

**Root Cause:**

Spawn beacons in volcanic/cave biomes can have misconfigured or missing spawn types. When `getRandomSpawn()` returns null, the subsequent `spawn.getId()` call crashes.

**Impact:** World thread crashes when players explore volcanic/red cave biomes with spawn beacon issues.

**The Fix:**

The early plugin detects when a method returns `RoleSpawnParameters` and the result is stored to a local variable. It injects a null check after the assignment:

```java
// Fixed - check for null after assignment
RoleSpawnParameters spawn = getRandomSpawn(accessor);
if (spawn == null) {
    System.out.println("[HyFixes-Early] WARNING: null spawn, returning null");
    return null;
}
spawn.getId();  // Safe now
```

**Note:** v1.4.2 had a bug where we checked the wrong variable (method parameter instead of local variable). This was fixed in v1.4.4.

---

### 18. BlockComponentChunk Duplicate Block Components Crash (v1.4.3-early)

**Severity:** Critical - Kicks player when using teleporters

**The Bug:**

GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/8

Hytale's `BlockComponentChunk.addEntityReference()` throws an exception when a duplicate block component is detected:

```java
// Original buggy code
if (existingRef != null) {
    throw new IllegalArgumentException("Duplicate block components at: " + position);
}
```

Error seen in logs:
```
java.lang.IllegalArgumentException: Duplicate block components at: 153349
    at BlockComponentChunk.addEntityReference(BlockComponentChunk.java:329)
    at BlockModule$BlockStateInfoRefSystem.onEntityAdded(BlockModule.java:334)
    at TeleporterSettingsPageSupplier.tryCreate(TeleporterSettingsPageSupplier.java:81)
```

**Root Cause:**

When interacting with teleporters, Hytale sometimes tries to add a block component entity reference that already exists. Instead of handling this gracefully, it throws an exception.

**Impact:** Player is kicked when interacting with teleporters.

**The Fix:**

The early plugin transforms `addEntityReference()` to be idempotent - it logs a warning and returns instead of throwing:

```java
// Fixed - ignore duplicates gracefully
if (existingRef != null) {
    System.out.println("[HyFixes-Early] WARNING: Duplicate block component, ignoring");
    return;  // Instead of throw
}
```

The bytecode transformation detects the "Duplicate block components" string pattern and replaces the subsequent `ATHROW` instruction with `POP` + warning log + `RETURN`.

---

### 19. SpawnMarkerEntity Null npcReferences - ROOT CAUSE FIX (v1.5.0-early)

**Severity:** Critical - Crashes world thread when spawn markers are removed

**The Bug:**

GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/5

This is the **ROOT CAUSE** of the null `npcReferences` crash. The `SpawnMarkerEntity` class has an `npcReferences` field that is **never initialized** in the constructor, leaving it as `null`.

```java
// SpawnMarkerEntity.java - field declaration
private InvalidatablePersistentRef<EntityStore>[] npcReferences;  // NEVER INITIALIZED!
```

When `SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove()` tries to iterate over this array:

```java
InvalidatablePersistentRef<EntityStore>[] refs = spawnMarkerEntity.getNpcReferences();
for (int i = 0; i < refs.length; i++) {  // CRASH: refs is null!
    // ...
}
```

**Impact:** World thread crashes when spawn markers are removed. This was responsible for **7,853+ entities needing runtime sanitization per session**.

**Previous Fix (v1.4.6):**

We added a null check in `MarkerAddRemoveSystem.onEntityRemove()` to return early if `getNpcReferences()` returns null. This was a band-aid that prevented the crash but didn't fix the root cause.

**ROOT CAUSE Fix (v1.5.0):**

The early plugin now transforms the `SpawnMarkerEntity` constructor to initialize the `npcReferences` field:

```java
// Injected at start of constructor
this.npcReferences = new InvalidatablePersistentRef[0];
```

**Technical Implementation:**

The `SpawnMarkerEntityTransformer` uses ASM to:
1. Detect the `SpawnMarkerEntity` class by its internal name
2. Find the constructor method (`<init>`)
3. Inject bytecode after the superclass constructor call (`invokespecial`) to:
   - Load `this` (ALOAD 0)
   - Create new empty array (ICONST_0, ANEWARRAY)
   - Store to field (PUTFIELD npcReferences)

**Why This Is Better:**

| Approach | Performance | Coverage |
|----------|-------------|----------|
| Runtime Sanitizer (v1.3.8) | Runs every tick on every spawn marker | Reactive - fixes after creation |
| Removal Null Check (v1.4.6) | Runs on entity removal | Reactive - prevents crash at removal |
| Constructor Fix (v1.5.0) | Runs once at entity creation | Proactive - prevents null from ever existing |

The constructor fix is **massively more efficient** - it runs once when the entity is created, not every tick or on removal.

---

### 17. Teleporter BlockCounter Not Decrementing (v1.6.0)

**Severity:** Medium - Teleporter limit stuck, but manual workaround exists

**The Bug:**

GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/11

When teleporters are deleted, the `BlockCounter` placement count is not decremented, causing players to permanently hit the 5 teleporter limit even after deleting all their teleporters.

The root cause is in `TrackedPlacement$OnAddRemove.onEntityRemove()`:

```java
public void onEntityRemove(Ref ref, RemoveReason reason, Store store, CommandBuffer commandBuffer) {
    if (reason != RemoveReason.REMOVE) return;
    TrackedPlacement tracked = commandBuffer.getComponent(ref, COMPONENT_TYPE);
    assert (tracked != null);  // CAN FAIL - component may already be removed!
    BlockCounter counter = commandBuffer.getResource(BLOCK_COUNTER_RESOURCE_TYPE);
    counter.untrackBlock(tracked.blockName);  // NPE if tracked is null
}
```

The code assumes `TrackedPlacement` component is always present when `onEntityRemove` is called, but due to component removal ordering, it may already be null.

**Impact:**
- Players cannot place new teleporters after reaching the 5 limit
- Deleting teleporters doesn't restore the limit
- The warp data in `warps.json` is correctly removed, but `BlockCounter` stays stuck

**The Fix (Early Plugin):**

`TrackedPlacementTransformer` replaces the `onEntityRemove` method with a null-safe version:

```java
public void onEntityRemove(...) {
    if (reason != RemoveReason.REMOVE) return;

    TrackedPlacement tracked = commandBuffer.getComponent(ref, COMPONENT_TYPE);
    if (tracked == null) {
        System.out.println("[HyFixes-Early] WARNING: TrackedPlacement null on remove");
        return;  // Gracefully handle null
    }

    String blockName = tracked.blockName;
    if (blockName == null || blockName.isEmpty()) {
        System.out.println("[HyFixes-Early] WARNING: blockName null/empty on remove");
        return;  // Gracefully handle null/empty
    }

    BlockCounter counter = commandBuffer.getResource(BLOCK_COUNTER_RESOURCE_TYPE);
    counter.untrackBlock(blockName);
    System.out.println("[HyFixes-Early] BlockCounter decremented for: " + blockName);
}
```

**The Fix (Runtime Plugin):**

`/fixcounter` admin command allows manual correction of BlockCounter values:

```
/fixcounter              - Show current teleporter count
/fixcounter list         - List all tracked block counts
/fixcounter reset        - Reset teleporter count to 0
/fixcounter set <value>  - Set teleporter count to specific value
/fixcounter <block> reset       - Reset specific block type
/fixcounter <block> set <value> - Set specific block type count
```

---

## Technical Reference

### Runtime Plugin Systems

| Fix | System Type | Registry | Hook Point |
|-----|-------------|----------|------------|
| PickupItemSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, queries `PickupItemComponent` |
| PickupItemChunkHandler | `RefSystem<EntityStore>` | EntityStoreRegistry | `onEntityRemove()` for `PickupItemComponent` |
| RespawnBlockSanitizer | `RefSystem<ChunkStore>` | ChunkStoreRegistry | `onEntityRemove()` for `RespawnBlock` component |
| ProcessingBenchSanitizer | `RefSystem<ChunkStore>` | ChunkStoreRegistry | `onEntityRemove()` for `ProcessingBenchState` component |
| InstancePositionTracker | `Listener` (EventHandler) | EventBus | `DrainPlayerFromWorldEvent`, `AddPlayerToWorldEvent` |
| EmptyArchetypeSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, queries `TransformComponent` |
| ChunkUnloadManager | `ScheduledExecutorService` | N/A (background thread) | Reflection-based API discovery, 30s interval |
| ChunkCleanupSystem | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every 600 ticks (30s), calls cleanup on main thread |
| GatherObjectiveTaskSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, validates objective refs |
| InteractionChainMonitor | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Tracks HyFixes statistics |
| CraftingManagerSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, clears stale bench refs |
| InteractionManagerSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, validates interaction chains |
| SpawnMarkerReferenceSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | **DEPRECATED** - Now fixed via SpawnMarkerEntityTransformer in early plugin |
| ChunkTrackerSanitizer | `RefSystem<EntityStore>` | EntityStoreRegistry | Validates PlayerRef on chunk operations |
| FixCounterCommand | `AbstractPlayerCommand` | CommandRegistry | Admin command to reset/view BlockCounter values |

### Early Plugin Transformers

| Transformer | Target Class | Target Method | Transformation |
|-------------|--------------|---------------|----------------|
| InteractionChainTransformer | `InteractionChain` | `putInteractionSyncData` | Full method replacement |
| InteractionChainTransformer | `InteractionChain` | `updateSyncPosition` | Full method replacement |
| WorldTransformer | `World` | `addPlayer` | Exception throw replaced with warning log |
| SpawnReferenceSystemsTransformer | `SpawnReferenceSystems$BeaconAddRemoveSystem` | `onEntityAdded` | Null check injection after `getSpawnController()` |
| BeaconSpawnControllerTransformer | `BeaconSpawnController` | `createRandomSpawnJob` | Null check injection after `getRandomSpawn()` |
| BlockComponentChunkTransformer | `BlockComponentChunk` | `addEntityReference` | Exception throw replaced with warning log |
| MarkerAddRemoveSystemTransformer | `SpawnReferenceSystems$MarkerAddRemoveSystem` | `onEntityRemove` | Null check injection after `getNpcReferences()` |
| SpawnMarkerEntityTransformer | `SpawnMarkerEntity` | `<init>` (constructor) | Field initialization for `npcReferences` (ROOT CAUSE FIX) |
| TrackedPlacementTransformer | `TrackedPlacement$OnAddRemove` | `onEntityRemove` | Full method replacement with null-safe version |

---

## See Also

- [README.md](README.md) - Plugin overview and installation
- [HYTALE_CORE_BUGS.md](HYTALE_CORE_BUGS.md) - Bugs that cannot be fixed (requires Hytale developers)
- [CHANGELOG.md](CHANGELOG.md) - Version history
