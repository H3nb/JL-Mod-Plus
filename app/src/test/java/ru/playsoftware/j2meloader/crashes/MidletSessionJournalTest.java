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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MidletSessionJournalTest {
	@Test
	public void roundTripPreservesDiagnosticFields() throws Exception {
		MidletSessionJournal.Snapshot expected = new MidletSessionJournal.Snapshot(
				MidletSessionJournal.SCHEMA_VERSION,
				"session-1",
				"ru.playsoftware.j2meloader:midlet",
				1234,
				1000L,
				2000L,
				3000L,
				4000L,
				MidletSessionJournal.Stage.STARTING,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				"Game",
				"Vendor",
				"1.0",
				"game.Main",
				"123456",
				"abcdef"
		);

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		MidletSessionJournal.write(expected, output);
		MidletSessionJournal.Snapshot actual = MidletSessionJournal.read(
				new ByteArrayInputStream(output.toByteArray()));

		assertEquals(expected.schemaVersion, actual.schemaVersion);
		assertEquals(expected.sessionId, actual.sessionId);
		assertEquals(expected.processName, actual.processName);
		assertEquals(expected.processPid, actual.processPid);
		assertEquals(expected.startedWallTimeMillis, actual.startedWallTimeMillis);
		assertEquals(expected.startedElapsedRealtimeMillis, actual.startedElapsedRealtimeMillis);
		assertEquals(expected.updatedWallTimeMillis, actual.updatedWallTimeMillis);
		assertEquals(expected.updatedElapsedRealtimeMillis, actual.updatedElapsedRealtimeMillis);
		assertEquals(expected.stage, actual.stage);
		assertEquals(expected.outcome, actual.outcome);
		assertEquals(expected.midletName, actual.midletName);
		assertEquals(expected.midletVendor, actual.midletVendor);
		assertEquals(expected.midletVersion, actual.midletVersion);
		assertEquals(expected.mainClass, actual.mainClass);
		assertEquals(expected.jarSize, actual.jarSize);
		assertEquals(expected.jarSha256, actual.jarSha256);
	}

	@Test
	public void optionalMetadataMayBeAbsent() throws Exception {
		MidletSessionJournal.Snapshot expected = new MidletSessionJournal.Snapshot(
				MidletSessionJournal.SCHEMA_VERSION,
				"session-2",
				null,
				55,
				1L,
				2L,
				3L,
				4L,
				MidletSessionJournal.Stage.PREPARING,
				MidletSessionJournal.Outcome.NONE,
				null,
				null,
				null,
				"game.Main",
				null,
				null
		);

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		MidletSessionJournal.write(expected, output);
		MidletSessionJournal.Snapshot actual = MidletSessionJournal.read(
				new ByteArrayInputStream(output.toByteArray()));

		assertNull(actual.processName);
		assertNull(actual.midletName);
		assertNull(actual.jarSize);
	}

	@Test
	public void futureSchemaIsRejected() throws Exception {
		String data = validProperties().replace("schemaVersion=1", "schemaVersion=2");
		assertReadFails(data);
	}

	@Test
	public void missingRequiredFieldIsRejected() throws Exception {
		String data = validProperties().replace("sessionId=session-3\n", "");
		assertReadFails(data);
	}

	@Test
	public void invalidLifecycleEnumIsRejected() throws Exception {
		String data = validProperties().replace("stage=RUNNING", "stage=NOT_A_STAGE");
		assertReadFails(data);
	}

	private static String validProperties() {
		return "schemaVersion=1\n"
				+ "sessionId=session-3\n"
				+ "processPid=100\n"
				+ "startedWallTimeMillis=1\n"
				+ "startedElapsedRealtimeMillis=2\n"
				+ "updatedWallTimeMillis=3\n"
				+ "updatedElapsedRealtimeMillis=4\n"
				+ "stage=RUNNING\n"
				+ "outcome=NONE\n";
	}

	private static void assertReadFails(String data) throws Exception {
		try {
			MidletSessionJournal.read(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
			fail("Expected IOException");
		} catch (IOException expected) {
			// Expected: corrupt or future journal data must not be treated as a valid snapshot.
		}
	}
}
