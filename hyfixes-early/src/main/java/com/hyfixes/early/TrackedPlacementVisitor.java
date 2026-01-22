package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor that transforms TrackedPlacement$OnAddRemove.onEntityRemove()
 * to handle null TrackedPlacement components gracefully.
 *
 * Original code:
 *   if (reason != RemoveReason.REMOVE) return;
 *   TrackedPlacement tracked = commandBuffer.getComponent(ref, COMPONENT_TYPE);
 *   assert (tracked != null);  // <-- Can fail!
 *   BlockCounter counter = commandBuffer.getResource(BLOCK_COUNTER_RESOURCE_TYPE);
 *   counter.untrackBlock(tracked.blockName);  // <-- NPE if tracked is null
 *
 * Transformed code:
 *   if (reason != RemoveReason.REMOVE) return;
 *   TrackedPlacement tracked = commandBuffer.getComponent(ref, COMPONENT_TYPE);
 *   if (tracked == null) {
 *       System.out.println("[HyFixes-Early] WARNING: TrackedPlacement null on remove");
 *       return;
 *   }
 *   String blockName = tracked.blockName;
 *   if (blockName == null || blockName.isEmpty()) {
 *       System.out.println("[HyFixes-Early] WARNING: blockName null/empty on remove");
 *       return;
 *   }
 *   BlockCounter counter = commandBuffer.getResource(BLOCK_COUNTER_RESOURCE_TYPE);
 *   counter.untrackBlock(blockName);
 *   System.out.println("[HyFixes-Early] Decremented counter for: " + blockName);
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/11">GitHub Issue #11</a>
 */
public class TrackedPlacementVisitor extends ClassVisitor {

    public TrackedPlacementVisitor(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Target: onEntityRemove(Ref, RemoveReason, Store, CommandBuffer)V
        if (name.equals("onEntityRemove") && descriptor.contains("RemoveReason")) {
            verbose("Found onEntityRemove method - replacing with null-safe version");
            return new OnEntityRemoveMethodVisitor(mv);
        }

        return mv;
    }

    /**
     * Replaces the entire onEntityRemove method body with a null-safe version.
     */
    private static class OnEntityRemoveMethodVisitor extends MethodVisitor {

        private final MethodVisitor target;

        public OnEntityRemoveMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, null); // Don't pass to super - we're replacing entirely
            this.target = mv;
        }

        @Override
        public void visitCode() {
            target.visitCode();

            // Labels for control flow
            Label returnLabel = new Label();
            Label trackedNullLabel = new Label();
            Label blockNameNullLabel = new Label();
            Label continueLabel = new Label();

            // ========================================
            // if (reason != RemoveReason.REMOVE) return;
            // ========================================
            // Load reason (arg 2, slot 2)
            target.visitVarInsn(Opcodes.ALOAD, 2);

            // Get RemoveReason.REMOVE
            target.visitFieldInsn(Opcodes.GETSTATIC,
                "com/hypixel/hytale/component/RemoveReason",
                "REMOVE",
                "Lcom/hypixel/hytale/component/RemoveReason;");

            // Compare - if not equal, return
            target.visitJumpInsn(Opcodes.IF_ACMPNE, returnLabel);

            // ========================================
            // TrackedPlacement tracked = commandBuffer.getComponent(ref, COMPONENT_TYPE);
            // ========================================
            // Load commandBuffer (arg 4, slot 4)
            target.visitVarInsn(Opcodes.ALOAD, 4);

            // Load ref (arg 1, slot 1)
            target.visitVarInsn(Opcodes.ALOAD, 1);

            // Get COMPONENT_TYPE field
            target.visitFieldInsn(Opcodes.GETSTATIC,
                "com/hypixel/hytale/server/core/modules/interaction/blocktrack/TrackedPlacement$OnAddRemove",
                "COMPONENT_TYPE",
                "Lcom/hypixel/hytale/component/ComponentType;");

            // Call getComponent (CommandBuffer is a class, not interface!)
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/hypixel/hytale/component/CommandBuffer",
                "getComponent",
                "(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentType;)Lcom/hypixel/hytale/component/Component;",
                false);

            // Cast to TrackedPlacement
            target.visitTypeInsn(Opcodes.CHECKCAST,
                "com/hypixel/hytale/server/core/modules/interaction/blocktrack/TrackedPlacement");

            // Store in local variable 5
            target.visitVarInsn(Opcodes.ASTORE, 5);

            // ========================================
            // if (tracked == null) { log warning; return; }
            // ========================================
            target.visitVarInsn(Opcodes.ALOAD, 5);
            target.visitJumpInsn(Opcodes.IFNULL, trackedNullLabel);

            // ========================================
            // String blockName = tracked.blockName;
            // ========================================
            target.visitVarInsn(Opcodes.ALOAD, 5);
            target.visitFieldInsn(Opcodes.GETFIELD,
                "com/hypixel/hytale/server/core/modules/interaction/blocktrack/TrackedPlacement",
                "blockName",
                "Ljava/lang/String;");
            target.visitVarInsn(Opcodes.ASTORE, 6);

            // ========================================
            // if (blockName == null || blockName.isEmpty()) { log warning; return; }
            // ========================================
            target.visitVarInsn(Opcodes.ALOAD, 6);
            target.visitJumpInsn(Opcodes.IFNULL, blockNameNullLabel);

            target.visitVarInsn(Opcodes.ALOAD, 6);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "isEmpty",
                "()Z",
                false);
            target.visitJumpInsn(Opcodes.IFNE, blockNameNullLabel);

            // ========================================
            // BlockCounter counter = commandBuffer.getResource(BLOCK_COUNTER_RESOURCE_TYPE);
            // ========================================
            target.visitVarInsn(Opcodes.ALOAD, 4);

            // Get BLOCK_COUNTER_RESOURCE_TYPE field
            target.visitFieldInsn(Opcodes.GETSTATIC,
                "com/hypixel/hytale/server/core/modules/interaction/blocktrack/TrackedPlacement$OnAddRemove",
                "BLOCK_COUNTER_RESOURCE_TYPE",
                "Lcom/hypixel/hytale/component/ResourceType;");

            // Call getResource (CommandBuffer is a class, not interface!)
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/hypixel/hytale/component/CommandBuffer",
                "getResource",
                "(Lcom/hypixel/hytale/component/ResourceType;)Lcom/hypixel/hytale/component/Resource;",
                false);

            // Cast to BlockCounter
            target.visitTypeInsn(Opcodes.CHECKCAST,
                "com/hypixel/hytale/server/core/modules/interaction/blocktrack/BlockCounter");

            // Store in local 7
            target.visitVarInsn(Opcodes.ASTORE, 7);

            // ========================================
            // counter.untrackBlock(blockName);
            // ========================================
            target.visitVarInsn(Opcodes.ALOAD, 7);
            target.visitVarInsn(Opcodes.ALOAD, 6);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/hypixel/hytale/server/core/modules/interaction/blocktrack/BlockCounter",
                "untrackBlock",
                "(Ljava/lang/String;)V",
                false);

            // ========================================
            // Log success (for debugging)
            // ========================================
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");

            // Create the log message using StringBuilder
            target.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            target.visitInsn(Opcodes.DUP);
            target.visitLdcInsn("[HyFixes-Early] BlockCounter decremented for: ");
            target.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
            target.visitVarInsn(Opcodes.ALOAD, 6);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);

            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Jump to return
            target.visitJumpInsn(Opcodes.GOTO, returnLabel);

            // ========================================
            // trackedNullLabel: Log warning and return
            // ========================================
            target.visitLabel(trackedNullLabel);
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] WARNING: TrackedPlacement component was null on entity remove - BlockCounter not decremented");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
            target.visitJumpInsn(Opcodes.GOTO, returnLabel);

            // ========================================
            // blockNameNullLabel: Log warning and return
            // ========================================
            target.visitLabel(blockNameNullLabel);
            target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            target.visitLdcInsn("[HyFixes-Early] WARNING: TrackedPlacement.blockName was null/empty on entity remove - BlockCounter not decremented");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // ========================================
            // returnLabel: return;
            // ========================================
            target.visitLabel(returnLabel);
            target.visitInsn(Opcodes.RETURN);

            // Set max stack and locals
            target.visitMaxs(5, 8);
            target.visitEnd();
        }

        // Override all other visit methods to do nothing (we're replacing the entire method)
        @Override
        public void visitInsn(int opcode) {
            // Ignore original instructions
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            // Ignore original instructions
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // Ignore original instructions
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // Ignore original instructions
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // Ignore original instructions
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            // Ignore original instructions
        }

        @Override
        public void visitLabel(Label label) {
            // Ignore original labels
        }

        @Override
        public void visitLdcInsn(Object value) {
            // Ignore original constants
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            // Ignore original frames
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Handled in visitCode
        }

        @Override
        public void visitEnd() {
            // Handled in visitCode
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            // Ignore original instructions
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            // Ignore original instructions
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            // Ignore original instructions
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            // Ignore original instructions
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            // Ignore original instructions
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            // Ignore original try-catch
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            // Ignore original local variables
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            // Ignore original line numbers
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            // Ignore original instructions
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // Preserve annotations by passing to target
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
    }
}
