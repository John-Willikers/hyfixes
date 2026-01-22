package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor that transforms ArchetypeChunk.getComponent()
 * to handle IndexOutOfBoundsException gracefully.
 *
 * The bug occurs when:
 * 1. Entity references become stale (entity removed from chunk)
 * 2. NPC systems try to access components from those entities
 * 3. getComponent() throws IndexOutOfBoundsException
 *
 * The fix:
 * Wrap the method body in a try-catch for IndexOutOfBoundsException
 * and return null instead of crashing.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/20">GitHub Issue #20</a>
 */
public class ArchetypeChunkVisitor extends ClassVisitor {

    public ArchetypeChunkVisitor(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Target: getComponent methods that return Component
        if (name.equals("getComponent") && descriptor.contains("Lcom/hypixel/hytale/component/Component;")) {
            verbose("Found getComponent method: " + descriptor);
            verbose("Wrapping with IndexOutOfBoundsException handler");
            return new GetComponentMethodVisitor(mv, access, descriptor);
        }

        // Also target: copySerializableEntity method
        if (name.equals("copySerializableEntity")) {
            verbose("Found copySerializableEntity method: " + descriptor);
            verbose("Wrapping with IndexOutOfBoundsException handler");
            return new CopySerializableEntityMethodVisitor(mv, access, descriptor);
        }

        return mv;
    }

    /**
     * Wraps getComponent() in a try-catch for IndexOutOfBoundsException.
     */
    private static class GetComponentMethodVisitor extends MethodVisitor {

        private final MethodVisitor target;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchHandler = new Label();
        private final Label methodEnd = new Label();
        private boolean started = false;

        public GetComponentMethodVisitor(MethodVisitor mv, int access, String descriptor) {
            super(Opcodes.ASM9, mv);
            this.target = mv;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            // Start try block
            mv.visitLabel(tryStart);
            started = true;
        }

        @Override
        public void visitInsn(int opcode) {
            // Intercept ARETURN to end try block before returning
            if (opcode == Opcodes.ARETURN && started) {
                // End try block, then return
                mv.visitLabel(tryEnd);
                super.visitInsn(opcode);

                // Add catch handler
                mv.visitLabel(catchHandler);

                // Log warning
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[HyFixes-Early] WARNING: getComponent() IndexOutOfBounds - returning null (stale entity ref)");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

                // Return null
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                started = false;
            } else {
                super.visitInsn(opcode);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Register the try-catch block
            mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/IndexOutOfBoundsException");

            // Need extra stack space for exception handling
            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
        }
    }

    /**
     * Wraps copySerializableEntity() in a try-catch for IndexOutOfBoundsException.
     * This handles the crash during chunk saving (Issue #29).
     */
    private static class CopySerializableEntityMethodVisitor extends MethodVisitor {

        private final MethodVisitor target;
        private final String descriptor;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchHandler = new Label();
        private boolean started = false;
        private boolean handlerAdded = false;

        public CopySerializableEntityMethodVisitor(MethodVisitor mv, int access, String descriptor) {
            super(Opcodes.ASM9, mv);
            this.target = mv;
            this.descriptor = descriptor;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            // Start try block
            mv.visitLabel(tryStart);
            started = true;
        }

        @Override
        public void visitInsn(int opcode) {
            // Intercept return instructions to end try block before returning
            if (started && !handlerAdded && isReturnOpcode(opcode)) {
                // End try block, then return
                mv.visitLabel(tryEnd);
                super.visitInsn(opcode);

                // Add catch handler
                mv.visitLabel(catchHandler);

                // Log warning
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[HyFixes-Early] WARNING: copySerializableEntity() IndexOutOfBounds - skipping (stale entity ref)");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

                // Return appropriate default value based on return type
                emitDefaultReturn(opcode);

                handlerAdded = true;
            } else {
                super.visitInsn(opcode);
            }
        }

        private boolean isReturnOpcode(int opcode) {
            return opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN ||
                   opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN ||
                   opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN;
        }

        private void emitDefaultReturn(int originalReturnOpcode) {
            switch (originalReturnOpcode) {
                case Opcodes.ARETURN:
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
                    break;
                case Opcodes.IRETURN:
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    break;
                case Opcodes.LRETURN:
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                    break;
                case Opcodes.FRETURN:
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                    break;
                case Opcodes.DRETURN:
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                    break;
                case Opcodes.RETURN:
                default:
                    mv.visitInsn(Opcodes.RETURN);
                    break;
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Register the try-catch block
            mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/IndexOutOfBoundsException");

            // Need extra stack space for exception handling
            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
        }
    }
}
