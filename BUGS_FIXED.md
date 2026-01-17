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

**The Fix:**

The early plugin transforms `World.addPlayer()` to handle this case gracefully:

```java
// Fixed - log warning and continue instead of throwing
if (playerRef.getReference() != null) {
    System.out.println("[HyFixes-Early] Warning: Player still in world, proceeding anyway");
    // Continue - Hytale's drain logic will clean up the old reference
}
```

The bytecode transformation intercepts the `ATHROW` instruction for the "Player is already in a world" exception and replaces it with a warning log and continues execution.

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
| SpawnMarkerReferenceSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, fixes null npcReferences |
| ChunkTrackerSanitizer | `RefSystem<EntityStore>` | EntityStoreRegistry | Validates PlayerRef on chunk operations |

### Early Plugin Transformers

| Transformer | Target Class | Target Method | Transformation |
|-------------|--------------|---------------|----------------|
| InteractionChainTransformer | `InteractionChain` | `putInteractionSyncData` | Full method replacement |
| InteractionChainTransformer | `InteractionChain` | `updateSyncPosition` | Full method replacement |
| WorldTransformer | `World` | `addPlayer` | Exception throw replaced with warning log |

---

## See Also

- [README.md](README.md) - Plugin overview and installation
- [HYTALE_CORE_BUGS.md](HYTALE_CORE_BUGS.md) - Bugs that cannot be fixed (requires Hytale developers)
- [CHANGELOG.md](CHANGELOG.md) - Version history
