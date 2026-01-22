package com.hyfixes.early;

import org.objectweb.asm.MethodVisitor;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms the lambda inside CommandBuffer.removeComponent()
 * to call tryRemoveComponent() instead of removeComponent().
 *
 * The original lambda:
 *   chunk -> {
 *       if (!ref.isValid()) return;
 *       chunk.removeComponent(ref, componentType);  // THROWS if component missing!
 *   }
 *
 * The transformed lambda:
 *   chunk -> {
 *       if (!ref.isValid()) return;
 *       chunk.tryRemoveComponent(ref, componentType);  // Safe - returns false if missing
 *   }
 *
 * This fixes race conditions where multiple systems queue removal of the same
 * component - the first removal succeeds, subsequent ones safely return false
 * instead of throwing IllegalArgumentException.
 */
public class RemoveComponentLambdaMethodVisitor extends MethodVisitor {

    private final String className;
    private final String methodName;
    private boolean transformed = false;

    public RemoveComponentLambdaMethodVisitor(MethodVisitor mv, String className, String methodName) {
        super(Opcodes.ASM9, mv);
        this.className = className;
        this.methodName = methodName;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Look for calls to removeComponent and change them to tryRemoveComponent
        if (name.equals("removeComponent")) {
            verbose("  Found call: " + owner + "." + name + descriptor);
            verbose("  Replacing with: tryRemoveComponent");

            // Change the method name from removeComponent to tryRemoveComponent
            // The signature stays the same: (Ref, ComponentType) -> void
            // Note: tryRemoveComponent returns void in CommandBuffer's interface
            super.visitMethodInsn(opcode, owner, "tryRemoveComponent", descriptor, isInterface);
            transformed = true;
        } else {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    @Override
    public void visitEnd() {
        if (transformed) {
            verbose("  Lambda transformation successful!");
        } else {
            verbose("  WARNING: No removeComponent call found in lambda!");
        }
        super.visitEnd();
    }
}
