package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

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
        // We ONLY want the lambda that has PlayerRef as a parameter - that's the whenComplete
        // handler that can perform fallback cleanup. Other lambdas don't have access to PlayerRef.
        if (name.startsWith("lambda$removePlayer$")) {
            verbose("Found lambda method: " + className + "." + name + descriptor);

            // Only transform if descriptor contains PlayerRef - that's the lambda we can clean up with
            if (descriptor.contains("PlayerRef")) {
                verbose("Lambda has PlayerRef parameter - applying fallback cleanup transform");
                transformed = true;
                return new RemovePlayerLambdaVisitor(mv, className, name, descriptor, access);
            } else {
                verbose("Lambda doesn't have PlayerRef - skipping");
            }
        }

        return mv;
    }

    public boolean isTransformed() {
        return transformed;
    }
}
