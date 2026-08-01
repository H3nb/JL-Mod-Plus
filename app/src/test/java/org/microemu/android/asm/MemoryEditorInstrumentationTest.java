/*
 * Copyright 2026 H3NB
 *
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

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import com.android.dx.command.dexer.Main;
import javax.microedition.shell.memory.MemoryEditorRuntime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MemoryEditorInstrumentationTest {
	private static final String CLASS_NAME = "game/GeneratedMemoryEditorTarget";

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@After
	public void tearDown() {
		MemoryEditorRuntime.clear();
	}

	@Test
	public void transformedFieldAccessIsVerifierSafeAndPreservesValues() throws Exception {
		Class<?> targetClass = defineTransformedTarget();
		Object target = targetClass.getDeclaredConstructor().newInstance();

		Method read = targetClass.getMethod("read");
		Method write = targetClass.getMethod("write", int.class);
		assertEquals(7, read.invoke(target));
		write.invoke(target, 42);
		assertEquals(42, read.invoke(target));

		Method readStatic = targetClass.getMethod("readStatic");
		Method writeStatic = targetClass.getMethod("writeStatic", long.class);
		writeStatic.invoke(null, 1234567890123L);
		assertEquals(1234567890123L, readStatic.invoke(null));
	}

	@Test
	public void transformedPrimitiveArrayAccessPreservesEverySupportedStackShape()
			throws Exception {
		Class<?> targetClass = defineTransformedTarget();

		byte[] bytes = {1};
		boolean[] booleans = {false};
		char[] chars = {'a'};
		short[] shorts = {2};
		int[] ints = {3};
		long[] longs = {4L};
		float[] floats = {5.0f};
		double[] doubles = {6.0d};

		assertArrayRoundTrip(targetClass, "Byte", byte[].class, byte.class, bytes, (byte) 11);
		assertArrayRoundTrip(targetClass, "Boolean", boolean[].class, boolean.class,
				booleans, true);
		assertArrayRoundTrip(targetClass, "Char", char[].class, char.class, chars, 'z');
		assertArrayRoundTrip(targetClass, "Short", short[].class, short.class,
				shorts, (short) 12);
		assertArrayRoundTrip(targetClass, "Int", int[].class, int.class, ints, 13);
		assertArrayRoundTrip(targetClass, "Long", long[].class, long.class, longs, 14L);
		assertArrayRoundTrip(targetClass, "Float", float[].class, float.class,
				floats, 15.5f);
		assertArrayRoundTrip(targetClass, "Double", double[].class, double.class,
				doubles, 16.5d);
	}

	@Test
	public void activeSearchCanFindEditAndFreezeAValueThroughTransformedCode()
			throws Exception {
		Class<?> targetClass = defineTransformedTarget();
		Object target = targetClass.getDeclaredConstructor().newInstance();
		Method read = targetClass.getMethod("read");
		Method write = targetClass.getMethod("write", int.class);

		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		assertEquals(7, read.invoke(target));
		MemoryEditorRuntime.finishCollection();

		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
		assertEquals(1, MemoryEditorRuntime.results(0, 200).size());
		assertEquals(1, MemoryEditorRuntime.editAll("42"));
		assertEquals(42, read.invoke(target));

		assertEquals(1, MemoryEditorRuntime.freezeAll("99"));
		write.invoke(target, 5);
		assertEquals(99, read.invoke(target));
	}

	@Test
	public void allPrimitiveHookFamiliesAreEmitted() {
		byte[] transformed = transform(createTarget());
		Set<String> hooks = new HashSet<>();
		Set<String> bridgeFields = new HashSet<>();
		new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitFieldInsn(int opcode, String owner, String name,
							String descriptor) {
						if (owner.equals(
								"javax/microedition/shell/memory/MemoryEditorBridge")) {
							bridgeFields.add(name);
						}
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String name,
							String descriptor, boolean isInterface) {
						if (owner.equals(
								"javax/microedition/shell/memory/MemoryEditorBridge")) {
							hooks.add(name);
						}
					}
				};
			}
		}, 0);

		assertTrue(hooks.contains("onReadInt"));
		assertTrue(hooks.contains("onWriteInt"));
		assertTrue(hooks.contains("onReadLong"));
		assertTrue(hooks.contains("onWriteLong"));
		assertTrue(hooks.contains("onReadFloat"));
		assertTrue(hooks.contains("onWriteFloat"));
		assertTrue(hooks.contains("onReadDouble"));
		assertTrue(hooks.contains("onWriteDouble"));
		assertTrue(bridgeFields.contains("ACTIVE_KINDS"));
	}

	@Test
	public void transformedPrimitiveAccessesCompleteTheRealDxPipeline() throws Exception {
		File input = temporaryFolder.newFile("game.jar");
		try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(input))) {
			jar.putNextEntry(new JarEntry(CLASS_NAME + ".class"));
			jar.write(createTarget());
			jar.closeEntry();
		}
		File output = new File(temporaryFolder.getRoot(), "converted.zip");

		Main.main(new String[]{
				"--no-optimize",
				"--output=" + output.getAbsolutePath(),
				input.getAbsolutePath()
		});

		assertTrue(output.isFile());
		try (ZipFile zip = new ZipFile(output)) {
			assertTrue(zip.getEntry("classes.dex") != null);
		}
	}

	@Test
	public void methodSizeFailureFallsBackToCompatibilityTransform() throws Exception {
		AndroidProducer.InstrumentationResult result = AndroidProducer.instrumentWithReport(
				createLargeTarget(), "game/FallbackTarget.class", 0L);

		assertFalse(result.memoryEditorApplied);
		assertEquals("METHOD_TOO_LARGE_PRECHECK", result.memoryEditorSkipReason);
		assertTrue(containsMethodCall(result.bytes,
				"javax/microedition/shell/time/EmulationTime", "sleep"));
		Class<?> targetClass = new ByteArrayClassLoader().define(result.bytes);
		targetClass.getMethod("large");
	}

	@Test
	public void dupX1StackShapeNeverEscapesTheOptionalInstrumentationLayer() {
		AndroidProducer.InstrumentationResult result = AndroidProducer.instrumentWithReport(
				createDupX1Target(), "game/DupX1Target.class", 0L);

		assertTrue(result.bytes.length > 0);
		new ClassReader(result.bytes).accept(new ClassVisitor(Opcodes.ASM9) {
		}, 0);
		if (!result.memoryEditorApplied) {
			assertTrue(result.memoryEditorSkipReason.equals("STACK_ANALYSIS")
					|| result.memoryEditorSkipReason.equals("INSTRUMENTATION_FAILED"));
		}
	}

	@Test
	public void dxFailureKeepsTheClassNameForTheInstallerReport() throws Exception {
		File input = temporaryFolder.newFile("broken.jar");
		try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(input))) {
			jar.putNextEntry(new JarEntry("game/Broken.class"));
			jar.write(new byte[]{0, 1, 2, 3});
			jar.closeEntry();
		}

		try {
			Main.main(new String[]{
					"--no-optimize",
					"--output=" + new File(temporaryFolder.getRoot(),
							"broken-converted.zip").getAbsolutePath(),
					input.getAbsolutePath()
			});
			fail("Expected DEX conversion failure");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(
					"game/Broken.class"));
		}
	}

	private static void assertArrayRoundTrip(Class<?> targetClass, String suffix,
			Class<?> arrayType, Class<?> valueType, Object array, Object replacement)
			throws Exception {
		Method write = targetClass.getMethod("write" + suffix, arrayType, int.class, valueType);
		Method read = targetClass.getMethod("read" + suffix, arrayType, int.class);
		write.invoke(null, array, 0, replacement);
		assertEquals(replacement, read.invoke(null, array, 0));
	}

	private static Class<?> defineTransformedTarget() {
		return new ByteArrayClassLoader().define(transform(createTarget()));
	}

	private static byte[] transform(byte[] original) {
		ClassWriter writer = new ClassWriter(
				ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		new ClassReader(original).accept(new AndroidClassVisitor(writer), 0);
		return writer.toByteArray();
	}

	private static byte[] createTarget() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, CLASS_NAME, null,
				"java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "staticValue",
				"J", null, null).visitEnd();

		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
				null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>",
				"()V", false);
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitIntInsn(Opcodes.BIPUSH, 7);
		method.visitFieldInsn(Opcodes.PUTFIELD, CLASS_NAME, "value", "I");
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();

		addInstanceFieldMethod(writer, "read", "()I", Opcodes.GETFIELD, Opcodes.IRETURN);
		addInstanceFieldMethod(writer, "write", "(I)V", Opcodes.PUTFIELD, Opcodes.RETURN);
		addStaticFieldMethod(writer, "readStatic", "()J", Opcodes.GETSTATIC, Opcodes.LRETURN);
		addStaticFieldMethod(writer, "writeStatic", "(J)V", Opcodes.PUTSTATIC, Opcodes.RETURN);

		addArrayMethods(writer, "Byte", "[B", Opcodes.BALOAD, Opcodes.BASTORE,
				Opcodes.IRETURN, Opcodes.ILOAD);
		addArrayMethods(writer, "Boolean", "[Z", Opcodes.BALOAD, Opcodes.BASTORE,
				Opcodes.IRETURN, Opcodes.ILOAD);
		addArrayMethods(writer, "Char", "[C", Opcodes.CALOAD, Opcodes.CASTORE,
				Opcodes.IRETURN, Opcodes.ILOAD);
		addArrayMethods(writer, "Short", "[S", Opcodes.SALOAD, Opcodes.SASTORE,
				Opcodes.IRETURN, Opcodes.ILOAD);
		addArrayMethods(writer, "Int", "[I", Opcodes.IALOAD, Opcodes.IASTORE,
				Opcodes.IRETURN, Opcodes.ILOAD);
		addArrayMethods(writer, "Long", "[J", Opcodes.LALOAD, Opcodes.LASTORE,
				Opcodes.LRETURN, Opcodes.LLOAD);
		addArrayMethods(writer, "Float", "[F", Opcodes.FALOAD, Opcodes.FASTORE,
				Opcodes.FRETURN, Opcodes.FLOAD);
		addArrayMethods(writer, "Double", "[D", Opcodes.DALOAD, Opcodes.DASTORE,
				Opcodes.DRETURN, Opcodes.DLOAD);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] createLargeTarget() {
		String name = "game/FallbackTarget";
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null,
				"java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "I", null,
				null).visitEnd();

		MethodVisitor large = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"large", "()V", null, null);
		large.visitCode();
		for (int i = 0; i < 12_000; i++) {
			large.visitFieldInsn(Opcodes.GETSTATIC, name, "value", "I");
			large.visitInsn(Opcodes.POP);
		}
		large.visitLdcInsn(1L);
		large.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep",
				"(J)V", false);
		large.visitInsn(Opcodes.RETURN);
		large.visitMaxs(0, 0);
		large.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] createDupX1Target() {
		String name = "game/DupX1Target";
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null,
				"java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "I", null,
				null).visitEnd();
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"mutate", "()V", null, null);
		method.visitCode();
		method.visitFieldInsn(Opcodes.GETSTATIC, name, "value", "I");
		method.visitInsn(Opcodes.ICONST_1);
		method.visitInsn(Opcodes.DUP_X1);
		method.visitFieldInsn(Opcodes.PUTSTATIC, name, "value", "I");
		method.visitInsn(Opcodes.POP);
		method.visitInsn(Opcodes.POP);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static boolean containsMethodCall(byte[] bytes, String owner, String name) {
		final boolean[] found = {false};
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String methodName,
					String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String calledOwner,
							String calledName, String calledDescriptor, boolean isInterface) {
						if (owner.equals(calledOwner) && name.equals(calledName)) {
							found[0] = true;
						}
					}
				};
			}
		}, 0);
		return found[0];
	}

	private static void addInstanceFieldMethod(ClassWriter writer, String name,
			String descriptor, int fieldOpcode, int returnOpcode) {
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor,
				null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		if (fieldOpcode == Opcodes.PUTFIELD) {
			method.visitVarInsn(Opcodes.ILOAD, 1);
		}
		method.visitFieldInsn(fieldOpcode, CLASS_NAME, "value", "I");
		method.visitInsn(returnOpcode);
		method.visitMaxs(0, 0);
		method.visitEnd();
	}

	private static void addStaticFieldMethod(ClassWriter writer, String name,
			String descriptor, int fieldOpcode, int returnOpcode) {
		MethodVisitor method = writer.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
		method.visitCode();
		if (fieldOpcode == Opcodes.PUTSTATIC) {
			method.visitVarInsn(Opcodes.LLOAD, 0);
		}
		method.visitFieldInsn(fieldOpcode, CLASS_NAME, "staticValue", "J");
		method.visitInsn(returnOpcode);
		method.visitMaxs(0, 0);
		method.visitEnd();
	}

	private static void addArrayMethods(ClassWriter writer, String suffix,
			String arrayDescriptor, int loadOpcode, int storeOpcode, int returnOpcode,
			int valueLoadOpcode) {
		String valueDescriptor = arrayDescriptor.substring(1);
		MethodVisitor read = writer.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read" + suffix,
				"(" + arrayDescriptor + "I)" + valueDescriptor, null, null);
		read.visitCode();
		read.visitVarInsn(Opcodes.ALOAD, 0);
		read.visitVarInsn(Opcodes.ILOAD, 1);
		read.visitInsn(loadOpcode);
		read.visitInsn(returnOpcode);
		read.visitMaxs(0, 0);
		read.visitEnd();

		MethodVisitor write = writer.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write" + suffix,
				"(" + arrayDescriptor + "I" + valueDescriptor + ")V", null, null);
		write.visitCode();
		write.visitVarInsn(Opcodes.ALOAD, 0);
		write.visitVarInsn(Opcodes.ILOAD, 1);
		write.visitVarInsn(valueLoadOpcode, 2);
		write.visitInsn(storeOpcode);
		write.visitInsn(Opcodes.RETURN);
		write.visitMaxs(0, 0);
		write.visitEnd();
	}

	private static final class ByteArrayClassLoader extends ClassLoader {
		private ByteArrayClassLoader() {
			super(MemoryEditorInstrumentationTest.class.getClassLoader());
		}

		private Class<?> define(byte[] bytes) {
			return defineClass(null, bytes, 0, bytes.length);
		}
	}
}
