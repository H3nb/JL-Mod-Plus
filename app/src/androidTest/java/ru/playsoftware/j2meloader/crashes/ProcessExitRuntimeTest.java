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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class ProcessExitRuntimeTest {
	private static final long REPORT_TIMEOUT_MILLIS = 20_000L;
	private static final long PROCESS_TIMEOUT_MILLIS = 10_000L;

	@Test
	public void abruptRemoteSignalDeathIsCapturedWithoutJavaException() {
		// ApplicationExitInfo is public from Android 11. API23-29 exercise the existing Java/journal
		// containment suite in CI, but cannot truthfully classify this process death via public API.
		assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);

		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String mainProcessName = context.getPackageName();
		String midletProcessName = mainProcessName + ":midlet";
		int mainPid = Process.myPid();
		Set<String> baselineIds = recordIds(LocalDiagnosticRepository.load(context));

		try {
			Intent intent = new Intent(context, CrashRuntimeProbeActivity.class)
					.putExtra(CrashRuntimeProbeActivity.EXTRA_MODE, CrashRuntimeProbeActivity.MODE_SIGNAL_KILL)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(intent);

			LocalDiagnosticRepository.Record record = awaitSignalExitRecord(context, baselineIds);
			awaitRemoteProcessStops(context, midletProcessName);
			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));

			assertEquals(LocalDiagnosticRepository.Kind.PROCESS_EXIT, record.getKind());
			assertEquals(CrashRuntimeProbeActivity.SIGNAL_MIDLET_NAME, record.getMidletName());
			assertEquals("midlet", record.getProcessRole());
			assertNotNull(record.getSessionId());
			assertTrue(record.hasProcessExit());
			assertFalse(record.hasJavaReport());
			assertTrue(record.getDetailText().contains("Exit reason: Signal termination"));
			assertTrue(record.getDetailText().contains("SIGKILL"));
			assertTrue(record.getDetailText().contains("Lifecycle stage: RUNNING"));
			assertTrue(record.getDetailText().contains("Session outcome: NONE"));
		} finally {
			cleanupSignalDiagnostics(context, baselineIds);
		}
	}

	private static LocalDiagnosticRepository.Record awaitSignalExitRecord(
			Context context, Set<String> existingIds) {
		long deadline = SystemClock.uptimeMillis() + REPORT_TIMEOUT_MILLIS;
		do {
			for (LocalDiagnosticRepository.Record record : LocalDiagnosticRepository.load(context)) {
				if (!existingIds.contains(record.getId())
						&& record.getKind() == LocalDiagnosticRepository.Kind.PROCESS_EXIT
						&& CrashRuntimeProbeActivity.SIGNAL_MIDLET_NAME.equals(record.getMidletName())) {
					return record;
				}
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("Timed out waiting for abrupt MIDlet process-exit diagnostic");
		return null;
	}

	private static void awaitRemoteProcessStops(Context context, String processName) {
		long deadline = SystemClock.uptimeMillis() + PROCESS_TIMEOUT_MILLIS;
		while (SystemClock.uptimeMillis() < deadline) {
			if (processPid(context, processName) == 0) {
				return;
			}
			SystemClock.sleep(100L);
		}
		fail("Remote MIDlet process is still alive after abrupt signal death");
	}

	private static int processPid(Context context, String processName) {
		ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
		if (processes == null) {
			return 0;
		}
		for (ActivityManager.RunningAppProcessInfo process : processes) {
			if (processName.equals(process.processName)) {
				return process.pid;
			}
		}
		return 0;
	}

	private static Set<String> recordIds(List<LocalDiagnosticRepository.Record> records) {
		HashSet<String> ids = new HashSet<>();
		for (LocalDiagnosticRepository.Record record : records) {
			ids.add(record.getId());
		}
		return ids;
	}

	private static void cleanupSignalDiagnostics(Context context, Set<String> baselineIds) {
		for (LocalDiagnosticRepository.Record record : LocalDiagnosticRepository.load(context)) {
			if (!baselineIds.contains(record.getId())
					&& CrashRuntimeProbeActivity.SIGNAL_MIDLET_NAME.equals(record.getMidletName())) {
				LocalDiagnosticRepository.delete(context, record);
			}
		}
	}
}
