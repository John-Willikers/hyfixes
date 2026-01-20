package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for Universe class.
 * Intercepts the lambda method used in removePlayer() async block.
 */
public class UniverseVisitor extends ClassVisitor {

    private String className;
    private boolean transformed = false;

    public UniverseVisitor(ClassVisitor classVisitor) {
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

        // Look for lambda methods in removePlayer - they're named lambda$removePlayer$N
        if (name.startsWith("lambda$removePlayer$")) {
            System.out.println("[HyFixes-Early] Found lambda method: " + className + "." + name + descriptor);
            System.out.println("[HyFixes-Early] Wrapping with fallback cleanup try-catch...");
            transformed = true;
            return new RemovePlayerLambdaVisitor(mv, className, name);
        }

        return mv;
    }

    public boolean isTransformed() {
        return transformed;
    }
}
