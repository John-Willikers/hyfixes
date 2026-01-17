# Hytale Core Engine Bugs

This document catalogs bugs in Hytale's core engine that **cannot be fixed at the plugin level**. These require fixes from the Hytale development team.

Each bug includes:
- Detailed technical analysis
- Decompiled bytecode evidence
- Reproduction steps (where known)
- Suggested fixes for Hytale developers

**Last Updated:** 2026-01-17
**Hytale Version:** Early Access (2025+)
**Analysis Tool:** HyFixes Plugin Development

---

## Table of Contents

1. [InteractionChain Sync Buffer Overflow](#1-interactionchain-sync-buffer-overflow-critical)
2. [Missing Replacement Interactions](#2-missing-replacement-interactions-medium)
3. [Client/Server Interaction Desync](#3-clientserver-interaction-desync-medium)
4. [World Task Queue Silent NPE](#4-world-task-queue-silent-npe-low)

---

## 1. InteractionChain Sync Buffer Overflow (CRITICAL)

### Summary

The `InteractionChain` class drops sync data when packets arrive out of order, causing combat, food consumption, and tool interactions to fail silently.

### Error Pattern

```
[SEVERE] [InteractionChain] Attempted to store sync data at 1. Offset: 3, Size: 0
[SEVERE] [InteractionChain] Attempted to store sync data at 5. Offset: 7, Size: 0
```

### Frequency

- **408-2,444 errors per 35-minute session** (varies by player activity)
- Spikes during: player login, combat, food consumption, tool use

### Affected Gameplay

| Feature | Impact |
|---------|--------|
| Combat damage | Hits may not register |
| Food consumption | Sound effects missing |
| Shield blocking | Defense may fail silently |
| Tool interactions | Chopping/mining desync |

### Technical Analysis

#### Affected Class
`com.hypixel.hytale.server.core.entity.InteractionChain`

#### Affected Method
`putInteractionSyncData(int index, InteractionSyncData data)`

#### Decompiled Bytecode

```java
public void putInteractionSyncData(int index, InteractionSyncData data) {
    // Line 1: Adjust index by current offset
    index = index - tempSyncDataOffset;

    // Line 2: BUG - If adjusted index is negative, data is silently dropped
    if (index < 0) {
        LOGGER.at(Level.SEVERE).log(
            "Attempted to store sync data at %d. Offset: %d, Size: %d",
            index + tempSyncDataOffset,  // Original index
            tempSyncDataOffset,
            tempSyncData.size()
        );
        return;  // <-- DATA LOST! No recovery attempt.
    }

    // Normal processing (only reached if index >= 0)
    if (index < tempSyncData.size()) {
        tempSyncData.set(index, data);
    } else if (index == tempSyncData.size()) {
        tempSyncData.add(data);
    } else {
        LOGGER.at(Level.WARNING).log("Gap in sequence: index=%d, size=%d",
            index, tempSyncData.size());
    }
}
```

#### Raw Bytecode Evidence

```
  public void putInteractionSyncData(int, InteractionSyncData);
    Code:
       0: iload_1
       1: aload_0
       2: getfield      #64    // Field tempSyncDataOffset:I
       5: isub                 // index = index - tempSyncDataOffset
       6: istore_1
       7: iload_1
       8: ifge          59     // if (index >= 0) goto normal_processing
      11: getstatic     #431   // LOGGER
      14: getstatic     #435   // Level.SEVERE
      ...
      56: goto          143    // return without storing data
```

### Root Cause

The `tempSyncDataOffset` field advances when sync data is consumed via `updateSyncPosition()`:

```java
public void updateSyncPosition(int position) {
    if (tempSyncDataOffset == position) {
        tempSyncDataOffset = position + 1;  // Advance offset
    } else if (position > tempSyncDataOffset) {
        throw new IllegalArgumentException("Gap detected");
    }
    // Note: position < tempSyncDataOffset is silently ignored
}
```

**The bug occurs when:**
1. Server processes sync data and advances `tempSyncDataOffset`
2. A new packet arrives with an index lower than current offset
3. The adjusted index becomes negative
4. Data is logged as error and silently dropped

**This is a network packet ordering/timing issue** - packets can arrive or be processed out of sequence, but the buffer doesn't handle this case.

### Why Plugin-Level Fix Is Impossible

| Approach | Why It Fails |
|----------|--------------|
| Hook via EntityTickingSystem | Error occurs inside method call, before any plugin tick |
| Reset buffers with reflection | Would cause worse desync - can't know correct state |
| Intercept method call | Called internally by Hytale code, no hook point |
| Pre-populate buffer | Can't predict needed size, doesn't fix timing |
| Replace component | Can't override Hytale's component registration |

### Suggested Fixes for Hytale

#### Option A: Handle Negative Index Gracefully

```java
public void putInteractionSyncData(int index, InteractionSyncData data) {
    int adjustedIndex = index - tempSyncDataOffset;

    if (adjustedIndex < 0) {
        // Instead of dropping, expand buffer backwards
        int expansion = -adjustedIndex;
        for (int i = 0; i < expansion; i++) {
            tempSyncData.add(0, null);  // Prepend nulls
        }
        tempSyncDataOffset = index;  // Reset offset to new base
        adjustedIndex = 0;
    }

    // Continue with normal processing...
}
```

#### Option B: Queue Out-of-Order Data

```java
private final Map<Integer, InteractionSyncData> pendingOutOfOrder = new HashMap<>();

public void putInteractionSyncData(int index, InteractionSyncData data) {
    int adjustedIndex = index - tempSyncDataOffset;

    if (adjustedIndex < 0) {
        // Queue for later processing
        pendingOutOfOrder.put(index, data);
        return;
    }

    // Normal processing...

    // After successful insert, check if any pending data can now be processed
    processPendingData();
}
```

#### Option C: Reset State on Invalid Condition

```java
public void putInteractionSyncData(int index, InteractionSyncData data) {
    int adjustedIndex = index - tempSyncDataOffset;

    if (adjustedIndex < 0) {
        // State is corrupt - reset and retry
        LOGGER.warning("Resetting sync buffer due to out-of-order data");
        tempSyncData.clear();
        tempSyncDataOffset = index;
        adjustedIndex = 0;
    }

    // Continue with normal processing...
}
```

### Log Evidence

Distribution from analyzed server session (2,444 total errors):

| Offset Value | Count | Percentage |
|--------------|-------|------------|
| Offset: 2 | ~1,777 | 72.7% |
| Offset: 7 | ~496 | 20.3% |
| Offset: 3 | ~117 | 4.8% |
| Other | ~54 | 2.2% |

---

## 2. Missing Replacement Interactions (MEDIUM)

### Summary

The interaction system fails to find replacement handlers for certain interaction variables, causing missing sound effects and potentially broken mechanics.

### Error Pattern

```
[SEVERE] [Hytale] Missing replacement interactions for interaction:
*Consume_Charge_Food_T1_Interactions_0 for var ConsumeSFX on item ItemStack{itemId=Plant_Fruit_Berries_Red...}
```

### Frequency

- **8-15 errors per session**
- Occurs during: eating, combat, shield use

### Affected Interactions

| Interaction | Variable | Context |
|-------------|----------|---------|
| `Consume_Charge_Food_T1` | `ConsumeSFX` | Eating berries/food |
| `Skeleton_Burnt_Soldier` | `Damage` | Combat with skeletons |
| `Shield_Block` | `Damage` | Blocking attacks |
| `NPC_Melee` | `Melee_Selector` | Goblin melee attacks |

### Technical Analysis

This appears to be a **content configuration issue** where interaction definitions reference variables that don't have replacement handlers configured.

#### Likely Location
- Interaction JSON/config files
- `InteractionManager.walkChain()` or related methods

### Root Cause

The interaction system uses a variable replacement mechanism where interaction templates can reference variables (like `ConsumeSFX`) that should be replaced with specific handlers. When no replacement is found, the error is logged.

### Why Plugin-Level Fix Is Impossible

- This is content/configuration data, not runtime code
- Interaction definitions are loaded at startup from game assets
- No plugin API to modify interaction configurations

### Suggested Fix for Hytale

1. Audit all interaction definitions for missing variable replacements
2. Add default/fallback handlers for common variables
3. Add validation at load time to catch missing replacements before runtime

---

## 3. Client/Server Interaction Desync (MEDIUM)

### Summary

The client and server interaction counters drift apart over time, causing action validation failures.

### Error Pattern

```
[WARN] [InteractionEntry] 2: Client/Server desync 3 != 0, 2987 != 2733
(for ****Empty_Interactions_Use_Interactions_0_Next_Failed)
```

### Frequency

- **20-30 warnings per session**
- Accumulates over play time

### Technical Analysis

The warning shows two pairs of mismatched values:
- `3 != 0` - Likely operation state mismatch
- `2987 != 2733` - Operation counter drift (254 operations behind)

This is **directly related to Bug #1** - when sync data is dropped, the counters drift apart.

### Root Cause

When `InteractionChain.putInteractionSyncData()` drops data (Bug #1), the server's state becomes inconsistent with what the client expects. Over time, this drift accumulates.

### Why Plugin-Level Fix Is Impossible

Same as Bug #1 - this is a consequence of the sync buffer overflow.

### Suggested Fix for Hytale

Fixing Bug #1 should significantly reduce or eliminate this issue. Additionally:

1. Add periodic client/server state reconciliation
2. Implement operation counter reset mechanism
3. Add drift detection with automatic resync

---

## 4. World Task Queue Silent NPE (LOW)

### Summary

The world task queue encounters NullPointerExceptions but doesn't provide stack traces, making diagnosis difficult.

### Error Pattern

```
[SEVERE] [World|default] Failed to run task!
java.lang.NullPointerException
```

### Frequency

- **10-15 errors per session**
- **60% correlate with player login events**

### Technical Analysis

The error is logged in what appears to be `World.consumeTaskQueue()` or similar, but **no stack trace is provided**, making it impossible to identify the specific task or null reference.

### Root Cause (Hypothesized)

Tasks are queued during player initialization that reference components not yet fully initialized. By the time the task executes, expected data is null.

### Why Plugin-Level Fix Is Impossible

- No stack trace means we can't identify which task fails
- Task queue is internal to World processing
- No plugin hook into task queue execution

### Suggested Fix for Hytale

1. **Add stack trace to error logging:**
```java
catch (NullPointerException e) {
    LOGGER.severe("Failed to run task!");
    LOGGER.severe(e);  // <-- Add this line
}
```

2. Add null checks in task execution
3. Defer tasks until component initialization is complete

---

## Appendix A: How to Reproduce

### Bug #1: InteractionChain Overflow

1. Have multiple players on server
2. Engage in rapid combat (melee + ranged)
3. Eat food while moving
4. Use tools rapidly (chop trees, mine)
5. Monitor logs for SEVERE InteractionChain messages

### Bug #2: Missing Replacements

1. Eat red berries (or other T1 food)
2. Block attacks with shield
3. Fight skeleton enemies
4. Monitor logs for "Missing replacement interactions"

### Bug #3: Client/Server Desync

1. Play normally for 30+ minutes
2. Perform many interactions (combat, tools, food)
3. Monitor logs for "Client/Server desync" warnings
4. Note: Frequency increases with playtime

### Bug #4: Task Queue NPE

1. Have players join/leave server
2. Monitor logs immediately after each join
3. Note correlation with login events

---

## Appendix B: Data Collection

To help Hytale developers, server admins can collect data:

### Log Collection Script

```bash
#!/bin/bash
# Extract Hytale core bugs from server log
LOG_FILE="$1"

echo "=== InteractionChain Overflow ==="
grep -c "Attempted to store sync data" "$LOG_FILE"

echo "=== Missing Replacements ==="
grep -c "Missing replacement interactions" "$LOG_FILE"

echo "=== Client/Server Desync ==="
grep -c "Client/Server desync" "$LOG_FILE"

echo "=== Task Queue NPE ==="
grep -c "Failed to run task" "$LOG_FILE"
```

### HyFixes Status Command

The `/interactionstatus` command (alias: `/hyfixes`) shows:
- Crashes prevented by HyFixes
- Known unfixable bug documentation
- Links to this document

---

## Appendix C: Version History

| Date | Hytale Version | Bugs Documented |
|------|----------------|-----------------|
| 2026-01-17 | Early Access | Initial documentation |

---

## Contributing

Found another Hytale core bug that can't be fixed at the plugin level?

1. Document the error pattern
2. Attempt to decompile and analyze
3. Confirm plugin-level fix is impossible
4. Open a PR to add to this document

**Repository:** [github.com/John-Willikers/hyfixes](https://github.com/John-Willikers/hyfixes)
