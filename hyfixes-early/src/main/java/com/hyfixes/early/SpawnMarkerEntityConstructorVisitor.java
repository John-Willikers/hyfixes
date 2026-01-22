package com.hyfixes.early;

import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms SpawnMarkerEntity's constructor to
 * initialize npcReferences to an empty array instead of leaving it null.
 *
 * This fixes the case where CODEC deserialization skips null/missing fields,
 * never calling setNpcReferences(), leaving the field as null.
 *
 * Original constructor ends with:
 *   ... initialize other fields ...
 *   RETURN
 *
 * Transformed to:
 *   ... initialize other fields ...
 *   this.npcReferences = new InvalidatablePersistentRef[0];
 *   RETURN
 */
public class SpawnMarkerEntityConstructorVisitor extends MethodVisitor {

    private static final String SPAWN_MARKER_ENTITY = "com/hypixel/hytale/server/spawning/spawnmarkers/SpawnMarkerEntity";
    private static final String INVALIDATABLE_PERSISTENT_REF = "com/hypixel/hytale/server/core/entity/reference/InvalidatablePersistentRef";

    private boolean injected = false;

    public SpawnMarkerEntityConstructorVisitor(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitInsn(int opcode) {
        // Inject initialization right before RETURN
        if (opcode == Opcodes.RETURN && !injected) {
            // this.npcReferences = new InvalidatablePersistentRef[0];
            mv.visitVarInsn(Opcodes.ALOAD, 0);  // this
            mv.visitInsn(Opcodes.ICONST_0);     // size = 0
            mv.visitTypeInsn(Opcodes.ANEWARRAY, INVALIDATABLE_PERSISTENT_REF);
            mv.visitFieldInsn(Opcodes.PUTFIELD, SPAWN_MARKER_ENTITY, "npcReferences",
                    "[L" + INVALIDATABLE_PERSISTENT_REF + ";");

            injected = true;
            verbose("Injected npcReferences initialization into constructor");
        }

        super.visitInsn(opcode);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Increase stack size for our injected code
        super.visitMaxs(Math.max(maxStack, 2), maxLocals);
    }

    public boolean isInjected() {
        return injected;
    }
}
