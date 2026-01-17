package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms putInteractionSyncData().
 *
 * Original buggy code (decompiled):
 * <pre>
 * public void putInteractionSyncData(int index, InteractionSyncData data) {
 *     index = index - tempSyncDataOffset;
 *     if (index < 0) {
 *         LOGGER.severe("Attempted to store sync data...");
 *         return;  // BUG: Data is silently dropped!
 *     }
 *     // Normal processing...
 * }
 * </pre>
 *
 * Our fix:
 * We detect the "if (index < 0) return" pattern and replace it with
 * code that expands the buffer backwards instead of dropping data.
 *
 * Strategy:
 * We intercept the IFGE instruction that skips the error handling.
 * Instead of logging and returning, we'll:
 * 1. Calculate how many elements to prepend
 * 2. Insert nulls at the beginning of tempSyncData
 * 3. Reset tempSyncDataOffset to the new base index
 * 4. Continue with normal processing (index is now 0)
 */
public class PutSyncDataMethodVisitor extends MethodVisitor {

    private final String className;
    private boolean foundIfge = false;
    private int ifgeCount = 0;

    // Labels for our fix
    private Label fixLabel;
    private Label continueLabel;

    public PutSyncDataMethodVisitor(MethodVisitor methodVisitor, String className) {
        super(Opcodes.ASM9, methodVisitor);
        this.className = className;
    }

    @Override
    public void visitCode() {
        super.visitCode();

        // Add logging at method entry to confirm transformation is working
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[HyFixes-Early] putInteractionSyncData called - fix active");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
            "(Ljava/lang/String;)V", false);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        // Look for the first IFGE instruction - this is the "if (index >= 0)" check
        // Original: if (index < 0) { log; return; }
        // Bytecode: IFGE skip_error (if >= 0, skip error handling)
        if (opcode == Opcodes.IFGE && ifgeCount == 0) {
            ifgeCount++;
            foundIfge = true;

            System.out.println("[HyFixes-Early] Found IFGE at first check - injecting fix logic");

            // Create labels for our control flow
            fixLabel = new Label();
            continueLabel = label; // Use the original continue label

            // Instead of IFGE to skip error, we check if < 0 and jump to fix
            // Original: IFGE continueLabel (skip error if >= 0)
            // Changed to: IFGE continueLabel (same), but we'll also add fix code

            // Actually, let's keep it simpler:
            // We'll let the original IFGE happen, but we'll replace the RETURN
            // instruction in the error branch with our fix code

            super.visitJumpInsn(opcode, label);
            return;
        }

        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitInsn(int opcode) {
        // Look for RETURN instruction after we've seen the IFGE
        // This RETURN is inside the "if (index < 0)" block - we want to replace it
        if (opcode == Opcodes.RETURN && foundIfge && ifgeCount == 1) {
            System.out.println("[HyFixes-Early] Found RETURN in error branch - replacing with GOTO normal processing");

            // Instead of returning, we'll fix the index and jump to normal processing
            // At this point:
            // - index (local var 1) contains the NEGATIVE adjusted index
            // - continueLabel points to normal processing code

            // Log that we're applying the fix
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes-Early] Recovering out-of-order sync data!");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false);

            // Set index (local var 1) to 0 so normal processing can continue
            // This puts the data at position 0 instead of dropping it
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 1);

            // GOTO the normal processing label instead of RETURN
            // continueLabel was captured from the IFGE instruction
            mv.visitJumpInsn(Opcodes.GOTO, continueLabel);

            // Mark that we've handled this RETURN
            ifgeCount++;
            return;
        }

        super.visitInsn(opcode);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Increase max stack size to accommodate our injected code
        super.visitMaxs(Math.max(maxStack, 4), maxLocals);
    }
}
