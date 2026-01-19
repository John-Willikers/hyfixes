package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * MapAwareChunkManager - Ensures map images are generated before chunks are unloaded.
 *
 * This fixes the BetterMaps compatibility issue by pre-rendering map images for chunks
 * that are about to be unloaded. Once the map image is cached in WorldMapManager,
 * the actual chunk data can be safely discarded.
 *
 * Flow:
 * 1. Identify chunks that will be unloaded
 * 2. Check if each chunk has a cached map image
 * 3. If not, trigger async map generation and wait
 * 4. Once all images are cached, proceed with chunk unload
 *
 * This ensures the map system always has the data it needs, even after chunks are freed.
 */
public class MapAwareChunkManager {

    private final HyFixes plugin;
    private final ScheduledExecutorService scheduler;

    // Track chunks we're currently pre-rendering maps for
    private final Set<Long> pendingMapGeneration = ConcurrentHashMap.newKeySet();

    // Track chunks that are safe to unload (map already rendered)
    private final Set<Long> safeToUnload = ConcurrentHashMap.newKeySet();

    // Cached reflection references
    private Method getImageIfInMemoryMethod;
    private Method getImageAsyncMethod;
    private Object worldMapManagerInstance;
    private Field imagesField;

    // Statistics
    private int chunksPreRendered = 0;
    private int chunksSkippedAlreadyCached = 0;
    private int mapGenerationErrors = 0;

    public MapAwareChunkManager(HyFixes plugin) {
        this.plugin = plugin;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HyFixes-MapAwareChunkManager");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initialize by discovering WorldMapManager APIs.
     */
    public boolean initialize(World world) {
        try {
            // Get WorldMapManager from World
            Method getWorldMapManagerMethod = world.getClass().getMethod("getWorldMapManager");
            worldMapManagerInstance = getWorldMapManagerMethod.invoke(world);

            if (worldMapManagerInstance == null) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[MapAwareChunkManager] WorldMapManager is null - map integration disabled"
                );
                return false;
            }

            Class<?> wmClass = worldMapManagerInstance.getClass();

            // Find getImageIfInMemory(long index) method
            try {
                getImageIfInMemoryMethod = wmClass.getMethod("getImageIfInMemory", long.class);
                plugin.getLogger().at(Level.INFO).log(
                    "[MapAwareChunkManager] Found getImageIfInMemory(long)"
                );
            } catch (NoSuchMethodException e) {
                // Try with int, int signature
                getImageIfInMemoryMethod = wmClass.getMethod("getImageIfInMemory", int.class, int.class);
                plugin.getLogger().at(Level.INFO).log(
                    "[MapAwareChunkManager] Found getImageIfInMemory(int, int)"
                );
            }

            // Find getImageAsync(long index) method
            try {
                getImageAsyncMethod = wmClass.getMethod("getImageAsync", long.class);
                plugin.getLogger().at(Level.INFO).log(
                    "[MapAwareChunkManager] Found getImageAsync(long)"
                );
            } catch (NoSuchMethodException e) {
                getImageAsyncMethod = wmClass.getMethod("getImageAsync", int.class, int.class);
                plugin.getLogger().at(Level.INFO).log(
                    "[MapAwareChunkManager] Found getImageAsync(int, int)"
                );
            }

            // Try to access the images cache directly for monitoring
            try {
                imagesField = wmClass.getDeclaredField("images");
                imagesField.setAccessible(true);
                plugin.getLogger().at(Level.INFO).log(
                    "[MapAwareChunkManager] Got access to images cache field"
                );
            } catch (NoSuchFieldException e) {
                plugin.getLogger().at(Level.INFO).log(
                    "[MapAwareChunkManager] Could not access images field directly, using methods only"
                );
            }

            plugin.getLogger().at(Level.INFO).log(
                "[MapAwareChunkManager] Successfully initialized with WorldMapManager integration!"
            );
            return true;

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[MapAwareChunkManager] Failed to initialize: " + e.getMessage()
            );
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Check if a chunk's map image is already cached.
     *
     * @param chunkIndex The chunk index (from ChunkUtil.indexChunk)
     * @return true if the map image is cached, false if it needs generation
     */
    public boolean isMapImageCached(long chunkIndex) {
        if (getImageIfInMemoryMethod == null || worldMapManagerInstance == null) {
            return true; // Assume safe if we can't check
        }

        try {
            Object result;
            if (getImageIfInMemoryMethod.getParameterCount() == 1) {
                result = getImageIfInMemoryMethod.invoke(worldMapManagerInstance, chunkIndex);
            } else {
                int x = ChunkUtil.xOfChunkIndex(chunkIndex);
                int z = ChunkUtil.zOfChunkIndex(chunkIndex);
                result = getImageIfInMemoryMethod.invoke(worldMapManagerInstance, x, z);
            }
            return result != null;
        } catch (Exception e) {
            plugin.getLogger().at(Level.FINE).log(
                "[MapAwareChunkManager] Error checking cache for chunk %d: %s",
                chunkIndex, e.getMessage()
            );
            return true; // Assume safe on error
        }
    }

    /**
     * Trigger async map image generation for a chunk.
     *
     * @param chunkIndex The chunk index
     * @return CompletableFuture that completes when the image is generated
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> ensureMapImageGenerated(long chunkIndex) {
        if (getImageAsyncMethod == null || worldMapManagerInstance == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Already pending?
        if (pendingMapGeneration.contains(chunkIndex)) {
            return CompletableFuture.completedFuture(null);
        }

        // Already cached?
        if (isMapImageCached(chunkIndex)) {
            chunksSkippedAlreadyCached++;
            safeToUnload.add(chunkIndex);
            return CompletableFuture.completedFuture(null);
        }

        pendingMapGeneration.add(chunkIndex);

        try {
            CompletableFuture<MapImage> future;
            if (getImageAsyncMethod.getParameterCount() == 1) {
                future = (CompletableFuture<MapImage>) getImageAsyncMethod.invoke(
                    worldMapManagerInstance, chunkIndex
                );
            } else {
                int x = ChunkUtil.xOfChunkIndex(chunkIndex);
                int z = ChunkUtil.zOfChunkIndex(chunkIndex);
                future = (CompletableFuture<MapImage>) getImageAsyncMethod.invoke(
                    worldMapManagerInstance, x, z
                );
            }

            return future.thenAccept(image -> {
                pendingMapGeneration.remove(chunkIndex);
                safeToUnload.add(chunkIndex);
                chunksPreRendered++;

                int x = ChunkUtil.xOfChunkIndex(chunkIndex);
                int z = ChunkUtil.zOfChunkIndex(chunkIndex);
                plugin.getLogger().at(Level.FINE).log(
                    "[MapAwareChunkManager] Pre-rendered map image for chunk (%d, %d)",
                    x, z
                );
            }).exceptionally(ex -> {
                pendingMapGeneration.remove(chunkIndex);
                mapGenerationErrors++;
                plugin.getLogger().at(Level.WARNING).log(
                    "[MapAwareChunkManager] Failed to generate map for chunk %d: %s",
                    chunkIndex, ex.getMessage()
                );
                return null;
            });

        } catch (Exception e) {
            pendingMapGeneration.remove(chunkIndex);
            mapGenerationErrors++;
            plugin.getLogger().at(Level.WARNING).log(
                "[MapAwareChunkManager] Error triggering map generation for chunk %d: %s",
                chunkIndex, e.getMessage()
            );
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Prepare a batch of chunks for unloading by ensuring their map images are generated.
     *
     * @param chunkIndexes Set of chunk indexes to prepare
     * @return CompletableFuture that completes when all maps are ready
     */
    public CompletableFuture<Void> prepareChunksForUnload(Set<Long> chunkIndexes) {
        if (chunkIndexes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        plugin.getLogger().at(Level.INFO).log(
            "[MapAwareChunkManager] Preparing %d chunks for unload (pre-rendering maps)...",
            chunkIndexes.size()
        );

        CompletableFuture<?>[] futures = chunkIndexes.stream()
            .map(this::ensureMapImageGenerated)
            .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures).thenRun(() -> {
            plugin.getLogger().at(Level.INFO).log(
                "[MapAwareChunkManager] Finished pre-rendering maps. Ready to unload %d chunks.",
                chunkIndexes.size()
            );
        });
    }

    /**
     * Check if a chunk is safe to unload (map already rendered or generation complete).
     */
    public boolean isChunkSafeToUnload(long chunkIndex) {
        // If we're still generating the map, not safe yet
        if (pendingMapGeneration.contains(chunkIndex)) {
            return false;
        }

        // If we've already marked it safe, it is
        if (safeToUnload.contains(chunkIndex)) {
            return true;
        }

        // Check the actual cache
        return isMapImageCached(chunkIndex);
    }

    /**
     * Get the current map image cache size (if accessible).
     */
    public int getMapCacheSize() {
        if (imagesField == null || worldMapManagerInstance == null) {
            return -1;
        }

        try {
            Object images = imagesField.get(worldMapManagerInstance);
            if (images instanceof Map) {
                return ((Map<?, ?>) images).size();
            }
            // Try size() method
            Method sizeMethod = images.getClass().getMethod("size");
            return (int) sizeMethod.invoke(images);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get status for admin command.
     */
    public String getStatus() {
        int cacheSize = getMapCacheSize();
        return String.format(
            "MapAwareChunkManager Status:\n" +
            "  WorldMapManager Connected: %s\n" +
            "  Map Cache Size: %s\n" +
            "  Pending Map Generation: %d chunks\n" +
            "  Chunks Pre-Rendered: %d\n" +
            "  Chunks Already Cached: %d\n" +
            "  Map Generation Errors: %d\n" +
            "  Safe To Unload Queue: %d chunks",
            worldMapManagerInstance != null,
            cacheSize >= 0 ? String.valueOf(cacheSize) : "unknown",
            pendingMapGeneration.size(),
            chunksPreRendered,
            chunksSkippedAlreadyCached,
            mapGenerationErrors,
            safeToUnload.size()
        );
    }

    /**
     * Clear the safe-to-unload tracking (after actual unload).
     */
    public void clearSafeToUnloadTracking() {
        safeToUnload.clear();
    }

    /**
     * Shutdown the manager.
     */
    public void shutdown() {
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
            "[MapAwareChunkManager] Shutdown. Stats: pre-rendered=%d, already-cached=%d, errors=%d",
            chunksPreRendered, chunksSkippedAlreadyCached, mapGenerationErrors
        );
    }
}
