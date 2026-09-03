/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package ru.playsoftware.j2meloader.memory;

import ru.playsoftware.j2meloader.BuildConfig;

final class NativeMemoryEngine {
	static {
		System.loadLibrary("jlmem");
	}

	private NativeMemoryEngine() {
	}

	static int configureTarget(int pid, int pageSize, long runtimeToken, long[] runs) {
		int result = configureTargetUnchecked(pid, pageSize, runtimeToken, runs);
		if (BuildConfig.DEBUG) {
			if (result == MemoryEngineContract.RESULT_OK) {
				configureV2ShadowTarget(pid, runtimeToken, runs);
			} else {
				clearV2ShadowTarget();
			}
		}
		return result;
	}

	private static native int configureTargetUnchecked(int pid, int pageSize, long runtimeToken,
	                                                   long[] runs);

	private static native void configureV2ShadowTarget(int pid, long runtimeToken, long[] runs);

	static native boolean prepareOperation(long cancellationEpoch);

	static native boolean canReadTarget(int pid, long address, long expectedBits);

	static native boolean canWriteTarget(int pid, long address, long expectedBits);

	static native int startKnown(int valueType, int predicate, String first, String second);

	static native int startUnknown(int valueType);

	static native int startGroup(int[] valueTypes, String[] values, int maxDistance);

	static native int startNearby(long anchorCandidateId, int radius, int valueType,
	                              int predicate, String first, String second);

	static native int refineKnown(int predicate, String first, String second);

	static native int recoverKnown(int predicate, String first, String second);

	static native int refineRelative(int predicate, int compareTarget, String first, String second);

	static native int undo();

	static native int refresh(long[] candidateIds, boolean allowRecovery);

	static native int filter(long[] candidateIds, boolean keep);

	static int edit(long[] candidateIds, String replacementValue) {
		int result = editUnchecked(candidateIds, replacementValue);
		return MemoryMutationOutcome.classifyEditResult(result, lastMessage());
	}

	private static native int editUnchecked(long[] candidateIds, String replacementValue);

	static native long[] expandResultGroups(long[] resultIds, int valueType);

	static native int editInspectorValue(long anchorCandidateId, int relativeOffset,
	                                     int valueType, long expectedBits,
	                                     String replacementValue);

	static native int pin(long[] candidateIds, boolean add);

	static native long[] watchPage();

	static native int freeze(long[] candidateIds, int mode, String firstValue, String secondValue);

	static native long resultCount();

	static native long[] scanProgress();

	static native int historyDepth();

	static native long[] resultPage(int offset, int limit);

	static native long[] inspect(long candidateId, int radius);

	static native long[] v2ShadowKnownEqual(int valueType, long initialBits, long currentBits);

	static native void clearSearch();

	static void clearTarget() {
		clearTargetUnchecked();
		if (BuildConfig.DEBUG) {
			clearV2ShadowTarget();
		}
	}

	private static native void clearTargetUnchecked();

	private static native void clearV2ShadowTarget();

	static native void cancel(long cancellationEpoch);

	static native String lastMessage();
}
