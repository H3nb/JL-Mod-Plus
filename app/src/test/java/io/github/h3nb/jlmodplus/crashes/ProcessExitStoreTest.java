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

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class ProcessExitStoreTest {

	@Test
	public void onlyProvenCrashesAndAnrsCreateProcessReports() {
		for (String role : new String[]{"main", "midlet", "memory_engine", "other"}) {
			for (int reason : new int[]{ProcessExitStore.REASON_CRASH,
					ProcessExitStore.REASON_CRASH_NATIVE, ProcessExitStore.REASON_ANR}) {
				assertTrue(ProcessExitStore.shouldRetainProcess(role, reason, 0));
			}
			for (int reason : new int[]{ProcessExitStore.REASON_LOW_MEMORY,
					ProcessExitStore.REASON_UNKNOWN, ProcessExitStore.REASON_OTHER,
					ProcessExitStore.REASON_MEMORY_LIMITER, ProcessExitStore.REASON_FREEZER,
					ProcessExitStore.REASON_DEPENDENCY_DIED,
					ProcessExitStore.REASON_EXCESSIVE_RESOURCE_USAGE,
					ProcessExitStore.REASON_INITIALIZATION_FAILURE,
					ProcessExitStore.REASON_USER_REQUESTED, ProcessExitStore.REASON_USER_STOPPED,
					ProcessExitStore.REASON_PERMISSION_CHANGE,
					ProcessExitStore.REASON_PACKAGE_UPDATED,
					ProcessExitStore.REASON_PACKAGE_STATE_CHANGE, ProcessExitStore.REASON_EXIT_SELF,
					42}) {
				assertFalse("role=" + role + " reason=" + reason,
						ProcessExitStore.shouldRetainProcess(role, reason, 1));
			}
		}
		assertFalse(ProcessExitStore.shouldRetainProcess(
				"reporter", ProcessExitStore.REASON_CRASH, 0));
	}

	@Test
	public void onlyCrashClassSignalsEstablishFatalEvidence() {
		for (int signal : new int[]{ProcessExitStore.SIGNAL_ILLEGAL, ProcessExitStore.SIGNAL_TRAP,
				ProcessExitStore.SIGNAL_ABORT, ProcessExitStore.SIGNAL_BUS,
				ProcessExitStore.SIGNAL_FPE, ProcessExitStore.SIGNAL_SEGV}) {
			assertTrue(ProcessExitStore.shouldRetain(ProcessExitStore.REASON_SIGNALED, signal));
		}
		for (int signal : new int[]{0, 1, ProcessExitStore.SIGNAL_KILL,
				ProcessExitStore.SIGNAL_TERM, 42}) {
			assertFalse(ProcessExitStore.shouldRetain(ProcessExitStore.REASON_SIGNALED, signal));
		}
	}

	@Test
	public void oldNonfatalProjectionIsExcludedUnderCurrentPolicy() {
		String packageName = "io.github.h3nb.jlmodplus.debug";
		String processName = packageName + ":midlet";
		assertEquals(null, ProcessExitStore.retainedStoredProcessRole(
				packageName, processName, "other", ProcessExitStore.REASON_LOW_MEMORY, 0));
		assertEquals(null, ProcessExitStore.retainedStoredProcessRole(
				packageName, processName, "other", ProcessExitStore.REASON_SIGNALED,
				ProcessExitStore.SIGNAL_KILL));
		assertEquals("midlet", ProcessExitStore.retainedStoredProcessRole(
				packageName, processName, "other", ProcessExitStore.REASON_CRASH, 0));
	}

	@Test
	public void controlledKillSupportsOnlyTheExactRecordedFatalSession() {
		MidletSessionJournal.Snapshot clean = session(MidletSessionJournal.Outcome.USER_STOP);
		MidletSessionJournal.Snapshot fatal = new MidletSessionJournal.Snapshot(
				2, clean.sessionId, clean.processName, clean.processPid,
				1L, 1L, 2L, 2L, MidletSessionJournal.Stage.COMPLETED,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				"223e4567-e89b-12d3-a456-426614174000",
				MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD,
				"Game", "Vendor", "1.0", "game.Main", "1", "abc123");
		assertTrue(ProcessExitStore.isFatalSessionShutdown(fatal, fatal.sessionId,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, "midlet"));
		assertFalse(ProcessExitStore.isFatalSessionShutdown(fatal, null,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, "midlet"));
		assertFalse(ProcessExitStore.isFatalSessionShutdown(clean, clean.sessionId,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, "midlet"));
		assertFalse(ProcessExitStore.isFatalSessionShutdown(fatal, fatal.sessionId,
				ProcessExitStore.REASON_LOW_MEMORY, 0, "midlet"));
		assertFalse(ProcessExitStore.isFatalSessionShutdown(fatal, fatal.sessionId,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, "memory_engine"));
	}

	@Test
	public void intentionalSessionSuppressesOnlyExactControlledSigkill() {
		MidletSessionJournal.Snapshot session = session(MidletSessionJournal.Outcome.USER_STOP);

		assertTrue(ProcessExitStore.isExpectedIntentionalSessionExit(
				session, session.sessionId,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, "midlet"));
		assertFalse(ProcessExitStore.isExpectedIntentionalSessionExit(
				session, session.sessionId,
				ProcessExitStore.REASON_CRASH, 0, "midlet"));
		assertFalse(ProcessExitStore.isExpectedIntentionalSessionExit(
				session, null,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, "midlet"));
	}

	@Test
	public void boundedCopyPreservesEmptyInput() throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		ProcessExitStore.TraceWriteResult result = ProcessExitStore.copyBounded(
				new ByteArrayInputStream(new byte[0]), output, 8);

		assertEquals(0, result.bytes);
		assertFalse(result.truncated);
		assertEquals(0, output.size());
	}

	@Test
	public void boundedCopyPreservesInputBelowLimit() throws IOException {
		byte[] input = bytes(7);
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		ProcessExitStore.TraceWriteResult result = ProcessExitStore.copyBounded(
				new ByteArrayInputStream(input), output, 8);

		assertEquals(input.length, result.bytes);
		assertFalse(result.truncated);
		assertArrayEquals(input, output.toByteArray());
	}

	@Test
	public void boundedCopyDoesNotMarkExactLimitAsTruncated() throws IOException {
		byte[] input = bytes(8);
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		ProcessExitStore.TraceWriteResult result = ProcessExitStore.copyBounded(
				new ByteArrayInputStream(input), output, 8);

		assertEquals(8, result.bytes);
		assertFalse(result.truncated);
		assertArrayEquals(input, output.toByteArray());
	}

	@Test
	public void boundedCopyCapsAndMarksInputOverLimit() throws IOException {
		byte[] input = bytes(9);
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		ProcessExitStore.TraceWriteResult result = ProcessExitStore.copyBounded(
				new ByteArrayInputStream(input), output, 8);

		assertEquals(8, result.bytes);
		assertTrue(result.truncated);
		assertArrayEquals(Arrays.copyOf(input, 8), output.toByteArray());
	}

	@Test
	public void boundedCopyWithZeroLimitOnlyProbesTruncation() throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		ProcessExitStore.TraceWriteResult empty = ProcessExitStore.copyBounded(
				new ByteArrayInputStream(new byte[0]), output, 0);
		assertEquals(0, empty.bytes);
		assertFalse(empty.truncated);
		assertEquals(0, output.size());

		ProcessExitStore.TraceWriteResult nonEmpty = ProcessExitStore.copyBounded(
				new ByteArrayInputStream(bytes(1)), output, 0);
		assertEquals(0, nonEmpty.bytes);
		assertTrue(nonEmpty.truncated);
		assertEquals(0, output.size());
	}

	@Test
	public void boundedCopyPropagatesReadFailure() {
		InputStream failing = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("synthetic read failure");
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				throw new IOException("synthetic read failure");
			}
		};

		assertThrows(IOException.class, () -> ProcessExitStore.copyBounded(
				failing, new ByteArrayOutputStream(), 8));
	}

	@Test
	public void boundedCopyPropagatesWriteFailure() {
		OutputStream failing = new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				throw new IOException("synthetic write failure");
			}

			@Override
			public void write(byte[] buffer, int offset, int length) throws IOException {
				throw new IOException("synthetic write failure");
			}
		};

		assertThrows(IOException.class, () -> ProcessExitStore.copyBounded(
				new ByteArrayInputStream(bytes(4)), failing, 8));
	}

	@Test
	public void boundedCopyPropagatesOutOfMemory() {
		InputStream failing = new InputStream() {
			@Override
			public int read() {
				throw new OutOfMemoryError("synthetic low-memory failure");
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				throw new OutOfMemoryError("synthetic low-memory failure");
			}
		};

		assertThrows(OutOfMemoryError.class, () -> ProcessExitStore.copyBounded(
				failing, new ByteArrayOutputStream(), 8));
	}

	private static MidletSessionJournal.Snapshot session(MidletSessionJournal.Outcome outcome) {
		return new MidletSessionJournal.Snapshot(
				2,
				"123e4567-e89b-12d3-a456-426614174000",
				"io.github.h3nb.jlmodplus:midlet",
				123,
				1L,
				1L,
				2L,
				2L,
				MidletSessionJournal.Stage.COMPLETED,
				outcome,
				null,
				null,
				"Game",
				"Vendor",
				"1.0",
				"game.Main",
				"1",
				"abc123");
	}

	private static byte[] bytes(int count) {
		byte[] data = new byte[count];
		for (int i = 0; i < count; i++) {
			data[i] = (byte) (i + 1);
		}
		return data;
	}
}
