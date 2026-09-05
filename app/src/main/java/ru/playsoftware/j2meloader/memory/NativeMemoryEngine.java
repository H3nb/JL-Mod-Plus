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
		int result = startKnownV2CompactOwner(valueType, predicate, first, second);
		if (result == MemoryEngineContract.RESULT_OK) {
			clearV2RevisionCatalog();
			publishV2KnownPagingStage(true, true);
			if (BuildConfig.DEBUG) resetV2ShadowSession();
		}
		return result;
	}

	private static native int startKnownV2CompactOwner(int valueType, int predicate,
	                                                  String first, String second);

	// Candidate-compatible first-search paths remain compiled as differential/fallback references.
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

	// Differential staging from the old Candidate mirror is retained for instrumentation only.
	static native boolean stageV2CompactRevision();

	static native boolean hasCurrentV2CompactRevision();

	static native long[] resultPageV2Compact(int offset, int limit);

	static native int refreshV2Compact(long[] candidateIds, boolean allowRecovery);

	static native int editV2Compact(long[] candidateIds, String replacementValue);

	static native int pinV2Compact(long[] candidateIds, boolean add);

	static native long[] inspectV2Compact(long candidateId, int radius);

	static native long[] expandResultGroupsV2Compact(long[] resultIds, int valueType);

	private static native void clearV2CompactRevisions();

	// Candidate-free production owner API.
	private static native int refineKnownV2CompactOwner(int predicate, String first, String second,
	                                                   boolean allowRelocationReconcile);

	private static native int refineRelativeV2CompactOwnerSafe(
			int predicate, int compareTarget, String first, String second);

	private static native int startGroupV2CompactOwner(
			int[] valueTypes, String[] values, int encodedDistance);

	private static native int filterV2CompactOwner(long[] candidateIds, boolean keep);

	private static native long[] resultPageV2CompactOwner(int offset, int limit);

	private static native int refreshV2CompactOwner(long[] candidateIds, boolean allowRecovery);

	private static native int editV2CompactOwner(long[] candidateIds, String replacementValue);

	private static native int pinV2CompactOwner(long[] candidateIds, boolean add);

	private static native long[] inspectV2CompactOwner(long candidateId, int radius);

	private static native long[] expandResultGroupsV2CompactOwner(long[] resultIds, int valueType);

	private static native int editInspectorValueV2CompactOwner(
			long anchorCandidateId, int relativeOffset, int valueType,
			long expectedBits, String replacementValue);

	private static native int startNearbyV2CompactOwner(
			long anchorCandidateId, int radius, int valueType,
			int predicate, String first, String second);

	private static native int undoV2CompactOwner();

	static native long[] v2CompactOwnerStats();

	static int startUnknown(int valueType) {
		int result = startUnknownUnchecked(valueType);
		if (result == MemoryEngineContract.RESULT_OK) resetV2ForLegacyNewSearch();
		return result;
	}

	private static native int startUnknownUnchecked(int valueType);

	static int startGroup(int[] valueTypes, String[] values, int encodedDistance) {
		int result = startGroupV2CompactOwner(valueTypes, values, encodedDistance);
		if (result == MemoryEngineContract.RESULT_OK) {
			clearV2RevisionCatalog();
			publishV2KnownPagingStage(true, true);
			if (BuildConfig.DEBUG) resetV2ShadowSession();
		}
		return result;
	}

	// Legacy Candidate-backed Group remains compiled only as a differential/fallback reference.
	private static native int startGroupUnchecked(int[] valueTypes, String[] values, int maxDistance);

	static int startNearby(long anchorCandidateId, int radius, int valueType,
	                       int predicate, String first, String second) {
		int result = startNearbyV2CompactOwner(
				anchorCandidateId, radius, valueType, predicate, first, second);
		if (result == MemoryEngineContract.RESULT_OK) {
			clearV2RevisionCatalog();
			publishV2KnownPagingStage(true, true);
			if (BuildConfig.DEBUG) resetV2ShadowSession();
		}
		return result;
	}

	static int refineKnown(int predicate, String first, String second) {
		return refineKnown(predicate, first, second, false);
	}

	static int refineKnown(int predicate, String first, String second,
	                      boolean allowRelocationReconcile) {
		// Production Known Next Scan is valid only for a compact candidate revision. Unknown
		// baselines must materialize through refineRelative() before Known predicates are available.
		if (!hasCurrentV2CompactRevision()) {
			return MemoryEngineContract.RESULT_INVALID_REQUEST;
		}
		int result = refineKnownV2CompactOwner(
				predicate, first, second, allowRelocationReconcile);
		if (result == MemoryEngineContract.RESULT_OK) {
			publishV2KnownPagingStage(true, true);
		}
		return result;
	}

	private static native int refineKnownV2Authoritative(int predicate, String first, String second,
	                                                    boolean allowRelocationReconcile);

	private static native int refineKnownAutoV2Authoritative(
			int predicate, String first, String second, boolean allowRelocationReconcile);

	static int refineRelative(int predicate, int compareTarget, String first, String second) {
		// The compact owner handles both Unknown-baseline first materialization and later relative COW
		// refinements. A successful first relative scan therefore never creates a Candidate mirror.
		int result = refineRelativeV2CompactOwnerSafe(predicate, compareTarget, first, second);
		if (result == MemoryEngineContract.RESULT_OK) {
			publishV2KnownPagingStage(true, true);
		}
		return result;
	}

	// Candidate-compatible relative path remains compiled for differential tests only.
	private static native int refineRelativeV2Authoritative(
			int predicate, int compareTarget, String first, String second);

	static int undo() {
		int result = undoV2CompactOwner();
		if (result != MemoryEngineContract.RESULT_OK) return result;
		if (hasCurrentV2CompactRevision()) {
			publishV2KnownPagingStage(true, true);
			return result;
		}
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
		if (hasCurrentV2CompactRevision()) {
			return refreshV2CompactOwner(candidateIds, allowRecovery);
		}
		return allowRecovery
				? refreshUnchecked(candidateIds, true)
				: refreshPresentation(candidateIds);
	}

	private static native int refreshUnchecked(long[] candidateIds, boolean allowRecovery);

	private static native int refreshPresentation(long[] candidateIds);

	static int filter(long[] candidateIds, boolean keep) {
		if (hasCurrentV2CompactRevision()) {
			int result = filterV2CompactOwner(candidateIds, keep);
			if (result == MemoryEngineContract.RESULT_OK) {
				publishV2KnownPagingStage(true, true);
			}
			return result;
		}
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
		int result = hasCurrentV2CompactRevision()
				? editV2CompactOwner(candidateIds, replacementValue)
				: editUnchecked(candidateIds, replacementValue);
		return MemoryMutationOutcome.classifyEditResult(result, lastMessage());
	}

	private static native int editUnchecked(long[] candidateIds, String replacementValue);

	static long[] expandResultGroups(long[] resultIds, int valueType) {
		return hasCurrentV2CompactRevision()
				? expandResultGroupsV2CompactOwner(resultIds, valueType)
				: expandResultGroupsUnchecked(resultIds, valueType);
	}

	private static native long[] expandResultGroupsUnchecked(long[] resultIds, int valueType);

	static int editInspectorValue(long anchorCandidateId, int relativeOffset,
	                              int valueType, long expectedBits,
	                              String replacementValue) {
		return hasCurrentV2CompactRevision()
				? editInspectorValueV2CompactOwner(
						anchorCandidateId, relativeOffset, valueType,
						expectedBits, replacementValue)
				: editInspectorValueUnchecked(
						anchorCandidateId, relativeOffset, valueType,
						expectedBits, replacementValue);
	}

	private static native int editInspectorValueUnchecked(
			long anchorCandidateId, int relativeOffset, int valueType,
			long expectedBits, String replacementValue);

	static int pin(long[] candidateIds, boolean add) {
		if (hasCurrentV2CompactRevision()) {
			int result = pinV2CompactOwner(candidateIds, add);
			if (result == MemoryEngineContract.RESULT_OK) {
				publishV2KnownPagingStage(true, true);
			}
			return result;
		}
		boolean authoritative = isCurrentV2RevisionAuthoritative();
		if (authoritative) rememberAuthoritativeCurrentRevision();
		int result = pinUnchecked(candidateIds, add);
		if (result == MemoryEngineContract.RESULT_OK && authoritative) {
			boolean staged = stageV2KnownResultStore() || stageV2AutoResultStore();
			publishV2KnownPagingStage(staged, staged);
			if (staged) rememberCurrentV2Revision();
		}
		return result;
	}

	private static native int pinUnchecked(long[] candidateIds, boolean add);

	static native long[] watchPage();

	// Freeze deliberately remains Watch-only. Every Watch row is already a promoted heavyweight
	// Candidate, so the compact ordinary result database is not part of the Freeze loop.
	static native int freeze(long[] candidateIds, int mode, String firstValue, String secondValue);

	static native long resultCount();

	static native long[] scanProgress();

	static native int historyDepth();

	private static void resetV2ForLegacyNewSearch() {
		clearV2KnownPagingStage();
		if (BuildConfig.DEBUG) resetV2ShadowSession();
	}

	private static boolean isCurrentV2RevisionAuthoritative() {
		synchronized (V2_KNOWN_PAGING_LOCK) {
			return v2KnownStaged && v2KnownAuthoritativeRevision;
		}
	}

	private static void rememberAuthoritativeCurrentRevision() {
		if (hasCurrentV2CompactRevision()) return;
		if (!isCurrentV2RevisionAuthoritative() || rememberCurrentV2Revision()) return;
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

		if (hasCurrentV2CompactRevision()) {
			long[] compact = resultPageV2CompactOwner(offset, limit);
			if (compact != null) {
				recordV2PageHit(stagedGeneration);
				return compact;
			}
			synchronized (V2_KNOWN_PAGING_LOCK) {
				v2KnownPageFallbacks++;
			}
			// Candidate-free authority must never silently fall through to an empty Candidate page.
			return null;
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
		return isCurrentV2RevisionAuthoritative();
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
		clearV2CompactRevisions();
		publishV2KnownPagingStage(false, false);
	}

	static long[] inspect(long candidateId, int radius) {
		return hasCurrentV2CompactRevision()
				? inspectV2CompactOwner(candidateId, radius)
				: inspectUnchecked(candidateId, radius);
	}

	private static native long[] inspectUnchecked(long candidateId, int radius);

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
