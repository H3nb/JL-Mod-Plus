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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
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
public class CrashRuntimeIsolationTest {
	private static final long REPORT_TIMEOUT_MILLIS = 20_000L;
	private static final long PROCESS_TIMEOUT_MILLIS = 10_000L;

	@Test
	public void repeatedRemoteSessionCrashesKeepMainProcessAndPersistExactReports() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String mainProcessName = context.getPackageName();
		String midletProcessName = mainProcessName + ":midlet";
		int mainPid = Process.myPid();
		Set<String> baselineIds = recordIds(LocalDiagnosticRepository.load(context));
		LocalDiagnosticRepository.Record first = null;
		LocalDiagnosticRepository.Record second = null;

		try {
			assertEquals(mainProcessName, ru.playsoftware.j2meloader.EmulatorApplication.getProcessName());
			assertEquals(mainPid, processPid(context, mainProcessName));

			first = launchProbeAndAwaitCorrelatedRecord(context, baselineIds);
			assertRemoteProcessStops(context, midletProcessName);
			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));
			assertTrue(first.hasJavaReport());
			assertNotNull(first.getEventId());
			assertEquals(CrashRuntimeProbeActivity.MIDLET_NAME, first.getMidletName());
			assertTrue(first.getDetailText().contains("Failure boundary: UNCAUGHT_THREAD"));

			Set<String> afterFirstIds = recordIds(LocalDiagnosticRepository.load(context));
			second = launchProbeAndAwaitCorrelatedRecord(context, afterFirstIds);
			assertRemoteProcessStops(context, midletProcessName);
			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));
			assertTrue(second.hasJavaReport());
			assertNotNull(second.getEventId());
			assertNotEquals(first.getEventId(), second.getEventId());
			assertEquals(CrashRuntimeProbeActivity.MIDLET_NAME, second.getMidletName());
		} finally {
			if (first != null) {
				LocalDiagnosticRepository.delete(context, first);
			}
			if (second != null) {
				LocalDiagnosticRepository.delete(context, second);
			}
		}
	}

	private static LocalDiagnosticRepository.Record launchProbeAndAwaitCorrelatedRecord(
			Context context, Set<String> existingIds) {
		Intent intent = new Intent(context, CrashRuntimeProbeActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);

		long deadline = SystemClock.uptimeMillis() + REPORT_TIMEOUT_MILLIS;
		do {
			List<LocalDiagnosticRepository.Record> records = LocalDiagnosticRepository.load(context);
			for (LocalDiagnosticRepository.Record record : records) {
				if (!existingIds.contains(record.getId())
						&& record.getKind() == LocalDiagnosticRepository.Kind.MIDLET_FAILURE
						&& CrashRuntimeProbeActivity.MIDLET_NAME.equals(record.getMidletName())
						&& record.hasJavaReport()) {
					return record;
				}
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);

		fail("Timed out waiting for exact-correlated remote crash report");
		return null;
	}

	private static void assertRemoteProcessStops(Context context, String processName) {
		long deadline = SystemClock.uptimeMillis() + PROCESS_TIMEOUT_MILLIS;
		while (SystemClock.uptimeMillis() < deadline) {
			if (processPid(context, processName) == 0) {
				return;
			}
			SystemClock.sleep(100L);
		}
		assertFalse("Remote crash process is still alive", processPid(context, processName) != 0);
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
		Set<String> ids = new HashSet<>();
		for (LocalDiagnosticRepository.Record record : records) {
			ids.add(record.getId());
		}
		return ids;
	}
}
