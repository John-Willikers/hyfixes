package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassTransformer that fixes the root cause of null npcReferences in SpawnMarkerEntity.
 *
 * The Bug:
 * In SpawnMarkerEntity.spawnNPC(), the npcReferences array is only created when
 * storedFlock != null. When storedFlock is null (non-flock spawns), the array
 * is never created, leaving npcReferences as null.
 *
 * Later, when the spawned NPC dies/despawns, MarkerAddRemoveSystem.onEntityRemove()
 * tries to iterate over the null npcReferences array, causing:
 *   NullPointerException: Cannot read the array length because "<local15>" is null
 *
 * The Fix:
 * Transform spawnNPC() to always create and populate the npcReferences array
 * when an NPC is spawned, regardless of whether storedFlock is null.
 *
 * Bytecode context (line 907-984):
 *   ALOAD 0
 *   GETFIELD storedFlock
 *   IFNULL label984           // BUG: Skips array creation!
 *   ... array creation at 923 ...
 *   ... array population 929-976 ...
 *   label984:
 *   ... logging ...
 *
 * Our fix injects code at label984 that creates and populates the array
 * if it's still null after the if block.
 */
public class SpawnMarkerEntityTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity";

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
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("spawnMarkerEntity")) {
            info("SpawnMarkerEntityTransformer DISABLED by config");
            return classBytes;
        }

        info("Transforming SpawnMarkerEntity class...");
        verbose("Fix 1: Constructor - initialize npcReferences to empty array");
        verbose("Fix 2: setNpcReferences() - convert null to empty array");
        verbose("Fix 3: spawnNPC() - create array when storedFlock is null");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            SpawnMarkerEntityVisitor visitor = new SpawnMarkerEntityVisitor(writer, className);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isFullyTransformed()) {
                info("SpawnMarkerEntity transformation COMPLETE! (both fixes applied)");
                return writer.toByteArray();
            } else if (visitor.isTransformed()) {
                info("SpawnMarkerEntity transformation PARTIAL - some fixes applied");
                return writer.toByteArray();
            } else {
                info("WARNING: SpawnMarkerEntity transformation may not have applied!");
                return writer.toByteArray();
            }
        } catch (Exception e) {
            error("ERROR transforming SpawnMarkerEntity: " + e.getMessage(), e);
            return classBytes;
        }
    }
}
