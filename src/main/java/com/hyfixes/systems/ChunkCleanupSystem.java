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

import com.hypixel.hytale.server.core.universe.world.World;

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
    
    // Chunk protection system
    private ChunkProtectionRegistry protectionRegistry = null;
    private ChunkProtectionScanner protectionScanner = null;
    private World cachedWorld = null;
    private int protectionVerificationInterval;
    private long lastProtectionScanTick = 0;

    // Teleporter listener for survival tracking
    private com.hyfixes.listeners.TeleporterProtectionListener teleporterListener = null;

    public ChunkCleanupSystem(HyFixes plugin) {
        this.plugin = plugin;
        this.cleanupIntervalTicks = ConfigManager.getInstance().getChunkCleanupIntervalTicks();
        this.protectionVerificationInterval = ConfigManager.getInstance().getChunkProtectionVerificationIntervalTicks();
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
        
        long currentTick = tickCounter.get();

        // Run chunk protection scan before cleanup
        if (ConfigManager.getInstance().isChunkProtectionEnabled() && 
            protectionRegistry != null && protectionScanner != null && cachedWorld != null) {
            
            try {
                // Scan for protected content
                int newlyProtected = protectionScanner.scanWorld(cachedWorld, currentTick);
                
                // Periodically remove stale protections
                if (currentTick - lastProtectionScanTick >= protectionVerificationInterval) {
                    int staleRemoved = protectionRegistry.removeStaleProtections(
                        currentTick, protectionVerificationInterval
                    );
                    lastProtectionScanTick = currentTick;
                    
                    if (ConfigManager.getInstance().logChunkProtectionEvents() && (newlyProtected > 0 || staleRemoved > 0)) {
                        plugin.getLogger().at(Level.INFO).log(
                            "[ChunkCleanupSystem] Protection scan: %d new, %d stale removed, %d total protected",
                            newlyProtected, staleRemoved, protectionRegistry.getProtectedChunkCount()
                        );
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkCleanupSystem] Protection scan error: %s", e.getMessage()
                );
            }
        }

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

        // Track cleanup cycle survival for teleporters
        if (teleporterListener != null) {
            teleporterListener.onCleanupCycleComplete(currentTick);
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
     * Set the chunk protection registry and scanner.
     * Called by HyFixes during initialization.
     */
    public void setChunkProtection(ChunkProtectionRegistry registry, ChunkProtectionScanner scanner) {
        this.protectionRegistry = registry;
        this.protectionScanner = scanner;
        if (registry != null && scanner != null) {
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkCleanupSystem] Chunk protection system registered"
            );
        }
    }

    /**
     * Set the teleporter listener for survival tracking.
     * Called by HyFixes after listener initialization.
     */
    public void setTeleporterListener(com.hyfixes.listeners.TeleporterProtectionListener listener) {
        this.teleporterListener = listener;
        if (listener != null) {
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkCleanupSystem] Teleporter listener registered for survival tracking"
            );
        }
    }
    
    /**
     * Set the world reference for protection scanning.
     */
    public void setWorld(World world) {
        this.cachedWorld = world;
    }

    /**
     * Get status for admin command.
     */
    public String getStatus() {
        long lastRun = lastCleanupTime.get();
        String lastRunStr = lastRun > 0 ?
            ((System.currentTimeMillis() - lastRun) / 1000) + "s ago" :
            "never";

        String protectionStatus = "disabled";
        if (protectionRegistry != null) {
            protectionStatus = protectionRegistry.getProtectedChunkCount() + " chunks protected";
        }

        String teleporterStatus = "not registered";
        if (teleporterListener != null) {
            teleporterStatus = String.format("%d added, %d removed, %d cleanup cycles survived",
                teleporterListener.getTeleportersAdded(),
                teleporterListener.getTeleportersRemoved(),
                teleporterListener.getCleanupCyclesSurvived()
            );
        }

        return String.format(
            "ChunkCleanupSystem Status (MAIN THREAD):\n" +
            "  ChunkLighting Ready: %s\n" +
            "  Total Cleanups: %d\n" +
            "  Successful Calls: %d\n" +
            "  Instance World Skips: %d\n" +
            "  Last Cleanup: %s\n" +
            "  Interval: %d seconds\n" +
            "  Chunk Protection: %s\n" +
            "  Teleporter Status: %s",
            chunkLightingInstance != null && invalidateLoadedChunksMethod != null,
            cleanupCount.get(),
            successCount.get(),
            threadErrorCount.get(),
            lastRunStr,
            cleanupIntervalTicks / 20,
            protectionStatus,
            teleporterStatus
        );
    }
    
    /**
     * Get the chunk protection registry (for external access).
     */
    public ChunkProtectionRegistry getProtectionRegistry() {
        return protectionRegistry;
    }
}
