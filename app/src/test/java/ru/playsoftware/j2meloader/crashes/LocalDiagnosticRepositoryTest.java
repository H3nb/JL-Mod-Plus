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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalDiagnosticRepositoryTest {
	@Test
	public void exactSessionAndEventAreCorrelated() {
		assertTrue(LocalDiagnosticRepository.isExactEventMatch(
				"session-1",
				"event-1",
				"session-1",
				"java.lang.RuntimeException: JL-Mod Plus session failure; eventId=event-1; boundary=LIFECYCLE_START"));
	}

	@Test
	public void sameSessionWithoutEventMarkerIsNotMerged() {
		assertFalse(LocalDiagnosticRepository.isExactEventMatch(
				"session-1",
				"event-1",
				"session-1",
				"java.lang.OutOfMemoryError"));
	}

	@Test
	public void matchingEventFromDifferentSessionIsNotMerged() {
		assertFalse(LocalDiagnosticRepository.isExactEventMatch(
				"session-1",
				"event-1",
				"session-2",
				"eventId=event-1; boundary=UNCAUGHT_THREAD"));
	}

	@Test
	public void eventPrefixDoesNotCauseFalseMatch() {
		assertFalse(LocalDiagnosticRepository.isExactEventMatch(
				"session-1",
				"event-1",
				"session-1",
				"eventId=event-10; boundary=UNCAUGHT_THREAD"));
	}

	@Test
	public void unsafeEventIdDoesNotCorrelate() {
		assertFalse(LocalDiagnosticRepository.isExactEventMatch(
				"session-1",
				"event/../1",
				"session-1",
				"eventId=event/../1; boundary=UNCAUGHT_THREAD"));
	}

	@Test
	public void missingFieldsDoNotCorrelate() {
		assertFalse(LocalDiagnosticRepository.isExactEventMatch(null, "event-1", "session-1", "eventId=event-1;"));
		assertFalse(LocalDiagnosticRepository.isExactEventMatch("session-1", null, "session-1", "eventId=event-1;"));
		assertFalse(LocalDiagnosticRepository.isExactEventMatch("session-1", "event-1", null, "eventId=event-1;"));
		assertFalse(LocalDiagnosticRepository.isExactEventMatch("session-1", "event-1", "session-1", null));
	}
}
