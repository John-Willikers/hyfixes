package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that wraps the removePlayer lambda with fallback cleanup.
 *
 * On IllegalStateException, performs fallback cleanup:
 * 1. playerRef.getChunkTracker().clear() - releases chunk memory
 */
public class RemovePlayerLambdaVisitor extends MethodVisitor {

    private final String className;
    private final String methodName;

    private final Label tryStart = new Label();
    private final Label tryEnd = new Label();
    private final Label catchHandler = new Label();
    private final Label methodEnd = new Label();

    private boolean started = false;

    public RemovePlayerLambdaVisitor(MethodVisitor methodVisitor, String className, String methodName) {
        super(Opcodes.ASM9, methodVisitor);
        this.className = className;
        this.methodName = methodName;
    }

    @Override
    public void visitCode() {
        super.visitCode();

        // Start try block at beginning of method
        mv.visitLabel(tryStart);
        started = true;
    }

    @Override
    public void visitInsn(int opcode) {
        // Intercept RETURN to end try block and add catch handler
        if (started && opcode == Opcodes.RETURN) {
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, methodEnd);

            // === CATCH HANDLER FOR IllegalStateException ===
            mv.visitLabel(catchHandler);

            // Exception is on stack - store it
            mv.visitVarInsn(Opcodes.ASTORE, 10);

            // Log warning
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] Player ref invalid during removal - performing fallback cleanup");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // === TRY FALLBACK CLEANUP ===
            Label cleanupTryStart = new Label();
            Label cleanupTryEnd = new Label();
            Label cleanupCatch = new Label();
            Label cleanupDone = new Label();

            mv.visitLabel(cleanupTryStart);

            // Load playerRef from slot 1 (captured lambda parameter)
            // Lambda signature is typically (PlayerRef)V or similar
            mv.visitVarInsn(Opcodes.ALOAD, 1);

            // Call playerRef.getChunkTracker()
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/hypixel/hytale/server/core/universe/PlayerRef",
                "getChunkTracker",
                "()Lcom/hypixel/hytale/server/core/modules/entity/player/ChunkTracker;",
                false);

            // Call chunkTracker.clear()
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/hypixel/hytale/server/core/modules/entity/player/ChunkTracker",
                "clear",
                "()V",
                false);

            // Log success
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] ChunkTracker cleared - memory leak prevented");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            mv.visitLabel(cleanupTryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, cleanupDone);

            // Catch any cleanup errors
            mv.visitLabel(cleanupCatch);
            mv.visitInsn(Opcodes.POP);  // Discard cleanup exception
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] Fallback cleanup failed - memory may leak");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            mv.visitLabel(cleanupDone);

            // Register cleanup try-catch
            mv.visitTryCatchBlock(cleanupTryStart, cleanupTryEnd, cleanupCatch, "java/lang/Exception");

            // === METHOD END ===
            mv.visitLabel(methodEnd);
            mv.visitInsn(Opcodes.RETURN);

            // Register main try-catch block
            mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/IllegalStateException");

            started = false;
            return;  // Don't call super - we handled RETURN
        }

        super.visitInsn(opcode);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Ensure enough stack and locals for our injected code
        super.visitMaxs(Math.max(maxStack, 4), Math.max(maxLocals, 12));
    }
}
