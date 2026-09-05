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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks only epoch metadata needed to decide whether a Java-heap address binding can still be
 * trusted. Ordinary search results inherit the search revision epoch and are not inserted into the
 * per-candidate map until a read/write/tracking action actually revalidates them.
 */
final class MemoryGcBindingTracker {
	private final Map<Long, Long> candidateEpochs = new ConcurrentHashMap<>();
	private volatile long searchEpoch = MemoryEngineContract.GC_COUNT_UNKNOWN;

	long searchEpoch() {
		return searchEpoch;
	}

	void setSearchEpoch(long gcCount) {
		searchEpoch = MemoryEngineContract.isKnownGcCount(gcCount)
				? gcCount : MemoryEngineContract.GC_COUNT_UNKNOWN;
	}

	boolean searchEpochChanged(long currentGcCount) {
		return MemoryEngineContract.didGcCountChange(searchEpoch, currentGcCount);
	}

	boolean candidatesNeedRevalidation(long[] candidateIds, long currentGcCount) {
		if (!MemoryEngineContract.isKnownGcCount(currentGcCount) || candidateIds == null) {
			return false;
		}
		for (long candidateId : candidateIds) {
			if (candidateId <= 0L) continue;
			Long explicitEpoch = candidateEpochs.get(candidateId);
			long trustedEpoch = explicitEpoch == null ? searchEpoch : explicitEpoch;
			if (MemoryEngineContract.didGcCountChange(trustedEpoch, currentGcCount)) {
				return true;
			}
		}
		return false;
	}

	void markCandidatesValidated(long[] candidateIds, long gcCount) {
		if (!MemoryEngineContract.isKnownGcCount(gcCount) || candidateIds == null) return;
		for (long candidateId : candidateIds) {
			if (candidateId > 0L) candidateEpochs.put(candidateId, gcCount);
		}
	}

	long candidateEpoch(long candidateId) {
		Long explicitEpoch = candidateEpochs.get(candidateId);
		return explicitEpoch == null ? searchEpoch : explicitEpoch;
	}

	void forgetCandidate(long candidateId) {
		candidateEpochs.remove(candidateId);
	}

	void clearSearchEpoch() {
		searchEpoch = MemoryEngineContract.GC_COUNT_UNKNOWN;
	}

	void clearAll() {
		candidateEpochs.clear();
		clearSearchEpoch();
	}
}
