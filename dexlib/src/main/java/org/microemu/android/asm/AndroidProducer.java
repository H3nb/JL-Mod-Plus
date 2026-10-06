/**
 * MicroEmulator
 * Copyright (C) 2008 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2017-2018 Nikita Shakarun
 * Copyright (C) 2021-2024 Yury Kharchenko
 * Modified for JL-Mod Plus.
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
import org.objectweb.asm.Opcodes;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class AndroidProducer {
	// Keep this aligned with the highest class-file version supported by the bundled ASM.
	// AndroidProducerDexTest intentionally fails when a future ASM adds a newer Vxx constant.
	static final int MAX_SUPPORTED_CLASS_VERSION = Opcodes.V27;
	private static final Map<Integer, Integer> patches = initPatchFixes();

	public static byte[] instrument(byte[] classData, String classFileName, long crc) {
		Integer patch = patches.get((int) crc);
		if (patch != null) {
			classData = patchClass(classData, patch);
		}

		if (hasClassMagic(classData)
				&& classMajorVersion(classData) > MAX_SUPPORTED_CLASS_VERSION) {
			throw new ClassProcessingException(
					ClassProcessingException.Kind.UNSUPPORTED_SOURCE,
					classFileName,
					"Source class version is newer than this converter supports",
					null);
		}

		final ClassReader cr;
		try {
			cr = new ClassReader(classData);
		} catch (RuntimeException sourceFailure) {
			throw new ClassProcessingException(
					ClassProcessingException.Kind.UNREADABLE_SOURCE,
					classFileName,
					"Source class cannot be read",
					sourceFailure);
		}

		final String sourceClassName;
		try {
			sourceClassName = cr.getClassName();
		} catch (RuntimeException sourceFailure) {
			throw new ClassProcessingException(
					ClassProcessingException.Kind.UNREADABLE_SOURCE,
					classFileName,
					"Source class identity cannot be read",
					sourceFailure);
		}

		String expectedName = classFileName.substring(0, classFileName.length() - 6);
		if (!sourceClassName.equals(expectedName)) {
			throw new ClassProcessingException(
					ClassProcessingException.Kind.IDENTITY_MISMATCH,
					classFileName,
					"Class name does not match path: " + sourceClassName + " != " + expectedName,
					null);
		}

		try {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			// Pass the original guest class token to reflection rewrites. The token carries the
			// AppClassLoader that owns classes present only in the MIDlet archive.
			ClassVisitor cv = new AndroidClassVisitor(cw, sourceClassName);
			cr.accept(cv, ClassReader.SKIP_DEBUG);
			return cw.toByteArray();
		} catch (RuntimeException transformFailure) {
			try {
				// The normal fast path never performs this pass. It is used only after a transform
				// anomaly to distinguish malformed source input from a failure introduced by JL-Mod.
				cr.accept(new ClassWriter(0), ClassReader.SKIP_DEBUG);
			} catch (RuntimeException sourceFailure) {
				ClassProcessingException unreadable = new ClassProcessingException(
						ClassProcessingException.Kind.UNREADABLE_SOURCE,
						classFileName,
						"Source class is malformed during full traversal",
						sourceFailure);
				unreadable.addSuppressed(transformFailure);
				throw unreadable;
			}
			throw new ClassProcessingException(
					ClassProcessingException.Kind.TRANSFORM_FAILURE,
					classFileName,
					"JL-Mod transform failed for valid source class",
					transformFailure);
		}
	}

	private static boolean hasClassMagic(byte[] classData) {
		return classData != null
				&& classData.length >= 8
				&& (classData[0] & 0xff) == 0xca
				&& (classData[1] & 0xff) == 0xfe
				&& (classData[2] & 0xff) == 0xba
				&& (classData[3] & 0xff) == 0xbe;
	}

	private static int classMajorVersion(byte[] classData) {
		return ((classData[6] & 0xff) << 8) | (classData[7] & 0xff);
	}

	private static byte[] patchClass(byte[] classData, int patch) {
		InputStream patchStream = AndroidProducer.class.getResourceAsStream("/assets/dexer/patches.bin");
		if (patchStream == null) {
			return classData;
		}
		try (DataInputStream dis = new DataInputStream(patchStream)) {
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
		InputStream patchStream = AndroidProducer.class.getResourceAsStream("/assets/dexer/patches.bin");
		if (patchStream == null) {
			return map;
		}
		try (DataInputStream dis = new DataInputStream(patchStream)) {
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
}
