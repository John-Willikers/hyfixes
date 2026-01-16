package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * InteractionChainMonitor - Tracks unfixable Hytale bugs for reporting
 *
 * IMPORTANT: This system CANNOT fix the InteractionChain overflow bug
 * (408+ errors per session). That bug is deep in Hytale's core networking
 * and interaction system.
 *
 * This monitor serves to:
 * 1. Track statistics about HyFixes-prevented crashes
 * 2. Provide admin insight via /interactionstatus command
 * 3. Log periodic summaries for server operators
 * 4. Help build evidence for bug reports to Hytale developers
 *
 * The InteractionChain errors we observed in logs:
 * - [SEVERE] [InteractionChain] Attempted to store sync data at X. Offset: Y, Size: 0
 * - 408 occurrences in a single 35-minute session
 * - Affects combat damage, food SFX, shield blocking
 *
 * Since we can't intercept Hytale's core logging, we track what we CAN
 * observe and fix through HyFixes.
 */
public class InteractionChainMonitor extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;

    // Timing
    private static final int LOG_INTERVAL_TICKS = 6000; // 5 minutes at 20 TPS
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastLogTime = new AtomicLong(0);

    // Statistics - HyFixes fixes
    private final AtomicInteger pickupItemFixes = new AtomicInteger(0);
    private final AtomicInteger respawnBlockFixes = new AtomicInteger(0);
    private final AtomicInteger processingBenchFixes = new AtomicInteger(0);
    private final AtomicInteger instanceExitFixes = new AtomicInteger(0);
    private final AtomicInteger objectiveFixes = new AtomicInteger(0);
    private final AtomicInteger chunkCleanups = new AtomicInteger(0);

    // Known unfixable issues (from log analysis)
    // These are constants based on our analysis - we can't track them in real-time
    // but we document them for admin awareness
    private static final String UNFIXABLE_ISSUES = """
        Known Hytale Core Issues (Cannot be fixed by plugins):

        1. InteractionChain Sync Buffer Overflow
           - ~408 errors per 35-minute session
           - Affects: combat damage, food SFX, shield blocking
           - Root cause: Buffer undersized for complex interaction chains

        2. Missing Replacement Interactions
           - ~8 errors per session
           - Affects: ConsumeSFX, Damage handlers
           - Root cause: Content configuration missing

        3. Client/Server Interaction Desync
           - ~27 warnings per session
           - Affects: Player action responsiveness
           - Root cause: Operation counter mismatch

        4. Generic Task Queue NPE
           - ~10 errors per session (6 during player login)
           - Root cause: Task references uninitialized components
        """;

    // Player count tracking
    private final AtomicInteger currentPlayers = new AtomicInteger(0);
    private final AtomicInteger peakPlayers = new AtomicInteger(0);

    private boolean loggedOnce = false;

    public InteractionChainMonitor(HyFixes plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void tick(
            float deltaTime,
            int entityIndex,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        // Only run on first entity to avoid duplicate processing
        if (entityIndex != 0) {
            return;
        }

        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[InteractionChainMonitor] Active - tracking HyFixes statistics"
            );
            loggedOnce = true;
        }

        int ticks = tickCounter.incrementAndGet();

        // Periodic logging
        if (ticks % LOG_INTERVAL_TICKS == 0) {
            logPeriodicSummary();
        }
    }

    /**
     * Log a periodic summary of HyFixes activity.
     */
    private void logPeriodicSummary() {
        lastLogTime.set(System.currentTimeMillis());

        // Collect current fix counts from other systems
        updateFixCounts();

        int totalFixes = pickupItemFixes.get() +
                         respawnBlockFixes.get() +
                         processingBenchFixes.get() +
                         instanceExitFixes.get() +
                         objectiveFixes.get();

        if (totalFixes > 0) {
            plugin.getLogger().at(Level.INFO).log(
                "[InteractionChainMonitor] 5-minute summary - Crashes prevented: " + totalFixes +
                " (PickupItem: " + pickupItemFixes.get() +
                ", RespawnBlock: " + respawnBlockFixes.get() +
                ", ProcessingBench: " + processingBenchFixes.get() +
                ", InstanceExit: " + instanceExitFixes.get() +
                ", Objective: " + objectiveFixes.get() + ")"
            );
        }
    }

    /**
     * Update fix counts from other HyFixes systems.
     * Called periodically to sync statistics.
     */
    private void updateFixCounts() {
        // These would need to be wired up to actual systems
        // For now, just track what we can
    }

    /**
     * Record a fix from the PickupItem sanitizer.
     */
    public void recordPickupItemFix() {
        pickupItemFixes.incrementAndGet();
    }

    /**
     * Record a fix from the RespawnBlock sanitizer.
     */
    public void recordRespawnBlockFix() {
        respawnBlockFixes.incrementAndGet();
    }

    /**
     * Record a fix from the ProcessingBench sanitizer.
     */
    public void recordProcessingBenchFix() {
        processingBenchFixes.incrementAndGet();
    }

    /**
     * Record a fix from the InstanceExit tracker.
     */
    public void recordInstanceExitFix() {
        instanceExitFixes.incrementAndGet();
    }

    /**
     * Record a fix from the Objective sanitizer.
     */
    public void recordObjectiveFix() {
        objectiveFixes.incrementAndGet();
    }

    /**
     * Record a chunk cleanup.
     */
    public void recordChunkCleanup() {
        chunkCleanups.incrementAndGet();
    }

    /**
     * Get full status report for admin command.
     */
    public String getFullStatus() {
        long uptimeMs = System.currentTimeMillis() - startTime.get();
        long uptimeMinutes = uptimeMs / 60000;

        int totalFixes = pickupItemFixes.get() +
                         respawnBlockFixes.get() +
                         processingBenchFixes.get() +
                         instanceExitFixes.get() +
                         objectiveFixes.get();

        StringBuilder sb = new StringBuilder();
        sb.append("=== HyFixes Statistics ===\n");
        sb.append(String.format("Uptime: %d minutes\n", uptimeMinutes));
        sb.append(String.format("Total Crashes Prevented: %d\n", totalFixes));
        sb.append("\n");
        sb.append("--- Crash Fixes by Type ---\n");
        sb.append(String.format("  PickupItem (null targetRef): %d\n", pickupItemFixes.get()));
        sb.append(String.format("  RespawnBlock (null array): %d\n", respawnBlockFixes.get()));
        sb.append(String.format("  ProcessingBench (null window ref): %d\n", processingBenchFixes.get()));
        sb.append(String.format("  InstanceExit (missing return world): %d\n", instanceExitFixes.get()));
        sb.append(String.format("  Objective (null task ref): %d\n", objectiveFixes.get()));
        sb.append("\n");
        sb.append("--- Memory Management ---\n");
        sb.append(String.format("  Chunk Cleanups: %d\n", chunkCleanups.get()));
        sb.append("\n");
        sb.append("--- Known Unfixable Issues ---\n");
        sb.append("(These are Hytale core bugs - report to developers)\n");
        sb.append("  InteractionChain Overflow: ~408/session (estimated)\n");
        sb.append("  Missing Replacement Interactions: ~8/session\n");
        sb.append("  Client/Server Desync: ~27/session\n");
        sb.append("  Task Queue NPE: ~10/session\n");

        return sb.toString();
    }

    /**
     * Get brief status for quick checks.
     */
    public String getBriefStatus() {
        int totalFixes = pickupItemFixes.get() +
                         respawnBlockFixes.get() +
                         processingBenchFixes.get() +
                         instanceExitFixes.get() +
                         objectiveFixes.get();

        return String.format("HyFixes: %d crashes prevented this session", totalFixes);
    }

    /**
     * Get information about unfixable issues (for bug reporting).
     */
    public String getUnfixableIssuesInfo() {
        return UNFIXABLE_ISSUES;
    }
}
