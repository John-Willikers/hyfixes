package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ClassVisitor that intercepts methods in SpawnMarkerEntity to fix null npcReferences:
 *
 * 1. Constructor - Initialize npcReferences to empty array (prevents null from start)
 * 2. setNpcReferences() - Converts null to empty array (CODEC deserialization safety)
 * 3. spawnNPC() - Creates npcReferences array when storedFlock is null (new spawns)
 */
public class SpawnMarkerEntityVisitor extends ClassVisitor {

    private static final String CONSTRUCTOR = "<init>";
    private static final String SPAWN_NPC_METHOD = "spawnNPC";
    private static final String SET_NPC_REFS_METHOD = "setNpcReferences";

    // Constructor takes no args
    private static final String CONSTRUCTOR_DESC = "()V";
    // Method descriptor: (Ref, SpawnMarker, Store) -> boolean
    private static final String SPAWN_NPC_DESC_CONTAINS = "Lcom/hypixel/hytale/component/Ref;";
    // setNpcReferences takes array of InvalidatablePersistentRef
    private static final String SET_NPC_REFS_DESC = "([Lcom/hypixel/hytale/server/core/entity/reference/InvalidatablePersistentRef;)V";

    private final String className;
    private boolean constructorTransformed = false;
    private boolean spawnNpcTransformed = false;
    private boolean setNpcRefsTransformed = false;

    public SpawnMarkerEntityVisitor(ClassVisitor cv, String className) {
        super(Opcodes.ASM9, cv);
        this.className = className;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Transform constructor - initialize npcReferences to empty array
        if (name.equals(CONSTRUCTOR) && descriptor.equals(CONSTRUCTOR_DESC)) {
            verbose("Found constructor: " + name + descriptor);
            verbose("Transforming constructor to initialize npcReferences");
            constructorTransformed = true;
            return new SpawnMarkerEntityConstructorVisitor(mv);
        }

        // Transform spawnNPC() - fix for new spawns where storedFlock is null
        if (name.equals(SPAWN_NPC_METHOD) && descriptor.contains(SPAWN_NPC_DESC_CONTAINS)) {
            verbose("Found method: " + name + descriptor);
            spawnNpcTransformed = true;
            return new SpawnNPCMethodVisitor(mv, className);
        }

        // Transform setNpcReferences() - fix for CODEC deserialization
        if (name.equals(SET_NPC_REFS_METHOD) && descriptor.equals(SET_NPC_REFS_DESC)) {
            verbose("Found method: " + name + descriptor);
            verbose("Transforming setNpcReferences() to reject null");
            setNpcRefsTransformed = true;
            return new SetNpcReferencesMethodVisitor(mv);
        }

        return mv;
    }

    public boolean isTransformed() {
        return constructorTransformed || spawnNpcTransformed || setNpcRefsTransformed;
    }

    public boolean isFullyTransformed() {
        return constructorTransformed && setNpcRefsTransformed;
    }
}
