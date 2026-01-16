package com.hyfixes;

import com.hyfixes.commands.ChunkStatusCommand;
import com.hyfixes.commands.ChunkUnloadCommand;
import com.hyfixes.commands.InteractionStatusCommand;
import com.hyfixes.listeners.EmptyArchetypeSanitizer;
import com.hyfixes.listeners.GatherObjectiveTaskSanitizer;
import com.hyfixes.listeners.InstancePositionTracker;
import com.hyfixes.listeners.PickupItemChunkHandler;
import com.hyfixes.listeners.PickupItemSanitizer;
import com.hyfixes.listeners.ProcessingBenchSanitizer;
import com.hyfixes.listeners.RespawnBlockSanitizer;
import com.hyfixes.systems.ChunkCleanupSystem;
import com.hyfixes.systems.ChunkUnloadManager;
import com.hyfixes.systems.InteractionChainMonitor;
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
 * - PickupItemChunkHandler: Backup protection during chunk unload events (v1.3.0)
 * - RespawnBlockSanitizer: Prevents crash when breaking respawn blocks with null respawnPoints
 * - ProcessingBenchSanitizer: Prevents crash when breaking processing benches with open windows
 * - EmptyArchetypeSanitizer: Monitors for entities with invalid state (empty archetypes)
 * - InstancePositionTracker: Prevents kick when exiting instances with missing return world
 * - ChunkUnloadManager: Aggressively unloads chunks to prevent memory bloat (v1.2.0)
 * - GatherObjectiveTaskSanitizer: Prevents crash from null refs in quest objectives (v1.3.0)
 * - InteractionChainMonitor: Tracks unfixable Hytale bugs for reporting (v1.3.0)
 */
public class HyFixes extends JavaPlugin {

    private static HyFixes instance;
    private InstancePositionTracker instancePositionTracker;
    private ChunkUnloadManager chunkUnloadManager;
    private ChunkCleanupSystem chunkCleanupSystem;
    private GatherObjectiveTaskSanitizer gatherObjectiveTaskSanitizer;
    private PickupItemChunkHandler pickupItemChunkHandler;
    private InteractionChainMonitor interactionChainMonitor;

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

        // Fix 6: Chunk memory bloat - chunks not unloading (v1.2.0)
        // Uses reflection to discover and call chunk unload APIs
        chunkUnloadManager = new ChunkUnloadManager(this);

        // Fix 6b: Main-thread chunk cleanup system (v1.2.2)
        // Runs cleanup methods on the main server thread to avoid InvocationTargetException
        chunkCleanupSystem = new ChunkCleanupSystem(this);
        getEntityStoreRegistry().registerSystem(chunkCleanupSystem);
        getLogger().at(Level.INFO).log("[FIX] ChunkCleanupSystem registered - runs cleanup on main thread");

        // Wire up the systems
        chunkUnloadManager.setChunkCleanupSystem(chunkCleanupSystem);
        chunkUnloadManager.start();
        getLogger().at(Level.INFO).log("[FIX] ChunkUnloadManager registered - aggressively unloads unused chunks");

        // Fix 7: GatherObjectiveTask null ref crash (v1.3.0)
        // Validates refs in quest objectives before they can crash
        gatherObjectiveTaskSanitizer = new GatherObjectiveTaskSanitizer(this);
        getEntityStoreRegistry().registerSystem(gatherObjectiveTaskSanitizer);
        getLogger().at(Level.INFO).log("[FIX] GatherObjectiveTaskSanitizer registered - prevents crash from null refs in quest objectives");

        // Fix 8: Pickup item chunk handler backup (v1.3.0)
        // RefSystem backup for catching null targetRef during chunk unloads
        pickupItemChunkHandler = new PickupItemChunkHandler(this);
        getEntityStoreRegistry().registerSystem(pickupItemChunkHandler);
        getLogger().at(Level.INFO).log("[FIX] PickupItemChunkHandler registered - backup protection during chunk unloads");

        // Fix 9: InteractionChain monitoring (v1.3.0)
        // Tracks unfixable Hytale bugs for reporting to developers
        interactionChainMonitor = new InteractionChainMonitor(this);
        getEntityStoreRegistry().registerSystem(interactionChainMonitor);
        getLogger().at(Level.INFO).log("[MON] InteractionChainMonitor registered - tracks HyFixes statistics");

        // Register admin commands
        registerCommands();
    }

    private void registerCommands() {
        getCommandRegistry().registerCommand(new ChunkStatusCommand(this));
        getCommandRegistry().registerCommand(new ChunkUnloadCommand(this));
        getCommandRegistry().registerCommand(new InteractionStatusCommand(this));
        getLogger().at(Level.INFO).log("[CMD] Registered /chunkstatus, /chunkunload, and /interactionstatus commands");
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("HyFixes has started! " + getFixCount() + " bug fix(es) active.");
    }

    @Override
    protected void shutdown() {
        // Stop the chunk unload manager
        if (chunkUnloadManager != null) {
            chunkUnloadManager.stop();
        }
        getLogger().at(Level.INFO).log("HyFixes has been disabled.");
    }

    private int getFixCount() {
        return 10; // PickupItemSanitizer, PickupItemChunkHandler, RespawnBlockSanitizer, ProcessingBenchSanitizer, EmptyArchetypeSanitizer, InstancePositionTracker, ChunkUnloadManager, ChunkCleanupSystem, GatherObjectiveTaskSanitizer, InteractionChainMonitor
    }

    public static HyFixes getInstance() {
        return instance;
    }

    /**
     * Get the ChunkUnloadManager for commands and status.
     */
    public ChunkUnloadManager getChunkUnloadManager() {
        return chunkUnloadManager;
    }

    /**
     * Get the ChunkCleanupSystem for commands and status.
     */
    public ChunkCleanupSystem getChunkCleanupSystem() {
        return chunkCleanupSystem;
    }

    /**
     * Get the GatherObjectiveTaskSanitizer for commands and status.
     */
    public GatherObjectiveTaskSanitizer getGatherObjectiveTaskSanitizer() {
        return gatherObjectiveTaskSanitizer;
    }

    /**
     * Get the PickupItemChunkHandler for commands and status.
     */
    public PickupItemChunkHandler getPickupItemChunkHandler() {
        return pickupItemChunkHandler;
    }

    /**
     * Get the InteractionChainMonitor for commands and status.
     */
    public InteractionChainMonitor getInteractionChainMonitor() {
        return interactionChainMonitor;
    }
}
