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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Conservative Android 6-10 fallback for abrupt MIDlet-process termination.
 *
 * Android only exposes {@code ApplicationExitInfo} from API 30. On API 23-29 we therefore retain
 * no guessed ANR/native/LMK classification. When an unfinished MIDlet journal is old enough and its
 * exact recorded process no longer exists, this class writes a schema-compatible ProcessExitStore
 * record whose reason is UNKNOWN and whose description explicitly states that the exact cause is
 * unavailable. The durable MIDlet journal remains the session authority.
 */
public final class LegacyProcessExitFallback {
	private static final String TAG = LegacyProcessExitFallback.class.getSimpleName();
	private static final long ORPHAN_GRACE_MILLIS = 1_500L;

	// These values intentionally mirror ProcessExitStore schema v1. Keeping the fallback writer
	// independent of ApplicationExitInfo prevents verifier/API coupling on Android 6-10 while making
	// the existing inbox, acknowledgment, correlation, and deletion paths consume the same records.
	private static final int SCHEMA_VERSION = 1;
	private static final int REASON_UNKNOWN = 0;
	private static final String RECORD_DIR = "diagnostics/process-exits";
	private static final String RECORD_SUFFIX = ".properties";
	private static final String BACKUP_SUFFIX = ".bak";
	private static final String NEW_SUFFIX = ".new";
	private static final String DESCRIPTION =
			"Exact termination cause unavailable on Android 6-10; ApplicationExitInfo requires API 30+.";

	private LegacyProcessExitFallback() {}

	/**
	 * Materializes at most one UNKNOWN process-exit record for each proven orphan MIDlet session.
	 * Diagnostics fail open: inability to prove that the exact process is gone produces no record.
	 */
	public static void ingest(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			return;
		}
		try {
			MidletSessionJournal.prune(context);
			List<File> journals = MidletSessionJournal.journalFiles(context);
			Set<String> retainedSessions = new HashSet<>(journals.size());
			Set<String> retainedLegacyKeys = new HashSet<>(journals.size());
			for (File journalFile : journals) {
				try {
					MidletSessionJournal.Snapshot snapshot = MidletSessionJournal.read(journalFile);
					if (MidletFailureRecovery.isSafeEventId(snapshot.sessionId)) {
						retainedSessions.add(snapshot.sessionId);
						retainedLegacyKeys.add(buildKey(snapshot));
					}
					if (isProvenOrphan(context, snapshot)) {
						persist(context, snapshot);
					}
				} catch (IOException | RuntimeException e) {
					Log.w(TAG, "Ignoring unreadable MIDlet session while checking legacy process exit", e);
				}
			}
			pruneOrphanRecords(context, retainedSessions);
			ProcessExitDeletionStore.pruneAgainstLegacyKeys(context, retainedLegacyKeys);
		} catch (RuntimeException e) {
			Log.w(TAG, "Legacy process-exit fallback failed open", e);
		} catch (OutOfMemoryError e) {
			try {
				Log.w(TAG, "Legacy process-exit fallback skipped under low memory");
			} catch (Throwable ignored) {}
		}
	}

	static boolean isProvenOrphan(Context context, MidletSessionJournal.Snapshot snapshot) {
		if (snapshot == null
				|| snapshot.outcome != MidletSessionJournal.Outcome.NONE
				|| snapshot.stage == MidletSessionJournal.Stage.COMPLETED
				|| !MidletFailureRecovery.isSafeEventId(snapshot.sessionId)
				|| snapshot.processPid <= 0) {
			return false;
		}
		String expectedProcess = context.getPackageName() + ":midlet";
		if (!expectedProcess.equals(snapshot.processName) || !pastGrace(snapshot)) {
			return false;
		}
		return exactProcessIsGone(snapshot.processPid, expectedProcess);
	}

	private static boolean pastGrace(MidletSessionJournal.Snapshot snapshot) {
		long nowElapsed = SystemClock.elapsedRealtime();
		if (snapshot.updatedElapsedRealtimeMillis >= 0
				&& nowElapsed >= snapshot.updatedElapsedRealtimeMillis) {
			return nowElapsed - snapshot.updatedElapsedRealtimeMillis >= ORPHAN_GRACE_MILLIS;
		}
		// elapsedRealtime resets at boot. Only fall back to wall time when it moves forward; a clock
		// rollback cannot safely prove age, so diagnostics fail open until a later observation.
		long nowWall = System.currentTimeMillis();
		return snapshot.updatedWallTimeMillis > 0
				&& nowWall >= snapshot.updatedWallTimeMillis
				&& nowWall - snapshot.updatedWallTimeMillis >= ORPHAN_GRACE_MILLIS;
	}

	private static boolean exactProcessIsGone(int pid, String expectedProcessName) {
		File cmdline = new File("/proc/" + pid + "/cmdline");
		if (!cmdline.exists()) {
			return true;
		}
		byte[] buffer = new byte[256];
		try (InputStream input = new FileInputStream(cmdline)) {
			int count = input.read(buffer);
			if (count <= 0) {
				return false;
			}
			int end = 0;
			while (end < count && buffer[end] != 0) {
				end++;
			}
			String actual = new String(buffer, 0, end, StandardCharsets.UTF_8).trim();
			// A different cmdline proves PID reuse, which also proves that the recorded MIDlet process
			// is gone. An unreadable/empty cmdline does not prove death and is handled fail-open above.
			return !expectedProcessName.equals(actual);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static void persist(Context context, MidletSessionJournal.Snapshot session) {
		File directory = new File(context.getFilesDir(), RECORD_DIR);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			Log.w(TAG, "Unable to create process-exit diagnostic directory");
			return;
		}
		String key = buildKey(session);
		if (ProcessExitDeletionStore.isDeleted(context, key)) {
			return;
		}
		File recordFile = new File(directory, key + RECORD_SUFFIX);
		if (atomicExists(recordFile)) {
			return;
		}

		Properties p = new Properties();
		p.setProperty("schemaVersion", Integer.toString(SCHEMA_VERSION));
		p.setProperty("key", key);
		p.setProperty("timestampMillis", Long.toString(session.updatedWallTimeMillis));
		put(p, "processName", session.processName);
		p.setProperty("processRole", "midlet");
		p.setProperty("pid", Integer.toString(session.processPid));
		p.setProperty("reason", Integer.toString(REASON_UNKNOWN));
		p.setProperty("status", "0");
		p.setProperty("importance",
				Integer.toString(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE));
		p.setProperty("pssKb", "0");
		p.setProperty("rssKb", "0");
		p.setProperty("description", DESCRIPTION);
		p.setProperty("lowMemoryKillReportSupported", "false");
		long versionCode = versionCode(context);
		if (versionCode >= 0) {
			p.setProperty("stateVersionCode", Long.toString(versionCode));
		}
		p.setProperty("stateSdk", Integer.toString(Build.VERSION.SDK_INT));
		p.setProperty("sessionId", session.sessionId);
		put(p, "deviceBrand", bound(Build.BRAND, 128));
		put(p, "deviceModel", bound(Build.MODEL, 128));
		if (Build.SUPPORTED_ABIS.length > 0) {
			put(p, "primaryAbi", bound(Build.SUPPORTED_ABIS[0], 128));
		}
		p.setProperty("traceBytes", "0");
		p.setProperty("traceTruncated", "false");

		AtomicFile atomic = new AtomicFile(recordFile);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			p.store(output, null);
			atomic.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			if (output != null) {
				try {
					atomic.failWrite(output);
				} catch (Throwable ignored) {}
			}
			Log.w(TAG, "Unable to persist legacy process-exit diagnostic", e);
		}
	}

	private static String buildKey(MidletSessionJournal.Snapshot session) {
		return session.updatedWallTimeMillis + "-" + session.processPid + "-0-0-"
				+ Integer.toUnsignedString(session.sessionId.hashCode());
	}

	private static long versionCode(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
					? info.getLongVersionCode() : info.versionCode;
		} catch (Exception ignored) {
			return -1;
		}
	}

	/** Removes only fallback records whose authoritative session journal has already been pruned. */
	private static void pruneOrphanRecords(Context context, Set<String> retainedSessions) {
		File directory = new File(context.getFilesDir(), RECORD_DIR);
		File[] files = directory.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file == null || !file.isFile() || !file.getName().endsWith(RECORD_SUFFIX)) {
				continue;
			}
			try {
				Properties p = new Properties();
				try (InputStream input = new AtomicFile(file).openRead()) {
					p.load(input);
				}
				if (!DESCRIPTION.equals(p.getProperty("description"))) {
					continue;
				}
				String sessionId = p.getProperty("sessionId");
				if (retainedSessions.contains(sessionId)) {
					continue;
				}
				deleteAtomic(file);
			} catch (IOException | RuntimeException e) {
				// A malformed record is owned by ProcessExitStore's normal read/prune path; do not guess.
			}
		}
	}

	private static boolean atomicExists(File file) {
		return file.isFile() || new File(file.getPath() + BACKUP_SUFFIX).isFile();
	}

	private static void deleteAtomic(File file) {
		deleteIfExists(file);
		deleteIfExists(new File(file.getPath() + BACKUP_SUFFIX));
		deleteIfExists(new File(file.getPath() + NEW_SUFFIX));
	}

	private static void deleteIfExists(File file) {
		if (file.exists() && file.isFile() && !file.delete()) {
			Log.w(TAG, "Unable to delete stale legacy process-exit record: " + file.getName());
		}
	}

	private static void put(Properties p, String key, String value) {
		if (value != null && !value.isEmpty()) {
			p.setProperty(key, value);
		}
	}

	private static String bound(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		String normalized = value.replace('\u0000', ' ').replace('\r', ' ').trim();
		if (normalized.isEmpty()) {
			return null;
		}
		return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
	}
}
