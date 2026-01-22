package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for BlockComponentChunk transformation.
 * Intercepts the addEntityReference method to make it handle duplicates gracefully.
 */
public class BlockComponentChunkVisitor extends ClassVisitor {

    private String className;

    private static final String ADD_ENTITY_REFERENCE_METHOD = "addEntityReference";

    public BlockComponentChunkVisitor(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals(ADD_ENTITY_REFERENCE_METHOD)) {
            verbose("Found method: " + name + descriptor);
            verbose("Applying duplicate block component fix...");
            return new AddEntityReferenceMethodVisitor(mv, className);
        }

        return mv;
    }
}
