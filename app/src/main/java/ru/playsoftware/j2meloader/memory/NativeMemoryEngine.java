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

package ru.playsoftware.j2meloader.memory;

final class NativeMemoryEngine {
	static {
		System.loadLibrary("jlmem");
	}

	private NativeMemoryEngine() {
	}

	static native int configureTarget(int pid, int pageSize, long runtimeToken, long[] runs);

	static native boolean canReadTarget(int pid, long address, long expectedBits);

	static native boolean canWriteTarget(int pid, long address, long expectedBits);

	static native int startKnown(int valueType, int predicate, String first, String second);

	static native int startUnknown(int valueType);

	static native int startGroup(int[] valueTypes, String[] values, int maxDistance);

	static native int refineKnown(int predicate, String first, String second);

	static native int recoverKnown(int predicate, String first, String second);

	static native int refineRelative(int predicate, int compareTarget, String first, String second);

	static native int undo();

	static native int refresh(long[] candidateIds, boolean allowRecovery);

	static native int filter(long[] candidateIds, boolean keep);

	static native int edit(long[] candidateIds, String replacementValue);

	static native int pin(long[] candidateIds, boolean add);

	static native long[] watchPage();

	static native int freeze(long[] candidateIds, int mode, String firstValue, String secondValue);

	static native long resultCount();

	static native long[] resultPage(int offset, int limit);

	static native void clearSearch();

	static native void clearTarget();

	static native void cancel();

	static native String lastMessage();
}
