package com.hyfixes.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.message.MessageFormat;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Command: /who
 *
 * Shows who is currently online on the server.
 */
public class WhoCommand extends AbstractAsyncCommand {
    public WhoCommand() {
        super("who", "server.commands.who.desc");
        this.setPermissionGroup(GameMode.Adventure);
    }

    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
        Map<String, World> worlds = Universe.get().getWorlds();
        ObjectArrayList<CompletableFuture<Void>> futures = new ObjectArrayList<>();

        for(Map.Entry<String, World> entry : worlds.entrySet()) {
            World world = (World)entry.getValue();
            CompletableFuture<Void> future = this.runAsync(context, () -> {
                Store<EntityStore> store = world.getEntityStore().getStore();
                Collection<PlayerRef> playerRefs = world.getPlayerRefs();
                List<Message> playerMessages = new ObjectArrayList<>();

                for(PlayerRef playerRef : playerRefs) {
                    Ref<EntityStore> ref = playerRef.getReference();
                    if (ref != null && ref.isValid()) {
                        Player playerComponent = store.getComponent(ref, Player.getComponentType());
                        if (playerComponent != null) {
                            DisplayNameComponent displayNameComponent = store.getComponent(ref, DisplayNameComponent.getComponentType());

                            // Safely get display name with null checks (fixed from assert)
                            Message displayName = (displayNameComponent != null)
                                    ? displayNameComponent.getDisplayName()
                                    : null;

                            if (displayName != null) {
                                playerMessages.add(
                                        Message.translation("server.commands.who.playerWithDisplayName")
                                                .param("displayName", displayName)
                                                .param("username", playerRef.getUsername())
                                );
                            } else {
                                playerMessages.add(Message.raw(playerRef.getUsername()));
                            }
                        }
                    }
                }

                context.sendMessage(MessageFormat.list(Message.raw(world.getName()), playerMessages));
            }, world);
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
