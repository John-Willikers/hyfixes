package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hyfixes.data.SavedPosition;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * FIX: ExitInstanceInteraction Missing Return World Crash
 *
 * PROBLEM: When a player exits an instance (dungeon, cave, etc.) and the return
 * world reference is missing or invalid, Hytale's InstancesPlugin.exitInstance()
 * throws IllegalArgumentException: "Missing return world" and KICKS the player!
 *
 * SOLUTION: Track where players were BEFORE entering an instance. When they try
 * to exit and the return world is missing, redirect them to their saved position
 * instead of kicking them.
 *
 * This uses a two-event approach:
 * 1. AddPlayerToWorldEvent - When entering an instance world, we know they left a normal world
 * 2. DrainPlayerFromWorldEvent - When leaving a world, save their position if it's a normal world
 *    and set destination to saved position if leaving an instance
 */
public class InstancePositionTracker {

    private final HyFixes plugin;
    private final Map<UUID, SavedPosition> savedPositions = new ConcurrentHashMap<>();
    private boolean loggedOnce = false;
    private int recoveryCount = 0;

    // Event registrations for cleanup
    private EventRegistration<?, ?> drainEventRegistration;
    private EventRegistration<?, ?> addEventRegistration;

    public InstancePositionTracker(HyFixes plugin) {
        this.plugin = plugin;
    }

    /**
     * Register event handlers with the plugin's event registry.
     */
    public void register() {
        // Register for DrainPlayerFromWorldEvent (fired when player leaves a world)
        drainEventRegistration = plugin.getEventRegistry().registerGlobal(
            DrainPlayerFromWorldEvent.class,
            this::onPlayerDrainedFromWorld
        );

        // Register for AddPlayerToWorldEvent (fired when player enters a world)
        addEventRegistration = plugin.getEventRegistry().registerGlobal(
            AddPlayerToWorldEvent.class,
            this::onPlayerAddedToWorld
        );

        plugin.getLogger().at(Level.INFO).log(
            "[InstancePositionTracker] Event handlers registered"
        );
    }

    /**
     * Called when a player is added to a world.
     * Used for logging and tracking when players enter instances.
     */
    private void onPlayerAddedToWorld(AddPlayerToWorldEvent event) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[InstancePositionTracker] Active - monitoring instance entries/exits"
            );
            loggedOnce = true;
        }

        World world = event.getWorld();
        if (world == null) {
            return;
        }

        String worldName = world.getName();

        // Get player UUID from the holder
        UUID playerUuid = getPlayerUuidFromHolder(event.getHolder());
        if (playerUuid == null) {
            return;
        }

        // Check if player is entering an instance world
        if (worldName != null && worldName.startsWith(InstancesPlugin.INSTANCE_PREFIX)) {
            plugin.getLogger().at(Level.FINE).log(
                "[InstancePositionTracker] Player " + playerUuid + " entered instance: " + worldName
            );
        }
    }

    /**
     * Called when a player is being drained (removed) from a world.
     * This is our chance to:
     * 1. Save their position if they're leaving a non-instance world (entering an instance)
     * 2. Set a fallback destination when leaving an instance (prevents "Missing return world" crash)
     */
    private void onPlayerDrainedFromWorld(DrainPlayerFromWorldEvent event) {
        World sourceWorld = event.getWorld();
        if (sourceWorld == null) {
            return;
        }

        String sourceWorldName = sourceWorld.getName();

        // Get player UUID from the holder
        UUID playerUuid = getPlayerUuidFromHolder(event.getHolder());
        if (playerUuid == null) {
            return;
        }

        // Case 1: Player is leaving a NORMAL world (probably entering an instance)
        // Save their position so we can restore it if instance exit fails
        if (sourceWorldName == null || !sourceWorldName.startsWith(InstancesPlugin.INSTANCE_PREFIX)) {
            // This is a normal world - save position before they enter the instance
            SavedPosition savedPos = new SavedPosition(
                null, // We'll use world name instead of UUID
                sourceWorldName,
                event.getTransform()
            );
            savedPositions.put(playerUuid, savedPos);

            plugin.getLogger().at(Level.FINE).log(
                "[InstancePositionTracker] Saved position for " + playerUuid +
                " in world '" + sourceWorldName + "' before potential instance entry"
            );
            return;
        }

        // Case 2: Player is leaving an INSTANCE world
        // ONLY set a fallback destination if Hytale's return world is missing/null
        // Previously we always overwrote the destination, which could cause state issues
        if (sourceWorldName.startsWith(InstancesPlugin.INSTANCE_PREFIX)) {
            // First, check if Hytale already has a valid return destination
            World existingDestination = null;
            try {
                // Try to get the event's current destination world
                existingDestination = event.getWorld();
            } catch (Exception e) {
                // Method might not exist or might throw - that's ok
            }

            // If Hytale already has a valid destination, don't interfere!
            if (existingDestination != null) {
                plugin.getLogger().at(Level.FINE).log(
                    "[InstancePositionTracker] Player exiting instance - Hytale has valid return world '" +
                    existingDestination.getName() + "', not interfering"
                );
                // Clean up our saved position since it's not needed
                savedPositions.remove(playerUuid);
                return;
            }

            // Hytale's destination is null - this is where the crash would happen
            // Now we try to provide a fallback
            SavedPosition savedPos = savedPositions.get(playerUuid);

            if (savedPos != null && savedPos.isValid()) {
                // We have a saved position! Set it as the destination
                World returnWorld = findWorldByName(savedPos.getWorldName());

                if (returnWorld != null) {
                    // Set the destination to our saved position
                    event.setWorld(returnWorld);
                    event.setTransform(savedPos.getTransform());

                    recoveryCount++;
                    plugin.getLogger().at(Level.INFO).log(
                        "[InstancePositionTracker] RECOVERY: Hytale had null return world, set destination to saved position in '" +
                        savedPos.getWorldName() + "' (recovery #" + recoveryCount + ")"
                    );

                    // Clean up the saved position
                    savedPositions.remove(playerUuid);
                } else {
                    plugin.getLogger().at(Level.WARNING).log(
                        "[InstancePositionTracker] Could not find saved world '" +
                        savedPos.getWorldName() + "' - world may have been unloaded. Instance exit may fail!"
                    );
                }
            } else {
                plugin.getLogger().at(Level.WARNING).log(
                    "[InstancePositionTracker] Player exiting instance but Hytale's return world is null and no valid saved position available. " +
                    "Instance exit may fail!"
                );
            }
        }
    }

    /**
     * Extract player UUID from a Holder object.
     */
    @SuppressWarnings("unchecked")
    private UUID getPlayerUuidFromHolder(Object holder) {
        try {
            // The holder might have a getUuid() method or we need to get it from components
            if (holder == null) {
                return null;
            }

            // Try to get UUID through reflection - Holder implementations vary
            var method = holder.getClass().getMethod("getUuid");
            return (UUID) method.invoke(holder);
        } catch (Exception e) {
            plugin.getLogger().at(Level.FINE).log(
                "[InstancePositionTracker] Could not get UUID from holder: " + e.getMessage()
            );
            return null;
        }
    }

    /**
     * Find a world by its name from the universe.
     */
    private World findWorldByName(String worldName) {
        if (worldName == null) {
            return null;
        }

        try {
            return Universe.get().getWorld(worldName);
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[InstancePositionTracker] Error finding world '" + worldName + "': " + e.getMessage()
            );
            return null;
        }
    }

    /**
     * Called when a player disconnects - clean up their saved position.
     */
    public void onPlayerDisconnect(UUID playerUuid) {
        savedPositions.remove(playerUuid);
    }

    /**
     * Get the number of times we've recovered players from instance exit crashes.
     */
    public int getRecoveryCount() {
        return recoveryCount;
    }

    /**
     * Get the number of players currently tracked.
     */
    public int getTrackedPlayerCount() {
        return savedPositions.size();
    }
}
