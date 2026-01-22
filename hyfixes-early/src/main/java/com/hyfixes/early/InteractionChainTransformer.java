package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - InteractionChain Bytecode Transformer
 *
 * This transformer fixes the CRITICAL InteractionChain sync buffer overflow bug
 * that causes 408-2,444 errors per 35-minute session.
 *
 * The Bug:
 * In InteractionChain.putInteractionSyncData(), when packets arrive out of order,
 * the adjusted index becomes negative and data is silently dropped, causing:
 * - Combat hits not registering
 * - Food consumption sound effects missing
 * - Shield blocking failing silently
 * - Tool interactions desyncing
 *
 * The Fix:
 * Transform putInteractionSyncData() to handle negative indices by expanding the
 * buffer backwards instead of dropping data.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/blob/main/HYTALE_CORE_BUGS.md">HYTALE_CORE_BUGS.md</a>
 */
public class InteractionChainTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.entity.InteractionChain";

    @Override
    public int priority() {
        // High priority - we want this transformation to happen early
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the InteractionChain class
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("interactionChain")) {
            info("InteractionChainTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming InteractionChain class...");
        verbose("Fixing putInteractionSyncData() buffer overflow bug");
        verbose("Fixing removeInteractionEntry() out-of-order bug (Issue #40)");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new InteractionChainVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("InteractionChain transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform InteractionChain!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
