package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hyfixes.systems.ChunkProtectionRegistry;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.logging.Level;

/**
 * Listens for bed/respawn block creation and updates chunk protection.
 *
 * When a bed is placed, protects that chunk from unloading.
 * This ensures player spawn points don't get nuked by chunk cleanup.
 */
public class RespawnBlockProtectionListener extends RefSystem<ChunkStore> {

    private final HyFixes plugin;
    private final ChunkProtectionRegistry registry;

    private boolean loggedOnce = false;

    private int bedsAdded = 0;
    private int bedsRemoved = 0;
    private int chunksMarkedKeepLoaded = 0;

    public RespawnBlockProtectionListener(HyFixes plugin, ChunkProtectionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return RespawnBlock.getComponentType();
    }

    @Override
    public void onEntityAdded(
            Ref<ChunkStore> ref,
            AddReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Active - monitoring bed/respawn block creation"
            );
            loggedOnce = true;
        }

        try {
            // Get the respawn block component
            RespawnBlock respawnBlock = store.getComponent(ref, RespawnBlock.getComponentType());
            if (respawnBlock == null) {
                return;
            }

            bedsAdded++;

            // Get chunk index from the WorldChunk component
            WorldChunk worldChunk = store.getComponent(ref, WorldChunk.getComponentType());
            long chunkIndex = worldChunk != null ? worldChunk.getIndex() : (0xBED00000L | (bedsAdded & 0xFFFF));

            // Get owner info for logging
            String ownerInfo = "unknown";
            try {
                java.util.UUID ownerUUID = respawnBlock.getOwnerUUID();
                if (ownerUUID != null) {
                    ownerInfo = ownerUUID.toString().substring(0, 8) + "...";
                }
            } catch (Exception ignored) {}

            String reason_str = "Bed/Respawn: " + ownerInfo;

            // Protect the chunk in our registry
            if (registry.protectChunk(chunkIndex, reason_str, System.currentTimeMillis() / 50)) {
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Protected chunk for bed: owner=%s (chunk 0x%X)",
                    ownerInfo, chunkIndex
                );
            }

            // CRITICAL: Set keepLoaded on the WorldChunk component!
            if (worldChunk != null && !worldChunk.shouldKeepLoaded()) {
                worldChunk.setKeepLoaded(true);
                chunksMarkedKeepLoaded++;
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Marked chunk as KEEP_LOADED for bed: owner=%s (chunk 0x%X)",
                    ownerInfo, chunkIndex
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[RespawnBlockProtection] Error on bed add: %s", e.getMessage()
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
        // Don't unprotect on chunk unload - only on actual bed destruction
        if (reason == RemoveReason.UNLOAD) {
            return;
        }

        try {
            RespawnBlock respawnBlock = store.getComponent(ref, RespawnBlock.getComponentType());
            if (respawnBlock == null) {
                return;
            }

            bedsRemoved++;

            String ownerInfo = "unknown";
            try {
                java.util.UUID ownerUUID = respawnBlock.getOwnerUUID();
                if (ownerUUID != null) {
                    ownerInfo = ownerUUID.toString().substring(0, 8) + "...";
                }
            } catch (Exception ignored) {}

            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Bed removed: owner=%s (reason: %s)",
                ownerInfo, reason
            );

            // Note: We don't unprotect immediately because there might be other
            // beds in the same chunk. The periodic scan will clean up stale protections.

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[RespawnBlockProtection] Error on bed remove: %s", e.getMessage()
            );
        }
    }

    public int getBedsAdded() {
        return bedsAdded;
    }

    public int getBedsRemoved() {
        return bedsRemoved;
    }

    public int getChunksMarkedKeepLoaded() {
        return chunksMarkedKeepLoaded;
    }

    public String getStatus() {
        int netBeds = bedsAdded - bedsRemoved;

        return String.format(
            "RespawnBlockProtectionListener Status:\n" +
            "  Beds added: %d\n" +
            "  Beds removed: %d\n" +
            "  Net active: %d\n" +
            "  Chunks marked KEEP_LOADED: %d",
            bedsAdded,
            bedsRemoved,
            netBeds,
            chunksMarkedKeepLoaded
        );
    }
}
