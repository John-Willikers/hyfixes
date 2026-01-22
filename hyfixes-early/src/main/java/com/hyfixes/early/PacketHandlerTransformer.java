package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * HyFixes Early Plugin - PacketHandler Bytecode Transformer
 *
 * This transformer fixes the interaction timeout bug that kicks players
 * when they experience network lag during block-breaking (hatchet/tree bug).
 *
 * The Bug:
 * PacketHandler.getOperationTimeoutThreshold() uses hardcoded values that
 * are too aggressive for players with unstable connections:
 *   timeout = (avg_ping * 2.0) + 3000ms
 *
 * The Fix:
 * Replace with configurable values:
 *   timeout = (avg_ping * pingMultiplier) + baseTimeoutMs
 *
 * Default config doubles the timeout allowance, giving laggy players
 * more time to respond without causing any client/server desync.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/25">GitHub Issue #25</a>
 */
public class PacketHandlerTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.io.PacketHandler";

    @Override
    public int priority() {
        // High priority - we want this transformation to happen early
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the PacketHandler class
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("interactionTimeout")) {
            info("PacketHandlerTransformer DISABLED by config");
            return classBytes;
        }

        var config = EarlyConfigManager.getInstance().getInteractionTimeoutConfig();
        separator();
        info("Transforming PacketHandler class...");
        verbose("Fixing interaction timeout (hatchet/tree bug)");
        verbose("Config: baseTimeoutMs=" + config.baseTimeoutMs +
                           ", pingMultiplier=" + config.pingMultiplier);
        separator();

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new PacketHandlerVisitor(writer, config.baseTimeoutMs, config.pingMultiplier);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            info("PacketHandler transformation COMPLETE!");
            verbose("Original size: " + classBytes.length + " bytes");
            verbose("Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            error("ERROR: Failed to transform PacketHandler!");
            error("Returning original bytecode to prevent crash.", e);
            return classBytes;
        }
    }
}
