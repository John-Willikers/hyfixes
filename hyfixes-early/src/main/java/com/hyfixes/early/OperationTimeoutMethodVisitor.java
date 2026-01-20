package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that REPLACES getOperationTimeoutThreshold() with configurable values.
 *
 * Original formula: (avg_ping * 2.0) + 3000L
 * New formula:      (avg_ping * pingMultiplier) + baseTimeoutMs
 */
public class OperationTimeoutMethodVisitor extends MethodVisitor {

    private final String className;
    private final MethodVisitor target;
    private final long baseTimeoutMs;
    private final double pingMultiplier;

    // Class/field references from decompiled code
    private static final String PONG_TYPE = "com/hypixel/hytale/server/core/io/PongType";
    private static final String PING_INFO = "com/hypixel/hytale/server/core/io/PingInfo";
    private static final String PING_METRIC_SET = "com/hypixel/hytale/server/core/io/PingMetricSet";
    private static final String TIME_UNIT = "java/util/concurrent/TimeUnit";

    public OperationTimeoutMethodVisitor(MethodVisitor methodVisitor, String className,
                                          long baseTimeoutMs, double pingMultiplier) {
        super(Opcodes.ASM9, null);  // null parent - we generate entirely new bytecode
        this.target = methodVisitor;
        this.className = className;
        this.baseTimeoutMs = baseTimeoutMs;
        this.pingMultiplier = pingMultiplier;
    }

    @Override
    public void visitCode() {
        generateFixedMethod();
    }

    private void generateFixedMethod() {
        /*
         * Original bytecode does:
         *   double average = this.getPingInfo(PongType.Tick).getPingMetricSet().getAverage(0);
         *   return PingInfo.TIME_UNIT.toMillis(Math.round(average * 2.0)) + 3000L;
         *
         * We change:
         *   - 2.0 -> pingMultiplier (configurable)
         *   - 3000L -> baseTimeoutMs (configurable)
         *
         * Local vars: 0=this, 1-2=average (double takes 2 slots)
         */

        target.visitCode();

        // double average = this.getPingInfo(PongType.Tick).getPingMetricSet().getAverage(0);
        target.visitVarInsn(Opcodes.ALOAD, 0);  // this
        target.visitFieldInsn(Opcodes.GETSTATIC, PONG_TYPE, "Tick", "L" + PONG_TYPE + ";");
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "getPingInfo",
                "(L" + PONG_TYPE + ";)L" + PING_INFO + ";", false);
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PING_INFO, "getPingMetricSet",
                "()L" + PING_METRIC_SET + ";", false);
        target.visitInsn(Opcodes.ICONST_0);  // 0 for getAverage parameter
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PING_METRIC_SET, "getAverage", "(I)D", false);
        target.visitVarInsn(Opcodes.DSTORE, 1);  // store average in locals 1-2

        // return PingInfo.TIME_UNIT.toMillis(Math.round(average * pingMultiplier)) + baseTimeoutMs;

        // Get PingInfo.TIME_UNIT
        target.visitFieldInsn(Opcodes.GETSTATIC, PING_INFO, "TIME_UNIT", "L" + TIME_UNIT + ";");

        // Math.round(average * pingMultiplier)
        target.visitVarInsn(Opcodes.DLOAD, 1);  // load average
        target.visitLdcInsn(pingMultiplier);    // push pingMultiplier (configurable!)
        target.visitInsn(Opcodes.DMUL);         // average * pingMultiplier
        target.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(D)J", false);

        // TIME_UNIT.toMillis(...)
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TIME_UNIT, "toMillis", "(J)J", false);

        // + baseTimeoutMs
        target.visitLdcInsn(baseTimeoutMs);     // push baseTimeoutMs (configurable!)
        target.visitInsn(Opcodes.LADD);

        // return
        target.visitInsn(Opcodes.LRETURN);

        target.visitMaxs(6, 3);
        target.visitEnd();
    }

    // Override all visit methods to ignore original bytecode
    @Override
    public void visitInsn(int opcode) {
        // Ignore original bytecode
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
        // Ignore original bytecode
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        // Ignore original bytecode
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Ignore original bytecode
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        // Ignore original bytecode
    }

    @Override
    public void visitLabel(Label label) {
        // Ignore original bytecode
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Ignore original bytecode
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        // Ignore original bytecode
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        // Ignore original bytecode
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor,
            org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        // Ignore original bytecode
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Ignore - we set our own in generateFixedMethod
    }

    @Override
    public void visitEnd() {
        // Ignore - we already called visitEnd in generateFixedMethod
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        // Ignore original bytecode
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        // Ignore original bytecode
    }

    @Override
    public void visitLocalVariable(String name, String descriptor, String signature,
            Label start, Label end, int index) {
        // Ignore original bytecode
    }
}
