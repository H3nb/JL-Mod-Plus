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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class ProcessExitRuntimeTest {
	private static final long REPORT_TIMEOUT_MILLIS = 20_000L;
	private static final long PROCESS_TIMEOUT_MILLIS = 10_000L;

	@Test
	public void newerNonfatalEvidenceStaysQueryableWithoutHidingFatalNotice() throws Exception {
		Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
		TemporaryFolder fixture = new TemporaryFolder(target.getCacheDir());
		fixture.create();
		Context context = new ContextWrapper(target) {
			@Override
			public File getFilesDir() {
				return fixture.getRoot();
			}
		};
		try {
			long now = System.currentTimeMillis();
			writeExitFixture(context, "2", now, ProcessExitStore.REASON_LOW_MEMORY,
					ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
			assertNull("A minimized foreground-service MIDlet's LMK must not interrupt Library",
					ProcessExitStore.findPendingStoredExit(context));
			assertNotNull(LocalDiagnosticRepository.findStored(context, "exit:2"));

			writeExitFixture(context, "1", now - 1_000L, ProcessExitStore.REASON_CRASH,
					ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
			ProcessExitStore.PendingExit pending = ProcessExitStore.findPendingStoredExit(context);
			assertNotNull(pending);
			assertEquals("exit:1", pending.getId());
			assertEquals(2, LocalDiagnosticRepository.loadStored(context).size());
			assertEquals(ProcessExitStore.REASON_LOW_MEMORY,
					LocalDiagnosticRepository.findStored(context, "exit:2").getProcessExitSnapshot().reason);

			ProcessExitStore.acknowledgePendingExits(context);
			assertNull(ProcessExitStore.findPendingStoredExit(context));
			assertEquals("Acknowledgment changes notices without deleting manual evidence",
					2, LocalDiagnosticRepository.loadStored(context).size());
		} finally {
			fixture.delete();
		}
	}

	private static void writeExitFixture(Context context, String key, long timestampMillis,
			int reason, int importance) throws IOException {
		File directory = new File(context.getFilesDir(), "diagnostics/process-exits");
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IOException("Unable to create process-exit fixture directory");
		}
		Properties metadata = new Properties();
		metadata.setProperty("schemaVersion", "1");
		metadata.setProperty("key", key);
		metadata.setProperty("timestampMillis", Long.toString(timestampMillis));
		metadata.setProperty("processName", context.getPackageName() + ":midlet");
		metadata.setProperty("processRole", "midlet");
		metadata.setProperty("pid", "123");
		metadata.setProperty("reason", Integer.toString(reason));
		metadata.setProperty("status", "0");
		metadata.setProperty("importance", Integer.toString(importance));
		try (FileOutputStream output = new FileOutputStream(new File(directory, key + ".properties"))) {
			metadata.store(output, null);
		}
	}

	@Test
	public void abruptRemoteSigkillDoesNotBecomeAFatalReport() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String mainProcessName = context.getPackageName();
		String midletProcessName = mainProcessName + ":midlet";
		int mainPid = Process.myPid();
		Set<String> baselineIds = recordIds(LocalDiagnosticRepository.load(context));
		Set<String> existingSessions = new HashSet<>();
		for (File file : MidletSessionJournal.journalFiles(context)) {
			existingSessions.add(MidletSessionJournal.read(file).sessionId);
		}
		File probeJournal = null;
		try {
			Intent intent = new Intent(context, CrashRuntimeProbeActivity.class)
					.putExtra(CrashRuntimeProbeActivity.EXTRA_MODE, CrashRuntimeProbeActivity.MODE_SIGNAL_KILL)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(intent);
			probeJournal = awaitSignalJournal(context, existingSessions);
			awaitRemoteProcessStops(context, midletProcessName);
			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));

			// Observe beyond the former API23-29 orphan grace period. Neither disappearance nor
			// API30+ unclassified SIGKILL establishes a crash, even with a live-session journal.
			long deadline = SystemClock.uptimeMillis() + 2_000L;
			do {
				assertEquals(baselineIds, recordIds(LocalDiagnosticRepository.load(context)));
				SystemClock.sleep(100L);
			} while (SystemClock.uptimeMillis() < deadline);
			MidletSessionJournal.Snapshot session = MidletSessionJournal.read(probeJournal);
			assertEquals(MidletSessionJournal.Outcome.NONE, session.outcome);
			assertTrue("An ended session still reconciles play stats without a crash report",
					MidletSessionTerminalClassifier.isTerminal(context, session));
		} finally {
			if (probeJournal != null) MidletSessionJournal.delete(probeJournal);
		}
	}

	private static File awaitSignalJournal(Context context, Set<String> existingSessions)
			throws Exception {
		long deadline = SystemClock.uptimeMillis() + REPORT_TIMEOUT_MILLIS;
		do {
			for (File file : MidletSessionJournal.journalFiles(context)) {
				try {
					MidletSessionJournal.Snapshot snapshot = MidletSessionJournal.read(file);
					if (!existingSessions.contains(snapshot.sessionId)
							&& CrashRuntimeProbeActivity.SIGNAL_MIDLET_NAME.equals(snapshot.midletName)
							&& snapshot.stage == MidletSessionJournal.Stage.RUNNING) return file;
				} catch (IOException incompletePublication) {
					// A .new-only first publication has no committed snapshot yet; retry the reader.
				}
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("Timed out waiting for abrupt MIDlet process session journal");
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
}
