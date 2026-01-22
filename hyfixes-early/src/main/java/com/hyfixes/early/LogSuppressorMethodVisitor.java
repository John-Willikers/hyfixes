package com.hyfixes.early;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM MethodVisitor that downgrades verbose "Client finished chain" log spam
 * from SEVERE to FINE level.
 *
 * The InteractionManager logs massive amounts of data when there's a client/server
 * desync on interaction chains. These logs dump full InteractionContext objects
 * which can produce hundreds of lines per occurrence.
 *
 * This visitor intercepts Logger.severe() calls where the format string starts
 * with "Client finished" and replaces them with Logger.fine() calls instead.
 */
public class LogSuppressorMethodVisitor extends MethodVisitor {

    private static final String LOGGER_CLASS = "java/util/logging/Logger";
    private String pendingLdcString = null;
    private boolean suppressNextSevere = false;

    public LogSuppressorMethodVisitor(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Track string constants that might be log messages
        if (value instanceof String) {
            String str = (String) value;
            if (str.startsWith("Client finished")) {
                pendingLdcString = str;
                suppressNextSevere = true;
            } else {
                pendingLdcString = null;
                suppressNextSevere = false;
            }
        }
        super.visitLdcInsn(value);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Check if this is a Logger.severe() call after a "Client finished" string
        if (suppressNextSevere && owner.equals(LOGGER_CLASS) && name.equals("severe")) {
            // Replace severe() with fine() - same signature
            verbose("  Downgrading 'Client finished...' log from SEVERE to FINE");
            super.visitMethodInsn(opcode, owner, "fine", descriptor, isInterface);
            suppressNextSevere = false;
            pendingLdcString = null;
            return;
        }

        // Reset tracking on any method call
        suppressNextSevere = false;
        pendingLdcString = null;
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitInsn(int opcode) {
        // Reset tracking on certain instructions that indicate the LDC isn't for logging
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
            suppressNextSevere = false;
            pendingLdcString = null;
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        // Don't reset on ALOAD/ASTORE as they're often used between LDC and invoke
        super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
        // Reset on jumps
        suppressNextSevere = false;
        pendingLdcString = null;
        super.visitJumpInsn(opcode, label);
    }
}
