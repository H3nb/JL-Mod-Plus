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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.microedition.shell.CrashRuntimeLifecycleControlActivity;
import javax.microedition.shell.MicroActivity;

import com.nokia.mid.ui.NotificationActivity;

import jlmod.runtimefixture.LifecycleMidlet;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.util.Constants;

@RunWith(AndroidJUnit4.class)
public class CrashRuntimeIsolationTest {
	private static final long REPORT_TIMEOUT_MILLIS = 20_000L;
	private static final long PROCESS_TIMEOUT_MILLIS = 10_000L;
	private static final long CLEAN_SESSION_TIMEOUT_MILLIS = 20_000L;
	private static final String LIFECYCLE_MIDLET_NAME = "JL-Mod Plus Lifecycle Runtime Fixture";
	private static final String LIFECYCLE_MIDLET_VENDOR = "JL-Mod Plus";
	private static final String LIFECYCLE_MIDLET_VERSION = "1.0";
	private static final String LIFECYCLE_FIXTURE_ROOT = "crash-runtime-lifecycle";

	@Test
	public void repeatedRemoteSessionCrashesKeepMainProcessAndPersistExactReports() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String mainProcessName = context.getPackageName();
		String midletProcessName = mainProcessName + ":midlet";
		int mainPid = Process.myPid();
		Set<String> baselineIds = recordIds(LocalDiagnosticRepository.load(context));

		try {
			assertEquals(mainProcessName, ru.playsoftware.j2meloader.EmulatorApplication.getProcessName());
			assertEquals(mainPid, processPid(context, mainProcessName));
			assertMidletFacingActivitiesUseMidletProcess(context, midletProcessName);

			LocalDiagnosticRepository.Record first = launchProbeAndAwaitCorrelatedRecord(
					context, baselineIds);
			assertRemoteProcessStops(context, midletProcessName);
			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));
			assertCorrelatedProbeRecord(first);

			Set<String> afterFirstIds = recordIds(LocalDiagnosticRepository.load(context));
			LocalDiagnosticRepository.Record second = launchProbeAndAwaitCorrelatedRecord(
					context, afterFirstIds);
			assertRemoteProcessStops(context, midletProcessName);
			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));
			assertCorrelatedProbeRecord(second);
			assertNotEquals(first.getEventId(), second.getEventId());
			assertNotEquals(first.getSessionId(), second.getSessionId());
		} finally {
			cleanupProbeDiagnostics(context, baselineIds);
		}
	}

	@Test
	public void realMidletFailureMatrixIsContainedAndNextSessionLaunchesCleanly() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String mainProcessName = context.getPackageName();
		String midletProcessName = mainProcessName + ":midlet";
		int mainPid = Process.myPid();
		Set<String> baselineIds = recordIds(LocalDiagnosticRepository.load(context));
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		boolean hadPreviousEmulatorDir = preferences.contains(Constants.PREF_EMULATOR_DIR);
		String previousEmulatorDir = preferences.getString(Constants.PREF_EMULATOR_DIR, null);
		File root = new File(context.getFilesDir(), LIFECYCLE_FIXTURE_ROOT);
		File appDir = new File(new File(root, "converted"), "fixture");
		File marker = new File(root, "lifecycle.marker");

		try {
			assertMidletFacingActivitiesUseMidletProcess(context, midletProcessName);
			deleteRecursively(root);
			prepareLifecycleFixture(context, root, appDir);
			assertTrue("Unable to switch emulator directory for lifecycle runtime fixture",
					preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.getAbsolutePath()).commit());

			assertLifecycleFailureCase(context, appDir, marker,
					LifecycleMidlet.MODE_CRASH_INIT,
					MidletSessionJournal.FailureBoundary.LIFECYCLE_INIT,
					LifecycleMidlet.INIT_FAILURE_MARKER,
					null, mainPid, mainProcessName, midletProcessName);
			assertLifecycleFailureCase(context, appDir, marker,
					LifecycleMidlet.MODE_CRASH_START,
					MidletSessionJournal.FailureBoundary.LIFECYCLE_START,
					LifecycleMidlet.START_FAILURE_MARKER,
					null, mainPid, mainProcessName, midletProcessName);
			assertLifecycleFailureCase(context, appDir, marker,
					LifecycleMidlet.MODE_CRASH_WORKER,
					MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD,
					LifecycleMidlet.WORKER_FAILURE_MARKER,
					null, mainPid, mainProcessName, midletProcessName);
			assertLifecycleFailureCase(context, appDir, marker,
					LifecycleMidlet.MODE_CRASH_PAUSE,
					MidletSessionJournal.FailureBoundary.LIFECYCLE_PAUSE,
					LifecycleMidlet.PAUSE_FAILURE_MARKER,
					CrashRuntimeLifecycleControlActivity.COMMAND_PAUSE,
					mainPid, mainProcessName, midletProcessName);
			assertLifecycleFailureCase(context, appDir, marker,
					LifecycleMidlet.MODE_CRASH_DESTROY,
					MidletSessionJournal.FailureBoundary.LIFECYCLE_DESTROY,
					LifecycleMidlet.DESTROY_FAILURE_MARKER,
					CrashRuntimeLifecycleControlActivity.COMMAND_DESTROY,
					mainPid, mainProcessName, midletProcessName);

			Set<String> afterCrashIds = recordIds(LocalDiagnosticRepository.load(context));
			clearMarker(marker);
			writeLifecycleManifest(appDir, LifecycleMidlet.MODE_CLEAN, marker);
			launchLifecycleMidlet(context, appDir);
			awaitMarker(marker);
			assertRemoteProcessStops(context, midletProcessName);
			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));
			assertNoNewLifecycleFailure(context, afterCrashIds);
		} finally {
			killRemoteProcessBestEffort(context, midletProcessName);
			cleanupLifecycleDiagnostics(context, baselineIds);
			restoreEmulatorDirectoryBestEffort(preferences, hadPreviousEmulatorDir, previousEmulatorDir);
			deleteRecursivelyBestEffort(root);
		}
	}

	@Test
	public void staleNokiaNotificationActionCannotEscapeTheMidletProcess() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String mainProcessName = context.getPackageName();
		String midletProcessName = mainProcessName + ":midlet";
		int mainPid = Process.myPid();
		Set<String> baselineIds = recordIds(LocalDiagnosticRepository.load(context));

		try {
			assertActivityUsesProcess(context, NotificationActivity.class, midletProcessName);
			Intent intent = new Intent(context, NotificationActivity.class)
					.putExtra("id", Integer.MAX_VALUE)
					.putExtra("event", 1)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(intent);
			SystemClock.sleep(1_000L);

			assertEquals(mainPid, Process.myPid());
			assertEquals(mainPid, processPid(context, mainProcessName));
			for (LocalDiagnosticRepository.Record record : LocalDiagnosticRepository.load(context)) {
				if (!baselineIds.contains(record.getId()) && "midlet".equals(record.getProcessRole())) {
					fail("Stale Nokia notification action produced a MIDlet-process crash: " + record.getId());
				}
			}
		} finally {
			killRemoteProcessBestEffort(context, midletProcessName);
		}
	}

	private static void assertLifecycleFailureCase(Context context, File appDir, File marker,
			String mode, MidletSessionJournal.FailureBoundary expectedBoundary, String failureMarker,
			String controlCommand, int mainPid, String mainProcessName, String midletProcessName)
			throws IOException {
		Set<String> existingIds = recordIds(LocalDiagnosticRepository.load(context));
		clearMarker(marker);
		writeLifecycleManifest(appDir, mode, marker);
		launchLifecycleMidlet(context, appDir);
		if (controlCommand != null) {
			awaitMarker(marker);
			launchLifecycleControl(context, controlCommand);
		}
		LocalDiagnosticRepository.Record failure = awaitLifecycleFailure(context, existingIds);
		assertRemoteProcessStops(context, midletProcessName);
		assertEquals(mainPid, Process.myPid());
		assertEquals(mainPid, processPid(context, mainProcessName));
		assertLifecycleFailure(failure, expectedBoundary, failureMarker);
	}

	private static void assertMidletFacingActivitiesUseMidletProcess(
			Context context, String midletProcessName) {
		assertActivityUsesProcess(context, MicroActivity.class, midletProcessName);
		assertActivityUsesProcess(context, NotificationActivity.class, midletProcessName);
	}

	private static void assertActivityUsesProcess(Context context, Class<?> activityClass,
			String expectedProcessName) {
		try {
			ActivityInfo info = context.getPackageManager().getActivityInfo(
					new ComponentName(context, activityClass), 0);
			assertEquals(expectedProcessName, info.processName);
		} catch (PackageManager.NameNotFoundException e) {
			throw new AssertionError(activityClass.getSimpleName()
					+ " is missing from the merged debug manifest", e);
		}
	}

	private static void assertCorrelatedProbeRecord(LocalDiagnosticRepository.Record record) {
		assertTrue(record.hasJavaReport());
		assertNotNull(record.getEventId());
		assertNotNull(record.getSessionId());
		assertEquals(CrashRuntimeProbeActivity.MIDLET_NAME, record.getMidletName());
		assertEquals("midlet", record.getProcessRole());
		assertTrue(record.getDetailText().contains("Failure boundary: UNCAUGHT_THREAD"));
		assertNotNull(record.getStackTrace());
		assertTrue(record.getStackTrace().contains("runtimeProbe=true;"));
	}

	private static void assertLifecycleFailure(LocalDiagnosticRepository.Record record,
			MidletSessionJournal.FailureBoundary expectedBoundary, String failureMarker) {
		assertTrue(record.hasJavaReport());
		assertNotNull(record.getEventId());
		assertNotNull(record.getSessionId());
		assertEquals(LIFECYCLE_MIDLET_NAME, record.getMidletName());
		assertEquals("midlet", record.getProcessRole());
		assertTrue(record.getDetailText().contains("Failure boundary: " + expectedBoundary.name()));
		assertTrue(record.getDetailText().contains("Entrypoint: " + LifecycleMidlet.CLASS_NAME));
		assertNotNull(record.getStackTrace());
		assertTrue(record.getStackTrace().contains(failureMarker));
		assertTrue(record.getStackTrace().contains(LifecycleMidlet.CLASS_NAME));
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

	private static LocalDiagnosticRepository.Record awaitLifecycleFailure(
			Context context, Set<String> existingIds) {
		long deadline = SystemClock.uptimeMillis() + REPORT_TIMEOUT_MILLIS;
		do {
			for (LocalDiagnosticRepository.Record record : LocalDiagnosticRepository.load(context)) {
				if (!existingIds.contains(record.getId())
						&& record.getKind() == LocalDiagnosticRepository.Kind.MIDLET_FAILURE
						&& LIFECYCLE_MIDLET_NAME.equals(record.getMidletName())
						&& record.hasJavaReport()) {
					return record;
				}
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("Timed out waiting for real MIDlet lifecycle crash report");
		return null;
	}

	private static void launchLifecycleMidlet(Context context, File appDir) {
		Intent intent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(appDir.getAbsolutePath()),
				context, MicroActivity.class)
				.putExtra(Constants.KEY_MIDLET_NAME, LIFECYCLE_MIDLET_NAME)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	private static void launchLifecycleControl(Context context, String command) {
		Intent intent = new Intent(context, CrashRuntimeLifecycleControlActivity.class)
				.putExtra(CrashRuntimeLifecycleControlActivity.EXTRA_COMMAND, command)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	private static void prepareLifecycleFixture(Context context, File root, File appDir) throws IOException {
		File configDir = new File(new File(root, "configs"), appDir.getName());
		if (!appDir.mkdirs() && !appDir.isDirectory()) {
			throw new IOException("Unable to create lifecycle fixture converted directory");
		}
		if (!configDir.mkdirs() && !configDir.isDirectory()) {
			throw new IOException("Unable to create lifecycle fixture config directory");
		}
		copyFile(new File(context.getApplicationInfo().sourceDir), new File(appDir, "converted.zip"));
		ProfileModel profile = new ProfileModel(configDir);
		profile.showKeyboard = false;
		profile.touchInput = false;
		profile.soundBank = "";
		if (!ProfilesManager.saveConfig(profile)) {
			throw new IOException("Unable to write lifecycle fixture profile");
		}
	}

	private static void writeLifecycleManifest(File appDir, String mode, File marker) throws IOException {
		File manifest = new File(appDir, "converted.dex.conf");
		String text = "Manifest-Version: 1.0\n"
				+ "MIDlet-Name: " + LIFECYCLE_MIDLET_NAME + "\n"
				+ "MIDlet-Vendor: " + LIFECYCLE_MIDLET_VENDOR + "\n"
				+ "MIDlet-Version: " + LIFECYCLE_MIDLET_VERSION + "\n"
				+ "MIDlet-1: " + LIFECYCLE_MIDLET_NAME + ",," + LifecycleMidlet.CLASS_NAME + "\n"
				+ LifecycleMidlet.MODE_PROPERTY + ": " + mode + "\n"
				+ LifecycleMidlet.MARKER_PROPERTY + ": " + marker.getAbsolutePath() + "\n";
		try (OutputStreamWriter writer = new OutputStreamWriter(
				new FileOutputStream(manifest), StandardCharsets.UTF_8)) {
			writer.write(text);
		}
	}

	private static void clearMarker(File marker) {
		if (marker.exists() && !marker.delete()) {
			fail("Unable to clear lifecycle runtime marker");
		}
	}

	private static void awaitMarker(File marker) {
		long deadline = SystemClock.uptimeMillis() + CLEAN_SESSION_TIMEOUT_MILLIS;
		do {
			if (marker.isFile() && marker.length() > 0) {
				return;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("MIDlet lifecycle fixture never reached its ready marker");
	}

	private static void assertNoNewLifecycleFailure(Context context, Set<String> existingIds) {
		long deadline = SystemClock.uptimeMillis() + 1_000L;
		do {
			for (LocalDiagnosticRepository.Record record : LocalDiagnosticRepository.load(context)) {
				if (!existingIds.contains(record.getId())
						&& LIFECYCLE_MIDLET_NAME.equals(record.getMidletName())) {
					fail("Clean follow-up MIDlet session produced a diagnostic failure: " + record.getId());
				}
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
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

	private static void killRemoteProcessBestEffort(Context context, String processName) {
		try {
			int pid = processPid(context, processName);
			if (pid != 0) {
				Process.killProcess(pid);
			}
		} catch (RuntimeException ignored) {
			// Teardown must not replace the validation failure.
		}
	}

	private static void restoreEmulatorDirectoryBestEffort(SharedPreferences preferences,
			boolean hadPreviousEmulatorDir, String previousEmulatorDir) {
		try {
			SharedPreferences.Editor editor = preferences.edit();
			if (hadPreviousEmulatorDir) {
				editor.putString(Constants.PREF_EMULATOR_DIR, previousEmulatorDir);
			} else {
				editor.remove(Constants.PREF_EMULATOR_DIR);
			}
			editor.commit();
		} catch (RuntimeException ignored) {
			// Teardown must not replace the validation failure.
		}
	}

	private static void cleanupProbeDiagnostics(Context context, Set<String> baselineIds) {
		cleanupDiagnostics(context, baselineIds, CrashRuntimeProbeActivity.MIDLET_NAME);
	}

	private static void cleanupLifecycleDiagnostics(Context context, Set<String> baselineIds) {
		cleanupDiagnostics(context, baselineIds, LIFECYCLE_MIDLET_NAME);
	}

	private static void cleanupDiagnostics(Context context, Set<String> baselineIds, String midletName) {
		try {
			for (LocalDiagnosticRepository.Record record : LocalDiagnosticRepository.load(context)) {
				if (!baselineIds.contains(record.getId()) && midletName.equals(record.getMidletName())) {
					LocalDiagnosticRepository.delete(context, record);
				}
			}
		} catch (RuntimeException ignored) {
			// Test cleanup is best-effort and must not replace the validation failure that led here.
		}
	}

	private static Set<String> recordIds(List<LocalDiagnosticRepository.Record> records) {
		Set<String> ids = new HashSet<>();
		for (LocalDiagnosticRepository.Record record : records) {
			ids.add(record.getId());
		}
		return ids;
	}

	private static void copyFile(File source, File destination) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		try (FileInputStream input = new FileInputStream(source);
			 FileOutputStream output = new FileOutputStream(destination)) {
			for (int read; (read = input.read(buffer)) != -1; ) {
				output.write(buffer, 0, read);
			}
			output.flush();
		}
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		if (!file.delete() && file.exists()) {
			throw new IllegalStateException("Unable to delete runtime fixture path: " + file);
		}
	}

	private static void deleteRecursivelyBestEffort(File file) {
		try {
			deleteRecursively(file);
		} catch (RuntimeException ignored) {
			// Teardown must not replace the validation failure.
		}
	}
}
