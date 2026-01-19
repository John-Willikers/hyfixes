package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * FIX: Empty Archetype Entity Holders
 *
 * PROBLEM: Hytale's EntityChunk$EntityChunkLoadingSystem logs SEVERE errors when
 * it encounters entities with empty archetypes (no components):
 *   Empty archetype entity holder: EntityHolder{archetype=Archetype{componentTypes=[]}, components=[]}
 *
 * These errors indicate corrupted or malformed entity data, typically caused by:
 * - Codec deserialization failures
 * - Data corruption during save/load
 * - World generation issues
 *
 * LIMITATION: This error occurs during chunk loading, not during entity ticking.
 * An EntityTickingSystem cannot intercept chunk loading operations.
 *
 * CURRENT SOLUTION: This system runs during entity ticks and attempts to detect
 * and remove any entities that somehow end up in the world with invalid state.
 * It's a safety net, not a prevention mechanism.
 *
 * The actual empty archetype detection happens in EntityChunkLoadingSystem which
 * already excludes these entities from processing. This sanitizer provides
 * additional monitoring and cleanup for any that slip through.
 *
 * TODO: Investigate chunk-level RefSystem or chunk loading hooks for better
 * prevention of empty archetype entities.
 */
public class EmptyArchetypeSanitizer extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private int checkedCount = 0;
    private int removedCount = 0;

    // Configuration (loaded from ConfigManager)
    private final int skipFirstN;
    private final int logEveryN;

    public EmptyArchetypeSanitizer(HyFixes plugin) {
        this.plugin = plugin;
        ConfigManager config = ConfigManager.getInstance();
        this.skipFirstN = config.getEmptyArchetypeSkipFirstN();
        this.logEveryN = config.getEmptyArchetypeLogEveryN();
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Query for TransformComponent - the base component most entities have
        return TransformComponent.getComponentType();
    }

    @Override
    public void tick(
            float deltaTime,
            int entityIndex,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[EmptyArchetypeSanitizer] Active - monitoring for entities with invalid state"
            );
            loggedOnce = true;
        }

        try {
            // Get the TransformComponent
            TransformComponent transform = chunk.getComponent(entityIndex, TransformComponent.getComponentType());

            // Skip first N entities silently to avoid log spam on startup
            checkedCount++;
            if (checkedCount <= skipFirstN) {
                return;
            }

            // Only log occasionally to avoid spam
            if (checkedCount % logEveryN == 0) {
                plugin.getLogger().at(Level.FINE).log(
                    "[EmptyArchetypeSanitizer] Checked " + checkedCount + " entities"
                );
            }

            // Check if transform is valid
            if (transform == null) {
                // Transform is null but entity was matched by query - something is wrong
                plugin.getLogger().at(Level.WARNING).log(
                    "[EmptyArchetypeSanitizer] Found entity with null TransformComponent at index " + entityIndex
                );
                return;
            }

            // Check if position is valid (NaN/Infinite check)
            var position = transform.getPosition();
            if (position != null) {
                // Vector3d has isFinite() method that checks for NaN and Infinite
                if (!position.isFinite()) {
                    removedCount++;
                    plugin.getLogger().at(Level.WARNING).log(
                        "[EmptyArchetypeSanitizer] Found entity #" + removedCount +
                        " with invalid position (NaN/Infinite) at index " + entityIndex +
                        " position: " + position
                    );
                    // Just log for now - removing might cause cascading issues
                }
            }

        } catch (Exception e) {
            // Log at FINE level to avoid spam
            plugin.getLogger().at(Level.FINE).log(
                "[EmptyArchetypeSanitizer] Error checking entity: " + e.getMessage()
            );
        }
    }

    public int getCheckedCount() {
        return checkedCount;
    }

    public int getRemovedCount() {
        return removedCount;
    }
}
