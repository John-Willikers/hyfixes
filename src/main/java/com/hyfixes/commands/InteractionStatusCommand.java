package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hyfixes.listeners.GatherObjectiveTaskSanitizer;
import com.hyfixes.listeners.PickupItemChunkHandler;
import com.hyfixes.systems.ChunkCleanupSystem;
import com.hyfixes.systems.ChunkUnloadManager;
import com.hyfixes.systems.InteractionChainMonitor;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;

/**
 * Command: /interactionstatus (alias: /hyfixstatus, /hfs)
 *
 * Shows comprehensive HyFixes statistics including:
 * - Crashes prevented by each sanitizer
 * - Memory management statistics
 * - Information about unfixable Hytale core bugs
 *
 * This helps server admins:
 * 1. Understand how HyFixes is protecting their server
 * 2. Gather evidence for bug reports to Hytale
 * 3. Monitor plugin health
 */
public class InteractionStatusCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;

    public InteractionStatusCommand(HyFixes plugin) {
        super("interactionstatus", "hyfixes.command.interactionstatus.desc");
        this.plugin = plugin;
        addAliases("hyfixstatus", "hfs", "hyfixes");
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

        // Header
        sendMessage(player, "&6========================================");
        sendMessage(player, "&6         HyFixes v1.3.0 Status");
        sendMessage(player, "&6========================================");
        sendMessage(player, "");

        // InteractionChainMonitor statistics
        InteractionChainMonitor monitor = plugin.getInteractionChainMonitor();
        if (monitor != null) {
            String status = monitor.getFullStatus();
            for (String line : status.split("\n")) {
                if (line.startsWith("===")) {
                    sendMessage(player, "&6" + line);
                } else if (line.startsWith("---")) {
                    sendMessage(player, "&e" + line);
                } else if (line.contains(": 0")) {
                    sendMessage(player, "&7" + line);
                } else if (line.contains(":") && !line.trim().isEmpty()) {
                    sendMessage(player, "&a" + line);
                } else {
                    sendMessage(player, "&7" + line);
                }
            }
        } else {
            sendMessage(player, "&cInteractionChainMonitor not initialized");
        }

        sendMessage(player, "");

        // GatherObjectiveTaskSanitizer status
        GatherObjectiveTaskSanitizer objectiveSanitizer = plugin.getGatherObjectiveTaskSanitizer();
        if (objectiveSanitizer != null) {
            sendMessage(player, "&6--- Objective Task Sanitizer ---");
            String status = objectiveSanitizer.getStatus();
            for (String line : status.split("\n")) {
                sendMessage(player, "&7" + line);
            }
        }

        sendMessage(player, "");

        // PickupItemChunkHandler status
        PickupItemChunkHandler chunkHandler = plugin.getPickupItemChunkHandler();
        if (chunkHandler != null) {
            sendMessage(player, "&6--- Pickup Item Chunk Handler ---");
            String status = chunkHandler.getStatus();
            for (String line : status.split("\n")) {
                sendMessage(player, "&7" + line);
            }
        }

        sendMessage(player, "");

        // ChunkCleanupSystem status
        ChunkCleanupSystem cleanupSystem = plugin.getChunkCleanupSystem();
        if (cleanupSystem != null) {
            sendMessage(player, "&6--- Chunk Cleanup System ---");
            String status = cleanupSystem.getStatus();
            for (String line : status.split("\n")) {
                sendMessage(player, "&7" + line);
            }
        }

        sendMessage(player, "");

        // ChunkUnloadManager status
        ChunkUnloadManager unloadManager = plugin.getChunkUnloadManager();
        if (unloadManager != null) {
            sendMessage(player, "&6--- Chunk Unload Manager ---");
            String status = unloadManager.getStatus();
            for (String line : status.split("\n")) {
                sendMessage(player, "&7" + line);
            }
        }

        sendMessage(player, "");
        sendMessage(player, "&6========================================");
        sendMessage(player, "&7Tip: Use /chunkstatus for chunk details");
        sendMessage(player, "&6========================================");
    }

    private void sendMessage(Player player, String message) {
        // Convert color codes
        String formatted = message.replace("&", "\u00A7");
        player.sendMessage(Message.raw(formatted));
    }
}
