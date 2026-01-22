package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms BeaconAddRemoveSystem.onEntityAdded()
 * to add a null check after getSpawnController().
 *
 * The original code crashes when spawnController is null:
 *   BeaconSpawnController spawnController = legacySpawnBeaconEntity.getSpawnController();
 *   if (!spawnController.hasSlots()) { // CRASH - spawnController can be null!
 *       npcEntity.setToDespawn();
 *       return;
 *   }
 *
 * The transformed code adds a null check:
 *   BeaconSpawnController spawnController = legacySpawnBeaconEntity.getSpawnController();
 *   if (spawnController == null) {
 *       System.out.println("[HyFixes-Early] null spawnController, despawning NPC");
 *       npcEntity.setToDespawn();
 *       return;
 *   }
 *   if (!spawnController.hasSlots()) {
 *       npcEntity.setToDespawn();
 *       return;
 *   }
 *
 * We detect getSpawnController() and inject the null check after it's stored.
 */
public class BeaconAddRemoveMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    // State tracking
    private boolean sawGetSpawnController = false;
    private int spawnControllerLocalVar = -1;
    private int npcEntityLocalVar = 8; // From bytecode analysis, NPCEntity is in local var 8

    // Descriptor constants
    private static final String GET_SPAWN_CONTROLLER_OWNER = "com/hypixel/hytale/server/spawning/beacons/LegacySpawnBeaconEntity";
    private static final String GET_SPAWN_CONTROLLER_NAME = "getSpawnController";
    private static final String GET_SPAWN_CONTROLLER_DESC = "()Lcom/hypixel/hytale/server/spawning/controllers/BeaconSpawnController;";

    private static final String BEACON_SPAWN_CONTROLLER = "com/hypixel/hytale/server/spawning/controllers/BeaconSpawnController";
    private static final String HAS_SLOTS_NAME = "hasSlots";

    private static final String NPC_ENTITY = "com/hypixel/hytale/server/npc/entities/NPCEntity";
    private static final String SET_TO_DESPAWN_NAME = "setToDespawn";

    public BeaconAddRemoveMethodVisitor(MethodVisitor mv, String className) {
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

        // Detect getSpawnController() call
        if (opcode == Opcodes.INVOKEVIRTUAL &&
            owner.equals(GET_SPAWN_CONTROLLER_OWNER) &&
            name.equals(GET_SPAWN_CONTROLLER_NAME)) {
            sawGetSpawnController = true;
            verbose("Detected getSpawnController() call");
        }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        target.visitVarInsn(opcode, var);

        // After getSpawnController() result is stored, inject null check
        if (sawGetSpawnController && opcode == Opcodes.ASTORE) {
            sawGetSpawnController = false;
            spawnControllerLocalVar = var;

            verbose("Injecting null check for spawnController (local var " + var + ")");

            // Generate null check:
            // if (spawnController == null) {
            //     System.out.println("[HyFixes-Early] WARNING: null spawnController...");
            //     npcEntity.setToDespawn();
            //     return;
            // }

            Label continueLabel = new Label();

            // Load spawnController and check if null
            target.visitVarInsn(Opcodes.ALOAD, var);
            target.visitJumpInsn(Opcodes.IFNONNULL, continueLabel);

            // spawnController is null - log warning
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] WARNING: null spawnController in BeaconAddRemoveSystem - despawning NPC (missing beacon type?)");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Despawn the NPC (same as what happens when hasSlots() returns false)
            target.visitVarInsn(Opcodes.ALOAD, npcEntityLocalVar);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NPC_ENTITY, SET_TO_DESPAWN_NAME, "()V", false);

            // Return early
            target.visitInsn(Opcodes.RETURN);

            // Continue label - spawnController is not null
            target.visitLabel(continueLabel);
        }
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
