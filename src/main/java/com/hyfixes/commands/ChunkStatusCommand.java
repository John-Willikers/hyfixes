package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hyfixes.systems.ChunkCleanupSystem;
import com.hyfixes.systems.ChunkUnloadManager;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hyfixes.util.ChatColorUtil;

/**
 * Command: /chunkstatus
 * Shows current chunk counts and ChunkUnloadManager status
 */
public class ChunkStatusCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;

    public ChunkStatusCommand(HyFixes plugin) {
        super("chunkstatus", "hyfixes.command.chunkstatus.desc");
        this.plugin = plugin;
        addAliases("chunks", "cs");
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

        ChunkUnloadManager manager = plugin.getChunkUnloadManager();
        if (manager == null) {
            sendMessage(player, "&c[HyFixes] ChunkUnloadManager is not initialized");
            return;
        }

        // Get status
        String status = manager.getStatus();

        // Send to player
        sendMessage(player, "&6=== HyFixes Chunk Status ===");
        for (String line : status.split("\n")) {
            sendMessage(player, "&7" + line.trim());
        }

        // Also list discovered methods
        java.util.List<String> methods = manager.getDiscoveredMethods();
        if (!methods.isEmpty()) {
            sendMessage(player, "&6Discovered Chunk APIs:");
            for (String method : methods) {
                sendMessage(player, "&7  - " + method);
            }
        } else {
            sendMessage(player, "&cNo chunk APIs discovered yet");
        }

        // Show ChunkCleanupSystem status
        ChunkCleanupSystem cleanupSystem = plugin.getChunkCleanupSystem();
        if (cleanupSystem != null) {
            sendMessage(player, "&6=== Main Thread Cleanup System ===");
            String cleanupStatus = cleanupSystem.getStatus();
            for (String line : cleanupStatus.split("\n")) {
                sendMessage(player, "&7" + line.trim());
            }
        }
    }

    private void sendMessage(Player player, String message) {
        ChatColorUtil.sendMessage(player, message);
    }
}
