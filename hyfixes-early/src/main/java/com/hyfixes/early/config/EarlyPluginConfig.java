package com.hyfixes.early.config;

/**
 * HyFixes Early Plugin Configuration
 * 
 * This class mirrors the transformer-related settings from the main HyFixesConfig.
 * It's loaded from the same config.json file as the runtime plugin.
 */
public class EarlyPluginConfig {

    // Transformer toggles
    public TransformersConfig transformers = new TransformersConfig();

    // World transformer settings
    public WorldConfig world = new WorldConfig();

    // Early plugin logging
    public EarlyConfig early = new EarlyConfig();

    // Interaction timeout settings
    public InteractionTimeoutConfig interactionTimeout = new InteractionTimeoutConfig();

    /**
     * Transformer toggle configuration
     */
    public static class TransformersConfig {
        public boolean interactionChain = true;
        public boolean interactionManager = true;
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
        public boolean universeRemovePlayer = true;
        public boolean playerSystems = false;
        public boolean livingEntity = true;
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
}
