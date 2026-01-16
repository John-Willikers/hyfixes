# HyFixes

Bug fixes for Hytale Early Access servers. This plugin patches known crashes and issues that can kick players or crash world threads.

## Installation

1. Download the latest `hyfixes-x.x.x.jar` from [Releases](https://github.com/John-Willikers/hyfixes/releases)
2. Place in your server's `mods/` directory
3. Restart the server

## Bug Fixes

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
- Memory grows unbounded: 4GB → 14GB+ while idle

**Impact:** Server eventually runs out of memory and crashes, or GC pauses become severe.

**The Fix:**

`ChunkUnloadManager` uses reflection to discover Hytale's internal chunk management APIs:
- `ChunkStore.waitForLoadingChunks()` - Syncs chunk loading state
- `ChunkLightingManager.invalidateLoadedChunks()` - Triggers chunk cleanup

`ChunkCleanupSystem` is an `EntityTickingSystem` that runs these methods on the **main server thread** every 30 seconds, avoiding the `InvocationTargetException` that occurs when calling them from background threads.

**Results:** Chunks now properly cycle - observed 942 → 211 chunks (77% reduction) after players move away.

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

### 9. Interaction Chain Monitoring (v1.3.0)

**Severity:** Monitoring - Cannot fix, only track

**The Bug:**

Hytale's InteractionChain system has a buffer overflow issue that causes 400+ errors per session:
```
[SEVERE] [InteractionChain] Attempted to store sync data at 1. Offset: 3, Size: 0
```

This affects combat damage, food SFX, and shield blocking. **This cannot be fixed at the plugin level** as it's deep in Hytale's core networking code.

**The Fix:**

`InteractionChainMonitor` tracks HyFixes statistics and known unfixable issues for reporting to Hytale developers.

**Admin Commands:**
- `/interactionstatus` (alias: `/hyfixes`, `/hfs`) - Shows comprehensive HyFixes statistics and known issues

---

## Technical Details

| Fix | System Type | Registry | Hook Point |
|-----|-------------|----------|------------|
| PickupItemSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, queries `PickupItemComponent` |
| PickupItemChunkHandler | `RefSystem<EntityStore>` | EntityStoreRegistry | `onEntityRemove()` for `PickupItemComponent` (v1.3.0) |
| RespawnBlockSanitizer | `RefSystem<ChunkStore>` | ChunkStoreRegistry | `onEntityRemove()` for `RespawnBlock` component |
| ProcessingBenchSanitizer | `RefSystem<ChunkStore>` | ChunkStoreRegistry | `onEntityRemove()` for `ProcessingBenchState` component |
| InstancePositionTracker | `Listener` (EventHandler) | EventBus | `DrainPlayerFromWorldEvent`, `AddPlayerToWorldEvent` |
| EmptyArchetypeSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, queries `TransformComponent` |
| ChunkUnloadManager | `ScheduledExecutorService` | N/A (background thread) | Reflection-based API discovery, 30s interval |
| ChunkCleanupSystem | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every 600 ticks (30s), calls cleanup on main thread |
| GatherObjectiveTaskSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, validates objective refs (v1.3.0) |
| InteractionChainMonitor | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Tracks HyFixes statistics (v1.3.0) |

## Building

Requires Java 21 and access to `HytaleServer.jar` (not included).

```bash
# Place HytaleServer.jar in libs/ directory
mkdir -p libs
cp /path/to/HytaleServer.jar libs/

# Build
./gradlew build

# JAR output
ls build/libs/hyfixes-*.jar
```

## CI/CD

This repository uses GitHub Actions to automatically:
- Build on every push to `main`
- Create releases when you push a version tag (`v1.0.0`, `v1.0.1`, etc.)

The workflow uses the official Hytale downloader to fetch `HytaleServer.jar` for compilation.

## License

This project is provided as-is for the Hytale community. Use at your own risk.

## Contributing

Found another Hytale bug that needs patching? Open an issue or PR!
