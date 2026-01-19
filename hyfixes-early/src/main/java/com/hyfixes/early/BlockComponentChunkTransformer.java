package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

/**
 * HyFixes Early Plugin - BlockComponentChunk Bytecode Transformer
 *
 * This transformer fixes the "Duplicate block components" crash that occurs
 * when interacting with teleporters or other block entities.
 *
 * The Bug:
 * In BlockComponentChunk.addEntityReference(), when a block component already exists
 * at a position, it throws an IllegalArgumentException instead of handling it gracefully.
 *
 * Error: java.lang.IllegalArgumentException: Duplicate block components at: 153349
 * at BlockComponentChunk.addEntityReference(BlockComponentChunk.java:329)
 * at BlockModule$BlockStateInfoRefSystem.onEntityAdded(BlockModule.java:334)
 * at TeleporterSettingsPageSupplier.tryCreate(TeleporterSettingsPageSupplier.java:81)
 *
 * The Fix:
 * Transform addEntityReference() to log a warning and return early instead of
 * throwing an exception when a duplicate is detected. This makes the method
 * idempotent - calling it multiple times with the same position is safe.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/8">GitHub Issue #8</a>
 */
public class BlockComponentChunkTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk";

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("blockComponentChunk")) {
            System.out.println("[HyFixes-Early] BlockComponentChunkTransformer DISABLED by config");
            return classBytes;
        }

        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming BlockComponentChunk...");
        System.out.println("[HyFixes-Early] Fixing duplicate block component crash (Issue #8)");
        System.out.println("[HyFixes-Early] ================================================");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new BlockComponentChunkVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            System.out.println("[HyFixes-Early] BlockComponentChunk transformation COMPLETE!");
            System.out.println("[HyFixes-Early] Original size: " + classBytes.length + " bytes");
            System.out.println("[HyFixes-Early] Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] ERROR: Failed to transform BlockComponentChunk!");
            System.err.println("[HyFixes-Early] Returning original bytecode to prevent crash.");
            e.printStackTrace();
            return classBytes;
        }
    }
}
