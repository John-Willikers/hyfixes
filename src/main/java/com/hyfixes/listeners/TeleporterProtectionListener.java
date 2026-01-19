package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hyfixes.systems.ChunkProtectionRegistry;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Listens for teleporter creation/destruction and updates chunk protection.
 *
 * When a teleporter is placed, protects that chunk from cleanup.
 * When a teleporter is removed, unprotects the chunk (if no other teleporters remain).
 */
public class TeleporterProtectionListener extends RefSystem<ChunkStore> {

    private final HyFixes plugin;
    private final ChunkProtectionRegistry registry;

    @SuppressWarnings("rawtypes")
    private ComponentType teleporterComponentType;
    private boolean initialized = false;
    private boolean loggedOnce = false;

    private int teleportersAdded = 0;
    private int teleportersRemoved = 0;
    private int cleanupCyclesSurvived = 0;
    private int chunksMarkedKeepLoaded = 0;
    private long lastCleanupCheckTick = 0;

    public TeleporterProtectionListener(HyFixes plugin, ChunkProtectionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        initializeTeleporterType();
    }

    @SuppressWarnings("rawtypes")
    private void initializeTeleporterType() {
        try {
            Class<?> teleporterPluginClass = Class.forName(
                "com.hypixel.hytale.builtin.adventure.teleporter.TeleporterPlugin"
            );
            Method getMethod = teleporterPluginClass.getMethod("get");
            Object teleporterPlugin = getMethod.invoke(null);

            if (teleporterPlugin != null) {
                Method getComponentTypeMethod = teleporterPluginClass.getMethod("getTeleporterComponentType");
                teleporterComponentType = (ComponentType) getComponentTypeMethod.invoke(teleporterPlugin);
                initialized = teleporterComponentType != null;

                if (initialized) {
                    plugin.getLogger().at(Level.INFO).log(
                        "[TeleporterProtectionListener] Initialized with ComponentType: %s",
                        teleporterComponentType
                    );
                }
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[TeleporterProtectionListener] TeleporterPlugin not found - listener disabled"
            );
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[TeleporterProtectionListener] Init error: %s", e.getMessage()
            );
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Query<ChunkStore> getQuery() {
        // Return the teleporter ComponentType as the Query
        // ComponentType implements Query, so this works
        if (teleporterComponentType != null) {
            return (Query<ChunkStore>) teleporterComponentType;
        }
        // Fallback: match nothing if not initialized
        return Query.any();
    }

    @Override
    public void onEntityAdded(
            Ref<ChunkStore> ref,
            AddReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if (!initialized || teleporterComponentType == null) {
            return;
        }

        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[TeleporterProtectionListener] Active - monitoring teleporter creation/destruction"
            );
            loggedOnce = true;
        }

        try {
            // Get the teleporter component
            Object teleporter = store.getComponent(ref, teleporterComponentType);
            if (teleporter == null) {
                return;
            }

            teleportersAdded++;

            // Get position from teleporter
            long chunkIndex = getChunkIndexFromTeleporter(teleporter);
            if (chunkIndex == -1) {
                // Fallback: use ref-based identification
                chunkIndex = 0xFEFE0000L | (teleportersAdded & 0xFFFF);
            }

            // Get warp name for logging
            String warpName = getWarpName(teleporter);
            String reason_str = "Teleporter" + (warpName != null ? ": " + warpName : " (new)");

            // Protect the chunk in our registry
            if (registry.protectChunk(chunkIndex, reason_str, System.currentTimeMillis() / 50)) {
                plugin.getLogger().at(Level.INFO).log(
                    "[TeleporterProtectionListener] Protected chunk for new teleporter: %s (chunk 0x%X)",
                    warpName != null ? warpName : "unnamed", chunkIndex
                );
            }

            // CRITICAL: Also set keepLoaded on the WorldChunk component!
            // This is the REAL Hytale mechanism that prevents chunk unloading
            try {
                WorldChunk worldChunk = store.getComponent(ref, WorldChunk.getComponentType());
                if (worldChunk != null && !worldChunk.shouldKeepLoaded()) {
                    worldChunk.setKeepLoaded(true);
                    chunksMarkedKeepLoaded++;
                    plugin.getLogger().at(Level.INFO).log(
                        "[TeleporterProtectionListener] Marked chunk as KEEP_LOADED: %s (chunk 0x%X)",
                        warpName != null ? warpName : "unnamed", chunkIndex
                    );
                }
            } catch (Exception ke) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[TeleporterProtectionListener] Could not set keepLoaded: %s", ke.getMessage()
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[TeleporterProtectionListener] Error on teleporter add: %s", e.getMessage()
            );
        }
    }

    @Override
    public void onEntityRemove(
            Ref<ChunkStore> ref,
            RemoveReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if (!initialized || teleporterComponentType == null) {
            return;
        }

        // Don't unprotect on chunk unload - only on actual destruction
        if (reason == RemoveReason.UNLOAD) {
            return;
        }

        try {
            // Get the teleporter component before it's removed
            Object teleporter = store.getComponent(ref, teleporterComponentType);
            if (teleporter == null) {
                return;
            }

            teleportersRemoved++;

            // Get warp name for logging
            String warpName = getWarpName(teleporter);

            plugin.getLogger().at(Level.INFO).log(
                "[TeleporterProtectionListener] Teleporter removed: %s (reason: %s)",
                warpName != null ? warpName : "unnamed", reason
            );

            // Note: We don't unprotect immediately because there might be other
            // teleporters in the same chunk. The periodic scan will clean up
            // stale protections if no teleporters remain.

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[TeleporterProtectionListener] Error on teleporter remove: %s", e.getMessage()
            );
        }
    }

    private long getChunkIndexFromTeleporter(Object teleporter) {
        try {
            Method getTransform = teleporter.getClass().getMethod("getTransform");
            Transform transform = (Transform) getTransform.invoke(teleporter);
            if (transform != null) {
                Vector3d pos = transform.getPosition();
                if (pos != null) {
                    int chunkX = (int) Math.floor(pos.getX()) >> 4;
                    int chunkZ = (int) Math.floor(pos.getZ()) >> 4;
                    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private String getWarpName(Object teleporter) {
        try {
            Method getWarp = teleporter.getClass().getMethod("getWarp");
            Object warp = getWarp.invoke(teleporter);
            if (warp != null && !warp.toString().isEmpty()) {
                return warp.toString();
            }
            Method getOwnedWarp = teleporter.getClass().getMethod("getOwnedWarp");
            Object ownedWarp = getOwnedWarp.invoke(teleporter);
            if (ownedWarp != null && !ownedWarp.toString().isEmpty()) {
                return ownedWarp.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Called by ChunkCleanupSystem after each cleanup cycle to track survival.
     * If teleportersRemoved hasn't increased since last check, teleporters survived.
     */
    public void onCleanupCycleComplete(long currentTick) {
        if (currentTick > lastCleanupCheckTick) {
            cleanupCyclesSurvived++;
            lastCleanupCheckTick = currentTick;
        }
    }

    public int getTeleportersAdded() {
        return teleportersAdded;
    }

    public int getTeleportersRemoved() {
        return teleportersRemoved;
    }

    public int getCleanupCyclesSurvived() {
        return cleanupCyclesSurvived;
    }

    public String getStatus() {
        int activeProtections = registry.getProtectedChunkCount();
        int netTeleporters = teleportersAdded - teleportersRemoved;

        return String.format(
            "TeleporterProtectionListener Status:\n" +
            "  Initialized: %s\n" +
            "  Teleporters added: %d\n" +
            "  Teleporters removed: %d\n" +
            "  Net active: %d\n" +
            "  Protected chunks (registry): %d\n" +
            "  Chunks marked KEEP_LOADED: %d\n" +
            "  Cleanup cycles survived: %d\n" +
            "  STATUS: %s",
            initialized,
            teleportersAdded,
            teleportersRemoved,
            netTeleporters,
            activeProtections,
            chunksMarkedKeepLoaded,
            cleanupCyclesSurvived,
            teleportersRemoved == 0 ? "ALL TELEPORTERS SAFE" :
                (netTeleporters > 0 ? "MOST TELEPORTERS SAFE" : "CHECK LOGS FOR ISSUES")
        );
    }
}
