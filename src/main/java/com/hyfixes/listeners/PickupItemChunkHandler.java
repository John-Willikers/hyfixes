package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * BACKUP FIX: Pickup Item Chunk Unload Handler
 *
 * PROBLEM: When a player teleports away and their chunk unloads, any
 * PickupItemComponent with a targetRef pointing to that player may crash
 * the world thread if the PickupItemSanitizer doesn't catch it in time.
 *
 * This RefSystem acts as a BACKUP to catch entity removal events,
 * particularly during chunk unloads, and ensure null targetRefs are
 * handled before the crash can occur.
 *
 * NOTE: This complements the existing PickupItemSanitizer (EntityTickingSystem)
 * by providing event-driven protection in addition to tick-based protection.
 *
 * The original PickupItemSanitizer remains the primary fix - this is a
 * safety net for edge cases where tick timing allows crashes to slip through.
 */
public class PickupItemChunkHandler extends RefSystem<EntityStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private int fixedCount = 0;

    public PickupItemChunkHandler(HyFixes plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PickupItemComponent.getComponentType();
    }

    @Override
    public void onEntityAdded(
            Ref<EntityStore> ref,
            AddReason reason,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        // No action needed when pickup items are added
    }

    @Override
    public void onEntityRemove(
            Ref<EntityStore> ref,
            RemoveReason reason,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[PickupItemChunkHandler] Active - monitoring pickup item lifecycle events"
            );
            loggedOnce = true;
        }

        // We want to catch ALL removals, but especially UNLOAD events
        // The crash can happen during any removal if timing is bad

        try {
            // Validate the ref first
            if (ref == null || !ref.isValid()) {
                return;
            }

            // Get the pickup item component
            PickupItemComponent pickupComponent = store.getComponent(ref, PickupItemComponent.getComponentType());
            if (pickupComponent == null) {
                return;
            }

            // If already finished, nothing to do
            if (pickupComponent.hasFinished()) {
                return;
            }

            // Check for null targetRef
            Ref<EntityStore> targetRef = pickupComponent.getTargetRef();

            if (targetRef == null) {
                // Mark as finished to prevent crash during removal cascade
                pickupComponent.setFinished(true);
                fixedCount++;

                String reasonStr = reason != null ? reason.name() : "UNKNOWN";
                plugin.getLogger().at(Level.WARNING).log(
                    "[PickupItemChunkHandler] Prevented crash #" + fixedCount +
                    " - fixed null targetRef during " + reasonStr +
                    " (backup protection triggered)"
                );
            } else if (!targetRef.isValid()) {
                // Target ref exists but is invalid - also mark finished
                pickupComponent.setFinished(true);
                fixedCount++;

                String reasonStr = reason != null ? reason.name() : "UNKNOWN";
                plugin.getLogger().at(Level.WARNING).log(
                    "[PickupItemChunkHandler] Prevented crash #" + fixedCount +
                    " - fixed invalid targetRef during " + reasonStr +
                    " (backup protection triggered)"
                );
            }

        } catch (Exception e) {
            // Log but don't crash - we're trying to prevent crashes after all
            plugin.getLogger().at(Level.FINE).log(
                "[PickupItemChunkHandler] Error during removal handling: " + e.getMessage()
            );
        }
    }

    /**
     * Get the count of fixed issues by this handler.
     */
    public int getFixedCount() {
        return fixedCount;
    }

    /**
     * Get status for admin commands.
     */
    public String getStatus() {
        return String.format(
            "PickupItemChunkHandler Status (Backup):\n" +
            "  Active: true\n" +
            "  Issues Fixed: %d",
            fixedCount
        );
    }
}
