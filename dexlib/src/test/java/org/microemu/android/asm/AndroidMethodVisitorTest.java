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

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public class AndroidMethodVisitorTest {
	@Test
	public void guestClockAndExplicitSleepUseParentOwnedBridge() {
		byte[] source = createClass();
		byte[] transformed = transform(source);
		List<String> calls = methodCalls(transformed, "timed");

		assertTrue(calls.contains("INVOKESTATIC javax/microedition/shell/GuestTimingBridge.currentTimeMillis()J"));
		assertTrue(calls.contains("INVOKESTATIC javax/microedition/shell/GuestTimingBridge.nanoTime()J"));
		assertTrue(calls.contains("INVOKESTATIC javax/microedition/shell/GuestTimingBridge.sleep(J)V"));
		assertTrue(calls.contains("INVOKESTATIC javax/microedition/shell/GuestTimingBridge.sleep(JI)V"));
		assertEquals(4, calls.size());
	}

	@Test
	public void yieldRewriteUsesGuestTimingBridge() {
		byte[] source = createYieldClass();
		byte[] transformed = transform(source);
		List<String> calls = methodCalls(transformed, "yielded");

		assertTrue(calls.contains("INVOKESTATIC javax/microedition/shell/GuestTimingBridge.sleep(J)V"));
		assertEquals(1, calls.size());
	}

	private static byte[] createClass() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Timing", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "timed", "()V", null,
				new String[] {"java/lang/InterruptedException"});
		method.visitCode();
		method.visitMethodInsn(
				Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
		method.visitInsn(Opcodes.POP2);
		method.visitMethodInsn(
				Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
		method.visitInsn(Opcodes.POP2);
		method.visitLdcInsn(10L);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
		method.visitLdcInsn(1L);
		method.visitInsn(Opcodes.ICONST_1);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(JI)V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(2, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] createYieldClass() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Yield", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "yielded", "()V", null, null);
		method.visitCode();
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "yield", "()V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(2, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static List<String> methodCalls(byte[] classData, String methodName) {
		List<String> calls = new ArrayList<>();
		new ClassReader(classData).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!methodName.equals(name)) {
					return next;
				}
				return new MethodVisitor(Opcodes.ASM9, next) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
							boolean isInterface) {
						calls.add(opcodeName(opcode) + " " + owner + "." + name + descriptor);
						super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
					}
				};
			}
		}, 0);
		return calls;
	}

	private static byte[] transform(byte[] source) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		new ClassReader(source).accept(new AndroidClassVisitor(writer), 0);
		return writer.toByteArray();
	}

	private static String opcodeName(int opcode) {
		return opcode == Opcodes.INVOKESTATIC ? "INVOKESTATIC" : Integer.toString(opcode);
	}
}
