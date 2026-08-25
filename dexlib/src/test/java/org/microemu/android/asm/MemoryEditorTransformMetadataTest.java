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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.dx.command.dexer.Main;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class MemoryEditorTransformMetadataTest {
    @Test
    public void sidecarCapturesSourceAndRuntimeFieldShapes() throws Exception {
        Path root = Files.createTempDirectory("jlmod-memory-metadata-");
        Path archive = root.resolve("sample.jar");
        Path dex = root.resolve("converted.dex");
        Path metadataFile = root.resolve("memory-editor.meta");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("sample/State.class"));
            output.write(createStateClass());
            output.closeEntry();
        }

        try {
            Main.Arguments arguments = new Main.Arguments();
            arguments.fileNames = new String[] {archive.toString()};
            arguments.outName = dex.toString();
            arguments.memoryMetadataName = metadataFile.toString();
            arguments.numThreads = 1;
            assertEquals(0, Main.run(arguments));

            MemoryEditorTransformMetadata metadata =
                    MemoryEditorTransformMetadata.read(metadataFile.toFile());
            assertTrue(metadata.isCompatible());
            assertEquals(1, metadata.getClasses().size());
            MemoryEditorTransformMetadata.ClassEntry state = metadata.getClasses().get(0);
            assertEquals("sample/State", state.getSourceInternalName());
            assertEquals("sample/State", state.getRuntimeInternalName());
            assertTrue(state.hasSourceClinit());
            assertEquals(MemoryEditorTransformMetadata.PROBE_NOT_ATTEMPTED,
                    state.getProbeStatus());
            assertEquals(1, state.getFields().size());
            MemoryEditorTransformMetadata.FieldEntry timer = state.getFields().get(0);
            assertEquals("timer", timer.getName());
            assertEquals("Ljava/util/Timer;", timer.getSourceDescriptor());
            assertEquals("Ljavax/microedition/shell/custom/Timer;", timer.getRuntimeDescriptor());
            assertNotNull(metadata.getSourceJarHash());
            assertFalse(metadata.getSourceJarHash().isEmpty());
        } finally {
            Files.deleteIfExists(metadataFile);
            Files.deleteIfExists(dex);
            Files.deleteIfExists(archive);
            Files.deleteIfExists(root);
        }
    }

    private static byte[] createStateClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/State", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "timer", "Ljava/util/Timer;", null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
