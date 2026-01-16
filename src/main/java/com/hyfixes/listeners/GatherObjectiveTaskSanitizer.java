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
import java.util.logging.Level;

/**
 * FIX: GatherObjectiveTask Null Ref Crash
 *
 * PROBLEM: Hytale's GatherObjectiveTask.lambda$setup0$1() at line 65 calls:
 *   store.getComponent(ref, ...)
 * where ref can be null if the target entity was destroyed.
 *
 * Error: java.lang.NullPointerException: Cannot invoke
 *        "com.hypixel.hytale.component.Ref.validate()" because "ref" is null
 *        at com.hypixel.hytale.component.Store.__internal_getComponent(Store.java:1222)
 *        at com.hypixel.hytale.builtin.adventure.objectives.task.GatherObjectiveTask.lambda$setup0$1(GatherObjectiveTask.java:65)
 *
 * SOLUTION: This system monitors player objectives each tick and validates
 * any Refs stored in objective tasks. If a Ref is null or invalid, we attempt
 * to clear/fail the objective before the crash can occur.
 *
 * NOTE: This uses reflection since we don't have direct API access to
 * GatherObjectiveTask. The component discovery happens at runtime.
 */
public class GatherObjectiveTaskSanitizer extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private boolean discoveryComplete = false;
    private boolean discoveryFailed = false;
    private int fixedCount = 0;

    // Discovered at runtime via reflection
    private Class<?> objectiveComponentClass = null;
    @SuppressWarnings("rawtypes")
    private ComponentType objectiveComponentType = null;
    private Method getActiveObjectivesMethod = null;
    private Method getTasksMethod = null;

    public GatherObjectiveTaskSanitizer(HyFixes plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Query for Player entities since objectives are attached to players
        return Player.getComponentType();
    }

    @Override
    public void tick(
            float deltaTime,
            int entityIndex,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[GatherObjectiveTaskSanitizer] Active - monitoring player objectives for corrupted refs"
            );
            loggedOnce = true;
        }

        // Try to discover objective API on first run
        if (!discoveryComplete && !discoveryFailed) {
            discoverObjectiveAPI();
        }

        // If discovery failed, we can only log and hope for the best
        if (discoveryFailed) {
            return;
        }

        // Skip if we couldn't find the objective component
        if (objectiveComponentType == null) {
            return;
        }

        try {
            // Get the player entity
            Player player = chunk.getComponent(entityIndex, Player.getComponentType());
            if (player == null) {
                return;
            }

            // Try to get objective component from player
            validatePlayerObjectives(player, store, chunk, entityIndex);

        } catch (Exception e) {
            // Don't spam logs - only log first error
            if (fixedCount == 0) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[GatherObjectiveTaskSanitizer] Error during validation: " + e.getMessage()
                );
            }
        }
    }

    /**
     * Discover the objective API via reflection.
     * This runs once on first tick.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void discoverObjectiveAPI() {
        discoveryComplete = true;

        plugin.getLogger().at(Level.INFO).log(
            "[GatherObjectiveTaskSanitizer] Attempting to discover objective API..."
        );

        try {
            // Try to find ObjectiveComponent or similar
            String[] possibleClasses = {
                "com.hypixel.hytale.builtin.adventure.objectives.ObjectiveComponent",
                "com.hypixel.hytale.builtin.adventure.objectives.PlayerObjectives",
                "com.hypixel.hytale.builtin.adventure.objectives.ObjectiveManager",
                "com.hypixel.hytale.server.core.modules.adventure.ObjectiveComponent"
            };

            for (String className : possibleClasses) {
                try {
                    objectiveComponentClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log(
                        "[GatherObjectiveTaskSanitizer] Found class: " + className
                    );

                    // Try to get component type
                    try {
                        Method getTypeMethod = objectiveComponentClass.getMethod("getComponentType");
                        objectiveComponentType = (ComponentType) getTypeMethod.invoke(null);
                        plugin.getLogger().at(Level.INFO).log(
                            "[GatherObjectiveTaskSanitizer] Got component type from: " + className
                        );
                    } catch (NoSuchMethodException e) {
                        // Not a component, keep searching
                    }

                    // Log available methods for debugging
                    logClassMethods(objectiveComponentClass);
                    break;

                } catch (ClassNotFoundException e) {
                    // Try next class
                }
            }

            if (objectiveComponentClass == null) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[GatherObjectiveTaskSanitizer] Could not find objective component class. " +
                    "Will monitor Player entities only."
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[GatherObjectiveTaskSanitizer] Discovery failed: " + e.getMessage()
            );
            discoveryFailed = true;
        }
    }

    /**
     * Log methods of a class for debugging/discovery.
     */
    private void logClassMethods(Class<?> clazz) {
        plugin.getLogger().at(Level.INFO).log(
            "[GatherObjectiveTaskSanitizer] Methods on " + clazz.getSimpleName() + ":"
        );
        for (Method m : clazz.getMethods()) {
            String name = m.getName();
            if (name.toLowerCase().contains("objective") ||
                name.toLowerCase().contains("task") ||
                name.toLowerCase().contains("ref") ||
                name.toLowerCase().contains("active")) {
                plugin.getLogger().at(Level.INFO).log(
                    "  - " + name + "(" + getParamTypes(m) + ")"
                );
            }
        }
    }

    private String getParamTypes(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }

    /**
     * Validate player objectives and check for null refs.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void validatePlayerObjectives(
            Player player,
            Store<EntityStore> store,
            ArchetypeChunk<EntityStore> chunk,
            int entityIndex
    ) {
        if (objectiveComponentType == null || objectiveComponentClass == null) {
            return;
        }

        try {
            // Try to get the objective component
            Object objectiveComponent = chunk.getComponent(entityIndex, objectiveComponentType);
            if (objectiveComponent == null) {
                return;
            }

            // Use reflection to check for null refs in the objective
            validateObjectiveRefs(objectiveComponent);

        } catch (Exception e) {
            // Silently fail - we're just trying to prevent crashes
        }
    }

    /**
     * Validate refs in an objective component via reflection.
     */
    private void validateObjectiveRefs(Object objectiveComponent) {
        try {
            // Look for fields that might contain Refs
            for (Field field : objectiveComponent.getClass().getDeclaredFields()) {
                if (Ref.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object refValue = field.get(objectiveComponent);

                    if (refValue == null) {
                        plugin.getLogger().at(Level.WARNING).log(
                            "[GatherObjectiveTaskSanitizer] Found null ref in objective field: " +
                            field.getName() + " - attempting to clear objective"
                        );
                        fixedCount++;
                        // Try to clear/cancel the objective
                        tryToClearObjective(objectiveComponent);
                    } else if (refValue instanceof Ref<?>) {
                        Ref<?> ref = (Ref<?>) refValue;
                        if (!ref.isValid()) {
                            plugin.getLogger().at(Level.WARNING).log(
                                "[GatherObjectiveTaskSanitizer] Found invalid ref in objective field: " +
                                field.getName() + " - attempting to clear objective"
                            );
                            fixedCount++;
                            tryToClearObjective(objectiveComponent);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail
        }
    }

    /**
     * Try to clear/cancel an objective with invalid refs.
     */
    private void tryToClearObjective(Object objectiveComponent) {
        try {
            // Try common methods to cancel/clear
            String[] methodNames = {"cancel", "clear", "fail", "complete", "setCompleted", "setCancelled"};

            for (String methodName : methodNames) {
                try {
                    Method method = objectiveComponent.getClass().getMethod(methodName);
                    method.invoke(objectiveComponent);
                    plugin.getLogger().at(Level.INFO).log(
                        "[GatherObjectiveTaskSanitizer] Called " + methodName + "() to prevent crash"
                    );
                    return;
                } catch (NoSuchMethodException e) {
                    // Try next method
                }
            }

            // Try with boolean parameter
            for (String methodName : methodNames) {
                try {
                    Method method = objectiveComponent.getClass().getMethod(methodName, boolean.class);
                    method.invoke(objectiveComponent, true);
                    plugin.getLogger().at(Level.INFO).log(
                        "[GatherObjectiveTaskSanitizer] Called " + methodName + "(true) to prevent crash"
                    );
                    return;
                } catch (NoSuchMethodException e) {
                    // Try next method
                }
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[GatherObjectiveTaskSanitizer] Could not clear objective: " + e.getMessage()
            );
        }
    }

    /**
     * Get the count of fixed objectives.
     */
    public int getFixedCount() {
        return fixedCount;
    }

    /**
     * Get status for admin commands.
     */
    public String getStatus() {
        return String.format(
            "GatherObjectiveTaskSanitizer Status:\n" +
            "  Discovery Complete: %s\n" +
            "  Discovery Failed: %s\n" +
            "  Objective Class Found: %s\n" +
            "  Component Type Found: %s\n" +
            "  Objectives Fixed: %d",
            discoveryComplete,
            discoveryFailed,
            objectiveComponentClass != null ? objectiveComponentClass.getSimpleName() : "None",
            objectiveComponentType != null,
            fixedCount
        );
    }
}
