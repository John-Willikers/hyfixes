package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

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

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.entity.EntityStore$UUIDSystem";

    @Override
    public String targetClass() {
        return TARGET_CLASS;
    }

    @Override
    public byte[] transform(byte[] classBytes) {
        // Check if transformer is enabled
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("uuidSystem")) {
            System.out.println("[HyFixes-Early] UUIDSystemTransformer is disabled, skipping");
            return classBytes;
        }

        try {
            System.out.println("[HyFixes-Early] Transforming: " + TARGET_CLASS);

            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            UUIDSystemVisitor visitor = new UUIDSystemVisitor(writer);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isTransformed()) {
                System.out.println("[HyFixes-Early] Successfully transformed UUIDSystem.onEntityRemove()");
                return writer.toByteArray();
            } else {
                System.err.println("[HyFixes-Early] WARNING: UUIDSystem transformation did not apply!");
                return classBytes;
            }
        } catch (Exception e) {
            System.err.println("[HyFixes-Early] Error transforming UUIDSystem: " + e.getMessage());
            e.printStackTrace();
            return classBytes;
        }
    }
}
