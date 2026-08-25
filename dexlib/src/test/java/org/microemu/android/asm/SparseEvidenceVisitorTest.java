/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microemu.android.asm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.dex.Dex;
import com.android.dex.MethodId;
import com.android.dx.command.dexer.Main;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class SparseEvidenceVisitorTest {
    @Test
    public void existingClinitGetsOneProbeAndDexRemainsReadable() throws Exception {
        Path root = Files.createTempDirectory("jlmod-sparse-probe-");
        Path archive = root.resolve("sample.jar");
        Path dexFile = root.resolve("sample.dex");
        Path metadataFile = root.resolve("sample.meta");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("sample/Probe.class"));
            output.write(createNormalClinitClass());
            output.closeEntry();
        }
        try {
            Main.Arguments arguments = new Main.Arguments();
            arguments.fileNames = new String[] {archive.toString()};
            arguments.outName = dexFile.toString();
            arguments.memoryMetadataName = metadataFile.toString();
            arguments.numThreads = 1;
            assertEquals(0, Main.run(arguments));

            Dex dex = new Dex(dexFile.toFile());
            assertEquals(1, countMethods(dex,
                    "Ljavax/microedition/shell/MemoryProbe;", "classInitReturned"));
            MemoryEditorTransformMetadata metadata =
                    MemoryEditorTransformMetadata.read(metadataFile.toFile());
            assertEquals(1, metadata.getClasses().size());
            assertEquals(MemoryEditorTransformMetadata.PROBE_INSERTED,
                    metadata.getClasses().get(0).getProbeStatus());
        } finally {
            Files.deleteIfExists(metadataFile);
            Files.deleteIfExists(dexFile);
            Files.deleteIfExists(archive);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void exceptionalClinitDoesNotGetNormalReturnEvidence() {
        MemoryEditorTransformMetadata.Builder metadata =
                new MemoryEditorTransformMetadata.Builder();
        byte[] source = createExceptionalClinitClass();
        byte[] transformed = AndroidProducer.instrument(source, "sample/Exceptional.class", 0L, metadata);

        AtomicInteger probeCalls = new AtomicInteger();
        new ClassReader(transformed).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        if (SparseEvidenceVisitor.PROBE_OWNER.equals(owner)
                                && SparseEvidenceVisitor.PROBE_METHOD.equals(name)) {
                            probeCalls.incrementAndGet();
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        assertEquals(0, probeCalls.get());
        assertEquals(MemoryEditorTransformMetadata.PROBE_NO_NORMAL_RETURN,
                metadata.snapshot().getClasses().get(0).getProbeStatus());
        assertTrue(transformed.length > 0);
    }

    private static int countMethods(Dex dex, String owner, String name) {
        int count = 0;
        for (MethodId method : dex.methodIds()) {
            if (owner.equals(dex.typeNames().get(method.getDeclaringClassIndex()))
                    && name.equals(dex.strings().get(method.getNameIndex()))) count++;
        }
        return count;
    }

    private static byte[] createNormalClinitClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Probe", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createExceptionalClinitClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Exceptional", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null,
                new String[] {"java/lang/RuntimeException"});
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
