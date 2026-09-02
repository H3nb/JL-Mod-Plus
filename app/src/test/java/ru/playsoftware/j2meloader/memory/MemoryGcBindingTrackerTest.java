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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MemoryGcBindingTrackerTest {
	@Test
	public void ordinaryResultsInheritSearchEpochUntilPromoted() {
		MemoryGcBindingTracker tracker = new MemoryGcBindingTracker();
		tracker.setSearchEpoch(10L);

		assertEquals(10L, tracker.candidateEpoch(100L));
		assertFalse(tracker.candidatesNeedRevalidation(new long[]{100L}, 10L));
		assertTrue(tracker.candidatesNeedRevalidation(new long[]{100L}, 11L));
	}

	@Test
	public void promotedCandidateCanBeRevalidatedAtNewerGcEpoch() {
		MemoryGcBindingTracker tracker = new MemoryGcBindingTracker();
		tracker.setSearchEpoch(10L);
		tracker.markCandidatesValidated(new long[]{100L}, 11L);

		assertEquals(11L, tracker.candidateEpoch(100L));
		assertFalse(tracker.candidatesNeedRevalidation(new long[]{100L}, 11L));
		assertTrue(tracker.candidatesNeedRevalidation(new long[]{100L}, 12L));
		assertTrue(tracker.searchEpochChanged(11L));
	}

	@Test
	public void forgottenTrackedCandidateFallsBackToCurrentSearchEpoch() {
		MemoryGcBindingTracker tracker = new MemoryGcBindingTracker();
		tracker.setSearchEpoch(20L);
		tracker.markCandidatesValidated(new long[]{100L}, 21L);
		assertEquals(21L, tracker.candidateEpoch(100L));

		tracker.forgetCandidate(100L);
		assertEquals(20L, tracker.candidateEpoch(100L));
		assertTrue(tracker.candidatesNeedRevalidation(new long[]{100L}, 21L));
	}

	@Test
	public void unavailableGcStatisticDoesNotInventRelocationEvidence() {
		MemoryGcBindingTracker tracker = new MemoryGcBindingTracker();
		tracker.setSearchEpoch(MemoryEngineContract.GC_COUNT_UNKNOWN);

		assertFalse(tracker.searchEpochChanged(5L));
		assertFalse(tracker.candidatesNeedRevalidation(new long[]{100L}, 5L));

		tracker.setSearchEpoch(5L);
		assertFalse(tracker.candidatesNeedRevalidation(
				new long[]{100L}, MemoryEngineContract.GC_COUNT_UNKNOWN));
	}
}
