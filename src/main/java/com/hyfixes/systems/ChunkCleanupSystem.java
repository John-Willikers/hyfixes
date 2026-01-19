package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * ChunkCleanupSystem - Runs chunk cleanup methods on the MAIN THREAD
 *
 * This system extends EntityTickingSystem to ensure our cleanup methods
 * are called from the main server thread, avoiding InvocationTargetExceptions.
 *
 * It runs every cleanupIntervalTicks ticks (default: 600 = 30 seconds at 20 TPS)
 *
 * NOTE: We only call invalidateLoadedChunks() here. The waitForLoadingChunks()
 * method was removed because calling it from within a system tick causes
 * "Store is currently processing!" errors - the Store's task queue contains
 * operations that try to modify entities while we're still inside Store.tick().
 */
public class ChunkCleanupSystem extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;

    // Configuration (loaded from ConfigManager)
    private final int cleanupIntervalTicks;

    // State
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final AtomicInteger cleanupCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger threadErrorCount = new AtomicInteger(0);
    private final AtomicLong lastCleanupTime = new AtomicLong(0);
    private boolean loggedOnce = false;
    private boolean hasRunOnce = false;
    private boolean loggedThreadWarning = false;

    // Cached method references (set by ChunkUnloadManager)
    // NOTE: chunkStoreInstance/waitForLoadingChunks removed - causes "Store is currently processing!" errors
    private Object chunkLightingInstance = null;
    private Method invalidateLoadedChunksMethod = null;

    public ChunkCleanupSystem(HyFixes plugin) {
        this.plugin = plugin;
        this.cleanupIntervalTicks = ConfigManager.getInstance().getChunkCleanupIntervalTicks();
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Query for Player entities - we just need something to tick on
        // We only run our logic once per cleanup interval anyway
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
        // Only run on the first entity each tick to avoid duplicate processing
        if (entityIndex != 0) {
            return;
        }

        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkCleanupSystem] Active on MAIN THREAD - will run cleanup every " +
                (cleanupIntervalTicks / 20) + " seconds"
            );
            loggedOnce = true;
        }

        // Increment tick counter
        int currentTick = tickCounter.incrementAndGet();

        // Only run cleanup every cleanupIntervalTicks
        if (currentTick % cleanupIntervalTicks != 0) {
            return;
        }

        // Run cleanup on main thread
        runMainThreadCleanup();
    }

    /**
     * Run cleanup methods on the main thread.
     * This is called from tick() which runs on the main server thread.
     */
    private void runMainThreadCleanup() {
        cleanupCount.incrementAndGet();
        lastCleanupTime.set(System.currentTimeMillis());

        int successes = 0;

        // Try invalidateLoadedChunks()
        if (chunkLightingInstance != null && invalidateLoadedChunksMethod != null) {
            try {
                invalidateLoadedChunksMethod.invoke(chunkLightingInstance);
                successes++;
                // Only log success occasionally to reduce spam
                if (cleanupCount.get() % 10 == 1) {
                    plugin.getLogger().at(Level.INFO).log(
                        "[ChunkCleanupSystem] invalidateLoadedChunks() running normally"
                    );
                }
            } catch (Exception e) {
                String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();

                // Check if this is a thread assertion error (expected when instance worlds exist)
                boolean isThreadError = cause != null &&
                    (cause.contains("Assert not in thread") ||
                     cause.contains("Assert not in ticking thread") ||
                     cause.contains("WorldThread - instance"));

                if (isThreadError) {
                    // Thread errors are expected when instance worlds are active
                    // Only log once to avoid spam, but track count
                    threadErrorCount.incrementAndGet();
                    if (!loggedThreadWarning) {
                        plugin.getLogger().at(Level.INFO).log(
                            "[ChunkCleanupSystem] Note: invalidateLoadedChunks() skipped due to instance world thread context. " +
                            "This is normal when dungeons/instances are active. Tracking silently."
                        );
                        loggedThreadWarning = true;
                    }
                } else {
                    // Unexpected error - log it
                    plugin.getLogger().at(Level.WARNING).log(
                        "[ChunkCleanupSystem] Failed invalidateLoadedChunks(): " +
                        e.getClass().getSimpleName() + " - " + cause
                    );
                }
            }
        }

        // NOTE: waitForLoadingChunks() was removed - calling it from within a system tick
        // causes "Store is currently processing!" errors because the task queue contains
        // operations that try to modify entities while we're still inside Store.tick().

        if (successes > 0) {
            successCount.addAndGet(successes);
        }

        if (!hasRunOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkCleanupSystem] First cleanup cycle complete. " +
                "ChunkLighting: " + (chunkLightingInstance != null)
            );
            hasRunOnce = true;
        }
    }

    /**
     * Set the ChunkLightingManager instance for cleanup operations.
     * Called by ChunkUnloadManager after API discovery.
     *
     * NOTE: setChunkStoreInstance() was removed - waitForLoadingChunks() causes
     * "Store is currently processing!" errors when called from a system tick.
     */
    public void setChunkLightingInstance(Object instance) {
        this.chunkLightingInstance = instance;
        if (instance != null) {
            try {
                invalidateLoadedChunksMethod = instance.getClass().getMethod("invalidateLoadedChunks");
                invalidateLoadedChunksMethod.setAccessible(true);
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkCleanupSystem] Registered ChunkLightingManager with invalidateLoadedChunks()"
                );
            } catch (NoSuchMethodException e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkCleanupSystem] ChunkLightingManager does not have invalidateLoadedChunks() method"
                );
            }
        }
    }

    /**
     * Get status for admin command.
     */
    public String getStatus() {
        long lastRun = lastCleanupTime.get();
        String lastRunStr = lastRun > 0 ?
            ((System.currentTimeMillis() - lastRun) / 1000) + "s ago" :
            "never";

        return String.format(
            "ChunkCleanupSystem Status (MAIN THREAD):\n" +
            "  ChunkLighting Ready: %s\n" +
            "  Total Cleanups: %d\n" +
            "  Successful Calls: %d\n" +
            "  Instance World Skips: %d\n" +
            "  Last Cleanup: %s\n" +
            "  Interval: %d seconds",
            chunkLightingInstance != null && invalidateLoadedChunksMethod != null,
            cleanupCount.get(),
            successCount.get(),
            threadErrorCount.get(),
            lastRunStr,
            cleanupIntervalTicks / 20
        );
    }
}
