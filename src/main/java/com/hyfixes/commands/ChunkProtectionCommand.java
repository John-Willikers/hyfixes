package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hyfixes.systems.ChunkProtectionRegistry;
import com.hyfixes.systems.ChunkProtectionScanner;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/**
 * Command: /chunkprotect
 * Shows and manages chunk protection status
 * 
 * Usage:
 * - /chunkprotect - Show protection status
 * - /chunkprotect list - List protected chunks
 * - /chunkprotect scan - Force scan all loaded chunks
 * - /chunkprotect clear - Clear all protection (dangerous)
 */
public class ChunkProtectionCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;

    public ChunkProtectionCommand(HyFixes plugin) {
        super("chunkprotect", "hyfixes.command.chunkprotect.desc");
        this.plugin = plugin;
        addAliases("cp", "chunkprot");
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

        ChunkProtectionRegistry registry = plugin.getChunkProtectionRegistry();
        ChunkProtectionScanner scanner = plugin.getChunkProtectionScanner();

        if (registry == null) {
            sendMessage(player, "&c[HyFixes] Chunk protection system is not enabled");
            sendMessage(player, "&7Enable it in config.json: chunkProtection.enabled = true");
            return;
        }

        // Parse subcommand
        String inputString = context.getInputString();

        // DEBUG: Show what we're getting
        sendMessage(player, "&7[DEBUG] Raw input: '" + inputString + "'");

        String subcommand = "";
        if (inputString != null && !inputString.trim().isEmpty()) {
            String[] parts = inputString.trim().split("\\s+");
            sendMessage(player, "&7[DEBUG] Parts count: " + parts.length + ", parts[0]: '" + parts[0] + "'");

            if (parts.length == 1) {
                // Just one word - check if it's a subcommand
                String word = parts[0].toLowerCase();
                if (!word.equals("chunkprotect") && !word.equals("/chunkprotect") &&
                    !word.equals("cp") && !word.equals("/cp") && !word.equals("chunkprot")) {
                    subcommand = word;
                }
            } else if (parts.length > 1) {
                // Multiple words - subcommand is the second one
                subcommand = parts[1].toLowerCase();
            }
        }

        sendMessage(player, "&7[DEBUG] Subcommand: '" + subcommand + "'");

        switch (subcommand) {
            case "list":
                showProtectedChunksList(player, registry);
                break;
            case "scan":
                forceScan(player, world, registry, scanner);
                break;
            case "clear":
                clearAllProtection(player, registry);
                break;
            case "debug":
                debugNearbyEntities(player, store, ref, world, registry);
                break;
            case "check":
                checkCurrentChunk(player, store, ref, world, registry);
                break;
            case "teleporters":
            case "tp":
                debugTeleporters(player, world);
                break;
            default:
                showStatus(player, registry, scanner);
                break;
        }
    }

    private void showStatus(Player player, ChunkProtectionRegistry registry, ChunkProtectionScanner scanner) {
        sendMessage(player, "&6=== Chunk Protection Status ===");
        
        String status = registry.getStatus();
        for (String line : status.split("\n")) {
            if (line.startsWith("===")) {
                continue; // Skip the header from registry
            }
            sendMessage(player, "&7" + line.trim());
        }
        
        if (scanner != null) {
            sendMessage(player, "&6Scanner: &7" + scanner.getStatus());
        }
        
        sendMessage(player, "&6Usage:");
        sendMessage(player, "&7  /chunkprotect list - List protected chunks");
        sendMessage(player, "&7  /chunkprotect scan - Force scan for protected content");
        sendMessage(player, "&7  /chunkprotect teleporters - Scan ChunkStore for teleporters");
        sendMessage(player, "&7  /chunkprotect debug - Show nearby entity archetypes");
        sendMessage(player, "&7  /chunkprotect check - Check if current chunk is protected");
        sendMessage(player, "&7  /chunkprotect clear - Clear all protection (dangerous!)");
    }

    private void showProtectedChunksList(Player player, ChunkProtectionRegistry registry) {
        sendMessage(player, "&6=== Protected Chunks ===");
        
        String list = registry.getProtectedChunksList();
        for (String line : list.split("\n")) {
            sendMessage(player, "&7" + line);
        }
    }

    private void forceScan(Player player, World world, ChunkProtectionRegistry registry, ChunkProtectionScanner scanner) {
        if (scanner == null) {
            sendMessage(player, "&c[HyFixes] Scanner not available");
            return;
        }

        sendMessage(player, "&6[HyFixes] Starting chunk protection scan...");
        
        int before = registry.getProtectedChunkCount();
        long currentTick = System.currentTimeMillis() / 50; // Approximate tick
        
        try {
            int newlyProtected = scanner.scanWorld(world, currentTick);
            int after = registry.getProtectedChunkCount();
            
            sendMessage(player, "&a[HyFixes] Scan complete!");
            sendMessage(player, "&7  Newly protected: &e" + newlyProtected);
            sendMessage(player, "&7  Total protected: &e" + after);
            sendMessage(player, "&7  (was " + before + " before scan)");
        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Scan error: " + e.getMessage());
        }
    }

    private void clearAllProtection(Player player, ChunkProtectionRegistry registry) {
        int count = registry.getProtectedChunkCount();
        
        if (count == 0) {
            sendMessage(player, "&7[HyFixes] No chunks are currently protected.");
            return;
        }
        
        sendMessage(player, "&c[HyFixes] WARNING: Clearing all chunk protection!");
        sendMessage(player, "&c[HyFixes] This may allow teleporters to be cleaned up!");
        
        int cleared = registry.clearAllProtections();
        
        sendMessage(player, "&6[HyFixes] Cleared protection from " + cleared + " chunks.");
        sendMessage(player, "&7Protection will be re-detected on next scan cycle.");
    }

    private void sendMessage(Player player, String message) {
        String formatted = message.replace("&", "\u00A7");
        player.sendMessage(Message.raw(formatted));
    }

    /**
     * Check if the player's current chunk is protected.
     */
    @SuppressWarnings("rawtypes")
    private void checkCurrentChunk(Player player, Store<EntityStore> store, Ref<EntityStore> ref,
                                   World world, ChunkProtectionRegistry registry) {
        try {
            // Get player position
            ComponentType transformType = TransformComponent.getComponentType();
            TransformComponent transform = (TransformComponent) store.getComponent(ref, transformType);
            if (transform == null) {
                sendMessage(player, "&c[HyFixes] Cannot get your position");
                return;
            }

            Object pos = transform.getPosition();
            float px, pz;
            try {
                Method getX = pos.getClass().getMethod("getX");
                Method getZ = pos.getClass().getMethod("getZ");
                px = ((Number) getX.invoke(pos)).floatValue();
                pz = ((Number) getZ.invoke(pos)).floatValue();
            } catch (NoSuchMethodException e) {
                Method xm = pos.getClass().getMethod("x");
                Method zm = pos.getClass().getMethod("z");
                px = ((Number) xm.invoke(pos)).floatValue();
                pz = ((Number) zm.invoke(pos)).floatValue();
            }

            int chunkX = (int) Math.floor(px) >> 4;
            int chunkZ = (int) Math.floor(pz) >> 4;
            long chunkIndex = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

            sendMessage(player, "&6=== Current Chunk Check ===");
            sendMessage(player, "&7Position: &e" + String.format("%.1f, %.1f", px, pz));
            sendMessage(player, "&7Chunk: &e[" + chunkX + ", " + chunkZ + "]");
            sendMessage(player, "&7Chunk Index: &e" + chunkIndex);

            if (registry.isChunkProtected(chunkIndex)) {
                ChunkProtectionRegistry.ProtectionInfo info = registry.getProtectionInfo(chunkIndex);
                sendMessage(player, "&a[OK] This chunk IS protected!");
                sendMessage(player, "&7Reason: &e" + (info != null ? info.reason : "Unknown"));
            } else {
                sendMessage(player, "&c[NO] This chunk is NOT protected");
                sendMessage(player, "&7Use &e/chunkprotect debug &7to see nearby entities");
            }
        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
        }
    }

    /**
     * Debug command: Show all nearby entity archetypes and whether they match keywords.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void debugNearbyEntities(Player player, Store<EntityStore> store, Ref<EntityStore> ref,
                                     World world, ChunkProtectionRegistry registry) {
        sendMessage(player, "&6=== Debug: Nearby Entity Archetypes ===");

        try {
            // Get player position
            ComponentType transformType = TransformComponent.getComponentType();
            TransformComponent playerTransform = (TransformComponent) store.getComponent(ref, transformType);
            if (playerTransform == null) {
                sendMessage(player, "&c[HyFixes] Cannot get your position");
                return;
            }

            Object playerPos = playerTransform.getPosition();
            final float[] playerCoords = new float[3];
            try {
                Method getX = playerPos.getClass().getMethod("getX");
                Method getY = playerPos.getClass().getMethod("getY");
                Method getZ = playerPos.getClass().getMethod("getZ");
                playerCoords[0] = ((Number) getX.invoke(playerPos)).floatValue();
                playerCoords[1] = ((Number) getY.invoke(playerPos)).floatValue();
                playerCoords[2] = ((Number) getZ.invoke(playerPos)).floatValue();
            } catch (NoSuchMethodException e) {
                Method xm = playerPos.getClass().getMethod("x");
                Method ym = playerPos.getClass().getMethod("y");
                Method zm = playerPos.getClass().getMethod("z");
                playerCoords[0] = ((Number) xm.invoke(playerPos)).floatValue();
                playerCoords[1] = ((Number) ym.invoke(playerPos)).floatValue();
                playerCoords[2] = ((Number) zm.invoke(playerPos)).floatValue();
            }

            sendMessage(player, "&7Your position: &e" + String.format("%.1f, %.1f, %.1f", playerCoords[0], playerCoords[1], playerCoords[2]));
            sendMessage(player, "&7Keywords: &e" + String.join(", ", ConfigManager.getInstance().getProtectedEntityKeywords()));

            // Get entity store
            Store<EntityStore> entityStore = world.getEntityStore().getStore();
            if (entityStore == null) {
                sendMessage(player, "&c[HyFixes] Cannot access entity store");
                return;
            }

            // Find getArchetype method
            Method getArchetypeMethod = null;
            for (Method m : entityStore.getClass().getMethods()) {
                if (m.getName().equals("getArchetype") && m.getParameterCount() == 1) {
                    getArchetypeMethod = m;
                    break;
                }
            }

            // Find forEachChunk method
            Method forEachChunkMethod = null;
            for (Method m : entityStore.getClass().getMethods()) {
                if (m.getName().equals("forEachChunk") && m.getParameterCount() == 1) {
                    forEachChunkMethod = m;
                    break;
                }
            }

            if (forEachChunkMethod == null) {
                sendMessage(player, "&c[HyFixes] Cannot find forEachChunk method");
                return;
            }

            final int[] entityCount = {0};
            final int[] matchCount = {0};
            final StringBuilder nearbyInfo = new StringBuilder();
            final float searchRadius = 50.0f;
            final Method archMethod = getArchetypeMethod;
            final String[] keywords = ConfigManager.getInstance().getProtectedEntityKeywords();

            Class<?> consumerType = forEachChunkMethod.getParameterTypes()[0];
            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                consumerType.getClassLoader(),
                new Class<?>[] { consumerType },
                (proxy, method, args) -> {
                    // Handle Object methods properly to avoid NPE on primitive returns
                    String methodName = method.getName();
                    if (methodName.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    } else if (methodName.equals("equals")) {
                        return proxy == args[0];
                    } else if (methodName.equals("toString")) {
                        return "DebugConsumer@" + System.identityHashCode(proxy);
                    } else if (methodName.equals("accept")) {
                        ArchetypeChunk chunk = (ArchetypeChunk) args[0];

                        try {
                            Field refsField = chunk.getClass().getDeclaredField("refs");
                            refsField.setAccessible(true);
                            Object refs = refsField.get(chunk);

                            if (refs != null && refs.getClass().isArray()) {
                                Object[] refArray = (Object[]) refs;

                                for (Object refObj : refArray) {
                                    if (!(refObj instanceof Ref)) continue;
                                    Ref<EntityStore> entityRef = (Ref<EntityStore>) refObj;

                                    // Get position
                                    TransformComponent etransform = (TransformComponent) entityStore.getComponent(entityRef, transformType);
                                    if (etransform == null) continue;

                                    Object epos = etransform.getPosition();
                                    if (epos == null) continue;

                                    float ex, ey, ez;
                                    try {
                                        Method getX = epos.getClass().getMethod("getX");
                                        Method getY = epos.getClass().getMethod("getY");
                                        Method getZ = epos.getClass().getMethod("getZ");
                                        ex = ((Number) getX.invoke(epos)).floatValue();
                                        ey = ((Number) getY.invoke(epos)).floatValue();
                                        ez = ((Number) getZ.invoke(epos)).floatValue();
                                    } catch (NoSuchMethodException e) {
                                        Method xm = epos.getClass().getMethod("x");
                                        Method ym = epos.getClass().getMethod("y");
                                        Method zm = epos.getClass().getMethod("z");
                                        ex = ((Number) xm.invoke(epos)).floatValue();
                                        ey = ((Number) ym.invoke(epos)).floatValue();
                                        ez = ((Number) zm.invoke(epos)).floatValue();
                                    }

                                    // Check distance
                                    float dist = (float) Math.sqrt(
                                        Math.pow(ex - playerCoords[0], 2) + Math.pow(ey - playerCoords[1], 2) + Math.pow(ez - playerCoords[2], 2)
                                    );

                                    if (dist > searchRadius) continue;

                                    entityCount[0]++;

                                    // Get archetype name
                                    String archetypeName = "Unknown";
                                    if (archMethod != null) {
                                        Object archetype = archMethod.invoke(entityStore, entityRef);
                                        if (archetype != null) {
                                            archetypeName = extractArchetypeName(archetype);
                                        }
                                    }

                                    // Check if matches keywords
                                    boolean matches = false;
                                    String lowerName = archetypeName.toLowerCase();
                                    for (String kw : keywords) {
                                        if (lowerName.contains(kw.toLowerCase())) {
                                            matches = true;
                                            break;
                                        }
                                    }

                                    if (matches) {
                                        matchCount[0]++;
                                    }

                                    // Only show first 10 entities
                                    if (entityCount[0] <= 10) {
                                        String matchStr = matches ? "&a[OK]" : "&c[NO]";
                                        nearbyInfo.append(String.format(
                                            "%s &7%.0fm: &e%s\n",
                                            matchStr, dist, archetypeName
                                        ));
                                    }
                                }
                            }
                        } catch (NoSuchFieldException ignored) {
                        } catch (Exception ignored) {
                        }
                        return null; // void method
                    }
                    // For any other method, return appropriate default for its return type
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType == double.class) return 0.0;
                    if (returnType == float.class) return 0.0f;
                    if (returnType == char.class) return '\0';
                    if (returnType == byte.class) return (byte) 0;
                    if (returnType == short.class) return (short) 0;
                    return null;
                }
            );

            forEachChunkMethod.invoke(entityStore, consumer);

            sendMessage(player, "&7Found &e" + entityCount[0] + " &7entities within " + (int) searchRadius + " blocks");
            sendMessage(player, "&7Keyword matches: &e" + matchCount[0]);

            if (nearbyInfo.length() > 0) {
                sendMessage(player, "&6Nearby entities (first 10):");
                for (String line : nearbyInfo.toString().split("\n")) {
                    if (!line.isEmpty()) {
                        sendMessage(player, line);
                    }
                }
            }

            if (entityCount[0] > 10) {
                sendMessage(player, "&7... and " + (entityCount[0] - 10) + " more");
            }

            if (matchCount[0] == 0 && entityCount[0] > 0) {
                sendMessage(player, "&c");
                sendMessage(player, "&cNo entities matched protection keywords!");
                sendMessage(player, "&7Your portal archetype may have a different name.");
                sendMessage(player, "&7Check the archetypes above and update config if needed.");
            }

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Extract a clean archetype name from an archetype object.
     */
    private String extractArchetypeName(Object archetype) {
        // Try getId()
        try {
            Method getId = archetype.getClass().getMethod("getId");
            Object id = getId.invoke(archetype);
            if (id != null) return id.toString();
        } catch (Exception ignored) {}

        // Try getName()
        try {
            Method getName = archetype.getClass().getMethod("getName");
            Object name = getName.invoke(archetype);
            if (name != null) return name.toString();
        } catch (Exception ignored) {}

        // Try getIdentifier()
        try {
            Method getIdentifier = archetype.getClass().getMethod("getIdentifier");
            Object identifier = getIdentifier.invoke(archetype);
            if (identifier != null) return identifier.toString();
        } catch (Exception ignored) {}

        // Parse toString()
        String str = archetype.toString();

        // Pattern: "name=XXX" -> extract XXX
        if (str.contains("name=")) {
            int start = str.indexOf("name=") + 5;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("]", start);
            if (end > start) {
                return str.substring(start, end).trim();
            }
        }

        // Pattern: "SomeArchetype[...]" -> extract "SomeArchetype"
        if (str.contains("[")) {
            return str.substring(0, str.indexOf("[")).trim();
        }

        // Limit length
        if (str.length() > 60) {
            return str.substring(0, 57) + "...";
        }
        return str;
    }

    /**
     * Debug command: Scan ChunkStore for Teleporter components.
     * This uses the proper Hytale API to find teleporters.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void debugTeleporters(Player player, World world) {
        sendMessage(player, "&6=== Debug: Teleporter Scan (ChunkStore) ===");

        try {
            // Get ChunkStore (NOT EntityStore!)
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                sendMessage(player, "&c[HyFixes] Cannot access ChunkStore");
                return;
            }

            Store<ChunkStore> store = chunkStore.getStore();
            if (store == null) {
                sendMessage(player, "&c[HyFixes] Cannot access ChunkStore's Store");
                return;
            }

            // Find TeleporterPlugin
            Class<?> teleporterPluginClass;
            try {
                teleporterPluginClass = Class.forName(
                    "com.hypixel.hytale.builtin.adventure.teleporter.TeleporterPlugin"
                );
            } catch (ClassNotFoundException e) {
                sendMessage(player, "&c[HyFixes] TeleporterPlugin not found!");
                sendMessage(player, "&7This means the Teleporter plugin is not loaded.");
                return;
            }

            // Get TeleporterPlugin.get()
            Method getMethod = teleporterPluginClass.getMethod("get");
            Object teleporterPlugin = getMethod.invoke(null);

            if (teleporterPlugin == null) {
                sendMessage(player, "&c[HyFixes] TeleporterPlugin.get() returned null");
                return;
            }

            // Get component type
            Method getComponentTypeMethod = teleporterPluginClass.getMethod("getTeleporterComponentType");
            ComponentType teleporterComponentType = (ComponentType) getComponentTypeMethod.invoke(teleporterPlugin);

            if (teleporterComponentType == null) {
                sendMessage(player, "&c[HyFixes] getTeleporterComponentType() returned null");
                return;
            }

            sendMessage(player, "&aTeleporterPlugin found!");
            sendMessage(player, "&7ComponentType: &e" + teleporterComponentType.getClass().getSimpleName());

            // Find forEachChunk with Query filter
            Method forEachChunkMethod = null;
            for (Method m : store.getClass().getMethods()) {
                if (m.getName().equals("forEachChunk") && m.getParameterCount() == 2) {
                    forEachChunkMethod = m;
                    break;
                }
            }

            if (forEachChunkMethod == null) {
                sendMessage(player, "&c[HyFixes] Cannot find forEachChunk(Query, BiConsumer) method");
                return;
            }

            sendMessage(player, "&7Scanning ChunkStore for Teleporter components...");

            final int[] teleporterCount = {0};
            final int[] chunkCount = {0};
            final StringBuilder teleporterInfo = new StringBuilder();
            final ComponentType finalTeleporterType = teleporterComponentType;

            BiConsumer<ArchetypeChunk<ChunkStore>, CommandBuffer<ChunkStore>> consumer =
                (chunk, commandBuffer) -> {
                    try {
                        chunkCount[0]++;
                        int chunkSize = chunk.size();

                        for (int i = 0; i < chunkSize; i++) {
                            Object teleporter = chunk.getComponent(i, finalTeleporterType);
                            if (teleporter != null) {
                                teleporterCount[0]++;

                                // Get warp name
                                String warpName = null;
                                try {
                                    Method getWarp = teleporter.getClass().getMethod("getWarp");
                                    Object warp = getWarp.invoke(teleporter);
                                    if (warp != null && !warp.toString().isEmpty()) {
                                        warpName = warp.toString();
                                    }
                                } catch (Exception ignored) {}

                                // Get position
                                String posStr = "unknown";
                                try {
                                    Method getTransform = teleporter.getClass().getMethod("getTransform");
                                    Transform transform = (Transform) getTransform.invoke(teleporter);
                                    if (transform != null) {
                                        Vector3d pos = transform.getPosition();
                                        if (pos != null) {
                                            posStr = String.format("%.0f, %.0f, %.0f",
                                                pos.getX(), pos.getY(), pos.getZ());
                                        } else {
                                            posStr = "pos=null";
                                        }
                                    } else {
                                        posStr = "transform=null";
                                    }
                                } catch (Exception e) {
                                    posStr = "err:" + e.getClass().getSimpleName();
                                }

                                // Only show first 15
                                if (teleporterCount[0] <= 15) {
                                    String name = warpName != null ? warpName : "(unnamed)";
                                    teleporterInfo.append(String.format(
                                        "&a[OK] &e%s &7at &f%s\n", name, posStr
                                    ));
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Skip on error
                    }
                };

            // Invoke with Query filter
            forEachChunkMethod.invoke(store, teleporterComponentType, consumer);

            sendMessage(player, "&7Scanned &e" + chunkCount[0] + " &7chunks with Teleporter components");
            sendMessage(player, "&7Found &e" + teleporterCount[0] + " &7teleporters total");

            if (teleporterInfo.length() > 0) {
                sendMessage(player, "&6Teleporters found:");
                for (String line : teleporterInfo.toString().split("\n")) {
                    if (!line.isEmpty()) {
                        sendMessage(player, line);
                    }
                }
            }

            if (teleporterCount[0] > 15) {
                sendMessage(player, "&7... and " + (teleporterCount[0] - 15) + " more");
            }

            if (teleporterCount[0] == 0) {
                sendMessage(player, "&c");
                sendMessage(player, "&cNo teleporters found in ChunkStore!");
                sendMessage(player, "&7Either no teleporters exist, or the scan filter isn't working.");
                sendMessage(player, "&7Try using &e/chunkprotect scan &7to trigger a full scan.");
            }

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
