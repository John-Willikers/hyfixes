package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * FIX: Pickup Item Null TargetRef World Crash
 *
 * PROBLEM: Hytale's PickupItemSystem.tick() at line 69 calls:
 *   getTargetRef().isValid()
 * without null-checking getTargetRef() first.
 *
 * When a player disconnects or an entity despawns while an item is being
 * picked up, targetRef becomes null and crashes the entire world thread,
 * disconnecting ALL players in that world.
 *
 * Error: java.lang.NullPointerException: Cannot invoke
 *        "com.hypixel.hytale.component.Ref.isValid()" because "targetRef" is null
 *        at com.hypixel.hytale.server.core.modules.entity.item.PickupItemSystem.tick(PickupItemSystem.java:69)
 *
 * SOLUTION: This system runs each tick and marks any PickupItemComponent with
 * a null targetRef as "finished". The PickupItemSystem will then safely remove
 * the entity instead of crashing.
 */
public class PickupItemSanitizer extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private int fixedCount = 0;

    public PickupItemSanitizer(HyFixes plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PickupItemComponent.getComponentType();
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
            plugin.getLogger().at(Level.INFO).log("[PickupItemSanitizer] Active - monitoring for corrupted pickup items");
            loggedOnce = true;
        }

        try {
            PickupItemComponent pickupComponent = chunk.getComponent(
                    entityIndex,
                    PickupItemComponent.getComponentType()
            );

            if (pickupComponent == null || pickupComponent.hasFinished()) {
                return;
            }

            // THE FIX: Check for null targetRef before PickupItemSystem crashes
            Ref<EntityStore> targetRef = pickupComponent.getTargetRef();

            if (targetRef == null) {
                pickupComponent.setFinished(true);
                fixedCount++;

                plugin.getLogger().at(Level.WARNING).log(
                    "[PickupItemSanitizer] Prevented world crash #" + fixedCount +
                    " - fixed corrupted pickup item with null targetRef"
                );
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[PickupItemSanitizer] Error during sanitization: " + e.getMessage()
            );
        }
    }
}
