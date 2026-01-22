package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - BeaconSpawnController Bytecode Transformer
 *
 * This transformer fixes the null spawn crash in volcanic/cave biomes when
 * spawn beacons try to spawn NPCs with missing or invalid spawn configurations.
 *
 * The Bug:
 * In BeaconSpawnController.createRandomSpawnJob(), the 'spawn' parameter
 * (type RoleSpawnParameters) can be null when the spawn beacon references
 * a spawn type that doesn't exist or has been misconfigured. This causes:
 *
 * Error: java.lang.NullPointerException: Cannot invoke "RoleSpawnParameters.getId()"
 *        because "spawn" is null
 * at BeaconSpawnController.createRandomSpawnJob(BeaconSpawnController.java:110)
 *
 * The Fix:
 * Transform createRandomSpawnJob() to add a null check at the method entry.
 * If spawn is null, log a warning and return null instead of crashing.
 */
public class BeaconSpawnControllerTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.spawning.controllers.BeaconSpawnController";

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
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("beaconSpawnController")) {
            info("BeaconSpawnControllerTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming BeaconSpawnController...");
        verbose("Fixing null spawn crash in createRandomSpawnJob()");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new BeaconSpawnControllerVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("BeaconSpawnController transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform BeaconSpawnController!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
