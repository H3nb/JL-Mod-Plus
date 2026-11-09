/*
 * Copyright 2026 JL-Mod Plus contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.microemu.android.asm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.android.dex.Dex;
import com.android.dex.MethodId;
import com.android.dx.command.dexer.ConversionResult;
import com.android.dx.command.dexer.Main;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class MethodRenamerTest {
    private static final String THREAD = "sample/MyThread";
    private static final String SUB = "sample/SubThread";
    private static final String OTHER = "sample/Other";

    @Test
    public void preservesOverridingAndAllCallsitesWithSuffixCollision() {
        byte[] parent = createClass(THREAD, "java/lang/Thread", true, true);
        byte[] child = createClass(SUB, THREAD, true, false);
        byte[] unrelated = createClass(OTHER, "java/lang/Object", true, false);
        byte[] caller = createCaller();

        MethodRenamer.Builder builder = new MethodRenamer.Builder();
        builder.addClass(child);
        builder.addClass(caller);
        builder.addClass(unrelated);
        builder.addClass(parent);
        MethodRenamer renamer = builder.build();
        String method = renamer.renamed(THREAD, "resume", "()V");
        assertEquals("resume$j2me$", method);
        assertEquals(method, renamer.renamed(SUB, "resume", "()V"));
        assertEquals(null, renamer.renamed(OTHER, "resume", "()V"));

        assertTrue(methods(transform(parent, THREAD, renamer)).contains(method));
        assertTrue(methods(transform(child, SUB, renamer)).contains(method));
        assertTrue(methods(transform(unrelated, OTHER, renamer)).contains("resume"));
        List<String> calls = calls(transform(caller, "sample/Caller", renamer));
        assertTrue(calls.contains(THREAD + "." + method));
        assertTrue(calls.contains(SUB + "." + method));
        assertTrue(calls.contains(OTHER + ".resume"));
        assertFalse(calls.contains(THREAD + ".resume"));
    }

    @Test
    public void dexConversionScansArchiveAndStillSkipsUnreadableSource() throws Exception {
        Path root = Files.createTempDirectory("jlmod-thread-renamer-");
        Path archive = root.resolve("thread.jar");
        Path dexPath = root.resolve("thread.dex");
        try {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
                put(jar, THREAD + ".class", createClass(THREAD, "java/lang/Thread", true, true));
                put(jar, SUB + ".class", createClass(SUB, THREAD, true, false));
                put(jar, OTHER + ".class", createClass(OTHER, "java/lang/Object", true, false));
                put(jar, "sample/Caller.class", createCaller());
                put(jar, "sample/Broken.class", new byte[]{0, 1, 2});
            }
            Main.Arguments arguments = new Main.Arguments();
            arguments.fileNames = new String[]{archive.toString()};
            arguments.outName = dexPath.toString();
            arguments.numThreads = 2;
            ConversionResult result = Main.runWithResult(arguments);
            assertTrue(result.isSuccess(), () -> "Unexpected diagnostics: " + result.getDiagnostics());
            assertEquals(5, result.getClassesDiscovered());
            assertEquals(4, result.getClassesConverted());
            assertEquals(1, result.getClassesSkipped());

            Dex dex = new Dex(dexPath.toFile());
            assertTrue(hasMethod(dex, "Lsample/MyThread;", "resume$j2me$"));
            assertTrue(hasMethod(dex, "Lsample/SubThread;", "resume$j2me$"));
            assertTrue(hasMethod(dex, "Lsample/Other;", "resume"));
            assertFalse(hasMethod(dex, "Lsample/MyThread;", "resume"));
        } finally {
            Files.deleteIfExists(dexPath);
            Files.deleteIfExists(archive);
            Files.deleteIfExists(root);
        }
    }

    private static byte[] transform(byte[] bytes, String name, MethodRenamer renamer) {
        return AndroidProducer.instrument(bytes, name + ".class", 0L, renamer);
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static boolean hasMethod(Dex dex, String owner, String name) {
        for (MethodId method : dex.methodIds()) {
            if (owner.equals(dex.typeNames().get(method.getDeclaringClassIndex()))
                    && name.equals(dex.strings().get(method.getNameIndex()))) return true;
        }
        return false;
    }

    private static List<String> methods(byte[] bytes) {
        List<String> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                result.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static List<String> calls(byte[] bytes) {
        List<String> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (!"runCalls".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String desc, boolean itf) {
                        result.add(owner + "." + name);
                    }
                };
            }
        }, 0);
        return result;
    }

    private static byte[] createClass(String name, String parent,
                                      boolean resume, boolean reservedSuffix) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, name, null, parent, null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        if (resume) addVoid(writer, "resume");
        if (reservedSuffix) addVoid(writer, "resume$j2me");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addVoid(ClassWriter writer, String name) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
    }

    private static byte[] createCaller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, "sample/Caller", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "runCalls", "(Lsample/MyThread;Lsample/SubThread;Lsample/Other;)V", null, null);
        method.visitCode();
        String[] owners = {THREAD, SUB, OTHER};
        for (int i = 0; i < owners.length; i++) {
            method.visitVarInsn(Opcodes.ALOAD, i);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owners[i], "resume", "()V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
