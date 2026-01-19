package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.logging.Level;

/**
 * ChunkProtectionScanner - Scans chunks for protected content.
 *
 * Uses the Hytale TeleporterPlugin API to detect teleporters/portals in the ChunkStore.
 * Teleporters are stored as Components in ChunkStore, not EntityStore.
 *
 * Key API calls:
 * - TeleporterPlugin.get().getTeleporterComponentType() - get the teleporter component type
 * - world.getChunkStore().getStore().forEachChunk(componentType, ...) - iterate chunks with teleporters
 * - Teleporter.getTransform().getPosition() - get position of a teleporter
 */
public class ChunkProtectionScanner {

    private final HyFixes plugin;
    private final ChunkProtectionRegistry registry;

    // Cached reflection handles (initialized lazily)
    private volatile boolean reflectionInitialized = false;
    private volatile boolean teleporterPluginAvailable = false;

    // TeleporterPlugin reflection
    private Object teleporterPlugin;
    @SuppressWarnings("rawtypes")
    private ComponentType teleporterComponentType;
    private Method forEachChunkSimpleMethod;  // 1-param: forEachChunk(BiPredicate)
    private Method forEachChunkFilteredMethod; // 2-param: forEachChunk(ComponentType, BiConsumer)

    // Scan statistics
    private volatile int totalScans = 0;
    private volatile int teleportersScanned = 0;
    private volatile int protectedFound = 0;

    public ChunkProtectionScanner(HyFixes plugin, ChunkProtectionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * Scan a world for protected content and update the registry.
     *
     * @param world The world to scan
     * @param currentTick The current server tick (for protection timestamps)
     * @return The number of chunks newly protected
     */
    public int scanWorld(World world, long currentTick) {
        if (!ConfigManager.getInstance().isChunkProtectionEnabled()) {
            return 0;
        }

        if (world == null) {
            return 0;
        }

        totalScans++;
        int newlyProtected = 0;

        try {
            // Get the ChunkStore (NOT EntityStore - teleporters are in ChunkStore!)
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                return 0;
            }

            Store<ChunkStore> store = chunkStore.getStore();
            if (store == null) {
                return 0;
            }

            // Initialize reflection handles if needed
            if (!reflectionInitialized) {
                initializeReflection(store);
            }

            // Scan for teleporters if plugin is available
            if (teleporterPluginAvailable && teleporterComponentType != null) {
                newlyProtected += scanTeleporters(store, currentTick);
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] Scan error: %s", e.getMessage()
            );
        }

        return newlyProtected;
    }

    /**
     * Scan for teleporter components in the ChunkStore.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int scanTeleporters(Store<ChunkStore> store, long currentTick) {
        final int[] protectedCount = {0};
        final int[] scannedCount = {0};
        final int[] fallbackCount = {0};
        final int[] chunksScanned = {0};
        Set<Long> protectedChunks = new HashSet<>();

        try {
            // Consumer/handler for processing teleporter chunks
            java.util.function.BiConsumer<ArchetypeChunk<ChunkStore>, CommandBuffer<ChunkStore>> handler =
                (chunk, commandBuffer) -> {
                    try {
                        chunksScanned[0]++;
                        int chunkSize = chunk.size();

                        for (int i = 0; i < chunkSize; i++) {
                            Object teleporter = chunk.getComponent(i, teleporterComponentType);

                            if (teleporter != null) {
                                scannedCount[0]++;
                                teleportersScanned++;

                                // Try to get chunk index from teleporter's transform
                                long chunkIndex = getTeleporterChunkIndex(teleporter);

                                // Fallback: try to get from Ref
                                if (chunkIndex == -1) {
                                    chunkIndex = getChunkIndexFromRef(chunk, i);
                                }

                                // Last resort: dump teleporter info and use fallback
                                if (chunkIndex == -1) {
                                    // Log verbose info about this teleporter for debugging
                                    if (fallbackCount[0] < 3) {
                                        logTeleporterDebugInfo(teleporter, chunk, i);
                                    }
                                    chunkIndex = 0xDEAD0000L | (fallbackCount[0]++ & 0xFFFF);
                                    plugin.getLogger().at(Level.INFO).log(
                                        "[ChunkProtectionScanner] Using fallback chunk index 0x%X for teleporter #%d",
                                        chunkIndex, fallbackCount[0]
                                    );
                                }

                                if (!protectedChunks.contains(chunkIndex)) {
                                    String warpName = getTeleporterWarpName(teleporter);
                                    String reason = "Teleporter" + (warpName != null ? ": " + warpName : "");

                                    if (registry.protectChunk(chunkIndex, reason, currentTick)) {
                                        protectedCount[0]++;
                                        protectedChunks.add(chunkIndex);
                                        protectedFound++;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Skip this chunk on error
                    }
                };

            // PRIMARY: Use filtered 2-param method - ComponentType implements Query, so pass it directly
            if (forEachChunkFilteredMethod != null && teleporterComponentType != null) {
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] Using filtered forEachChunk with ComponentType (implements Query)"
                );
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] Method: %s", forEachChunkFilteredMethod.toGenericString()
                );
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] ComponentType: %s (class: %s)",
                    teleporterComponentType, teleporterComponentType.getClass().getName()
                );

                // ComponentType implements Query<ECS_TYPE>, so we can pass it directly
                forEachChunkFilteredMethod.invoke(store, teleporterComponentType, handler);

                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] Scanned %d archetype chunks, found %d teleporters",
                    chunksScanned[0], scannedCount[0]
                );
            }

            // FALLBACK: Use simple 1-param method if filtered didn't work or find teleporters
            if (scannedCount[0] == 0 && forEachChunkSimpleMethod != null) {
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] Using simple forEachChunk (fallback)"
                );
                BiPredicate<ArchetypeChunk<ChunkStore>, CommandBuffer<ChunkStore>> predicate =
                    (chunk, commandBuffer) -> {
                        handler.accept(chunk, commandBuffer);
                        return true; // Continue iterating
                    };
                forEachChunkSimpleMethod.invoke(store, predicate);

                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] Fallback scanned %d archetype chunks, found %d teleporters",
                    chunksScanned[0], scannedCount[0]
                );
            }

            if (scannedCount[0] == 0 && forEachChunkFilteredMethod == null && forEachChunkSimpleMethod == null) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkProtectionScanner] No forEachChunk method available!"
                );
            }

            if (ConfigManager.getInstance().logChunkProtectionEvents() && protectedCount[0] > 0) {
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] Found %d teleporters, protected %d new chunks",
                    scannedCount[0], protectedCount[0]
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] Teleporter scan error: %s", e.getMessage()
            );
        }

        return protectedCount[0];
    }

    /**
     * Try to get chunk index from the Ref in the ArchetypeChunk.
     * ChunkStore Refs might encode position information.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private long getChunkIndexFromRef(ArchetypeChunk<ChunkStore> chunk, int index) {
        try {
            // Get the Ref for this entity
            Ref<ChunkStore> ref = chunk.getReferenceTo(index);
            if (ref == null) {
                return -1;
            }

            // Try to get chunk position from the Ref
            // Refs often have an index or ID that encodes position

            // Try getIndex() method
            try {
                Method getIndex = ref.getClass().getMethod("getIndex");
                Object indexVal = getIndex.invoke(ref);
                if (indexVal instanceof Number) {
                    return ((Number) indexVal).longValue();
                }
            } catch (NoSuchMethodException ignored) {}

            // Try getChunkIndex() method
            try {
                Method getChunkIndex = ref.getClass().getMethod("getChunkIndex");
                Object chunkIdx = getChunkIndex.invoke(ref);
                if (chunkIdx instanceof Number) {
                    return ((Number) chunkIdx).longValue();
                }
            } catch (NoSuchMethodException ignored) {}

            // Try getId() method
            try {
                Method getId = ref.getClass().getMethod("getId");
                Object id = getId.invoke(ref);
                if (id instanceof Number) {
                    return ((Number) id).longValue();
                }
            } catch (NoSuchMethodException ignored) {}

            // Try to parse the Ref's toString for position info
            String refStr = ref.toString();
            // Look for patterns like "chunk=123" or "index=456"
            if (refStr.contains("chunk=")) {
                int start = refStr.indexOf("chunk=") + 6;
                int end = refStr.indexOf(",", start);
                if (end == -1) end = refStr.indexOf("]", start);
                if (end == -1) end = refStr.indexOf(")", start);
                if (end > start) {
                    try {
                        return Long.parseLong(refStr.substring(start, end).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

        } catch (Exception e) {
            // Failed to get chunk index from ref
        }
        return -1;
    }

    /**
     * Get the chunk index from a Teleporter component's position.
     * Tries multiple approaches to find position data.
     */
    private long getTeleporterChunkIndex(Object teleporter) {
        Vector3d pos = null;

        // Method 1: Try getTransform().getPosition()
        try {
            Method getTransform = teleporter.getClass().getMethod("getTransform");
            Transform transform = (Transform) getTransform.invoke(teleporter);
            if (transform != null) {
                pos = transform.getPosition();
            }
        } catch (Exception ignored) {}

        // Method 2: Try getPosition() directly
        if (pos == null) {
            try {
                Method getPosition = teleporter.getClass().getMethod("getPosition");
                Object result = getPosition.invoke(teleporter);
                if (result instanceof Vector3d) {
                    pos = (Vector3d) result;
                }
            } catch (Exception ignored) {}
        }

        // Method 3: Try getOrigin()
        if (pos == null) {
            try {
                Method getOrigin = teleporter.getClass().getMethod("getOrigin");
                Object result = getOrigin.invoke(teleporter);
                if (result instanceof Vector3d) {
                    pos = (Vector3d) result;
                } else if (result instanceof Transform) {
                    pos = ((Transform) result).getPosition();
                }
            } catch (Exception ignored) {}
        }

        // Method 4: Try getDestination() - might have location
        if (pos == null) {
            try {
                Method getDest = teleporter.getClass().getMethod("getDestination");
                Object dest = getDest.invoke(teleporter);
                if (dest != null) {
                    // Try to get position from destination
                    try {
                        Method destGetPos = dest.getClass().getMethod("getPosition");
                        Object destPos = destGetPos.invoke(dest);
                        if (destPos instanceof Vector3d) {
                            pos = (Vector3d) destPos;
                        }
                    } catch (Exception ignored) {}
                    // Try getTransform on destination
                    try {
                        Method destGetTransform = dest.getClass().getMethod("getTransform");
                        Transform destTransform = (Transform) destGetTransform.invoke(dest);
                        if (destTransform != null) {
                            pos = destTransform.getPosition();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        // Method 5: Try getWarp() and get location from warp
        if (pos == null) {
            try {
                Method getWarp = teleporter.getClass().getMethod("getWarp");
                Object warp = getWarp.invoke(teleporter);
                if (warp != null) {
                    // Try warp.getPosition()
                    try {
                        Method warpGetPos = warp.getClass().getMethod("getPosition");
                        Object warpPos = warpGetPos.invoke(warp);
                        if (warpPos instanceof Vector3d) {
                            pos = (Vector3d) warpPos;
                        }
                    } catch (Exception ignored) {}
                    // Try warp.getTransform()
                    try {
                        Method warpGetTransform = warp.getClass().getMethod("getTransform");
                        Transform warpTransform = (Transform) warpGetTransform.invoke(warp);
                        if (warpTransform != null) {
                            pos = warpTransform.getPosition();
                        }
                    } catch (Exception ignored) {}
                    // Try warp.getLocation()
                    try {
                        Method warpGetLoc = warp.getClass().getMethod("getLocation");
                        Object loc = warpGetLoc.invoke(warp);
                        if (loc instanceof Vector3d) {
                            pos = (Vector3d) loc;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        // Method 6: Try any field that might be a Vector3d position
        if (pos == null) {
            try {
                for (java.lang.reflect.Field field : teleporter.getClass().getDeclaredFields()) {
                    if (field.getType() == Vector3d.class ||
                        field.getName().toLowerCase().contains("pos") ||
                        field.getName().toLowerCase().contains("location")) {
                        field.setAccessible(true);
                        Object value = field.get(teleporter);
                        if (value instanceof Vector3d) {
                            pos = (Vector3d) value;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (pos != null) {
            // Convert to chunk coordinates
            int chunkX = (int) Math.floor(pos.getX()) >> 4;
            int chunkZ = (int) Math.floor(pos.getZ()) >> 4;
            return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        }

        return -1;
    }

    /**
     * Get the warp name from a Teleporter component.
     */
    private String getTeleporterWarpName(Object teleporter) {
        try {
            // Try getWarp() first
            Method getWarp = teleporter.getClass().getMethod("getWarp");
            Object warp = getWarp.invoke(teleporter);
            if (warp != null && !warp.toString().isEmpty()) {
                return warp.toString();
            }

            // Try getOwnedWarp()
            Method getOwnedWarp = teleporter.getClass().getMethod("getOwnedWarp");
            Object ownedWarp = getOwnedWarp.invoke(teleporter);
            if (ownedWarp != null && !ownedWarp.toString().isEmpty()) {
                return ownedWarp.toString();
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Scan a specific position for protected blocks.
     */
    public boolean scanBlocksAtPosition(World world, int x, int y, int z, long currentTick) {
        if (!ConfigManager.getInstance().isChunkProtectionEnabled()) {
            return false;
        }

        try {
            int blockId = world.getBlock(x, y, z);
            if (blockId == 0) {
                return false;
            }

            BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
            String blockName = blockType != null ? blockType.getId() : "unknown";

            String[] blockKeywords = ConfigManager.getInstance().getProtectedBlockKeywords();
            if (matchesKeywords(blockName, blockKeywords)) {
                // Calculate chunk index
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                long chunkIndex = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

                return registry.protectChunk(chunkIndex, "Block: " + blockName, currentTick);
            }
        } catch (Exception e) {
            // Block check failed - ignore
        }

        return false;
    }

    /**
     * Check if a string matches any of the keywords (case-insensitive).
     */
    private boolean matchesKeywords(String value, String[] keywords) {
        if (value == null || keywords == null) {
            return false;
        }
        String lower = value.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Initialize reflection handles for TeleporterPlugin.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void initializeReflection(Store<ChunkStore> store) {
        try {
            // Find TeleporterPlugin class
            Class<?> teleporterPluginClass = Class.forName(
                "com.hypixel.hytale.builtin.adventure.teleporter.TeleporterPlugin"
            );

            // Get the singleton instance via TeleporterPlugin.get()
            Method getMethod = teleporterPluginClass.getMethod("get");
            teleporterPlugin = getMethod.invoke(null);

            if (teleporterPlugin != null) {
                // Get the teleporter component type via getTeleporterComponentType()
                Method getComponentTypeMethod = teleporterPluginClass.getMethod("getTeleporterComponentType");
                teleporterComponentType = (ComponentType) getComponentTypeMethod.invoke(teleporterPlugin);

                if (teleporterComponentType != null) {
                    teleporterPluginAvailable = true;

                    plugin.getLogger().at(Level.INFO).log(
                        "[ChunkProtectionScanner] TeleporterPlugin found! ComponentType: %s",
                        teleporterComponentType.getClass().getSimpleName()
                    );
                }
            }

            // Find ALL forEachChunk methods on Store and log them
            plugin.getLogger().at(Level.INFO).log("[ChunkProtectionScanner] Looking for forEachChunk methods...");
            for (Method m : store.getClass().getMethods()) {
                if (m.getName().equals("forEachChunk")) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    plugin.getLogger().at(Level.INFO).log(
                        "[ChunkProtectionScanner]   Found: %s (params=%d, first=%s)",
                        m.toGenericString(), m.getParameterCount(),
                        paramTypes.length > 0 ? paramTypes[0].getSimpleName() : "none"
                    );

                    if (m.getParameterCount() == 1) {
                        // 1-param version - prefer BiPredicate (returns boolean for control)
                        forEachChunkSimpleMethod = m;
                    } else if (m.getParameterCount() == 2) {
                        // Look for forEachChunk(Query, BiConsumer) specifically - void return
                        String firstParamName = paramTypes[0].getName();
                        if (firstParamName.contains("Query")) {
                            // Prefer the void/BiConsumer version over boolean/BiPredicate
                            if (m.getReturnType() == void.class || forEachChunkFilteredMethod == null) {
                                forEachChunkFilteredMethod = m;
                                plugin.getLogger().at(Level.INFO).log(
                                    "[ChunkProtectionScanner]   -> Using this as filtered method (Query-based, return=%s)",
                                    m.getReturnType().getSimpleName()
                                );
                            }
                        }
                    }
                }
            }

            reflectionInitialized = true;

            plugin.getLogger().at(Level.INFO).log(
                "[ChunkProtectionScanner] Reflection initialized. TeleporterPlugin: %s, filtered: %s, simple: %s",
                teleporterPluginAvailable, forEachChunkFilteredMethod != null, forEachChunkSimpleMethod != null
            );

        } catch (ClassNotFoundException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] TeleporterPlugin not found - teleporter protection disabled"
            );
            reflectionInitialized = true;
            teleporterPluginAvailable = false;
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] Reflection init error: %s", e.getMessage()
            );
            reflectionInitialized = true;
        }
    }

    /**
     * Get scan statistics.
     */
    public String getStatus() {
        return String.format(
            "Total scans: %d, Teleporters scanned: %d, Protected found: %d, TeleporterPlugin: %s",
            totalScans, teleportersScanned, protectedFound, teleporterPluginAvailable
        );
    }

    /**
     * Check if the teleporter plugin was found and is available.
     */
    public boolean isTeleporterPluginAvailable() {
        return teleporterPluginAvailable;
    }

    /**
     * Force re-initialization of reflection handles.
     */
    public void resetReflection() {
        reflectionInitialized = false;
        teleporterPluginAvailable = false;
        teleporterPlugin = null;
        teleporterComponentType = null;
        forEachChunkSimpleMethod = null;
        forEachChunkFilteredMethod = null;
    }

    /**
     * Log verbose debug info about a teleporter when we can't get its position.
     */
    @SuppressWarnings("rawtypes")
    private void logTeleporterDebugInfo(Object teleporter, ArchetypeChunk chunk, int index) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[ChunkProtectionScanner] DEBUG - Teleporter info:\n");
            sb.append("  Class: ").append(teleporter.getClass().getName()).append("\n");

            // List all methods
            sb.append("  Methods:\n");
            for (Method m : teleporter.getClass().getMethods()) {
                if (m.getDeclaringClass() != Object.class && m.getParameterCount() == 0) {
                    String returnType = m.getReturnType().getSimpleName();
                    sb.append("    - ").append(m.getName()).append("() -> ").append(returnType);
                    // Try to invoke and get value
                    try {
                        Object value = m.invoke(teleporter);
                        if (value != null) {
                            String valStr = value.toString();
                            if (valStr.length() > 50) valStr = valStr.substring(0, 50) + "...";
                            sb.append(" = ").append(valStr);
                        } else {
                            sb.append(" = null");
                        }
                    } catch (Exception e) {
                        sb.append(" = [error: ").append(e.getClass().getSimpleName()).append("]");
                    }
                    sb.append("\n");
                }
            }

            // List relevant fields
            sb.append("  Fields:\n");
            for (java.lang.reflect.Field f : teleporter.getClass().getDeclaredFields()) {
                sb.append("    - ").append(f.getName()).append(": ").append(f.getType().getSimpleName());
                try {
                    f.setAccessible(true);
                    Object value = f.get(teleporter);
                    if (value != null) {
                        String valStr = value.toString();
                        if (valStr.length() > 50) valStr = valStr.substring(0, 50) + "...";
                        sb.append(" = ").append(valStr);
                    } else {
                        sb.append(" = null");
                    }
                } catch (Exception e) {
                    sb.append(" = [error]");
                }
                sb.append("\n");
            }

            // Chunk info
            sb.append("  ArchetypeChunk info:\n");
            sb.append("    - size: ").append(chunk.size()).append("\n");
            sb.append("    - index in chunk: ").append(index).append("\n");
            try {
                Ref ref = chunk.getReferenceTo(index);
                if (ref != null) {
                    sb.append("    - Ref class: ").append(ref.getClass().getName()).append("\n");
                    sb.append("    - Ref toString: ").append(ref.toString()).append("\n");
                }
            } catch (Exception e) {
                sb.append("    - Ref: [error]\n");
            }

            plugin.getLogger().at(Level.INFO).log(sb.toString());

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] Failed to log debug info: %s", e.getMessage()
            );
        }
    }
}
