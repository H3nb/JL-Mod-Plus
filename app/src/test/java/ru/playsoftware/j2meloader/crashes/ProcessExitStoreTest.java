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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.system.OsConstants;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public class ProcessExitStoreTest {
	private static final int FOREGROUND = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
	private static final int CACHED = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;

	@Test
	public void crashesAndAnrsAreAlwaysRetained() {
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_CRASH, 0, CACHED, false));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_CRASH_NATIVE, OsConstants.SIGSEGV, CACHED, false));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_ANR, 0, CACHED, false));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, 0, CACHED, false));
	}

	@Test
	public void expectedUserPackageAndNonActionableSystemExitsAreSuppressed() {
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_USER_REQUESTED, 0, FOREGROUND, true));
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_USER_STOPPED, 0, FOREGROUND, true));
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_PACKAGE_UPDATED, 0, FOREGROUND, true));
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, 0, FOREGROUND, true));
		assertFalse(ProcessExitStore.shouldRetain(
				ProcessExitStore.REASON_OTHER, 0, FOREGROUND, true));
	}

	@Test
	public void sigkillRequiresMidletOrForegroundEvidence() {
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_SIGNALED, OsConstants.SIGKILL, CACHED, false));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_SIGNALED, OsConstants.SIGKILL, CACHED, true));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_SIGNALED, OsConstants.SIGKILL, FOREGROUND, false));
	}

	@Test
	public void lowMemoryKillRequiresMidletOrForegroundEvidence() {
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_LOW_MEMORY, 0, CACHED, false));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_LOW_MEMORY, 0, CACHED, true));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_LOW_MEMORY, 0, FOREGROUND, false));
	}

	@Test
	public void cleanSelfExitIsNotPromotedToCrash() {
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_EXIT_SELF, 0, FOREGROUND, true));
		assertTrue(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_EXIT_SELF, 1, FOREGROUND, true));
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

	private static byte[] bytes(int count) {
		byte[] data = new byte[count];
		for (int i = 0; i < count; i++) {
			data[i] = (byte) (i + 1);
		}
		return data;
	}
}
