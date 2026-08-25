/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Maps every JVM type-bearing representation used by the guest Timer transform. */
final class TimingTypeMapper {
	static final String HOST_TIMER = "java/util/Timer";
	static final String GUEST_TIMER = "javax/microedition/shell/custom/Timer";
	static final String HOST_TIMER_TASK = "java/util/TimerTask";
	static final String GUEST_TIMER_TASK = "javax/microedition/shell/custom/TimerTask";

	private TimingTypeMapper() {
	}

	static String mapInternalName(String name) {
		if (name == null) {
			return null;
		}
		if (name.startsWith("[")) {
			return mapDescriptor(name);
		}
		if (HOST_TIMER.equals(name)) {
			return GUEST_TIMER;
		}
		if (HOST_TIMER_TASK.equals(name)) {
			return GUEST_TIMER_TASK;
		}
		return name;
	}

	static String mapDescriptor(String descriptor) {
		if (descriptor == null) {
			return null;
		}
		return mapTypeTokens(descriptor);
	}

	/** Generic signatures use the same internal names inside L...; type tokens. */
	static String mapSignature(String signature) {
		return signature == null ? null : mapTypeTokens(signature);
	}

	/**
	 * Maps complete object-type tokens only. A raw string replacement would also rewrite legal
	 * class names such as java/util/TimerTaskExtension, producing unverifiable bytecode.
	 */
	private static String mapTypeTokens(String value) {
		StringBuilder mapped = null;
		int copyFrom = 0;
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) != 'L') {
				continue;
			}
			int nameStart = i + 1;
			int nameEnd = nameStart;
			while (nameEnd < value.length()) {
				char character = value.charAt(nameEnd);
				if (character == ';' || character == '<' || character == '.') {
					break;
				}
				nameEnd++;
			}
			if (nameEnd == nameStart) {
				continue;
			}
			String mappedName = mapInternalName(value.substring(nameStart, nameEnd));
			if (!mappedName.equals(value.substring(nameStart, nameEnd))) {
				if (mapped == null) {
					mapped = new StringBuilder(value.length() + mappedName.length());
				}
				mapped.append(value, copyFrom, nameStart);
				mapped.append(mappedName);
				copyFrom = nameEnd;
			}
			i = nameEnd - 1;
		}
		if (mapped == null) {
			return value;
		}
		mapped.append(value, copyFrom, value.length());
		return mapped.toString();
	}

	static AnnotationVisitor mapAnnotationVisitor(AnnotationVisitor visitor) {
		if (visitor == null) {
			return null;
		}
		return new AnnotationVisitor(Opcodes.ASM9, visitor) {
			@Override
			public void visit(String name, Object value) {
				super.visit(name, mapValue(value));
			}

			@Override
			public void visitEnum(String name, String descriptor, String value) {
				super.visitEnum(name, mapDescriptor(descriptor), value);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String name, String descriptor) {
				return mapAnnotationVisitor(
						super.visitAnnotation(name, mapDescriptor(descriptor)));
			}

			@Override
			public AnnotationVisitor visitArray(String name) {
				return mapAnnotationVisitor(super.visitArray(name));
			}
		};
	}

	static Object mapFrameValue(Object value) {
		if (!(value instanceof String)) {
			return value;
		}
		String stringValue = (String) value;
		return stringValue.startsWith("[") || stringValue.startsWith("L")
				? mapDescriptor(stringValue) : mapInternalName(stringValue);
	}

	static Object mapValue(Object value) {
		if (value instanceof Type) {
			return mapType((Type) value);
		}
		if (value instanceof Handle) {
			return mapHandle((Handle) value);
		}
		if (value instanceof ConstantDynamic) {
			ConstantDynamic dynamic = (ConstantDynamic) value;
			Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
			for (int i = 0; i < arguments.length; i++) {
				arguments[i] = mapValue(dynamic.getBootstrapMethodArgument(i));
			}
			return new ConstantDynamic(
					dynamic.getName(),
					mapDescriptor(dynamic.getDescriptor()),
					mapHandle(dynamic.getBootstrapMethod()),
					arguments);
		}
		return value;
	}

	static Type mapType(Type type) {
		switch (type.getSort()) {
			case Type.OBJECT:
				return Type.getObjectType(mapInternalName(type.getInternalName()));
			case Type.ARRAY:
			case Type.METHOD:
				return Type.getType(mapDescriptor(type.getDescriptor()));
			default:
				return type;
		}
	}

	static Handle mapHandle(Handle handle) {
		return new Handle(
				handle.getTag(),
				mapInternalName(handle.getOwner()),
				handle.getName(),
				mapDescriptor(handle.getDesc()),
				handle.isInterface());
	}
}
