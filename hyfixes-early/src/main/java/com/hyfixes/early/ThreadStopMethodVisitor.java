package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that wraps Thread.stop() calls with try-catch.
 *
 * Original: thread.stop();
 *
 * Transformed:
 *   Thread tempThread = thread;  // Store reference
 *   try {
 *       tempThread.stop();
 *   } catch (UnsupportedOperationException e) {
 *       System.err.println("[HyFixes] Thread.stop() not supported, using interrupt()");
 *       tempThread.interrupt();
 *   }
 */
public class ThreadStopMethodVisitor extends MethodVisitor {

    private final String className;
    private boolean injected = false;
    private int nextLocalVar = -1;

    public ThreadStopMethodVisitor(MethodVisitor methodVisitor, String className) {
        super(Opcodes.ASM9, methodVisitor);
        this.className = className;
    }

    @Override
    public void visitCode() {
        super.visitCode();
        // We'll determine the next available local var slot later
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Detect call to Thread.stop()
        if (!injected &&
            opcode == Opcodes.INVOKEVIRTUAL &&
            owner.equals("java/lang/Thread") &&
            name.equals("stop") &&
            descriptor.equals("()V")) {

            verbose("Injecting try-catch around Thread.stop()");

            // At this point, the Thread object is on the stack
            // Store it in a local variable so we can access it in the catch block

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchHandler = new Label();
            Label afterCatch = new Label();

            // Register the try-catch block FIRST (before emitting code)
            mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/UnsupportedOperationException");

            // DUP the thread reference - one for storing, one for stop()
            mv.visitInsn(Opcodes.DUP);

            // Store thread in local variable (use slot 10 - should be safe for this method)
            // This is a bit hacky but works for most cases
            int threadLocalVar = 10;
            mv.visitVarInsn(Opcodes.ASTORE, threadLocalVar);

            // === TRY BLOCK ===
            mv.visitLabel(tryStart);

            // Call Thread.stop() (thread ref still on stack from before DUP)
            mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

            mv.visitLabel(tryEnd);

            // Jump past catch handler
            mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

            // === CATCH HANDLER ===
            mv.visitLabel(catchHandler);

            // Exception is on stack - pop it
            mv.visitInsn(Opcodes.POP);

            // Log warning
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] Thread.stop() not supported on Java 21+, using interrupt() instead");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Load thread from local variable and call interrupt()
            mv.visitVarInsn(Opcodes.ALOAD, threadLocalVar);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "interrupt", "()V", false);

            // === AFTER CATCH ===
            mv.visitLabel(afterCatch);

            injected = true;
            return; // Don't call super - we handled it
        }

        // All other method calls pass through
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Ensure we have enough local vars (we used slot 10)
        super.visitMaxs(Math.max(maxStack, 3), Math.max(maxLocals, 11));
    }
}
