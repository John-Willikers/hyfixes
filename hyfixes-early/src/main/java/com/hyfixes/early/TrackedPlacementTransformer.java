package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - TrackedPlacement Bytecode Transformer
 *
 * This transformer fixes the BlockCounter decrement bug where teleporter
 * placement counts don't decrease when teleporters are deleted.
 *
 * The Bug:
 * In TrackedPlacement$OnAddRemove.onEntityRemove(), if the TrackedPlacement
 * component is null or its blockName is null, the BlockCounter.untrackBlock()
 * is never called, leaving the placement count stuck.
 *
 * The Fix:
 * Transform onEntityRemove() to:
 * 1. Handle null TrackedPlacement component gracefully (log warning, continue)
 * 2. Handle null blockName gracefully (log warning, continue)
 * 3. Log successful decrements for debugging
 *
 * This ensures teleporter limits are properly decremented even if there
 * are race conditions or component ordering issues.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/11">GitHub Issue #11</a>
 */
public class TrackedPlacementTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.modules.interaction.blocktrack.TrackedPlacement$OnAddRemove";

    @Override
    public int priority() {
        // Standard priority
        return 50;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the TrackedPlacement$OnAddRemove inner class
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("trackedPlacement")) {
            info("TrackedPlacementTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming TrackedPlacement$OnAddRemove class...");
        verbose("Fixing BlockCounter decrement null check bug");
        verbose("Issue: https://github.com/John-Willikers/hyfixes/issues/11");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new TrackedPlacementVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("TrackedPlacement$OnAddRemove transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform TrackedPlacement$OnAddRemove!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
