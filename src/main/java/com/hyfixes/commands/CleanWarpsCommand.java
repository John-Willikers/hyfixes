package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.Message;
import com.hyfixes.util.ChatColorUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.math.vector.Vector3f;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * CleanWarpsCommand - Finds and removes orphaned warp entries from warps.json
 *
 * GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/11
 *
 * The Bug:
 * When teleporters are deleted but the TrackedPlacement component fails,
 * the warp entry in warps.json remains even though the block is gone.
 * This causes ghost "Press F to interact" zones.
 *
 * The Fix:
 * This command reads warps.json, checks if each teleporter block still exists
 * at the stored coordinates, and removes orphaned entries.
 *
 * Usage:
 *   /cleanwarps         - Scan for orphaned warps
 *   /cleanwarps scan    - Same as above
 *   /cleanwarps clean   - Remove orphaned warps from warps.json
 */
public class CleanWarpsCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;
    private static final String WARPS_FILE = "universe/warps.json";
    private static final String TELEPORTER_BLOCK_NAME = "Teleporter";

    public CleanWarpsCommand(HyFixes plugin) {
        super("cleanwarps", "hyfixes.command.cleanwarps.desc");
        this.plugin = plugin;
        addAliases("cw", "fixwarps", "warpclean");
        setAllowsExtraArguments(true);
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

        switch (action) {
            case "scan":
                scanWarps(player, world, false);
                break;
            case "clean":
            case "remove":
            case "fix":
                scanWarps(player, world, true);
                break;
            case "delete":
            case "kill":
                // Delete warp entity at position: /cw delete x y z
                if (args.length >= 4) {
                    try {
                        double dx = Double.parseDouble(args[1]);
                        double dy = Double.parseDouble(args[2]);
                        double dz = Double.parseDouble(args[3]);
                        deleteWarpEntity(player, world, dx, dy, dz);
                    } catch (NumberFormatException e) {
                        sendMessage(player, "&cUsage: /cw delete <x> <y> <z>");
                    }
                } else {
                    sendMessage(player, "&cUsage: /cw delete <x> <y> <z>");
                    sendMessage(player, "&7Deletes the warp entity at the specified position");
                }
                break;
            case "help":
            default:
                showHelp(player);
                break;
        }
    }

    private void showHelp(Player player) {
        sendMessage(player, "&6=== CleanWarps Help ===");
        sendMessage(player, "&7/cleanwarps scan &f- Scan for orphaned warps");
        sendMessage(player, "&7/cleanwarps clean &f- Remove orphaned warps from warps.json");
        sendMessage(player, "&7/cleanwarps delete <x> <y> <z> &f- Delete warp entity at position");
        sendMessage(player, "&7/cleanwarps help &f- Show this help");
        sendMessage(player, "&7");
        sendMessage(player, "&eOrphaned warp ENTITIES (not just warps.json) cause ghost 'Press F'.");
        sendMessage(player, "&eUse /cw delete to remove the entity itself.");
    }

    /**
     * Deletes a warp entity at the specified position.
     */
    private void deleteWarpEntity(Player player, World world, double tx, double ty, double tz) {
        sendMessage(player, "&6[HyFixes] Searching for warp entity near " + tx + ", " + ty + ", " + tz + "...");
        plugin.getLogger().at(Level.INFO).log("[CleanWarps] ========================================");
        plugin.getLogger().at(Level.INFO).log("[CleanWarps] DELETING WARP ENTITY near %.1f, %.1f, %.1f", tx, ty, tz);
        plugin.getLogger().at(Level.INFO).log("[CleanWarps] ========================================");

        try {
            EntityStore entityStore = world.getEntityStore();
            Store<EntityStore> store = entityStore.getStore();

            // Get TransformComponent type
            Class<?> transformClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.TransformComponent");
            java.lang.reflect.Method getCompTypeMethod = transformClass.getMethod("getComponentType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            com.hypixel.hytale.component.ComponentType transformComponentType =
                (com.hypixel.hytale.component.ComponentType) getCompTypeMethod.invoke(null);

            final double searchRadius = 1.0; // Within 1 block
            final boolean[] found = {false};
            final Ref<EntityStore>[] targetRef = new Ref[1];
            final String[] entityInfo = {""};

            // Iterate through all entities to find the warp entity
            store.forEachChunk((chunk, cmdBuffer) -> {
                if (found[0]) return; // Already found

                try {
                    java.lang.reflect.Field refsField = chunk.getClass().getDeclaredField("refs");
                    refsField.setAccessible(true);
                    Object refs = refsField.get(chunk);
                    if (refs != null && refs.getClass().isArray()) {
                        Object[] refArray = (Object[]) refs;
                        for (Object refObj : refArray) {
                            if (found[0]) break;
                            if (refObj instanceof Ref) {
                                @SuppressWarnings("unchecked")
                                Ref<EntityStore> entityRef = (Ref<EntityStore>) refObj;

                                // Get position
                                Object transform = store.getComponent(entityRef, transformComponentType);
                                if (transform == null) continue;

                                java.lang.reflect.Method getPosMethod = transform.getClass().getMethod("getPosition");
                                Object pos = getPosMethod.invoke(transform);
                                if (pos == null) continue;

                                float px, py, pz;
                                try {
                                    java.lang.reflect.Method getX = pos.getClass().getMethod("x");
                                    java.lang.reflect.Method getY = pos.getClass().getMethod("y");
                                    java.lang.reflect.Method getZ = pos.getClass().getMethod("z");
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

                                if (dist <= searchRadius) {
                                    // Check if it has WarpComponent
                                    Object archetype = store.getArchetype(entityRef);
                                    String archetypeStr = archetype != null ? archetype.toString() : "unknown";

                                    if (archetypeStr.contains("WarpComponent")) {
                                        found[0] = true;
                                        targetRef[0] = entityRef;
                                        entityInfo[0] = String.format("Warp entity at (%.1f, %.1f, %.1f)", px, py, pz);
                                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] Found: %s", entityInfo[0]);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip chunk errors
                }
            });

            if (found[0] && targetRef[0] != null) {
                sendMessage(player, "&aFound: " + entityInfo[0]);
                sendMessage(player, "&eAttempting to delete...");

                // Try to remove the entity
                try {
                    // Log all removeEntity methods to find the right signature
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] Looking for removeEntity methods on Store...");
                    for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                        if (m.getName().equals("removeEntity")) {
                            StringBuilder params = new StringBuilder();
                            for (Class<?> paramType : m.getParameterTypes()) {
                                if (params.length() > 0) params.append(", ");
                                params.append(paramType.getName());
                            }
                            plugin.getLogger().at(Level.INFO).log("[CleanWarps]   removeEntity(%s) -> %s",
                                params.toString(), m.getReturnType().getName());
                        }
                    }

                    // Try the single-arg version first (just the ref)
                    java.lang.reflect.Method removeEntityMethod = null;
                    for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                        if (m.getName().equals("removeEntity") && m.getParameterCount() == 1) {
                            Class<?>[] paramTypes = m.getParameterTypes();
                            if (paramTypes[0].getName().contains("Ref")) {
                                removeEntityMethod = m;
                                plugin.getLogger().at(Level.INFO).log("[CleanWarps] Using single-arg removeEntity(Ref)");
                                break;
                            }
                        }
                    }

                    // If no single-arg version, try 2-arg with RemoveReason
                    if (removeEntityMethod == null) {
                        for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                            if (m.getName().equals("removeEntity") && m.getParameterCount() == 2) {
                                Class<?>[] paramTypes = m.getParameterTypes();
                                plugin.getLogger().at(Level.INFO).log("[CleanWarps] Found 2-arg: %s, %s",
                                    paramTypes[0].getName(), paramTypes[1].getName());

                                // Check if second param is RemoveReason enum
                                if (paramTypes[1].isEnum() && paramTypes[1].getName().contains("RemoveReason")) {
                                    removeEntityMethod = m;
                                    // Get the REMOVE enum constant (not DESTROY - that doesn't exist)
                                    Object[] enumConstants = paramTypes[1].getEnumConstants();
                                    Object removeReason = null;
                                    for (Object ec : enumConstants) {
                                        plugin.getLogger().at(Level.INFO).log("[CleanWarps]   RemoveReason: %s", ec);
                                        if (ec.toString().equals("REMOVE")) {
                                            removeReason = ec;
                                        }
                                    }
                                    if (removeReason != null) {
                                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] Calling removeEntity with REMOVE reason...");
                                        Object result = removeEntityMethod.invoke(store, targetRef[0], removeReason);
                                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] removeEntity result: %s", result);
                                        sendMessage(player, "&a[HyFixes] Warp entity deleted!");
                                        sendMessage(player, "&eNote: You may need to rejoin for the ghost prompt to disappear.");
                                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] Successfully deleted warp entity at %.1f, %.1f, %.1f", tx, ty, tz);
                                    } else {
                                        plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Could not find REMOVE in RemoveReason enum!");
                                        sendMessage(player, "&cCould not find REMOVE reason constant");
                                    }
                                    break;
                                }
                            }
                        }
                    } else {
                        // Use single-arg version
                        Object result = removeEntityMethod.invoke(store, targetRef[0]);
                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] removeEntity result: %s", result);
                        sendMessage(player, "&a[HyFixes] Warp entity deleted!");
                        sendMessage(player, "&eNote: You may need to rejoin for the ghost prompt to disappear.");
                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] Successfully deleted warp entity at %.1f, %.1f, %.1f", tx, ty, tz);
                    }

                    if (removeEntityMethod == null) {
                        sendMessage(player, "&cCould not find suitable removeEntity method");
                        plugin.getLogger().at(Level.WARNING).log("[CleanWarps] No suitable removeEntity method found");
                    }
                } catch (Exception re) {
                    sendMessage(player, "&cError deleting entity: " + re.getMessage());
                    plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Delete error: %s", re.getMessage());
                    re.printStackTrace();
                }
            } else {
                sendMessage(player, "&cNo warp entity found within " + searchRadius + " blocks of that position");
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] No warp entity found near %.1f, %.1f, %.1f", tx, ty, tz);
            }

            plugin.getLogger().at(Level.INFO).log("[CleanWarps] ========================================");

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Delete warp error: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    private void scanWarps(Player player, World world, boolean removeOrphans) {
        try {
            sendMessage(player, "&6[HyFixes] Scanning warps.json for orphaned teleporters...");

            // Find warps.json - try multiple locations
            Path warpsPath = findWarpsFile();
            if (warpsPath == null || !Files.exists(warpsPath)) {
                sendMessage(player, "&c[HyFixes] Could not find warps.json");
                sendMessage(player, "&7Tried: universe/warps.json");
                return;
            }

            sendMessage(player, "&7Found: " + warpsPath.toString());

            // Read and parse JSON
            String jsonContent = new String(Files.readAllBytes(warpsPath));
            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
            JsonArray warps = root.getAsJsonArray("Warps");

            if (warps == null || warps.size() == 0) {
                sendMessage(player, "&7No warps found in warps.json");
                return;
            }

            sendMessage(player, "&7Found " + warps.size() + " warp(s) to check...");

            // Check each warp
            List<WarpInfo> orphanedWarps = new ArrayList<>();
            List<WarpInfo> validWarps = new ArrayList<>();
            int checked = 0;

            for (JsonElement element : warps) {
                JsonObject warp = element.getAsJsonObject();
                checked++;

                String id = warp.get("Id").getAsString();
                String warpWorld = warp.get("World").getAsString();
                double x = warp.get("X").getAsDouble();
                double y = warp.get("Y").getAsDouble();
                double z = warp.get("Z").getAsDouble();

                WarpInfo info = new WarpInfo(id, warpWorld, x, y, z, element);

                // Check if we're in the same world
                String currentWorld = world.getName();
                if (!warpWorld.equals(currentWorld)) {
                    sendMessage(player, "&7  [" + id + "] Different world (" + warpWorld + ") - skipping");
                    validWarps.add(info);  // Keep warps in other worlds
                    continue;
                }

                // Check if teleporter block exists at this location
                sendMessage(player, "&7  Checking [" + id + "] at " + x + ", " + y + ", " + z + "...");
                boolean blockExists = checkTeleporterExists(world, x, y, z, player);

                if (blockExists) {
                    sendMessage(player, "&a  [" + id + "] Valid - teleporter exists at " +
                            (int) x + ", " + (int) y + ", " + (int) z);
                    validWarps.add(info);
                } else {
                    sendMessage(player, "&c  [" + id + "] ORPHANED - no teleporter at " +
                            (int) x + ", " + (int) y + ", " + (int) z);
                    orphanedWarps.add(info);
                }
            }

            // Report results
            sendMessage(player, "&6=== Scan Results ===");
            sendMessage(player, "&7Warps checked: &f" + checked);
            sendMessage(player, "&aValid warps: &f" + validWarps.size());
            sendMessage(player, "&cOrphaned warps: &f" + orphanedWarps.size());

            if (orphanedWarps.isEmpty()) {
                sendMessage(player, "&aAll warps are valid!");
                return;
            }

            // Remove orphaned warps if requested
            if (removeOrphans && !orphanedWarps.isEmpty()) {
                sendMessage(player, "&e");
                sendMessage(player, "&eRemoving orphaned warps from warps.json...");
                sendMessage(player, "&7File path: " + warpsPath.toAbsolutePath());

                // Build new JSON with only valid warps
                JsonArray newWarps = new JsonArray();
                for (WarpInfo valid : validWarps) {
                    newWarps.add(valid.jsonElement);
                }
                sendMessage(player, "&7New warp count: " + newWarps.size() + " (was " + warps.size() + ")");

                root.add("Warps", newWarps);

                // Write back to file
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                String newJson = gson.toJson(root);

                try {
                    Files.write(warpsPath, newJson.getBytes());
                    sendMessage(player, "&aSuccessfully wrote to warps.json!");
                } catch (Exception writeEx) {
                    sendMessage(player, "&cFailed to write file: " + writeEx.getMessage());
                    plugin.getLogger().at(Level.SEVERE).log("[CleanWarps] Write failed: " + writeEx.getMessage());
                    writeEx.printStackTrace();
                    return;
                }

                sendMessage(player, "&aRemoved " + orphanedWarps.size() + " orphaned warp(s)!");
                sendMessage(player, "&7warps.json has been updated.");
                sendMessage(player, "&e");
                sendMessage(player, "&eIMPORTANT: Restart the server for changes to take effect!");
                sendMessage(player, "&eThe Teleport plugin caches warps in memory.");

                plugin.getLogger().at(Level.INFO).log(
                    "[CleanWarps] Removed %d orphaned warps: %s",
                    orphanedWarps.size(),
                    orphanedWarps.stream().map(w -> w.id).reduce((a, b) -> a + ", " + b).orElse("none")
                );
            } else if (!removeOrphans && !orphanedWarps.isEmpty()) {
                sendMessage(player, "&7");
                sendMessage(player, "&7Run &f/cleanwarps clean &7to remove orphaned warps.");
            } else if (removeOrphans && orphanedWarps.isEmpty()) {
                sendMessage(player, "&7No orphaned warps to remove.");
            }

        } catch (IOException e) {
            sendMessage(player, "&c[HyFixes] Error reading warps.json: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanWarps] IO error: " + e.getMessage());
        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Path findWarpsFile() {
        // Try relative to server root
        Path path = Paths.get(WARPS_FILE);
        if (Files.exists(path)) return path;

        // Try absolute common paths
        String[] tryPaths = {
            "universe/warps.json",
            "./universe/warps.json",
            "../universe/warps.json",
            "plugins/Teleport/warps.json",
            "mods/Teleport/warps.json"
        };

        for (String tryPath : tryPaths) {
            path = Paths.get(tryPath);
            if (Files.exists(path)) return path;
        }

        return Paths.get(WARPS_FILE);  // Return default for error message
    }

    private boolean checkTeleporterExists(World world, double wx, double wy, double wz, Player player) {
        try {
            plugin.getLogger().at(Level.INFO).log(
                "[CleanWarps] === Searching for teleporter/interaction near %.2f, %.2f, %.2f ===",
                wx, wy, wz
            );

            EntityStore entityStore = world.getEntityStore();
            if (entityStore == null) {
                plugin.getLogger().at(Level.WARNING).log("[CleanWarps] EntityStore is null!");
                return false;
            }

            Store<EntityStore> store = entityStore.getStore();
            if (store == null) {
                plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Store is null!");
                return false;
            }

            // Search radius - look within 5 blocks
            final double searchRadius = 5.0;
            final double wxFinal = wx;
            final double wyFinal = wy;
            final double wzFinal = wz;
            final List<String> nearbyEntities = new ArrayList<>();
            final boolean[] foundTeleporter = {false};

            int entityCount = store.getEntityCount();
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] EntityStore has %d entities total - iterating all...", entityCount);

            // Use reflection to iterate through ALL entities
            try {
                // Get TransformComponent type to check positions
                Class<?> transformClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.TransformComponent");
                java.lang.reflect.Method getComponentTypeMethod = transformClass.getMethod("getComponentType");
                @SuppressWarnings({"unchecked", "rawtypes"})
                com.hypixel.hytale.component.ComponentType transformComponentType =
                    (com.hypixel.hytale.component.ComponentType) getComponentTypeMethod.invoke(null);

                // Log ALL Store methods to find entity iteration
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] === ALL Store methods ===");
                for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                    // Skip Object methods
                    if (m.getDeclaringClass() == Object.class) continue;
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] Store.%s(%d params) -> %s",
                        m.getName(), m.getParameterCount(), m.getReturnType().getSimpleName());
                }
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] === END Store methods ===");

                // Use forEachChunk via reflection to iterate all archetype chunks
                final int[] processedRefs = {0};
                final int[] chunkCount = {0};

                try {
                    // Find forEachChunk(1 param) -> void which takes Consumer<ArchetypeChunk>
                    java.lang.reflect.Method forEachChunkMethod = null;
                    for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                        if (m.getName().equals("forEachChunk") && m.getParameterCount() == 1 &&
                            m.getReturnType() == void.class) {
                            plugin.getLogger().at(Level.INFO).log("[CleanWarps] Using forEachChunk(1) -> void, param: %s",
                                m.getParameterTypes()[0].getName());
                            forEachChunkMethod = m;
                            break;
                        }
                    }

                    if (forEachChunkMethod != null) {
                        Class<?> consumerType = forEachChunkMethod.getParameterTypes()[0];
                        final com.hypixel.hytale.component.ComponentType finalTransformType = transformComponentType;
                        final Store<EntityStore> finalStore = store;

                        Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                            consumerType.getClassLoader(),
                            new Class<?>[] { consumerType },
                            (proxy, method, args) -> {
                                if (method.getName().equals("accept")) {
                                    ArchetypeChunk chunk = (ArchetypeChunk) args[0];
                                    chunkCount[0]++;

                                    // First chunk - log all methods
                                    if (chunkCount[0] == 1) {
                                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] ArchetypeChunk methods:");
                                        for (java.lang.reflect.Method cm : chunk.getClass().getMethods()) {
                                            if (cm.getDeclaringClass() != Object.class) {
                                                plugin.getLogger().at(Level.INFO).log("[CleanWarps]   %s(%d) -> %s",
                                                    cm.getName(), cm.getParameterCount(), cm.getReturnType().getSimpleName());
                                            }
                                        }
                                        // Also check fields
                                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] ArchetypeChunk fields:");
                                        for (java.lang.reflect.Field f : chunk.getClass().getDeclaredFields()) {
                                            plugin.getLogger().at(Level.INFO).log("[CleanWarps]   %s : %s",
                                                f.getName(), f.getType().getSimpleName());
                                        }
                                    }

                                    // Try to access refs via field
                                    try {
                                        java.lang.reflect.Field refsField = chunk.getClass().getDeclaredField("refs");
                                        refsField.setAccessible(true);
                                        Object refs = refsField.get(chunk);
                                        if (refs != null && refs.getClass().isArray()) {
                                            Object[] refArray = (Object[]) refs;
                                            if (chunkCount[0] == 1) {
                                                plugin.getLogger().at(Level.INFO).log("[CleanWarps] Found refs field with %d entries", refArray.length);
                                            }
                                            for (Object refObj : refArray) {
                                                if (refObj instanceof Ref) {
                                                    processedRefs[0]++;
                                                    @SuppressWarnings("unchecked")
                                                    Ref<EntityStore> entityRef = (Ref<EntityStore>) refObj;
                                                    processEntityRef(finalStore, entityRef, finalTransformType,
                                                        wxFinal, wyFinal, wzFinal, searchRadius, nearbyEntities, foundTeleporter);
                                                }
                                            }
                                        }
                                    } catch (NoSuchFieldException nsfe) {
                                        // Try other field names
                                        if (chunkCount[0] == 1) {
                                            plugin.getLogger().at(Level.INFO).log("[CleanWarps] No 'refs' field, trying size()=%d", chunk.size());
                                        }
                                    }
                                }
                                return null;
                            }
                        );

                        forEachChunkMethod.invoke(store, consumer);
                        plugin.getLogger().at(Level.INFO).log("[CleanWarps] Iterated %d chunks, processed %d refs", chunkCount[0], processedRefs[0]);
                    }
                } catch (Exception forEachEx) {
                    plugin.getLogger().at(Level.WARNING).log("[CleanWarps] forEachChunk failed: %s", forEachEx.getMessage());
                    forEachEx.printStackTrace();
                }

            } catch (Exception iterEx) {
                plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Entity iteration error: " + iterEx.getMessage());
                iterEx.printStackTrace();
            }

            // Also check blocks as a fallback
            int baseX = (int) Math.floor(wx);
            int baseY = (int) Math.floor(wy);
            int baseZ = (int) Math.floor(wz);

            int blockId = world.getBlock(baseX, baseY, baseZ);
            if (blockId != 0) {
                BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
                String blockName = blockType != null ? blockType.getId() : "unknown";
                plugin.getLogger().at(Level.INFO).log(
                    "[CleanWarps] Block at warp position: id=%d, name='%s'",
                    blockId, blockName
                );
                if (blockName.toLowerCase().contains("teleport")) {
                    foundTeleporter[0] = true;
                }
            }

            // Log summary
            plugin.getLogger().at(Level.INFO).log(
                "[CleanWarps] === Search complete. Nearby entities: %d. Teleporter found: %s ===",
                nearbyEntities.size(), foundTeleporter[0]
            );

            if (foundTeleporter[0]) {
                sendMessage(player, "&a    Found teleporter/interaction entity nearby!");
                return true;
            }

            sendMessage(player, "&7    No teleporter found (iterated " + entityCount + " entities)");
            if (!nearbyEntities.isEmpty()) {
                sendMessage(player, "&8    Nearby entities found:");
                for (String info : nearbyEntities) {
                    sendMessage(player, "&8      " + info);
                }
            } else {
                sendMessage(player, "&8    No entities within " + searchRadius + " blocks");
            }

            return false;

        } catch (Exception e) {
            sendMessage(player, "&c    Error: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log(
                "[CleanWarps] Error searching near %.1f,%.1f,%.1f: %s",
                wx, wy, wz, e.getMessage()
            );
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processEntityRef(
            Store<EntityStore> store,
            Ref<EntityStore> entityRef,
            com.hypixel.hytale.component.ComponentType transformComponentType,
            double wx, double wy, double wz,
            double searchRadius,
            List<String> nearbyEntities,
            boolean[] foundTeleporter) {
        try {
            Object transform = store.getComponent(entityRef, transformComponentType);
            if (transform == null) return;

            // Get position from transform
            java.lang.reflect.Method getPosMethod = transform.getClass().getMethod("getPosition");
            Object pos = getPosMethod.invoke(transform);
            if (pos == null) return;

            // Get x, y, z from position (Vector3f uses fields or getX/getY/getZ)
            float px, py, pz;
            try {
                // Try method accessors first
                java.lang.reflect.Method getX = pos.getClass().getMethod("getX");
                java.lang.reflect.Method getY = pos.getClass().getMethod("getY");
                java.lang.reflect.Method getZ = pos.getClass().getMethod("getZ");
                px = ((Number) getX.invoke(pos)).floatValue();
                py = ((Number) getY.invoke(pos)).floatValue();
                pz = ((Number) getZ.invoke(pos)).floatValue();
            } catch (NoSuchMethodException e) {
                // Try x(), y(), z() methods
                try {
                    java.lang.reflect.Method getX = pos.getClass().getMethod("x");
                    java.lang.reflect.Method getY = pos.getClass().getMethod("y");
                    java.lang.reflect.Method getZ = pos.getClass().getMethod("z");
                    px = ((Number) getX.invoke(pos)).floatValue();
                    py = ((Number) getY.invoke(pos)).floatValue();
                    pz = ((Number) getZ.invoke(pos)).floatValue();
                } catch (NoSuchMethodException e2) {
                    // Try field access
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
            }

            double dist = Math.sqrt(
                Math.pow(px - wx, 2) +
                Math.pow(py - wy, 2) +
                Math.pow(pz - wz, 2)
            );

            if (dist <= searchRadius) {
                // Get archetype/entity type info
                String archetypeName = "Unknown";
                try {
                    // Try to get archetype via reflection
                    java.lang.reflect.Method getArchetypeMethod = null;
                    for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                        if (m.getName().contains("Archetype") && m.getParameterCount() == 1) {
                            getArchetypeMethod = m;
                            break;
                        }
                    }
                    if (getArchetypeMethod != null) {
                        Object archetype = getArchetypeMethod.invoke(store, entityRef);
                        if (archetype != null) {
                            archetypeName = archetype.toString();
                        }
                    }
                    // Also try to get the ref's string representation
                    if (archetypeName.equals("Unknown")) {
                        archetypeName = entityRef.toString();
                    }
                } catch (Exception ae) {
                    archetypeName = entityRef.toString();
                }

                String entityInfo = String.format("Entity at (%.1f,%.1f,%.1f) dist=%.1f type=%s",
                    px, py, pz, dist, archetypeName);

                plugin.getLogger().at(Level.INFO).log("[CleanWarps] NEARBY: " + entityInfo);
                nearbyEntities.add(entityInfo);

                // Check if it's a teleporter or interaction-related
                String lowerType = archetypeName.toLowerCase();
                if (lowerType.contains("teleport") || lowerType.contains("portal") ||
                    lowerType.contains("warp") || lowerType.contains("interaction") ||
                    lowerType.contains("zone")) {
                    foundTeleporter[0] = true;
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] FOUND TELEPORTER/INTERACTION!");

                    // DUMP ALL COMPONENT DATA for debugging
                    dumpAllComponentData(store, entityRef, px, py, pz);
                }
            }
        } catch (Exception e) {
            // Skip entities we can't process
        }
    }

    /**
     * Dumps ALL component data for a teleporter entity to help debug
     * why one teleporter shows ghost "Press F" and another doesn't.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dumpAllComponentData(Store<EntityStore> store, Ref<EntityStore> entityRef, float px, float py, float pz) {
        plugin.getLogger().at(Level.INFO).log("[CleanWarps] ===============================================");
        plugin.getLogger().at(Level.INFO).log("[CleanWarps] COMPONENT DUMP for entity at (%.1f, %.1f, %.1f)", px, py, pz);
        plugin.getLogger().at(Level.INFO).log("[CleanWarps] ===============================================");

        try {
            // Get the archetype to find all component types
            java.lang.reflect.Method getArchetypeMethod = null;
            for (java.lang.reflect.Method m : store.getClass().getMethods()) {
                if (m.getName().equals("getArchetype") && m.getParameterCount() == 1) {
                    getArchetypeMethod = m;
                    break;
                }
            }

            if (getArchetypeMethod == null) {
                plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Could not find getArchetype method");
                return;
            }

            Object archetype = getArchetypeMethod.invoke(store, entityRef);
            if (archetype == null) {
                plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Archetype is null");
                return;
            }

            // Get component types from archetype via field access (no getter method exists)
            java.lang.reflect.Field componentTypesField = null;
            try {
                componentTypesField = archetype.getClass().getDeclaredField("componentTypes");
                componentTypesField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // Try without "Declared"
                for (java.lang.reflect.Field f : archetype.getClass().getFields()) {
                    if (f.getName().equals("componentTypes")) {
                        componentTypesField = f;
                        break;
                    }
                }
            }

            if (componentTypesField == null) {
                plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Could not find componentTypes field");
                return;
            }

            Object componentTypes = componentTypesField.get(archetype);
            if (componentTypes == null || !componentTypes.getClass().isArray()) {
                plugin.getLogger().at(Level.WARNING).log("[CleanWarps] componentTypes is null or not array");
                return;
            }

            Object[] typeArray = (Object[]) componentTypes;
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] Entity has %d component types", typeArray.length);

            // Iterate through each component type and dump its data
            for (Object compType : typeArray) {
                if (compType == null) continue;

                try {
                    // Get component instance
                    Object component = store.getComponent(entityRef, (com.hypixel.hytale.component.ComponentType) compType);
                    if (component == null) {
                        continue; // Skip null components silently to reduce noise
                    }

                    String componentClassName = component.getClass().getSimpleName();

                    // Special handling for key interaction-related components
                    if (componentClassName.contains("Interaction") || componentClassName.contains("Warp") ||
                        componentClassName.contains("Nameplate") || componentClassName.contains("Transform")) {
                        plugin.getLogger().at(Level.INFO).log("[CleanWarps]   [%s] (%s):", compType.toString(), componentClassName);
                        // Dump all fields of the component
                        dumpObjectFields(component, "      ");
                    }

                } catch (Exception compEx) {
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps]   [%s] ERROR: %s", compType.toString(), compEx.getMessage());
                }
            }

            // SPECIAL: Try to get the Interactions component (index 73) explicitly
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] === Checking for Interactions component (index 73) ===");
            try {
                Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
                java.lang.reflect.Method getModuleMethod = interactionModuleClass.getMethod("get");
                Object interactionModule = getModuleMethod.invoke(null);
                java.lang.reflect.Method getInteractionsCompMethod = interactionModuleClass.getMethod("getInteractionsComponentType");
                @SuppressWarnings({"unchecked", "rawtypes"})
                com.hypixel.hytale.component.ComponentType interactionsCompType =
                    (com.hypixel.hytale.component.ComponentType) getInteractionsCompMethod.invoke(interactionModule);

                Object interactions = store.getComponent(entityRef, interactionsCompType);
                if (interactions != null) {
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] FOUND Interactions component on this entity!");
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] Interactions class: %s", interactions.getClass().getName());

                    // Dump ALL fields of Interactions
                    for (java.lang.reflect.Field f : interactions.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        try {
                            Object value = f.get(interactions);
                            String valueStr = value == null ? "null" : value.toString();
                            if (valueStr.length() > 200) valueStr = valueStr.substring(0, 200) + "...";
                            plugin.getLogger().at(Level.INFO).log("[CleanWarps]   Interactions.%s = %s", f.getName(), valueStr);
                        } catch (Exception fe) {
                            plugin.getLogger().at(Level.INFO).log("[CleanWarps]   Interactions.%s = <error: %s>", f.getName(), fe.getMessage());
                        }
                    }

                    // Try to get methods that might reveal interaction state
                    for (java.lang.reflect.Method m : interactions.getClass().getMethods()) {
                        if (m.getDeclaringClass() == Object.class) continue;
                        if (m.getParameterCount() == 0 && !m.getReturnType().equals(void.class)) {
                            String name = m.getName();
                            if (name.startsWith("get") || name.startsWith("is") || name.startsWith("has")) {
                                try {
                                    Object result = m.invoke(interactions);
                                    String resultStr = result == null ? "null" : result.toString();
                                    if (resultStr.length() > 200) resultStr = resultStr.substring(0, 200) + "...";
                                    plugin.getLogger().at(Level.INFO).log("[CleanWarps]   Interactions.%s() = %s", name, resultStr);
                                } catch (Exception me) {
                                    // Skip method errors
                                }
                            }
                        }
                    }
                } else {
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] No Interactions component on this entity");
                }
            } catch (Exception intEx) {
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] Error checking Interactions: %s", intEx.getMessage());
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log("[CleanWarps] Error dumping components: %s", e.getMessage());
            e.printStackTrace();
        }

        plugin.getLogger().at(Level.INFO).log("[CleanWarps] ===============================================");
    }

    /**
     * Recursively dumps all fields of an object for debugging.
     */
    private void dumpObjectFields(Object obj, String indent) {
        if (obj == null) {
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %snull", indent);
            return;
        }

        Class<?> clazz = obj.getClass();
        plugin.getLogger().at(Level.INFO).log("[CleanWarps] %sDumping %s", indent, clazz.getName());

        // For primitives, wrappers, strings, just print the value
        if (clazz.isPrimitive() || obj instanceof Number || obj instanceof Boolean ||
            obj instanceof String || obj instanceof Character || obj instanceof Enum) {
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %sValue: %s", indent, obj.toString());
            return;
        }

        // For Ref types, print validity and ref info
        if (obj instanceof Ref) {
            Ref<?> ref = (Ref<?>) obj;
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %sRef[valid=%s, %s]", indent, ref.isValid(), ref.toString());
            return;
        }

        // For arrays (handle both Object[] and primitive arrays)
        if (clazz.isArray()) {
            int length = java.lang.reflect.Array.getLength(obj);
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %sArray[%d elements, type=%s]", indent, length, clazz.getComponentType().getName());
            int printCount = Math.min(length, 3);
            for (int i = 0; i < printCount; i++) {
                Object elem = java.lang.reflect.Array.get(obj, i);
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  [%d]: %s", indent, i,
                    elem != null ? elem.toString() : "null");
            }
            if (length > 3) {
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  ... and %d more", indent, length - 3);
            }
            return;
        }

        // For collections
        if (obj instanceof java.util.Collection) {
            java.util.Collection<?> col = (java.util.Collection<?>) obj;
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %sCollection[%d elements]", indent, col.size());
            int i = 0;
            for (Object item : col) {
                if (i >= 3) {
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  ... and %d more", indent, col.size() - 3);
                    break;
                }
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  [%d]: %s", indent, i,
                    item != null ? item.toString() : "null");
                i++;
            }
            return;
        }

        // For maps
        if (obj instanceof java.util.Map) {
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) obj;
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %sMap[%d entries]", indent, map.size());
            int i = 0;
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                if (i >= 3) {
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  ... and %d more", indent, map.size() - 3);
                    break;
                }
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  %s -> %s", indent,
                    entry.getKey() != null ? entry.getKey().toString() : "null",
                    entry.getValue() != null ? entry.getValue().toString() : "null");
                i++;
            }
            return;
        }

        // For other objects, dump declared fields
        try {
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %sObject has %d declared fields", indent, fields.length);

            int fieldCount = 0;
            for (java.lang.reflect.Field field : fields) {
                // Skip static fields
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;

                fieldCount++;
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);

                    String fieldName = field.getName();
                    String valueStr;

                    if (value == null) {
                        valueStr = "null";
                    } else if (value instanceof Number || value instanceof Boolean ||
                               value instanceof String || value instanceof Character || value instanceof Enum) {
                        valueStr = value.toString();
                    } else if (value instanceof Ref) {
                        Ref<?> ref = (Ref<?>) value;
                        valueStr = "Ref[valid=" + ref.isValid() + ", " + ref.toString() + "]";
                    } else if (value.getClass().isArray()) {
                        int arrLen = java.lang.reflect.Array.getLength(value);
                        valueStr = "Array[" + arrLen + ", type=" + value.getClass().getComponentType().getName() + "]";
                    } else if (value instanceof java.util.Collection) {
                        valueStr = "Collection[" + ((java.util.Collection<?>) value).size() + "]";
                    } else if (value instanceof java.util.Map) {
                        valueStr = "Map[" + ((java.util.Map<?, ?>) value).size() + "]";
                    } else {
                        // Try to get a meaningful string representation
                        try {
                            valueStr = value.toString();
                            if (valueStr.length() > 100) {
                                valueStr = valueStr.substring(0, 100) + "...";
                            }
                        } catch (Exception toStringEx) {
                            valueStr = value.getClass().getSimpleName() + "@" + Integer.toHexString(value.hashCode());
                        }
                    }

                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  %s = %s", indent, fieldName, valueStr);
                } catch (Exception fieldEx) {
                    plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  %s = <error: %s>", indent, field.getName(), fieldEx.getMessage());
                }
            }

            if (fieldCount == 0) {
                plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s  <no instance fields>", indent);
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.INFO).log("[CleanWarps] %s<error reading fields: %s>", indent, e.getMessage());
        }
    }

    private void sendMessage(Player player, String message) {
        ChatColorUtil.sendMessage(player, message);
    }

    /**
     * Helper class to store warp info during processing
     */
    private static class WarpInfo {
        final String id;
        final String world;
        final double x, y, z;
        final JsonElement jsonElement;

        WarpInfo(String id, String world, double x, double y, double z, JsonElement jsonElement) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.jsonElement = jsonElement;
        }
    }
}
