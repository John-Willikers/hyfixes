package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * FIX: CraftingManager Bench Already Set Crash
 *
 * PROBLEM: When a player opens a processing bench (campfire, crafting table, etc.)
 * while CraftingManager already has a bench reference set, it throws:
 *   java.lang.IllegalArgumentException: Bench blockType is already set! Must be cleared (close UI).
 *   at CraftingManager.setBench(CraftingManager.java:157)
 *
 * This can happen when:
 * 1. Player's previous bench interaction didn't properly clean up
 * 2. Player rapidly opens multiple benches
 * 3. Race condition during bench window opening
 *
 * The error kicks the player from the server.
 *
 * SOLUTION: This system monitors Player entities each tick and clears any stale
 * bench references in CraftingManager when the player doesn't have a bench window
 * open. This prevents the IllegalArgumentException when opening a new bench.
 *
 * Uses reflection since CraftingManager API may not be directly accessible.
 */
public class CraftingManagerSanitizer extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private boolean discoveryComplete = false;
    private boolean discoveryFailed = false;
    private int fixedCount = 0;

    // Discovered via reflection
    private Class<?> craftingManagerClass = null;
    @SuppressWarnings("rawtypes")
    private ComponentType craftingManagerType = null;
    private Method clearBenchMethod = null;
    private Method getBenchMethod = null;
    private Field benchBlockTypeField = null;

    public CraftingManagerSanitizer(HyFixes plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
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
                "[CraftingManagerSanitizer] Active - monitoring for stale bench references"
            );
            loggedOnce = true;
        }

        // Discover API on first run
        if (!discoveryComplete && !discoveryFailed) {
            discoverCraftingManagerAPI();
        }

        if (discoveryFailed || craftingManagerType == null) {
            return;
        }

        try {
            // Get the CraftingManager component from the player
            Object craftingManager = chunk.getComponent(entityIndex, craftingManagerType);
            if (craftingManager == null) {
                return;
            }

            // Check if bench is currently set
            if (isBenchSet(craftingManager)) {
                // Check if player actually has a bench window open
                Player player = chunk.getComponent(entityIndex, Player.getComponentType());
                if (player != null && !hasBenchWindowOpen(player)) {
                    // Stale bench reference - clear it!
                    clearBench(craftingManager);
                    fixedCount++;
                    plugin.getLogger().at(Level.WARNING).log(
                        "[CraftingManagerSanitizer] Prevented crash #" + fixedCount +
                        " - cleared stale bench reference for player"
                    );
                }
            }
        } catch (Exception e) {
            // Silently fail - don't spam logs
            if (fixedCount == 0) {
                plugin.getLogger().at(Level.FINE).log(
                    "[CraftingManagerSanitizer] Error during check: " + e.getMessage()
                );
            }
        }
    }

    /**
     * Discover the CraftingManager API via reflection.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void discoverCraftingManagerAPI() {
        discoveryComplete = true;

        plugin.getLogger().at(Level.INFO).log(
            "[CraftingManagerSanitizer] Discovering CraftingManager API..."
        );

        try {
            // Find CraftingManager class
            String[] possibleClasses = {
                "com.hypixel.hytale.builtin.crafting.component.CraftingManager",
                "com.hypixel.hytale.server.core.modules.crafting.CraftingManager",
                "com.hypixel.hytale.builtin.crafting.CraftingManager"
            };

            for (String className : possibleClasses) {
                try {
                    craftingManagerClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log(
                        "[CraftingManagerSanitizer] Found CraftingManager: " + className
                    );
                    break;
                } catch (ClassNotFoundException e) {
                    // Try next
                }
            }

            if (craftingManagerClass == null) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[CraftingManagerSanitizer] Could not find CraftingManager class"
                );
                discoveryFailed = true;
                return;
            }

            // Get component type
            try {
                Method getTypeMethod = craftingManagerClass.getMethod("getComponentType");
                craftingManagerType = (ComponentType) getTypeMethod.invoke(null);
                plugin.getLogger().at(Level.INFO).log(
                    "[CraftingManagerSanitizer] Got CraftingManager component type"
                );
            } catch (NoSuchMethodException e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[CraftingManagerSanitizer] CraftingManager has no getComponentType() method"
                );
                discoveryFailed = true;
                return;
            }

            // Find clearBench or similar method
            String[] clearMethods = {"clearBench", "clear", "reset", "closeBench"};
            for (String methodName : clearMethods) {
                try {
                    clearBenchMethod = craftingManagerClass.getMethod(methodName);
                    plugin.getLogger().at(Level.INFO).log(
                        "[CraftingManagerSanitizer] Found clear method: " + methodName + "()"
                    );
                    break;
                } catch (NoSuchMethodException e) {
                    // Try next
                }
            }

            // Find getBench or benchBlockType field to check if set
            String[] getterMethods = {"getBench", "getBenchBlockType", "getCurrentBench"};
            for (String methodName : getterMethods) {
                try {
                    getBenchMethod = craftingManagerClass.getMethod(methodName);
                    plugin.getLogger().at(Level.INFO).log(
                        "[CraftingManagerSanitizer] Found getter method: " + methodName + "()"
                    );
                    break;
                } catch (NoSuchMethodException e) {
                    // Try next
                }
            }

            // Also look for benchBlockType field directly
            String[] fieldNames = {"benchBlockType", "bench", "currentBench", "blockType"};
            for (String fieldName : fieldNames) {
                try {
                    benchBlockTypeField = craftingManagerClass.getDeclaredField(fieldName);
                    benchBlockTypeField.setAccessible(true);
                    plugin.getLogger().at(Level.INFO).log(
                        "[CraftingManagerSanitizer] Found field: " + fieldName
                    );
                    break;
                } catch (NoSuchFieldException e) {
                    // Try next
                }
            }

            if (clearBenchMethod == null && benchBlockTypeField == null) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[CraftingManagerSanitizer] Could not find way to clear bench state. " +
                    "Will monitor only."
                );
            }

            plugin.getLogger().at(Level.INFO).log(
                "[CraftingManagerSanitizer] API discovery complete"
            );

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[CraftingManagerSanitizer] API discovery failed: " + e.getMessage()
            );
            discoveryFailed = true;
        }
    }

    /**
     * Check if the CraftingManager has a bench set.
     */
    private boolean isBenchSet(Object craftingManager) {
        try {
            if (getBenchMethod != null) {
                Object bench = getBenchMethod.invoke(craftingManager);
                return bench != null;
            }
            if (benchBlockTypeField != null) {
                Object benchType = benchBlockTypeField.get(craftingManager);
                return benchType != null;
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Clear the bench reference in CraftingManager.
     */
    private void clearBench(Object craftingManager) {
        try {
            if (clearBenchMethod != null) {
                clearBenchMethod.invoke(craftingManager);
                return;
            }
            if (benchBlockTypeField != null) {
                benchBlockTypeField.set(craftingManager, null);
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[CraftingManagerSanitizer] Failed to clear bench: " + e.getMessage()
            );
        }
    }

    /**
     * Check if player has a bench window open.
     * Returns TRUE if we can't determine - this is safer because:
     * - If we return false when a window IS open, we break crafting
     * - If we return true when no window is open, we just miss clearing a stale ref
     *   (and Hytale will throw an error, but that's better than breaking ALL crafting)
     */
    private boolean hasBenchWindowOpen(Player player) {
        try {
            // Try to check via WindowManager or PageManager
            Object windowManager = getPlayerWindowManager(player);
            if (windowManager == null) {
                // Can't find window manager - assume window IS open to be safe
                // This means we won't clear the bench, which is better than
                // accidentally clearing it while player is crafting
                return true;
            }

            // Check if any bench window is open
            Method hasOpenWindowsMethod = findMethod(windowManager.getClass(),
                "hasOpenWindows", "hasWindows", "getOpenWindowCount", "isOpen");
            if (hasOpenWindowsMethod != null) {
                Object result = hasOpenWindowsMethod.invoke(windowManager);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
                if (result instanceof Integer) {
                    return ((Integer) result) > 0;
                }
            }

        } catch (Exception e) {
            // If we can't check, assume window IS open (safer - don't break crafting)
        }
        // Default to true - better to miss clearing a stale ref than break crafting
        return true;
    }

    /**
     * Get the WindowManager from a Player.
     */
    private Object getPlayerWindowManager(Player player) {
        try {
            String[] methodNames = {"getWindowManager", "windowManager", "getWindows"};
            for (String methodName : methodNames) {
                try {
                    Method method = Player.class.getMethod(methodName);
                    return method.invoke(player);
                } catch (NoSuchMethodException e) {
                    // Try next
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Find a method by trying multiple names.
     */
    private Method findMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                // Try next
            }
        }
        return null;
    }

    /**
     * Get the count of fixed crashes.
     */
    public int getFixedCount() {
        return fixedCount;
    }

    /**
     * Get status for admin commands.
     */
    public String getStatus() {
        return String.format(
            "CraftingManagerSanitizer Status:\n" +
            "  Discovery Complete: %s\n" +
            "  Discovery Failed: %s\n" +
            "  CraftingManager Found: %s\n" +
            "  Clear Method Found: %s\n" +
            "  Bench Field Found: %s\n" +
            "  Crashes Prevented: %d",
            discoveryComplete,
            discoveryFailed,
            craftingManagerClass != null ? craftingManagerClass.getSimpleName() : "No",
            clearBenchMethod != null,
            benchBlockTypeField != null,
            fixedCount
        );
    }
}
