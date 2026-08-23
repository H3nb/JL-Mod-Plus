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

	@Test
	public void finiteObjectWaitUsesParentOwnedMonitorBridge() {
		byte[] source = createWaitClass();
		byte[] transformed = transform(source);
		List<String> calls = methodCalls(transformed, "waited");

		assertTrue(calls.contains(
				"INVOKESTATIC javax/microedition/shell/GuestTimingBridge.waitOnMonitor(Ljava/lang/Object;J)V"));
		assertTrue(calls.contains(
				"INVOKESTATIC javax/microedition/shell/GuestTimingBridge.waitOnMonitor(Ljava/lang/Object;JI)V"));
		assertEquals(2, calls.size());
	}

	@Test
	public void currentDateAndCalendarFactoriesUseParentOwnedGuestTime() {
		byte[] source = createDateAndCalendarClass();
		byte[] transformed = transform(source);
		List<String> calls = methodCalls(transformed, "dates");

		assertTrue(calls.contains(
				"INVOKESTATIC javax/microedition/shell/GuestTimingBridge.newDate()Ljava/util/Date;"));
		assertTrue(calls.contains(
				"INVOKESTATIC javax/microedition/shell/GuestTimingBridge.calendarInstance()Ljava/util/Calendar;"));
		assertTrue(calls.contains(
				"INVOKESTATIC javax/microedition/shell/GuestTimingBridge.calendarInstance(Ljava/util/TimeZone;)Ljava/util/Calendar;"));
		assertEquals(4, calls.size());
		assertEquals(0, typeInstructions(transformed, "dates", "java/util/Date").size());

		byte[] subclass = transform(createDateSubclassClass());
		List<String> constructorCalls = methodCalls(subclass, "<init>");
		assertTrue(constructorCalls.contains(
				"INVOKESPECIAL java/util/Date.<init>()V"));
		assertEquals(1, constructorCalls.size());
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

	private static byte[] createWaitClass() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Wait", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "waited", "(Ljava/lang/Object;)V", null,
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
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] createDateAndCalendarClass() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/DateAndCalendar", null,
				"java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dates", "(Ljava/util/TimeZone;)Ljava/util/Date;",
				null, null);
		method.visitCode();
		method.visitTypeInsn(Opcodes.NEW, "java/util/Date");
		method.visitInsn(Opcodes.DUP);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
		method.visitInsn(Opcodes.POP);
		method.visitMethodInsn(
				Opcodes.INVOKESTATIC, "java/util/Calendar", "getInstance",
				"()Ljava/util/Calendar;", false);
		method.visitInsn(Opcodes.POP);
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(
				Opcodes.INVOKESTATIC, "java/util/Calendar", "getInstance",
				"(Ljava/util/TimeZone;)Ljava/util/Calendar;", false);
		method.visitInsn(Opcodes.POP);
		method.visitTypeInsn(Opcodes.NEW, "java/util/Date");
		method.visitInsn(Opcodes.DUP);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(2, 1);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] createDateSubclassClass() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/DateSubclass", null,
				"java/util/Date", null);
		MethodVisitor method = writer.visitMethod(
				Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(1, 1);
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

	private static List<String> typeInstructions(byte[] classData, String methodName, String typeName) {
		List<String> types = new ArrayList<>();
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
					public void visitTypeInsn(int opcode, String type) {
						if (typeName.equals(type)) {
							types.add(type);
						}
						super.visitTypeInsn(opcode, type);
					}
				};
			}
		}, 0);
		return types;
	}

	private static byte[] transform(byte[] source) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		new ClassReader(source).accept(new AndroidClassVisitor(writer), 0);
		return writer.toByteArray();
	}

	private static String opcodeName(int opcode) {
		if (opcode == Opcodes.INVOKESTATIC) {
			return "INVOKESTATIC";
		}
		if (opcode == Opcodes.INVOKESPECIAL) {
			return "INVOKESPECIAL";
		}
		return Integer.toString(opcode);
	}
}
