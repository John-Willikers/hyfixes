package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * Transformer for EntityStore$UUIDSystem to fix NPE during chunk unload.
 *
 * The vanilla UUIDSystem.onEntityRemove() method can crash when uuidComponent
 * is null - which happens when entities are removed during chunk unload before
 * their UUID component is fully initialized.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/28">GitHub Issue #28</a>
 */
public class UUIDSystemTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.universe.world.storage.EntityStore$UUIDSystem";

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the target class
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("uuidSystem")) {
            info("UUIDSystemTransformer DISABLED by config");
            return classBytes;
        }

        try {
            info("Transforming: " + TARGET_CLASS);

            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            UUIDSystemVisitor visitor = new UUIDSystemVisitor(writer);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isTransformed()) {
                info("UUIDSystem transformation COMPLETE!");
                return writer.toByteArray();
            } else {
                error("WARNING: UUIDSystem transformation did not apply!");
                return classBytes;
            }
        } catch (Exception e) {
            error("Error transforming UUIDSystem: " + e.getMessage(), e);
            return classBytes;
        }
    }
}
