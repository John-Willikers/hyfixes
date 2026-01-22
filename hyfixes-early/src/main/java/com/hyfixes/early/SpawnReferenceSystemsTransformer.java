package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - SpawnReferenceSystems Bytecode Transformer
 *
 * This transformer fixes the null spawnController crash when loading chunks
 * with spawn beacons that reference missing/invalid beacon types.
 *
 * The Bug:
 * In SpawnReferenceSystems$BeaconAddRemoveSystem.onEntityAdded(), when a spawn beacon
 * references a beacon type that doesn't exist (e.g., typo like "VoIcanic" instead of "Volcanic"),
 * getSpawnController() returns null and the subsequent hasSlots() call crashes:
 *
 * Error: java.lang.NullPointerException: Cannot invoke "BeaconSpawnController.hasSlots()"
 *        because "spawnController" is null
 * at SpawnReferenceSystems$BeaconAddRemoveSystem.onEntityAdded(SpawnReferenceSystems.java:269)
 *
 * The Fix:
 * Transform onEntityAdded() to add a null check after getSpawnController().
 * If null, log a warning and despawn the NPC gracefully instead of crashing.
 */
public class SpawnReferenceSystemsTransformer implements ClassTransformer {

    // Target the inner class
    private static final String TARGET_CLASS = "com.hypixel.hytale.server.npc.systems.SpawnReferenceSystems$BeaconAddRemoveSystem";

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
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("spawnReferenceSystems")) {
            info("SpawnReferenceSystemsTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming SpawnReferenceSystems$BeaconAddRemoveSystem...");
        verbose("Fixing null spawnController crash in onEntityAdded()");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new SpawnReferenceSystemsVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("SpawnReferenceSystems transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform SpawnReferenceSystems!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
