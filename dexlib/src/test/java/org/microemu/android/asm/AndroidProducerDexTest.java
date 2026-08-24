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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class AndroidProducerDexTest {
	@Test
	public void transformedTimingCallsitesProduceReadableDex() throws Exception {
		Path root = Files.createTempDirectory("jlmod-dex-transform-");
		Path classDirectory = Files.createDirectories(root.resolve("sample"));
		Path classFile = classDirectory.resolve("Timing.class");
		Path dateSubclassFile = classDirectory.resolve("DateSubclass.class");
		Path dexFile = root.resolve("timing.dex");
		try {
			Files.write(classFile, createTimingClass());
			Files.write(dateSubclassFile, createDateSubclassClass());

			Main.Arguments arguments = new Main.Arguments();
			arguments.fileNames = new String[] {
				root.resolve(".").resolve("sample").resolve("Timing.class").toString(),
				root.resolve(".").resolve("sample").resolve("DateSubclass.class").toString()
			};
			arguments.outName = dexFile.toString();
			arguments.numThreads = 1;
			assertEquals(0, Main.run(arguments));

			Dex dex = new Dex(dexFile.toFile());
			String bridge = "Ljavax/microedition/shell/GuestTimingBridge;";
			assertEquals(1, countMethods(dex, bridge, "currentTimeMillis"));
			assertEquals(1, countMethods(dex, bridge, "nanoTime"));
			assertEquals(2, countMethods(dex, bridge, "sleep"));
			assertEquals(2, countMethods(dex, bridge, "waitOnMonitor"));
			// Date() is rewritten to currentTimeMillis() + Date(long), keeping the original
			// allocation/constructor verifier shape valid for subclasses and non-canonical code.
			assertEquals(1, countMethods(dex, bridge, "currentTimeMillis"));
			assertEquals(2, countMethods(dex, bridge, "calendarInstance"));
		} finally {
			Files.deleteIfExists(dexFile);
			Files.deleteIfExists(classFile);
			Files.deleteIfExists(dateSubclassFile);
			Files.deleteIfExists(classDirectory);
			Files.deleteIfExists(root);
		}
	}

	@Test
	public void conversionFailsWhenOneArchiveClassCannotBeTransformed() throws Exception {
		Path root = Files.createTempDirectory("jlmod-dex-transform-failure-");
		Path archive = root.resolve("mixed.jar");
		Path dexFile = root.resolve("mixed.dex");
		try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
			output.putNextEntry(new JarEntry("sample/Good.class"));
			output.write(createSimpleClass("sample/Good"));
			output.closeEntry();
			output.putNextEntry(new JarEntry("sample/Bad.class"));
			// The path deliberately disagrees with the class's internal name.
			output.write(createSimpleClass("sample/BadPayload"));
			output.closeEntry();
		}

		try {
			Main.Arguments arguments = new Main.Arguments();
			arguments.fileNames = new String[] {archive.toString()};
			arguments.outName = dexFile.toString();
			arguments.numThreads = 1;
			assertTrue(Main.run(arguments) != 0);
		} finally {
			Files.deleteIfExists(dexFile);
			Files.deleteIfExists(archive);
			Files.deleteIfExists(root);
		}
	}

	private static int countMethods(Dex dex, String owner, String name) {
		int count = 0;
		for (MethodId method : dex.methodIds()) {
			if (owner.equals(dex.typeNames().get(method.getDeclaringClassIndex()))
					&& name.equals(dex.strings().get(method.getNameIndex()))) {
				count++;
			}
		}
		return count;
	}

	private static byte[] createTimingClass() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Timing", null,
				"java/lang/Object", null);
		createClockAndSleepMethod(writer);
		createWaitMethod(writer);
		createDateAndCalendarMethod(writer);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] createSimpleClass(String name) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null,
				"java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] createDateSubclassClass() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/DateSubclass", null,
				"java/util/Date", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(1, 1);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void createClockAndSleepMethod(ClassWriter writer) {
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"timed", "()V", null, new String[] {"java/lang/InterruptedException"});
		method.visitCode();
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
		method.visitInsn(Opcodes.POP2);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
		method.visitInsn(Opcodes.POP2);
		method.visitLdcInsn(10L);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
		method.visitLdcInsn(10L);
		method.visitInsn(Opcodes.ICONST_1);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(JI)V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(2, 0);
		method.visitEnd();
	}

	private static void createWaitMethod(ClassWriter writer) {
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"waited", "(Ljava/lang/Object;)V", null,
				new String[] {"java/lang/InterruptedException"});
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitLdcInsn(10L);
		method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "wait", "(J)V", false);
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitLdcInsn(10L);
		method.visitInsn(Opcodes.ICONST_1);
		method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "wait", "(JI)V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(3, 1);
		method.visitEnd();
	}

	private static void createDateAndCalendarMethod(ClassWriter writer) {
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"dates", "(Ljava/util/TimeZone;)Ljava/util/Date;", null, null);
		method.visitCode();
		method.visitTypeInsn(Opcodes.NEW, "java/util/Date");
		method.visitInsn(Opcodes.DUP);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
		method.visitInsn(Opcodes.POP);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Calendar", "getInstance",
				"()Ljava/util/Calendar;", false);
		method.visitInsn(Opcodes.POP);
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Calendar", "getInstance",
				"(Ljava/util/TimeZone;)Ljava/util/Calendar;", false);
		method.visitInsn(Opcodes.POP);
		method.visitTypeInsn(Opcodes.NEW, "java/util/Date");
		method.visitInsn(Opcodes.DUP);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(2, 1);
		method.visitEnd();
	}
}
