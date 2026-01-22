package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for TickingThread.
 * Intercepts the stop() method to wrap Thread.stop() calls.
 */
public class TickingThreadVisitor extends ClassVisitor {

    private static final String TARGET_METHOD = "stop";

    private String className;
    private boolean transformed = false;

    public TickingThreadVisitor(ClassVisitor classVisitor) {
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
            verbose("Found method: " + className + "." + name + descriptor);
            verbose("Wrapping Thread.stop() with fallback to interrupt()...");
            transformed = true;
            return new ThreadStopMethodVisitor(mv, className);
        }

        return mv;
    }

    public boolean isTransformed() {
        return transformed;
    }
}
