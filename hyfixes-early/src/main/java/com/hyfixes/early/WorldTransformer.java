package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

/**
 * HyFixes Early Plugin - World Bytecode Transformer
 *
 * This transformer fixes the instance teleport race condition where players
 * get kicked with "Player is already in a world" error.
 *
 * The Bug:
 * In World.addPlayer(), when a player enters an instance portal, Hytale's async code
 * tries to add them to the new instance world before removing them from their current world.
 * World.addPlayer() checks if playerRef.getReference() is non-null and throws
 * IllegalStateException if so. This is a race condition in InstancesPlugin where
 * removeFromStore() hasn't completed before addPlayer() is called.
 *
 * Error: java.lang.IllegalStateException: Player is already in a world
 * at com.hypixel.hytale.server.core.universe.world.World.addPlayer(World.java:1008)
 *
 * The Fix (Option A - Retry Loop):
 * Instead of immediately throwing, we inject a retry loop that waits up to 100ms
 * (20 retries × 5ms each) for the reference to be cleared by the drain operation.
 * If the reference clears, we log success and continue. If it doesn't clear after
 * all retries, we throw the original exception (indicating a real problem, not a race).
 *
 * This properly handles the race condition while still catching genuine errors.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/7">GitHub Issue #7</a>
 */
public class WorldTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.universe.world.World";

    @Override
    public int priority() {
        // High priority - we want this transformation to happen early
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the World class
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("world")) {
            System.out.println("[HyFixes-Early] WorldTransformer DISABLED by config");
            return classBytes;
        }

        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming World class...");
        System.out.println("[HyFixes-Early] Fixing addPlayer() race condition with retry loop (Issue #7)");
        System.out.println("[HyFixes-Early] ================================================");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new WorldVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            System.out.println("[HyFixes-Early] World transformation COMPLETE!");
            System.out.println("[HyFixes-Early] Original size: " + classBytes.length + " bytes");
            System.out.println("[HyFixes-Early] Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] ERROR: Failed to transform World!");
            System.err.println("[HyFixes-Early] Returning original bytecode to prevent crash.");
            e.printStackTrace();
            return classBytes;
        }
    }
}
