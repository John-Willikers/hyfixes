package com.hyfixes;

import com.hyfixes.commands.ChunkStatusCommand;
import com.hyfixes.commands.ChunkUnloadCommand;
import com.hyfixes.commands.CleanInteractionsCommand;
import com.hyfixes.commands.CleanWarpsCommand;
import com.hyfixes.commands.FixCounterCommand;
import com.hyfixes.commands.InteractionStatusCommand;
import com.hyfixes.commands.WhoCommand;
import com.hyfixes.config.ConfigManager;
import com.hyfixes.listeners.CraftingManagerSanitizer;
import com.hyfixes.listeners.EmptyArchetypeSanitizer;
import com.hyfixes.listeners.InteractionManagerSanitizer;
import com.hyfixes.listeners.GatherObjectiveTaskSanitizer;
import com.hyfixes.listeners.InstancePositionTracker;
import com.hyfixes.listeners.PickupItemChunkHandler;
import com.hyfixes.listeners.PickupItemSanitizer;
import com.hyfixes.listeners.ProcessingBenchSanitizer;
import com.hyfixes.listeners.RespawnBlockSanitizer;
import com.hyfixes.listeners.SpawnBeaconSanitizer;
import com.hyfixes.listeners.ChunkTrackerSanitizer;
import com.hyfixes.listeners.InstanceTeleportSanitizer;
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
 * - CraftingManagerSanitizer: Prevents crash from stale bench references (v1.3.1)
 * - InteractionManagerSanitizer: Prevents NPE crash when opening crafttables (v1.3.1, Issue #1)
 * - SpawnBeaconSanitizer: Prevents crash from null spawn parameters in BeaconSpawnController (v1.3.7, Issue #4)
 * - [MOVED TO EARLY PLUGIN] SpawnMarkerReferenceSanitizer: Now fixed via bytecode transformation (v1.4.0)
 * - ChunkTrackerSanitizer: Prevents crash from invalid PlayerRefs during chunk unload (v1.3.9, Issue #6)
 * - InstanceTeleportSanitizer: Monitors and handles instance portal race conditions (v1.3.10, Issue #7)
 */
public class HyFixes extends JavaPlugin {

    private static HyFixes instance;
    private InstancePositionTracker instancePositionTracker;
    private ChunkUnloadManager chunkUnloadManager;
    private ChunkCleanupSystem chunkCleanupSystem;
    private GatherObjectiveTaskSanitizer gatherObjectiveTaskSanitizer;
    private PickupItemChunkHandler pickupItemChunkHandler;
    private InteractionChainMonitor interactionChainMonitor;
    private CraftingManagerSanitizer craftingManagerSanitizer;
    private InteractionManagerSanitizer interactionManagerSanitizer;
    private SpawnBeaconSanitizer spawnBeaconSanitizer;
    private ChunkTrackerSanitizer chunkTrackerSanitizer;
    private InstanceTeleportSanitizer instanceTeleportSanitizer;

    public HyFixes(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        getLogger().at(Level.INFO).log("HyFixes is loading...");
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("Setting up HyFixes...");

        // Initialize configuration first
        ConfigManager config = ConfigManager.getInstance();
        if (config.isLoadedFromFile()) {
            getLogger().at(Level.INFO).log("[CONFIG] Loaded configuration from mods/hyfixes/config.json");
        } else {
            getLogger().at(Level.INFO).log("[CONFIG] Using default configuration (config.json generated)");
        }
        if (config.hasEnvironmentOverrides()) {
            getLogger().at(Level.INFO).log("[CONFIG] Environment variable overrides applied");
        }

        // Register bug fix systems
        registerBugFixes();

        getLogger().at(Level.INFO).log("HyFixes setup complete!");
    }

    private void registerBugFixes() {
        ConfigManager config = ConfigManager.getInstance();

        // Fix 1: Pickup item null targetRef crash
        // Hytale's PickupItemSystem.tick() crashes if getTargetRef() returns null
        if (config.isSanitizerEnabled("pickupItem")) {
            getEntityStoreRegistry().registerSystem(new PickupItemSanitizer(this));
            getLogger().at(Level.INFO).log("[FIX] PickupItemSanitizer registered - prevents world crash from corrupted items");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] PickupItemSanitizer - disabled via config");
        }

        // Fix 2: RespawnBlock null respawnPoints crash
        // Hytale's RespawnBlock$OnRemove.onEntityRemove() crashes if respawnPoints is null
        if (config.isSanitizerEnabled("respawnBlock")) {
            getChunkStoreRegistry().registerSystem(new RespawnBlockSanitizer(this));
            getLogger().at(Level.INFO).log("[FIX] RespawnBlockSanitizer registered - prevents crash when breaking respawn blocks");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] RespawnBlockSanitizer - disabled via config");
        }

        // Fix 3: ProcessingBench window NPE crash
        // Hytale's ProcessingBenchState.onDestroy() crashes when windows have null refs
        if (config.isSanitizerEnabled("processingBench")) {
            getChunkStoreRegistry().registerSystem(new ProcessingBenchSanitizer(this));
            getLogger().at(Level.INFO).log("[FIX] ProcessingBenchSanitizer registered - prevents crash when breaking benches with open windows");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] ProcessingBenchSanitizer - disabled via config");
        }

        // Fix 4: Empty archetype entity monitoring
        // Monitors for entities with invalid state (empty archetypes)
        if (config.isSanitizerEnabled("emptyArchetype")) {
            getEntityStoreRegistry().registerSystem(new EmptyArchetypeSanitizer(this));
            getLogger().at(Level.INFO).log("[FIX] EmptyArchetypeSanitizer registered - monitors for invalid entity states");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] EmptyArchetypeSanitizer - disabled via config");
        }

        // Fix 5: Instance exit missing return world crash
        // Tracks player positions before entering instances and restores them if exit fails
        if (config.isSanitizerEnabled("instancePositionTracker")) {
            instancePositionTracker = new InstancePositionTracker(this);
            instancePositionTracker.register();
            getLogger().at(Level.INFO).log("[FIX] InstancePositionTracker registered - prevents crash when exiting instances with missing return world");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] InstancePositionTracker - disabled via config");
        }

        // Fix 6: Chunk memory bloat - chunks not unloading (v1.2.0)
        // Uses reflection to discover and call chunk unload APIs
        // NOTE: Can be disabled via config or HYFIXES_DISABLE_CHUNK_UNLOAD=true for BetterMap compatibility (Issue #21)
        if (!config.isChunkUnloadEnabled()) {
            getLogger().at(Level.INFO).log("[DISABLED] ChunkUnloadManager - disabled via config");
            getLogger().at(Level.INFO).log("[DISABLED] This improves compatibility with BetterMap and other map plugins");
            chunkUnloadManager = null;
            chunkCleanupSystem = null;
        } else {
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
            getLogger().at(Level.INFO).log("[TIP] To disable for BetterMap compatibility, set chunkUnload.enabled=false in config.json");
        }

        // Fix 7: GatherObjectiveTask null ref crash (v1.3.0)
        // Validates refs in quest objectives before they can crash
        if (config.isSanitizerEnabled("gatherObjective")) {
            gatherObjectiveTaskSanitizer = new GatherObjectiveTaskSanitizer(this);
            getEntityStoreRegistry().registerSystem(gatherObjectiveTaskSanitizer);
            getLogger().at(Level.INFO).log("[FIX] GatherObjectiveTaskSanitizer registered - prevents crash from null refs in quest objectives");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] GatherObjectiveTaskSanitizer - disabled via config");
        }

        // Fix 8: Pickup item chunk handler backup (v1.3.0)
        // RefSystem backup for catching null targetRef during chunk unloads
        if (config.isSanitizerEnabled("pickupItemChunkHandler")) {
            pickupItemChunkHandler = new PickupItemChunkHandler(this);
            getEntityStoreRegistry().registerSystem(pickupItemChunkHandler);
            getLogger().at(Level.INFO).log("[FIX] PickupItemChunkHandler registered - backup protection during chunk unloads");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] PickupItemChunkHandler - disabled via config");
        }

        // Fix 9: InteractionChain monitoring (v1.3.0)
        // Tracks unfixable Hytale bugs for reporting to developers
        interactionChainMonitor = new InteractionChainMonitor(this);
        getEntityStoreRegistry().registerSystem(interactionChainMonitor);
        getLogger().at(Level.INFO).log("[MON] InteractionChainMonitor registered - tracks HyFixes statistics");

        // Fix 10: CraftingManager bench already set crash (v1.3.1)
        // Clears stale bench references before they cause IllegalArgumentException
        if (config.isSanitizerEnabled("craftingManager")) {
            craftingManagerSanitizer = new CraftingManagerSanitizer(this);
            getEntityStoreRegistry().registerSystem(craftingManagerSanitizer);
            getLogger().at(Level.INFO).log("[FIX] CraftingManagerSanitizer registered - prevents bench already set crash");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] CraftingManagerSanitizer - disabled via config");
        }

        // Fix 11: InteractionManager NPE crash when opening crafttables (v1.3.1)
        // GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/1
        // Validates interaction chains and removes ones with null context before they cause NPE
        if (config.isSanitizerEnabled("interactionManager")) {
            interactionManagerSanitizer = new InteractionManagerSanitizer(this);
            getEntityStoreRegistry().registerSystem(interactionManagerSanitizer);
            getLogger().at(Level.INFO).log("[FIX] InteractionManagerSanitizer registered - prevents crafttable interaction crash");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] InteractionManagerSanitizer - disabled via config");
        }

        // Fix 12: SpawnBeacon null RoleSpawnParameters crash (v1.3.7)
        // GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/4
        // Validates spawn parameters before BeaconSpawnController.createRandomSpawnJob() can crash
        if (config.isSanitizerEnabled("spawnBeacon")) {
            spawnBeaconSanitizer = new SpawnBeaconSanitizer(this);
            getEntityStoreRegistry().registerSystem(spawnBeaconSanitizer);
            getLogger().at(Level.INFO).log("[FIX] SpawnBeaconSanitizer registered - prevents spawn beacon null parameter crash");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] SpawnBeaconSanitizer - disabled via config");
        }

        // Fix 13: SpawnMarkerReference null npcReferences crash (v1.3.8)
        // GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/5
        // MOVED TO EARLY PLUGIN in v1.4.0 - Now fixed via bytecode transformation
        // The early plugin transforms SpawnMarkerEntity constructor to initialize npcReferences
        getLogger().at(Level.INFO).log("[MOVED] SpawnMarkerReferenceSanitizer - now fixed via early plugin bytecode transformation");

        // Fix 14: ChunkTracker null PlayerRef crash (v1.3.9)
        // GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/6
        // Prevents world crash when ChunkTracker has invalid PlayerRefs after player disconnect
        if (config.isSanitizerEnabled("chunkTracker")) {
            chunkTrackerSanitizer = new ChunkTrackerSanitizer(this);
            getEntityStoreRegistry().registerSystem(chunkTrackerSanitizer);
            getLogger().at(Level.INFO).log("[FIX] ChunkTrackerSanitizer registered - prevents chunk unload crash after player disconnect");
        } else {
            getLogger().at(Level.INFO).log("[DISABLED] ChunkTrackerSanitizer - disabled via config");
        }

        // Fix 15: Instance teleport race condition (v1.3.10)
        // GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/7
        // DISABLED in v1.4.1 - This sanitizer was causing timing issues that led to
        // the race condition being triggered MORE often after plugin removal.
        // The underlying bug is a Hytale race condition in World.addPlayer() that
        // cannot be safely fixed at the plugin level.
        // instanceTeleportSanitizer = new InstanceTeleportSanitizer(this);
        // instanceTeleportSanitizer.register();
        getLogger().at(Level.INFO).log("[DISABLED] InstanceTeleportSanitizer - race condition cannot be safely fixed at plugin level");

        // Register admin commands
        registerCommands();
    }

    private void registerCommands() {
        getCommandRegistry().registerCommand(new ChunkStatusCommand(this));
        getCommandRegistry().registerCommand(new ChunkUnloadCommand(this));
        getCommandRegistry().registerCommand(new CleanInteractionsCommand(this));
        getCommandRegistry().registerCommand(new CleanWarpsCommand(this));
        getCommandRegistry().registerCommand(new FixCounterCommand(this));
        getCommandRegistry().registerCommand(new InteractionStatusCommand(this));
        getCommandRegistry().registerCommand(new WhoCommand());
        getLogger().at(Level.INFO).log("[CMD] Registered /chunkstatus, /chunkunload, /fixcounter, /interactionstatus, and /who commands");
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
        // Base fixes: PickupItemSanitizer, PickupItemChunkHandler, RespawnBlockSanitizer, ProcessingBenchSanitizer,
        // EmptyArchetypeSanitizer, InstancePositionTracker, GatherObjectiveTaskSanitizer, InteractionChainMonitor,
        // CraftingManagerSanitizer, InteractionManagerSanitizer, SpawnBeaconSanitizer, ChunkTrackerSanitizer
        // (SpawnMarkerReferenceSanitizer moved to early plugin, InstanceTeleportSanitizer disabled)
        int count = 12;
        // ChunkUnloadManager + ChunkCleanupSystem (optional, can be disabled for BetterMap compatibility)
        if (chunkUnloadManager != null) {
            count += 2;
        }
        return count;
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

    /**
     * Get the CraftingManagerSanitizer for commands and status.
     */
    public CraftingManagerSanitizer getCraftingManagerSanitizer() {
        return craftingManagerSanitizer;
    }

    /**
     * Get the InteractionManagerSanitizer for commands and status.
     */
    public InteractionManagerSanitizer getInteractionManagerSanitizer() {
        return interactionManagerSanitizer;
    }

    /**
     * Get the SpawnBeaconSanitizer for commands and status.
     */
    public SpawnBeaconSanitizer getSpawnBeaconSanitizer() {
        return spawnBeaconSanitizer;
    }

    /**
     * Get the ChunkTrackerSanitizer for commands and status.
     */
    public ChunkTrackerSanitizer getChunkTrackerSanitizer() {
        return chunkTrackerSanitizer;
    }

    /**
     * Get the InstanceTeleportSanitizer for commands and status.
     */
    public InstanceTeleportSanitizer getInstanceTeleportSanitizer() {
        return instanceTeleportSanitizer;
    }
}
