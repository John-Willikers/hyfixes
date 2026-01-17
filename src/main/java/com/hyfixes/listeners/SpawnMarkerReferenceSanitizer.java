package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * SpawnMarkerReferenceSanitizer - Prevents world crash when spawn markers have null npcReferences
 *
 * GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/5
 *
 * The Bug:
 * When a player enters a chunk with spawn markers that have corrupted state, the server can crash with:
 * java.lang.NullPointerException: Cannot read the array length because "<local15>" is null
 * at com.hypixel.hytale.server.npc.systems.SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove(SpawnReferenceSystems.java:166)
 *
 * This happens because SpawnMarkerEntity.getNpcReferences() returns null, and the
 * MarkerAddRemoveSystem.onEntityRemove() method tries to iterate over this null array.
 *
 * The Fix:
 * This sanitizer runs each tick on entities with SpawnMarkerEntity components.
 * It checks if npcReferences is null and sets it to an empty array before
 * the MarkerAddRemoveSystem can crash on it.
 */
public class SpawnMarkerReferenceSanitizer extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;

    // Discovered via reflection
    private Class<?> spawnMarkerEntityClass = null;
    private ComponentType spawnMarkerEntityType = null;
    private Method getNpcReferencesMethod = null;
    private Method setNpcReferencesMethod = null;
    private Class<?> invalidatablePersistentRefClass = null;

    private boolean initialized = false;
    private boolean apiDiscoveryFailed = false;

    // Statistics
    private final AtomicInteger entitiesChecked = new AtomicInteger(0);
    private final AtomicInteger nullArraysFixed = new AtomicInteger(0);
    private final AtomicInteger crashesPrevented = new AtomicInteger(0);

    public SpawnMarkerReferenceSanitizer(HyFixes plugin) {
        this.plugin = plugin;
        // Try early discovery so getQuery() works at registration time
        discoverApiEarly();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Query<EntityStore> getQuery() {
        if (spawnMarkerEntityType != null) {
            return spawnMarkerEntityType;
        }
        // Fallback - this will cause issues but at least won't NPE
        plugin.getLogger().at(Level.WARNING).log(
                "[SpawnMarkerReferenceSanitizer] Could not discover SpawnMarkerEntity component type - sanitizer disabled");
        return null;
    }

    @Override
    public void tick(
            float deltaTime,
            int index,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        if (!initialized && !apiDiscoveryFailed) {
            discoverApi();
        }

        if (apiDiscoveryFailed || spawnMarkerEntityType == null) {
            return;
        }

        try {
            entitiesChecked.incrementAndGet();

            // Get the SpawnMarkerEntity component
            Object spawnMarkerEntity = chunk.getComponent(index, spawnMarkerEntityType);
            if (spawnMarkerEntity == null) {
                return;
            }

            // Check if npcReferences is null
            Object npcReferences = getNpcReferencesMethod.invoke(spawnMarkerEntity);
            if (npcReferences == null) {
                // Create an empty array and set it
                Object emptyArray = Array.newInstance(invalidatablePersistentRefClass, 0);
                setNpcReferencesMethod.invoke(spawnMarkerEntity, emptyArray);

                nullArraysFixed.incrementAndGet();
                crashesPrevented.incrementAndGet();

                plugin.getLogger().at(Level.INFO).log(
                        "[SpawnMarkerReferenceSanitizer] Fixed null npcReferences array on SpawnMarkerEntity to prevent crash");
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.FINE).log(
                    "[SpawnMarkerReferenceSanitizer] Error during tick: " + e.getMessage());
        }
    }

    /**
     * Early discovery - called from constructor so getQuery() works at registration time
     */
    private void discoverApiEarly() {
        try {
            // Find SpawnMarkerEntity class and component type
            spawnMarkerEntityClass = Class.forName("com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity");
            Method getComponentTypeMethod = spawnMarkerEntityClass.getMethod("getComponentType");
            spawnMarkerEntityType = (ComponentType) getComponentTypeMethod.invoke(null);

            plugin.getLogger().at(Level.INFO).log(
                    "[SpawnMarkerReferenceSanitizer] Early API discovery successful - SpawnMarkerEntity type found");

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                    "[SpawnMarkerReferenceSanitizer] Early API discovery failed: " + e.getMessage());
            apiDiscoveryFailed = true;
        }
    }

    /**
     * Full API discovery - called on first tick
     */
    private void discoverApi() {
        try {
            plugin.getLogger().at(Level.INFO).log("[SpawnMarkerReferenceSanitizer] Discovering full API...");

            // Find InvalidatablePersistentRef class
            invalidatablePersistentRefClass = Class.forName(
                    "com.hypixel.hytale.server.core.entity.reference.InvalidatablePersistentRef");

            // Get getNpcReferences() method
            getNpcReferencesMethod = spawnMarkerEntityClass.getMethod("getNpcReferences");

            // Get setNpcReferences() method - takes array of InvalidatablePersistentRef
            Class<?> arrayClass = Array.newInstance(invalidatablePersistentRefClass, 0).getClass();
            setNpcReferencesMethod = spawnMarkerEntityClass.getMethod("setNpcReferences", arrayClass);

            initialized = true;
            plugin.getLogger().at(Level.INFO).log("[SpawnMarkerReferenceSanitizer] Full API discovery successful!");
            plugin.getLogger().at(Level.INFO).log("  - getNpcReferences: " + getNpcReferencesMethod);
            plugin.getLogger().at(Level.INFO).log("  - setNpcReferences: " + setNpcReferencesMethod);

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                    "[SpawnMarkerReferenceSanitizer] Full API discovery failed: " + e.getMessage());
            apiDiscoveryFailed = true;
        }
    }

    /**
     * Get status for the /interactionstatus command
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Initialized: ").append(initialized).append("\n");
        sb.append("API Discovery Failed: ").append(apiDiscoveryFailed).append("\n");
        sb.append("Entities Checked: ").append(entitiesChecked.get()).append("\n");
        sb.append("Null Arrays Fixed: ").append(nullArraysFixed.get()).append("\n");
        sb.append("Crashes Prevented: ").append(crashesPrevented.get());
        return sb.toString();
    }

    public int getCrashesPrevented() {
        return crashesPrevented.get();
    }
}
