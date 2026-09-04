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
		// One production validator owns the resident-run contract. The old 4,096-run forwarding
		// wrapper is intentionally bypassed so a fragmented ART heap cannot be accepted by the
		// target bridge and then rejected by a second, stale native limit.
		int result = configureTargetExpanded(pid, pageSize, runtimeToken, runs);
		if (BuildConfig.DEBUG) {
			if (result == MemoryEngineContract.RESULT_OK) {
				configureV2ShadowTarget(pid, runtimeToken, runs);
			} else {
				clearV2ShadowTarget();
			}
		}
		return result;
	}

	private static native int configureTargetExpanded(int pid, int pageSize, long runtimeToken,
	                                                  long[] runs);

	private static native void configureV2ShadowTarget(int pid, long runtimeToken, long[] runs);

	static native boolean prepareOperation(long cancellationEpoch);

	static native boolean canReadTarget(int pid, long address, long expectedBits);

	static native boolean canWriteTarget(int pid, long address, long expectedBits);

	static int startKnown(int valueType, int predicate, String first, String second) {
		int result = startKnownUnchecked(valueType, predicate, first, second);
		if (result == MemoryEngineContract.RESULT_OK) {
			// Production v2 staging is deliberately opportunistic. The native adapter only publishes
			// an explicit-type ResultStore after proving exact address/type parity against the immutable
			// legacy revision and enforcing a conservative size cap. Failure leaves legacy paging intact.
			if (valueType != MemoryEngineContract.TYPE_AUTO) {
				stageV2KnownResultStore();
			} else {
				clearV2KnownResultStore();
			}
			if (BuildConfig.DEBUG) {
				// A fresh production Known search starts a new semantic revision even when the target and
				// primitive type are unchanged. Drop the retained debug shadow revision so the next parity
				// probe performs an independent first scan rather than accidentally refining stale bits.
				resetV2ShadowSession();
			}
		}
		return result;
	}

	private static native int startKnownUnchecked(int valueType, int predicate,
	                                             String first, String second);

	private static native boolean stageV2KnownResultStore();

	private static native void clearV2KnownResultStore();

	static native int startUnknown(int valueType);

	static native int startGroup(int[] valueTypes, String[] values, int maxDistance);

	static native int startNearby(long anchorCandidateId, int radius, int valueType,
	                              int predicate, String first, String second);

	static int refineKnown(int predicate, String first, String second) {
		// Compatibility/internal callers that have no target-GC evidence must never request an
		// expensive relocation reconciliation. The service uses the overload below after comparing
		// the published search epoch with the current target GC epoch.
		return refineKnown(predicate, first, second, false);
	}

	static int refineKnown(int predicate, String first, String second,
	                      boolean allowRelocationReconcile) {
		// Ordinary search results remain address membership, not millions of permanently tracked
		// objects. Native first evaluates only the committed addresses. A full streaming relocation
		// pass is merely permitted here; native still requires >=64K typed candidates and strong
		// fingerprint evidence before paying that cost. Strict identity remains mandatory on writes.
		int result = refineKnownAddressSet(
				predicate, first, second, allowRelocationReconcile);
		if (result == MemoryEngineContract.RESULT_OK) {
			// Keep the verified ResultStore read path opportunistically staged for explicit types.
			stageV2KnownResultStore();
		} else {
			// A failed/cancelled refine leaves the legacy revision transactional and authoritative.
			// Drop any staged mirror rather than letting presentation depend on stale migration state.
			clearV2KnownResultStore();
		}
		return result;
	}

	private static native int refineKnownAddressSet(int predicate, String first, String second,
	                                                boolean allowRelocationReconcile);

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

	static long[] resultPage(int offset, int limit) {
		// Only a ResultStore revision that was built from and verified against the exact current
		// immutable legacy SearchState can answer here. Any state change/invariant mismatch returns
		// null and falls back to the validated Candidate paging implementation.
		long[] staged = resultPageV2Known(offset, limit);
		return staged != null ? staged : resultPageUnchecked(offset, limit);
	}

	private static native long[] resultPageV2Known(int offset, int limit);

	private static native long[] resultPageUnchecked(int offset, int limit);

	static native long[] inspect(long candidateId, int radius);

	/**
	 * Returns [valueType, predicate, firstBits, secondBits] using the exact native parser that owns
	 * the production legacy Known search. This is migration/diagnostics metadata only: v2 never
	 * reparses query strings independently.
	 */
	static native long[] canonicalKnownPlan(int valueType, int predicate,
	                                       String first, String second);

	static native long[] v2ShadowKnownEqual(int valueType, long initialBits, long currentBits);

	/**
	 * Debug/shadow boundary for an already parsed explicit-type Known query. Thresholds are raw
	 * primitive bits from the authoritative legacy parser; this method intentionally accepts no
	 * query strings so the v2 validation path can never grow a second parser with subtly different
	 * signedness, Float rounding, range, or hex semantics.
	 */
	static native long[] v2ShadowKnown(int valueType, int predicate,
	                                  long firstBits, long secondBits);

	static void clearSearch() {
		clearSearchUnchecked();
		clearV2KnownResultStore();
		if (BuildConfig.DEBUG) {
			resetV2ShadowSession();
		}
	}

	private static native void clearSearchUnchecked();

	static void clearTarget() {
		clearTargetUnchecked();
		clearV2KnownResultStore();
		if (BuildConfig.DEBUG) {
			clearV2ShadowTarget();
		}
	}

	private static native void clearTargetUnchecked();

	private static native void clearV2ShadowTarget();

	private static native void resetV2ShadowSession();

	static native void cancel(long cancellationEpoch);

	static native String lastMessage();
}