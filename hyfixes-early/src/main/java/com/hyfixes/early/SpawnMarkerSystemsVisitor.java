package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for SpawnReferenceSystems$MarkerAddRemoveSystem transformation.
 * Intercepts the onEntityRemove method to add null check for npcReferences array.
 */
public class SpawnMarkerSystemsVisitor extends ClassVisitor {

    private String className;

    private static final String ON_ENTITY_REMOVE_METHOD = "onEntityRemove";

    public SpawnMarkerSystemsVisitor(ClassVisitor cv) {
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

        if (name.equals(ON_ENTITY_REMOVE_METHOD)) {
            System.out.println("[HyFixes-Early] Found method: " + name + descriptor);
            System.out.println("[HyFixes-Early] Applying null npcReferences check...");
            return new MarkerRemoveMethodVisitor(mv, className);
        }

        return mv;
    }
}
