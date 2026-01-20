package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that wraps Thread.stop() calls with try-catch.
 *
 * Original: thread.stop();
 *
 * Transformed:
 *   try {
 *       thread.stop();
 *   } catch (UnsupportedOperationException e) {
 *       System.err.println("[HyFixes] Thread.stop() not supported, using interrupt()");
 *       thread.interrupt();
 *   }
 */
public class ThreadStopMethodVisitor extends MethodVisitor {

    private final String className;
    private boolean injected = false;

    public ThreadStopMethodVisitor(MethodVisitor methodVisitor, String className) {
        super(Opcodes.ASM9, methodVisitor);
        this.className = className;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Detect call to Thread.stop()
        if (!injected &&
            opcode == Opcodes.INVOKEVIRTUAL &&
            owner.equals("java/lang/Thread") &&
            name.equals("stop") &&
            descriptor.equals("()V")) {

            System.out.println("[HyFixes-Early] Injecting try-catch around Thread.stop()");

            // At this point, the Thread object is on the stack
            // We need to: DUP it (for potential interrupt call), try stop(), catch and interrupt

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchHandler = new Label();
            Label afterCatch = new Label();

            // DUP the thread reference for fallback interrupt()
            mv.visitInsn(Opcodes.DUP);

            // Try block start
            mv.visitLabel(tryStart);

            // Call Thread.stop() on the original reference
            mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

            // Pop the DUP'd reference (not needed if stop() succeeded)
            mv.visitInsn(Opcodes.POP);

            // Jump past catch handler
            mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

            // Try block end
            mv.visitLabel(tryEnd);

            // Catch handler
            mv.visitLabel(catchHandler);

            // Stack has: [DUP'd thread ref, exception]
            // Pop the exception
            mv.visitInsn(Opcodes.POP);

            // Log warning
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] Thread.stop() not supported on Java 21+, using interrupt() instead");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Call thread.interrupt() on the DUP'd reference (still on stack)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "interrupt", "()V", false);

            // After catch
            mv.visitLabel(afterCatch);

            // Register the try-catch block
            mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/UnsupportedOperationException");

            injected = true;
            return; // Don't call super - we handled it
        }

        // All other method calls pass through
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
}
