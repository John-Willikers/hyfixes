package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - SetMemoriesCapacityInteraction Bytecode Transformer
 *
 * This transformer fixes a crash in Hytale's adventure/memories system where
 * SetMemoriesCapacityInteraction.firstRun() crashes with "ComponentType is invalid!"
 * when the PlayerMemories component isn't loaded for certain instances.
 *
 * The fix adds a validation check at the start of firstRun() to check if
 * PlayerMemories.getComponentType().isValid() returns false. If invalid,
 * the interaction fails gracefully instead of crashing and kicking the player.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/52">Issue #52</a>
 */
public class SetMemoriesCapacityTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.builtin.adventure.memories.interactions.SetMemoriesCapacityInteraction";

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the target class
        if (!TARGET_CLASS.equals(className)) {
            return classBytes;
        }

        // Check if transformer is enabled
        // Note: Using a general "memoriesInteraction" config key
        // For now, always enable this fix since it prevents player kicks

        separator();
        info("Transforming SetMemoriesCapacityInteraction class...");
        verbose("  Target: " + TARGET_CLASS);
        verbose("  Original size: " + classBytes.length + " bytes");

        try {
            ClassReader reader = new ClassReader(classBytes);
            // COMPUTE_FRAMES is required because we inject bytecode with jump instructions
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new SetMemoriesCapacityVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformed = writer.toByteArray();
            verbose("  Transformed size: " + transformed.length + " bytes");
            info("SetMemoriesCapacityInteraction transformation COMPLETE!");
            separator();

            return transformed;

        } catch (Exception e) {
            error("ERROR: Failed to transform SetMemoriesCapacityInteraction!");
            error("  Exception: " + e.getMessage());
            e.printStackTrace();
            return classBytes;
        }
    }
}
