package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - ArchetypeChunk Bytecode Transformer
 *
 * This transformer fixes the IndexOutOfBoundsException in ArchetypeChunk.getComponent()
 * that occurs when the NPC/ECS system tries to access a component from an entity
 * that has already been removed from the archetype chunk.
 *
 * The Bug:
 * java.lang.IndexOutOfBoundsException: Index out of range: 0
 *     at com.hypixel.hytale.component.ArchetypeChunk.getComponent(ArchetypeChunk.java:159)
 *     at com.hypixel.hytale.server.npc.role.support.EntityList.add(EntityList.java:139)
 *     at com.hypixel.hytale.server.npc.systems.PositionCacheSystems$UpdateSystem.addEntities(PositionCacheSystems.java:384)
 *
 * Root Cause:
 * Entity references become stale but aren't cleaned up before being accessed.
 * When PositionCacheSystems tries to add entities to the position cache, it
 * accesses entities that have already been removed from their archetype chunks.
 *
 * The Fix:
 * Add bounds checking at the start of getComponent() to return null gracefully
 * when the index is out of bounds, instead of throwing IndexOutOfBoundsException.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/20">GitHub Issue #20</a>
 */
public class ArchetypeChunkTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.component.ArchetypeChunk";

    @Override
    public int priority() {
        // High priority - core ECS component
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the ArchetypeChunk class
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("archetypeChunk")) {
            info("ArchetypeChunkTransformer DISABLED by config");
            return classBytes;
        }

        separator();
        info("Transforming ArchetypeChunk class...");
        verbose("Fixing getComponent() IndexOutOfBoundsException (Issue #20)");
        verbose("Fixing copySerializableEntity() IndexOutOfBoundsException (Issue #29)");
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ArchetypeChunkVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("ArchetypeChunk transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform ArchetypeChunk!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
