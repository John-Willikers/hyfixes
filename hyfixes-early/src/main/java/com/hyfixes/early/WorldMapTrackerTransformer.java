package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - WorldMapTracker Bytecode Transformer
 *
 * This transformer fixes the iterator corruption crash in WorldMapTracker.unloadImages()
 * that occurs on high-population servers (~35+ players) approximately every 30 minutes.
 *
 * The Bug:
 * In WorldMapTracker.unloadImages(), a LongOpenHashSet is iterated with iterator.remove()
 * being called during iteration. Under high load, when the set needs to rehash, the
 * iterator's internal 'wrapped' field can become null, causing:
 *
 * Error: java.lang.NullPointerException: Cannot invoke "LongArrayList.getLong(int)"
 *        because "this.wrapped" is null
 * at LongOpenHashSet$SetIterator.nextLong(LongOpenHashSet.java:551)
 * at WorldMapTracker.unloadImages(WorldMapTracker.java:466)
 *
 * The Fix:
 * Wrap the iterator loop in a try-catch to gracefully handle the NPE.
 * If the exception occurs, log a warning and continue - the world map will
 * recover on the next tick without crashing the WorldMap thread.
 *
 * GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/16
 */
public class WorldMapTrackerTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.universe.world.WorldMapTracker";

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
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("worldMapTracker")) {
            info("WorldMapTrackerTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming WorldMapTracker...");
        verbose("Fixing iterator corruption crash in unloadImages()");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new WorldMapTrackerVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("WorldMapTracker transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform WorldMapTracker!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
