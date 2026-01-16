package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.logging.Level;

/**
 * FIX: RespawnBlock Null RespawnPoints Crash
 *
 * PROBLEM: Hytale's RespawnBlock$OnRemove.onEntityRemove() at line 106 iterates:
 *   for (int i = 0; i < respawnPoints.length; i++)
 * without null-checking respawnPoints first.
 *
 * When a player breaks a respawn block (bed, sleeping bag, etc.) and the
 * PlayerWorldData.getRespawnPoints() returns null, the server crashes with:
 *   java.lang.NullPointerException: Cannot read the array length because "respawnPoints" is null
 *
 * This kicks the player from the server!
 *
 * SOLUTION: Create a RefSystem that hooks into the same RespawnBlock component
 * lifecycle. When a RespawnBlock is about to be removed, we validate/initialize
 * the owner's respawnPoints array BEFORE the crash-causing code runs.
 *
 * This system should be registered with a higher priority so it runs before
 * the built-in RespawnBlock$OnRemove system.
 */
public class RespawnBlockSanitizer extends RefSystem<ChunkStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private int fixedCount = 0;

    public RespawnBlockSanitizer(HyFixes plugin) {
        this.plugin = plugin;
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
        // We don't need to do anything when respawn blocks are added
    }

    @Override
    public void onEntityRemove(
            Ref<ChunkStore> ref,
            RemoveReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockSanitizer] Active - monitoring respawn block removals"
            );
            loggedOnce = true;
        }

        // Only process actual removals, not chunk unloads
        if (reason == RemoveReason.UNLOAD) {
            return;
        }

        try {
            // Get the RespawnBlock component
            RespawnBlock respawnBlock = store.getComponent(ref, RespawnBlock.getComponentType());
            if (respawnBlock == null) {
                return;
            }

            // Get the owner UUID
            UUID ownerUUID = respawnBlock.getOwnerUUID();
            if (ownerUUID == null) {
                return;
            }

            // Get the player reference
            PlayerRef playerRef = Universe.get().getPlayer(ownerUUID);
            if (playerRef == null || !playerRef.isValid()) {
                // Player is offline - they might still have null respawnPoints
                // but since they're not in the world, the crash might not occur
                // Log at FINE level for debugging
                plugin.getLogger().at(Level.FINE).log(
                    "[RespawnBlockSanitizer] Owner " + ownerUUID + " is offline, skipping"
                );
                return;
            }

            // Get the Player component from holder
            Holder<EntityStore> holder = playerRef.getHolder();
            if (holder == null) {
                return;
            }

            Player player = holder.getComponent(Player.getComponentType());
            if (player == null) {
                return;
            }

            // Get PlayerConfigData
            PlayerConfigData configData = player.getPlayerConfigData();
            if (configData == null) {
                return;
            }

            // Get the world name from the store's external data
            String worldName = getWorldName(store);
            if (worldName == null) {
                // Try to get it from the player's current world
                World world = Universe.get().getWorld(playerRef.getWorldUuid());
                if (world != null) {
                    worldName = world.getName();
                }
            }

            if (worldName == null) {
                plugin.getLogger().at(Level.FINE).log(
                    "[RespawnBlockSanitizer] Could not determine world name, skipping"
                );
                return;
            }

            // Get PlayerWorldData for this world
            PlayerWorldData worldData = configData.getPerWorldData(worldName);
            if (worldData == null) {
                return;
            }

            // THE FIX: Check if respawnPoints is null and initialize it
            if (worldData.getRespawnPoints() == null) {
                worldData.setRespawnPoints(new PlayerRespawnPointData[0]);
                fixedCount++;

                plugin.getLogger().at(Level.WARNING).log(
                    "[RespawnBlockSanitizer] Prevented crash #" + fixedCount +
                    " - initialized null respawnPoints for player " + playerRef.getUsername() +
                    " in world " + worldName
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[RespawnBlockSanitizer] Error during sanitization: " + e.getMessage()
            );
        }
    }

    /**
     * Try to get the world name from the chunk store
     */
    private String getWorldName(Store<ChunkStore> store) {
        try {
            // The store might have external data that includes the world reference
            var externalData = store.getExternalData();
            if (externalData != null) {
                // Try to get World from external data
                // This depends on ChunkStore's external data type
                // For now, return null and fall back to player's world
            }
        } catch (Exception e) {
            // Ignore and return null
        }
        return null;
    }

    public int getFixedCount() {
        return fixedCount;
    }
}
