package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms World.addPlayer() to handle the race condition
 * where a player is being added to a new world before being removed from their old world.
 *
 * The original code:
 *   if (playerRef.getReference() != null) {
 *       throw new IllegalStateException("Player is already in a world");
 *   }
 *
 * The transformed code:
 *   if (playerRef.getReference() != null) {
 *       // Log warning but continue - Hytale's drain will clean up
 *       System.out.println("[HyFixes] Warning: Player still in world, proceeding anyway");
 *   }
 *
 * We detect the pattern by watching for the specific LDC constant "Player is already in a world"
 * and replace the ATHROW with a jump past the exception block.
 */
public class WorldAddPlayerMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    // State machine for detecting the pattern
    private boolean sawPlayerAlreadyInWorldString = false;
    private boolean inExceptionBlock = false;
    private Label skipLabel = null;

    // Track if we've seen the getReference() call followed by ifnull
    private boolean afterGetReferenceCall = false;
    private Label continuationLabel = null;

    public WorldAddPlayerMethodVisitor(MethodVisitor mv, String className) {
        // Pass null to parent - we'll forward everything ourselves with modifications
        super(Opcodes.ASM9, null);
        this.target = mv;
        this.className = className;
    }

    @Override
    public void visitCode() {
        target.visitCode();
    }

    @Override
    public void visitInsn(int opcode) {
        // Check if this is the ATHROW after "Player is already in a world"
        if (opcode == Opcodes.ATHROW && sawPlayerAlreadyInWorldString) {
            // Replace the throw with a POP (to remove the exception from stack) and continue
            // Actually, we need to pop the exception object that was built
            // The stack has: exception object
            // We want to pop it and continue
            target.visitInsn(Opcodes.POP);

            // Log that we skipped the exception
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] WARNING: Player already in world during addPlayer - proceeding anyway (race condition handled)");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Reset state
            sawPlayerAlreadyInWorldString = false;
            // Don't emit the ATHROW - just continue
            return;
        }

        target.visitInsn(opcode);
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Detect the "Player is already in a world" string constant
        if (value instanceof String && value.equals("Player is already in a world")) {
            sawPlayerAlreadyInWorldString = true;
            System.out.println("[HyFixes-Early] Found 'Player is already in a world' exception pattern");
        }
        target.visitLdcInsn(value);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        target.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        target.visitVarInsn(opcode, var);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        target.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        target.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLabel(Label label) {
        target.visitLabel(label);
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        target.visitFrame(type, numLocal, local, numStack, stack);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        target.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        target.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        target.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        target.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        target.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        target.visitTryCatchBlock(start, end, handler, type);
    }

    @Override
    public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
        target.visitLocalVariable(name, descriptor, signature, start, end, index);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        target.visitLineNumber(line, start);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Increase max stack by 2 for our println call
        target.visitMaxs(maxStack + 2, maxLocals);
    }

    @Override
    public void visitEnd() {
        target.visitEnd();
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        target.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return target.visitAnnotation(descriptor, visible);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
        return target.visitParameterAnnotation(parameter, descriptor, visible);
    }

    @Override
    public void visitParameter(String name, int access) {
        target.visitParameter(name, access);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitAnnotationDefault() {
        return target.visitAnnotationDefault();
    }

    @Override
    public void visitAttribute(org.objectweb.asm.Attribute attribute) {
        target.visitAttribute(attribute);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
        return target.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitInsnAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
        return target.visitInsnAnnotation(typeRef, typePath, descriptor, visible);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitTryCatchAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
        return target.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitLocalVariableAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
        return target.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, descriptor, visible);
    }
}
