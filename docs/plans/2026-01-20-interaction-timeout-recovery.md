# Interaction Timeout Configuration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prevent players from being kicked due to network lag during interactions by making the timeout threshold configurable and more lenient.

**Architecture:** Use ASM bytecode transformation in hyfixes-early to modify `PacketHandler.getOperationTimeoutThreshold()`. Replace the hardcoded timeout formula with configurable values that server admins can tune for their player base.

**Tech Stack:** Java 21, ASM 9.8, Hytale Server Early Plugin API

**GitHub Issue:** https://github.com/John-Willikers/hyfixes/issues/25

---

## Root Cause Analysis

The timeout is calculated in `PacketHandler.getOperationTimeoutThreshold()`:

```java
public long getOperationTimeoutThreshold() {
    double average = this.getPingInfo(PongType.Tick).getPingMetricSet().getAverage(0);
    return PingInfo.TIME_UNIT.toMillis(Math.round(average * 2.0)) + 3000L;
}
```

**Formula:** `(average_ping × 2.0) + 3000ms`

| Player Ping | Timeout |
|-------------|---------|
| 50ms | 3100ms |
| 100ms | 3200ms |
| 200ms | 3400ms |
| 500ms | 4000ms |

For players with unstable connections or temporary lag spikes, these timeouts are too aggressive. When exceeded, `InteractionManager.serverTick()` throws a RuntimeException that causes the player to be kicked.

## Fix Strategy

Transform `PacketHandler.getOperationTimeoutThreshold()` to use configurable values:

**New Formula:** `(average_ping × pingMultiplier) + baseTimeoutMs`

**Default Config (more lenient):**
- `baseTimeoutMs`: 6000 (was 3000)
- `pingMultiplier`: 3.0 (was 2.0)

| Player Ping | Old Timeout | New Timeout |
|-------------|-------------|-------------|
| 50ms | 3100ms | 6150ms |
| 100ms | 3200ms | 6300ms |
| 200ms | 3400ms | 6600ms |
| 500ms | 4000ms | 7500ms |

This gives players ~2x more time to respond without causing any desync issues.

---

## Task 1: Add Config Options to EarlyPluginConfig

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyPluginConfig.java`

**Step 1: Add transformer toggle and config class**

Add to `TransformersConfig`:
```java
public boolean interactionTimeout = true;
```

Add new config class after `EarlyLoggingConfig`:
```java
/**
 * Interaction timeout configuration
 * Controls how long the server waits for client responses during interactions
 */
public static class InteractionTimeoutConfig {
    /** Base timeout in milliseconds (added to ping-based calculation) */
    public long baseTimeoutMs = 6000;

    /** Multiplier applied to average ping */
    public double pingMultiplier = 3.0;
}
```

Add field to `EarlyPluginConfig`:
```java
public InteractionTimeoutConfig interactionTimeout = new InteractionTimeoutConfig();
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyPluginConfig.java
git commit -m "feat(config): add interactionTimeout config options"
```

---

## Task 2: Add Config Accessor to EarlyConfigManager

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyConfigManager.java`

**Step 1: Add accessor method**

Add method to get interaction timeout config:
```java
public EarlyPluginConfig.InteractionTimeoutConfig getInteractionTimeoutConfig() {
    return config.interactionTimeout;
}
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyConfigManager.java
git commit -m "feat(config): add interactionTimeout config accessor"
```

---

## Task 3: Create PacketHandlerTransformer

**Files:**
- Create: `hyfixes-early/src/main/java/com/hyfixes/early/PacketHandlerTransformer.java`

**Step 1: Create transformer class**

```java
package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

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
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        if (!EarlyConfigManager.getInstance().isTransformerEnabled("interactionTimeout")) {
            System.out.println("[HyFixes-Early] PacketHandlerTransformer DISABLED by config");
            return classBytes;
        }

        var config = EarlyConfigManager.getInstance().getInteractionTimeoutConfig();
        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming PacketHandler class...");
        System.out.println("[HyFixes-Early] Fixing interaction timeout (hatchet/tree bug)");
        System.out.println("[HyFixes-Early] Config: baseTimeoutMs=" + config.baseTimeoutMs +
                           ", pingMultiplier=" + config.pingMultiplier);
        System.out.println("[HyFixes-Early] ================================================");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new PacketHandlerVisitor(writer, config.baseTimeoutMs, config.pingMultiplier);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            System.out.println("[HyFixes-Early] PacketHandler transformation COMPLETE!");
            System.out.println("[HyFixes-Early] Original size: " + classBytes.length + " bytes");
            System.out.println("[HyFixes-Early] Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] ERROR: Failed to transform PacketHandler!");
            System.err.println("[HyFixes-Early] Returning original bytecode to prevent crash.");
            e.printStackTrace();
            return classBytes;
        }
    }
}
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/PacketHandlerTransformer.java
git commit -m "feat(early): add PacketHandlerTransformer"
```

---

## Task 4: Create PacketHandlerVisitor

**Files:**
- Create: `hyfixes-early/src/main/java/com/hyfixes/early/PacketHandlerVisitor.java`

**Step 1: Create class visitor**

```java
package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for PacketHandler.
 *
 * Intercepts getOperationTimeoutThreshold() to apply configurable timeout values.
 */
public class PacketHandlerVisitor extends ClassVisitor {

    private static final String TARGET_METHOD = "getOperationTimeoutThreshold";
    private static final String TARGET_DESCRIPTOR = "()J";  // returns long

    private String className;
    private final long baseTimeoutMs;
    private final double pingMultiplier;

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
            System.out.println("[HyFixes-Early] Found method: " + name + descriptor);
            System.out.println("[HyFixes-Early] Applying configurable timeout fix...");
            return new OperationTimeoutMethodVisitor(mv, className, baseTimeoutMs, pingMultiplier);
        }

        return mv;
    }
}
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/PacketHandlerVisitor.java
git commit -m "feat(early): add PacketHandlerVisitor"
```

---

## Task 5: Create OperationTimeoutMethodVisitor

**Files:**
- Create: `hyfixes-early/src/main/java/com/hyfixes/early/OperationTimeoutMethodVisitor.java`

**Step 1: Create method visitor**

This replaces the `getOperationTimeoutThreshold()` method with our configurable version.

Original method:
```java
public long getOperationTimeoutThreshold() {
    double average = this.getPingInfo(PongType.Tick).getPingMetricSet().getAverage(0);
    return PingInfo.TIME_UNIT.toMillis(Math.round(average * 2.0)) + 3000L;
}
```

New method:
```java
public long getOperationTimeoutThreshold() {
    double average = this.getPingInfo(PongType.Tick).getPingMetricSet().getAverage(0);
    return PingInfo.TIME_UNIT.toMillis(Math.round(average * PING_MULTIPLIER)) + BASE_TIMEOUT_MS;
}
```

```java
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
    @Override public void visitInsn(int opcode) {}
    @Override public void visitVarInsn(int opcode, int varIndex) {}
    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}
    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}
    @Override public void visitJumpInsn(int opcode, Label label) {}
    @Override public void visitLabel(Label label) {}
    @Override public void visitLdcInsn(Object value) {}
    @Override public void visitIntInsn(int opcode, int operand) {}
    @Override public void visitTypeInsn(int opcode, String type) {}
    @Override public void visitInvokeDynamicInsn(String name, String descriptor,
            org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {}
    @Override public void visitMaxs(int maxStack, int maxLocals) {}
    @Override public void visitEnd() {}
    @Override public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {}
    @Override public void visitLineNumber(int line, Label start) {}
    @Override public void visitLocalVariable(String name, String descriptor, String signature,
            Label start, Label end, int index) {}
}
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/OperationTimeoutMethodVisitor.java
git commit -m "feat(early): add OperationTimeoutMethodVisitor with configurable timeout"
```

---

## Task 6: Build and Verify

**Step 1: Build both plugins**

```bash
./gradlew clean build
```

**Step 2: Verify JAR contains new classes**

```bash
unzip -l hyfixes-early/build/libs/hyfixes-early-*.jar | grep -E "PacketHandler|OperationTimeout"
```

Expected output:
```
  com/hyfixes/early/PacketHandlerTransformer.class
  com/hyfixes/early/PacketHandlerVisitor.class
  com/hyfixes/early/OperationTimeoutMethodVisitor.class
```

**Step 3: Commit build verification**

```bash
git add -A
git commit -m "feat(early): complete interaction timeout fix implementation"
```

---

## Task 7: Update Documentation

**Files:**
- Modify: `BUGS_FIXED.md`

**Step 1: Add entry for Fix 17**

```markdown
### Fix 17: Interaction Timeout Configuration (Hatchet/Tree Bug)

**Problem:** Players get kicked from the server when they experience network lag during
block-breaking interactions (most commonly noticed when chopping trees with a hatchet).

**Root Cause:** `PacketHandler.getOperationTimeoutThreshold()` uses hardcoded timeout values
that are too aggressive for players with unstable connections:
- Formula: `(average_ping × 2.0) + 3000ms`
- A player with 100ms ping only gets 3200ms to respond
- Any lag spike exceeding this threshold causes `InteractionManager` to throw a RuntimeException
- The exception handler in `TickInteractionManagerSystem.tick()` removes the player entity

**Solution:** Bytecode transformation in hyfixes-early makes the timeout configurable:
- New formula: `(average_ping × pingMultiplier) + baseTimeoutMs`
- Default: `pingMultiplier=3.0`, `baseTimeoutMs=6000` (doubles the timeout allowance)
- Server admins can tune these values in `config.json`

**Config Example:**
```json
{
  "transformers": {
    "interactionTimeout": true
  },
  "interactionTimeout": {
    "baseTimeoutMs": 6000,
    "pingMultiplier": 3.0
  }
}
```

**Files Modified (Early Plugin):**
- `PacketHandlerTransformer.java` - Registers the bytecode transformer
- `PacketHandlerVisitor.java` - Identifies the target method
- `OperationTimeoutMethodVisitor.java` - Replaces timeout calculation with configurable values
- `EarlyPluginConfig.java` - Adds config options

**Technical Details:**
- Transforms: `com.hypixel.hytale.server.core.io.PacketHandler`
- Method: `getOperationTimeoutThreshold()J`
- No desync risk - just extends the time window for client responses
```

**Step 2: Commit**

```bash
git add BUGS_FIXED.md
git commit -m "docs: add Fix 17 - Interaction Timeout Configuration"
```

---

## Task 8: Deploy and Test

**Step 1: Upload JARs to dev server**

Copy both JARs to the dev server Docker container:
- `build/libs/hyfixes-*.jar` → `/home/container/mods/`
- `hyfixes-early/build/libs/hyfixes-early-*.jar` → `/home/container/earlyplugins/`

**Step 2: Restart server and verify transformation**

Look for in logs:
```
[HyFixes-Early] Transforming PacketHandler class...
[HyFixes-Early] Fixing interaction timeout (hatchet/tree bug)
[HyFixes-Early] Config: baseTimeoutMs=6000, pingMultiplier=3.0
[HyFixes-Early] PacketHandler transformation COMPLETE!
```

**Step 3: Test the fix**

1. Join server with a player
2. Find trees and chop with hatchet
3. If possible, simulate lag (throttle network)
4. Verify player stays connected during lag spikes
5. Verify interactions complete normally when connection stabilizes

---

## Verification Checklist

- [ ] `EarlyPluginConfig` has `interactionTimeout` transformer toggle
- [ ] `EarlyPluginConfig` has `InteractionTimeoutConfig` class with `baseTimeoutMs` and `pingMultiplier`
- [ ] `EarlyConfigManager` has `getInteractionTimeoutConfig()` accessor
- [ ] `PacketHandlerTransformer` created and targets correct class
- [ ] `PacketHandlerVisitor` created and targets `getOperationTimeoutThreshold` method
- [ ] `OperationTimeoutMethodVisitor` replaces method with configurable values
- [ ] Both JARs build successfully
- [ ] New classes present in hyfixes-early JAR
- [ ] Server logs show transformer activating with config values
- [ ] Players no longer kicked during lag spikes
- [ ] Interactions complete normally when connection recovers
- [ ] `BUGS_FIXED.md` updated with Fix 17

---

## Config Reference

Default `config.json` settings:

```json
{
  "transformers": {
    "interactionTimeout": true
  },
  "interactionTimeout": {
    "baseTimeoutMs": 6000,
    "pingMultiplier": 3.0
  }
}
```

| Setting | Default | Description |
|---------|---------|-------------|
| `transformers.interactionTimeout` | `true` | Enable/disable the transformer |
| `interactionTimeout.baseTimeoutMs` | `6000` | Base timeout added to ping calculation (was 3000) |
| `interactionTimeout.pingMultiplier` | `3.0` | Multiplier for average ping (was 2.0) |

**Timeout Comparison:**

| Ping | Old Timeout | New Default | With 10s base |
|------|-------------|-------------|---------------|
| 50ms | 3.1s | 6.15s | 10.15s |
| 100ms | 3.2s | 6.3s | 10.3s |
| 200ms | 3.4s | 6.6s | 10.6s |
| 500ms | 4.0s | 7.5s | 11.5s |
