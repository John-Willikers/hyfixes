package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

/**
 * Transformer for TickingThread to fix Thread.stop() UnsupportedOperationException on Java 21+.
 *
 * The vanilla code calls Thread.stop() to force-kill stuck threads during world shutdown.
 * Java 21+ removed Thread.stop() - it now throws UnsupportedOperationException.
 * We wrap the call in try-catch and fall back to Thread.interrupt().
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/32">GitHub Issue #32</a>
 */
public class TickingThreadTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.util.thread.TickingThread";

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        if (!EarlyConfigManager.getInstance().isTransformerEnabled("tickingThread")) {
            System.out.println("[HyFixes-Early] TickingThreadTransformer is disabled, skipping");
            return classBytes;
        }

        try {
            System.out.println("[HyFixes-Early] Transforming: " + TARGET_CLASS);
            System.out.println("[HyFixes-Early] Fixing Thread.stop() UnsupportedOperationException (Issue #32)");

            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            TickingThreadVisitor visitor = new TickingThreadVisitor(writer);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isTransformed()) {
                System.out.println("[HyFixes-Early] Successfully transformed TickingThread.stop()");
                return writer.toByteArray();
            } else {
                System.err.println("[HyFixes-Early] WARNING: TickingThread transformation did not apply!");
                return classBytes;
            }
        } catch (Exception e) {
            System.err.println("[HyFixes-Early] Error transforming TickingThread: " + e.getMessage());
            e.printStackTrace();
            return classBytes;
        }
    }
}
