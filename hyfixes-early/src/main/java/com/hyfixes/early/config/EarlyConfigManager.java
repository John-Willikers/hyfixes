package com.hyfixes.early.config;

import com.google.gson.Gson;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages the HyFixes Early Plugin configuration.
 * 
 * Reads from the same config file as the runtime plugin: mods/hyfixes/config.json
 * 
 * Uses static initialization so transformers can access config before
 * the runtime plugin loads.
 */
public class EarlyConfigManager {

    private static final String CONFIG_DIR = "mods/hyfixes";
    private static final String CONFIG_FILE = "config.json";
    private static final Path CONFIG_PATH = Paths.get(CONFIG_DIR, CONFIG_FILE);

    private static EarlyConfigManager instance;
    private static final Object lock = new Object();

    private EarlyPluginConfig config;
    private boolean loadedFromFile = false;

    /**
     * Get the singleton instance, loading config on first access.
     */
    public static EarlyConfigManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new EarlyConfigManager();
                    instance.loadConfig();
                }
            }
        }
        return instance;
    }

    /**
     * Private constructor - use getInstance()
     */
    private EarlyConfigManager() {
        this.config = new EarlyPluginConfig();
    }

    /**
     * Load configuration from file.
     * Note: The early plugin only reads the config, it doesn't create it.
     * The runtime plugin is responsible for generating defaults.
     */
    private void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (Reader reader = new BufferedReader(new InputStreamReader(
                        Files.newInputStream(CONFIG_PATH), StandardCharsets.UTF_8))) {
                    Gson gson = new Gson();
                    EarlyPluginConfig loaded = gson.fromJson(reader, EarlyPluginConfig.class);
                    if (loaded != null) {
                        this.config = loaded;
                        this.loadedFromFile = true;
                        System.out.println("[HyFixes-Early-Config] Loaded configuration from: " + CONFIG_PATH);
                    } else {
                        System.out.println("[HyFixes-Early-Config] Config file was empty, using defaults");
                    }
                }
            } else {
                System.out.println("[HyFixes-Early-Config] No config file found at: " + CONFIG_PATH);
                System.out.println("[HyFixes-Early-Config] Using default settings. Config will be created by runtime plugin.");
            }

            // Apply environment variable overrides for verbose logging
            applyEnvironmentOverrides();

        } catch (Exception e) {
            System.err.println("[HyFixes-Early-Config] Error loading config: " + e.getMessage());
            System.err.println("[HyFixes-Early-Config] Using default configuration");
            e.printStackTrace();
            this.config = new EarlyPluginConfig();
        }
    }

    /**
     * Apply environment variable overrides.
     */
    private void applyEnvironmentOverrides() {
        // HYFIXES_VERBOSE=true or -Dhyfixes.verbose=true
        String verbose = System.getenv("HYFIXES_VERBOSE");
        if (verbose == null) {
            verbose = System.getProperty("hyfixes.verbose");
        }
        if ("true".equalsIgnoreCase(verbose)) {
            config.early.logging.verbose = true;
            System.out.println("[HyFixes-Early-Config] ENV override: early.logging.verbose = true (HYFIXES_VERBOSE)");
        }
    }

    /**
     * Get the current configuration.
     */
    public EarlyPluginConfig getConfig() {
        return config;
    }

    /**
     * Check if config was loaded from file.
     */
    public boolean isLoadedFromFile() {
        return loadedFromFile;
    }

    // ============================================
    // Convenience methods for transformer checks
    // ============================================

    /**
     * Check if a transformer is enabled by name.
     */
    public boolean isTransformerEnabled(String name) {
        EarlyPluginConfig.TransformersConfig t = config.transformers;
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
                System.err.println("[HyFixes-Early-Config] Unknown transformer: " + name);
                yield true; // Default to enabled for safety
            }
        };
    }

    /**
     * Check if verbose logging is enabled.
     */
    public boolean isVerbose() {
        return config.early.logging.verbose;
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
