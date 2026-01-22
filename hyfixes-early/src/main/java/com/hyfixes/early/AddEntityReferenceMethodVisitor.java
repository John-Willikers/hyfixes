package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms BlockComponentChunk.addEntityReference()
 * to handle duplicate block components gracefully instead of throwing.
 *
 * The original code throws when a duplicate is detected:
 *   throw new IllegalArgumentException("Duplicate block components at: " + position);
 *
 * The transformed code logs a warning and returns:
 *   System.out.println("[HyFixes-Early] WARNING: Duplicate block components, ignoring");
 *   return;
 *
 * We detect the pattern by watching for:
 * 1. LDC "Duplicate block components" (or string starting with it)
 * 2. Or INVOKEDYNAMIC makeConcatWithConstants with that pattern
 * Then replace the subsequent ATHROW with POP + warning + RETURN
 */
public class AddEntityReferenceMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    // State tracking
    private boolean sawDuplicateBlockComponentsString = false;
    private boolean sawNewIllegalArgumentException = false;

    public AddEntityReferenceMethodVisitor(MethodVisitor mv, String className) {
        super(Opcodes.ASM9, null);
        this.target = mv;
        this.className = className;
    }

    @Override
    public void visitCode() {
        target.visitCode();
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        // Detect NEW IllegalArgumentException
        if (opcode == Opcodes.NEW && type.equals("java/lang/IllegalArgumentException")) {
            sawNewIllegalArgumentException = true;
        }
        target.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Detect the "Duplicate block components" string
        if (value instanceof String) {
            String str = (String) value;
            if (str.contains("Duplicate block components")) {
                sawDuplicateBlockComponentsString = true;
                verbose("Found 'Duplicate block components' exception pattern");
            }
        }
        target.visitLdcInsn(value);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        // Check for string concatenation that includes "Duplicate block components"
        if (name.equals("makeConcatWithConstants")) {
            for (Object arg : bootstrapMethodArguments) {
                if (arg instanceof String && ((String) arg).contains("Duplicate block components")) {
                    sawDuplicateBlockComponentsString = true;
                    verbose("Found 'Duplicate block components' in string concat");
                    break;
                }
            }
        }
        target.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void visitInsn(int opcode) {
        // Check if this is the ATHROW after the duplicate block components check
        if (opcode == Opcodes.ATHROW && sawDuplicateBlockComponentsString) {
            // Replace the throw with: POP (remove exception), log warning, return
            target.visitInsn(Opcodes.POP); // Remove the exception from stack

            // Log warning
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] WARNING: Duplicate block component detected - ignoring (teleporter fix)");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Return instead of throwing (method returns void)
            target.visitInsn(Opcodes.RETURN);

            // Reset state
            sawDuplicateBlockComponentsString = false;
            sawNewIllegalArgumentException = false;

            verbose("Replaced ATHROW with warning + return");
            return; // Don't emit the original ATHROW
        }

        target.visitInsn(opcode);
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
        // Increase max stack for our println call
        target.visitMaxs(maxStack + 2, maxLocals);
    }

    @Override
    public void visitEnd() {
        target.visitEnd();
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
