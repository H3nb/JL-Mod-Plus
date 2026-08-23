package org.microemu.android.asm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.V1_5;

import com.android.dex.ClassDef;
import com.android.dex.Dex;
import com.android.dex.MethodId;
import com.android.dx.command.dexer.Main;
import com.android.dx.io.Opcodes;
import com.android.dx.io.instructions.DecodedInstruction;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class DxBooleanReturnIntegrationTest {
	private static final String FIXTURE_NAME = "org/microemu/android/asm/DxBooleanReturnFixture";
	private static final String FIXTURE_DESCRIPTOR = "L" + FIXTURE_NAME + ";";

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void dxMaterializesBooleanNarrowingAsIntegerOperation() throws Exception {
		File inputJar = temporaryFolder.newFile("fixture.jar");
		writeFixtureJar(inputJar);
		File output = new File(temporaryFolder.getRoot(), "converted.zip");

		Main.main(new String[]{
				"--no-optimize",
				"--output=" + output.getAbsolutePath(),
				inputJar.getAbsolutePath()
		});

		Dex dex = new Dex(output);
		List<Integer> booleanOpcodes = methodOpcodes(dex, "fromShortField");
		assertNotNull(booleanOpcodes);
		assertTrue(booleanOpcodes.contains(Opcodes.SGET_SHORT));
		assertTrue("boolean return must contain an integer AND before DEX return",
				containsIntAnd(booleanOpcodes));
		assertEquals(Integer.valueOf(Opcodes.RETURN),
				booleanOpcodes.get(booleanOpcodes.size() - 1));

		List<Integer> intOpcodes = methodOpcodes(dex, "asInt");
		assertNotNull(intOpcodes);
		assertFalse("non-boolean return must not gain boolean narrowing",
				containsIntAnd(intOpcodes));
		assertEquals(Integer.valueOf(Opcodes.RETURN),
				intOpcodes.get(intOpcodes.size() - 1));
	}

	private static boolean containsIntAnd(List<Integer> opcodes) {
		return opcodes.contains(Opcodes.AND_INT)
				|| opcodes.contains(Opcodes.AND_INT_2ADDR)
				|| opcodes.contains(Opcodes.AND_INT_LIT16)
				|| opcodes.contains(Opcodes.AND_INT_LIT8);
	}

	private static List<Integer> methodOpcodes(Dex dex, String methodName) {
		for (ClassDef classDef : dex.classDefs()) {
			if (!FIXTURE_DESCRIPTOR.equals(dex.typeNames().get(classDef.getTypeIndex()))) {
				continue;
			}
			Dex.Section data = dex.open(classDef.getClassDataOffset());
			int staticFields = data.readUleb128();
			int instanceFields = data.readUleb128();
			int directMethods = data.readUleb128();
			int virtualMethods = data.readUleb128();
			skipFields(data, staticFields + instanceFields);
			List<Integer> result = findMethodOpcodes(dex, data, directMethods, methodName);
			if (result != null) {
				return result;
			}
			return findMethodOpcodes(dex, data, virtualMethods, methodName);
		}
		return null;
	}

	private static void skipFields(Dex.Section data, int count) {
		for (int i = 0; i < count; i++) {
			data.readUleb128();
			data.readUleb128();
		}
	}

	private static List<Integer> findMethodOpcodes(Dex dex, Dex.Section data, int count, String methodName) {
		int methodIndex = 0;
		for (int i = 0; i < count; i++) {
			methodIndex += data.readUleb128();
			data.readUleb128();
			int codeOffset = data.readUleb128();
			MethodId method = dex.methodIds().get(methodIndex);
			if (methodName.equals(dex.strings().get(method.getNameIndex()))) {
				return decodeOpcodes(dex, codeOffset);
			}
		}
		return null;
	}

	private static List<Integer> decodeOpcodes(Dex dex, int codeOffset) {
		Dex.Section code = dex.open(codeOffset);
		code.readUnsignedShort(); // registers_size
		code.readUnsignedShort(); // ins_size
		code.readUnsignedShort(); // outs_size
		code.readUnsignedShort(); // tries_size
		code.readInt(); // debug_info_off
		int instructionCount = code.readInt();
		DecodedInstruction[] decoded = DecodedInstruction.decodeAll(code.readShortArray(instructionCount));
		List<Integer> opcodes = new ArrayList<>();
		for (DecodedInstruction instruction : decoded) {
			if (instruction != null) {
				opcodes.add(instruction.getOpcode());
			}
		}
		return opcodes;
	}

	private static void writeFixtureJar(File jar) throws Exception {
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
			out.putNextEntry(new JarEntry(FIXTURE_NAME + ".class"));
			out.write(createFixture());
			out.closeEntry();
		}
	}

	private static byte[] createFixture() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(V1_5, ACC_PUBLIC, FIXTURE_NAME, null, "java/lang/Object", null);
		FieldVisitor field = writer.visitField(ACC_PUBLIC | ACC_STATIC, "value", "S", null, null);
		field.visitEnd();
		writeShortFieldReturnMethod(writer, "fromShortField", "()Z");
		writeShortFieldReturnMethod(writer, "asInt", "()I");
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void writeShortFieldReturnMethod(ClassWriter writer, String name, String descriptor) {
		MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
		method.visitCode();
		method.visitFieldInsn(GETSTATIC, FIXTURE_NAME, "value", "S");
		method.visitInsn(IRETURN);
		method.visitMaxs(1, 0);
		method.visitEnd();
	}
}
