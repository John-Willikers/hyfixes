package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for SpawnReferenceSystems$BeaconAddRemoveSystem transformation.
 * Intercepts the onEntityAdded method to add null check for spawnController.
 */
public class SpawnReferenceSystemsVisitor extends ClassVisitor {

    private String className;

    private static final String ON_ENTITY_ADDED_METHOD = "onEntityAdded";

    public SpawnReferenceSystemsVisitor(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals(ON_ENTITY_ADDED_METHOD)) {
            verbose("Found method: " + name + descriptor);
            verbose("Applying null spawnController check...");
            return new BeaconAddRemoveMethodVisitor(mv, className);
        }

        return mv;
    }
}
