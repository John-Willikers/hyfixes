# HyFixes Installation Guide

This bundle contains two plugins that work together to fix bugs in Hytale servers.

## Contents

- `hyfixes.jar` - Runtime plugin (required)
- `hyfixes-early.jar` - Early plugin for bytecode fixes (recommended)

---

## Quick Install

### Step 1: Runtime Plugin (Required)

1. Copy `hyfixes.jar` to your server's `mods/` folder
2. Restart the server

### Step 2: Early Plugin (Recommended)

1. Copy `hyfixes-early.jar` to your server's `earlyplugins/` folder
2. Start the server with early plugins enabled:
   - **Option A:** Set environment variable `ACCEPT_EARLY_PLUGINS=1`
   - **Option B:** Press Enter when you see the early plugins warning

---

## Verify Installation

### Runtime Plugin

Look for these log messages at startup:
```
[HyFixes|P] Plugin enabled - HyFixes vX.X.X
[HyFixes|P] [PickupItemSanitizer] Active
[HyFixes|P] [ChunkCleanupSystem] Active on MAIN THREAD
```

### Early Plugin

Look for these log messages at startup:
```
[HyFixes-Early] Transforming InteractionChain class...
[HyFixes-Early] Found method: putInteractionSyncData - Applying buffer overflow fix...
[HyFixes-Early] Found method: updateSyncPosition - Applying sync position fix...
[HyFixes-Early] InteractionChain transformation COMPLETE!
```

---

## What Each Plugin Fixes

### Runtime Plugin (hyfixes.jar)

Fixes crashes that can be intercepted at runtime:
- Pickup item crashes (kicks all players)
- Respawn block crashes (kicks player)
- Processing bench crashes (kicks player)
- Instance exit crashes (kicks player)
- Chunk memory bloat (OOM crashes)
- Crafting manager crashes (kicks player)
- Interaction manager crashes (kicks player)
- Quest objective crashes
- Spawn marker crashes

### Early Plugin (hyfixes-early.jar)

Fixes deep networking bugs via bytecode transformation:
- Sync buffer overflow (combat/food/tool desync)
- Sync position gaps (kicks player)

---

## Support

**Discord:** https://discord.gg/u5R7kuuGXU

**GitHub:** https://github.com/John-Willikers/hyfixes

**Report Bugs:** https://github.com/John-Willikers/hyfixes/issues
