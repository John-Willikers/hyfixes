package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that wraps InteractionManager.serverTick() in a try-catch
 * to handle RuntimeException when clients are too slow to send clientData.
 *
 * The original method throws RuntimeException with message "Client took too long
 * to send clientData" when the timeout is exceeded. This kicks the player.
 *
 * Fix: Wrap the ENTIRE method body in try-catch. Catch RuntimeException, check
 * if it's the timeout message, properly cancel the chain and return null.
 * Re-throw if it's a different exception.
 *
 * IMPORTANT: We use cancelChains(chain) instead of just removing from the map.
 * This properly notifies the client that the chain was cancelled, preventing
 * "Client finished chain earlier than server" desync errors.
 *
 * Method signature: private InteractionSyncData serverTick(Ref, InteractionChain, long)
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/40">Issue #40</a>
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/46">Issue #46</a>
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/51">Issue #51</a>
 */
public class ServerTickMethodVisitor extends MethodVisitor {

    private static final int RETURN_VALUE_LOCAL = 15;
    private static final int EXCEPTION_LOCAL = 16;

    private final String className;
    private final Label tryStart = new Label();
    private final Label tryEnd = new Label();
    private final Label catchHandler = new Label();
    private final Label normalExit = new Label();

    public ServerTickMethodVisitor(MethodVisitor mv, String className) {
        super(Opcodes.ASM9, mv);
        this.className = className;
    }

    @Override
    public void visitCode() {
        super.visitCode();

        // Register the try-catch block for RuntimeException
        // This covers the ENTIRE method body (tryStart to tryEnd)
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/RuntimeException");

        // Emit the try block start label
        mv.visitLabel(tryStart);
    }

    @Override
    public void visitInsn(int opcode) {
        // Intercept ALL ARETURN instructions and redirect them to normalExit
        if (opcode == Opcodes.ARETURN) {
            // Store the return value temporarily and jump to normal exit
            mv.visitVarInsn(Opcodes.ASTORE, RETURN_VALUE_LOCAL);
            mv.visitJumpInsn(Opcodes.GOTO, normalExit);
            return; // Don't call super - we handled this instruction
        }

        super.visitInsn(opcode);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // === END OF TRY BLOCK ===
        // This is placed after all original code but before the exit/handler code
        mv.visitLabel(tryEnd);

        // === NORMAL EXIT ===
        // All original returns jump here
        mv.visitLabel(normalExit);
        mv.visitVarInsn(Opcodes.ALOAD, RETURN_VALUE_LOCAL);
        mv.visitInsn(Opcodes.ARETURN);

        // === CATCH HANDLER ===
        // Handles RuntimeException from anywhere in the method
        mv.visitLabel(catchHandler);

        // Stack has the RuntimeException on it - store it
        mv.visitVarInsn(Opcodes.ASTORE, EXCEPTION_LOCAL);

        // Check if exception message contains "Client took too long"
        mv.visitVarInsn(Opcodes.ALOAD, EXCEPTION_LOCAL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/RuntimeException", "getMessage", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.DUP);

        // If message is null, re-throw
        Label messageNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, messageNotNull);
        mv.visitInsn(Opcodes.POP); // pop the null
        mv.visitVarInsn(Opcodes.ALOAD, EXCEPTION_LOCAL);
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitLabel(messageNotNull);
        // Stack has message string - check if it's the timeout message
        mv.visitLdcInsn("Client took too long");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false);

        // If doesn't contain the timeout message, re-throw
        Label isTimeoutException = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, isTimeoutException);
        mv.visitVarInsn(Opcodes.ALOAD, EXCEPTION_LOCAL);
        mv.visitInsn(Opcodes.ATHROW);

        // It's the timeout exception - properly cancel the chain instead of kicking player
        // Issue #51: Must use cancelChains() to properly notify client, not just remove from map!
        mv.visitLabel(isTimeoutException);

        // === PROPERLY CANCEL THE CHAIN ===
        // Method params: this=0 (InteractionManager), entityRef=1, chain=2, currentTick=3-4
        // Call: this.cancelChains(chain) - this notifies the client and cleans up properly

        Label cancelTryStart = new Label();
        Label cancelTryEnd = new Label();
        Label cancelCatch = new Label();
        Label afterCancel = new Label();

        // Register try-catch for cancellation (in case something fails)
        mv.visitTryCatchBlock(cancelTryStart, cancelTryEnd, cancelCatch, "java/lang/Throwable");

        mv.visitLabel(cancelTryStart);

        // this.cancelChains(chain) - properly cancels and notifies client
        mv.visitVarInsn(Opcodes.ALOAD, 0);  // this (InteractionManager)
        mv.visitVarInsn(Opcodes.ALOAD, 2);  // chain (InteractionChain)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "com/hypixel/hytale/server/core/entity/InteractionManager",
            "cancelChains",
            "(Lcom/hypixel/hytale/server/core/entity/InteractionChain;)V",
            false
        );

        mv.visitLabel(cancelTryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, afterCancel);

        // Catch block - swallow error if cancellation fails
        mv.visitLabel(cancelCatch);
        mv.visitInsn(Opcodes.POP); // pop the exception

        mv.visitLabel(afterCancel);

        // Return null (graceful failure instead of kick)
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        // Increase stack and locals to accommodate our additions
        super.visitMaxs(Math.max(maxStack, 4), Math.max(maxLocals, 17));
    }
}
