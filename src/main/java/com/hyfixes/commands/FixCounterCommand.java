package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.Message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Command: /fixcounter [blocktype] [value]
 *
 * Admin command to fix BlockCounter values that get out of sync when
 * teleporters are deleted but the placement count doesn't decrement.
 *
 * Usage:
 *   /fixcounter              - Show current teleporter count
 *   /fixcounter list         - List all tracked block counts
 *   /fixcounter reset        - Reset teleporter count to 0
 *   /fixcounter set <value>  - Set teleporter count to specific value
 *   /fixcounter <block> reset       - Reset specific block type
 *   /fixcounter <block> set <value> - Set specific block type count
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/11">GitHub Issue #11</a>
 */
public class FixCounterCommand extends AbstractPlayerCommand {

    private static final String DEFAULT_TELEPORTER_BLOCK = "Teleporter";
    private final HyFixes plugin;

    public FixCounterCommand(HyFixes plugin) {
        super("fixcounter", "hyfixes.command.fixcounter.desc");
        this.plugin = plugin;
        addAliases("fc", "blockcounter", "teleporterlimit");
    }

    @Override
    protected boolean canGeneratePermission() {
        // Only admins should use this
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

        // Parse arguments from input string (e.g., "/fixcounter reset" -> ["reset"])
        String inputString = context.getInputString();
        String[] parts = inputString.trim().split("\\s+");
        // Skip the command name itself
        String[] args = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        try {
            // Get BlockCounter from world's ChunkStore
            Object blockCounter = getBlockCounter(world);
            if (blockCounter == null) {
                sendMessage(player, "&c[HyFixes] Could not access BlockCounter for this world");
                return;
            }

            // Parse arguments
            if (args.length == 0) {
                // Show teleporter count
                showCount(player, blockCounter, DEFAULT_TELEPORTER_BLOCK);
            } else if (args[0].equalsIgnoreCase("list")) {
                // List all counts
                listAllCounts(player, blockCounter, world);
            } else if (args[0].equalsIgnoreCase("reset")) {
                // Reset teleporter count
                resetCount(player, blockCounter, DEFAULT_TELEPORTER_BLOCK, world);
            } else if (args[0].equalsIgnoreCase("set") && args.length >= 2) {
                // Set teleporter count
                int value = Integer.parseInt(args[1]);
                setCount(player, blockCounter, DEFAULT_TELEPORTER_BLOCK, value, world);
            } else if (args.length >= 2) {
                // Block type specified
                String blockType = args[0];
                if (args[1].equalsIgnoreCase("reset")) {
                    resetCount(player, blockCounter, blockType, world);
                } else if (args[1].equalsIgnoreCase("set") && args.length >= 3) {
                    int value = Integer.parseInt(args[2]);
                    setCount(player, blockCounter, blockType, value, world);
                } else {
                    showCount(player, blockCounter, blockType);
                }
            } else {
                // Show usage
                showUsage(player);
            }
        } catch (NumberFormatException e) {
            sendMessage(player, "&c[HyFixes] Invalid number format");
            showUsage(player);
        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error: " + e.getMessage());
            plugin.getLogger().at(java.util.logging.Level.WARNING).withCause(e).log("FixCounterCommand error");
        }
    }

    private Object getBlockCounter(World world) {
        try {
            // Get ChunkStore from World
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                return null;
            }

            // Get the Store from ChunkStore
            Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
            if (chunkStoreStore == null) {
                return null;
            }

            // Try to get BlockCounter resource using reflection
            // First, get the InteractionModule to get the ResourceType
            Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
            Method getMethod = interactionModuleClass.getMethod("get");
            Object interactionModule = getMethod.invoke(null);

            Method getBlockCounterResourceTypeMethod = interactionModuleClass.getMethod("getBlockCounterResourceType");
            Object resourceType = getBlockCounterResourceTypeMethod.invoke(interactionModule);

            // Get the resource from the store
            Method getResourceMethod = chunkStoreStore.getClass().getMethod("getResource", ResourceType.class);
            return getResourceMethod.invoke(chunkStoreStore, resourceType);

        } catch (Exception e) {
            plugin.getLogger().at(java.util.logging.Level.WARNING).withCause(e).log("Failed to get BlockCounter");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> getBlockPlacementCounts(Object blockCounter) throws Exception {
        // Get the blockPlacementCounts field via reflection
        Field countsField = blockCounter.getClass().getDeclaredField("blockPlacementCounts");
        countsField.setAccessible(true);

        // This is an Object2IntMap<String>, we need to convert it
        Object countsMap = countsField.get(blockCounter);

        // Convert to regular map for easier handling
        java.util.Map<String, Integer> result = new java.util.HashMap<>();

        // Use the map's entrySet method
        Method entrySetMethod = countsMap.getClass().getMethod("object2IntEntrySet");
        Object entrySet = entrySetMethod.invoke(countsMap);

        // Iterate entries
        for (Object entry : (Iterable<?>) entrySet) {
            Method getKeyMethod = entry.getClass().getMethod("getKey");
            Method getIntValueMethod = entry.getClass().getMethod("getIntValue");

            String key = (String) getKeyMethod.invoke(entry);
            int value = (int) getIntValueMethod.invoke(entry);
            result.put(key, value);
        }

        return result;
    }

    private int getCount(Object blockCounter, String blockType) throws Exception {
        Method getCountMethod = blockCounter.getClass().getMethod("getBlockPlacementCount", String.class);
        return (int) getCountMethod.invoke(blockCounter, blockType);
    }

    private void setCountValue(Object blockCounter, String blockType, int value) throws Exception {
        // Get the blockPlacementCounts field via reflection
        Field countsField = blockCounter.getClass().getDeclaredField("blockPlacementCounts");
        countsField.setAccessible(true);
        Object countsMap = countsField.get(blockCounter);

        // Use put method to set the value
        Method putMethod = countsMap.getClass().getMethod("put", Object.class, int.class);
        putMethod.invoke(countsMap, blockType, value);
    }

    private void showCount(Player player, Object blockCounter, String blockType) throws Exception {
        int count = getCount(blockCounter, blockType);
        sendMessage(player, "&6[HyFixes] BlockCounter Status");
        sendMessage(player, "&7Block Type: &f" + blockType);
        sendMessage(player, "&7Current Count: &e" + count);
        sendMessage(player, "&7");
        sendMessage(player, "&7Use &f/fixcounter reset &7to reset to 0");
        sendMessage(player, "&7Use &f/fixcounter set <value> &7to set specific value");
    }

    private void listAllCounts(Player player, Object blockCounter, World world) throws Exception {
        Map<String, Integer> counts = getBlockPlacementCounts(blockCounter);

        sendMessage(player, "&6[HyFixes] All BlockCounter Values");
        sendMessage(player, "&7World: &f" + world.getName());
        sendMessage(player, "&7");

        if (counts.isEmpty()) {
            sendMessage(player, "&7No block placement counts tracked");
        } else {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String blockType = entry.getKey();
                int count = entry.getValue();
                String color = count > 0 ? "&e" : "&7";
                sendMessage(player, "&7  " + blockType + ": " + color + count);
            }
        }
        sendMessage(player, "&7");
        sendMessage(player, "&7Total tracked types: &f" + counts.size());
    }

    private void resetCount(Player player, Object blockCounter, String blockType, World world) throws Exception {
        int oldCount = getCount(blockCounter, blockType);
        setCountValue(blockCounter, blockType, 0);

        sendMessage(player, "&a[HyFixes] BlockCounter Reset!");
        sendMessage(player, "&7Block Type: &f" + blockType);
        sendMessage(player, "&7Old Count: &c" + oldCount);
        sendMessage(player, "&7New Count: &a0");
        sendMessage(player, "&7");
        sendMessage(player, "&eNote: This change persists until server restart or chunk reload.");

        plugin.getLogger().at(java.util.logging.Level.INFO).log(
            "[FixCounter] Player reset %s count from %d to 0 in world %s",
            blockType, oldCount, world.getName()
        );
    }

    private void setCount(Player player, Object blockCounter, String blockType, int value, World world) throws Exception {
        int oldCount = getCount(blockCounter, blockType);
        setCountValue(blockCounter, blockType, value);

        sendMessage(player, "&a[HyFixes] BlockCounter Updated!");
        sendMessage(player, "&7Block Type: &f" + blockType);
        sendMessage(player, "&7Old Count: &c" + oldCount);
        sendMessage(player, "&7New Count: &a" + value);

        plugin.getLogger().at(java.util.logging.Level.INFO).log(
            "[FixCounter] Player set %s count from %d to %d in world %s",
            blockType, oldCount, value, world.getName()
        );
    }

    private void showUsage(Player player) {
        sendMessage(player, "&6[HyFixes] /fixcounter Usage:");
        sendMessage(player, "&7  /fixcounter &f- Show teleporter count");
        sendMessage(player, "&7  /fixcounter list &f- List all block counts");
        sendMessage(player, "&7  /fixcounter reset &f- Reset teleporter count to 0");
        sendMessage(player, "&7  /fixcounter set <value> &f- Set teleporter count");
        sendMessage(player, "&7  /fixcounter <block> reset &f- Reset specific block");
        sendMessage(player, "&7  /fixcounter <block> set <value> &f- Set specific block count");
    }

    private void sendMessage(Player player, String message) {
        String formatted = message.replace("&", "\u00A7");
        player.sendMessage(Message.raw(formatted));
    }
}
