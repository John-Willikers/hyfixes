package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms SpawnMarkerEntity.spawnNPC() to fix the
 * null npcReferences bug.
 *
 * The bug: When storedFlock is null, the code skips creating the npcReferences array.
 * But the NPC was still spawned (stored in local var 23), so we need to track it.
 *
 * Strategy:
 * We find the IFNULL jump that skips array creation when storedFlock is null,
 * and at the target label, we inject code to create and populate the array
 * if it's still null.
 *
 * Injected code (pseudocode):
 *   if (this.npcReferences == null && npcRef != null) {
 *       this.npcReferences = new InvalidatablePersistentRef[1];
 *       InvalidatablePersistentRef ref = new InvalidatablePersistentRef();
 *       ref.setEntity(npcRef, store);
 *       this.npcReferences[0] = ref;
 *   }
 */
public class SpawnNPCMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    // Track state for injection
    private boolean sawStoredFlockNullCheck = false;
    private boolean injectedFix = false;
    private boolean sawRefreshTimeoutCall = false;

    // For tracking the storedFlock null check pattern
    private boolean sawAload0 = false;
    private boolean sawStoredFlockGetfield = false;

    // Field/class names we need
    private static final String SPAWN_MARKER_ENTITY = "com/hypixel/hytale/server/spawning/spawnmarkers/SpawnMarkerEntity";
    private static final String INVALIDATABLE_PERSISTENT_REF = "com/hypixel/hytale/server/core/entity/reference/InvalidatablePersistentRef";
    private static final String COMPONENT_ACCESSOR = "com/hypixel/hytale/component/ComponentAccessor";

    // Local variable indices (from bytecode analysis)
    private static final int LOCAL_VAR_NPC_REF = 23;  // The spawned NPC reference
    private static final int LOCAL_VAR_STORE = 3;     // The Store parameter

    public SpawnNPCMethodVisitor(MethodVisitor mv, String className) {
        super(Opcodes.ASM9, null);  // Don't chain to mv yet, we'll delegate manually
        this.target = mv;
        this.className = className;
    }

    @Override
    public void visitCode() {
        target.visitCode();
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        target.visitVarInsn(opcode, var);

        // Track ALOAD 0 (this)
        if (opcode == Opcodes.ALOAD && var == 0) {
            sawAload0 = true;
        } else {
            sawAload0 = false;
            sawStoredFlockGetfield = false;
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        target.visitFieldInsn(opcode, owner, name, descriptor);

        // Track GETFIELD storedFlock after ALOAD 0
        if (sawAload0 && opcode == Opcodes.GETFIELD && name.equals("storedFlock")) {
            sawStoredFlockGetfield = true;
            verbose("Found storedFlock field access in spawnNPC");
        } else {
            sawAload0 = false;
            sawStoredFlockGetfield = false;
        }
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        target.visitJumpInsn(opcode, label);

        // Track IFNULL after GETFIELD storedFlock - confirms this method has the bug pattern
        if (sawStoredFlockGetfield && opcode == Opcodes.IFNULL) {
            sawStoredFlockNullCheck = true;
            verbose("Found storedFlock null check pattern - will inject fix before return");
        }

        sawAload0 = false;
        sawStoredFlockGetfield = false;
    }

    @Override
    public void visitLabel(Label label) {
        target.visitLabel(label);
    }

    @Override
    public void visitInsn(int opcode) {
        // Inject our fix right before ICONST_1 that follows refreshTimeout() call
        // This is the successful return path at the end of the method
        if (opcode == Opcodes.ICONST_1 && sawStoredFlockNullCheck && sawRefreshTimeoutCall && !injectedFix) {
            verbose("Injecting npcReferences null fix before success return");

            // Inject: if (this.npcReferences == null && npcRef != null) { create array }
            injectNpcReferencesNullFix();

            injectedFix = true;
        }

        target.visitInsn(opcode);

        // Reset after any instruction
        sawRefreshTimeoutCall = false;
    }

    /**
     * Injects bytecode to create and populate npcReferences if it's null.
     *
     * Equivalent Java:
     *   if (this.npcReferences == null) {
     *       Ref npcRef = <local var 23>;
     *       if (npcRef != null) {
     *           this.npcReferences = new InvalidatablePersistentRef[1];
     *           InvalidatablePersistentRef ref = new InvalidatablePersistentRef();
     *           ref.setEntity(npcRef, store);
     *           this.npcReferences[0] = ref;
     *       }
     *   }
     */
    private void injectNpcReferencesNullFix() {
        Label skipLabel = new Label();

        // Check if npcReferences is null
        target.visitVarInsn(Opcodes.ALOAD, 0);  // this
        target.visitFieldInsn(Opcodes.GETFIELD, SPAWN_MARKER_ENTITY, "npcReferences",
                "[L" + INVALIDATABLE_PERSISTENT_REF + ";");
        target.visitJumpInsn(Opcodes.IFNONNULL, skipLabel);  // if not null, skip

        // Check if npcRef (local 23) is not null
        target.visitVarInsn(Opcodes.ALOAD, LOCAL_VAR_NPC_REF);  // npcRef
        target.visitJumpInsn(Opcodes.IFNULL, skipLabel);  // if null, skip

        // Log that we're fixing the null array
        target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        target.visitLdcInsn("[HyFixes-Early] Creating missing npcReferences array in spawnNPC (storedFlock was null)");
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // this.npcReferences = new InvalidatablePersistentRef[1];
        target.visitVarInsn(Opcodes.ALOAD, 0);  // this
        target.visitInsn(Opcodes.ICONST_1);     // size = 1
        target.visitTypeInsn(Opcodes.ANEWARRAY, INVALIDATABLE_PERSISTENT_REF);
        target.visitFieldInsn(Opcodes.PUTFIELD, SPAWN_MARKER_ENTITY, "npcReferences",
                "[L" + INVALIDATABLE_PERSISTENT_REF + ";");

        // InvalidatablePersistentRef ref = new InvalidatablePersistentRef();
        target.visitTypeInsn(Opcodes.NEW, INVALIDATABLE_PERSISTENT_REF);
        target.visitInsn(Opcodes.DUP);
        target.visitMethodInsn(Opcodes.INVOKESPECIAL, INVALIDATABLE_PERSISTENT_REF, "<init>", "()V", false);
        // Stack: [ref]

        // DUP ref so we can use it for both setEntity and array store
        target.visitInsn(Opcodes.DUP);
        // Stack: [ref, ref]

        // ref.setEntity(npcRef, store);
        target.visitVarInsn(Opcodes.ALOAD, LOCAL_VAR_NPC_REF);  // npcRef (Ref)
        target.visitVarInsn(Opcodes.ALOAD, LOCAL_VAR_STORE);    // store (ComponentAccessor)
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INVALIDATABLE_PERSISTENT_REF, "setEntity",
                "(Lcom/hypixel/hytale/component/Ref;L" + COMPONENT_ACCESSOR + ";)V", false);
        // Stack: [ref] (setEntity consumed the first ref copy)

        // this.npcReferences[0] = ref;
        target.visitVarInsn(Opcodes.ALOAD, 0);  // this
        target.visitFieldInsn(Opcodes.GETFIELD, SPAWN_MARKER_ENTITY, "npcReferences",
                "[L" + INVALIDATABLE_PERSISTENT_REF + ";");
        // Stack: [ref, npcReferences]
        target.visitInsn(Opcodes.SWAP);
        // Stack: [npcReferences, ref]
        target.visitInsn(Opcodes.ICONST_0);
        // Stack: [npcReferences, ref, 0]
        target.visitInsn(Opcodes.SWAP);
        // Stack: [npcReferences, 0, ref]
        target.visitInsn(Opcodes.AASTORE);
        // Stack: [] (stored ref at npcReferences[0])

        // Skip label
        target.visitLabel(skipLabel);
    }

    @Override
    public void visitLdcInsn(Object value) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
        target.visitLdcInsn(value);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
        target.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

        // Track call to refreshTimeout() - the success return follows this
        if (name.equals("refreshTimeout") && owner.contains("SpawnMarkerEntity")) {
            sawRefreshTimeoutCall = true;
            verbose("Detected refreshTimeout() call in spawnNPC");
        }
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        target.visitFrame(type, numLocal, local, numStack, stack);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
        target.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        target.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
        target.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
        target.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
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
        // Increase max stack for our injected code
        target.visitMaxs(maxStack + 4, maxLocals);
    }

    @Override
    public void visitEnd() {
        if (!injectedFix) {
            if (sawStoredFlockNullCheck) {
                verbose("WARNING: Found storedFlock null check but couldn't inject fix!");
            } else {
                verbose("WARNING: Could not find storedFlock null check pattern!");
            }
            verbose("The method structure may have changed - fix may not be applied.");
        }
        target.visitEnd();
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        sawAload0 = false;
        sawStoredFlockGetfield = false;
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
