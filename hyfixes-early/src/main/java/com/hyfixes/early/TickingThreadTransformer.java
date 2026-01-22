package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

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
            info("TickingThreadTransformer DISABLED by config");
            return classBytes;
        }

        try {
            info("Transforming: " + TARGET_CLASS);
            verbose("Fixing Thread.stop() UnsupportedOperationException (Issue #32)");

            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            TickingThreadVisitor visitor = new TickingThreadVisitor(writer);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isTransformed()) {
                info("TickingThread transformation COMPLETE!");
                return writer.toByteArray();
            } else {
                error("WARNING: TickingThread transformation did not apply!");
                return classBytes;
            }
        } catch (Exception e) {
            error("Error transforming TickingThread: " + e.getMessage(), e);
            return classBytes;
        }
    }
}
