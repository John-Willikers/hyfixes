package com.hyfixes.early;

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
 * IllegalStateException if so.
 *
 * Error: java.lang.IllegalStateException: Player is already in a world
 * at com.hypixel.hytale.server.core.universe.world.World.addPlayer(World.java:1008)
 *
 * The Fix:
 * Transform addPlayer() to handle the case where a player is still in another world
 * by logging a warning and proceeding gracefully instead of throwing an exception.
 * Hytale's drain logic will eventually clean up the old world reference.
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

        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming World class...");
        System.out.println("[HyFixes-Early] Fixing addPlayer() race condition bug (Issue #7)");
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
