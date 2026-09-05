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

/**
 * Pure policy helpers for translating ART GC observations into Memory Editor trust decisions.
 * A GC-count change is a stale-address hint, not by itself a user-visible failure.
 */
final class MemoryGcPolicy {
	private MemoryGcPolicy() {
	}

	static boolean firstSearchRequiresStableEpoch(boolean addressSnapshotBaseline,
	                                             long gcBefore, long gcAfter) {
		return addressSnapshotBaseline &&
				MemoryEngineContract.didGcCountChange(gcBefore, gcAfter);
	}

	/**
	 * Known-value searches may be published across a GC. Keep the older known epoch in that case so
	 * later CandidateId use is treated as stale and revalidated before identity-sensitive work.
	 */
	static long publishedSearchEpoch(boolean addressSnapshotBaseline,
	                                long gcBefore, long gcAfter) {
		if (!addressSnapshotBaseline &&
				MemoryEngineContract.didGcCountChange(gcBefore, gcAfter) &&
				MemoryEngineContract.isKnownGcCount(gcBefore)) {
			return gcBefore;
		}
		return MemoryEngineContract.latestKnownGcCount(gcBefore, gcAfter);
	}

	/**
	 * A fresh target-range snapshot is a fallback, not the first response to a GC-count change.
	 * Retry when the identity-aware attempt proves the old binding/ranges unsafe, or when GC moves
	 * again during that verification window. This remains valid when ART GC statistics are absent.
	 */
	static boolean shouldRetryReadWithFreshRanges(int fastRefreshResult,
	                                             int bindingResult,
	                                             boolean gcChangedDuringFastRefresh) {
		if (gcChangedDuringFastRefresh) return true;
		if (fastRefreshResult == MemoryEngineContract.RESULT_IDENTITY_UNSAFE ||
				fastRefreshResult == MemoryEngineContract.RESULT_TARGET_LOST) {
			return true;
		}
		return fastRefreshResult == MemoryEngineContract.RESULT_OK &&
				bindingResult == MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
	}

	/**
	 * A unique relocation is safe to carry into a guarded mutation. Native refresh has already
	 * verified the candidate identity, and the mutation performs the same verification once more
	 * immediately before writing. Ambiguous or lost bindings must still fail closed.
	 */
	static boolean mutationBindingIsReady(int bindingResult) {
		return bindingResult == MemoryEngineContract.RESULT_OK ||
				bindingResult == MemoryEngineContract.RESULT_GC_REVALIDATED;
	}

	/** A successful or partial mutation may already have changed target memory. */
	static boolean mutationMayHaveWritten(int result) {
		return result == MemoryEngineContract.RESULT_OK ||
				result == MemoryEngineContract.RESULT_PARTIAL_WRITE;
	}

	/**
	 * If GC moves during any mutation that may have written bytes, the final binding cannot be
	 * confirmed safely. Never auto-retry the write; report a GC race instead.
	 */
	static boolean shouldReportGcRaceAfterMutation(int result, long gcBefore, long gcAfter) {
		return mutationMayHaveWritten(result) &&
				MemoryEngineContract.didGcCountChange(gcBefore, gcAfter);
	}

	/** Freeze records recover independently when their last validated epoch becomes stale. */
	static boolean freezeRecordNeedsRecovery(long validatedGcCount, long currentGcCount) {
		return MemoryEngineContract.didGcCountChange(validatedGcCount, currentGcCount);
	}
}
