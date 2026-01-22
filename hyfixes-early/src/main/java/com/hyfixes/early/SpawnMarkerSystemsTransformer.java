package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - SpawnMarkerSystems Bytecode Transformer
 *
 * This transformer fixes the null npcReferences crash when spawn markers are removed.
 *
 * The Bug:
 * In SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove(), when a spawn marker
 * has a null npcReferences array, the code crashes trying to iterate over it:
 *
 * Error: java.lang.NullPointerException: Cannot read the array length because "<local15>" is null
 *        at SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove(SpawnReferenceSystems.java:166)
 *
 * This happens because SpawnMarkerEntity.getNpcReferences() can return null, and the
 * MarkerAddRemoveSystem doesn't check for null before iterating.
 *
 * The Fix:
 * Transform onEntityRemove() to add a null check after getNpcReferences() is called.
 * If null, skip the iteration instead of crashing.
 *
 * Impact:
 * - Eliminates need for runtime SpawnMarkerReferenceSanitizer (was fixing 7000+ entities/session)
 * - Much more efficient - only runs on entity removal, not every tick
 */
public class SpawnMarkerSystemsTransformer implements ClassTransformer {

    // Target the MarkerAddRemoveSystem inner class
    private static final String TARGET_CLASS = "com.hypixel.hytale.server.npc.systems.SpawnReferenceSystems$MarkerAddRemoveSystem";

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
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("spawnMarkerSystems")) {
            info("SpawnMarkerSystemsTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming SpawnReferenceSystems$MarkerAddRemoveSystem...");
        verbose("Fixing null npcReferences crash in onEntityRemove()");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new SpawnMarkerSystemsVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("MarkerAddRemoveSystem transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform MarkerAddRemoveSystem!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
