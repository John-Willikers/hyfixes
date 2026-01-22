package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor that finds and wraps setInventory methods in LivingEntity.
 */
public class LivingEntityVisitor extends ClassVisitor {

    private static final String SET_INVENTORY_METHOD = "setInventory";
    private static final String INVENTORY_TYPE = "Lcom/hypixel/hytale/server/core/inventory/Inventory;";

    public LivingEntityVisitor(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                    String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Target all setInventory methods that take Inventory as first param
        if (SET_INVENTORY_METHOD.equals(name) && descriptor.startsWith("(" + INVENTORY_TYPE)) {
            verbose("Wrapping setInventory method: " + descriptor);
            return new SetInventoryMethodVisitor(mv, access, name, descriptor);
        }

        return mv;
    }
}
