package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM MethodVisitor that transforms SetMemoriesCapacityInteraction.firstRun() to
 * validate the PlayerMemories ComponentType before using it.
 *
 * The original method crashes with "ComponentType is invalid!" when the memories
 * module isn't loaded for certain instances (like Forgotten_Temple).
 *
 * This fix injects code at the start of firstRun() that:
 * 1. Gets the PlayerMemories ComponentType
 * 2. Checks if it's valid using isValid()
 * 3. If invalid, sets state to Failed and returns early
 * 4. If valid, continues with the original code
 *
 * Original firstRun() starts with:
 *   Ref entity = context.getEntity();
 *   CommandBuffer buffer = context.getCommandBuffer();
 *   ...
 *
 * Transformed to:
 *   // Check if PlayerMemories ComponentType is valid
 *   ComponentType memoriesType = PlayerMemories.getComponentType();
 *   if (!memoriesType.isValid()) {
 *       context.getState().state = InteractionState.Failed;
 *       return;
 *   }
 *   // Original code continues...
 *   Ref entity = context.getEntity();
 *   ...
 *
 * Method signature: protected void firstRun(InteractionType, InteractionContext, CooldownHandler)
 * Parameters: this=0, type=1, context=2, handler=3
 */
public class SetMemoriesCapacityMethodVisitor extends MethodVisitor {

    private static final String PLAYER_MEMORIES = "com/hypixel/hytale/builtin/adventure/memories/component/PlayerMemories";
    private static final String COMPONENT_TYPE = "com/hypixel/hytale/component/ComponentType";
    private static final String INTERACTION_CONTEXT = "com/hypixel/hytale/server/core/entity/InteractionContext";
    private static final String INTERACTION_SYNC_DATA = "com/hypixel/hytale/protocol/InteractionSyncData";
    private static final String INTERACTION_STATE = "com/hypixel/hytale/protocol/InteractionState";

    public SetMemoriesCapacityMethodVisitor(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitCode() {
        super.visitCode();

        // Inject validation check at the very start of the method
        verbose("Injecting ComponentType validation into firstRun()");

        Label continueLabel = new Label();

        // Step 1: Get the ComponentType
        // ComponentType memoriesType = PlayerMemories.getComponentType();
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            PLAYER_MEMORIES,
            "getComponentType",
            "()Lcom/hypixel/hytale/component/ComponentType;",
            false
        );

        // Step 2: Check if it's valid
        // if (memoriesType.isValid()) goto continueLabel
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            COMPONENT_TYPE,
            "isValid",
            "()Z",
            false
        );
        mv.visitJumpInsn(Opcodes.IFNE, continueLabel);  // if true (valid), skip to continue

        // Step 3: ComponentType is invalid - set state to Failed and return
        // context.getState().state = InteractionState.Failed;
        mv.visitVarInsn(Opcodes.ALOAD, 2);  // Load context (parameter 2)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            INTERACTION_CONTEXT,
            "getState",
            "()L" + INTERACTION_SYNC_DATA + ";",
            false
        );
        mv.visitFieldInsn(
            Opcodes.GETSTATIC,
            INTERACTION_STATE,
            "Failed",
            "L" + INTERACTION_STATE + ";"
        );
        mv.visitFieldInsn(
            Opcodes.PUTFIELD,
            INTERACTION_SYNC_DATA,
            "state",
            "L" + INTERACTION_STATE + ";"
        );

        // return;
        mv.visitInsn(Opcodes.RETURN);

        // Label for continuing with original code if ComponentType is valid
        mv.visitLabel(continueLabel);

        // Original code will follow...
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Increase stack size to accommodate our injected code
        super.visitMaxs(Math.max(maxStack, 3), maxLocals);
    }
}
