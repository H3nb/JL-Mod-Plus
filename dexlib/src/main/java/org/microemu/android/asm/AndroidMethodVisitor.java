/**
 * MicroEmulator
 * Copyright (C) 2008 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2017-2018 Nikita Shakarun
 * Copyright 2020-2022 Yury Kharchenko
 * Copyright 2026 H3NB
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
 * @version $Id$
 */

package org.microemu.android.asm;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;

public class AndroidMethodVisitor extends MethodVisitor {
	static boolean USE_PANIC_LOGGING = false;
	private final boolean speedhackEnabled;
	private final ArrayList<Label> exceptionHandlers = new ArrayList<>();

	public AndroidMethodVisitor(MethodVisitor methodVisitor) {
		this(methodVisitor, true);
	}

	public AndroidMethodVisitor(MethodVisitor methodVisitor, boolean speedhackEnabled) {
		super(ASM9, methodVisitor);
		this.speedhackEnabled = speedhackEnabled;
	}

	@Override
	public void visitLabel(Label label) {
		mv.visitLabel(label);
		if (USE_PANIC_LOGGING && exceptionHandlers.contains(label)) {
			mv.visitInsn(DUP);
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
		}
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
			switch (owner) {
			case "java/lang/Class":
				if (name.equals("getResourceAsStream")) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/util/ContextHolder",
							name, "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;", itf);
					return;
				}
				break;
			case "java/lang/Thread":
				/*
				 * Legacy JL-Mod compatibility rule.  A number of J2ME game loops use
				 * Thread.yield() as their frame-pacing point.  Android is allowed to
				 * treat yield as a scheduler hint, so it may return immediately and
				 * starve the repaint/event path.  Preserve the original 1 ms pause in
				 * every conversion mode; only the implementation of that pause follows
				 * virtual time when speedhack is enabled.
				 */
				if (opcode == INVOKESTATIC && name.equals("yield") && desc.equals("()V")) {
					mv.visitLdcInsn(1L);
					if (speedhackEnabled) {
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/time/EmulationTime",
								"sleep", "(J)V", false);
					} else {
						mv.visitMethodInsn(INVOKESTATIC, "java/lang/Thread",
								"sleep", "(J)V", false);
					}
					return;
				}
				if (speedhackEnabled && opcode == INVOKESTATIC && name.equals("sleep")) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/time/EmulationTime",
							name, desc, false);
					return;
				} else if (speedhackEnabled && opcode == INVOKEVIRTUAL && name.equals("join")
						&& (desc.equals("(J)V") || desc.equals("(JI)V"))) {
					String joinDescriptor = "(Ljava/lang/Thread;" + desc.substring(1);
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/time/EmulationTime",
							name, joinDescriptor, false);
					return;
				}
				break;
			case "java/lang/Object":
				if (speedhackEnabled && opcode == INVOKEVIRTUAL) {
					if (name.equals("wait")
							&& (desc.equals("()V") || desc.equals("(J)V")
							|| desc.equals("(JI)V"))) {
						String waitDescriptor = "(Ljava/lang/Object;" + desc.substring(1);
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/time/EmulationMonitor",
								"waitOn", waitDescriptor, false);
						return;
					}
					if (name.equals("notify") && desc.equals("()V")) {
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/time/EmulationMonitor",
								"notifyOne", "(Ljava/lang/Object;)V", false);
						return;
					}
					if (name.equals("notifyAll") && desc.equals("()V")) {
						mv.visitMethodInsn(INVOKESTATIC,
								"javax/microedition/shell/time/EmulationMonitor",
								"notifyAllOn", "(Ljava/lang/Object;)V", false);
						return;
					}
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
				if (speedhackEnabled && opcode == INVOKESTATIC &&
						(name.equals("currentTimeMillis") || name.equals("nanoTime"))) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/time/EmulationTime",
							name, desc, false);
					return;
				} else if (opcode == INVOKESTATIC && name.equals("getProperty")) {
					mv.visitMethodInsn(opcode, "javax/microedition/shell/MidletSystem", name, desc, itf);
					return;
				}
				break;
			case "java/util/Date":
				if (speedhackEnabled && opcode == INVOKESPECIAL && name.equals("<init>") && desc.equals("()V")) {
					/*
					 * A no-argument Date constructor reads the host wall clock inside
					 * java.util.Date.  Keep the allocation and constructor invocation
					 * intact, but supply the emulation wall time explicitly.  This is
					 * stack-safe for both ordinary Date instances and Date subclasses:
					 * NEW/DUP leave the uninitialized object on the stack while the
					 * long argument is appended immediately before invokespecial.
					 */
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/time/EmulationTime",
							"currentTimeMillis", "()J", false);
					mv.visitMethodInsn(INVOKESPECIAL, owner, name, "(J)V", itf);
					return;
				}
				break;
			case "java/util/Calendar":
				if (speedhackEnabled && opcode == INVOKESTATIC && name.equals("getInstance")
						&& isCalendarFactoryDescriptor(desc)) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/shell/time/EmulationDateTime",
							name, desc, false);
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
		desc = desc.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		mv.visitMethodInsn(opcode, owner, name, desc, itf);
	}

	private void injectGetPropertyEncoding() {
		mv.visitLdcInsn("microedition.encoding");
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperty",
				"(Ljava/lang/String;)Ljava/lang/String;", false);
	}

	private static boolean isCalendarFactoryDescriptor(String desc) {
		return desc.equals("()Ljava/util/Calendar;")
				|| desc.equals("(Ljava/util/TimeZone;)Ljava/util/Calendar;")
				|| desc.equals("(Ljava/util/Locale;)Ljava/util/Calendar;")
				|| desc.equals("(Ljava/util/TimeZone;Ljava/util/Locale;)Ljava/util/Calendar;");
	}

	@Override
	public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
		if (USE_PANIC_LOGGING && type != null) {
			exceptionHandlers.add(handler);
		}
		mv.visitTryCatchBlock(start, end, handler, type);
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
		type = type.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		super.visitTypeInsn(opcode, type);
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
		descriptor = descriptor.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		owner = owner.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		super.visitFieldInsn(opcode, owner, name, descriptor);
	}

	@Override
	public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
		descriptor = descriptor.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		super.visitMultiANewArrayInsn(descriptor, numDimensions);
	}
}
