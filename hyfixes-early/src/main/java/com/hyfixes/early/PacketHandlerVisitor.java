package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for PacketHandler.
 *
 * Intercepts getOperationTimeoutThreshold() to apply configurable timeout values.
 */
public class PacketHandlerVisitor extends ClassVisitor {

    private static final String TARGET_METHOD = "getOperationTimeoutThreshold";
    private static final String TARGET_DESCRIPTOR = "()J";  // returns long

    private String className;
    private final long baseTimeoutMs;
    private final double pingMultiplier;

    public PacketHandlerVisitor(ClassVisitor classVisitor, long baseTimeoutMs, double pingMultiplier) {
        super(Opcodes.ASM9, classVisitor);
        this.baseTimeoutMs = baseTimeoutMs;
        this.pingMultiplier = pingMultiplier;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals(TARGET_METHOD) && descriptor.equals(TARGET_DESCRIPTOR)) {
            System.out.println("[HyFixes-Early] Found method: " + name + descriptor);
            System.out.println("[HyFixes-Early] Applying configurable timeout fix...");
            return new OperationTimeoutMethodVisitor(mv, className, baseTimeoutMs, pingMultiplier);
        }

        return mv;
    }
}
