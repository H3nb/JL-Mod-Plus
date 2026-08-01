/**
 * Copyright 2026 H3NB
 *
 * MicroEmulator
 * Copyright (C) 2008 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2017-2018 Nikita Shakarun
 * Copyright (C) 2021-2024 Yury Kharchenko
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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.Opcodes;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class AndroidProducer {
	private static final int MEMORY_EDITOR_METHOD_INSTRUCTION_LIMIT = 10_000;
	private static final Map<Integer, Integer> patches = initPatchFixes();

	/** Result of converting one class through the optional Memory Editor layer. */
	public static final class InstrumentationResult {
		public final byte[] bytes;
		public final boolean memoryEditorApplied;
		public final String memoryEditorSkipReason;

		private InstrumentationResult(byte[] bytes, boolean memoryEditorApplied,
				String memoryEditorSkipReason) {
			this.bytes = bytes;
			this.memoryEditorApplied = memoryEditorApplied;
			this.memoryEditorSkipReason = memoryEditorSkipReason;
		}
	}

	public static byte[] instrument(byte[] classData, String classFileName, long crc)
				throws IllegalArgumentException {
		return instrumentWithReport(classData, classFileName, crc).bytes;
	}

	/**
	 * Instruments one class and falls back to the compatibility-only pipeline
	 * when the optional Memory Editor visitor cannot safely transform it.
	 *
	 * <p>The fallback deliberately starts from the original class bytes. This
	 * preserves timing, encoding, Timer, and other emulator rewrites while
	 * dropping only the optional Memory Editor layer for the affected class.</p>
	 */
	public static InstrumentationResult instrumentWithReport(byte[] classData,
			String classFileName, long crc) throws IllegalArgumentException {
		Integer patch = patches.get((int) crc);
		if (patch != null) {
			classData = patchClass(classData, patch);
		}
		ClassReader cr = new ClassReader(classData);
		if (!cr.getClassName().equals(classFileName.substring(0, classFileName.length() - 6))) {
			throw new IllegalArgumentException("Class name does not match path");
		}
		if (exceedsMemoryEditorPreflight(classData)) {
			try {
				return new InstrumentationResult(transform(classData, false), false,
						"METHOD_TOO_LARGE_PRECHECK");
			} catch (RuntimeException compatibilityFailure) {
				throw new IllegalArgumentException(
						"Memory Editor preflight fallback failed for " + classFileName,
						compatibilityFailure);
			}
		}

		try {
			return new InstrumentationResult(transform(classData, true), true, null);
		} catch (RuntimeException memoryEditorFailure) {
			try {
				byte[] compatibilityOnly = transform(classData, false);
				return new InstrumentationResult(compatibilityOnly, false,
						classifyFailure(memoryEditorFailure));
			} catch (RuntimeException compatibilityFailure) {
				IllegalArgumentException failure = new IllegalArgumentException(
						"Memory Editor fallback failed for " + classFileName,
						compatibilityFailure);
				failure.addSuppressed(memoryEditorFailure);
				throw failure;
			}
		}
	}

	private static byte[] transform(byte[] classData, boolean memoryEditorEnabled) {
		ClassReader cr = new ClassReader(classData);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		ClassVisitor cv = new AndroidClassVisitor(cw, memoryEditorEnabled);
		cr.accept(cv, ClassReader.SKIP_DEBUG);
		return cw.toByteArray();
	}

	private static boolean exceedsMemoryEditorPreflight(byte[] classData) {
		try {
			final boolean[] tooLarge = {false};
			new ClassReader(classData).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
						String signature, String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
						private int instructionCount;

						private void count() {
							if (++instructionCount > MEMORY_EDITOR_METHOD_INSTRUCTION_LIMIT) {
								tooLarge[0] = true;
							}
						}

						@Override public void visitInsn(int opcode) { count(); }
						@Override public void visitIntInsn(int opcode, int operand) { count(); }
						@Override public void visitVarInsn(int opcode, int varIndex) { count(); }
						@Override public void visitTypeInsn(int opcode, String type) { count(); }
						@Override public void visitFieldInsn(int opcode, String owner,
								String name, String descriptor) { count(); }
						@Override public void visitMethodInsn(int opcode, String owner,
								String name, String descriptor, boolean isInterface) { count(); }
						@Override public void visitInvokeDynamicInsn(String name, String descriptor,
								org.objectweb.asm.Handle bootstrapMethodHandle,
								Object... bootstrapMethodArguments) { count(); }
						@Override public void visitJumpInsn(int opcode,
								org.objectweb.asm.Label label) { count(); }
						@Override public void visitLdcInsn(Object value) { count(); }
						@Override public void visitIincInsn(int varIndex, int increment) { count(); }
						@Override public void visitTableSwitchInsn(int min, int max,
								org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) { count(); }
						@Override public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt,
								int[] keys, org.objectweb.asm.Label[] labels) { count(); }
						@Override public void visitMultiANewArrayInsn(String descriptor,
								int numDimensions) { count(); }
					};
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return tooLarge[0];
		} catch (RuntimeException ignored) {
			// Let the normal transformation path classify malformed input.
			return false;
		}
	}

	private static String classifyFailure(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof MethodTooLargeException) {
				return "METHOD_TOO_LARGE";
			}
			if (current instanceof IndexOutOfBoundsException) {
				return "STACK_ANALYSIS";
			}
		}
		return "INSTRUMENTATION_FAILED";
	}

	private static byte[] patchClass(byte[] classData, int patch) {
		try (DataInputStream dis = new DataInputStream(openPatches())) {
			dis.skipBytes(patch);
			int len = dis.readUnsignedShort();
			int newSize = dis.readShort() + classData.length;
			byte[] patchData = new byte[len - 2];
			dis.readFully(patchData);
			return BinaryPatcher.patch(classData, patchData, newSize);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return classData;
	}

	public static Map<Integer, Integer> initPatchFixes() {
		Map<Integer, Integer> map = new HashMap<>();
		try (DataInputStream dis = new DataInputStream(openPatches())) {
			int pos = 0;
			//noinspection InfiniteLoopStatement
			while (true) {
				int key = dis.readInt();
				pos += 4;
				map.put(key, pos);
				int len = dis.readUnsignedShort();
				dis.skipBytes(len);
				pos += len + 2;
			}
		} catch (EOFException ignored) {
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return map;
	}

	private static InputStream openPatches() throws IOException {
		InputStream stream = AndroidProducer.class.getResourceAsStream(
				"/assets/dexer/patches.bin");
		if (stream == null) {
			// JVM unit tests expose src/main/assets as a resource root.
			stream = AndroidProducer.class.getResourceAsStream("/dexer/patches.bin");
		}
		if (stream == null) {
			throw new IOException("Missing dexer patch resource");
		}
		return stream;
	}
}
