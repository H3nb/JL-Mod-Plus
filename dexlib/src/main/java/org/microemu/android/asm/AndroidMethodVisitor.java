/**
 * MicroEmulator
 * Copyright (C) 2008 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2017-2018 Nikita Shakarun
 * Copyright 2020-2022 Yury Kharchenko
 * <p>
 * It is licensed under the following two licenses as alternatives:
 * 1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 * 2. Apache License (the "AL") Version 2.0
 * <p>
 * You may not use this file except in compliance with at least one of
 * the above two licenses.
 * <p>
 * You may obtain a copy of the LGPL at
 * http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 * <p>
 * You may obtain a copy of the AL at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the LGPL or the AL for the specific language governing permissions and
 * limitations.
 *
 * Modified in JL-Mod Plus to preserve Java ME bytecode return semantics and route guest timing
 * call sites through the parent-owned emulator bridge during DEX conversion.
 *
 * @version $Id$
 */

package org.microemu.android.asm;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.util.ArrayList;

public class AndroidMethodVisitor extends MethodVisitor {
	static boolean USE_PANIC_LOGGING = false;
	private final ArrayList<Label> exceptionHandlers = new ArrayList<>();
	private final boolean returnsBoolean;
	/** Internal name of the guest class containing this method, when available. */
	private final String ownerClassName;

	public AndroidMethodVisitor(MethodVisitor methodVisitor) {
		this(methodVisitor, false, null);
	}

	AndroidMethodVisitor(MethodVisitor methodVisitor, boolean returnsBoolean) {
		this(methodVisitor, returnsBoolean, null);
	}

	AndroidMethodVisitor(MethodVisitor methodVisitor, boolean returnsBoolean, String ownerClassName) {
		super(ASM9, methodVisitor);
		this.returnsBoolean = returnsBoolean;
		this.ownerClassName = ownerClassName;
	}

	@Override
	public void visitLabel(Label label) {
		flushDateAllocation();
		mv.visitLabel(label);
		if (USE_PANIC_LOGGING && exceptionHandlers.contains(label)) {
			mv.visitInsn(DUP);
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
		}
	}

	@Override
	public void visitInsn(int opcode) {
		flushDateAllocation();
		if (opcode == IRETURN && returnsBoolean) {
			// JVMS ireturn narrows boolean results as value & 1. Make that implicit JVM
			// conversion explicit before dx so ART sees an int-compatible return value.
			super.visitInsn(ICONST_1);
			super.visitInsn(IAND);
		}
		super.visitInsn(opcode);
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
		boolean rewriteDateConstructor = owner.equals("java/util/Date")
				&& opcode == INVOKESPECIAL
				&& name.equals("<init>")
				&& desc.equals("()V");
		if (rewriteDateConstructor) {
			mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
					"currentTimeMillis", "()J", false);
			mv.visitMethodInsn(INVOKESPECIAL, "java/util/Date", "<init>", "(J)V", false);
			return;
		}
		flushDateAllocation();
		switch (owner) {
			case "java/lang/Class":
				if (opcode == INVOKESTATIC && name.equals("forName")
						&& desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
					if (ownerClassName == null) {
						// Keep the old ABI for callers that instantiate this visitor directly and for
						// already converted archives. Producer-based conversion supplies the caller
						// token below so child guest class loaders remain visible to reflection.
						mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
								"forName", desc, false);
					} else {
						mv.visitLdcInsn(Type.getObjectType(ownerClassName));
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/GuestTimingBridge",
								"forName",
								"(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Class;",
								false);
					}
					return;
				}
				if (opcode == INVOKEVIRTUAL && name.equals("newInstance")
						&& desc.equals("()Ljava/lang/Object;")) {
					if (ownerClassName == null) {
						// Preserve the one-argument bridge ABI for already converted archives.
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/GuestTimingBridge",
								"newInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
					} else {
						// Class.newInstance() performs access checks relative to its caller. Supplying the
						// guest class token lets the parent bridge retain those checks while virtualizing
						// Date.class.newInstance() through the guest clock.
						mv.visitLdcInsn(Type.getObjectType(ownerClassName));
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/GuestTimingBridge",
								"newInstance",
								"(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/Object;",
								false);
					}
					return;
				}
				if (opcode == INVOKEVIRTUAL && name.equals("getName")
						&& desc.equals("()Ljava/lang/String;")) {
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/GuestTimingBridge",
								"className", "(Ljava/lang/Class;)Ljava/lang/String;", false);
					return;
				}
				if (opcode == INVOKEVIRTUAL && name.equals("toString")
						&& desc.equals("()Ljava/lang/String;")) {
					mv.visitMethodInsn(INVOKESTATIC,
							"javax/microedition/shell/GuestTimingBridge",
							"classToString", "(Ljava/lang/Class;)Ljava/lang/String;", false);
					return;
				}
				if (name.equals("getResourceAsStream")) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/util/ContextHolder",
							name, "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;", itf);
					return;
				}
				break;
			case "java/lang/Thread":
				if (name.equals("yield")) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
							"yieldCompat", "()V", false);
					return;
				}
				if (opcode == INVOKESTATIC && name.equals("sleep")
						&& (desc.equals("(J)V") || desc.equals("(JI)V"))) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
							"sleep", desc, false);
					return;
				}
				break;
			case "java/lang/Object":
				if (opcode == INVOKEVIRTUAL && name.equals("wait")
						&& (desc.equals("(J)V") || desc.equals("(JI)V"))) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
							"waitOnMonitor", "(Ljava/lang/Object;" + desc.substring(1), false);
					return;
				}
				break;
			case "java/util/Date":
				break;
			case "java/util/Calendar":
				if (opcode == INVOKESTATIC && name.equals("getInstance")
						&& (desc.equals("()Ljava/util/Calendar;")
						|| desc.equals("(Ljava/util/TimeZone;)Ljava/util/Calendar;"))) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
							"calendarInstance", desc, false);
					return;
				}
				break;
			case "java/lang/String":
				if (name.equals("<init>") && desc.startsWith("([B") && !desc.endsWith("Ljava/lang/String;)V")) {
					injectGetPropertyEncoding();
					String descriptor = new StringBuilder(desc.length() + 18)
							.append(desc)
							.insert(desc.length() - 2, "Ljava/lang/String;")
							.toString();
					mv.visitMethodInsn(opcode, owner, name, descriptor, itf);
					return;
				} else if (name.equals("getBytes"))
					if (desc.equals("()[B")) {
						injectGetPropertyEncoding();
						mv.visitMethodInsn(opcode, owner, name, "(Ljava/lang/String;)[B", itf);
						return;
					}
				break;
			case "java/io/InputStreamReader":
				if (name.equals("<init>") && desc.equals("(Ljava/io/InputStream;)V")) {
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/io/InputStream;Ljava/lang/String;)V", itf);
					return;
				}
				break;
			case "java/io/OutputStreamWriter":
				if (name.equals("<init>") && desc.equals("(Ljava/io/OutputStream;)V")) {
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/io/OutputStream;Ljava/lang/String;)V", itf);
					return;
				}
				break;
			case "java/io/ByteArrayOutputStream":
				if (name.equals("toString") && desc.equals("()Ljava/lang/String;")) {
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/lang/String;)Ljava/lang/String;", itf);
					return;
				}
				break;
			case "java/io/PrintStream":
				if (name.equals("<init>") && desc.equals("(Ljava/io/OutputStream;)V")) {
					mv.visitInsn(ICONST_0);
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/io/OutputStream;ZLjava/lang/String;)V", itf);
					return;
				}
				break;
			case "com/siemens/mp/io/Connection":
				if (opcode == INVOKESTATIC && name.equals("setListener")) {
					name = "setListenerCompat";
				}
				break;
			case "java/lang/System":
				if (opcode == INVOKESTATIC && name.equals("currentTimeMillis") && desc.equals("()J")) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
							"currentTimeMillis", "()J", false);
					return;
				}
				if (opcode == INVOKESTATIC && name.equals("nanoTime") && desc.equals("()J")) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/GuestTimingBridge",
							"nanoTime", "()J", false);
					return;
				}
				if (opcode == INVOKESTATIC && name.equals("getProperty")) {
					mv.visitMethodInsn(opcode, "javax/microedition/shell/MidletSystem", name, desc, itf);
					return;
				}
				break;
			case "java/util/Timer":
				owner = "javax/microedition/shell/custom/Timer";
				break;
			case "java/util/TimerTask":
				owner = "javax/microedition/shell/custom/TimerTask";
				break;
		}
		owner = TimingTypeMapper.mapInternalName(owner);
		desc = TimingTypeMapper.mapDescriptor(desc);
		mv.visitMethodInsn(opcode, owner, name, desc, itf);
	}

	@Override
	public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
		return TimingTypeMapper.mapAnnotationVisitor(
				super.visitAnnotation(TimingTypeMapper.mapDescriptor(descriptor), visible));
	}

	@Override
	public AnnotationVisitor visitTypeAnnotation(
			int typeRef, TypePath typePath, String descriptor, boolean visible) {
		return TimingTypeMapper.mapAnnotationVisitor(
				super.visitTypeAnnotation(typeRef, typePath,
						TimingTypeMapper.mapDescriptor(descriptor), visible));
	}

	@Override
	public AnnotationVisitor visitAnnotationDefault() {
		return TimingTypeMapper.mapAnnotationVisitor(super.visitAnnotationDefault());
	}

	@Override
	public AnnotationVisitor visitParameterAnnotation(
			int parameter, String descriptor, boolean visible) {
		return TimingTypeMapper.mapAnnotationVisitor(
				super.visitParameterAnnotation(
						parameter, TimingTypeMapper.mapDescriptor(descriptor), visible));
	}

	@Override
	public AnnotationVisitor visitInsnAnnotation(
			int typeRef, TypePath typePath, String descriptor, boolean visible) {
		return TimingTypeMapper.mapAnnotationVisitor(
				super.visitInsnAnnotation(typeRef, typePath,
						TimingTypeMapper.mapDescriptor(descriptor), visible));
	}

	@Override
	public AnnotationVisitor visitTryCatchAnnotation(
			int typeRef, TypePath typePath, String descriptor, boolean visible) {
		return TimingTypeMapper.mapAnnotationVisitor(
				super.visitTryCatchAnnotation(typeRef, typePath,
						TimingTypeMapper.mapDescriptor(descriptor), visible));
	}

	@Override
	public AnnotationVisitor visitLocalVariableAnnotation(
			int typeRef,
			TypePath typePath,
			Label[] start,
			Label[] end,
			int[] index,
			String descriptor,
			boolean visible) {
		return TimingTypeMapper.mapAnnotationVisitor(
				super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index,
						TimingTypeMapper.mapDescriptor(descriptor), visible));
	}

	private void injectGetPropertyEncoding() {
		mv.visitLdcInsn("microedition.encoding");
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperty",
				"(Ljava/lang/String;)Ljava/lang/String;", false);
	}

	@Override
	public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
		if (USE_PANIC_LOGGING && type != null) {
			exceptionHandlers.add(handler);
		}
		mv.visitTryCatchBlock(start, end, handler, TimingTypeMapper.mapInternalName(type));
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
		flushDateAllocation();
		type = TimingTypeMapper.mapInternalName(type);
		super.visitTypeInsn(opcode, type);
	}

	private void flushDateAllocation() {
		// Kept as a single call-site hook for the legacy visitor flow. Date allocation is now
		// preserved verbatim and every Date.<init>()V invocation is rewritten directly, so no
		// NEW/DUP state machine may swallow legal bytecode patterns.
	}

	@Override
	public void visitIntInsn(int opcode, int operand) {
		flushDateAllocation();
		super.visitIntInsn(opcode, operand);
	}

	@Override
	public void visitVarInsn(int opcode, int var) {
		flushDateAllocation();
		super.visitVarInsn(opcode, var);
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
		flushDateAllocation();
		descriptor = TimingTypeMapper.mapDescriptor(descriptor);
		owner = TimingTypeMapper.mapInternalName(owner);
		super.visitFieldInsn(opcode, owner, name, descriptor);
	}

	@Override
	public void visitJumpInsn(int opcode, Label label) {
		flushDateAllocation();
		super.visitJumpInsn(opcode, label);
	}

	@Override
	public void visitLdcInsn(Object value) {
		flushDateAllocation();
		super.visitLdcInsn(TimingTypeMapper.mapValue(value));
	}

	@Override
	public void visitIincInsn(int var, int increment) {
		flushDateAllocation();
		super.visitIincInsn(var, increment);
	}

	@Override
	public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
		flushDateAllocation();
		super.visitTableSwitchInsn(min, max, dflt, labels);
	}

	@Override
	public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
		flushDateAllocation();
		super.visitLookupSwitchInsn(dflt, keys, labels);
	}

	@Override
	public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
		flushDateAllocation();
		descriptor = TimingTypeMapper.mapDescriptor(descriptor);
		super.visitMultiANewArrayInsn(descriptor, numDimensions);
	}

	@Override
	public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
		flushDateAllocation();
		Object[] mappedArguments = new Object[bsmArgs.length];
		for (int i = 0; i < bsmArgs.length; i++) {
			mappedArguments[i] = TimingTypeMapper.mapValue(bsmArgs[i]);
		}
		super.visitInvokeDynamicInsn(
				name, TimingTypeMapper.mapDescriptor(descriptor),
				TimingTypeMapper.mapHandle(bsm), mappedArguments);
	}

	@Override
	public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
		flushDateAllocation();
		Object[] mappedLocal = mapFrameValues(local);
		Object[] mappedStack = mapFrameValues(stack);
		super.visitFrame(type, numLocal, mappedLocal, numStack, mappedStack);
	}

	@Override
	public void visitLocalVariable(
			String name, String descriptor, String signature, Label start, Label end, int index) {
		flushDateAllocation();
		super.visitLocalVariable(
				name,
				TimingTypeMapper.mapDescriptor(descriptor),
				TimingTypeMapper.mapSignature(signature),
				start,
				end,
				index);
	}

	private static Object[] mapFrameValues(Object[] values) {
		if (values == null) {
			return null;
		}
		Object[] mapped = new Object[values.length];
		for (int i = 0; i < values.length; i++) {
			mapped[i] = TimingTypeMapper.mapFrameValue(values[i]);
		}
		return mapped;
	}

	@Override
	public void visitLineNumber(int line, Label start) {
		flushDateAllocation();
		super.visitLineNumber(line, start);
	}

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
		flushDateAllocation();
		super.visitMaxs(maxStack, maxLocals);
	}

	@Override
	public void visitEnd() {
		flushDateAllocation();
		super.visitEnd();
	}
}
