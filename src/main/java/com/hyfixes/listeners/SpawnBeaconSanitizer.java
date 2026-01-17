package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * FIX: SpawnBeacon Null RoleSpawnParameters Crash
 *
 * GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/4
 *
 * PROBLEM: Hytale's BeaconSpawnController.createRandomSpawnJob() at line 110 calls:
 *   spawn.getId()
 * where spawn (RoleSpawnParameters) can be null, causing a NullPointerException.
 *
 * Error: java.lang.NullPointerException: Cannot invoke
 *        "com.hypixel.hytale.server.spawning.assets.spawns.config.RoleSpawnParameters.getId()"
 *        because "spawn" is null
 *        at com.hypixel.hytale.server.spawning.controllers.BeaconSpawnController.createRandomSpawnJob(BeaconSpawnController.java:110)
 *        at com.hypixel.hytale.server.spawning.beacons.SpawnBeaconSystems$ControllerTick.createRandomSpawnJobs(SpawnBeaconSystems.java:536)
 *
 * SOLUTION: This system monitors spawn beacon entities each tick and validates
 * spawn parameters before Hytale's spawn controller tries to use them.
 * If a null spawn is found, we remove it from the spawn list to prevent the crash.
 *
 * NOTE: This uses reflection since we don't have direct API access to
 * BeaconSpawnController. The component discovery happens at runtime.
 */
public class SpawnBeaconSanitizer extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private boolean discoveryComplete = false;
    private boolean discoveryFailed = false;
    private int fixedCount = 0;
    private int checkedCount = 0;

    // Discovered at runtime via reflection
    private Class<?> beaconSpawnControllerClass = null;
    private Class<?> roleSpawnParametersClass = null;
    private Class<?> spawnBeaconClass = null;
    @SuppressWarnings("rawtypes")
    private ComponentType spawnBeaconType = null;
    @SuppressWarnings("rawtypes")
    private ComponentType beaconSpawnControllerType = null;

    // Fields for accessing spawn data
    private Field spawnsField = null;  // Field containing spawn configurations
    private Field roleSpawnsField = null;  // Alternative field name
    private Field spawnListField = null;  // Another alternative
    private Method getSpawnsMethod = null;  // Method to get spawns if no direct field

    public SpawnBeaconSanitizer(HyFixes plugin) {
        this.plugin = plugin;
        // Try to discover API early so getQuery() works at registration time
        discoverSpawnAPIEarly();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Query<EntityStore> getQuery() {
        // If we've discovered the spawn beacon type, use it
        if (beaconSpawnControllerType != null) {
            return beaconSpawnControllerType;
        }
        if (spawnBeaconType != null) {
            return spawnBeaconType;
        }
        // Fallback: This shouldn't happen if early discovery worked
        // But if it does, we need to return something valid
        // Return null - the system won't be registered properly
        plugin.getLogger().at(Level.WARNING).log(
            "[SpawnBeaconSanitizer] Could not discover spawn beacon component type - sanitizer disabled"
        );
        return null;
    }

    /**
     * Early discovery attempt - runs in constructor before getQuery() is called.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void discoverSpawnAPIEarly() {
        try {
            // Try to find and get component type for BeaconSpawnController
            String[] controllerClasses = {
                "com.hypixel.hytale.server.spawning.controllers.BeaconSpawnController",
                "com.hypixel.hytale.server.spawning.BeaconSpawnController",
                "com.hypixel.hytale.builtin.spawning.controllers.BeaconSpawnController"
            };

            for (String className : controllerClasses) {
                try {
                    beaconSpawnControllerClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException e) {
                    // Try next
                }
            }

            if (beaconSpawnControllerClass != null) {
                try {
                    Method getTypeMethod = beaconSpawnControllerClass.getMethod("getComponentType");
                    beaconSpawnControllerType = (ComponentType) getTypeMethod.invoke(null);
                } catch (Exception e) {
                    // Try TYPE field
                    try {
                        Field typeField = beaconSpawnControllerClass.getField("TYPE");
                        beaconSpawnControllerType = (ComponentType) typeField.get(null);
                    } catch (Exception ex) {
                        // Continue without it
                    }
                }
            }

            // Also try SpawnBeacon
            String[] beaconClasses = {
                "com.hypixel.hytale.server.spawning.beacons.SpawnBeacon",
                "com.hypixel.hytale.server.spawning.SpawnBeacon",
                "com.hypixel.hytale.builtin.spawning.SpawnBeacon"
            };

            for (String className : beaconClasses) {
                try {
                    spawnBeaconClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException e) {
                    // Try next
                }
            }

            if (spawnBeaconClass != null && spawnBeaconType == null) {
                try {
                    Method getTypeMethod = spawnBeaconClass.getMethod("getComponentType");
                    spawnBeaconType = (ComponentType) getTypeMethod.invoke(null);
                } catch (Exception e) {
                    try {
                        Field typeField = spawnBeaconClass.getField("TYPE");
                        spawnBeaconType = (ComponentType) typeField.get(null);
                    } catch (Exception ex) {
                        // Continue
                    }
                }
            }

            if (beaconSpawnControllerType != null || spawnBeaconType != null) {
                plugin.getLogger().at(Level.INFO).log(
                    "[SpawnBeaconSanitizer] Early discovery succeeded: Controller=" +
                    (beaconSpawnControllerType != null) + ", Beacon=" + (spawnBeaconType != null)
                );
                discoveryComplete = true;
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[SpawnBeaconSanitizer] Early discovery failed: " + e.getMessage()
            );
        }
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
                "[SpawnBeaconSanitizer] Active - monitoring spawn beacons for null spawn parameters"
            );
            loggedOnce = true;
        }

        // Try to discover spawn API on first run
        if (!discoveryComplete && !discoveryFailed) {
            discoverSpawnAPI();
        }

        // If discovery failed, we can only log and hope for the best
        if (discoveryFailed || beaconSpawnControllerType == null) {
            return;
        }

        try {
            checkedCount++;

            // Get the spawn controller component
            Object controller = chunk.getComponent(entityIndex, beaconSpawnControllerType);
            if (controller == null) {
                return;
            }

            // Validate spawn parameters
            validateSpawnParameters(controller);

        } catch (Exception e) {
            // Don't spam logs - only log first error
            if (fixedCount == 0 && checkedCount < 10) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[SpawnBeaconSanitizer] Error during validation: " + e.getMessage()
                );
            }
        }
    }

    /**
     * Discover the spawn API via reflection.
     * This runs once on first tick.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void discoverSpawnAPI() {
        discoveryComplete = true;

        plugin.getLogger().at(Level.INFO).log(
            "[SpawnBeaconSanitizer] Attempting to discover spawn beacon API..."
        );

        try {
            // Step 1: Find BeaconSpawnController class
            String[] controllerClasses = {
                "com.hypixel.hytale.server.spawning.controllers.BeaconSpawnController",
                "com.hypixel.hytale.server.spawning.BeaconSpawnController",
                "com.hypixel.hytale.builtin.spawning.controllers.BeaconSpawnController"
            };

            for (String className : controllerClasses) {
                try {
                    beaconSpawnControllerClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log(
                        "[SpawnBeaconSanitizer] Found BeaconSpawnController: " + className
                    );
                    break;
                } catch (ClassNotFoundException e) {
                    // Try next
                }
            }

            // Step 2: Find RoleSpawnParameters class
            String[] paramClasses = {
                "com.hypixel.hytale.server.spawning.assets.spawns.config.RoleSpawnParameters",
                "com.hypixel.hytale.server.spawning.config.RoleSpawnParameters",
                "com.hypixel.hytale.builtin.spawning.config.RoleSpawnParameters"
            };

            for (String className : paramClasses) {
                try {
                    roleSpawnParametersClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log(
                        "[SpawnBeaconSanitizer] Found RoleSpawnParameters: " + className
                    );
                    break;
                } catch (ClassNotFoundException e) {
                    // Try next
                }
            }

            // Step 3: Find SpawnBeacon component class
            String[] beaconClasses = {
                "com.hypixel.hytale.server.spawning.beacons.SpawnBeacon",
                "com.hypixel.hytale.server.spawning.SpawnBeacon",
                "com.hypixel.hytale.builtin.spawning.SpawnBeacon"
            };

            for (String className : beaconClasses) {
                try {
                    spawnBeaconClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log(
                        "[SpawnBeaconSanitizer] Found SpawnBeacon: " + className
                    );
                    break;
                } catch (ClassNotFoundException e) {
                    // Try next
                }
            }

            // Step 4: Get component types
            if (beaconSpawnControllerClass != null) {
                try {
                    Method getTypeMethod = beaconSpawnControllerClass.getMethod("getComponentType");
                    beaconSpawnControllerType = (ComponentType) getTypeMethod.invoke(null);
                    plugin.getLogger().at(Level.INFO).log(
                        "[SpawnBeaconSanitizer] Got BeaconSpawnController component type"
                    );
                } catch (NoSuchMethodException e) {
                    plugin.getLogger().at(Level.WARNING).log(
                        "[SpawnBeaconSanitizer] BeaconSpawnController has no getComponentType() - trying fields"
                    );
                    // Try to find TYPE field
                    try {
                        Field typeField = beaconSpawnControllerClass.getField("TYPE");
                        beaconSpawnControllerType = (ComponentType) typeField.get(null);
                        plugin.getLogger().at(Level.INFO).log(
                            "[SpawnBeaconSanitizer] Got BeaconSpawnController component type from TYPE field"
                        );
                    } catch (NoSuchFieldException ex) {
                        // Continue
                    }
                }

                // Log fields for debugging
                logClassFields(beaconSpawnControllerClass);
                logClassMethods(beaconSpawnControllerClass);

                // Step 5: Find spawns field/method in BeaconSpawnController
                String[] spawnFieldNames = {
                    "spawns", "roleSpawns", "spawnList", "spawnParameters",
                    "roleSpawnParameters", "spawnConfigs", "spawnEntries"
                };

                for (String fieldName : spawnFieldNames) {
                    try {
                        spawnsField = beaconSpawnControllerClass.getDeclaredField(fieldName);
                        spawnsField.setAccessible(true);
                        plugin.getLogger().at(Level.INFO).log(
                            "[SpawnBeaconSanitizer] Found spawns field: " + fieldName +
                            " (type: " + spawnsField.getType().getSimpleName() + ")"
                        );
                        break;
                    } catch (NoSuchFieldException e) {
                        // Try next
                    }
                }

                // Try getter methods
                String[] spawnMethodNames = {
                    "getSpawns", "getRoleSpawns", "getSpawnList", "getSpawnParameters"
                };

                for (String methodName : spawnMethodNames) {
                    try {
                        getSpawnsMethod = beaconSpawnControllerClass.getMethod(methodName);
                        plugin.getLogger().at(Level.INFO).log(
                            "[SpawnBeaconSanitizer] Found spawns method: " + methodName + "()"
                        );
                        break;
                    } catch (NoSuchMethodException e) {
                        // Try next
                    }
                }
            }

            if (spawnBeaconClass != null) {
                try {
                    Method getTypeMethod = spawnBeaconClass.getMethod("getComponentType");
                    spawnBeaconType = (ComponentType) getTypeMethod.invoke(null);
                    plugin.getLogger().at(Level.INFO).log(
                        "[SpawnBeaconSanitizer] Got SpawnBeacon component type"
                    );
                } catch (NoSuchMethodException e) {
                    // Try TYPE field
                    try {
                        Field typeField = spawnBeaconClass.getField("TYPE");
                        spawnBeaconType = (ComponentType) typeField.get(null);
                    } catch (NoSuchFieldException ex) {
                        // Continue
                    }
                }
            }

            // Summary
            if (beaconSpawnControllerClass == null) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[SpawnBeaconSanitizer] Could not find BeaconSpawnController class. " +
                    "Sanitizer will be inactive."
                );
                discoveryFailed = true;
            } else {
                plugin.getLogger().at(Level.INFO).log(
                    "[SpawnBeaconSanitizer] API Discovery complete: " +
                    "Controller=" + (beaconSpawnControllerClass != null ? "YES" : "NO") +
                    ", SpawnParams=" + (roleSpawnParametersClass != null ? "YES" : "NO") +
                    ", ComponentType=" + (beaconSpawnControllerType != null ? "YES" : "NO") +
                    ", SpawnsField=" + (spawnsField != null ? "YES" : "NO")
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[SpawnBeaconSanitizer] Discovery failed: " + e.getMessage()
            );
            e.printStackTrace();
            discoveryFailed = true;
        }
    }

    /**
     * Log fields of a class for debugging/discovery.
     */
    private void logClassFields(Class<?> clazz) {
        plugin.getLogger().at(Level.INFO).log(
            "[SpawnBeaconSanitizer] Fields on " + clazz.getSimpleName() + ":"
        );
        for (Field f : clazz.getDeclaredFields()) {
            plugin.getLogger().at(Level.INFO).log(
                "  - " + f.getName() + ": " + f.getType().getSimpleName()
            );
        }
    }

    /**
     * Log methods of a class for debugging/discovery.
     */
    private void logClassMethods(Class<?> clazz) {
        plugin.getLogger().at(Level.INFO).log(
            "[SpawnBeaconSanitizer] Methods on " + clazz.getSimpleName() + ":"
        );
        for (Method m : clazz.getMethods()) {
            String name = m.getName();
            if (name.toLowerCase().contains("spawn") ||
                name.toLowerCase().contains("role") ||
                name.toLowerCase().contains("get") ||
                name.toLowerCase().contains("set")) {
                plugin.getLogger().at(Level.INFO).log(
                    "  - " + name + "(" + getParamTypes(m) + ") -> " + m.getReturnType().getSimpleName()
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
     * Validate spawn parameters in a BeaconSpawnController and remove nulls.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void validateSpawnParameters(Object controller) {
        try {
            Object spawns = null;

            // Try to get spawns via field
            if (spawnsField != null) {
                spawns = spawnsField.get(controller);
            } else if (getSpawnsMethod != null) {
                spawns = getSpawnsMethod.invoke(controller);
            }

            if (spawns == null) {
                // Try to find any field that looks like a collection of spawns
                for (Field field : controller.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(controller);
                    if (value instanceof Collection || value instanceof Map ||
                        (value != null && value.getClass().isArray())) {
                        spawns = value;
                        break;
                    }
                }
            }

            if (spawns == null) {
                return;
            }

            // Check for nulls and remove them
            int removed = removeNullSpawns(spawns);
            if (removed > 0) {
                fixedCount += removed;
                plugin.getLogger().at(Level.WARNING).log(
                    "[SpawnBeaconSanitizer] Removed " + removed +
                    " null spawn parameter(s) to prevent crash (total fixed: " + fixedCount + ")"
                );
            }

        } catch (Exception e) {
            // Silently fail - we're just trying to prevent crashes
        }
    }

    /**
     * Remove null entries from a spawns collection.
     * Returns the number of entries removed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int removeNullSpawns(Object spawns) {
        int removed = 0;

        try {
            if (spawns instanceof List) {
                List list = (List) spawns;
                Iterator iter = list.iterator();
                while (iter.hasNext()) {
                    Object spawn = iter.next();
                    if (spawn == null) {
                        iter.remove();
                        removed++;
                    } else if (isInvalidSpawn(spawn)) {
                        iter.remove();
                        removed++;
                    }
                }
            } else if (spawns instanceof Collection) {
                Collection coll = (Collection) spawns;
                Iterator iter = coll.iterator();
                while (iter.hasNext()) {
                    Object spawn = iter.next();
                    if (spawn == null) {
                        iter.remove();
                        removed++;
                    } else if (isInvalidSpawn(spawn)) {
                        iter.remove();
                        removed++;
                    }
                }
            } else if (spawns instanceof Map) {
                Map map = (Map) spawns;
                Iterator<Map.Entry> iter = map.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry entry = iter.next();
                    if (entry.getValue() == null) {
                        iter.remove();
                        removed++;
                    } else if (isInvalidSpawn(entry.getValue())) {
                        iter.remove();
                        removed++;
                    }
                }
            } else if (spawns.getClass().isArray()) {
                // Can't remove from arrays, but we can null-check and log
                Object[] arr = (Object[]) spawns;
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] == null) {
                        plugin.getLogger().at(Level.WARNING).log(
                            "[SpawnBeaconSanitizer] Found null spawn at array index " + i +
                            " - cannot remove from array, crash may occur"
                        );
                    }
                }
            }
        } catch (Exception e) {
            // Log but don't fail
            plugin.getLogger().at(Level.WARNING).log(
                "[SpawnBeaconSanitizer] Error removing null spawns: " + e.getMessage()
            );
        }

        return removed;
    }

    /**
     * Check if a spawn object is invalid (has null required fields).
     */
    private boolean isInvalidSpawn(Object spawn) {
        if (spawn == null) return true;

        try {
            // Check if getId() would return null or throw
            Method getIdMethod = spawn.getClass().getMethod("getId");
            Object id = getIdMethod.invoke(spawn);
            if (id == null) {
                return true;
            }
        } catch (NoSuchMethodException e) {
            // No getId method, can't validate this way
        } catch (Exception e) {
            // getId() threw an exception, spawn is invalid
            return true;
        }

        return false;
    }

    /**
     * Get the count of fixed spawn issues.
     */
    public int getFixedCount() {
        return fixedCount;
    }

    /**
     * Get status for admin commands.
     */
    public String getStatus() {
        return String.format(
            "SpawnBeaconSanitizer Status:\n" +
            "  Discovery Complete: %s\n" +
            "  Discovery Failed: %s\n" +
            "  BeaconSpawnController Found: %s\n" +
            "  RoleSpawnParameters Found: %s\n" +
            "  Component Type Found: %s\n" +
            "  Spawns Field Found: %s\n" +
            "  Beacons Checked: %d\n" +
            "  Null Spawns Removed: %d",
            discoveryComplete,
            discoveryFailed,
            beaconSpawnControllerClass != null ? beaconSpawnControllerClass.getSimpleName() : "None",
            roleSpawnParametersClass != null ? roleSpawnParametersClass.getSimpleName() : "None",
            beaconSpawnControllerType != null,
            spawnsField != null || getSpawnsMethod != null,
            checkedCount,
            fixedCount
        );
    }
}
