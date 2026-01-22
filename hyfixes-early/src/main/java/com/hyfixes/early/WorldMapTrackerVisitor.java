package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for WorldMapTracker transformation.
 * Intercepts the unloadImages method to add NPE protection for iterator corruption.
 */
public class WorldMapTrackerVisitor extends ClassVisitor {

    private String className;

    private static final String UNLOAD_IMAGES_METHOD = "unloadImages";
    private static final String UNLOAD_IMAGES_DESCRIPTOR = "(III)V";

    public WorldMapTrackerVisitor(ClassVisitor cv) {
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

        if (name.equals(UNLOAD_IMAGES_METHOD) && descriptor.equals(UNLOAD_IMAGES_DESCRIPTOR)) {
            verbose("Found method: " + name + descriptor);
            verbose("Applying iterator NPE protection...");
            return new UnloadImagesMethodVisitor(mv, className);
        }

        return mv;
    }
}
