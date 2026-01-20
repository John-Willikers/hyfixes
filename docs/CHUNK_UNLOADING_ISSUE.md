# Hytale Vanilla Chunk Unloading Issue & HyFixes Protection

## The Problem: Vanilla Hytale Chunk Memory Management

Hytale Early Access servers have an aggressive chunk unloading system designed to manage server memory. However, this system has a critical flaw: **it does not check whether chunks contain important player-placed content before unloading them**.

### What Gets Lost

When vanilla Hytale unloads a chunk, the following player content can be permanently destroyed:

- **Teleporters** - Player-placed fast travel points
- **Portals** - Instance/dungeon entry points
- **Beds & Sleeping Bags** - Respawn points
- **Other persistent block entities**

### Why This Happens

Hytale's chunk unloading system operates on a simple principle: if no players are near a chunk for a period of time, unload it to free memory. The problem is:

1. **No content awareness** - The system doesn't check what's IN the chunk
2. **No persistence layer** - Unloaded chunks with player content aren't saved properly
3. **Silent data loss** - Players don't know their teleporters are gone until they try to use them

### Symptoms Server Operators See

- Players report teleporters "disappearing" after server restarts
- Beds stop working as respawn points
- Portal destinations become invalid
- High-traffic servers lose more content than low-traffic ones (more chunk cycling)

### This Is NOT Caused By Map Plugins

Map plugins like BetterMap that render world data do **not** cause this issue. The chunk unloading is a **vanilla Hytale behavior** that occurs regardless of what plugins are installed. Map plugins simply make the problem more visible because:

1. Map rendering may trigger chunk loads in areas players haven't visited recently
2. This can accelerate the chunk lifecycle, making the vanilla bug more apparent
3. The timing correlation leads to incorrect blame on map plugins

---

## The Solution: HyFixes Chunk Protection System

Starting in **HyFixes v1.4.2**, we implemented a comprehensive chunk protection system that prevents vanilla Hytale from unloading chunks containing important content.

### How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                    HyFixes Chunk Protection                  │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. SCANNING PHASE (Background Thread)                       │
│     └─> ChunkProtectionScanner scans all loaded chunks       │
│         └─> Detects teleporters via TeleporterPlugin API     │
│         └─> Detects beds/portals via block type scanning     │
│         └─> Records protected chunk indexes in registry      │
│                                                              │
│  2. REAL-TIME MONITORING (Event Listeners)                   │
│     └─> TeleporterProtectionListener                         │
│         └─> Hooks teleporter placement events                │
│         └─> Immediately protects chunk when teleporter placed│
│     └─> RespawnBlockProtectionListener                       │
│         └─> Hooks bed/sleeping bag placement events          │
│         └─> Immediately protects chunk when respawn set      │
│                                                              │
│  3. CLEANUP PHASE (Main Thread, every 30 seconds)            │
│     └─> ChunkCleanupSystem identifies orphan chunks          │
│     └─> Checks each chunk against ChunkProtectionRegistry    │
│     └─> SKIPS any chunk that is protected                    │
│     └─> Only unloads truly orphan chunks (no players,        │
│         no important content)                                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Protected Content Types

| Content Type | Detection Method | Protection Trigger |
|--------------|------------------|-------------------|
| **Teleporters** | TeleporterPlugin API scan | Scan + real-time placement listener |
| **Portals** | Entity keyword matching | Periodic scan |
| **Beds** | Block type + RespawnBlock events | Real-time listener |
| **Sleeping Bags** | Block type + RespawnBlock events | Real-time listener |
| **Spawn Beacons** | Configurable keyword matching | Periodic scan |

### Configuration Options

HyFixes allows server operators to customize protection behavior in `mods/hyfixes/config.json`:

```json
{
  "chunkProtection": {
    "enabled": true,
    "protectedEntityKeywords": ["teleporter", "portal", "warp"],
    "protectedBlockKeywords": ["bed", "sleeping_bag", "respawn"],
    "protectSpawnBeacons": true,
    "protectGrowingPlants": false,
    "logProtectionEvents": false
  }
}
```

### Compatibility with Map Plugins

HyFixes v1.8.0+ includes a **Map-Aware Mode** specifically designed for compatibility with map plugins like BetterMap:

```json
{
  "mapAwareMode": true
}
```

Or via environment variable:
```bash
HYFIXES_DISABLE_CHUNK_UNLOAD=true
```

When enabled, this mode:
- Preserves chunk data needed for map rendering
- Coordinates with map plugin chunk requests
- Prevents the chunk cycling that can trigger vanilla data loss

---

## Technical Details

### ChunkProtectionRegistry

Thread-safe registry using `ConcurrentHashMap<Long, ProtectionInfo>`:

- **Key**: Packed chunk index (`(chunkX << 32) | chunkZ`)
- **Value**: Protection metadata (reason, timestamp, verification tick)
- **Thread Safety**: Accessed from main thread (cleanup) and background threads (scanning)

### Protection Verification

Protections are periodically verified to handle content removal:

1. Scanner re-scans protected chunks every N ticks
2. If protected content no longer exists, protection is removed
3. Prevents "ghost protections" from accumulating

### Memory Impact

The protection system adds minimal memory overhead:

- ~100 bytes per protected chunk (ProtectionInfo object)
- Typical servers protect 50-200 chunks
- Total overhead: 5-20 KB

---

## Summary

| Aspect | Vanilla Hytale | With HyFixes |
|--------|----------------|--------------|
| Teleporter persistence | Lost on chunk unload | Protected |
| Bed respawn points | Lost on chunk unload | Protected |
| Portal destinations | Lost on chunk unload | Protected |
| Memory management | Aggressive, data-lossy | Smart, content-aware |
| Map plugin compatibility | Chunk cycling causes data loss | Map-aware mode available |

**The chunk unloading data loss is a vanilla Hytale issue, not caused by map plugins.** HyFixes provides the protection layer that vanilla Hytale is missing.

---

## Links

- **HyFixes GitHub**: https://github.com/John-Willikers/hyfixes
- **HyFixes Discord**: https://discord.gg/r6KzU4n7V8
- **Issue Tracker**: https://github.com/John-Willikers/hyfixes/issues

---

*Document version: 1.0 - January 2026*
*HyFixes version: 1.9.0+*
