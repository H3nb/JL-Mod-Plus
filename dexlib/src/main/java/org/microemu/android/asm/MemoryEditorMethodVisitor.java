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

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/**
 * Legacy/experimental bytecode visitor for Memory Editor observation points.
 *
 * <p>The default production conversion does not select this visitor. It is
 * retained for focused tests and explicit diagnostic reinstall modes while the
 * broad per-access hook surface is investigated for game-specific lag, stuck
 * screens, and instability. Keep the mode guard and this safety note in sync
 * when changing the conversion pipeline.</p>
 *
 * <p>When selected, it adds observation points to primitive fields and
 * primitive array elements. It deliberately does not touch reference values or
 * platform classes: the runtime editor works with logical values exposed by
 * the transformed MIDlet, not arbitrary Android process memory.</p>
 */
final class MemoryEditorMethodVisitor extends AdviceAdapter {
	private static final Type BRIDGE = Type.getObjectType(
			"javax/microedition/shell/memory/MemoryEditorBridge");
	private static final String READ_INT =
			"(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;JII)I";
	private static final String WRITE_INT = READ_INT;
	private static final String READ_LONG =
			"(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;JIJ)J";
	private static final String WRITE_LONG = READ_LONG;
	private static final String READ_FLOAT =
			"(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;JIF)F";
	private static final String WRITE_FLOAT = READ_FLOAT;
	private static final String READ_DOUBLE =
			"(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;JID)D";
	private static final String WRITE_DOUBLE = READ_DOUBLE;
	private static final String ARRAY_MEMBER = "#array";
	private final String className;
	private final String methodName;
	private final String methodDesc;
	private long operation;
	private int objectLocal = -1;
	private int indexLocal = -1;
	private int intLocal = -1;
	private int longLocal = -1;
	private int floatLocal = -1;
	private int doubleLocal = -1;

	MemoryEditorMethodVisitor(int access, String name, String descriptor,
			org.objectweb.asm.MethodVisitor methodVisitor, String className) {
		super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
		this.className = className;
		this.methodName = name;
		this.methodDesc = descriptor;
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
		if (!isSafeOwner(owner) || primitiveKind(descriptor) == 0) {
			super.visitFieldInsn(opcode, owner, name, descriptor);
			return;
		}
		int kind = primitiveKind(descriptor);
		long site = site(owner + "." + name + descriptor);
		if (opcode == GETSTATIC || opcode == GETFIELD) {
			Label inactive = new Label();
			Label done = new Label();
			emitActiveGate(kind, inactive);
			int target = -1;
			if (opcode == GETFIELD) {
				// Retain one copy for candidate identity while the original
				// GETFIELD still performs its own null check first.
				dup();
			}
			super.visitFieldInsn(opcode, owner, name, descriptor);
			int value = valueLocal(kind);
			storeLocal(value);
			if (opcode == GETFIELD) {
				target = objectLocal();
				storeLocal(target);
			}
			loadTarget(target);
			push(Type.getObjectType(owner));
			push(name + descriptor);
			push(site);
			push(opcode == GETSTATIC ? -2 : -1);
			loadLocal(value);
			invokeBridge(true, kind);
			goTo(done);
			mark(inactive);
			super.visitFieldInsn(opcode, owner, name, descriptor);
			mark(done);
			return;
		}
		if (opcode == PUTSTATIC || opcode == PUTFIELD) {
			Label inactive = new Label();
			Label done = new Label();
			emitActiveGate(kind, inactive);
			int value = valueLocal(kind);
			storeLocal(value);
			int target = -1;
			if (opcode == PUTFIELD) {
				// Leave the original target on the stack for PUTFIELD and use
				// only its duplicate as the hook identity.
				dup();
				target = objectLocal();
				storeLocal(target);
			}
			loadTarget(target);
			push(Type.getObjectType(owner));
			push(name + descriptor);
			push(site);
			push(opcode == PUTSTATIC ? -2 : -1);
			loadLocal(value);
			invokeBridge(false, kind);
			super.visitFieldInsn(opcode, owner, name, descriptor);
			goTo(done);
			mark(inactive);
			super.visitFieldInsn(opcode, owner, name, descriptor);
			mark(done);
			return;
		}
		super.visitFieldInsn(opcode, owner, name, descriptor);
	}

	@Override
	public void visitInsn(int opcode) {
		int kind = arrayKind(opcode);
		if (kind == 0) {
			super.visitInsn(opcode);
			return;
		}
		boolean read = opcode == IALOAD || opcode == LALOAD || opcode == FALOAD
				|| opcode == DALOAD || opcode == BALOAD || opcode == CALOAD || opcode == SALOAD;
		Label inactive = new Label();
		Label done = new Label();
		emitActiveGate(kind, inactive);
		if (read) {
			// Keep a copy solely for candidate identity. The original array load
			// still executes first, preserving its null and bounds exceptions.
			dup2();
			super.visitInsn(opcode);
			int value = valueLocal(kind);
			storeLocal(value);
			int index = indexLocal();
			storeLocal(index);
			int array = objectLocal();
			storeLocal(array);
			loadLocal(array);
			visitInsn(ACONST_NULL);
			push(ARRAY_MEMBER);
			push(site("array" + opcode));
			loadLocal(index);
			loadLocal(value);
			invokeBridge(true, kind);
		} else {
			// Store the value first: an array store arrives as array, index,
			// value. Preserve array and index on the operand stack so BASTORE
			// remains valid for both byte[] and boolean[].
			int value = valueLocal(kind);
			storeLocal(value);
			dup2();
			int index = indexLocal();
			storeLocal(index);
			int array = objectLocal();
			storeLocal(array);
			loadLocal(array);
			visitInsn(ACONST_NULL);
			push(ARRAY_MEMBER);
			push(site("array" + opcode));
			loadLocal(index);
			loadLocal(value);
			invokeBridge(false, kind);
			super.visitInsn(opcode);
		}
		goTo(done);
		mark(inactive);
		super.visitInsn(opcode);
		mark(done);
	}

	private void emitActiveGate(int kind, Label inactive) {
		getStatic(BRIDGE, "ACTIVE_KINDS", Type.INT_TYPE);
		push(1 << (kind - 1));
		math(AND, Type.INT_TYPE);
		ifZCmp(EQ, inactive);
	}

	private void invokeBridge(boolean read, int kind) {
		String suffix;
		String descriptor;
		switch (kind) {
			case 1:
				suffix = "Int";
				descriptor = read ? READ_INT : WRITE_INT;
				break;
			case 2:
				suffix = "Long";
				descriptor = read ? READ_LONG : WRITE_LONG;
				break;
			case 3:
				suffix = "Float";
				descriptor = read ? READ_FLOAT : WRITE_FLOAT;
				break;
			case 4:
				suffix = "Double";
				descriptor = read ? READ_DOUBLE : WRITE_DOUBLE;
				break;
			default:
				throw new IllegalArgumentException("Unknown primitive kind: " + kind);
		}
		String name = (read ? "onRead" : "onWrite") + suffix;
		invokeStatic(BRIDGE, new Method(name, descriptor));
	}

	private void loadTarget(int target) {
		if (target < 0) {
			visitInsn(ACONST_NULL);
		} else {
			loadLocal(target);
		}
	}

	private int objectLocal() {
		if (objectLocal < 0) {
			objectLocal = newLocal(Type.getType(Object.class));
		}
		return objectLocal;
	}

	private int indexLocal() {
		if (indexLocal < 0) {
			indexLocal = newLocal(Type.INT_TYPE);
		}
		return indexLocal;
	}

	private int valueLocal(int kind) {
		switch (kind) {
			case 1:
				if (intLocal < 0) intLocal = newLocal(Type.INT_TYPE);
				return intLocal;
			case 2:
				if (longLocal < 0) longLocal = newLocal(Type.LONG_TYPE);
				return longLocal;
			case 3:
				if (floatLocal < 0) floatLocal = newLocal(Type.FLOAT_TYPE);
				return floatLocal;
			case 4:
				if (doubleLocal < 0) doubleLocal = newLocal(Type.DOUBLE_TYPE);
				return doubleLocal;
			default:
				throw new IllegalArgumentException("Unknown primitive kind: " + kind);
		}
	}

	private long site(String member) {
		long hash = 0xcbf29ce484222325L;
		String value = className + '#' + methodName + methodDesc + '#' + member + '#' + operation++;
		for (int i = 0; i < value.length(); i++) {
			hash ^= value.charAt(i);
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	private static boolean isSafeOwner(String owner) {
		return !(owner.startsWith("java/") || owner.startsWith("javax/")
				|| owner.startsWith("android/") || owner.startsWith("androidx/")
				|| owner.startsWith("org/microemu/") || owner.startsWith("ru/woesss/")
				|| owner.startsWith("io/github/h3nb/jlmodplus/")
				|| owner.equals("kotlin/Metadata"));
	}

	private static int primitiveKind(String descriptor) {
		if (descriptor.length() != 1) return 0;
		switch (descriptor.charAt(0)) {
			case 'Z':
			case 'B':
			case 'C':
			case 'S':
			case 'I': return 1;
			case 'J': return 2;
			case 'F': return 3;
			case 'D': return 4;
			default: return 0;
		}
	}

	private static int arrayKind(int opcode) {
		switch (opcode) {
			case BALOAD: case BASTORE: case IALOAD: case IASTORE: case CALOAD: case CASTORE:
			case SALOAD: case SASTORE: return 1;
			case LALOAD: case LASTORE: return 2;
			case FALOAD: case FASTORE: return 3;
			case DALOAD: case DASTORE: return 4;
			default: return 0;
		}
	}

	private static Type stackType(int kind) {
		switch (kind) {
			case 1: return Type.INT_TYPE;
			case 2: return Type.LONG_TYPE;
			case 3: return Type.FLOAT_TYPE;
			case 4: return Type.DOUBLE_TYPE;
			default: throw new IllegalArgumentException("Unknown primitive kind: " + kind);
		}
	}

}
