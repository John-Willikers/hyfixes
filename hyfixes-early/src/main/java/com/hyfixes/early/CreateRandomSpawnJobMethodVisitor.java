package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms BeaconSpawnController.createRandomSpawnJob()
 * to add a null check for the spawn parameter at method entry.
 *
 * The original code crashes when spawn is null:
 *   public SpawnJob createRandomSpawnJob(RoleSpawnParameters spawn, ...) {
 *       // ...
 *       spawn.getId(); // CRASH - spawn can be null!
 *   }
 *
 * The transformed code adds a null check at method entry:
 *   public SpawnJob createRandomSpawnJob(RoleSpawnParameters spawn, ...) {
 *       if (spawn == null) {
 *           System.out.println("[HyFixes-Early] WARNING: null spawn in createRandomSpawnJob");
 *           return null;
 *       }
 *       // ... original code continues
 *   }
 *
 * This prevents the NullPointerException when volcanic/cave biome spawn beacons
 * have misconfigured or missing spawn types.
 */
public class CreateRandomSpawnJobMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;
    private final String methodDescriptor;

    // For instance methods: var 0 = this, var 1 = first param (spawn)
    private static final int SPAWN_PARAM_VAR = 1;

    public CreateRandomSpawnJobMethodVisitor(MethodVisitor mv, String className, String descriptor) {
        super(Opcodes.ASM9, null);
        this.target = mv;
        this.className = className;
        this.methodDescriptor = descriptor;
    }

    @Override
    public void visitCode() {
        target.visitCode();

        // Inject null check at the very beginning of the method
        System.out.println("[HyFixes-Early] Injecting null spawn check at method entry");

        Label continueLabel = new Label();

        // Load spawn parameter (var 1) and check if null
        target.visitVarInsn(Opcodes.ALOAD, SPAWN_PARAM_VAR);
        target.visitJumpInsn(Opcodes.IFNONNULL, continueLabel);

        // spawn is null - log warning
        target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        target.visitLdcInsn("[HyFixes-Early] WARNING: null spawn in createRandomSpawnJob - returning null (missing spawn config?)");
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // Return null instead of crashing
        target.visitInsn(Opcodes.ACONST_NULL);
        target.visitInsn(Opcodes.ARETURN);

        // Continue label - spawn is not null, proceed with original code
        target.visitLabel(continueLabel);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        target.visitVarInsn(opcode, var);
    }

    @Override
    public void visitInsn(int opcode) {
        target.visitInsn(opcode);
    }

    @Override
    public void visitLdcInsn(Object value) {
        target.visitLdcInsn(value);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        target.visitFieldInsn(opcode, owner, name, descriptor);
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
        // Increase max stack for our println call
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
