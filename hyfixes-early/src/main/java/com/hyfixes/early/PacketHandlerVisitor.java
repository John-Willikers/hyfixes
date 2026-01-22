package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for PacketHandler.
 *
 * This visitor intercepts problematic methods and applies fixes:
 * 1. getOperationTimeoutThreshold - hardcoded timeout values kick laggy players
 *
 * The original method uses: (avg_ping * 2.0) + 3000ms
 * This is too aggressive for players with unstable connections, causing them
 * to be kicked during block-breaking interactions (the "hatchet/tree bug").
 *
 * Our fix replaces the hardcoded values with configurable ones:
 * (avg_ping * pingMultiplier) + baseTimeoutMs
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/25">GitHub Issue #25</a>
 */
public class PacketHandlerVisitor extends ClassVisitor {

    private static final String TARGET_METHOD = "getOperationTimeoutThreshold";
    // Descriptor check ensures we match the exact method signature, not overloads
    private static final String TARGET_DESCRIPTOR = "()J";

    private String className;
    private final long baseTimeoutMs;
    private final double pingMultiplier;

    /**
     * Creates a new PacketHandlerVisitor.
     *
     * @param classVisitor the downstream visitor in the chain
     * @param baseTimeoutMs base timeout in milliseconds added to ping calculation (default: 6000)
     * @param pingMultiplier multiplier applied to average ping (default: 3.0)
     */
    public PacketHandlerVisitor(ClassVisitor classVisitor, long baseTimeoutMs, double pingMultiplier) {
        super(Opcodes.ASM9, classVisitor);
        this.baseTimeoutMs = baseTimeoutMs;
        this.pingMultiplier = pingMultiplier;
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
            verbose("Found method: " + name + descriptor);
            verbose("Applying configurable timeout fix...");
            return new OperationTimeoutMethodVisitor(mv, className, baseTimeoutMs, pingMultiplier);
        }

        return mv;
    }
}
