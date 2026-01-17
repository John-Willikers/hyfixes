package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
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

    private boolean initialized = false;
    private boolean apiDiscoveryFailed = false;

    // Statistics
    private final AtomicInteger chainsValidated = new AtomicInteger(0);
    private final AtomicInteger chainsRemoved = new AtomicInteger(0);
    private final AtomicInteger crashesPrevented = new AtomicInteger(0);

    public InteractionManagerSanitizer(HyFixes plugin) {
        this.plugin = plugin;
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

            for (Map.Entry<Integer, Object> entry : chains.entrySet()) {
                chainsValidated.incrementAndGet();
                Object chain = entry.getValue();

                if (chain == null) {
                    chainsToRemove.add(entry.getKey());
                    continue;
                }

                // Check if context is null
                Object context = contextField.get(chain);
                if (context == null) {
                    chainsToRemove.add(entry.getKey());
                    plugin.getLogger().at(Level.WARNING).log(
                            "[InteractionManagerSanitizer] Found chain with null context, removing to prevent crash");
                    continue;
                }

                // Check if owningEntity ref is null or invalid
                Object owningEntityRef = owningEntityField.get(context);
                if (owningEntityRef == null) {
                    chainsToRemove.add(entry.getKey());
                    plugin.getLogger().at(Level.WARNING).log(
                            "[InteractionManagerSanitizer] Found chain with null owningEntity ref, removing to prevent crash");
                    continue;
                }

                // Check if the ref is valid
                if (isValidMethod != null) {
                    Boolean isValid = (Boolean) isValidMethod.invoke(owningEntityRef);
                    if (!isValid) {
                        chainsToRemove.add(entry.getKey());
                        plugin.getLogger().at(Level.WARNING).log(
                                "[InteractionManagerSanitizer] Found chain with invalid owningEntity ref, removing to prevent crash");
                    }
                }
            }

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
     * Get status for the /interactionstatus command
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Initialized: ").append(initialized).append("\n");
        sb.append("API Discovery Failed: ").append(apiDiscoveryFailed).append("\n");
        sb.append("Chains Validated: ").append(chainsValidated.get()).append("\n");
        sb.append("Chains Removed: ").append(chainsRemoved.get()).append("\n");
        sb.append("Crashes Prevented: ").append(crashesPrevented.get());
        return sb.toString();
    }

    /**
     * Get the number of crashes prevented
     */
    public int getCrashesPrevented() {
        return crashesPrevented.get();
    }
}
