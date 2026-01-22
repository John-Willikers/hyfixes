package com.hyfixes.early;

import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that injects null check for uuidComponent in onEntityRemove.
 *
 * The original code does:
 *   UUIDComponent uuidComponent = ...;
 *   UUID uuid = uuidComponent.getUuid();  // NPE if uuidComponent is null!
 *   this.uuidToEntity.remove(uuid);
 *
 * We inject a null check before the getUuid() call:
 *   if (uuidComponent == null) {
 *       System.err.println("[HyFixes] Warning: uuidComponent is null for entity removal");
 *       return;  // Safe early return
 *   }
 */
public class UUIDRemoveMethodVisitor extends MethodVisitor {

    private final String className;

    // Track state for injection
    private boolean injectedNullCheck = false;

    // UUIDComponent class internal name
    private static final String UUID_COMPONENT_TYPE = "com/hypixel/hytale/server/core/entity/UUIDComponent";
    private static final String GET_UUID_METHOD = "getUuid";

    public UUIDRemoveMethodVisitor(MethodVisitor methodVisitor, String className) {
        super(Opcodes.ASM9, methodVisitor);
        this.className = className;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Detect call to UUIDComponent.getUuid()
        if (!injectedNullCheck &&
            opcode == Opcodes.INVOKEVIRTUAL &&
            owner.equals(UUID_COMPONENT_TYPE) &&
            name.equals(GET_UUID_METHOD)) {

            verbose("Injecting null check before " + owner + "." + name);

            // At this point, uuidComponent is on the stack (ready for getUuid() call)
            // We need to: DUP it, check null, then continue

            // DUP the object reference
            mv.visitInsn(Opcodes.DUP);

            // Create label for "not null" continuation
            Label notNullLabel = new Label();

            // IFNONNULL - jump to notNullLabel if not null
            mv.visitJumpInsn(Opcodes.IFNONNULL, notNullLabel);

            // === NULL CASE ===
            // Pop the null reference (we DUP'd it)
            mv.visitInsn(Opcodes.POP);

            // Log warning: System.err.println("[HyFixes] uuidComponent null in onEntityRemove")
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] Warning: uuidComponent is null during entity removal - skipping UUID cleanup");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Return early (method returns void)
            mv.visitInsn(Opcodes.RETURN);

            // === NOT NULL CASE ===
            mv.visitLabel(notNullLabel);
            // Stack still has original uuidComponent, continue with getUuid() call

            injectedNullCheck = true;
        }

        // Continue with original instruction
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
}
