package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that wraps setInventory() to validate inventory ownership.
 *
 * Before any inventory assignment, this calls InventoryOwnershipGuard.validateAndClone()
 * to check if the inventory is already owned by a different player. If so, it clones
 * the inventory to prevent shared reference bugs.
 *
 * Transformation:
 * Before: public Inventory setInventory(Inventory inventory, ...) { ... }
 * After:  public Inventory setInventory(Inventory inventory, ...) {
 *             inventory = InventoryOwnershipGuard.validateAndClone(this, inventory);
 *             ... original code ...
 *         }
 */
public class SetInventoryMethodVisitor extends MethodVisitor {

    private static final String GUARD_CLASS = "com/hyfixes/guard/InventoryOwnershipGuard";
    private static final String LIVING_ENTITY_CLASS = "com/hypixel/hytale/server/core/entity/LivingEntity";
    private static final String INVENTORY_CLASS = "com/hypixel/hytale/server/core/inventory/Inventory";

    private final String methodName;
    private final String methodDescriptor;
    private boolean injected = false;

    public SetInventoryMethodVisitor(MethodVisitor mv, int access, String name, String descriptor) {
        super(Opcodes.ASM9, mv);
        this.methodName = name;
        this.methodDescriptor = descriptor;
    }

    @Override
    public void visitCode() {
        super.visitCode();

        if (!injected) {
            injectValidation();
            injected = true;
        }
    }

    private void injectValidation() {
        // Generate: inventory = InventoryOwnershipGuard.validateAndClone(this, inventory);
        //
        // Stack operations:
        // 1. ALOAD 0 - push 'this' (LivingEntity)
        // 2. ALOAD 1 - push 'inventory' parameter
        // 3. INVOKESTATIC validateAndClone
        // 4. ASTORE 1 - store result back to inventory parameter

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();

        // Try block for the guard call (in case guard class isn't loaded)
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");

        mv.visitLabel(tryStart);

        // Load 'this' (the LivingEntity)
        mv.visitVarInsn(Opcodes.ALOAD, 0);

        // Load inventory parameter (slot 1)
        mv.visitVarInsn(Opcodes.ALOAD, 1);

        // Call static method: InventoryOwnershipGuard.validateAndClone(LivingEntity, Inventory)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            GUARD_CLASS,
            "validateAndClone",
            "(L" + LIVING_ENTITY_CLASS + ";L" + INVENTORY_CLASS + ";)L" + INVENTORY_CLASS + ";",
            false
        );

        // Store result back to inventory parameter
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        mv.visitLabel(tryEnd);

        // Jump over catch block
        Label afterCatch = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

        // Catch handler - just swallow the exception and continue with original inventory
        mv.visitLabel(catchHandler);
        mv.visitInsn(Opcodes.POP); // Pop the exception

        mv.visitLabel(afterCatch);

        verbose("Injected inventory ownership validation into " + methodName);
    }
}
