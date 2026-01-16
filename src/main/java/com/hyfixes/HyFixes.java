package com.hyfixes;

import com.hyfixes.listeners.EmptyArchetypeSanitizer;
import com.hyfixes.listeners.InstancePositionTracker;
import com.hyfixes.listeners.PickupItemSanitizer;
import com.hyfixes.listeners.ProcessingBenchSanitizer;
import com.hyfixes.listeners.RespawnBlockSanitizer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * HyFixes - Bug fixes for Hytale Early Access
 *
 * This plugin contains workarounds for known Hytale server bugs
 * that may cause crashes or unexpected behavior.
 *
 * Current fixes:
 * - PickupItemSanitizer: Prevents world thread crash from corrupted pickup items (null targetRef)
 * - RespawnBlockSanitizer: Prevents crash when breaking respawn blocks with null respawnPoints
 * - ProcessingBenchSanitizer: Prevents crash when breaking processing benches with open windows
 * - EmptyArchetypeSanitizer: Monitors for entities with invalid state (empty archetypes)
 * - InstancePositionTracker: Prevents kick when exiting instances with missing return world
 */
public class HyFixes extends JavaPlugin {

    private static HyFixes instance;
    private InstancePositionTracker instancePositionTracker;

    public HyFixes(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        getLogger().at(Level.INFO).log("HyFixes is loading...");
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("Setting up HyFixes...");

        // Register bug fix systems
        registerBugFixes();

        getLogger().at(Level.INFO).log("HyFixes setup complete!");
    }

    private void registerBugFixes() {
        // Fix 1: Pickup item null targetRef crash
        // Hytale's PickupItemSystem.tick() crashes if getTargetRef() returns null
        getEntityStoreRegistry().registerSystem(new PickupItemSanitizer(this));
        getLogger().at(Level.INFO).log("[FIX] PickupItemSanitizer registered - prevents world crash from corrupted items");

        // Fix 2: RespawnBlock null respawnPoints crash
        // Hytale's RespawnBlock$OnRemove.onEntityRemove() crashes if respawnPoints is null
        getChunkStoreRegistry().registerSystem(new RespawnBlockSanitizer(this));
        getLogger().at(Level.INFO).log("[FIX] RespawnBlockSanitizer registered - prevents crash when breaking respawn blocks");

        // Fix 3: ProcessingBench window NPE crash
        // Hytale's ProcessingBenchState.onDestroy() crashes when windows have null refs
        getChunkStoreRegistry().registerSystem(new ProcessingBenchSanitizer(this));
        getLogger().at(Level.INFO).log("[FIX] ProcessingBenchSanitizer registered - prevents crash when breaking benches with open windows");

        // Fix 4: Empty archetype entity monitoring
        // Monitors for entities with invalid state (empty archetypes)
        getEntityStoreRegistry().registerSystem(new EmptyArchetypeSanitizer(this));
        getLogger().at(Level.INFO).log("[FIX] EmptyArchetypeSanitizer registered - monitors for invalid entity states");

        // Fix 5: Instance exit missing return world crash
        // Tracks player positions before entering instances and restores them if exit fails
        instancePositionTracker = new InstancePositionTracker(this);
        instancePositionTracker.register();
        getLogger().at(Level.INFO).log("[FIX] InstancePositionTracker registered - prevents crash when exiting instances with missing return world");
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("HyFixes has started! " + getFixCount() + " bug fix(es) active.");
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("HyFixes has been disabled.");
    }

    private int getFixCount() {
        return 5; // PickupItemSanitizer, RespawnBlockSanitizer, ProcessingBenchSanitizer, EmptyArchetypeSanitizer, InstancePositionTracker
    }

    public static HyFixes getInstance() {
        return instance;
    }
}
