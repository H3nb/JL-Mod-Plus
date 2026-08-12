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
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Persists bounded, high-signal Android process-exit diagnostics.
 *
 * Android 11+ keeps ApplicationExitInfo in a system ring buffer. The main process snapshots useful
 * exits into app-private storage so ANR/native/signal/low-memory evidence is not lost when that ring
 * buffer rolls over. Normal user/package/background-management exits are deliberately filtered out.
 */
public final class ProcessExitStore {
	static final int SCHEMA_VERSION = 1;
	static final int MAX_RECORD_COUNT = 64;
	static final long MAX_RECORD_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;
	static final int MAX_TRACE_BYTES = 512 * 1024;
	private static final int MAX_DISPLAY_TRACE_BYTES = 128 * 1024;
	private static final int DISPLAY_TRACE_HEAD_BYTES = 96 * 1024;
	private static final int MAX_DESCRIPTION_LENGTH = 1024;
	private static final int MAX_PROCESS_NAME_LENGTH = 256;
	private static final int MAX_STATE_SUMMARY_BYTES = 128;
	private static final int MAX_HISTORY_RESULTS = 64;

	private static final String TAG = ProcessExitStore.class.getSimpleName();
	private static final String RECORD_DIR = "diagnostics/process-exits";
	private static final String ACK_DIR = "diagnostics/process-exit-acks";
	private static final String RECORD_SUFFIX = ".properties";
	private static final String TRACE_SUFFIX = ".trace";
	private static final String ACK_SUFFIX = ".ack";
	private static final String ATOMIC_BACKUP_SUFFIX = ".bak";
	private static final String ATOMIC_NEW_SUFFIX = ".new";
	private static final String STATE_PREFIX = "jlp1";

	private static final String KEY_SCHEMA_VERSION = "schemaVersion";
	private static final String KEY_KEY = "key";
	private static final String KEY_TIMESTAMP = "timestampMillis";
	private static final String KEY_PROCESS_NAME = "processName";
	private static final String KEY_PROCESS_ROLE = "processRole";
	private static final String KEY_PID = "pid";
	private static final String KEY_REASON = "reason";
	private static final String KEY_STATUS = "status";
	private static final String KEY_IMPORTANCE = "importance";
	private static final String KEY_PSS_KB = "pssKb";
	private static final String KEY_RSS_KB = "rssKb";
	private static final String KEY_DESCRIPTION = "description";
	private static final String KEY_LMK_SUPPORTED = "lowMemoryKillReportSupported";
	private static final String KEY_STATE_ROLE = "stateRole";
	private static final String KEY_STATE_VERSION_CODE = "stateVersionCode";
	private static final String KEY_STATE_SDK = "stateSdk";
	private static final String KEY_SESSION_ID = "sessionId";
	private static final String KEY_PRIMARY_ABI = "primaryAbi";
	private static final String KEY_TRACE_KIND = "traceKind";
	private static final String KEY_TRACE_BYTES = "traceBytes";
	private static final String KEY_TRACE_TRUNCATED = "traceTruncated";

	private ProcessExitStore() {}

	/** Initializes process identity breadcrumbs and snapshots prior exits from the main process. */
	static void initializeProcess(Context context, String processRole) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		try {
			Api30Impl.setProcessState(context, processRole, null);
			if ("main".equals(processRole)) {
				ingest(context);
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "Process-exit diagnostics initialization failed open", e);
		} catch (OutOfMemoryError e) {
			logLowMemory("Process-exit diagnostics initialization skipped under low memory");
		}
	}

	/** Publishes the current MIDlet session as the exact postmortem correlation key. */
	static void setMidletSession(Context context, String sessionId) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
				|| !MidletFailureRecovery.isSafeEventId(sessionId)) {
			return;
		}
		try {
			Api30Impl.setProcessState(context, "midlet", sessionId);
		} catch (RuntimeException e) {
			Log.w(TAG, "Unable to publish MIDlet process-exit session identity", e);
		} catch (OutOfMemoryError e) {
			logLowMemory("Unable to publish MIDlet process-exit session identity under low memory");
		}
	}

	/** Snapshots new high-signal ApplicationExitInfo records. Diagnostics must never block startup. */
	static void ingest(Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		try {
			Api30Impl.ingest(context);
			prune(context);
		} catch (RuntimeException e) {
			Log.w(TAG, "Unable to ingest Android process-exit diagnostics", e);
		} catch (OutOfMemoryError e) {
			logLowMemory("Unable to ingest Android process-exit diagnostics under low memory");
		}
	}

	static List<Snapshot> loadStored(Context context) {
		List<File> files = recordFiles(context);
		if (files.isEmpty()) {
			return Collections.emptyList();
		}
		ArrayList<Snapshot> records = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				records.add(read(file));
			} catch (IOException | RuntimeException e) {
				Log.w(TAG, "Ignoring unreadable process-exit record: " + file.getName());
			}
		}
		records.sort((left, right) -> {
			if (left.timestampMillis == right.timestampMillis) {
				return left.key.compareTo(right.key);
			}
			return left.timestampMillis < right.timestampMillis ? 1 : -1;
		});
		return records;
	}

	/** Returns the newest actionable process exit not already represented by a MIDlet failure notice. */
	public static PendingExit findPendingExit(Context context) {
		ingest(context);
		List<Snapshot> records = loadStored(context);
		if (records.isEmpty()) {
			pruneAcknowledgments(context, Collections.emptySet());
			return null;
		}
		Set<String> acknowledged = readAcknowledgedKeys(context);
		HashSet<String> retained = new HashSet<>(records.size());
		for (Snapshot record : records) {
			retained.add(record.key);
		}
		pruneAcknowledgments(context, retained);
		for (Snapshot record : records) {
			if (acknowledged.contains(record.key)
					|| isRepresentedByUnexpectedMidletFailure(context, record.sessionId)) {
				continue;
			}
			MidletSessionJournal.Snapshot session = findSession(context, record.sessionId);
			return new PendingExit(record, session == null ? null : session.midletName);
		}
		return null;
	}

	/** Acknowledges all retained process-exit notices without deleting diagnostic evidence. */
	public static void acknowledgePendingExits(Context context) {
		List<Snapshot> records = loadStored(context);
		if (records.isEmpty()) {
			return;
		}
		File directory = acknowledgmentDirectory(context);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			Log.w(TAG, "Unable to create process-exit acknowledgment directory");
			return;
		}
		for (Snapshot record : records) {
			File marker = new File(directory, record.key + ACK_SUFFIX);
			try {
				if (!marker.exists() && !marker.createNewFile()) {
					Log.w(TAG, "Unable to acknowledge process-exit record: " + record.key);
				}
			} catch (IOException | SecurityException e) {
				Log.w(TAG, "Unable to acknowledge process-exit record: " + record.key, e);
			}
		}
	}

	static boolean delete(Context context, Snapshot snapshot) {
		if (snapshot == null) {
			return false;
		}
		boolean success = deleteAtomic(snapshot.recordFile);
		if (snapshot.traceFile != null) {
			success &= deleteAtomic(snapshot.traceFile);
		}
		File marker = new File(acknowledgmentDirectory(context), snapshot.key + ACK_SUFFIX);
		if (marker.exists() && (!marker.isFile() || !marker.delete())) {
			success = false;
		}
		return success;
	}

	static String reasonLabel(int reason) {
		return switch (reason) {
			case ApplicationExitInfo.REASON_CRASH -> "Java crash";
			case ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash";
			case ApplicationExitInfo.REASON_ANR -> "ANR";
			case ApplicationExitInfo.REASON_LOW_MEMORY -> "Low-memory kill";
			case ApplicationExitInfo.REASON_SIGNALED -> "Signal termination";
			case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure";
			case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage";
			case ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died";
			case ApplicationExitInfo.REASON_FREEZER -> "App freezer termination";
			case ApplicationExitInfo.REASON_EXIT_SELF -> "Self exit";
			case ApplicationExitInfo.REASON_OTHER -> "Other process termination";
			default -> "Process termination (reason " + reason + ")";
		};
	}

	static String statusLabel(Snapshot snapshot) {
		if (snapshot == null) {
			return null;
		}
		String signal = signalName(snapshot.status);
		if (snapshot.reason == ApplicationExitInfo.REASON_SIGNALED
				|| snapshot.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
			String suffix = signal == null ? Integer.toString(snapshot.status)
					: signal + " (" + snapshot.status + ")";
			if (snapshot.status == OsConstants.SIGKILL && !snapshot.lowMemoryKillReportSupported) {
				return suffix + "; may represent low-memory kill on this device";
			}
			return suffix;
		}
		return Integer.toString(snapshot.status);
	}

	static String importanceLabel(int importance) {
		return switch (importance) {
			case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground";
			case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service";
			case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible";
			case ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible";
			case ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service";
			case ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached";
			case ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone";
			default -> "importance " + importance;
		};
	}

	static String readDisplayTrace(Snapshot snapshot) {
		if (snapshot == null || snapshot.traceFile == null || !"anr-text".equals(snapshot.traceKind)) {
			return null;
		}
		try {
			byte[] data = readBoundedAtomic(snapshot.traceFile, MAX_TRACE_BYTES);
			if (data.length <= MAX_DISPLAY_TRACE_BYTES) {
				return new String(data, StandardCharsets.UTF_8);
			}
			int tailBytes = MAX_DISPLAY_TRACE_BYTES - DISPLAY_TRACE_HEAD_BYTES;
			String head = new String(data, 0, DISPLAY_TRACE_HEAD_BYTES, StandardCharsets.UTF_8);
			String tail = new String(data, data.length - tailBytes, tailBytes, StandardCharsets.UTF_8);
			return head + "\n\n[... trace display shortened; raw retained locally ...]\n\n" + tail;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static boolean shouldRetain(int reason, int status, int importance, boolean midletProcess) {
		boolean foregroundish = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE;
		return switch (reason) {
			case ApplicationExitInfo.REASON_CRASH,
					ApplicationExitInfo.REASON_CRASH_NATIVE,
					ApplicationExitInfo.REASON_ANR,
					ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
					ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true;
			case ApplicationExitInfo.REASON_LOW_MEMORY -> midletProcess || foregroundish;
			case ApplicationExitInfo.REASON_SIGNALED -> status != OsConstants.SIGKILL
					|| midletProcess || foregroundish;
			case ApplicationExitInfo.REASON_DEPENDENCY_DIED,
					ApplicationExitInfo.REASON_FREEZER,
					ApplicationExitInfo.REASON_OTHER -> midletProcess || foregroundish;
			case ApplicationExitInfo.REASON_EXIT_SELF -> status != 0 && (midletProcess || foregroundish);
			case ApplicationExitInfo.REASON_UNKNOWN,
					ApplicationExitInfo.REASON_PERMISSION_CHANGE,
					ApplicationExitInfo.REASON_USER_REQUESTED,
					ApplicationExitInfo.REASON_USER_STOPPED,
					ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
					ApplicationExitInfo.REASON_PACKAGE_UPDATED -> false;
			default -> midletProcess || foregroundish;
		};
	}

	private static void prune(Context context) {
		List<Snapshot> records = loadStored(context);
		long now = System.currentTimeMillis();
		int kept = 0;
		for (Snapshot record : records) {
			long age = record.timestampMillis > 0 && now >= record.timestampMillis
					? now - record.timestampMillis : 0;
			if (age > MAX_RECORD_AGE_MILLIS || kept >= MAX_RECORD_COUNT) {
				if (!delete(context, record)) {
					Log.w(TAG, "Unable to prune process-exit record: " + record.key);
				}
				continue;
			}
			kept++;
		}
	}

	private static List<File> recordFiles(Context context) {
		File directory = recordDirectory(context);
		File[] files = directory.listFiles();
		if (files == null || files.length == 0) {
			return Collections.emptyList();
		}
		ArrayList<File> result = new ArrayList<>();
		HashSet<String> seen = new HashSet<>();
		for (File file : files) {
			if (file == null || !file.isFile()) {
				continue;
			}
			String name = file.getName();
			String baseName = null;
			if (name.endsWith(RECORD_SUFFIX)) {
				baseName = name;
			} else if (name.endsWith(RECORD_SUFFIX + ATOMIC_BACKUP_SUFFIX)) {
				baseName = name.substring(0, name.length() - ATOMIC_BACKUP_SUFFIX.length());
			}
			if (baseName == null) {
				continue;
			}
			File base = new File(directory, baseName);
			if (seen.add(base.getAbsolutePath())) {
				result.add(base);
			}
		}
		return result;
	}

	private static Snapshot read(File file) throws IOException {
		Properties properties = new Properties();
		try (InputStream input = new AtomicFile(file).openRead()) {
			properties.load(input);
		}
		int schema = parseInt(properties, KEY_SCHEMA_VERSION);
		if (schema != SCHEMA_VERSION) {
			throw new IOException("Unsupported process-exit schema: " + schema);
		}
		String key = require(properties, KEY_KEY);
		if (!isSafeKey(key)) {
			throw new IOException("Unsafe process-exit key");
		}
		String traceKind = optional(properties, KEY_TRACE_KIND);
		long traceBytes = parseLongDefault(properties, KEY_TRACE_BYTES, 0);
		boolean traceTruncated = Boolean.parseBoolean(properties.getProperty(KEY_TRACE_TRUNCATED, "false"));
		File traceFile = traceBytes > 0 ? traceFile(file.getParentFile(), key) : null;
		if (traceFile != null && !atomicExists(traceFile)) {
			traceFile = null;
		}
		return new Snapshot(
				file,
				traceFile,
				key,
				parseLong(properties, KEY_TIMESTAMP),
				optional(properties, KEY_PROCESS_NAME),
				optional(properties, KEY_PROCESS_ROLE),
				parseInt(properties, KEY_PID),
				parseInt(properties, KEY_REASON),
				parseInt(properties, KEY_STATUS),
				parseInt(properties, KEY_IMPORTANCE),
				parseLongDefault(properties, KEY_PSS_KB, 0),
				parseLongDefault(properties, KEY_RSS_KB, 0),
				optional(properties, KEY_DESCRIPTION),
				Boolean.parseBoolean(properties.getProperty(KEY_LMK_SUPPORTED, "false")),
				optional(properties, KEY_STATE_ROLE),
				parseLongDefault(properties, KEY_STATE_VERSION_CODE, -1),
				parseIntDefault(properties, KEY_STATE_SDK, -1),
				optional(properties, KEY_SESSION_ID),
				optional(properties, KEY_PRIMARY_ABI),
				traceKind,
				traceBytes,
				traceTruncated
		);
	}

	private static void writeRecord(File file, Snapshot snapshot) throws IOException {
		Properties properties = new Properties();
		properties.setProperty(KEY_SCHEMA_VERSION, Integer.toString(SCHEMA_VERSION));
		properties.setProperty(KEY_KEY, snapshot.key);
		properties.setProperty(KEY_TIMESTAMP, Long.toString(snapshot.timestampMillis));
		put(properties, KEY_PROCESS_NAME, snapshot.processName);
		put(properties, KEY_PROCESS_ROLE, snapshot.processRole);
		properties.setProperty(KEY_PID, Integer.toString(snapshot.pid));
		properties.setProperty(KEY_REASON, Integer.toString(snapshot.reason));
		properties.setProperty(KEY_STATUS, Integer.toString(snapshot.status));
		properties.setProperty(KEY_IMPORTANCE, Integer.toString(snapshot.importance));
		properties.setProperty(KEY_PSS_KB, Long.toString(snapshot.pssKb));
		properties.setProperty(KEY_RSS_KB, Long.toString(snapshot.rssKb));
		put(properties, KEY_DESCRIPTION, snapshot.description);
		properties.setProperty(KEY_LMK_SUPPORTED, Boolean.toString(snapshot.lowMemoryKillReportSupported));
		put(properties, KEY_STATE_ROLE, snapshot.stateRole);
		if (snapshot.stateVersionCode >= 0) {
			properties.setProperty(KEY_STATE_VERSION_CODE, Long.toString(snapshot.stateVersionCode));
		}
		if (snapshot.stateSdk >= 0) {
			properties.setProperty(KEY_STATE_SDK, Integer.toString(snapshot.stateSdk));
		}
		put(properties, KEY_SESSION_ID, snapshot.sessionId);
		put(properties, KEY_PRIMARY_ABI, snapshot.primaryAbi);
		put(properties, KEY_TRACE_KIND, snapshot.traceKind);
		properties.setProperty(KEY_TRACE_BYTES, Long.toString(snapshot.traceBytes));
		properties.setProperty(KEY_TRACE_TRUNCATED, Boolean.toString(snapshot.traceTruncated));

		AtomicFile atomicFile = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			output = atomicFile.startWrite();
			properties.store(output, null);
			atomicFile.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			if (output != null) {
				try {
					atomicFile.failWrite(output);
				} catch (Throwable ignored) {}
			}
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Unable to persist process-exit record", e);
		}
	}

	private static void writeTrace(File file, byte[] data) throws IOException {
		AtomicFile atomicFile = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			output = atomicFile.startWrite();
			output.write(data);
			atomicFile.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			if (output != null) {
				try {
					atomicFile.failWrite(output);
				} catch (Throwable ignored) {}
			}
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Unable to persist process-exit trace", e);
		}
	}

	private static byte[] readBoundedAtomic(File file, int maxBytes) throws IOException {
		try (InputStream input = new AtomicFile(file).openRead();
			 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024))) {
			byte[] buffer = new byte[8192];
			int remaining = maxBytes;
			while (remaining > 0) {
				int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
				if (read < 0) {
					break;
				}
				output.write(buffer, 0, read);
				remaining -= read;
			}
			return output.toByteArray();
		}
	}

	private static boolean deleteAtomic(File base) {
		if (base == null) {
			return true;
		}
		boolean success = deleteIfExists(base);
		success &= deleteIfExists(new File(base.getPath() + ATOMIC_BACKUP_SUFFIX));
		success &= deleteIfExists(new File(base.getPath() + ATOMIC_NEW_SUFFIX));
		return success;
	}

	private static boolean deleteIfExists(File file) {
		return !file.exists() || (file.isFile() && file.delete());
	}

	private static boolean atomicExists(File base) {
		return base.isFile() || new File(base.getPath() + ATOMIC_BACKUP_SUFFIX).isFile();
	}

	private static File recordDirectory(Context context) {
		return new File(context.getFilesDir(), RECORD_DIR);
	}

	private static File acknowledgmentDirectory(Context context) {
		return new File(context.getFilesDir(), ACK_DIR);
	}

	private static File recordFile(File directory, String key) {
		return new File(directory, key + RECORD_SUFFIX);
	}

	private static File traceFile(File directory, String key) {
		return new File(directory, key + TRACE_SUFFIX);
	}

	private static boolean isSafeKey(String key) {
		if (key == null || key.isEmpty() || key.length() > 96) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if ((c >= '0' && c <= '9') || c == '-') {
				continue;
			}
			return false;
		}
		return true;
	}

	private static Set<String> readAcknowledgedKeys(Context context) {
		File[] files = acknowledgmentDirectory(context).listFiles();
		if (files == null || files.length == 0) {
			return Collections.emptySet();
		}
		HashSet<String> keys = new HashSet<>(files.length);
		for (File file : files) {
			if (file == null || !file.isFile()) {
				continue;
			}
			String name = file.getName();
			if (!name.endsWith(ACK_SUFFIX)) {
				continue;
			}
			String key = name.substring(0, name.length() - ACK_SUFFIX.length());
			if (isSafeKey(key)) {
				keys.add(key);
			}
		}
		return keys;
	}

	private static void pruneAcknowledgments(Context context, Set<String> retainedKeys) {
		File[] files = acknowledgmentDirectory(context).listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file == null || !file.isFile() || !file.getName().endsWith(ACK_SUFFIX)) {
				continue;
			}
			String key = file.getName().substring(0, file.getName().length() - ACK_SUFFIX.length());
			if (!retainedKeys.contains(key) && !file.delete()) {
				Log.w(TAG, "Unable to delete orphan process-exit acknowledgment: " + file.getName());
			}
		}
	}

	private static boolean isRepresentedByUnexpectedMidletFailure(Context context, String sessionId) {
		MidletSessionJournal.Snapshot session = findSession(context, sessionId);
		return session != null
				&& session.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
				&& MidletFailureRecovery.isSafeEventId(session.failureEventId);
	}

	private static MidletSessionJournal.Snapshot findSession(Context context, String sessionId) {
		if (!MidletFailureRecovery.isSafeEventId(sessionId)) {
			return null;
		}
		for (File file : MidletSessionJournal.journalFiles(context)) {
			try {
				MidletSessionJournal.Snapshot snapshot = MidletSessionJournal.read(file);
				if (sessionId.equals(snapshot.sessionId)) {
					return snapshot;
				}
			} catch (IOException | RuntimeException ignored) {}
		}
		return null;
	}

	private static String signalName(int signal) {
		if (signal == OsConstants.SIGABRT) return "SIGABRT";
		if (signal == OsConstants.SIGBUS) return "SIGBUS";
		if (signal == OsConstants.SIGFPE) return "SIGFPE";
		if (signal == OsConstants.SIGILL) return "SIGILL";
		if (signal == OsConstants.SIGKILL) return "SIGKILL";
		if (signal == OsConstants.SIGSEGV) return "SIGSEGV";
		if (signal == OsConstants.SIGTERM) return "SIGTERM";
		if (signal == OsConstants.SIGTRAP) return "SIGTRAP";
		return null;
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

	private static void put(Properties properties, String key, String value) {
		if (value != null && !value.isEmpty()) {
			properties.setProperty(key, value);
		}
	}

	private static String optional(Properties properties, String key) {
		String value = properties.getProperty(key);
		return value == null || value.trim().isEmpty() ? null : value;
	}

	private static String require(Properties properties, String key) throws IOException {
		String value = optional(properties, key);
		if (value == null) {
			throw new IOException("Missing process-exit field: " + key);
		}
		return value;
	}

	private static int parseInt(Properties properties, String key) throws IOException {
		try {
			return Integer.parseInt(require(properties, key));
		} catch (NumberFormatException e) {
			throw new IOException("Invalid process-exit integer: " + key, e);
		}
	}

	private static int parseIntDefault(Properties properties, String key, int fallback) throws IOException {
		String value = optional(properties, key);
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IOException("Invalid process-exit integer: " + key, e);
		}
	}

	private static long parseLong(Properties properties, String key) throws IOException {
		try {
			return Long.parseLong(require(properties, key));
		} catch (NumberFormatException e) {
			throw new IOException("Invalid process-exit long: " + key, e);
		}
	}

	private static long parseLongDefault(Properties properties, String key, long fallback) throws IOException {
		String value = optional(properties, key);
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new IOException("Invalid process-exit long: " + key, e);
		}
	}

	private static void logLowMemory(String message) {
		try {
			Log.w(TAG, message);
		} catch (Throwable ignored) {}
	}

	static final class Snapshot {
		final File recordFile;
		final File traceFile;
		final String key;
		final String id;
		final long timestampMillis;
		final String processName;
		final String processRole;
		final int pid;
		final int reason;
		final int status;
		final int importance;
		final long pssKb;
		final long rssKb;
		final String description;
		final boolean lowMemoryKillReportSupported;
		final String stateRole;
		final long stateVersionCode;
		final int stateSdk;
		final String sessionId;
		final String primaryAbi;
		final String traceKind;
		final long traceBytes;
		final boolean traceTruncated;

		Snapshot(File recordFile, File traceFile, String key, long timestampMillis,
				 String processName, String processRole, int pid, int reason, int status,
				 int importance, long pssKb, long rssKb, String description,
				 boolean lowMemoryKillReportSupported, String stateRole, long stateVersionCode,
				 int stateSdk, String sessionId, String primaryAbi, String traceKind,
				 long traceBytes, boolean traceTruncated) {
			this.recordFile = recordFile;
			this.traceFile = traceFile;
			this.key = key;
			this.id = "exit:" + key;
			this.timestampMillis = timestampMillis;
			this.processName = processName;
			this.processRole = processRole;
			this.pid = pid;
			this.reason = reason;
			this.status = status;
			this.importance = importance;
			this.pssKb = pssKb;
			this.rssKb = rssKb;
			this.description = description;
			this.lowMemoryKillReportSupported = lowMemoryKillReportSupported;
			this.stateRole = stateRole;
			this.stateVersionCode = stateVersionCode;
			this.stateSdk = stateSdk;
			this.sessionId = sessionId;
			this.primaryAbi = primaryAbi;
			this.traceKind = traceKind;
			this.traceBytes = traceBytes;
			this.traceTruncated = traceTruncated;
		}
	}

	public static final class PendingExit {
		private final String id;
		private final long timestampMillis;
		private final String processRole;
		private final String midletName;
		private final String reason;

		private PendingExit(Snapshot snapshot, String midletName) {
			this.id = snapshot.id;
			this.timestampMillis = snapshot.timestampMillis;
			this.processRole = snapshot.processRole;
			this.midletName = midletName;
			this.reason = reasonLabel(snapshot.reason);
		}

		public String getId() {
			return id;
		}

		public long getTimestampMillis() {
			return timestampMillis;
		}

		public String getProcessRole() {
			return processRole;
		}

		public String getMidletName() {
			return midletName;
		}

		public String getReason() {
			return reason;
		}
	}

	private static final class StateSummary {
		final String role;
		final long versionCode;
		final int sdk;
		final String sessionId;

		StateSummary(String role, long versionCode, int sdk, String sessionId) {
			this.role = role;
			this.versionCode = versionCode;
			this.sdk = sdk;
			this.sessionId = sessionId;
		}
	}

	private static final class TraceCapture {
		final byte[] data;
		final boolean truncated;
		final String kind;

		TraceCapture(byte[] data, boolean truncated, String kind) {
			this.data = data;
			this.truncated = truncated;
			this.kind = kind;
		}
	}

	private static final class Api30Impl {
		private Api30Impl() {}

		static void setProcessState(Context context, String processRole, String sessionId) {
			ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			if (activityManager == null) {
				return;
			}
			long versionCode = -1;
			try {
				PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
				versionCode = info.getLongVersionCode();
			} catch (Exception ignored) {}
			StringBuilder state = new StringBuilder(96);
			state.append(STATE_PREFIX)
					.append("|r=").append(bound(processRole, 16))
					.append("|vc=").append(versionCode)
					.append("|sdk=").append(Build.VERSION.SDK_INT);
			if (MidletFailureRecovery.isSafeEventId(sessionId)) {
				state.append("|s=").append(sessionId);
			}
			byte[] bytes = state.toString().getBytes(StandardCharsets.US_ASCII);
			if (bytes.length <= MAX_STATE_SUMMARY_BYTES) {
				activityManager.setProcessStateSummary(bytes);
			}
		}

		static void ingest(Context context) {
			ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			if (activityManager == null) {
				return;
			}
			List<ApplicationExitInfo> history = activityManager.getHistoricalProcessExitReasons(
					context.getPackageName(), 0, MAX_HISTORY_RESULTS);
			boolean lmkSupported = ActivityManager.isLowMemoryKillReportSupported();
			File directory = recordDirectory(context);
			if (!directory.isDirectory() && !directory.mkdirs()) {
				Log.w(TAG, "Unable to create process-exit diagnostic directory");
				return;
			}
			for (ApplicationExitInfo info : history) {
				String processName = info.getProcessName();
				if (!isOwnedProcess(context.getPackageName(), processName)) {
					continue;
				}
				String processRole = CrashReporter.classifyProcess(context.getPackageName(), processName);
				boolean midlet = "midlet".equals(processRole);
				if (!shouldRetain(info.getReason(), info.getStatus(), info.getImportance(), midlet)) {
					continue;
				}
				String key = buildKey(info);
				File recordFile = recordFile(directory, key);
				if (atomicExists(recordFile)) {
					continue;
				}
				StateSummary state = parseState(info.getProcessStateSummary());
				TraceCapture trace = captureTrace(info);
				File traceFile = null;
				long traceBytes = 0;
				if (trace != null && trace.data.length > 0) {
					traceFile = traceFile(directory, key);
					try {
						writeTrace(traceFile, trace.data);
						traceBytes = trace.data.length;
					} catch (IOException e) {
						Log.w(TAG, "Unable to persist process-exit trace: " + key);
						traceFile = null;
					}
				}
				String primaryAbi = Build.SUPPORTED_ABIS.length == 0 ? null : Build.SUPPORTED_ABIS[0];
				Snapshot snapshot = new Snapshot(
						recordFile,
						traceFile,
						key,
						info.getTimestamp(),
						bound(processName, MAX_PROCESS_NAME_LENGTH),
						processRole,
						info.getPid(),
						info.getReason(),
						info.getStatus(),
						info.getImportance(),
						info.getPss(),
						info.getRss(),
						bound(info.getDescription(), MAX_DESCRIPTION_LENGTH),
						lmkSupported,
						state.role,
						state.versionCode,
						state.sdk,
						state.sessionId,
						primaryAbi,
						trace == null ? null : trace.kind,
						traceBytes,
						trace != null && trace.truncated
				);
				try {
					writeRecord(recordFile, snapshot);
				} catch (IOException e) {
					Log.w(TAG, "Unable to persist process-exit record: " + key);
					if (traceFile != null) {
						deleteAtomic(traceFile);
					}
				}
			}
		}

		private static boolean isOwnedProcess(String packageName, String processName) {
			return processName != null
					&& (processName.equals(packageName) || processName.startsWith(packageName + ":"));
		}

		private static String buildKey(ApplicationExitInfo info) {
			return info.getTimestamp() + "-" + info.getPid() + "-" + info.getReason() + "-" + info.getStatus();
		}

		private static StateSummary parseState(byte[] bytes) {
			if (bytes == null || bytes.length == 0 || bytes.length > MAX_STATE_SUMMARY_BYTES) {
				return new StateSummary(null, -1, -1, null);
			}
			String text = new String(bytes, StandardCharsets.US_ASCII);
			String[] fields = text.split("\\|");
			if (fields.length == 0 || !STATE_PREFIX.equals(fields[0])) {
				return new StateSummary(null, -1, -1, null);
			}
			String role = null;
			long versionCode = -1;
			int sdk = -1;
			String sessionId = null;
			for (int i = 1; i < fields.length; i++) {
				String field = fields[i];
				int equals = field.indexOf('=');
				if (equals <= 0 || equals == field.length() - 1) {
					continue;
				}
				String key = field.substring(0, equals);
				String value = field.substring(equals + 1);
				try {
					switch (key) {
						case "r" -> role = bound(value, 16);
						case "vc" -> versionCode = Long.parseLong(value);
						case "sdk" -> sdk = Integer.parseInt(value);
						case "s" -> sessionId = MidletFailureRecovery.isSafeEventId(value) ? value : null;
						default -> { }
					}
				} catch (NumberFormatException ignored) {}
			}
			return new StateSummary(role, versionCode, sdk, sessionId);
		}

		private static TraceCapture captureTrace(ApplicationExitInfo info) {
			try (InputStream input = info.getTraceInputStream()) {
				if (input == null) {
					return null;
				}
				ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
				byte[] buffer = new byte[8192];
				int total = 0;
				boolean truncated = false;
				while (total < MAX_TRACE_BYTES) {
					int read = input.read(buffer, 0, Math.min(buffer.length, MAX_TRACE_BYTES - total));
					if (read < 0) {
						break;
					}
					output.write(buffer, 0, read);
					total += read;
				}
				if (total == MAX_TRACE_BYTES && input.read() >= 0) {
					truncated = true;
				}
				String kind;
				if (info.getReason() == ApplicationExitInfo.REASON_ANR) {
					kind = "anr-text";
				} else if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
						&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					kind = "native-tombstone-protobuf";
				} else {
					kind = "system-trace";
				}
				return new TraceCapture(output.toByteArray(), truncated, kind);
			} catch (IOException | RuntimeException e) {
				return null;
			} catch (OutOfMemoryError e) {
				return null;
			}
		}
	}
}
