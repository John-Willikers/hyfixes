package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for InteractionManager.
 *
 * This visitor intercepts methods and applies fixes:
 * - serverTick - throws RuntimeException when client is too slow (Issue #40)
 * - All methods - downgrades "Client finished chain" log spam from SEVERE to FINE
 */
public class InteractionManagerVisitor extends ClassVisitor {

    private static final String SERVER_TICK_METHOD = "serverTick";

    private String className;

    public InteractionManagerVisitor(ClassVisitor classVisitor) {
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

        // Apply log suppression to all methods (downgrades "Client finished chain" spam)
        mv = new LogSuppressorMethodVisitor(mv);

        if (name.equals(SERVER_TICK_METHOD)) {
            verbose("Found method: " + name + descriptor);
            verbose("Applying client timeout fix (Issue #40)...");
            return new ServerTickMethodVisitor(mv, className);
        }

        return mv;
    }
}
