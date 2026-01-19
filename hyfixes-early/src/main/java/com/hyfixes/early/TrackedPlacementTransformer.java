package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

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
            System.out.println("[HyFixes-Early] TrackedPlacementTransformer DISABLED by config");
            return classBytes;
        }

        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming TrackedPlacement$OnAddRemove class...");
        System.out.println("[HyFixes-Early] Fixing BlockCounter decrement null check bug");
        System.out.println("[HyFixes-Early] Issue: https://github.com/John-Willikers/hyfixes/issues/11");
        System.out.println("[HyFixes-Early] ================================================");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new TrackedPlacementVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            System.out.println("[HyFixes-Early] TrackedPlacement$OnAddRemove transformation COMPLETE!");
            System.out.println("[HyFixes-Early] Original size: " + classBytes.length + " bytes");
            System.out.println("[HyFixes-Early] Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] ERROR: Failed to transform TrackedPlacement$OnAddRemove!");
            System.err.println("[HyFixes-Early] Returning original bytecode to prevent crash.");
            e.printStackTrace();
            return classBytes;
        }
    }
}
