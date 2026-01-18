package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

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
        sendMessage(player, "&7/cleanwarps help &f- Show this help");
        sendMessage(player, "&7");
        sendMessage(player, "&eThis checks if teleporter blocks exist at warp locations.");
        sendMessage(player, "&eOrphaned warps (no block) can cause ghost 'Press F' prompts.");
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
                boolean blockExists = checkTeleporterExists(world, (int) x, (int) y, (int) z);

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
            if (removeOrphans) {
                sendMessage(player, "&e");
                sendMessage(player, "&eRemoving orphaned warps from warps.json...");

                // Build new JSON with only valid warps
                JsonArray newWarps = new JsonArray();
                for (WarpInfo valid : validWarps) {
                    newWarps.add(valid.jsonElement);
                }

                root.add("Warps", newWarps);

                // Write back to file
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                String newJson = gson.toJson(root);
                Files.write(warpsPath, newJson.getBytes());

                sendMessage(player, "&aRemoved " + orphanedWarps.size() + " orphaned warp(s)!");
                sendMessage(player, "&7warps.json has been updated.");
                sendMessage(player, "&e");
                sendMessage(player, "&eNote: You may need to rejoin for ghost interactions to clear.");

                plugin.getLogger().at(Level.INFO).log(
                    "[CleanWarps] Removed %d orphaned warps: %s",
                    orphanedWarps.size(),
                    orphanedWarps.stream().map(w -> w.id).reduce((a, b) -> a + ", " + b).orElse("none")
                );
            } else {
                sendMessage(player, "&7");
                sendMessage(player, "&7Run &f/cleanwarps clean &7to remove orphaned warps.");
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

    private boolean checkTeleporterExists(World world, int x, int y, int z) {
        try {
            // Get the block at the warp position
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                plugin.getLogger().at(Level.FINE).log("[CleanWarps] ChunkStore is null");
                return false;
            }

            // Get block ID at position
            int blockId = world.getBlock(x, y, z);

            if (blockId == 0) {
                // Air block - no teleporter
                return false;
            }

            // Get the BlockType for this block ID
            BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
            if (blockType == null) {
                return false;
            }

            // Check if it's a teleporter
            String blockName = blockType.getId();
            boolean isTeleporter = blockName != null &&
                    (blockName.equalsIgnoreCase(TELEPORTER_BLOCK_NAME) ||
                     blockName.toLowerCase().contains("teleport"));

            plugin.getLogger().at(Level.FINE).log(
                "[CleanWarps] Block at %d,%d,%d: id=%d, name=%s, isTeleporter=%s",
                x, y, z, blockId, blockName, isTeleporter
            );

            return isTeleporter;

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[CleanWarps] Error checking block at %d,%d,%d: %s",
                x, y, z, e.getMessage()
            );
            return false;
        }
    }

    private void sendMessage(Player player, String message) {
        String formatted = message.replace("&", "\u00A7");
        player.sendMessage(Message.raw(formatted));
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
