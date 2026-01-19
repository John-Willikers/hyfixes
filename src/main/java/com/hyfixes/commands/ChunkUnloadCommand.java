package com.hyfixes.commands;

import com.hyfixes.HyFixes;
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
 * Command: /chunkunload
 * Forces an immediate chunk cleanup attempt
 */
public class ChunkUnloadCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;

    public ChunkUnloadCommand(HyFixes plugin) {
        super("chunkunload", "hyfixes.command.chunkunload.desc");
        this.plugin = plugin;
        addAliases("forceunload", "cu");
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

        sendMessage(player, "&6[HyFixes] Forcing immediate chunk cleanup...");

        // Run cleanup
        manager.forceCleanup();

        sendMessage(player, "&a[HyFixes] Chunk cleanup triggered. Check server logs for details.");
    }

    private void sendMessage(Player player, String message) {
        ChatColorUtil.sendMessage(player, message);
    }
}
