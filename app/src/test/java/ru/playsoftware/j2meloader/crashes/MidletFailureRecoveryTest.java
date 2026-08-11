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

package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MidletFailureRecoveryTest {
	@Test
	public void newestUnacknowledgedFailureWins() {
		MidletSessionJournal.Snapshot older = failure("session-a", "event-a", 100L);
		MidletSessionJournal.Snapshot newer = failure("session-b", "event-b", 200L);

		MidletFailureRecovery.PendingFailure pending = MidletFailureRecovery.selectNewestPending(
				Arrays.asList(older, newer), Collections.emptySet());

		assertEquals("event-b", pending.getEventId());
		assertEquals("session-b", pending.getSessionId());
	}

	@Test
	public void acknowledgedNewestFallsBackToOlderPendingFailure() {
		MidletSessionJournal.Snapshot older = failure("session-a", "event-a", 100L);
		MidletSessionJournal.Snapshot newer = failure("session-b", "event-b", 200L);
		Set<String> acknowledged = new HashSet<>(Collections.singletonList("event-b"));

		MidletFailureRecovery.PendingFailure pending = MidletFailureRecovery.selectNewestPending(
				Arrays.asList(older, newer), acknowledged);

		assertEquals("event-a", pending.getEventId());
	}

	@Test
	public void normalOrIncompleteSessionsAreNotSurfacedAsFailures() {
		MidletSessionJournal.Snapshot running = snapshot(
				"session-running", null, 300L,
				MidletSessionJournal.Outcome.NONE, null);
		MidletSessionJournal.Snapshot completed = snapshot(
				"session-complete", null, 400L,
				MidletSessionJournal.Outcome.USER_STOP, null);

		assertNull(MidletFailureRecovery.selectNewestPending(
				Arrays.asList(running, completed), Collections.emptySet()));
	}

	@Test
	public void failureWithoutSafeEventIdIsIgnored() {
		MidletSessionJournal.Snapshot invalid = snapshot(
				"session-invalid", "../event", 500L,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD);

		assertNull(MidletFailureRecovery.selectNewestPending(
				Collections.singletonList(invalid), Collections.emptySet()));
	}

	@Test
	public void retainedFailureEventIdsExcludeNonFailuresAndInvalidIds() {
		List<MidletSessionJournal.Snapshot> snapshots = Arrays.asList(
				failure("session-a", "event-a", 100L),
				snapshot("session-normal", null, 200L,
						MidletSessionJournal.Outcome.MIDLET_REQUEST, null),
				snapshot("session-invalid", "bad/event", 300L,
						MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
						MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD)
		);

		Set<String> ids = MidletFailureRecovery.collectFailureEventIds(snapshots);

		assertEquals(1, ids.size());
		assertTrue(ids.contains("event-a"));
	}

	@Test
	public void equalTimestampUsesStableSessionTieBreak() {
		MidletSessionJournal.Snapshot first = failure("session-a", "event-a", 100L);
		MidletSessionJournal.Snapshot second = failure("session-b", "event-b", 100L);

		MidletFailureRecovery.PendingFailure pending = MidletFailureRecovery.selectNewestPending(
				Arrays.asList(second, first), Collections.emptySet());

		assertEquals("session-b", pending.getSessionId());
	}

	private static MidletSessionJournal.Snapshot failure(String sessionId, String eventId,
			long updatedWallTimeMillis) {
		return snapshot(sessionId, eventId, updatedWallTimeMillis,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD);
	}

	private static MidletSessionJournal.Snapshot snapshot(String sessionId, String eventId,
			long updatedWallTimeMillis, MidletSessionJournal.Outcome outcome,
			MidletSessionJournal.FailureBoundary boundary) {
		return new MidletSessionJournal.Snapshot(
				MidletSessionJournal.SCHEMA_VERSION,
				sessionId,
				"ru.playsoftware.j2meloader:midlet",
				123,
				1L,
				2L,
				updatedWallTimeMillis,
				4L,
				MidletSessionJournal.Stage.RUNNING,
				outcome,
				eventId,
				boundary,
				"Game " + sessionId,
				"Vendor",
				"1.0",
				"game.Main",
				"1000",
				"abcdef"
		);
	}
}
