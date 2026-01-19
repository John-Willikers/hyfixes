package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hyfixes.util.ReflectionHelper;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * ChunkUnloadManager - AGGRESSIVE chunk unload system
 *
 * This system runs every 30 seconds and attempts to force-unload chunks that are
 * no longer needed. It uses deep reflection to find and call internal Hytale APIs.
 *
 * v1.2.1 AGGRESSIVE MODE:
 * - Scans ALL methods for release/remove/clear/evict/trim/flush/purge/dispose
 * - Digs into ChunkStore internal fields to find caches
 * - Tries to release chunk references
 * - Calls any method that might trigger cleanup
 *
 * PROMETHEUS EVIDENCE:
 * - Player flew in straight line, loaded 9,440+ chunks
 * - Only 634 chunks "active" (view radius)
 * - Orphan chunks sitting in memory NOT unloading
 * - Memory keeps growing unbounded
 */
public class ChunkUnloadManager {

    private final HyFixes plugin;
    private final ReflectionHelper reflectionHelper;
    private final ScheduledExecutorService scheduler;

    // Statistics
    private final AtomicInteger totalUnloadAttempts = new AtomicInteger(0);
    private final AtomicInteger successfulUnloads = new AtomicInteger(0);
    private final AtomicInteger methodsCalled = new AtomicInteger(0);
    private final AtomicLong lastRunTime = new AtomicLong(0);

    // Configuration (loaded from ConfigManager)
    private final int checkIntervalSeconds;
    private final int initialDelaySeconds;
    private final int gcEveryNAttempts;

    // State
    private ScheduledFuture<?> scheduledTask;
    private boolean apiDiscovered = false;
    private boolean loggedOnce = false;

    // Discovered API references (cached after first discovery)
    private Object chunkStoreInstance = null;
    private Object chunkLightingInstance = null;
    private final List<Method> cleanupMethods = new ArrayList<>();
    private final List<Method> allChunkStoreMethods = new ArrayList<>();
    private Method getChunkIndexesMethod = null;
    private Method releaseRefMethod = null;

    // Reference to the main-thread cleanup system
    private ChunkCleanupSystem chunkCleanupSystem = null;

    // Keywords that suggest cleanup/release functionality
    private static final String[] CLEANUP_KEYWORDS = {
        "unload", "release", "remove", "clear", "dispose", "free",
        "evict", "trim", "flush", "purge", "clean", "invalidate",
        "reset", "discard", "drop", "expire", "gc", "collect"
    };

    public ChunkUnloadManager(HyFixes plugin) {
        this.plugin = plugin;
        this.reflectionHelper = new ReflectionHelper(plugin);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HyFixes-ChunkUnloadManager");
            t.setDaemon(true);
            return t;
        });

        // Load configuration
        ConfigManager config = ConfigManager.getInstance();
        this.checkIntervalSeconds = config.getChunkUnloadIntervalSeconds();
        this.initialDelaySeconds = config.getChunkUnloadInitialDelaySeconds();
        this.gcEveryNAttempts = config.getChunkUnloadGcEveryNAttempts();
    }

    /**
     * Start the chunk unload manager.
     */
    public void start() {
        plugin.getLogger().at(Level.INFO).log(
            "[ChunkUnloadManager] Starting AGGRESSIVE chunk unload manager v1.2.1..."
        );

        scheduledTask = scheduler.scheduleAtFixedRate(
            this::runChunkCleanup,
            initialDelaySeconds,
            checkIntervalSeconds,
            TimeUnit.SECONDS
        );

        plugin.getLogger().at(Level.INFO).log(
            "[ChunkUnloadManager] Scheduled chunk cleanup every " + checkIntervalSeconds + " seconds (initial delay: " + initialDelaySeconds + "s)"
        );
    }

    /**
     * Set the ChunkCleanupSystem that runs on the main thread.
     * Discovered instances will be passed to it for safe method invocation.
     */
    public void setChunkCleanupSystem(ChunkCleanupSystem system) {
        this.chunkCleanupSystem = system;
        plugin.getLogger().at(Level.INFO).log(
            "[ChunkUnloadManager] ChunkCleanupSystem registered for main-thread cleanup"
        );

        // If we already discovered instances, pass them now
        // NOTE: chunkStoreInstance is no longer passed - waitForLoadingChunks() causes
        // "Store is currently processing!" errors when called from a system tick
        if (chunkLightingInstance != null) {
            chunkCleanupSystem.setChunkLightingInstance(chunkLightingInstance);
        }
    }

    /**
     * Stop the chunk unload manager.
     */
    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        plugin.getLogger().at(Level.INFO).log(
            "[ChunkUnloadManager] Stopped. Total attempts: " + totalUnloadAttempts.get() +
            ", Methods called: " + methodsCalled.get()
        );
    }

    /**
     * Main cleanup routine - runs every CHECK_INTERVAL_SECONDS.
     */
    private void runChunkCleanup() {
        try {
            lastRunTime.set(System.currentTimeMillis());

            // First run: discover APIs aggressively
            if (!apiDiscovered) {
                discoverChunkAPIsAggressively();
                apiDiscovered = true;
            }

            if (!loggedOnce) {
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkUnloadManager] Active - monitoring chunk counts"
                );
                loggedOnce = true;
            }

            World world = getDefaultWorld();
            if (world == null) {
                return;
            }

            // Try all aggressive unload strategies
            attemptAggressiveUnload(world);

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkUnloadManager] Error during chunk cleanup: " + e.getMessage()
            );
        }
    }

    /**
     * AGGRESSIVE API discovery - scans EVERYTHING for cleanup methods.
     */
    private void discoverChunkAPIsAggressively() {
        plugin.getLogger().at(Level.INFO).log(
            "[ChunkUnloadManager] AGGRESSIVE API discovery starting..."
        );

        World world = getDefaultWorld();
        if (world == null) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkUnloadManager] Cannot discover APIs - no world available yet"
            );
            return;
        }

        // Use ReflectionHelper for initial scan
        reflectionHelper.discoverAPIs(world, "World");

        // Now do our own deep dive
        scanWorldForChunkManagers(world);

        // Log what we found
        plugin.getLogger().at(Level.INFO).log(
            "[ChunkUnloadManager] Discovery complete: " + cleanupMethods.size() + " potential cleanup methods"
        );
        for (Method m : cleanupMethods) {
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkUnloadManager]   - " + m.getDeclaringClass().getSimpleName() + "." + m.getName()
            );
        }
    }

    /**
     * Deep scan of World class for chunk managers and their internals.
     */
    private void scanWorldForChunkManagers(World world) {
        Class<?> clazz = world.getClass();

        while (clazz != null && clazz != Object.class) {
            try {
                for (Field f : clazz.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    String type = f.getType().getSimpleName().toLowerCase();

                    if (name.contains("chunk") || type.contains("chunk") ||
                        name.contains("region") || type.contains("region") ||
                        name.contains("storage") || type.contains("storage") ||
                        name.contains("cache") || type.contains("cache")) {

                        f.setAccessible(true);
                        Object manager = f.get(world);

                        if (manager != null) {
                            plugin.getLogger().at(Level.INFO).log(
                                "[ChunkUnloadManager] Found manager: " +
                                f.getName() + " (" + f.getType().getSimpleName() + ")"
                            );

                            // Save references and pass to main-thread cleanup system
                            // NOTE: ChunkStore is no longer passed to cleanup system because
                            // waitForLoadingChunks() causes "Store is currently processing!" errors
                            if (type.contains("chunkstore") || name.equals("chunkstore")) {
                                chunkStoreInstance = manager;
                                // ChunkStore reference kept for other potential uses, but NOT passed
                                // to ChunkCleanupSystem anymore
                            }
                            if (type.contains("chunklighting") || name.contains("lighting")) {
                                chunkLightingInstance = manager;
                                if (chunkCleanupSystem != null) {
                                    chunkCleanupSystem.setChunkLightingInstance(manager);
                                    plugin.getLogger().at(Level.INFO).log(
                                        "[ChunkUnloadManager] Passed ChunkLightingManager to main-thread cleanup system"
                                    );
                                }
                            }

                            // Scan ALL methods on this manager
                            scanManagerAggressively(manager);

                            // Also scan internal fields of the manager for caches
                            scanManagerInternals(manager);
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().at(Level.FINE).log(
                    "[ChunkUnloadManager] Error scanning class " + clazz.getName() + ": " + e.getMessage()
                );
            }

            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Aggressively scan a manager for ALL methods, especially cleanup-related ones.
     */
    private void scanManagerAggressively(Object manager) {
        Class<?> clazz = manager.getClass();
        String className = clazz.getSimpleName();

        // Get ALL methods including inherited
        Set<Method> allMethods = new HashSet<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            allMethods.addAll(Arrays.asList(c.getDeclaredMethods()));
            allMethods.addAll(Arrays.asList(c.getMethods()));
            c = c.getSuperclass();
        }

        for (Method m : allMethods) {
            String name = m.getName().toLowerCase();

            try {
                m.setAccessible(true);
            } catch (Exception e) {
                continue;
            }

            // Save all ChunkStore methods
            if (className.contains("ChunkStore")) {
                allChunkStoreMethods.add(m);
            }

            // Look for getChunkIndexes specifically
            if (name.equals("getchunkindexes")) {
                getChunkIndexesMethod = m;
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkUnloadManager] Found getChunkIndexes() on " + className
                );
            }

            // Look for any cleanup-related methods
            for (String keyword : CLEANUP_KEYWORDS) {
                if (name.contains(keyword)) {
                    cleanupMethods.add(m);
                    plugin.getLogger().at(Level.INFO).log(
                        "[ChunkUnloadManager] Found cleanup method: " + m.getName() +
                        "(" + formatParams(m) + ") on " + className
                    );
                    break;
                }
            }

            // Log count/size methods to understand current state
            if (name.contains("count") || name.contains("size")) {
                try {
                    if (m.getParameterCount() == 0) {
                        Object result = m.invoke(manager);
                        if (result instanceof Number) {
                            plugin.getLogger().at(Level.INFO).log(
                                "[ChunkUnloadManager] " + m.getName() + "() = " + result
                            );
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Scan internal fields of managers to find caches we can clear.
     */
    private void scanManagerInternals(Object manager) {
        Class<?> clazz = manager.getClass();
        String className = clazz.getSimpleName();

        while (clazz != null && clazz != Object.class) {
            try {
                for (Field f : clazz.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    String type = f.getType().getSimpleName().toLowerCase();

                    // Look for internal maps, caches, collections
                    if (name.contains("cache") || name.contains("map") ||
                        name.contains("loaded") || name.contains("chunks") ||
                        type.contains("map") || type.contains("cache") ||
                        type.contains("concurrent")) {

                        f.setAccessible(true);
                        Object field = f.get(manager);

                        if (field != null) {
                            int size = getCollectionSize(field);
                            plugin.getLogger().at(Level.INFO).log(
                                "[ChunkUnloadManager] Found internal field: " + className + "." +
                                f.getName() + " (" + f.getType().getSimpleName() + ") size=" + size
                            );

                            // Look for clear() method on this collection
                            try {
                                Method clearMethod = field.getClass().getMethod("clear");
                                if (clearMethod != null) {
                                    plugin.getLogger().at(Level.INFO).log(
                                        "[ChunkUnloadManager] Field " + f.getName() + " has clear() method!"
                                    );
                                }
                            } catch (NoSuchMethodException e) {
                                // No clear method
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore field access errors
            }

            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Get the size of a collection/map/array.
     */
    private int getCollectionSize(Object obj) {
        try {
            if (obj instanceof Collection) {
                return ((Collection<?>) obj).size();
            } else if (obj instanceof Map) {
                return ((Map<?, ?>) obj).size();
            } else if (obj.getClass().isArray()) {
                return java.lang.reflect.Array.getLength(obj);
            } else {
                // Try to call size() method
                Method sizeMethod = obj.getClass().getMethod("size");
                Object result = sizeMethod.invoke(obj);
                if (result instanceof Number) {
                    return ((Number) result).intValue();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }

    /**
     * Format method parameters for logging.
     */
    private String formatParams(Method m) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
     * AGGRESSIVE unload attempt - try everything we found.
     * Note: Direct calls to invalidateLoadedChunks/waitForLoadingChunks are now
     * handled by ChunkCleanupSystem on the MAIN THREAD to avoid InvocationTargetException.
     */
    private void attemptAggressiveUnload(World world) {
        totalUnloadAttempts.incrementAndGet();
        int callCount = 0;

        // Strategy 0: (MOVED TO MAIN THREAD) Direct calls now handled by ChunkCleanupSystem
        // The ChunkCleanupSystem ticks on the main server thread and calls
        // invalidateLoadedChunks() and waitForLoadingChunks() safely.

        // Strategy 1: Call all discovered cleanup methods with safe signatures
        // SKIP methods that are handled by ChunkCleanupSystem on main thread
        for (Method m : cleanupMethods) {
            String methodName = m.getName();

            // Skip methods that must run on main thread (handled by ChunkCleanupSystem)
            if (methodName.equals("waitForLoadingChunks") ||
                methodName.equals("invalidateLoadedChunks") ||
                methodName.startsWith("lambda$invalidateLoadedChunks")) {
                continue; // These are called from ChunkCleanupSystem on main thread
            }

            try {
                Object target = getTargetForMethod(m);
                if (target == null) {
                    // Log at FINE level to reduce spam
                    continue;
                }

                Class<?>[] params = m.getParameterTypes();

                if (params.length == 0) {
                    // No-arg method - safe to call
                    m.invoke(target);
                    callCount++;
                    plugin.getLogger().at(Level.INFO).log(
                        "[ChunkUnloadManager] Called " + m.getName() + "() on " +
                        target.getClass().getSimpleName()
                    );
                } else if (params.length == 1 && params[0] == boolean.class) {
                    // Boolean arg - try with true (force)
                    m.invoke(target, true);
                    callCount++;
                    plugin.getLogger().at(Level.INFO).log(
                        "[ChunkUnloadManager] Called " + m.getName() + "(true) on " +
                        target.getClass().getSimpleName()
                    );
                }
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkUnloadManager] Failed to call " + m.getName() + ": " + e.getClass().getSimpleName() + " - " + e.getMessage()
                );
            }
        }

        // Strategy 2: Get chunk indexes and try to release them
        if (getChunkIndexesMethod != null && chunkStoreInstance != null) {
            tryReleaseChunkIndexes();
        }

        // Strategy 3: Force GC more aggressively
        if (totalUnloadAttempts.get() % gcEveryNAttempts == 0) {
            System.gc();
            System.runFinalization();
            System.gc();
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkUnloadManager] Forced double GC (attempt #" + totalUnloadAttempts.get() + ")"
            );
        }

        methodsCalled.addAndGet(callCount);

        if (callCount > 0) {
            successfulUnloads.incrementAndGet();
        }
    }

    /**
     * Try direct calls to known methods on our cached instances.
     */
    private int tryDirectCalls() {
        int callCount = 0;

        // Try invalidateLoadedChunks() on ChunkLightingManager
        if (chunkLightingInstance != null) {
            try {
                Method invalidate = chunkLightingInstance.getClass().getMethod("invalidateLoadedChunks");
                invalidate.invoke(chunkLightingInstance);
                callCount++;
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkUnloadManager] Direct call: invalidateLoadedChunks() on ChunkLightingManager"
                );
            } catch (NoSuchMethodException e) {
                // Method doesn't exist
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkUnloadManager] Direct call failed: invalidateLoadedChunks() - " + e.getMessage()
                );
            }
        }

        // Try waitForLoadingChunks() on ChunkStore (might help sync state)
        if (chunkStoreInstance != null) {
            try {
                Method waitMethod = chunkStoreInstance.getClass().getMethod("waitForLoadingChunks");
                waitMethod.invoke(chunkStoreInstance);
                callCount++;
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkUnloadManager] Direct call: waitForLoadingChunks() on ChunkStore"
                );
            } catch (NoSuchMethodException e) {
                // Method doesn't exist
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkUnloadManager] Direct call failed: waitForLoadingChunks() - " + e.getMessage()
                );
            }
        }

        return callCount;
    }

    /**
     * Try to get chunk indexes and release them.
     */
    private void tryReleaseChunkIndexes() {
        try {
            Object indexes = getChunkIndexesMethod.invoke(chunkStoreInstance);
            if (indexes != null) {
                int count = getCollectionSize(indexes);
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkUnloadManager] ChunkStore has " + count + " chunk indexes"
                );

                // Look for a release method that takes chunk index
                for (Method m : allChunkStoreMethods) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("release") || name.contains("remove") || name.contains("unload")) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1 && (params[0] == long.class || params[0] == Long.class)) {
                            releaseRefMethod = m;
                            plugin.getLogger().at(Level.INFO).log(
                                "[ChunkUnloadManager] Found potential release method: " + m.getName() + "(long)"
                            );
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.FINE).log(
                "[ChunkUnloadManager] Failed to get chunk indexes: " + e.getMessage()
            );
        }
    }

    /**
     * Get the appropriate target object for invoking a method.
     * More aggressive matching - try both instances if method might work.
     */
    private Object getTargetForMethod(Method m) {
        String declaringClass = m.getDeclaringClass().getSimpleName();
        String methodName = m.getName().toLowerCase();

        // Direct class name matching
        if (declaringClass.contains("ChunkStore") && chunkStoreInstance != null) {
            return chunkStoreInstance;
        }
        if (declaringClass.contains("ChunkLighting") && chunkLightingInstance != null) {
            return chunkLightingInstance;
        }

        // Check if method is callable on our instances (handles inheritance)
        if (chunkStoreInstance != null) {
            try {
                // Check if instance's class has this method
                chunkStoreInstance.getClass().getMethod(m.getName(), m.getParameterTypes());
                return chunkStoreInstance;
            } catch (NoSuchMethodException e) {
                // Method not found on this instance
            }
        }
        if (chunkLightingInstance != null) {
            try {
                chunkLightingInstance.getClass().getMethod(m.getName(), m.getParameterTypes());
                return chunkLightingInstance;
            } catch (NoSuchMethodException e) {
                // Method not found on this instance
            }
        }

        // Fallback: Try assignability check
        if (chunkStoreInstance != null &&
            m.getDeclaringClass().isAssignableFrom(chunkStoreInstance.getClass())) {
            return chunkStoreInstance;
        }
        if (chunkLightingInstance != null &&
            m.getDeclaringClass().isAssignableFrom(chunkLightingInstance.getClass())) {
            return chunkLightingInstance;
        }

        plugin.getLogger().at(Level.FINE).log(
            "[ChunkUnloadManager] No target found for method: " + m.getName() +
            " (declaring: " + declaringClass + ")"
        );
        return null;
    }

    /**
     * Get the default world.
     */
    private World getDefaultWorld() {
        try {
            return Universe.get().getWorld("default");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Force an immediate chunk cleanup (for admin command).
     */
    public void forceCleanup() {
        plugin.getLogger().at(Level.INFO).log(
            "[ChunkUnloadManager] Forcing AGGRESSIVE chunk cleanup..."
        );
        runChunkCleanup();
    }

    /**
     * Get statistics for admin command.
     */
    public String getStatus() {
        long lastRun = lastRunTime.get();
        String lastRunStr = lastRun > 0 ?
            ((System.currentTimeMillis() - lastRun) / 1000) + "s ago" :
            "never";

        return String.format(
            "ChunkUnloadManager Status (AGGRESSIVE v1.2.1):\n" +
            "  API Discovered: %s\n" +
            "  ChunkStore Found: %s\n" +
            "  ChunkLighting Found: %s\n" +
            "  Cleanup Methods: %d\n" +
            "  Total Attempts: %d\n" +
            "  Methods Called: %d\n" +
            "  Last Run: %s",
            apiDiscovered,
            chunkStoreInstance != null,
            chunkLightingInstance != null,
            cleanupMethods.size(),
            totalUnloadAttempts.get(),
            methodsCalled.get(),
            lastRunStr
        );
    }

    /**
     * Get discovered chunk methods for debug command.
     */
    public java.util.List<String> getDiscoveredMethods() {
        List<String> result = new ArrayList<>();
        for (Method m : cleanupMethods) {
            result.add(m.getDeclaringClass().getSimpleName() + "." + m.getName() + "(" + formatParams(m) + ")");
        }
        return result;
    }
}
