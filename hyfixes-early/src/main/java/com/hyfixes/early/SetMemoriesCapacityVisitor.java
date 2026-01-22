package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for SetMemoriesCapacityInteraction.
 *
 * Transforms the firstRun() method to add a validation check for the
 * PlayerMemories ComponentType before attempting to use it.
 */
public class SetMemoriesCapacityVisitor extends ClassVisitor {

    private static final String FIRST_RUN_METHOD = "firstRun";
    private static final String FIRST_RUN_DESC = "(Lcom/hypixel/hytale/protocol/InteractionType;Lcom/hypixel/hytale/server/core/entity/InteractionContext;Lcom/hypixel/hytale/server/core/modules/interaction/interaction/CooldownHandler;)V";

    public SetMemoriesCapacityVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (FIRST_RUN_METHOD.equals(name) && FIRST_RUN_DESC.equals(descriptor)) {
            verbose("Found method: " + name + descriptor);
            verbose("Applying ComponentType validation fix...");
            return new SetMemoriesCapacityMethodVisitor(mv);
        }

        return mv;
    }
}
