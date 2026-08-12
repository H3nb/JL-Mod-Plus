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

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.system.OsConstants;

import org.junit.Test;

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
	public void expectedUserAndPackageManagementExitsAreSuppressed() {
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_USER_REQUESTED, 0, FOREGROUND, true));
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_USER_STOPPED, 0, FOREGROUND, true));
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_PACKAGE_UPDATED, 0, FOREGROUND, true));
		assertFalse(ProcessExitStore.shouldRetain(
				ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, 0, FOREGROUND, true));
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
}
