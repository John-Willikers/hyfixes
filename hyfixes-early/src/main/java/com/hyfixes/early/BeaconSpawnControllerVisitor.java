package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.hyfixes.early.EarlyLogger.*;

/**
 * ASM ClassVisitor for BeaconSpawnController transformation.
 * Intercepts the createRandomSpawnJob method to add null check for spawn parameter.
 */
public class BeaconSpawnControllerVisitor extends ClassVisitor {

    private String className;

    private static final String CREATE_RANDOM_SPAWN_JOB_METHOD = "createRandomSpawnJob";

    public BeaconSpawnControllerVisitor(ClassVisitor cv) {
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

        if (name.equals(CREATE_RANDOM_SPAWN_JOB_METHOD)) {
            verbose("Found method: " + name + descriptor);
            verbose("Applying null spawn parameter check...");
            return new CreateRandomSpawnJobMethodVisitor(mv, className, descriptor);
        }

        return mv;
    }
}
