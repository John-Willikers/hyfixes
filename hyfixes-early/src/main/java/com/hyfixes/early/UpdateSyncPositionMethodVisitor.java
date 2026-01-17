package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that REPLACES updateSyncPosition(int) entirely.
 *
 * Original method logic:
 *   if (tempSyncDataOffset == index) {
 *       tempSyncDataOffset = index + 1;
 *   } else if (index > tempSyncDataOffset) {
 *       throw new IllegalArgumentException("Temp sync data sent out of order...");  // BUG!
 *   }
 *   // index < offset is silently ignored
 *
 * Fixed method logic:
 *   if (index >= tempSyncDataOffset) {
 *       tempSyncDataOffset = index + 1;  // Handle gaps gracefully
 *   }
 *   // index < offset is silently ignored (already processed)
 */
public class UpdateSyncPositionMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    private static final String TEMP_SYNC_DATA_OFFSET_FIELD = "tempSyncDataOffset";

    public UpdateSyncPositionMethodVisitor(MethodVisitor methodVisitor, String className) {
        // Pass null to parent - we'll generate our own bytecode entirely
        super(Opcodes.ASM9, null);
        this.target = methodVisitor;
        this.className = className;
    }

    @Override
    public void visitCode() {
        // Generate the entire new method body
        generateFixedMethod();
    }

    private void generateFixedMethod() {
        Label endMethod = new Label();

        // Local vars: 0=this, 1=index

        target.visitCode();

        // if (index >= tempSyncDataOffset)
        target.visitVarInsn(Opcodes.ILOAD, 1);   // load index
        target.visitVarInsn(Opcodes.ALOAD, 0);   // load this
        target.visitFieldInsn(Opcodes.GETFIELD, className, TEMP_SYNC_DATA_OFFSET_FIELD, "I");
        target.visitJumpInsn(Opcodes.IF_ICMPLT, endMethod);  // if index < offset, skip to end

        // tempSyncDataOffset = index + 1
        target.visitVarInsn(Opcodes.ALOAD, 0);   // this
        target.visitVarInsn(Opcodes.ILOAD, 1);   // index
        target.visitInsn(Opcodes.ICONST_1);      // 1
        target.visitInsn(Opcodes.IADD);          // index + 1
        target.visitFieldInsn(Opcodes.PUTFIELD, className, TEMP_SYNC_DATA_OFFSET_FIELD, "I");

        target.visitLabel(endMethod);
        target.visitInsn(Opcodes.RETURN);

        // Set max stack and locals
        target.visitMaxs(3, 2);
        target.visitEnd();
    }

    // Override all other visit methods to do nothing (we're replacing the entire method)
    @Override
    public void visitInsn(int opcode) {
        // Ignore original bytecode
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
        // Ignore original bytecode
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        // Ignore original bytecode
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Ignore original bytecode
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        // Ignore original bytecode
    }

    @Override
    public void visitLabel(Label label) {
        // Ignore original bytecode
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Ignore original bytecode
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        // Ignore original bytecode
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        // Ignore original bytecode
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        // Ignore original bytecode
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Ignore - we set our own in generateFixedMethod
    }

    @Override
    public void visitEnd() {
        // Ignore - we already called visitEnd in generateFixedMethod
    }
}
