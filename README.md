# HyFixes

Essential bug fixes for Hytale Early Access servers. Prevents crashes, player kicks, and desync issues caused by known bugs in Hytale's core systems.

[![Discord](https://img.shields.io/badge/Discord-Join%20for%20Support-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/u5R7kuuGXU)
[![GitHub Issues](https://img.shields.io/badge/GitHub-Report%20Bugs-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/John-Willikers/hyfixes/issues)

---

## Two Plugins, One Solution

HyFixes consists of **two complementary plugins** that work together to fix different types of bugs:

| Plugin | File | Purpose |
|--------|------|---------|
| **Runtime Plugin** | `hyfixes.jar` | Fixes bugs at runtime using sanitizers and event hooks |
| **Early Plugin** | `hyfixes-early.jar` | Fixes deep core bugs via bytecode transformation at class load |

### Why Two Plugins?

Some Hytale bugs occur in code paths that cannot be intercepted at runtime. The **Early Plugin** uses Java bytecode transformation (ASM) to rewrite buggy methods *before* they're loaded, allowing us to fix issues deep in Hytale's networking and interaction systems.

---

## Quick Start

### Runtime Plugin (Required)

1. Download `hyfixes.jar` from [Releases](https://github.com/John-Willikers/hyfixes/releases)
2. Place in your server's `mods/` directory
3. Restart the server

### Early Plugin (Recommended)

1. Download `hyfixes-early.jar` from [Releases](https://github.com/John-Willikers/hyfixes/releases)
2. Place in your server's `earlyplugins/` directory
3. Start the server with early plugins enabled:
   - Set `ACCEPT_EARLY_PLUGINS=1` environment variable, OR
   - Press Enter when prompted at startup

---

## What Gets Fixed

### Runtime Plugin Fixes

| Bug | Severity | What Happens |
|-----|----------|--------------|
| Pickup Item Crash | Critical | World thread crashes, ALL players kicked |
| RespawnBlock Crash | Critical | Player kicked when breaking bed |
| ProcessingBench Crash | Critical | Player kicked when bench is destroyed |
| Instance Exit Crash | Critical | Player kicked when exiting dungeon |
| Chunk Memory Bloat | High | Server runs out of memory over time |
| CraftingManager Crash | Critical | Player kicked when opening bench |
| InteractionManager Crash | Critical | Player kicked during interactions |
| Quest Objective Crash | Critical | Quest system crashes |
| SpawnMarker Crash | Critical | World thread crashes during spawning |

### Early Plugin Fixes (Bytecode)

| Bug | Severity | What Happens |
|-----|----------|--------------|
| Sync Buffer Overflow | Critical | Combat/food/tool desync, 400-2500 errors/session |
| Sync Position Gap | Critical | Player kicked with "out of order" exception |

---

## How It Works

### Runtime Plugin

The runtime plugin registers **sanitizers** that run each server tick:

```
Server Tick
    |
    v
[PickupItemSanitizer] --> Check for null targetRef --> Mark as finished
[CraftingManagerSanitizer] --> Check for stale bench refs --> Clear them
[InteractionManagerSanitizer] --> Check for null contexts --> Remove chain
    |
    v
Hytale's Systems Run (safely, with corrupted data already cleaned up)
```

It also uses **RefSystems** that hook into entity lifecycle events to catch crashes during removal/unload operations.

### Early Plugin

The early plugin uses ASM bytecode transformation to rewrite methods at class load time:

```
Server Startup
    |
    v
JVM loads InteractionChain.class
    |
    v
[InteractionChainTransformer] intercepts class bytes
    |
    v
[PutSyncDataMethodVisitor] rewrites putInteractionSyncData()
[UpdateSyncPositionMethodVisitor] rewrites updateSyncPosition()
    |
    v
Fixed class is loaded into JVM
```

**Original buggy code:**
```java
if (adjustedIndex < 0) {
    LOGGER.severe("Attempted to store sync data...");
    return;  // DATA DROPPED!
}
```

**Transformed fixed code:**
```java
if (adjustedIndex < 0) {
    // Expand buffer backwards instead of dropping
    int expansion = -adjustedIndex;
    for (int i = 0; i < expansion; i++) {
        tempSyncData.add(0, null);
    }
    tempSyncDataOffset += adjustedIndex;
    adjustedIndex = 0;
}
// Continue processing...
```

---

## Admin Commands

| Command | Aliases | Description |
|---------|---------|-------------|
| `/hyfixes` | `/hfs`, `/interactionstatus` | Show HyFixes statistics and status |
| `/chunkstatus` | | Show chunk counts and memory info |
| `/chunkunload` | | Force immediate chunk cleanup |

---

## Verification

### Runtime Plugin Loaded

Look for these log messages at startup:
```
[HyFixes|P] Plugin enabled - HyFixes vX.X.X
[HyFixes|P] [PickupItemSanitizer] Active - monitoring for corrupted pickup items
[HyFixes|P] [ChunkCleanupSystem] Active on MAIN THREAD
```

### Early Plugin Loaded

Look for these log messages at startup:
```
[HyFixes-Early] Transforming InteractionChain class...
[HyFixes-Early] Found method: putInteractionSyncData - Applying buffer overflow fix...
[HyFixes-Early] Found method: updateSyncPosition - Applying sync position fix...
[HyFixes-Early] InteractionChain transformation COMPLETE!
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [BUGS_FIXED.md](BUGS_FIXED.md) | Detailed technical info on every bug we fix |
| [HYTALE_CORE_BUGS.md](HYTALE_CORE_BUGS.md) | Bugs that require Hytale developers to fix |
| [CHANGELOG.md](CHANGELOG.md) | Version history and release notes |

---

## Support

**Found a bug?** Please report it on [GitHub Issues](https://github.com/John-Willikers/hyfixes/issues) with:
- Server logs showing the error
- Steps to reproduce (if known)
- HyFixes version

**Need help?** Join our [Discord](https://discord.gg/u5R7kuuGXU) for community support!

---

## Building from Source

Requires Java 21 and access to `HytaleServer.jar`.

```bash
# Clone the repo
git clone https://github.com/John-Willikers/hyfixes.git
cd hyfixes

# Place HytaleServer.jar in libs/ directory
mkdir -p libs
cp /path/to/HytaleServer.jar libs/

# Build runtime plugin
./gradlew build
# Output: build/libs/hyfixes.jar

# Build early plugin
cd hyfixes-early
./gradlew build
# Output: build/libs/hyfixes-early-1.0.0.jar
```

---

## CI/CD

This repository uses GitHub Actions to automatically:
- Build on every push to `main`
- Create releases when you push a version tag (`v1.0.0`, `v1.0.1`, etc.)

---

## License

This project is provided as-is for the Hytale community. Use at your own risk.

---

## Contributing

Found another Hytale bug that needs patching? We'd love your help!

1. Open an [issue](https://github.com/John-Willikers/hyfixes/issues) describing the bug
2. Fork the repo and create a fix
3. Submit a PR with your changes

Join our [Discord](https://discord.gg/u5R7kuuGXU) to discuss ideas!
