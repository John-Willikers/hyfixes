# Default World Recovery Module Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Automatically reload the default world when it's removed exceptionally due to crashes, preventing the "no default world configured" error that blocks all players from joining.

**Architecture:** Event-driven recovery using Hytale's `RemoveWorldEvent`. When the default world is removed with `RemovalReason.EXCEPTIONAL`, schedule a delayed reload using `Universe.get().loadWorld()`. Includes configurable retry logic and cooldown to prevent infinite loops.

**Tech Stack:** Java 21, Hytale Server API (Universe, World, RemoveWorldEvent), HyFixes event registration pattern

**GitHub Issue:** https://github.com/John-Willikers/hyfixes/issues/23

---

## Background

When any plugin causes an exception in the world thread, Hytale's error handling removes the world via `removeWorldExceptionally()`. The default world is never recreated, leaving all players unable to join until server restart.

**Root Cause Chain:**
1. Plugin exception (e.g., Optimizer's AiLodController) crashes in world thread
2. Hytale catches exception and calls `Universe.removeWorldExceptionally("default")`
3. World is removed from `worlds` map, `RemoveWorldEvent` fired with `EXCEPTIONAL` reason
4. No recovery mechanism exists - world stays gone
5. `Universe.addPlayer()` checks `getDefaultWorld()` which returns null
6. Player disconnected with "No world available to join"

**Solution:** Listen for `RemoveWorldEvent`, detect when default world is removed exceptionally, and reload it from disk.

---

## Task 1: Add Config Option for Default World Recovery

**Files:**
- Modify: `src/main/java/com/hyfixes/config/HyFixesConfig.java:85` (SanitizersConfig class)

**Step 1: Add the config field**

Add `defaultWorldRecovery` to the `SanitizersConfig` class:

```java
    /**
     * Sanitizer toggle configuration
     */
    public static class SanitizersConfig {
        public boolean pickupItem = true;
        public boolean respawnBlock = true;
        public boolean processingBench = true;
        public boolean craftingManager = true;
        public boolean interactionManager = true;
        public boolean spawnBeacon = true;
        public boolean chunkTracker = true;
        public boolean gatherObjective = true;
        public boolean emptyArchetype = true;
        public boolean instancePositionTracker = true;
        public boolean pickupItemChunkHandler = true;
        public boolean defaultWorldRecovery = true;  // NEW: Auto-reload default world after crash
    }
```

**Step 2: Verify syntax**

Run: `./gradlew compileJava --console=plain 2>&1 | head -30`
Expected: BUILD SUCCESSFUL or no errors in HyFixesConfig.java

**Step 3: Commit**

```bash
git add src/main/java/com/hyfixes/config/HyFixesConfig.java
git commit -m "feat(config): add defaultWorldRecovery sanitizer toggle"
```

---

## Task 2: Add Config Manager Support

**Files:**
- Modify: `src/main/java/com/hyfixes/config/ConfigManager.java:208-222` (isSanitizerEnabled switch)

**Step 1: Add the switch case**

Add a case for `"defaultworldrecovery"` in the `isSanitizerEnabled` method:

```java
    public boolean isSanitizerEnabled(String name) {
        HyFixesConfig.SanitizersConfig s = config.sanitizers;
        return switch (name.toLowerCase()) {
            case "pickupitem" -> s.pickupItem;
            case "respawnblock" -> s.respawnBlock;
            case "processingbench" -> s.processingBench;
            case "craftingmanager" -> s.craftingManager;
            case "interactionmanager" -> s.interactionManager;
            case "spawnbeacon" -> s.spawnBeacon;
            case "chunktracker" -> s.chunkTracker;
            case "gatherobjective" -> s.gatherObjective;
            case "emptyarchetype" -> s.emptyArchetype;
            case "instancepositiontracker" -> s.instancePositionTracker;
            case "pickupitemchunkhandler" -> s.pickupItemChunkHandler;
            case "defaultworldrecovery" -> s.defaultWorldRecovery;  // NEW
            default -> {
                System.err.println("[HyFixes-Config] Unknown sanitizer: " + name);
                yield true; // Default to enabled for safety
            }
        };
    }
```

**Step 2: Verify syntax**

Run: `./gradlew compileJava --console=plain 2>&1 | head -30`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyfixes/config/ConfigManager.java
git commit -m "feat(config): add defaultWorldRecovery to sanitizer lookup"
```

---

## Task 3: Create DefaultWorldRecoverySanitizer Class

**Files:**
- Create: `src/main/java/com/hyfixes/listeners/DefaultWorldRecoverySanitizer.java`

**Step 1: Create the sanitizer class**

```java
package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * FIX: Default World Recovery - GitHub Issue #23
 *
 * PROBLEM: When any exception occurs in the world thread (from any plugin),
 * Hytale removes the world via removeWorldExceptionally(). The default world
 * is never recreated, leaving all players unable to join until server restart.
 *
 * SOLUTION: Listen for RemoveWorldEvent with EXCEPTIONAL reason. When the default
 * world is removed, schedule a reload from disk after a short delay. This gives
 * Hytale time to clean up the crashed world state before we reload.
 *
 * SAFETY FEATURES:
 * - Cooldown period to prevent rapid recovery loops
 * - Maximum retry attempts to avoid infinite loops
 * - Only recovers the configured default world
 * - Logs all recovery attempts for debugging
 */
public class DefaultWorldRecoverySanitizer {

    private final HyFixes plugin;
    private EventRegistration<?, ?> eventRegistration;

    // Recovery state
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
    private final AtomicInteger recoveryCount = new AtomicInteger(0);
    private volatile long lastRecoveryAttempt = 0;

    // Configuration
    private static final long RECOVERY_DELAY_MS = 2000;      // Wait 2s before reload
    private static final long RECOVERY_COOLDOWN_MS = 30000;  // 30s between recovery attempts
    private static final int MAX_RECOVERY_ATTEMPTS = 5;      // Max attempts before giving up

    public DefaultWorldRecoverySanitizer(HyFixes plugin) {
        this.plugin = plugin;
    }

    /**
     * Register the event listener with HyFixes event registry.
     */
    public void register() {
        eventRegistration = plugin.getEventRegistry().registerGlobal(
            RemoveWorldEvent.class,
            this::onWorldRemoved
        );

        plugin.getLogger().at(Level.INFO).log(
            "[DefaultWorldRecovery] Event handler registered - monitoring for exceptional world removal"
        );
    }

    /**
     * Handle world removal events.
     */
    private void onWorldRemoved(RemoveWorldEvent event) {
        World world = event.getWorld();
        if (world == null) {
            return;
        }

        String worldName = world.getName();
        RemoveWorldEvent.RemovalReason reason = event.getRemovalReason();

        // Only handle EXCEPTIONAL removals
        if (reason != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            plugin.getLogger().at(Level.FINE).log(
                "[DefaultWorldRecovery] World '%s' removed with reason %s - not recovering",
                worldName, reason
            );
            return;
        }

        // Check if this is the default world
        String defaultWorldName = getDefaultWorldName();
        if (defaultWorldName == null || !defaultWorldName.equalsIgnoreCase(worldName)) {
            plugin.getLogger().at(Level.INFO).log(
                "[DefaultWorldRecovery] Non-default world '%s' removed exceptionally - not recovering",
                worldName
            );
            return;
        }

        // Log the exceptional removal
        plugin.getLogger().at(Level.WARNING).log(
            "[DefaultWorldRecovery] DEFAULT WORLD '%s' removed exceptionally! Initiating recovery...",
            worldName
        );

        // Attempt recovery
        scheduleRecovery(worldName);
    }

    /**
     * Schedule world recovery after a delay.
     */
    private void scheduleRecovery(String worldName) {
        // Check if recovery is already in progress
        if (!recoveryInProgress.compareAndSet(false, true)) {
            plugin.getLogger().at(Level.INFO).log(
                "[DefaultWorldRecovery] Recovery already in progress, skipping duplicate"
            );
            return;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        if (now - lastRecoveryAttempt < RECOVERY_COOLDOWN_MS) {
            long waitTime = RECOVERY_COOLDOWN_MS - (now - lastRecoveryAttempt);
            plugin.getLogger().at(Level.WARNING).log(
                "[DefaultWorldRecovery] Cooldown active, waiting %dms before retry",
                waitTime
            );
            recoveryInProgress.set(false);

            // Schedule after cooldown
            HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> scheduleRecovery(worldName),
                waitTime,
                TimeUnit.MILLISECONDS
            );
            return;
        }

        // Check max attempts
        int attempts = recoveryCount.incrementAndGet();
        if (attempts > MAX_RECOVERY_ATTEMPTS) {
            plugin.getLogger().at(Level.SEVERE).log(
                "[DefaultWorldRecovery] CRITICAL: Max recovery attempts (%d) exceeded! " +
                "Server requires manual restart. Check logs for root cause.",
                MAX_RECOVERY_ATTEMPTS
            );
            recoveryInProgress.set(false);
            return;
        }

        lastRecoveryAttempt = now;

        plugin.getLogger().at(Level.INFO).log(
            "[DefaultWorldRecovery] Scheduling world reload in %dms (attempt %d/%d)",
            RECOVERY_DELAY_MS, attempts, MAX_RECOVERY_ATTEMPTS
        );

        // Schedule the actual recovery
        HytaleServer.SCHEDULED_EXECUTOR.schedule(
            () -> performRecovery(worldName),
            RECOVERY_DELAY_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Perform the actual world reload.
     */
    private void performRecovery(String worldName) {
        try {
            Universe universe = Universe.get();

            // Double-check the world is still missing
            World existingWorld = universe.getWorld(worldName);
            if (existingWorld != null) {
                plugin.getLogger().at(Level.INFO).log(
                    "[DefaultWorldRecovery] World '%s' already exists, recovery not needed",
                    worldName
                );
                recoveryInProgress.set(false);
                return;
            }

            // Check if world is loadable from disk
            if (!universe.isWorldLoadable(worldName)) {
                plugin.getLogger().at(Level.SEVERE).log(
                    "[DefaultWorldRecovery] CRITICAL: World '%s' is not loadable from disk! " +
                    "World data may be corrupted or missing.",
                    worldName
                );
                recoveryInProgress.set(false);
                return;
            }

            plugin.getLogger().at(Level.INFO).log(
                "[DefaultWorldRecovery] Loading world '%s' from disk...",
                worldName
            );

            // Reload the world
            CompletableFuture<World> loadFuture = universe.loadWorld(worldName);

            loadFuture.whenComplete((world, throwable) -> {
                recoveryInProgress.set(false);

                if (throwable != null) {
                    plugin.getLogger().at(Level.SEVERE).log(
                        "[DefaultWorldRecovery] FAILED to reload world '%s': %s",
                        worldName, throwable.getMessage()
                    );

                    // Log full stack trace at FINE level
                    plugin.getLogger().at(Level.FINE).log(
                        "[DefaultWorldRecovery] Stack trace: " + throwable
                    );
                } else if (world != null) {
                    plugin.getLogger().at(Level.INFO).log(
                        "[DefaultWorldRecovery] SUCCESS! World '%s' has been recovered. " +
                        "Players can now join. (Recovery #%d)",
                        worldName, recoveryCount.get()
                    );

                    // Reset recovery count on success
                    recoveryCount.set(0);
                } else {
                    plugin.getLogger().at(Level.WARNING).log(
                        "[DefaultWorldRecovery] World load returned null for '%s'",
                        worldName
                    );
                }
            });

        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).log(
                "[DefaultWorldRecovery] Exception during recovery: %s",
                e.getMessage()
            );
            recoveryInProgress.set(false);
        }
    }

    /**
     * Get the configured default world name from server config.
     */
    private String getDefaultWorldName() {
        try {
            var config = HytaleServer.get().getConfig();
            if (config != null && config.getDefaults() != null) {
                return config.getDefaults().getWorld();
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[DefaultWorldRecovery] Could not get default world name: %s",
                e.getMessage()
            );
        }
        return "default"; // Fallback
    }

    /**
     * Get the number of recovery attempts made this session.
     */
    public int getRecoveryCount() {
        return recoveryCount.get();
    }

    /**
     * Check if a recovery is currently in progress.
     */
    public boolean isRecoveryInProgress() {
        return recoveryInProgress.get();
    }

    /**
     * Reset the recovery counter (useful after manual intervention).
     */
    public void resetRecoveryCounter() {
        recoveryCount.set(0);
        lastRecoveryAttempt = 0;
        plugin.getLogger().at(Level.INFO).log(
            "[DefaultWorldRecovery] Recovery counter reset"
        );
    }
}
```

**Step 2: Verify syntax**

Run: `./gradlew compileJava --console=plain 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyfixes/listeners/DefaultWorldRecoverySanitizer.java
git commit -m "feat: add DefaultWorldRecoverySanitizer for crash recovery

Listens for RemoveWorldEvent with EXCEPTIONAL reason and automatically
reloads the default world from disk. Includes safety features:
- 2 second delay before reload (let crash cleanup finish)
- 30 second cooldown between attempts
- Max 5 recovery attempts before requiring restart
- Only recovers the configured default world

Fixes #23"
```

---

## Task 4: Register Sanitizer in HyFixes Main Class

**Files:**
- Modify: `src/main/java/com/hyfixes/HyFixes.java`

**Step 1: Add import and field**

Add to imports section (around line 10):
```java
import com.hyfixes.listeners.DefaultWorldRecoverySanitizer;
```

Add field to class (after line 45, with other sanitizer fields):
```java
    private DefaultWorldRecoverySanitizer defaultWorldRecoverySanitizer;
```

**Step 2: Add registration in registerBugFixes()**

Add after the SpawnMarkerReferenceSanitizer section (around line 295), before the ChunkTrackerSanitizer:

```java
        // Fix 16: Default World Recovery (v1.4.3)
        // GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/23
        // Automatically reloads the default world when it crashes exceptionally
        if (config.isSanitizerEnabled("defaultWorldRecovery")) {
            defaultWorldRecoverySanitizer = new DefaultWorldRecoverySanitizer(this);
            defaultWorldRecoverySanitizer.register();
            getLogger().at(Level.INFO).log("[FIX] DefaultWorldRecoverySanitizer registered - auto-recovers default world after crash");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] DefaultWorldRecoverySanitizer - disabled via config");
        }
```

**Step 3: Add getter method**

Add getter method at end of class (after other getters, around line 420):

```java
    public DefaultWorldRecoverySanitizer getDefaultWorldRecoverySanitizer() {
        return defaultWorldRecoverySanitizer;
    }
```

**Step 4: Verify syntax**

Run: `./gradlew compileJava --console=plain 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add src/main/java/com/hyfixes/HyFixes.java
git commit -m "feat: register DefaultWorldRecoverySanitizer in plugin init"
```

---

## Task 5: Build and Test

**Step 1: Full build**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL

**Step 2: Verify JAR contains new class**

Run: `unzip -l build/libs/hyfixes-*.jar | grep DefaultWorld`
Expected: Line showing `com/hyfixes/listeners/DefaultWorldRecoverySanitizer.class`

**Step 3: Commit version bump (if needed)**

If version needs updating in build.gradle.kts:
```bash
git add build.gradle.kts
git commit -m "chore: bump version for default world recovery feature"
```

---

## Task 6: Update Documentation

**Files:**
- Modify: `BUGS_FIXED.md`
- Modify: `README.md` (if it lists features)

**Step 1: Add to BUGS_FIXED.md**

Add new section:

```markdown
## Fix 16: Default World Recovery (v1.4.3)

**Issue:** [#23](https://github.com/John-Willikers/hyfixes/issues/23)

**Problem:** When any plugin causes an exception in the world thread, Hytale removes
the world via `removeWorldExceptionally()`. The default world is never recreated,
leaving all players unable to join until server restart with error:
"no default world configured"

**Solution:** `DefaultWorldRecoverySanitizer` listens for `RemoveWorldEvent` with
`RemovalReason.EXCEPTIONAL`. When the default world is removed, it automatically
reloads it from disk after a 2-second delay.

**Safety Features:**
- 2 second delay before reload (allows crash cleanup to complete)
- 30 second cooldown between recovery attempts
- Maximum 5 recovery attempts before requiring manual restart
- Only recovers the configured default world (not instance worlds)
- Full logging of all recovery attempts

**Config:** `sanitizers.defaultWorldRecovery` (default: `true`)
```

**Step 2: Commit documentation**

```bash
git add BUGS_FIXED.md README.md
git commit -m "docs: add Default World Recovery to BUGS_FIXED.md"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | Add config option | HyFixesConfig.java |
| 2 | Add config manager support | ConfigManager.java |
| 3 | Create sanitizer class | DefaultWorldRecoverySanitizer.java |
| 4 | Register in HyFixes | HyFixes.java |
| 5 | Build and verify | build.gradle.kts |
| 6 | Update documentation | BUGS_FIXED.md |

**Estimated Time:** 15-20 minutes

**Testing Notes:**
- This fix cannot be easily unit tested as it requires a running Hytale server
- Manual testing: Use a plugin that intentionally crashes the world thread
- Verify via logs: Look for `[DefaultWorldRecovery] SUCCESS!` message after crash
