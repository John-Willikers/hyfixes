package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for InteractionChain.
 *
 * This visitor intercepts problematic methods and applies fixes:
 * 1. putInteractionSyncData - buffer overflow when data arrives out of order
 * 2. updateSyncPosition - throws IllegalArgumentException on sync gaps
 */
public class InteractionChainVisitor extends ClassVisitor {

    private static final String PUT_SYNC_DATA_METHOD = "putInteractionSyncData";
    private static final String UPDATE_SYNC_POSITION_METHOD = "updateSyncPosition";

    private String className;

    public InteractionChainVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals(PUT_SYNC_DATA_METHOD)) {
            System.out.println("[HyFixes-Early] Found method: " + name + descriptor);
            System.out.println("[HyFixes-Early] Applying buffer overflow fix...");
            return new PutSyncDataMethodVisitor(mv, className);
        }

        if (name.equals(UPDATE_SYNC_POSITION_METHOD)) {
            System.out.println("[HyFixes-Early] Found method: " + name + descriptor);
            System.out.println("[HyFixes-Early] Applying sync position fix...");
            return new UpdateSyncPositionMethodVisitor(mv, className);
        }

        return mv;
    }
}
