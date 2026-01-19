package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * InteractionManagerSanitizer - Prevents NPE crashes during interaction tick
 *
 * GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/1
 *
 * The Bug:
 * When a player opens a crafttable at specific locations, the InteractionManager
 * can end up with chains containing null context data. When TickInteractionManagerSystem
 * tries to tick these chains, it throws a NullPointerException and KICKS THE PLAYER.
 *
 * Error Pattern:
 * [SEVERE] [InteractionSystems$TickInteractionManagerSystem] Exception while ticking entity interactions! Removing!
 * java.lang.NullPointerException
 *
 * The Fix:
 * This sanitizer runs each tick BEFORE TickInteractionManagerSystem (by using a higher priority
 * system group if possible, or by registering early). It:
 * 1. Gets the InteractionManager component from each Player
 * 2. Validates all chains in the chains map
 * 3. Removes any chains with null context, null refs, or invalid state
 * 4. This prevents the NPE from ever reaching TickInteractionManagerSystem
 */
public class InteractionManagerSanitizer extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;

    // Discovered via reflection at runtime
    private Class<?> interactionManagerClass = null;
    private ComponentType interactionManagerType = null;
    private Method getChainsMethod = null;
    private Field contextField = null;  // InteractionChain.context
    private Field owningEntityField = null;  // InteractionContext.owningEntity
    private Method isValidMethod = null;  // Ref.isValid()

    // Timeout detection - added in v1.3.3
    private Field callStateField = null;  // InteractionChain.callState (enum)
    private Field waitStartTimeField = null;  // Timestamp when chain started waiting
    private Method cancelMethod = null;  // InteractionChain.cancel() or similar
    private Object waitingForClientDataState = null;  // CallState.WAITING_FOR_CLIENT_DATA enum value

    // Client timeout threshold - configurable (default 2000ms)
    // Lowered from 2500ms in v1.3.6 to catch more timeout issues before player gets kicked
    private final long clientTimeoutMs;

    // Track chains waiting for client data: chainKey -> firstSeenTimestamp
    private final ConcurrentHashMap<String, Long> waitingChains = new ConcurrentHashMap<>();

    private boolean initialized = false;
    private boolean apiDiscoveryFailed = false;
    private boolean timeoutDetectionEnabled = false;

    // Statistics
    private final AtomicInteger chainsValidated = new AtomicInteger(0);
    private final AtomicInteger chainsRemoved = new AtomicInteger(0);
    private final AtomicInteger crashesPrevented = new AtomicInteger(0);
    private final AtomicInteger timeoutsPrevented = new AtomicInteger(0);

    public InteractionManagerSanitizer(HyFixes plugin) {
        this.plugin = plugin;
        this.clientTimeoutMs = ConfigManager.getInstance().getInteractionManagerClientTimeoutMs();
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Query for Player entities (they have InteractionManager)
        return Player.getComponentType();
    }

    @Override
    public void tick(
            float deltaTime,
            int index,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        // Try to discover API on first tick
        if (!initialized && !apiDiscoveryFailed) {
            discoverApi();
        }

        if (apiDiscoveryFailed) {
            return;
        }

        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);

            // Get InteractionManager component
            Object interactionManager = chunk.getComponent(index, interactionManagerType);
            if (interactionManager == null) {
                return;
            }

            // Get the chains map
            @SuppressWarnings("unchecked")
            Map<Integer, Object> chains = (Map<Integer, Object>) getChainsMethod.invoke(interactionManager);
            if (chains == null || chains.isEmpty()) {
                return;
            }

            // Validate each chain
            java.util.List<Integer> chainsToRemove = new java.util.ArrayList<>();
            java.util.List<String> seenChainKeys = new java.util.ArrayList<>();

            for (Map.Entry<Integer, Object> entry : chains.entrySet()) {
                chainsValidated.incrementAndGet();
                Object chain = entry.getValue();
                Integer chainId = entry.getKey();

                if (chain == null) {
                    chainsToRemove.add(chainId);
                    continue;
                }

                // Check if context is null
                Object context = contextField.get(chain);
                if (context == null) {
                    chainsToRemove.add(chainId);
                    plugin.getLogger().at(Level.WARNING).log(
                            "[InteractionManagerSanitizer] Found chain with null context, removing to prevent crash");
                    continue;
                }

                // Check if owningEntity ref is null or invalid
                Object owningEntityRef = owningEntityField.get(context);
                if (owningEntityRef == null) {
                    chainsToRemove.add(chainId);
                    plugin.getLogger().at(Level.WARNING).log(
                            "[InteractionManagerSanitizer] Found chain with null owningEntity ref, removing to prevent crash");
                    continue;
                }

                // Check if the ref is valid
                if (isValidMethod != null) {
                    Boolean isValid = (Boolean) isValidMethod.invoke(owningEntityRef);
                    if (!isValid) {
                        chainsToRemove.add(chainId);
                        plugin.getLogger().at(Level.WARNING).log(
                                "[InteractionManagerSanitizer] Found chain with invalid owningEntity ref, removing to prevent crash");
                        continue;
                    }
                }

                // Client timeout detection (v1.3.3)
                if (timeoutDetectionEnabled && callStateField != null) {
                    try {
                        Object callState = callStateField.get(chain);
                        String chainKey = ref.toString() + ":" + chainId;
                        seenChainKeys.add(chainKey);

                        // Check if chain is waiting for client data
                        if (isWaitingForClientData(callState)) {
                            Long firstSeen = waitingChains.get(chainKey);
                            long now = System.currentTimeMillis();

                            if (firstSeen == null) {
                                // First time seeing this chain waiting
                                waitingChains.put(chainKey, now);
                            } else if (now - firstSeen > clientTimeoutMs) {
                                // Chain has been waiting too long - proactively cancel it
                                chainsToRemove.add(chainId);
                                waitingChains.remove(chainKey);
                                timeoutsPrevented.incrementAndGet();
                                plugin.getLogger().at(Level.WARNING).log(
                                        "[InteractionManagerSanitizer] Chain waiting for client data > " +
                                        clientTimeoutMs + "ms, removing to prevent kick (chain " + chainId + ")");
                            }
                        } else {
                            // Chain not waiting anymore - remove from tracking
                            waitingChains.remove(chainKey);
                        }
                    } catch (Exception e) {
                        // Ignore timeout check errors - still have main validation
                    }
                }
            }

            // Clean up tracking for chains that no longer exist
            waitingChains.keySet().removeIf(key -> !seenChainKeys.contains(key));

            // Remove invalid chains
            if (!chainsToRemove.isEmpty()) {
                for (Integer chainId : chainsToRemove) {
                    chains.remove(chainId);
                    chainsRemoved.incrementAndGet();
                }
                crashesPrevented.incrementAndGet();
                plugin.getLogger().at(Level.INFO).log(
                        "[InteractionManagerSanitizer] Removed " + chainsToRemove.size() +
                                " invalid chain(s) to prevent player kick");
            }

        } catch (Exception e) {
            // Don't crash on our sanitizer - log and continue
            plugin.getLogger().at(Level.FINE).log(
                    "[InteractionManagerSanitizer] Error during validation: " + e.getMessage());
        }
    }

    private void discoverApi() {
        try {
            plugin.getLogger().at(Level.INFO).log("[InteractionManagerSanitizer] Discovering InteractionManager API...");

            // Find InteractionManager class
            interactionManagerClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionManager");

            // Find InteractionChain class
            Class<?> interactionChainClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionChain");

            // Find InteractionContext class
            Class<?> interactionContextClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionContext");

            // Find Ref class
            Class<?> refClass = Class.forName("com.hypixel.hytale.component.Ref");

            // Get ComponentType for InteractionManager via InteractionModule
            Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
            Method getModuleMethod = interactionModuleClass.getMethod("get");
            Object interactionModule = getModuleMethod.invoke(null);
            Method getComponentTypeMethod = interactionModuleClass.getMethod("getInteractionManagerComponent");
            interactionManagerType = (ComponentType) getComponentTypeMethod.invoke(interactionModule);

            // Get getChains() method
            getChainsMethod = interactionManagerClass.getMethod("getChains");

            // Get context field from InteractionChain
            contextField = interactionChainClass.getDeclaredField("context");
            contextField.setAccessible(true);

            // Get owningEntity field from InteractionContext
            owningEntityField = interactionContextClass.getDeclaredField("owningEntity");
            owningEntityField.setAccessible(true);

            // Get isValid() method from Ref
            isValidMethod = refClass.getMethod("isValid");

            initialized = true;
            plugin.getLogger().at(Level.INFO).log("[InteractionManagerSanitizer] API discovery successful!");
            plugin.getLogger().at(Level.INFO).log("  - InteractionManager ComponentType: " + interactionManagerType);
            plugin.getLogger().at(Level.INFO).log("  - getChains method: " + getChainsMethod);
            plugin.getLogger().at(Level.INFO).log("  - context field: " + contextField);
            plugin.getLogger().at(Level.INFO).log("  - owningEntity field: " + owningEntityField);
            plugin.getLogger().at(Level.INFO).log("  - isValid method: " + isValidMethod);

            // Timeout detection discovery (v1.3.3)
            discoverTimeoutApi(interactionChainClass);

        } catch (ClassNotFoundException e) {
            plugin.getLogger().at(Level.WARNING).log(
                    "[InteractionManagerSanitizer] API discovery failed - class not found: " + e.getMessage());
            apiDiscoveryFailed = true;
        } catch (NoSuchMethodException e) {
            plugin.getLogger().at(Level.WARNING).log(
                    "[InteractionManagerSanitizer] API discovery failed - method not found: " + e.getMessage());
            apiDiscoveryFailed = true;
        } catch (NoSuchFieldException e) {
            plugin.getLogger().at(Level.WARNING).log(
                    "[InteractionManagerSanitizer] API discovery failed - field not found: " + e.getMessage());
            apiDiscoveryFailed = true;
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                    "[InteractionManagerSanitizer] API discovery failed: " + e.getMessage());
            apiDiscoveryFailed = true;
        }
    }

    /**
     * Discover API for client timeout detection.
     * This is optional - if it fails, we still have the main validation.
     */
    private void discoverTimeoutApi(Class<?> interactionChainClass) {
        try {
            // Find CallState enum
            Class<?> callStateClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionChain$CallState");

            // Find callState field on InteractionChain
            String[] stateFieldNames = {"callState", "state", "currentState"};
            for (String fieldName : stateFieldNames) {
                try {
                    callStateField = interactionChainClass.getDeclaredField(fieldName);
                    callStateField.setAccessible(true);
                    plugin.getLogger().at(Level.INFO).log(
                            "[InteractionManagerSanitizer] Found callState field: " + fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    // Try next
                }
            }

            // Find WAITING_FOR_CLIENT_DATA enum value
            if (callStateClass.isEnum()) {
                for (Object enumValue : callStateClass.getEnumConstants()) {
                    String name = enumValue.toString();
                    if (name.contains("WAITING") && name.contains("CLIENT")) {
                        waitingForClientDataState = enumValue;
                        plugin.getLogger().at(Level.INFO).log(
                                "[InteractionManagerSanitizer] Found waiting state: " + name);
                        break;
                    }
                }
            }

            // Find cancel method on InteractionChain
            String[] cancelMethods = {"cancel", "stop", "abort"};
            for (String methodName : cancelMethods) {
                try {
                    cancelMethod = interactionChainClass.getMethod(methodName);
                    plugin.getLogger().at(Level.INFO).log(
                            "[InteractionManagerSanitizer] Found cancel method: " + methodName + "()");
                    break;
                } catch (NoSuchMethodException e) {
                    // Try next
                }
            }

            // Enable timeout detection if we found the state field
            if (callStateField != null && waitingForClientDataState != null) {
                timeoutDetectionEnabled = true;
                plugin.getLogger().at(Level.INFO).log(
                        "[InteractionManagerSanitizer] Client timeout detection ENABLED (" +
                        clientTimeoutMs + "ms threshold)");
            } else {
                plugin.getLogger().at(Level.INFO).log(
                        "[InteractionManagerSanitizer] Client timeout detection not available " +
                        "(callStateField=" + (callStateField != null) +
                        ", waitingState=" + (waitingForClientDataState != null) + ")");
            }

        } catch (ClassNotFoundException e) {
            plugin.getLogger().at(Level.INFO).log(
                    "[InteractionManagerSanitizer] CallState enum not found - timeout detection disabled");
        } catch (Exception e) {
            plugin.getLogger().at(Level.INFO).log(
                    "[InteractionManagerSanitizer] Timeout detection discovery failed: " + e.getMessage());
        }
    }

    /**
     * Check if a chain's CallState indicates it's waiting for client data.
     */
    private boolean isWaitingForClientData(Object callState) {
        if (callState == null || waitingForClientDataState == null) {
            return false;
        }
        // Compare enum values
        return callState == waitingForClientDataState ||
               callState.toString().contains("WAITING") && callState.toString().contains("CLIENT");
    }

    /**
     * Get status for the /interactionstatus command
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Initialized: ").append(initialized).append("\n");
        sb.append("API Discovery Failed: ").append(apiDiscoveryFailed).append("\n");
        sb.append("Timeout Detection: ").append(timeoutDetectionEnabled ? "ENABLED" : "disabled").append("\n");
        sb.append("Chains Validated: ").append(chainsValidated.get()).append("\n");
        sb.append("Chains Removed: ").append(chainsRemoved.get()).append("\n");
        sb.append("Crashes Prevented: ").append(crashesPrevented.get()).append("\n");
        sb.append("Timeouts Prevented: ").append(timeoutsPrevented.get());
        return sb.toString();
    }

    /**
     * Get the number of client timeouts prevented
     */
    public int getTimeoutsPrevented() {
        return timeoutsPrevented.get();
    }

    /**
     * Get the number of crashes prevented
     */
    public int getCrashesPrevented() {
        return crashesPrevented.get();
    }
}
