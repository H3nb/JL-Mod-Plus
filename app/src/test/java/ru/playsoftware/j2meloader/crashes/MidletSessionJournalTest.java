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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MidletSessionJournalTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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
				"event-1",
				MidletSessionJournal.FailureBoundary.LIFECYCLE_START,
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
		assertEquals(expected.failureEventId, actual.failureEventId);
		assertEquals(expected.failureBoundary, actual.failureBoundary);
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
		assertNull(actual.failureEventId);
		assertNull(actual.failureBoundary);
		assertNull(actual.midletName);
		assertNull(actual.jarSize);
	}

	@Test
	public void legacySchemaOneWithoutFailureFieldsStillReads() throws Exception {
		MidletSessionJournal.Snapshot actual = MidletSessionJournal.read(
				new ByteArrayInputStream(validProperties().getBytes(StandardCharsets.UTF_8)));

		assertEquals(MidletSessionJournal.SCHEMA_VERSION, actual.schemaVersion);
		assertEquals("session-3", actual.sessionId);
		assertEquals(MidletSessionJournal.Stage.RUNNING, actual.stage);
		assertEquals(MidletSessionJournal.Outcome.NONE, actual.outcome);
		assertNull(actual.failureEventId);
		assertNull(actual.failureBoundary);
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

	@Test
	public void invalidOptionalFailureBoundaryIsRejected() throws Exception {
		String data = validProperties() + "failureEventId=event-2\n"
				+ "failureBoundary=NOT_A_BOUNDARY\n";
		assertReadFails(data);
	}

	@Test
	public void atomicSidecarsCollapseToOneCanonicalJournal() throws Exception {
		File root = temporaryFolder.getRoot();
		File backup = temporaryFolder.newFile("session.properties.bak");
		File pending = temporaryFolder.newFile("session.properties.new");
		File unrelated = temporaryFolder.newFile("notes.txt");

		List<File> journals = MidletSessionJournal.canonicalJournalFiles(
				Arrays.asList(pending, unrelated, backup));

		assertEquals(1, journals.size());
		assertEquals(new File(root, "session.properties").getAbsolutePath(),
				journals.get(0).getAbsolutePath());
	}

	@Test
	public void deletingJournalAlsoDeletesAtomicSidecars() throws Exception {
		File base = temporaryFolder.newFile("session-delete.properties");
		File backup = temporaryFolder.newFile("session-delete.properties.bak");
		File pending = temporaryFolder.newFile("session-delete.properties.new");

		assertTrue(MidletSessionJournal.delete(base));
		assertFalse(base.exists());
		assertFalse(backup.exists());
		assertFalse(pending.exists());
	}

	@Test
	public void retentionDeletesExpiredBackupOnlyJournal() throws Exception {
		long now = 1_000_000L;
		File base = new File(temporaryFolder.getRoot(), "backup-only.properties");
		File backup = temporaryFolder.newFile("backup-only.properties.bak");
		if (!backup.setLastModified(now - 10_000L)) {
			throw new IOException("Unable to set backup journal timestamp");
		}

		MidletSessionJournal.pruneFiles(
				Arrays.asList(base), now, 10, 2_000L, 100L);

		assertFalse(backup.exists());
	}

	@Test
	public void retentionKeepsNewestJournalsWithinCountLimit() throws Exception {
		long now = 1_000_000L;
		List<File> journals = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			journals.add(createJournalFile("journal-" + i, now - 10_000L - i));
		}

		MidletSessionJournal.pruneFiles(journals, now, 3, 100_000L, 1_000L);

		assertEquals(3, existingCount(journals));
		assertTrue(journals.get(0).exists());
		assertTrue(journals.get(1).exists());
		assertTrue(journals.get(2).exists());
	}

	@Test
	public void retentionPreservesRecentlyUpdatedJournals() throws Exception {
		long now = 1_000_000L;
		List<File> journals = Arrays.asList(
				createJournalFile("recent-1", now - 100L),
				createJournalFile("recent-2", now - 200L),
				createJournalFile("recent-3", now - 300L)
		);

		MidletSessionJournal.pruneFiles(journals, now, 1, 100L, 1_000L);

		assertEquals(3, existingCount(journals));
	}

	@Test
	public void retentionDeletesExpiredJournalEvenBelowCountLimit() throws Exception {
		long now = 1_000_000L;
		File recent = createJournalFile("recent", now - 500L);
		File expired = createJournalFile("expired", now - 10_000L);
		List<File> journals = Arrays.asList(recent, expired);

		MidletSessionJournal.pruneFiles(journals, now, 10, 2_000L, 100L);

		assertTrue(recent.exists());
		assertFalse(expired.exists());
	}

	@Test
	public void retentionIgnoresDirectoriesWithoutMutatingCallerList() throws Exception {
		long now = 1_000_000L;
		File newest = createJournalFile("newest", now - 10_000L);
		File older = createJournalFile("older", now - 20_000L);
		File directory = temporaryFolder.newFolder("not-a-journal");
		List<File> journals = Arrays.asList(older, null, directory, newest);
		List<File> originalOrder = new ArrayList<>(journals);

		MidletSessionJournal.pruneFiles(journals, now, 1, 100_000L, 1_000L);

		assertEquals(originalOrder, journals);
		assertTrue(newest.exists());
		assertFalse(older.exists());
		assertTrue(directory.exists());
	}

	private File createJournalFile(String name, long modified) throws IOException {
		File file = temporaryFolder.newFile(name);
		if (!file.setLastModified(modified)) {
			throw new IOException("Unable to set journal timestamp");
		}
		return file;
	}

	private static int existingCount(List<File> files) {
		int count = 0;
		for (File file : files) {
			if (file != null && file.exists()) {
				count++;
			}
		return count;
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
