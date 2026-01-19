package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

/**
 * HyFixes Early Plugin - CommandBuffer Bytecode Transformer
 *
 * This transformer fixes the "ComponentType is not in archetype" crash that occurs
 * when deferred component removal is attempted on a component that no longer exists.
 *
 * The Bug:
 * CommandBuffer.removeComponent() queues a lambda that calls Store.removeComponent().
 * Due to race conditions between multiple systems, the component may be removed by
 * one system before another system's deferred removal executes, causing a crash.
 *
 * Example race condition:
 *   1. System A checks: MountedComponent exists? Yes!
 *   2. System A queues: removeComponent(MountedComponent)
 *   3. System B checks: MountedComponent exists? Yes!
 *   4. System B queues: removeComponent(MountedComponent)
 *   5. System A's removal executes: SUCCESS
 *   6. System B's removal executes: CRASH - component already gone!
 *
 * Error: java.lang.IllegalArgumentException: ComponentType is not in archetype:
 *        ComponentType{...MountedComponent, index=114}
 * at com.hypixel.hytale.component.CommandBuffer.lambda$removeComponent$0(CommandBuffer.java:430)
 *
 * The Fix:
 * Transform the lambda in CommandBuffer.removeComponent() to call tryRemoveComponent()
 * instead of removeComponent(). The tryRemoveComponent() method safely checks if the
 * component exists before attempting removal, returning false instead of throwing.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/12">GitHub Issue #12</a>
 */
public class CommandBufferTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.component.CommandBuffer";

    @Override
    public int priority() {
        // High priority - we want this transformation to happen early
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        // Only transform the CommandBuffer class
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("commandBuffer")) {
            System.out.println("[HyFixes-Early] CommandBufferTransformer DISABLED by config");
            return classBytes;
        }

        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming CommandBuffer class...");
        System.out.println("[HyFixes-Early] Fixing removeComponent() race condition (Issue #12)");
        System.out.println("[HyFixes-Early] Making deferred removals use tryRemoveComponent()");
        System.out.println("[HyFixes-Early] ================================================");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new CommandBufferVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            System.out.println("[HyFixes-Early] CommandBuffer transformation COMPLETE!");
            System.out.println("[HyFixes-Early] Original size: " + classBytes.length + " bytes");
            System.out.println("[HyFixes-Early] Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] ERROR: Failed to transform CommandBuffer!");
            System.err.println("[HyFixes-Early] Returning original bytecode to prevent crash.");
            e.printStackTrace();
            return classBytes;
        }
    }
}
