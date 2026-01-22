package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms BeaconSpawnController.createRandomSpawnJob()
 * to add a null check for the spawn LOCAL VARIABLE after it's assigned.
 *
 * The bug: createRandomSpawnJob(ComponentAccessor accessor) calls a method that returns
 * RoleSpawnParameters and stores it in a local variable 'spawn'. If that method returns null,
 * the subsequent spawn.getId() crashes with NPE.
 *
 * The pattern we're looking for:
 *   INVOKEVIRTUAL/INVOKEINTERFACE ...getRandomSpawn... -> returns RoleSpawnParameters
 *   ASTORE X  (stores result in local var X)
 *   ... later ...
 *   ALOAD X
 *   INVOKEINTERFACE getId() -> CRASH if X is null
 *
 * The fix: After ASTORE X, inject:
 *   ALOAD X
 *   IFNONNULL continue
 *   [log warning]
 *   ACONST_NULL
 *   ARETURN
 *   continue:
 *
 * This prevents the NullPointerException when volcanic/cave biome spawn beacons
 * have misconfigured or missing spawn types.
 */
public class CreateRandomSpawnJobMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;
    private final String methodDescriptor;

    // Track if we just saw a method call that returns RoleSpawnParameters
    private boolean sawSpawnReturnMethod = false;
    private int spawnLocalVarIndex = -1;
    private boolean injectedNullCheck = false;

    public CreateRandomSpawnJobMethodVisitor(MethodVisitor mv, String className, String descriptor) {
        super(Opcodes.ASM9, null);
        this.target = mv;
        this.className = className;
        this.methodDescriptor = descriptor;
    }

    @Override
    public void visitCode() {
        target.visitCode();
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

        // Check if this method returns RoleSpawnParameters (the spawn type)
        // The return type will be in the descriptor after the )
        if (descriptor.contains(")Lcom/hypixel/hytale/server/spawning/assets/spawns/config/RoleSpawnParameters;") ||
            descriptor.contains(")Lcom/hypixel/hytale/server/spawning/assets/spawns/config/")) {
            // This method returns something related to spawn parameters
            // Could be getRandomSpawn() or similar
            sawSpawnReturnMethod = true;
            verbose("Detected spawn-returning method call: " + name + descriptor);
        }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        target.visitVarInsn(opcode, var);

        // If we just saw a spawn-returning method and this is ASTORE, the result is being stored
        if (sawSpawnReturnMethod && opcode == Opcodes.ASTORE && !injectedNullCheck) {
            spawnLocalVarIndex = var;
            sawSpawnReturnMethod = false;

            // Inject null check right after storing the spawn variable
            verbose("Injecting null check after spawn stored to var " + var);

            Label continueLabel = new Label();

            // Load the spawn var we just stored and check if null
            target.visitVarInsn(Opcodes.ALOAD, var);
            target.visitJumpInsn(Opcodes.IFNONNULL, continueLabel);

            // spawn is null - log warning
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] WARNING: null spawn from getRandomSpawn() - returning null (missing spawn config in beacon?)");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Return null instead of crashing
            target.visitInsn(Opcodes.ACONST_NULL);
            target.visitInsn(Opcodes.ARETURN);

            // Continue label - spawn is not null, proceed with original code
            target.visitLabel(continueLabel);

            injectedNullCheck = true;
        } else if (opcode == Opcodes.ASTORE) {
            // Reset if this isn't immediately after a spawn method
            sawSpawnReturnMethod = false;
        }
    }

    @Override
    public void visitInsn(int opcode) {
        // Reset flag on any other instruction between method call and ASTORE
        if (sawSpawnReturnMethod && opcode != Opcodes.DUP && opcode != Opcodes.NOP) {
            // Only keep flag if it's a harmless instruction like DUP
            // Otherwise the return value isn't being stored to a variable we can check
        }
        target.visitInsn(opcode);
    }

    @Override
    public void visitLdcInsn(Object value) {
        sawSpawnReturnMethod = false;
        target.visitLdcInsn(value);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        sawSpawnReturnMethod = false;
        target.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        sawSpawnReturnMethod = false;
        target.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        sawSpawnReturnMethod = false;
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
        sawSpawnReturnMethod = false;
        target.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        target.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        sawSpawnReturnMethod = false;
        target.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        sawSpawnReturnMethod = false;
        target.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        sawSpawnReturnMethod = false;
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
            verbose("WARNING: Could not find spawn variable assignment to inject null check!");
        }
        target.visitEnd();
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        sawSpawnReturnMethod = false;
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
