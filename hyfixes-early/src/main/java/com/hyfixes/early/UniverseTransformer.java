package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * Transformer for Universe class to fix memory leak in removePlayer().
 *
 * When a player times out, the async removal can fail with IllegalStateException
 * if the entity reference is invalidated before getComponent() is called.
 * This leaves ChunkTracker data in memory (20GB+ leak).
 *
 * We wrap the async lambda with try-catch and perform fallback cleanup.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/34">GitHub Issue #34</a>
 */
public class UniverseTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.universe.Universe";

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        if (!EarlyConfigManager.getInstance().isTransformerEnabled("universeRemovePlayer")) {
            info("UniverseTransformer DISABLED by config");
            return classBytes;
        }

        try {
            info("Transforming: " + TARGET_CLASS);
            verbose("Fixing removePlayer() memory leak (Issue #34)");

            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            UniverseVisitor visitor = new UniverseVisitor(writer);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isTransformed()) {
                info("Universe transformation COMPLETE!");
                return writer.toByteArray();
            } else {
                error("WARNING: Universe transformation did not apply!");
                return classBytes;
            }
        } catch (Exception e) {
            error("Error transforming Universe: " + e.getMessage(), e);
            return classBytes;
        }
    }
}
