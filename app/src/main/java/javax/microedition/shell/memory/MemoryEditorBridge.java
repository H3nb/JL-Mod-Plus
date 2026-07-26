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

package javax.microedition.shell.memory;

/** Bytecode ABI used by the generated DEX instrumentation. */
public final class MemoryEditorBridge {
	private MemoryEditorBridge() {
	}

	public static int onReadInt(Object target, Class<?> owner, String member,
			long site, int index, int value) {
		return MemoryEditorRuntime.onReadInt(target, owner, member, site, index, value);
	}

	public static int onWriteInt(Object target, Class<?> owner, String member,
			long site, int index, int value) {
		return MemoryEditorRuntime.onWriteInt(target, owner, member, site, index, value);
	}

	public static long onReadLong(Object target, Class<?> owner, String member,
			long site, int index, long value) {
		return MemoryEditorRuntime.onReadLong(target, owner, member, site, index, value);
	}

	public static long onWriteLong(Object target, Class<?> owner, String member,
			long site, int index, long value) {
		return MemoryEditorRuntime.onWriteLong(target, owner, member, site, index, value);
	}

	public static float onReadFloat(Object target, Class<?> owner, String member,
			long site, int index, float value) {
		return MemoryEditorRuntime.onReadFloat(target, owner, member, site, index, value);
	}

	public static float onWriteFloat(Object target, Class<?> owner, String member,
			long site, int index, float value) {
		return MemoryEditorRuntime.onWriteFloat(target, owner, member, site, index, value);
	}

	public static double onReadDouble(Object target, Class<?> owner, String member,
			long site, int index, double value) {
		return MemoryEditorRuntime.onReadDouble(target, owner, member, site, index, value);
	}

	public static double onWriteDouble(Object target, Class<?> owner, String member,
			long site, int index, double value) {
		return MemoryEditorRuntime.onWriteDouble(target, owner, member, site, index, value);
	}
}
