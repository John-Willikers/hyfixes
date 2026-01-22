package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms MarkerAddRemoveSystem.onEntityRemove()
 * to add a null check for the npcReferences array.
 *
 * The bug: onEntityRemove() calls SpawnMarkerEntity.getNpcReferences() which can return null.
 * The code then tries to iterate over this null array, crashing with:
 *   NullPointerException: Cannot read the array length because "<local15>" is null
 *   at SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove(SpawnReferenceSystems.java:166)
 *
 * The pattern we're looking for:
 *   INVOKEVIRTUAL/INVOKEINTERFACE SpawnMarkerEntity.getNpcReferences()[InvalidatablePersistentRef;
 *   ASTORE X  (stores result in local var X)
 *   ...
 *   ALOAD X
 *   ARRAYLENGTH  -> CRASH if X is null!
 *
 * The fix: After ASTORE X, inject:
 *   ALOAD X
 *   IFNONNULL continue
 *   [log warning - optional]
 *   RETURN  (exit method early, nothing to iterate)
 *   continue:
 *
 * This prevents the NullPointerException when spawn markers have null npcReferences.
 * Much more efficient than runtime sanitizer which was fixing 7000+ entities per session!
 */
public class MarkerRemoveMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    // Track if we just saw a method call that returns the npcReferences array
    private boolean sawNpcReferencesCall = false;
    private int npcRefsLocalVarIndex = -1;
    private boolean injectedNullCheck = false;

    public MarkerRemoveMethodVisitor(MethodVisitor mv, String className) {
        super(Opcodes.ASM9, null);
        this.target = mv;
        this.className = className;
    }

    @Override
    public void visitCode() {
        target.visitCode();
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

        // Check if this is a call to getNpcReferences() returning InvalidatablePersistentRef[]
        // The descriptor would be something like "()[L...InvalidatablePersistentRef;"
        if (name.equals("getNpcReferences") ||
            (descriptor.contains("[Lcom/hypixel/hytale/server/core/entity/reference/InvalidatablePersistentRef;") &&
             descriptor.startsWith("()"))) {
            sawNpcReferencesCall = true;
            verbose("Detected getNpcReferences() call: " + owner + "." + name + descriptor);
        }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        target.visitVarInsn(opcode, var);

        // If we just saw getNpcReferences() and this is ASTORE, the result is being stored
        if (sawNpcReferencesCall && opcode == Opcodes.ASTORE && !injectedNullCheck) {
            npcRefsLocalVarIndex = var;
            sawNpcReferencesCall = false;

            // Inject null check right after storing the npcReferences array
            verbose("Injecting null check after npcReferences stored to var " + var);

            Label continueLabel = new Label();

            // Load the npcRefs var we just stored and check if null
            target.visitVarInsn(Opcodes.ALOAD, var);
            target.visitJumpInsn(Opcodes.IFNONNULL, continueLabel);

            // npcReferences is null - log warning and return early
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] Skipping null npcReferences in onEntityRemove() - spawn marker had no NPC refs");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Return early - nothing to iterate over
            target.visitInsn(Opcodes.RETURN);

            // Continue label - npcReferences is not null, proceed with iteration
            target.visitLabel(continueLabel);

            injectedNullCheck = true;
        } else if (opcode == Opcodes.ASTORE) {
            // Reset if this isn't immediately after getNpcReferences
            sawNpcReferencesCall = false;
        }
    }

    @Override
    public void visitInsn(int opcode) {
        target.visitInsn(opcode);
    }

    @Override
    public void visitLdcInsn(Object value) {
        sawNpcReferencesCall = false;
        target.visitLdcInsn(value);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        sawNpcReferencesCall = false;
        target.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        sawNpcReferencesCall = false;
        target.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        sawNpcReferencesCall = false;
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
        sawNpcReferencesCall = false;
        target.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        target.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        sawNpcReferencesCall = false;
        target.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        sawNpcReferencesCall = false;
        target.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        sawNpcReferencesCall = false;
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
        // Increase max stack for our println call and null check
        target.visitMaxs(maxStack + 3, maxLocals);
    }

    @Override
    public void visitEnd() {
        if (!injectedNullCheck) {
            verbose("WARNING: Could not find getNpcReferences() call to inject null check!");
            verbose("The method structure may have changed - fix may not be applied.");
        }
        target.visitEnd();
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        sawNpcReferencesCall = false;
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
