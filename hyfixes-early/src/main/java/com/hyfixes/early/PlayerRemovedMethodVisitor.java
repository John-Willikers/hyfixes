package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that removes the PlayerUtil.broadcastMessageToPlayers call
 * from PlayerSystems$PlayerRemovedSystem.onEntityRemoved().
 *
 * We detect and remove the call by watching for:
 * 1. String constant "server.general.playerLeftWorld" (in LDC or INVOKEDYNAMIC)
 * 2. The subsequent INVOKESTATIC to PlayerUtil.broadcastMessageToPlayers
 *
 * The entire call chain leading up to and including the INVOKESTATIC is removed.
 */
public class PlayerRemovedMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;

    // State tracking for detecting the broadcast call
    private boolean inBroadcastCall = false;
    private int stackDepth = 0;

    public PlayerRemovedMethodVisitor(MethodVisitor mv, String className) {
        super(Opcodes.ASM9, null);
        this.target = mv;
        this.className = className;
    }

    @Override
    public void visitCode() {
        target.visitCode();
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Detect the "server.general.playerLeftWorld" string - start of broadcast call
        if (value instanceof String str) {
            if (str.equals("server.general.playerLeftWorld")) {
                inBroadcastCall = true;
                stackDepth = 0;
                System.out.println("[HyFixes-Early] Detected 'server.general.playerLeftWorld' - removing broadcast call");
                return; // Don't emit this LDC
            }
        }

        // If we're in a broadcast call, track but don't emit
        if (inBroadcastCall) {
            return;
        }

        target.visitLdcInsn(value);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Check if this is the PlayerUtil.broadcastMessageToPlayers call
        if (inBroadcastCall && opcode == Opcodes.INVOKESTATIC) {
            if (owner.endsWith("PlayerUtil") && name.equals("broadcastMessageToPlayers")) {
                // End of the broadcast call - reset state and skip this call
                System.out.println("[HyFixes-Early] Removed PlayerUtil.broadcastMessageToPlayers call");
                inBroadcastCall = false;
                stackDepth = 0;
                return; // Don't emit the INVOKESTATIC
            }
        }

        // If we're building up the broadcast call, don't emit intermediate method calls
        if (inBroadcastCall) {
            return;
        }

        target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        // If we're in a broadcast call, skip field access
        if (inBroadcastCall) {
            return;
        }
        target.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        // If we're in a broadcast call, skip variable loads
        if (inBroadcastCall) {
            return;
        }
        target.visitVarInsn(opcode, var);
    }

    @Override
    public void visitInsn(int opcode) {
        // If we're in a broadcast call, skip instructions
        if (inBroadcastCall) {
            return;
        }
        target.visitInsn(opcode);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        // If we're in a broadcast call, skip jump instructions
        if (inBroadcastCall) {
            return;
        }
        target.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLabel(Label label) {
        // If we're in a broadcast call, skip labels associated with removed code
        if (inBroadcastCall) {
            return;
        }
        target.visitLabel(label);
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        // If we're in a broadcast call, skip stack frame entries for removed code
        if (inBroadcastCall) {
            return;
        }
        target.visitFrame(type, numLocal, local, numStack, stack);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (inBroadcastCall) {
            return;
        }
        target.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (inBroadcastCall) {
            return;
        }
        target.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        // Check for string concatenation that includes "server.general.playerLeftWorld"
        if (name.equals("makeConcatWithConstants")) {
            for (Object arg : bootstrapMethodArguments) {
                if (arg instanceof String && ((String) arg).contains("server.general.playerLeftWorld")) {
                    inBroadcastCall = true;
                    stackDepth = 0;
                    System.out.println("[HyFixes-Early] Detected 'server.general.playerLeftWorld' in string concat");
                    return; // Don't emit
                }
            }
        }

        if (inBroadcastCall) {
            return;
        }

        target.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        if (inBroadcastCall) {
            return;
        }
        target.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        if (inBroadcastCall) {
            return;
        }
        target.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        if (inBroadcastCall) {
            return;
        }
        target.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        if (inBroadcastCall) {
            return;
        }
        target.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        if (inBroadcastCall) {
            return;
        }
        target.visitTryCatchBlock(start, end, handler, type);
    }

    @Override
    public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
        target.visitLocalVariable(name, descriptor, signature, start, end, index);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        if (inBroadcastCall) {
            return;
        }
        target.visitLineNumber(line, start);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        target.visitMaxs(maxStack, maxLocals);
    }

    @Override
    public void visitEnd() {
        target.visitEnd();
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
