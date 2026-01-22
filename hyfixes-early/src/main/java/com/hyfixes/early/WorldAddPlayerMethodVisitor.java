package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import org.objectweb.asm.Label;
import static com.hyfixes.early.EarlyLogger.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that transforms World.addPlayer() to handle the race condition
 * where a player is being added to a new world before being removed from their old world.
 *
 * The original code:
 *   if (playerRef.getReference() != null) {
 *       throw new IllegalStateException("Player is already in a world");
 *   }
 *
 * The transformed code (Option A - Retry Loop):
 *   if (playerRef.getReference() != null) {
 *       // Race condition - wait for drain to complete (5 retries x 20ms = 100ms max)
 *       // Unrolled loop to avoid frame computation issues
 *       Thread.sleep(20); if (playerRef.getReference() == null) goto continue;
 *       Thread.sleep(20); if (playerRef.getReference() == null) goto continue;
 *       Thread.sleep(20); if (playerRef.getReference() == null) goto continue;
 *       Thread.sleep(20); if (playerRef.getReference() == null) goto continue;
 *       Thread.sleep(20); if (playerRef.getReference() == null) goto continue;
 *       // Still not null after 100ms - throw original exception
 *       throw new IllegalStateException("Player is already in a world");
 *   continue:
 *       System.out.println("[HyFixes-Early] Race condition resolved");
 *   }
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/7">GitHub Issue #7</a>
 */
public class WorldAddPlayerMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    // Configuration (loaded from EarlyConfigManager)
    private final int retryCount;
    private final long retryDelayMs;
    private final long retryDelayNanos;

    // State machine for detecting the pattern
    private boolean sawPlayerAlreadyInWorldString = false;

    public WorldAddPlayerMethodVisitor(MethodVisitor mv, String className) {
        super(Opcodes.ASM9, null);
        this.target = mv;
        this.className = className;

        // Load configuration
        EarlyConfigManager config = EarlyConfigManager.getInstance();
        this.retryCount = config.getWorldRetryCount();
        this.retryDelayMs = config.getWorldRetryDelayMs();
        this.retryDelayNanos = retryDelayMs * 1_000_000L; // Convert ms to nanos
    }

    @Override
    public void visitCode() {
        target.visitCode();
    }

    @Override
    public void visitInsn(int opcode) {
        // Check if this is the ATHROW after "Player is already in a world"
        if (opcode == Opcodes.ATHROW && sawPlayerAlreadyInWorldString) {
            verbose("Injecting retry loop for race condition fix");

            // Pop the exception that was built (we'll recreate it if needed)
            target.visitInsn(Opcodes.POP);

            // playerRef is in local variable slot 1 (first parameter after 'this')
            int playerRefSlot = 1;

            // Single label for successful continuation
            Label retryContinue = new Label();

            // Log that we're entering retry mode
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] Player reference not null - waiting for drain (race condition handling)");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Unrolled retry loop - retryCount iterations x retryDelayMs each
            // No try-catch needed - we'll use LockSupport.parkNanos which doesn't throw
            long totalWaitMs = retryCount * retryDelayMs;

            for (int retry = 0; retry < retryCount; retry++) {
                // Check if reference cleared
                target.visitVarInsn(Opcodes.ALOAD, playerRefSlot);
                target.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "com/hypixel/hytale/server/core/universe/PlayerRef",
                    "getReference",
                    "()Lcom/hypixel/hytale/component/Ref;",
                    false
                );
                target.visitJumpInsn(Opcodes.IFNULL, retryContinue);

                // Use LockSupport.parkNanos - configured delay, doesn't throw InterruptedException
                target.visitLdcInsn(retryDelayNanos);
                target.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/util/concurrent/locks/LockSupport",
                    "parkNanos",
                    "(J)V",
                    false
                );
            }

            // Final check after all retries
            target.visitVarInsn(Opcodes.ALOAD, playerRefSlot);
            target.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "com/hypixel/hytale/server/core/universe/PlayerRef",
                "getReference",
                "()Lcom/hypixel/hytale/component/Ref;",
                false
            );
            target.visitJumpInsn(Opcodes.IFNULL, retryContinue);

            // Still not null after configured wait time - log failure and throw
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] Retry FAILED - player still in world after " + totalWaitMs + "ms, throwing exception");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Recreate and throw the exception
            target.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            target.visitInsn(Opcodes.DUP);
            target.visitLdcInsn("Player is already in a world");
            target.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException",
                "<init>",
                "(Ljava/lang/String;)V",
                false
            );
            target.visitInsn(Opcodes.ATHROW);

            // Success label - reference cleared during retry
            target.visitLabel(retryContinue);

            // Log success
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] Race condition RESOLVED - player reference cleared during retry wait");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Continue with normal method execution
            sawPlayerAlreadyInWorldString = false;
            return;
        }

        target.visitInsn(opcode);
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Detect the "Player is already in a world" string constant
        if (value instanceof String && value.equals("Player is already in a world")) {
            sawPlayerAlreadyInWorldString = true;
            verbose("Found 'Player is already in a world' exception pattern - will inject retry loop");
        }
        target.visitLdcInsn(value);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        target.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        target.visitVarInsn(opcode, var);
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
        // Increase stack size for our operations
        target.visitMaxs(maxStack + 4, maxLocals);
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
