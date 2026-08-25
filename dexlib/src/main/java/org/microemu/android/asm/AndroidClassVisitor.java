/**
 * MicroEmulator
 * Copyright (C) 2008 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2017-2018 Nikita Shakarun
 * Copyright (C) 2022 Yury Kharchenko
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
 * Modified in JL-Mod Plus to preserve Java ME bytecode return semantics during DEX conversion.
 *
 * @version $Id$
 */

package org.microemu.android.asm;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

public class AndroidClassVisitor extends ClassVisitor {
	private final String ownerClassName;

	AndroidClassVisitor(ClassVisitor cv) {
		this(cv, null);
	}

	AndroidClassVisitor(ClassVisitor cv, String ownerClassName) {
		super(Opcodes.ASM9, cv);
		this.ownerClassName = ownerClassName;
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		desc = TimingTypeMapper.mapDescriptor(desc);
		signature = TimingTypeMapper.mapSignature(signature);
		if (exceptions != null) {
			exceptions = exceptions.clone();
			for (int i = 0; i < exceptions.length; i++) {
				exceptions[i] = TimingTypeMapper.mapInternalName(exceptions[i]);
			}
		}
		boolean returnsBoolean = Type.getReturnType(desc).getSort() == Type.BOOLEAN;
		return new AndroidMethodVisitor(
				super.visitMethod(access, name, desc, signature, exceptions), returnsBoolean,
				ownerClassName);
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		signature = TimingTypeMapper.mapSignature(signature);
		superName = TimingTypeMapper.mapInternalName(superName);
		if (interfaces != null) {
			interfaces = interfaces.clone();
			for (int i = 0; i < interfaces.length; i++) {
				interfaces[i] = TimingTypeMapper.mapInternalName(interfaces[i]);
			}
		}
		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public void visitOuterClass(String owner, String name, String descriptor) {
		super.visitOuterClass(
				TimingTypeMapper.mapInternalName(owner),
				name,
				TimingTypeMapper.mapDescriptor(descriptor));
	}

	@Override
	public void visitInnerClass(String name, String outerName, String innerName, int access) {
		super.visitInnerClass(
				TimingTypeMapper.mapInternalName(name),
				TimingTypeMapper.mapInternalName(outerName),
				innerName,
				access);
	}

	@Override
	public void visitNestHost(String nestHost) {
		super.visitNestHost(TimingTypeMapper.mapInternalName(nestHost));
	}

	@Override
	public void visitNestMember(String nestMember) {
		super.visitNestMember(TimingTypeMapper.mapInternalName(nestMember));
	}

	@Override
	public void visitPermittedSubclass(String permittedSubclass) {
		super.visitPermittedSubclass(TimingTypeMapper.mapInternalName(permittedSubclass));
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
	public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
		descriptor = TimingTypeMapper.mapDescriptor(descriptor);
		signature = TimingTypeMapper.mapSignature(signature);
		value = TimingTypeMapper.mapValue(value);
		FieldVisitor fieldVisitor = super.visitField(access, name, descriptor, signature, value);
		if (fieldVisitor == null) {
			return null;
		}
		return new FieldVisitor(Opcodes.ASM9, fieldVisitor) {
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
		};
	}

	@Override
	public RecordComponentVisitor visitRecordComponent(
			String name, String descriptor, String signature) {
		RecordComponentVisitor recordComponentVisitor = super.visitRecordComponent(
				name,
				TimingTypeMapper.mapDescriptor(descriptor),
				TimingTypeMapper.mapSignature(signature));
		if (recordComponentVisitor == null) {
			return null;
		}
		return new RecordComponentVisitor(Opcodes.ASM9, recordComponentVisitor) {
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
		};
	}
}
