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
	private static final Object V2_KNOWN_PAGING_LOCK = new Object();
	private static long v2KnownStageGeneration;
	private static boolean v2KnownStaged;
	private static boolean v2KnownAuthoritativeRevision;
	private static long v2KnownPageHits;
	private static long v2KnownPageFallbacks;

	static {
		System.loadLibrary("jlmem");
	}

	private NativeMemoryEngine() {
	}

	static int configureTarget(int pid, int pageSize, long runtimeToken, long[] runs) {
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
		int result = valueType == MemoryEngineContract.TYPE_AUTO
				? startKnownAutoV2Authoritative(predicate, first, second)
				: startKnownV2Authoritative(valueType, predicate, first, second);
		if (result == MemoryEngineContract.RESULT_OK) {
			publishV2KnownPagingStage(true, true);
			rememberCurrentV2Revision();
			if (BuildConfig.DEBUG) resetV2ShadowSession();
		}
		return result;
	}

	private static native int startKnownV2Authoritative(int valueType, int predicate,
	                                                    String first, String second);

	private static native int startKnownAutoV2Authoritative(int predicate, String first,
	                                                        String second);

	private static native boolean stageV2KnownResultStore();

	private static native boolean hasCurrentV2KnownResultStore();

	private static native void clearV2KnownResultStore();

	private static native boolean stageV2AutoResultStore();

	private static native boolean hasCurrentV2AutoResultStore();

	private static native void clearV2AutoResultStore();

	private static native boolean rememberCurrentV2Revision();

	private static native void clearV2RevisionCatalog();

	static native long[] v2RevisionCatalogStats();

	static native int startUnknown(int valueType);

	static native int startGroup(int[] valueTypes, String[] values, int maxDistance);

	static native int startNearby(long anchorCandidateId, int radius, int valueType,
	                              int predicate, String first, String second);

	static int refineKnown(int predicate, String first, String second) {
		return refineKnown(predicate, first, second, false);
	}

	static int refineKnown(int predicate, String first, String second,
	                      boolean allowRelocationReconcile) {
		rememberAuthoritativeCurrentRevision();
		boolean autoRevision = hasCurrentV2AutoResultStore();
		boolean explicitRevision = hasCurrentV2KnownResultStore();
		if (!autoRevision && !explicitRevision) autoRevision = stageV2AutoResultStore();
		int result = autoRevision
				? refineKnownAutoV2Authoritative(
						predicate, first, second, allowRelocationReconcile)
				: refineKnownV2Authoritative(
						predicate, first, second, allowRelocationReconcile);
		if (result == MemoryEngineContract.RESULT_OK) {
			boolean authoritative = hasCurrentV2KnownResultStore() || hasCurrentV2AutoResultStore();
			if (!authoritative) authoritative = stageV2KnownResultStore() || stageV2AutoResultStore();
			publishV2KnownPagingStage(authoritative, authoritative);
			if (authoritative) rememberCurrentV2Revision();
		}
		return result;
	}

	private static native int refineKnownV2Authoritative(int predicate, String first, String second,
	                                                    boolean allowRelocationReconcile);

	private static native int refineKnownAutoV2Authoritative(
			int predicate, String first, String second, boolean allowRelocationReconcile);

	static int refineRelative(int predicate, int compareTarget, String first, String second) {
		rememberAuthoritativeCurrentRevision();
		int result = refineRelativeV2Authoritative(predicate, compareTarget, first, second);
		if (result == MemoryEngineContract.RESULT_OK) {
			boolean authoritative = hasCurrentV2KnownResultStore() || hasCurrentV2AutoResultStore();
			if (!authoritative) authoritative = stageV2KnownResultStore() || stageV2AutoResultStore();
			publishV2KnownPagingStage(authoritative, authoritative);
			if (authoritative) rememberCurrentV2Revision();
		}
		return result;
	}

	private static native int refineRelativeV2Authoritative(
			int predicate, int compareTarget, String first, String second);

	static int undo() {
		int result = undoV2Aware();
		if (result != MemoryEngineContract.RESULT_OK) return result;
		boolean remembered = hasCurrentV2KnownResultStore() || hasCurrentV2AutoResultStore();
		if (remembered) {
			publishV2KnownPagingStage(true, true);
			return result;
		}
		boolean restaged = stageV2KnownResultStore() || stageV2AutoResultStore();
		publishV2KnownPagingStage(restaged, false);
		return result;
	}

	private static native int undoV2Aware();

	static int refresh(long[] candidateIds, boolean allowRecovery) {
		return allowRecovery
				? refreshUnchecked(candidateIds, true)
				: refreshPresentation(candidateIds);
	}

	private static native int refreshUnchecked(long[] candidateIds, boolean allowRecovery);

	private static native int refreshPresentation(long[] candidateIds);

	static int filter(long[] candidateIds, boolean keep) {
		rememberAuthoritativeCurrentRevision();
		int result = filterUnchecked(candidateIds, keep);
		if (result == MemoryEngineContract.RESULT_OK) {
			boolean staged = stageV2KnownResultStore() || stageV2AutoResultStore();
			publishV2KnownPagingStage(staged, false);
		}
		return result;
	}

	private static native int filterUnchecked(long[] candidateIds, boolean keep);

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

	private static void rememberAuthoritativeCurrentRevision() {
		boolean authoritative;
		synchronized (V2_KNOWN_PAGING_LOCK) {
			authoritative = v2KnownStaged && v2KnownAuthoritativeRevision;
		}
		if (!authoritative || rememberCurrentV2Revision()) return;
		// Watch/presentation operations can replace SearchState while leaving membership unchanged.
		// Rebuild only bitmap metadata from the current Candidate mirror, never target memory, then
		// bind the authoritative revision to the new immutable SearchState pointer.
		boolean staged = stageV2KnownResultStore() || stageV2AutoResultStore();
		if (staged) rememberCurrentV2Revision();
	}

	private static long[] stagedResultPage(int offset, int limit) {
		long[] page = resultPageV2Auto(offset, limit);
		return page != null ? page : resultPageV2Known(offset, limit);
	}

	private static boolean restageCurrentResultStoreForPaging() {
		boolean staged = stageV2KnownResultStore();
		if (!staged) staged = stageV2AutoResultStore();
		if (staged) publishV2KnownPagingStage(true, false);
		return staged;
	}

	static long[] resultPage(int offset, int limit) {
		long stagedGeneration;
		synchronized (V2_KNOWN_PAGING_LOCK) {
			stagedGeneration = v2KnownStaged ? v2KnownStageGeneration : 0L;
		}
		if (stagedGeneration != 0L) {
			long[] staged = stagedResultPage(offset, limit);
			if (staged != null) {
				recordV2PageHit(stagedGeneration);
				return staged;
			}
		}

		if (restageCurrentResultStoreForPaging()) {
			synchronized (V2_KNOWN_PAGING_LOCK) {
				stagedGeneration = v2KnownStageGeneration;
			}
			long[] staged = stagedResultPage(offset, limit);
			if (staged != null) {
				recordV2PageHit(stagedGeneration);
				return staged;
			}
		}

		synchronized (V2_KNOWN_PAGING_LOCK) {
			v2KnownPageFallbacks++;
			if (stagedGeneration != 0L && v2KnownStageGeneration == stagedGeneration) {
				v2KnownStaged = false;
				v2KnownAuthoritativeRevision = false;
			}
		}
		return resultPageUnchecked(offset, limit);
	}

	private static void recordV2PageHit(long stagedGeneration) {
		synchronized (V2_KNOWN_PAGING_LOCK) {
			if (v2KnownStaged && v2KnownStageGeneration == stagedGeneration) v2KnownPageHits++;
		}
	}

	private static native long[] resultPageV2Auto(int offset, int limit);

	private static native long[] resultPageV2Known(int offset, int limit);

	private static native long[] resultPageUnchecked(int offset, int limit);

	static long[] v2KnownPagingStats() {
		synchronized (V2_KNOWN_PAGING_LOCK) {
			return new long[]{
					v2KnownStaged ? 1L : 0L,
					v2KnownStageGeneration,
					v2KnownPageHits,
					v2KnownPageFallbacks
			};
		}
	}

	static boolean v2KnownAuthoritativeRevision() {
		synchronized (V2_KNOWN_PAGING_LOCK) {
			return v2KnownStaged && v2KnownAuthoritativeRevision;
		}
	}

	static boolean v2KnownAuthoritativeFirstScan() {
		return v2KnownAuthoritativeRevision();
	}

	private static void publishV2KnownPagingStage(boolean staged, boolean authoritativeRevision) {
		synchronized (V2_KNOWN_PAGING_LOCK) {
			v2KnownStageGeneration = v2KnownStageGeneration == Long.MAX_VALUE
					? 1L : v2KnownStageGeneration + 1L;
			v2KnownStaged = staged;
			v2KnownAuthoritativeRevision = staged && authoritativeRevision;
			v2KnownPageHits = 0L;
			v2KnownPageFallbacks = 0L;
		}
	}

	private static void clearV2KnownPagingStage() {
		clearV2AutoResultStore();
		clearV2KnownResultStore();
		clearV2RevisionCatalog();
		publishV2KnownPagingStage(false, false);
	}

	static native long[] inspect(long candidateId, int radius);

	static native long[] canonicalKnownPlan(int valueType, int predicate, String first, String second);

	static native long[] v2ShadowKnownEqual(int valueType, long initialBits, long currentBits);

	static native long[] v2ShadowKnown(int valueType, int predicate,
	                                  long firstBits, long secondBits);

	static void clearSearch() {
		clearSearchUnchecked();
		clearV2KnownPagingStage();
		if (BuildConfig.DEBUG) resetV2ShadowSession();
	}

	private static native void clearSearchUnchecked();

	static void clearTarget() {
		clearTargetUnchecked();
		clearV2KnownPagingStage();
		if (BuildConfig.DEBUG) clearV2ShadowTarget();
	}

	private static native void clearTargetUnchecked();

	private static native void clearV2ShadowTarget();

	private static native void resetV2ShadowSession();

	static native void cancel(long cancellationEpoch);

	static native String lastMessage();
}
