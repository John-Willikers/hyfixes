package com.hyfixes.config;

/**
 * HyFixes Configuration - Contains all runtime and early plugin settings.
 * 
 * This class is deserialized from config.json using Gson.
 * All fields have default values that match the original hardcoded behavior.
 */
public class HyFixesConfig {

    // Chunk unload settings
    public ChunkUnloadConfig chunkUnload = new ChunkUnloadConfig();
    
    // Chunk cleanup settings
    public ChunkCleanupConfig chunkCleanup = new ChunkCleanupConfig();
    
    // Chunk protection settings
    public ChunkProtectionConfig chunkProtection = new ChunkProtectionConfig();
    
    // Sanitizer toggles
    public SanitizersConfig sanitizers = new SanitizersConfig();
    
    // Interaction manager settings
    public InteractionManagerConfig interactionManager = new InteractionManagerConfig();
    
    // Instance tracker settings
    public InstanceTrackerConfig instanceTracker = new InstanceTrackerConfig();
    
    // Monitor settings
    public MonitorConfig monitor = new MonitorConfig();
    
    // Empty archetype settings
    public EmptyArchetypeConfig emptyArchetype = new EmptyArchetypeConfig();
    
    // Logging settings
    public LoggingConfig logging = new LoggingConfig();
    
    // Transformer toggles (for early plugin)
    public TransformersConfig transformers = new TransformersConfig();
    
    // World transformer settings
    public WorldConfig world = new WorldConfig();
    
    // Early plugin logging
    public EarlyConfig early = new EarlyConfig();

    // Interaction timeout settings (for early plugin)
    public InteractionTimeoutConfig interactionTimeout = new InteractionTimeoutConfig();

    /**
     * Chunk unload configuration
     */
    public static class ChunkUnloadConfig {
        public boolean enabled = true;
        public int intervalSeconds = 30;
        public int initialDelaySeconds = 10;
        public int gcEveryNAttempts = 5;

        /**
         * Map-Aware Mode: Pre-renders map images before unloading chunks.
         * This ensures BetterMaps and the vanilla map system have the data they need.
         * When enabled, map images are generated for chunks before they're unloaded,
         * preserving map data even after chunk memory is freed.
         *
         * Enable this if you're using BetterMaps or experiencing black/missing map areas.
         */
        public boolean mapAwareMode = false;
    }

    /**
     * Chunk cleanup configuration
     */
    public static class ChunkCleanupConfig {
        public int intervalTicks = 600; // 30 seconds at 20 TPS
    }

    /**
     * Chunk protection configuration - prevents cleanup of chunks containing important content
     */
    public static class ChunkProtectionConfig {
        public boolean enabled = true;
        public String[] protectedEntityKeywords = {"teleport", "portal", "warp", "interaction", "zone"};
        public String[] protectedBlockKeywords = {"teleport", "portal", "warp", "spawner", "beacon"};
        public boolean protectGrowingPlants = true;
        public boolean protectSpawnBeacons = true;
        public int verificationIntervalTicks = 12000; // 10 minutes at 20 TPS
        public boolean logProtectionEvents = false;
    }

    /**
     * Sanitizer toggle configuration
     */
    public static class SanitizersConfig {
        public boolean pickupItem = true;
        public boolean respawnBlock = true;
        public boolean processingBench = true;
        public boolean craftingManager = true;
        public boolean interactionManager = true;
        public boolean spawnBeacon = true;
        public boolean chunkTracker = true;
        public boolean gatherObjective = true;
        public boolean emptyArchetype = true;
        public boolean instancePositionTracker = true;
        public boolean pickupItemChunkHandler = true;
        public boolean defaultWorldRecovery = true;  // Auto-reload default world after crash
    }

    /**
     * Interaction manager configuration
     */
    public static class InteractionManagerConfig {
        public long clientTimeoutMs = 2000;
    }

    /**
     * Instance tracker configuration
     */
    public static class InstanceTrackerConfig {
        public int positionTtlHours = 24;
    }

    /**
     * Monitor configuration
     */
    public static class MonitorConfig {
        public int logIntervalTicks = 6000; // 5 minutes at 20 TPS
    }

    /**
     * Empty archetype configuration
     */
    public static class EmptyArchetypeConfig {
        public int skipFirstN = 1000;
        public int logEveryN = 10000;
    }

    /**
     * Logging configuration
     */
    public static class LoggingConfig {
        public boolean verbose = false;
        public boolean sanitizerActions = true;
    }

    /**
     * Transformer toggle configuration (for early plugin)
     */
    public static class TransformersConfig {
        public boolean interactionChain = true;
        public boolean world = true;
        public boolean spawnReferenceSystems = true;
        public boolean beaconSpawnController = true;
        public boolean blockComponentChunk = true;
        public boolean spawnMarkerSystems = true;
        public boolean spawnMarkerEntity = true;
        public boolean trackedPlacement = true;
        public boolean commandBuffer = true;
        public boolean worldMapTracker = true;
        public boolean archetypeChunk = true;
        public boolean interactionTimeout = true;
        public boolean uuidSystem = true;
        public boolean tickingThread = true;
    }

    /**
     * World transformer configuration
     */
    public static class WorldConfig {
        public int retryCount = 5;
        public long retryDelayMs = 20;
    }

    /**
     * Early plugin configuration
     */
    public static class EarlyConfig {
        public EarlyLoggingConfig logging = new EarlyLoggingConfig();
    }

    /**
     * Early plugin logging configuration
     */
    public static class EarlyLoggingConfig {
        public boolean verbose = false;
    }

    /**
     * Interaction timeout configuration
     * Controls how long the server waits for client responses during interactions
     */
    public static class InteractionTimeoutConfig {
        /** Base timeout in milliseconds (added to ping-based calculation) */
        public long baseTimeoutMs = 6000;

        /** Multiplier applied to average ping */
        public double pingMultiplier = 3.0;
    }


    // ============================================
    // Convenience setter methods for dashboard UI
    // ============================================

    /**
     * Set verbose logging.
     */
    public void setVerbose(boolean verbose) {
        this.logging.verbose = verbose;
    }

    /**
     * Check if verbose logging is enabled.
     */
    public boolean isVerbose() {
        return this.logging.verbose;
    }

    /**
     * Set chunk unload enabled.
     */
    public void setChunkUnloadEnabled(boolean enabled) {
        this.chunkUnload.enabled = enabled;
    }

    /**
     * Check if chunk unload is enabled.
     */
    public boolean isChunkUnloadEnabled() {
        return this.chunkUnload.enabled;
    }

    /**
     * Set chunk protection enabled.
     */
    public void setChunkProtectionEnabled(boolean enabled) {
        this.chunkProtection.enabled = enabled;
    }

    /**
     * Check if chunk protection is enabled.
     */
    public boolean isChunkProtectionEnabled() {
        return this.chunkProtection.enabled;
    }

    /**
     * Set map-aware mode enabled.
     */
    public void setMapAwareModeEnabled(boolean enabled) {
        this.chunkUnload.mapAwareMode = enabled;
    }

    /**
     * Check if map-aware mode is enabled.
     */
    public boolean isMapAwareModeEnabled() {
        return this.chunkUnload.mapAwareMode;
    }

    /**
     * Set sanitizer action logging.
     */
    public void setLogSanitizerActions(boolean enabled) {
        this.logging.sanitizerActions = enabled;
    }

    /**
     * Check if sanitizer action logging is enabled.
     */
    public boolean logSanitizerActions() {
        return this.logging.sanitizerActions;
    }

    /**
     * Set chunk protection event logging.
     */
    public void setLogChunkProtectionEvents(boolean enabled) {
        this.chunkProtection.logProtectionEvents = enabled;
    }

    /**
     * Check if chunk protection event logging is enabled.
     */
    public boolean logChunkProtectionEvents() {
        return this.chunkProtection.logProtectionEvents;
    }
}
