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

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class AndroidProducerManagedTailTest {
	private static final String BRIDGE = "javax/microedition/shell/MemoryDiscoveryBridge";

	@Test
	public void everyNormalStaticInitializerReturnPublishesTheClassTail() {
		byte[] input = classWithBranchingInitializer();
		byte[] output = transform(input, "sample/Managed");

		assertEquals(2, countTailCalls(output, "<clinit>"));
	}

	@Test
	public void classWithManagedStaticFieldGetsSyntheticInitializer() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Synthetic", null,
				"java/lang/Object", null);
		writer.visitField(Opcodes.ACC_STATIC, "value", "Ljava/lang/Object;", null, null).visitEnd();
		writer.visitEnd();

		byte[] output = transform(writer.toByteArray(), "sample/Synthetic");
		assertEquals(1, countTailCalls(output, "<clinit>"));
	}

	private static byte[] transform(byte[] input, String owner) {
		ClassReader reader = new ClassReader(input);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		reader.accept(new AndroidClassVisitor(writer, owner), 0);
		return writer.toByteArray();
	}

	private static int countTailCalls(byte[] classData, String methodName) {
		final int[] count = {0};
		new ClassReader(classData).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                String signature, String[] exceptions) {
				MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!methodName.equals(name)) return delegate;
				return new MethodVisitor(Opcodes.ASM9, delegate) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String name,
					                           String descriptor, boolean isInterface) {
						if (opcode == Opcodes.INVOKESTATIC && BRIDGE.equals(owner)
								&& "tailSeen".equals(name)) count[0]++;
						super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
					}
				};
			}
		}, 0);
		return count[0];
	}

	private static byte[] classWithBranchingInitializer() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Managed", null,
				"java/lang/Object", null);
		writer.visitField(Opcodes.ACC_STATIC, "value", "I", null, null).visitEnd();
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		method.visitCode();
		Label alternate = new Label();
		method.visitInsn(Opcodes.ICONST_0);
		method.visitJumpInsn(Opcodes.IFEQ, alternate);
		method.visitInsn(Opcodes.ICONST_1);
		method.visitFieldInsn(Opcodes.PUTSTATIC, "sample/Managed", "value", "I");
		method.visitInsn(Opcodes.RETURN);
		method.visitLabel(alternate);
		method.visitInsn(Opcodes.ICONST_2);
		method.visitFieldInsn(Opcodes.PUTSTATIC, "sample/Managed", "value", "I");
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(1, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
