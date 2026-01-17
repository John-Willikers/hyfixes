package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for InteractionChain.
 *
 * This visitor intercepts the putInteractionSyncData method and applies
 * our fix for the buffer overflow bug.
 */
public class InteractionChainVisitor extends ClassVisitor {

    private static final String TARGET_METHOD = "putInteractionSyncData";

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

        if (name.equals(TARGET_METHOD)) {
            System.out.println("[HyFixes-Early] Found method: " + name + descriptor);
            System.out.println("[HyFixes-Early] Applying buffer overflow fix...");

            // Wrap with our custom method visitor that will modify the bytecode
            return new PutSyncDataMethodVisitor(mv, className);
        }

        return mv;
    }
}
