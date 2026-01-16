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

### 4. Empty Archetype Entity Monitoring

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

## Technical Details

| Fix | System Type | Registry | Hook Point |
|-----|-------------|----------|------------|
| PickupItemSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, queries `PickupItemComponent` |
| RespawnBlockSanitizer | `RefSystem<ChunkStore>` | ChunkStoreRegistry | `onEntityRemove()` for `RespawnBlock` component |
| ProcessingBenchSanitizer | `RefSystem<ChunkStore>` | ChunkStoreRegistry | `onEntityRemove()` for `ProcessingBenchState` component |
| EmptyArchetypeSanitizer | `EntityTickingSystem<EntityStore>` | EntityStoreRegistry | Every tick, queries `TransformComponent` |

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
