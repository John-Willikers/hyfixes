package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

/**
 * HyFixes Early Plugin - PlayerSystems Bytecode Transformer
 *
 * This transformer removes the broadcast message when a player leaves a world.
 *
 * The Change:
 * In PlayerSystems$PlayerRemovedSystem.onEntityRemoved(), removes the call to:
 * PlayerUtil.broadcastMessageToPlayers(..., "server.general.playerLeftWorld", ...)
 *
 * This silences the "player left world" message that appears when players teleport
 * between worlds or leave the server.
 */
public class PlayerSystemsTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems$PlayerRemovedSystem";

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, @NonNullDecl String packageName, @NonNullDecl byte[] classBytes) {
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("playerSystems")) {
            System.out.println("[HyFixes-Early] PlayerSystemsTransformer DISABLED by config");
            return classBytes;
        }

        try {
            System.out.println("[HyFixes-Early] Transforming " + className);

            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            PlayerSystemsVisitor visitor = new PlayerSystemsVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (!visitor.isTransformed()) {
                System.err.println("[HyFixes-Early] No matching instructions found to transform in " + className + " - leaving class bytes unchanged");
                return classBytes;
            }
            System.out.println("[HyFixes-Early] Successfully transformed " + className);
            return writer.toByteArray();

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] FAILED to transform " + className);
            e.printStackTrace();
            return classBytes;
        }
    }
}
