package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.Message;
import com.hyfixes.util.ChatColorUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * CleanInteractionsCommand - Removes orphaned interaction zones from deleted blocks
 *
 * GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/11
 *
 * The Bug:
 * When teleporters (or other interactable blocks) are removed while the TrackedPlacement
 * component fails, the block gets removed but the interaction zone may remain.
 * This causes "Press F to interact" to appear at locations where blocks no longer exist.
 *
 * The Fix:
 * This command allows admins to scan for and remove orphaned interaction chains.
 *
 * Usage:
 *   /cleaninteractions         - Scan for orphaned interactions
 *   /cleaninteractions scan    - Same as above
 *   /cleaninteractions clean   - Remove all found orphaned interactions
 */
public class CleanInteractionsCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;

    // Reflection fields - discovered at runtime
    private boolean initialized = false;
    private boolean initFailed = false;

    // InteractionManager access
    @SuppressWarnings("rawtypes")
    private ComponentType interactionManagerType = null;
    private Method getChainsMethod = null;
    private Field contextField = null;
    private Field targetEntityRefField = null;

    public CleanInteractionsCommand(HyFixes plugin) {
        super("cleaninteractions", "hyfixes.command.cleaninteractions.desc");
        this.plugin = plugin;
        addAliases("ci", "cleanint", "fixinteractions");
        setAllowsExtraArguments(true);  // Allow manual argument parsing
    }

    @Override
    protected boolean canGeneratePermission() {
        return true;
    }

    @Override
    protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> ref,
            PlayerRef playerRef,
            World world
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        // Parse arguments
        String inputString = context.getInputString();
        String[] parts = inputString.trim().split("\\s+");
        String[] args = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        String action = args.length > 0 ? args[0].toLowerCase() : "scan";

        // Initialize reflection API if needed
        if (!initialized && !initFailed) {
            initializeApi(player);
        }

        if (initFailed) {
            sendMessage(player, "&c[HyFixes] CleanInteractions API not available - see server logs");
            return;
        }

        switch (action) {
            case "scan":
                scanForOrphans(player, store, ref, world, false);
                break;
            case "clean":
            case "remove":
            case "fix":
                scanForOrphans(player, store, ref, world, true);
                break;
            case "explore":
            case "dump":
                exploreInteractionModule(player, world);
                break;
            case "zones":
                exploreInteractionZones(player, world);
                break;
            case "block":
                // Check block interaction state at specific position
                // Usage: /ci block x y z
                if (args.length >= 4) {
                    try {
                        int bx = Integer.parseInt(args[1]);
                        int by = Integer.parseInt(args[2]);
                        int bz = Integer.parseInt(args[3]);
                        checkBlockInteraction(player, world, bx, by, bz);
                    } catch (NumberFormatException e) {
                        sendMessage(player, "&cUsage: /ci block <x> <y> <z>");
                    }
                } else {
                    sendMessage(player, "&cUsage: /ci block <x> <y> <z>");
                }
                break;
            case "tracked":
            case "placement":
                // Explore TrackedPlacement at specific position
                // Usage: /ci tracked x y z [clean]
                if (args.length >= 4) {
                    try {
                        int tx = Integer.parseInt(args[1]);
                        int ty = Integer.parseInt(args[2]);
                        int tz = Integer.parseInt(args[3]);
                        boolean cleanTracked = args.length >= 5 && args[4].equalsIgnoreCase("clean");
                        exploreTrackedPlacement(player, world, tx, ty, tz, cleanTracked);
                    } catch (NumberFormatException e) {
                        sendMessage(player, "&cUsage: /ci tracked <x> <y> <z> [clean]");
                    }
                } else {
                    sendMessage(player, "&cUsage: /ci tracked <x> <y> <z> [clean]");
                }
                break;
            case "entities":
            case "near":
                // Find ALL entities near a position
                // Usage: /ci entities x y z [radius]
                if (args.length >= 4) {
                    try {
                        double ex = Double.parseDouble(args[1]);
                        double ey = Double.parseDouble(args[2]);
                        double ez = Double.parseDouble(args[3]);
                        double radius = args.length >= 5 ? Double.parseDouble(args[4]) : 3.0;
                        findAllEntitiesNear(player, world, ex, ey, ez, radius);
                    } catch (NumberFormatException e) {
                        sendMessage(player, "&cUsage: /ci entities <x> <y> <z> [radius]");
                    }
                } else {
                    sendMessage(player, "&cUsage: /ci entities <x> <y> <z> [radius]");
                }
                break;
            case "help":
            default:
                showHelp(player);
                break;
        }
    }

    private void showHelp(Player player) {
        sendMessage(player, "&6=== CleanInteractions Help ===");
        sendMessage(player, "&7/ci scan &f- Scan for orphaned interactions");
        sendMessage(player, "&7/ci clean &f- Remove orphaned interactions");
        sendMessage(player, "&7/ci explore &f- Dump InteractionModule structure");
        sendMessage(player, "&7/ci zones &f- Explore interaction zones in world");
        sendMessage(player, "&7/ci block <x> <y> <z> &f- Check block interaction state");
        sendMessage(player, "&7/ci tracked <x> <y> <z> &f- Explore TrackedPlacement entries");
        sendMessage(player, "&7/ci tracked <x> <y> <z> clean &f- Remove TrackedPlacement");
        sendMessage(player, "&7/ci help &f- Show this help");
        sendMessage(player, "&7");
        sendMessage(player, "&eNote: Ghost 'Press F' prompts may require a rejoin to clear");
        sendMessage(player, "&eif they persist after cleaning.");
    }

    /**
     * Explores the InteractionModule to understand its structure and find where
     * interaction zones are stored.
     */
    private void exploreInteractionModule(Player player, World world) {
        sendMessage(player, "&6[HyFixes] Exploring InteractionModule structure...");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] EXPLORING InteractionModule");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        try {
            // Get InteractionModule singleton
            Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
            Method getModuleMethod = interactionModuleClass.getMethod("get");
            Object interactionModule = getModuleMethod.invoke(null);

            sendMessage(player, "&7InteractionModule class: &f" + interactionModule.getClass().getName());
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionModule: %s", interactionModule.getClass().getName());

            // Dump ALL methods
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Methods ===");
            for (Method m : interactionModuleClass.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   %s(%d) -> %s",
                    m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
            }

            // Dump ALL fields
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Fields ===");
            for (Field f : interactionModuleClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object value = null;
                try {
                    value = f.get(interactionModule);
                } catch (Exception e) {
                    value = "<error: " + e.getMessage() + ">";
                }
                String valueStr = value == null ? "null" : value.getClass().getSimpleName();
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   %s : %s = %s",
                    f.getName(), f.getType().getSimpleName(), valueStr);
            }

            // Look for anything "Zone" related
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Looking for Zone-related classes ===");
            String[] zoneClasses = {
                "com.hypixel.hytale.server.core.entity.InteractionZone",
                "com.hypixel.hytale.server.core.modules.interaction.InteractionZone",
                "com.hypixel.hytale.server.core.modules.interaction.InteractionZoneManager",
                "com.hypixel.hytale.server.core.modules.interaction.ZoneManager",
                "com.hypixel.hytale.server.core.entity.interaction.InteractionZone"
            };

            for (String className : zoneClasses) {
                try {
                    Class<?> zoneClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] FOUND: %s", className);
                    sendMessage(player, "&a  Found: &f" + className);

                    // Dump its methods
                    for (Method m : zoneClass.getMethods()) {
                        if (m.getDeclaringClass() == Object.class) continue;
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     %s(%d) -> %s",
                            m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                    }
                } catch (ClassNotFoundException e) {
                    // Not found, try next
                }
            }

            // Try to find world-level interaction storage
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Exploring World for interaction data ===");
            for (Method m : world.getClass().getMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("interact") || name.contains("zone")) {
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] World.%s(%d) -> %s",
                        m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                }
            }

            // Check EntityStore for interaction-related components
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Exploring EntityStore ===");
            var entityStore = world.getEntityStore();
            if (entityStore != null) {
                for (Method m : entityStore.getClass().getMethods()) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("interact") || name.contains("zone") || name.contains("component")) {
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] EntityStore.%s(%d) -> %s",
                            m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                    }
                }
            }

            // Check ChunkStore for interaction data
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Exploring ChunkStore ===");
            var chunkStore = world.getChunkStore();
            if (chunkStore != null) {
                for (Method m : chunkStore.getClass().getMethods()) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("interact") || name.contains("zone")) {
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ChunkStore.%s(%d) -> %s",
                            m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                    }
                }

                // Check the Store<ChunkStore> for resources
                var store = chunkStore.getStore();
                if (store != null) {
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === ChunkStore.getStore() resources ===");
                    // Try to get all resource types
                    try {
                        Method getResourcesMethod = store.getClass().getMethod("getResources");
                        Object resources = getResourcesMethod.invoke(store);
                        if (resources != null) {
                            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Resources: %s", resources.toString());
                        }
                    } catch (NoSuchMethodException e) {
                        // Try fields
                        for (Field f : store.getClass().getDeclaredFields()) {
                            String name = f.getName().toLowerCase();
                            if (name.contains("resource") || name.contains("interact") || name.contains("zone")) {
                                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Store field: %s : %s",
                                    f.getName(), f.getType().getSimpleName());
                            }
                        }
                    }
                }
            }

            sendMessage(player, "&aDump complete! Check server logs for details.");
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error exploring: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] Explore error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Explores interaction zones in the world near the player's position.
     */
    private void exploreInteractionZones(Player player, World world) {
        sendMessage(player, "&6[HyFixes] Exploring interaction zones near you...");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] EXPLORING INTERACTION ZONES");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        try {
            sendMessage(player, "&7Exploring interaction zones...");

            // Try to find InteractionZone entities or components
            var entityStore = world.getEntityStore();
            var store = entityStore.getStore();

            // Look for any component type that might be interaction-related
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Searching for interaction-related ComponentTypes ===");

            // Try to get InteractionModule and its component types
            Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
            Method getModuleMethod = interactionModuleClass.getMethod("get");
            Object interactionModule = getModuleMethod.invoke(null);

            // List all methods that return ComponentType
            for (Method m : interactionModuleClass.getMethods()) {
                if (m.getReturnType().getSimpleName().equals("ComponentType") && m.getParameterCount() == 0) {
                    try {
                        Object compType = m.invoke(interactionModule);
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionModule.%s() = %s",
                            m.getName(), compType);
                        sendMessage(player, "&7  " + m.getName() + ": &f" + compType);
                    } catch (Exception e) {
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionModule.%s() = ERROR: %s",
                            m.getName(), e.getMessage());
                    }
                }
            }

            // Try to find InteractionContext or related classes
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Exploring InteractionContext ===");
            try {
                Class<?> contextClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionContext");
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionContext fields:");
                for (Field f : contextClass.getDeclaredFields()) {
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   %s : %s", f.getName(), f.getType().getSimpleName());
                }
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionContext methods:");
                for (Method m : contextClass.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   %s(%d) -> %s",
                        m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                }
            } catch (ClassNotFoundException e) {
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionContext class not found");
            }

            // Try to find InteractionChain and dump its structure
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Exploring InteractionChain ===");
            try {
                Class<?> chainClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionChain");
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionChain fields:");
                for (Field f : chainClass.getDeclaredFields()) {
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   %s : %s", f.getName(), f.getType().getName());
                }
            } catch (ClassNotFoundException e) {
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] InteractionChain class not found");
            }

            // Try to find Interactable component or similar
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Looking for Interactable-related classes ===");
            String[] interactableClasses = {
                "com.hypixel.hytale.server.core.entity.Interactable",
                "com.hypixel.hytale.server.core.modules.interaction.Interactable",
                "com.hypixel.hytale.server.core.modules.interaction.InteractableComponent",
                "com.hypixel.hytale.server.core.entity.interaction.Interactable",
                "com.hypixel.hytale.builtin.interaction.Interactable"
            };

            for (String className : interactableClasses) {
                try {
                    Class<?> intClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] FOUND: %s", className);
                    sendMessage(player, "&a  Found: &f" + intClass.getSimpleName());

                    // Dump its fields
                    for (Field f : intClass.getDeclaredFields()) {
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     field: %s : %s",
                            f.getName(), f.getType().getSimpleName());
                    }
                } catch (ClassNotFoundException e) {
                    // Not found
                }
            }

            // Check for block-based interaction registration
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Looking for Block interaction classes ===");
            String[] blockInteractClasses = {
                "com.hypixel.hytale.server.core.universe.world.meta.BlockInteraction",
                "com.hypixel.hytale.server.core.modules.interaction.BlockInteraction",
                "com.hypixel.hytale.builtin.teleport.TeleportInteraction"
            };

            for (String className : blockInteractClasses) {
                try {
                    Class<?> biClass = Class.forName(className);
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] FOUND: %s", className);
                    sendMessage(player, "&a  Found: &f" + biClass.getSimpleName());
                } catch (ClassNotFoundException e) {
                    // Not found
                }
            }

            sendMessage(player, "&aDump complete! Check server logs for full details.");
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] Zones explore error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check block interaction state at a specific position.
     */
    private void checkBlockInteraction(Player player, World world, int x, int y, int z) {
        sendMessage(player, "&6[HyFixes] Checking block interaction at " + x + ", " + y + ", " + z);
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] CHECKING BLOCK INTERACTION at %d, %d, %d", x, y, z);
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        try {
            // Get block info at position
            int blockId = world.getBlock(x, y, z);
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Block ID: %d", blockId);
            sendMessage(player, "&7Block ID: &f" + blockId);

            if (blockId != 0) {
                try {
                    Class<?> blockTypeClass = Class.forName("com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType");
                    Method getAssetMapMethod = blockTypeClass.getMethod("getAssetMap");
                    Object assetMap = getAssetMapMethod.invoke(null);
                    Method getAssetMethod = assetMap.getClass().getMethod("getAsset", int.class);
                    Object blockType = getAssetMethod.invoke(assetMap, blockId);
                    if (blockType != null) {
                        Method getIdMethod = blockType.getClass().getMethod("getId");
                        String blockName = (String) getIdMethod.invoke(blockType);
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Block name: %s", blockName);
                        sendMessage(player, "&7Block name: &f" + blockName);
                    }
                } catch (Exception e) {
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Could not get block name: %s", e.getMessage());
                }
            } else {
                sendMessage(player, "&7Block: &fAIR (empty)");
            }

            // Explore World methods related to block interaction
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === World block interaction methods ===");
            for (Method m : world.getClass().getMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("block") && (name.contains("interact") || name.contains("state"))) {
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] World.%s(%d) -> %s",
                        m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());

                    // Try to call getter methods with block position
                    if (m.getName().startsWith("get") && m.getParameterCount() == 3) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params[0] == int.class && params[1] == int.class && params[2] == int.class) {
                            try {
                                Object result = m.invoke(world, x, y, z);
                                String resultStr = result == null ? "null" : result.toString();
                                plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   -> Result: %s", resultStr);
                                sendMessage(player, "&7" + m.getName() + ": &f" + resultStr);
                            } catch (Exception e) {
                                plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   -> Error: %s", e.getMessage());
                            }
                        }
                    }
                }
            }

            // Try to get block state/meta at position
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Checking BlockState ===");
            try {
                // Try World.getBlockState or similar
                Method getBlockStateMethod = null;
                for (Method m : world.getClass().getMethods()) {
                    if (m.getName().equals("getBlockState") && m.getParameterCount() == 3) {
                        getBlockStateMethod = m;
                        break;
                    }
                }
                if (getBlockStateMethod != null) {
                    Object blockState = getBlockStateMethod.invoke(world, x, y, z);
                    if (blockState != null) {
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] BlockState: %s", blockState.getClass().getName());
                        sendMessage(player, "&aFound BlockState: &f" + blockState.getClass().getSimpleName());

                        // Dump block state fields
                        for (Field f : blockState.getClass().getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            try {
                                Object value = f.get(blockState);
                                String valueStr = value == null ? "null" : value.toString();
                                if (valueStr.length() > 100) valueStr = valueStr.substring(0, 100) + "...";
                                plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   %s = %s", f.getName(), valueStr);
                            } catch (Exception fe) {
                                // Skip
                            }
                        }
                    } else {
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] BlockState is null at this position");
                        sendMessage(player, "&7No BlockState at this position");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] BlockState check error: %s", e.getMessage());
            }

            // Check ChunkStore for block-level interaction data
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Checking ChunkStore for block interaction ===");
            try {
                var chunkStore = world.getChunkStore();
                if (chunkStore != null) {
                    // Get the chunk containing this block
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Block is in chunk %d, %d", chunkX, chunkZ);

                    // Look for methods that take block coordinates
                    for (Method m : chunkStore.getClass().getMethods()) {
                        String name = m.getName().toLowerCase();
                        if (name.contains("interact") || name.contains("block")) {
                            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ChunkStore.%s(%d) -> %s",
                                m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                        }
                    }

                    // Try TrackedPlacement component
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] === Checking TrackedPlacement ===");
                    try {
                        Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
                        Method getModuleMethod = interactionModuleClass.getMethod("get");
                        Object interactionModule = getModuleMethod.invoke(null);
                        Method getTrackedMethod = interactionModuleClass.getMethod("getTrackedPlacementComponentType");
                        Object trackedCompType = getTrackedMethod.invoke(interactionModule);

                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] TrackedPlacement ComponentType: %s", trackedCompType);

                        // TrackedPlacement is a ChunkStore component - need to get it per chunk
                        var store = chunkStore.getStore();
                        if (store != null) {
                            // Try to find TrackedPlacement data
                            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Exploring ChunkStore.Store for TrackedPlacement...");

                            // Look for methods to get chunk-level components
                            for (Method m : store.getClass().getMethods()) {
                                if (m.getName().contains("Component") || m.getName().contains("Resource")) {
                                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Store.%s(%d) -> %s",
                                        m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                                }
                            }
                        }
                    } catch (Exception te) {
                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] TrackedPlacement check error: %s", te.getMessage());
                    }
                }
            } catch (Exception ce) {
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ChunkStore check error: %s", ce.getMessage());
            }

            sendMessage(player, "&aDump complete! Check server logs.");
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] Block check error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initializeApi(Player player) {
        try {
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Discovering API...");

            // Get InteractionManager component type
            Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
            Method getModuleMethod = interactionModuleClass.getMethod("get");
            Object interactionModule = getModuleMethod.invoke(null);
            Method getComponentTypeMethod = interactionModuleClass.getMethod("getInteractionManagerComponent");
            interactionManagerType = (ComponentType) getComponentTypeMethod.invoke(interactionModule);

            // Get InteractionManager.getChains()
            Class<?> interactionManagerClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionManager");
            getChainsMethod = interactionManagerClass.getMethod("getChains");

            // Get InteractionChain.context
            Class<?> interactionChainClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionChain");
            contextField = interactionChainClass.getDeclaredField("context");
            contextField.setAccessible(true);

            // Try to find InteractionContext fields for target entity
            Class<?> interactionContextClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionContext");

            // Look for target entity ref field
            String[] refFieldNames = {"targetEntity", "targetEntityRef", "target", "targetRef", "interactingWith"};
            for (String fieldName : refFieldNames) {
                try {
                    targetEntityRefField = interactionContextClass.getDeclaredField(fieldName);
                    targetEntityRefField.setAccessible(true);
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Found target entity field: " + fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    // Try next
                }
            }

            initialized = true;
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] API discovery successful!");

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] API discovery failed: " + e.getMessage());
            e.printStackTrace();
            initFailed = true;
        }
    }

    private void scanForOrphans(Player player, Store<EntityStore> store, Ref<EntityStore> playerRef, World world, boolean remove) {
        try {
            sendMessage(player, "&6[HyFixes] Scanning for orphaned interactions...");

            int scanned = 0;
            int orphansFound = 0;
            int removed = 0;
            List<String> orphanDetails = new ArrayList<>();

            // Get the player's InteractionManager
            Object interactionManager = store.getComponent(playerRef, interactionManagerType);
            if (interactionManager != null) {
                @SuppressWarnings("unchecked")
                Map<Integer, Object> chains = (Map<Integer, Object>) getChainsMethod.invoke(interactionManager);

                if (chains != null && !chains.isEmpty()) {
                    List<Integer> toRemove = new ArrayList<>();

                    for (Map.Entry<Integer, Object> entry : chains.entrySet()) {
                        scanned++;
                        Object chain = entry.getValue();

                        if (chain == null) {
                            orphansFound++;
                            toRemove.add(entry.getKey());
                            orphanDetails.add("Chain " + entry.getKey() + " (null chain)");
                            continue;
                        }

                        Object context = contextField.get(chain);
                        if (context == null) {
                            orphansFound++;
                            toRemove.add(entry.getKey());
                            orphanDetails.add("Chain " + entry.getKey() + " (null context)");
                            continue;
                        }

                        // Check if target entity ref is valid
                        if (targetEntityRefField != null) {
                            try {
                                Object targetRef = targetEntityRefField.get(context);
                                if (targetRef instanceof Ref) {
                                    Ref<?> tRef = (Ref<?>) targetRef;
                                    if (!tRef.isValid()) {
                                        orphansFound++;
                                        toRemove.add(entry.getKey());
                                        orphanDetails.add("Chain " + entry.getKey() + " (invalid target ref)");
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore field access errors
                            }
                        }
                    }

                    if (remove && !toRemove.isEmpty()) {
                        for (Integer chainId : toRemove) {
                            chains.remove(chainId);
                            removed++;
                        }
                    }
                }
            }

            // Report results
            sendMessage(player, "&6=== Scan Results ===");
            sendMessage(player, "&7Interaction chains scanned: &f" + scanned);
            sendMessage(player, "&7Orphaned interactions found: &e" + orphansFound);

            if (remove && removed > 0) {
                sendMessage(player, "&aOrphaned interactions removed: &f" + removed);
                plugin.getLogger().at(Level.INFO).log(
                    "[CleanInteractions] Removed %d orphaned interaction chains for player",
                    removed
                );
            } else if (!remove && orphansFound > 0) {
                sendMessage(player, "&7Run &f/cleaninteractions clean &7to remove them");
            }

            if (!orphanDetails.isEmpty() && orphanDetails.size() <= 5) {
                sendMessage(player, "&7Details:");
                for (String detail : orphanDetails) {
                    sendMessage(player, "&7  - " + detail);
                }
            }

            // Suggest rejoin if ghost interactions may remain
            if (orphansFound == 0) {
                sendMessage(player, "&7");
                sendMessage(player, "&eNo orphaned chains found in your InteractionManager.");
                sendMessage(player, "&7If you still see ghost 'Press F' prompts:");
                sendMessage(player, "&7  1. &fRejoin the server &7(clears client cache)");
                sendMessage(player, "&7  2. The ghost may be a client-side artifact");
            }

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error during scan: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] Scan error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Explore and optionally clean TrackedPlacement entries at a specific block position.
     * TrackedPlacement tracks interactions for blocks like teleporters.
     */
    private void exploreTrackedPlacement(Player player, World world, int x, int y, int z, boolean clean) {
        sendMessage(player, "&6[HyFixes] Exploring TrackedPlacement at " + x + ", " + y + ", " + z);
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] EXPLORING TrackedPlacement at %d, %d, %d", x, y, z);
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        try {
            // Get ChunkStore
            var chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                sendMessage(player, "&cChunkStore is null!");
                return;
            }

            var store = chunkStore.getStore();
            if (store == null) {
                sendMessage(player, "&cChunkStore.getStore() is null!");
                return;
            }

            // Get InteractionModule to get TrackedPlacement ComponentType
            Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
            Method getModuleMethod = interactionModuleClass.getMethod("get");
            Object interactionModule = getModuleMethod.invoke(null);

            Method getTrackedMethod = interactionModuleClass.getMethod("getTrackedPlacementComponentType");
            @SuppressWarnings("rawtypes")
            ComponentType trackedCompType = (ComponentType) getTrackedMethod.invoke(interactionModule);

            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] TrackedPlacement ComponentType: %s", trackedCompType);
            sendMessage(player, "&7TrackedPlacement type: &f" + trackedCompType);

            // Calculate chunk coordinates
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Target chunk: %d, %d", chunkX, chunkZ);
            sendMessage(player, "&7Target chunk: &f" + chunkX + ", " + chunkZ);

            // Iterate through chunks to find TrackedPlacement components
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Scanning for TrackedPlacement entries...");

            final int[] foundCount = {0};
            final int[] nearbyCount = {0};
            final int[] removedCount = {0};
            final double searchRadius = 3.0;

            // Iterate through the store to find TrackedPlacement entries
            store.forEachChunk((chunk, cmdBuffer) -> {
                try {
                    // Get the refs in this chunk
                    java.lang.reflect.Field refsField = chunk.getClass().getDeclaredField("refs");
                    refsField.setAccessible(true);
                    Object refs = refsField.get(chunk);

                    if (refs != null && refs.getClass().isArray()) {
                        Object[] refArray = (Object[]) refs;
                        for (Object refObj : refArray) {
                            if (refObj instanceof Ref) {
                                @SuppressWarnings("unchecked")
                                Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkRef =
                                    (Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore>) refObj;

                                // Try to get TrackedPlacement component
                                Object tracked = store.getComponent(chunkRef, trackedCompType);
                                if (tracked != null) {
                                    foundCount[0]++;
                                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Found TrackedPlacement: %s", tracked.getClass().getName());

                                    // Dump ALL fields of the TrackedPlacement structure (including inherited)
                                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   === ALL FIELDS (including inherited) ===");

                                    // Get all fields from class hierarchy
                                    List<Field> allFields = new ArrayList<>();
                                    Class<?> currentClass = tracked.getClass();
                                    while (currentClass != null && currentClass != Object.class) {
                                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   --- Class: %s ---", currentClass.getName());
                                        for (Field f : currentClass.getDeclaredFields()) {
                                            allFields.add(f);
                                        }
                                        currentClass = currentClass.getSuperclass();
                                    }

                                    for (Field f : allFields) {
                                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                                        f.setAccessible(true);
                                        try {
                                            Object value = f.get(tracked);
                                            String typeName = f.getType().getSimpleName();
                                            String valueStr = value == null ? "null" : value.toString();
                                            if (valueStr.length() > 300) valueStr = valueStr.substring(0, 300) + "...";
                                            plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   FIELD: %s (%s) = %s",
                                                f.getName(), typeName, valueStr);

                                            // For any collection-like object, try to get its size and contents
                                            if (value != null) {
                                                // Check for Collection
                                                if (value instanceof java.util.Collection) {
                                                    java.util.Collection<?> coll = (java.util.Collection<?>) value;
                                                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     Collection size: %d", coll.size());
                                                    int idx = 0;
                                                    for (Object item : coll) {
                                                        if (idx >= 10) {
                                                            plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     ... and %d more", coll.size() - 10);
                                                            break;
                                                        }
                                                        String itemStr = item == null ? "null" : item.toString();
                                                        if (itemStr.length() > 150) itemStr = itemStr.substring(0, 150) + "...";
                                                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     [%d]: %s", idx, itemStr);
                                                        idx++;
                                                    }
                                                }
                                                // Check for array
                                                else if (value.getClass().isArray()) {
                                                    int len = java.lang.reflect.Array.getLength(value);
                                                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     Array length: %d", len);
                                                    for (int i = 0; i < Math.min(len, 10); i++) {
                                                        Object item = java.lang.reflect.Array.get(value, i);
                                                        String itemStr = item == null ? "null" : item.toString();
                                                        if (itemStr.length() > 150) itemStr = itemStr.substring(0, 150) + "...";
                                                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     [%d]: %s", i, itemStr);
                                                    }
                                                }
                                            }

                                            // If it's a map or collection, try to iterate for positions
                                            if (value instanceof Map) {
                                                @SuppressWarnings("unchecked")
                                                Map<Object, Object> map = (Map<Object, Object>) value;
                                                plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     Map has %d entries", map.size());

                                                List<Object> keysToRemove = new ArrayList<>();
                                                for (Map.Entry<Object, Object> entry : map.entrySet()) {
                                                    Object key = entry.getKey();
                                                    Object val = entry.getValue();
                                                    String keyStr = key == null ? "null" : key.toString();
                                                    String valStr = val == null ? "null" : val.toString();
                                                    if (valStr.length() > 100) valStr = valStr.substring(0, 100) + "...";

                                                    // Check if this entry is near our target position
                                                    boolean isNearby = keyStr.contains(x + "") || keyStr.contains(y + "") || keyStr.contains(z + "");

                                                    // Try to extract position from key if it's a packed long
                                                    if (key instanceof Long) {
                                                        long packed = (Long) key;
                                                        // Common packing: x | (z << 26) | (y << 52) or similar
                                                        // Try multiple unpacking strategies
                                                        int px1 = (int) (packed & 0x3FFFFFF);
                                                        int pz1 = (int) ((packed >> 26) & 0x3FFFFFF);
                                                        int py1 = (int) ((packed >> 52) & 0xFFF);

                                                        // Also try simpler packing
                                                        int px2 = (int) (packed & 0xFFFF);
                                                        int py2 = (int) ((packed >> 16) & 0xFFFF);
                                                        int pz2 = (int) ((packed >> 32) & 0xFFFF);

                                                        double dist1 = Math.sqrt(Math.pow(px1 - x, 2) + Math.pow(py1 - y, 2) + Math.pow(pz1 - z, 2));
                                                        double dist2 = Math.sqrt(Math.pow(px2 - x, 2) + Math.pow(py2 - y, 2) + Math.pow(pz2 - z, 2));

                                                        if (dist1 < searchRadius || dist2 < searchRadius) {
                                                            isNearby = true;
                                                            plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     NEARBY! Key=%d unpacks to (%d,%d,%d) or (%d,%d,%d)",
                                                                packed, px1, py1, pz1, px2, py2, pz2);
                                                        }
                                                    }

                                                    if (isNearby) {
                                                        nearbyCount[0]++;
                                                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     >>> NEARBY: key=%s, val=%s", keyStr, valStr);

                                                        if (clean) {
                                                            keysToRemove.add(key);
                                                        }
                                                    }
                                                }

                                                // Remove nearby entries if cleaning
                                                if (clean && !keysToRemove.isEmpty()) {
                                                    for (Object key : keysToRemove) {
                                                        map.remove(key);
                                                        removedCount[0]++;
                                                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]     REMOVED: key=%s", key);
                                                    }
                                                }
                                            }
                                        } catch (Exception fe) {
                                            plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   %s: ERROR - %s", f.getName(), fe.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip chunk errors
                }
            });

            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Found %d TrackedPlacement components", foundCount[0]);
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Found %d nearby entries", nearbyCount[0]);
            sendMessage(player, "&7TrackedPlacement components found: &f" + foundCount[0]);
            sendMessage(player, "&7Nearby entries (within " + searchRadius + " blocks): &e" + nearbyCount[0]);

            if (clean && removedCount[0] > 0) {
                sendMessage(player, "&aRemoved: &f" + removedCount[0] + " entries");
                plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Removed %d TrackedPlacement entries", removedCount[0]);
            } else if (!clean && nearbyCount[0] > 0) {
                sendMessage(player, "&7Run &f/ci tracked " + x + " " + y + " " + z + " clean &7to remove them");
            }

            sendMessage(player, "&aDump complete! Check server logs for details.");
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] TrackedPlacement error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Find ALL entities near a position, regardless of type.
     * Useful for finding ghost interaction zones.
     */
    private void findAllEntitiesNear(Player player, World world, double tx, double ty, double tz, double radius) {
        sendMessage(player, "&6[HyFixes] Finding ALL entities near " + tx + ", " + ty + ", " + tz + " (radius " + radius + ")");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] FINDING ALL ENTITIES near %.1f, %.1f, %.1f (radius %.1f)", tx, ty, tz, radius);
        plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        try {
            var entityStore = world.getEntityStore();
            var store = entityStore.getStore();

            // Get TransformComponent type
            Class<?> entityModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.EntityModule");
            Method getModuleMethod = entityModuleClass.getMethod("get");
            Object entityModule = getModuleMethod.invoke(null);
            Method getCompTypeMethod = entityModuleClass.getMethod("getTransformComponentType");
            @SuppressWarnings("rawtypes")
            ComponentType transformComponentType = (ComponentType) getCompTypeMethod.invoke(entityModule);

            final int[] totalEntities = {0};
            final int[] nearbyEntities = {0};

            store.forEachChunk((chunk, cmdBuffer) -> {
                try {
                    java.lang.reflect.Field refsField = chunk.getClass().getDeclaredField("refs");
                    refsField.setAccessible(true);
                    Object refs = refsField.get(chunk);

                    if (refs != null && refs.getClass().isArray()) {
                        Object[] refArray = (Object[]) refs;
                        for (Object refObj : refArray) {
                            if (refObj instanceof Ref) {
                                totalEntities[0]++;
                                @SuppressWarnings("unchecked")
                                Ref<EntityStore> entityRef = (Ref<EntityStore>) refObj;

                                // Get position
                                Object transform = store.getComponent(entityRef, transformComponentType);
                                if (transform == null) continue;

                                Method getPosMethod = transform.getClass().getMethod("getPosition");
                                Object pos = getPosMethod.invoke(transform);
                                if (pos == null) continue;

                                float px, py, pz;
                                try {
                                    Method getX = pos.getClass().getMethod("x");
                                    Method getY = pos.getClass().getMethod("y");
                                    Method getZ = pos.getClass().getMethod("z");
                                    px = ((Number) getX.invoke(pos)).floatValue();
                                    py = ((Number) getY.invoke(pos)).floatValue();
                                    pz = ((Number) getZ.invoke(pos)).floatValue();
                                } catch (NoSuchMethodException e) {
                                    java.lang.reflect.Field xField = pos.getClass().getDeclaredField("x");
                                    java.lang.reflect.Field yField = pos.getClass().getDeclaredField("y");
                                    java.lang.reflect.Field zField = pos.getClass().getDeclaredField("z");
                                    xField.setAccessible(true);
                                    yField.setAccessible(true);
                                    zField.setAccessible(true);
                                    px = ((Number) xField.get(pos)).floatValue();
                                    py = ((Number) yField.get(pos)).floatValue();
                                    pz = ((Number) zField.get(pos)).floatValue();
                                }

                                double dist = Math.sqrt(
                                    Math.pow(px - tx, 2) +
                                    Math.pow(py - ty, 2) +
                                    Math.pow(pz - tz, 2)
                                );

                                if (dist <= radius) {
                                    nearbyEntities[0]++;
                                    Object archetype = store.getArchetype(entityRef);
                                    String archetypeStr = archetype != null ? archetype.toString() : "null";

                                    // Extract just the component type names for readability
                                    List<String> componentNames = new ArrayList<>();
                                    if (archetypeStr.contains("componentTypes=[")) {
                                        // Parse component names from archetype string
                                        String[] parts = archetypeStr.split("typeClass=class ");
                                        for (String part : parts) {
                                            if (part.contains(",")) {
                                                String className = part.substring(0, part.indexOf(","));
                                                if (className.contains(".")) {
                                                    className = className.substring(className.lastIndexOf(".") + 1);
                                                }
                                                if (!className.equals("null") && !className.isEmpty()) {
                                                    componentNames.add(className);
                                                }
                                            }
                                        }
                                    }

                                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ENTITY #%d at (%.1f, %.1f, %.1f) dist=%.2f",
                                        nearbyEntities[0], px, py, pz, dist);
                                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   Components: %s", componentNames);

                                    // Check for interaction-related components
                                    boolean hasInteraction = archetypeStr.contains("Interaction") || archetypeStr.contains("Zone");
                                    if (hasInteraction) {
                                        plugin.getLogger().at(Level.INFO).log("[CleanInteractions]   >>> HAS INTERACTION COMPONENT! <<<");
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip chunk errors
                }
            });

            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Scanned %d total entities", totalEntities[0]);
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Found %d entities within radius", nearbyEntities[0]);
            sendMessage(player, "&7Total entities scanned: &f" + totalEntities[0]);
            sendMessage(player, "&7Entities within " + radius + " blocks: &e" + nearbyEntities[0]);

            if (nearbyEntities[0] == 0) {
                sendMessage(player, "&7No entities found near that position.");
                sendMessage(player, "&eThe ghost interaction may be client-side cached.");
                sendMessage(player, "&7Try: &f/rejoin &7or reconnect to clear client cache.");
            }

            sendMessage(player, "&aCheck server logs for details.");
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] ========================================");

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] Entity search error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendMessage(Player player, String message) {
        ChatColorUtil.sendMessage(player, message);
    }
}
