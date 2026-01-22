package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for EntityStore$UUIDSystem.
 *
 * Intercepts the onEntityRemove method to inject null check for uuidComponent.
 */
public class UUIDSystemVisitor extends ClassVisitor {

    private static final String TARGET_METHOD = "onEntityRemove";
    private static final String TARGET_DESCRIPTOR = "(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/RemoveReason;Lcom/hypixel/hytale/component/Store;Lcom/hypixel/hytale/component/CommandBuffer;)V";

    private String className;
    private boolean transformed = false;

    public UUIDSystemVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals(TARGET_METHOD) && descriptor.equals(TARGET_DESCRIPTOR)) {
            verbose("Found method: " + className + "." + name + descriptor);
            verbose("Applying uuidComponent null check...");
            transformed = true;
            return new UUIDRemoveMethodVisitor(mv, className);
        }

        return mv;
    }

    /**
     * Check if the transformation was applied.
     */
    public boolean isTransformed() {
        return transformed;
    }
}
