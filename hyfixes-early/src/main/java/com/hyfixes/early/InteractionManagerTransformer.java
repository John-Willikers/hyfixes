package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - InteractionManager Bytecode Transformer
 *
 * This transformer applies multiple fixes to InteractionManager:
 *
 * Fix 1 - Client Timeout (Issue #40):
 * In InteractionManager.serverTick(), when a client doesn't send clientData within
 * the timeout window, a RuntimeException is thrown, kicking the player.
 * We wrap the method in a try-catch to handle it gracefully.
 *
 * Fix 2 - Log Spam Suppression:
 * The InteractionManager logs massive amounts of data at SEVERE level when there's
 * a client/server desync ("Client finished chain earlier than server!"). These logs
 * dump full InteractionContext objects which can produce hundreds of lines per occurrence.
 * We downgrade these logs from SEVERE to FINE (debug) level.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/40">Issue #40</a>
 */
public class InteractionManagerTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.entity.InteractionManager";

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
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("interactionManager")) {
            info("InteractionManagerTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming InteractionManager class...");
        verbose("  - Fixing serverTick() client timeout bug (Issue #40)");
        verbose("  - Suppressing 'Client finished chain' log spam");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new InteractionManagerVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("InteractionManager transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform InteractionManager!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
