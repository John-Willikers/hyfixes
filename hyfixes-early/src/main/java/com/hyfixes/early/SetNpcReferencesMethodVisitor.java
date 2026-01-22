package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms SpawnMarkerEntity.setNpcReferences() to
 * convert null input to an empty array.
 *
 * This fixes the CODEC deserialization issue where entities saved with null
 * npcReferences load as null, causing crashes in MarkerAddRemoveSystem.
 *
 * Original:
 *   public void setNpcReferences(InvalidatablePersistentRef[] refs) {
 *       this.npcReferences = refs;  // Can be null!
 *   }
 *
 * Transformed:
 *   public void setNpcReferences(InvalidatablePersistentRef[] refs) {
 *       if (refs == null) {
 *           refs = new InvalidatablePersistentRef[0];
 *       }
 *       this.npcReferences = refs;
 *   }
 *
 * This ensures npcReferences is NEVER null after loading from disk.
 */
public class SetNpcReferencesMethodVisitor extends MethodVisitor {

    private static final String INVALIDATABLE_PERSISTENT_REF = "com/hypixel/hytale/server/core/entity/reference/InvalidatablePersistentRef";

    private boolean injected = false;

    public SetNpcReferencesMethodVisitor(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitCode() {
        super.visitCode();

        // Inject null check at the very beginning of the method
        // Parameter 'refs' is at local variable index 1 (index 0 is 'this')

        Label notNullLabel = new Label();

        // if (refs != null) goto notNullLabel
        mv.visitVarInsn(Opcodes.ALOAD, 1);  // Load refs parameter
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNullLabel);

        // refs is null - replace with empty array
        // refs = new InvalidatablePersistentRef[0];
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, INVALIDATABLE_PERSISTENT_REF);
        mv.visitVarInsn(Opcodes.ASTORE, 1);  // Store back to refs parameter

        // NOTE: Removed runtime logging to prevent log spam (fix is working silently)

        mv.visitLabel(notNullLabel);

        injected = true;
        verbose("Injected null check into setNpcReferences()");
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
