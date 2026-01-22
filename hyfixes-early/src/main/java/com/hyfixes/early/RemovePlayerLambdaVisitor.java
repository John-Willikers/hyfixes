package com.hyfixes.early;

import static com.hyfixes.early.EarlyLogger.*;
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
    private final String descriptor;
    private final int playerRefSlot;

    private final Label tryStart = new Label();
    private final Label tryEnd = new Label();
    private final Label catchHandler = new Label();
    private final Label methodEnd = new Label();

    private boolean started = false;

    public RemovePlayerLambdaVisitor(MethodVisitor methodVisitor, String className, String methodName, String descriptor, int access) {
        super(Opcodes.ASM9, methodVisitor);
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        // Find which slot has PlayerRef - parse descriptor
        // For instance methods, slot 0 is 'this', so we need to offset by 1
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        int baseSlot = isStatic ? 0 : 1;  // Instance methods have 'this' in slot 0
        this.playerRefSlot = baseSlot + findPlayerRefParamIndex(descriptor);
        verbose("Method is " + (isStatic ? "static" : "instance") + ", PlayerRef is in slot " + playerRefSlot);
    }

    /**
     * Find the parameter index (0-based) of PlayerRef by parsing the descriptor.
     * This returns the index in the parameter list, not the JVM slot.
     */
    private int findPlayerRefParamIndex(String desc) {
        // Descriptor format: (Lcom/.../PlayerRef;Ljava/lang/Void;...)V
        int slot = 0;
        int i = 1; // Skip opening '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            if (desc.charAt(i) == 'L') {
                // Object type - find the semicolon
                int end = desc.indexOf(';', i);
                String type = desc.substring(i + 1, end);
                if (type.endsWith("PlayerRef")) {
                    return slot;
                }
                i = end + 1;
                slot++;
            } else if (desc.charAt(i) == '[') {
                // Array - skip to element type
                i++;
                if (desc.charAt(i) == 'L') {
                    i = desc.indexOf(';', i) + 1;
                } else {
                    i++;
                }
                slot++;
            } else {
                // Primitive type (Z, B, C, S, I, F, J, D)
                if (desc.charAt(i) == 'J' || desc.charAt(i) == 'D') {
                    slot += 2; // long and double take 2 slots
                } else {
                    slot++;
                }
                i++;
            }
        }
        // Default to slot 0 if not found
        return 0;
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

            // Load playerRef from dynamically determined slot
            mv.visitVarInsn(Opcodes.ALOAD, playerRefSlot);

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
