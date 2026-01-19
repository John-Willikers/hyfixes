package com.hyfixes.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages the HyFixes configuration file.
 * 
 * The config file is stored at: mods/hyfixes/config.json
 * 
 * This class uses static initialization so both the runtime plugin
 * and early plugin can access the same config instance.
 */
public class ConfigManager {

    private static final String CONFIG_DIR = "mods/hyfixes";
    private static final String CONFIG_FILE = "config.json";
    private static final Path CONFIG_PATH = Paths.get(CONFIG_DIR, CONFIG_FILE);

    private static ConfigManager instance;
    private static final Object lock = new Object();

    private HyFixesConfig config;
    private boolean loadedFromFile = false;
    private boolean envOverridesApplied = false;

    /**
     * Get the singleton instance, loading config on first access.
     */
    public static ConfigManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new ConfigManager();
                    instance.loadConfig();
                }
            }
        }
        return instance;
    }

    /**
     * Private constructor - use getInstance()
     */
    private ConfigManager() {
        this.config = new HyFixesConfig();
    }

    /**
     * Load configuration from file, creating defaults if needed.
     */
    private void loadConfig() {
        try {
            // Create directory if it doesn't exist
            Path configDir = Paths.get(CONFIG_DIR);
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                System.out.println("[HyFixes-Config] Created config directory: " + CONFIG_DIR);
            }

            // Check if config file exists
            if (Files.exists(CONFIG_PATH)) {
                // Load existing config
                try (Reader reader = new BufferedReader(new InputStreamReader(
                        Files.newInputStream(CONFIG_PATH), StandardCharsets.UTF_8))) {
                    Gson gson = new Gson();
                    HyFixesConfig loaded = gson.fromJson(reader, HyFixesConfig.class);
                    if (loaded != null) {
                        this.config = loaded;
                        this.loadedFromFile = true;
                        System.out.println("[HyFixes-Config] Loaded configuration from: " + CONFIG_PATH);
                    } else {
                        System.out.println("[HyFixes-Config] Config file was empty, using defaults");
                    }
                }
            } else {
                // Generate default config
                saveDefaultConfig();
                System.out.println("[HyFixes-Config] Generated default configuration at: " + CONFIG_PATH);
            }

            // Apply environment variable overrides for backwards compatibility
            applyEnvironmentOverrides();

        } catch (Exception e) {
            System.err.println("[HyFixes-Config] Error loading config: " + e.getMessage());
            System.err.println("[HyFixes-Config] Using default configuration");
            e.printStackTrace();
            this.config = new HyFixesConfig();
        }
    }

    /**
     * Save the default configuration to file.
     */
    private void saveDefaultConfig() throws IOException {
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();

        String json = gson.toJson(config);
        
        // Add comment header
        String header = "// HyFixes Configuration\n" +
                "// Delete this file to regenerate defaults\n" +
                "// For documentation, see: https://github.com/John-Willikers/hyfixes\n\n";
        
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(CONFIG_PATH), StandardCharsets.UTF_8))) {
            // Note: JSON doesn't support comments, but we can add them as fields
            // or just write the JSON directly
            writer.write(json);
        }
    }

    /**
     * Apply environment variable overrides for backwards compatibility.
     * ENV/JVM args take precedence over config file values.
     */
    private void applyEnvironmentOverrides() {
        // HYFIXES_DISABLE_CHUNK_UNLOAD=true or -Dhyfixes.disableChunkUnload=true
        String disableChunkUnload = System.getenv("HYFIXES_DISABLE_CHUNK_UNLOAD");
        if (disableChunkUnload == null) {
            disableChunkUnload = System.getProperty("hyfixes.disableChunkUnload");
        }
        if ("true".equalsIgnoreCase(disableChunkUnload)) {
            config.chunkUnload.enabled = false;
            envOverridesApplied = true;
            System.out.println("[HyFixes-Config] ENV override: chunkUnload.enabled = false (HYFIXES_DISABLE_CHUNK_UNLOAD)");
        }

        // HYFIXES_VERBOSE=true or -Dhyfixes.verbose=true
        String verbose = System.getenv("HYFIXES_VERBOSE");
        if (verbose == null) {
            verbose = System.getProperty("hyfixes.verbose");
        }
        if ("true".equalsIgnoreCase(verbose)) {
            config.logging.verbose = true;
            config.early.logging.verbose = true;
            envOverridesApplied = true;
            System.out.println("[HyFixes-Config] ENV override: logging.verbose = true (HYFIXES_VERBOSE)");
        }
    }

    /**
     * Reload configuration from file.
     */
    public void reload() {
        loadConfig();
        System.out.println("[HyFixes-Config] Configuration reloaded");
    }

    /**
     * Get the current configuration.
     */
    public HyFixesConfig getConfig() {
        return config;
    }

    /**
     * Check if config was loaded from file (vs using defaults).
     */
    public boolean isLoadedFromFile() {
        return loadedFromFile;
    }

    /**
     * Check if environment overrides were applied.
     */
    public boolean hasEnvironmentOverrides() {
        return envOverridesApplied;
    }

    // ============================================
    // Convenience methods for common checks
    // ============================================

    /**
     * Check if a sanitizer is enabled by name.
     */
    public boolean isSanitizerEnabled(String name) {
        HyFixesConfig.SanitizersConfig s = config.sanitizers;
        return switch (name.toLowerCase()) {
            case "pickupitem" -> s.pickupItem;
            case "respawnblock" -> s.respawnBlock;
            case "processingbench" -> s.processingBench;
            case "craftingmanager" -> s.craftingManager;
            case "interactionmanager" -> s.interactionManager;
            case "spawnbeacon" -> s.spawnBeacon;
            case "chunktracker" -> s.chunkTracker;
            case "gatherobjective" -> s.gatherObjective;
            case "emptyarchetype" -> s.emptyArchetype;
            case "instancepositiontracker" -> s.instancePositionTracker;
            case "pickupitemchunkhandler" -> s.pickupItemChunkHandler;
            default -> {
                System.err.println("[HyFixes-Config] Unknown sanitizer: " + name);
                yield true; // Default to enabled for safety
            }
        };
    }

    /**
     * Check if a transformer is enabled by name.
     */
    public boolean isTransformerEnabled(String name) {
        HyFixesConfig.TransformersConfig t = config.transformers;
        return switch (name.toLowerCase()) {
            case "interactionchain" -> t.interactionChain;
            case "world" -> t.world;
            case "spawnreferencesystems" -> t.spawnReferenceSystems;
            case "beaconspawncontroller" -> t.beaconSpawnController;
            case "blockcomponentchunk" -> t.blockComponentChunk;
            case "spawnmarkersystems" -> t.spawnMarkerSystems;
            case "spawnmarkerentity" -> t.spawnMarkerEntity;
            case "trackedplacement" -> t.trackedPlacement;
            case "commandbuffer" -> t.commandBuffer;
            case "worldmaptracker" -> t.worldMapTracker;
            case "archetypechunk" -> t.archetypeChunk;
            default -> {
                System.err.println("[HyFixes-Config] Unknown transformer: " + name);
                yield true; // Default to enabled for safety
            }
        };
    }

    /**
     * Check if verbose logging is enabled.
     */
    public boolean isVerbose() {
        return config.logging.verbose;
    }

    /**
     * Check if early plugin verbose logging is enabled.
     */
    public boolean isEarlyVerbose() {
        return config.early.logging.verbose;
    }

    /**
     * Check if sanitizer action logging is enabled.
     */
    public boolean logSanitizerActions() {
        return config.logging.sanitizerActions;
    }

    // ============================================
    // Chunk unload settings
    // ============================================

    public boolean isChunkUnloadEnabled() {
        return config.chunkUnload.enabled;
    }

    public int getChunkUnloadIntervalSeconds() {
        return config.chunkUnload.intervalSeconds;
    }

    public int getChunkUnloadInitialDelaySeconds() {
        return config.chunkUnload.initialDelaySeconds;
    }

    public int getChunkUnloadGcEveryNAttempts() {
        return config.chunkUnload.gcEveryNAttempts;
    }

    // ============================================
    // Chunk cleanup settings
    // ============================================

    public int getChunkCleanupIntervalTicks() {
        return config.chunkCleanup.intervalTicks;
    }

    // ============================================
    // Interaction manager settings
    // ============================================

    public long getInteractionManagerClientTimeoutMs() {
        return config.interactionManager.clientTimeoutMs;
    }

    // ============================================
    // Instance tracker settings
    // ============================================

    public int getInstanceTrackerPositionTtlHours() {
        return config.instanceTracker.positionTtlHours;
    }

    // ============================================
    // Monitor settings
    // ============================================

    public int getMonitorLogIntervalTicks() {
        return config.monitor.logIntervalTicks;
    }

    // ============================================
    // Empty archetype settings
    // ============================================

    public int getEmptyArchetypeSkipFirstN() {
        return config.emptyArchetype.skipFirstN;
    }

    public int getEmptyArchetypeLogEveryN() {
        return config.emptyArchetype.logEveryN;
    }

    // ============================================
    // World transformer settings
    // ============================================

    public int getWorldRetryCount() {
        return config.world.retryCount;
    }

    public long getWorldRetryDelayMs() {
        return config.world.retryDelayMs;
    }
}
