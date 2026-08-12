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

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Stores bounded, high-signal Android process-exit evidence.
 *
 * API 30+ system history complements the Java exception journal: it can explain a process death
 * which had no opportunity to throw/report in Java, including ANR, native crash, signal death, and
 * low-memory termination. Normal process-management exits are intentionally filtered out.
 */
public final class ProcessExitStore {
	static final int SCHEMA_VERSION = 1;
	static final int MAX_RECORD_COUNT = 64;
	static final long MAX_RECORD_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;
	static final int MAX_TRACE_BYTES = 512 * 1024;

	// Stable ApplicationExitInfo reason values. Keeping these primitive values outside Api30Impl
	// avoids verifier/API-level coupling on Android 6-10; typed framework access stays API30-only.
	static final int REASON_UNKNOWN = 0;
	static final int REASON_EXIT_SELF = 1;
	static final int REASON_SIGNALED = 2;
	static final int REASON_LOW_MEMORY = 3;
	static final int REASON_CRASH = 4;
	static final int REASON_CRASH_NATIVE = 5;
	static final int REASON_ANR = 6;
	static final int REASON_INITIALIZATION_FAILURE = 7;
	static final int REASON_PERMISSION_CHANGE = 8;
	static final int REASON_EXCESSIVE_RESOURCE_USAGE = 9;
	static final int REASON_USER_REQUESTED = 10;
	static final int REASON_USER_STOPPED = 11;
	static final int REASON_DEPENDENCY_DIED = 12;
	static final int REASON_OTHER = 13;
	static final int REASON_FREEZER = 14;
	static final int REASON_PACKAGE_STATE_CHANGE = 15;
	static final int REASON_PACKAGE_UPDATED = 16;

	private static final int MAX_HISTORY_RESULTS = 64;
	private static final int MAX_DESCRIPTION_LENGTH = 1024;
	private static final int MAX_DEVICE_VALUE_LENGTH = 128;
	private static final int MAX_PROCESS_NAME_LENGTH = 256;
	private static final int MAX_STATE_SUMMARY_BYTES = 128;
	private static final int MAX_DISPLAY_TRACE_BYTES = 128 * 1024;
	private static final int DISPLAY_TRACE_HEAD_BYTES = 96 * 1024;

	private static final String TAG = ProcessExitStore.class.getSimpleName();
	private static final String RECORD_DIR = "diagnostics/process-exits";
	private static final String ACK_DIR = "diagnostics/process-exit-acks";
	private static final String RECORD_SUFFIX = ".properties";
	private static final String TRACE_SUFFIX = ".trace";
	private static final String ACK_SUFFIX = ".ack";
	private static final String BACKUP_SUFFIX = ".bak";
	private static final String NEW_SUFFIX = ".new";
	private static final String STATE_PREFIX = "jlp1";

	private static final String KEY_SCHEMA = "schemaVersion";
	private static final String KEY_KEY = "key";
	private static final String KEY_TIMESTAMP = "timestampMillis";
	private static final String KEY_PROCESS_NAME = "processName";
	private static final String KEY_PROCESS_ROLE = "processRole";
	private static final String KEY_PID = "pid";
	private static final String KEY_REASON = "reason";
	private static final String KEY_STATUS = "status";
	private static final String KEY_IMPORTANCE = "importance";
	private static final String KEY_PSS = "pssKb";
	private static final String KEY_RSS = "rssKb";
	private static final String KEY_DESCRIPTION = "description";
	private static final String KEY_LMK_SUPPORTED = "lowMemoryKillReportSupported";
	private static final String KEY_VERSION_CODE = "stateVersionCode";
	private static final String KEY_SDK = "stateSdk";
	private static final String KEY_SESSION_ID = "sessionId";
	private static final String KEY_DEVICE_BRAND = "deviceBrand";
	private static final String KEY_DEVICE_MODEL = "deviceModel";
	private static final String KEY_PRIMARY_ABI = "primaryAbi";
	private static final String KEY_TRACE_KIND = "traceKind";
	private static final String KEY_TRACE_BYTES = "traceBytes";
	private static final String KEY_TRACE_TRUNCATED = "traceTruncated";

	private ProcessExitStore() {}

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

	/** Publishes the immutable MIDlet session ID into Android's <=128-byte process state summary. */
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
			logLowMemory("Unable to publish MIDlet process-exit identity under low memory");
		}
	}

	/** Copies useful framework exit history into app-private durable storage. */
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
		ArrayList<Snapshot> result = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				Snapshot snapshot = read(file);
				// The isolated MIDlet process is deliberately killed after a graceful MIDlet exit.
				// Exact journal outcome keeps that expected SIGKILL out of the crash inbox.
				if (isIntentionalSessionExit(context, snapshot.sessionId)) {
					delete(context, snapshot);
					continue;
				}
				result.add(snapshot);
			} catch (IOException | RuntimeException e) {
				Log.w(TAG, "Ignoring unreadable process-exit record: " + file.getName());
			}
		}
		Collections.sort(result, (left, right) -> {
			if (left.timestampMillis == right.timestampMillis) {
				return left.key.compareTo(right.key);
			}
			return left.timestampMillis < right.timestampMillis ? 1 : -1;
		});
		return result;
	}

	/** Returns one unacknowledged abnormal exit not already represented by a MIDlet failure notice. */
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

	/** Acknowledgment suppresses repeat UI notices but never deletes diagnostic evidence. */
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

	/** Deletes dependent trace evidence before the metadata that points to it. */
	static boolean delete(Context context, Snapshot snapshot) {
		if (snapshot == null) {
			return false;
		}
		if (snapshot.traceFile != null && !deleteAtomic(snapshot.traceFile)) {
			return false;
		}
		if (!deleteAtomic(snapshot.recordFile)) {
			return false;
		}
		File marker = new File(acknowledgmentDirectory(context), snapshot.key + ACK_SUFFIX);
		return !marker.exists() || (marker.isFile() && marker.delete());
	}

	static String reasonLabel(int reason) {
		return switch (reason) {
			case REASON_CRASH -> "Java crash";
			case REASON_CRASH_NATIVE -> "Native crash";
			case REASON_ANR -> "ANR";
			case REASON_LOW_MEMORY -> "Low-memory kill";
			case REASON_SIGNALED -> "Signal termination";
			case REASON_INITIALIZATION_FAILURE -> "Initialization failure";
			case REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage";
			case REASON_DEPENDENCY_DIED -> "Dependency died";
			case REASON_FREEZER -> "App freezer termination";
			case REASON_EXIT_SELF -> "Self exit";
			case REASON_OTHER -> "Other process termination";
			default -> "Process termination (reason " + reason + ")";
		};
	}

	static String statusLabel(Snapshot snapshot) {
		if (snapshot == null) {
			return null;
		}
		if (snapshot.reason != REASON_SIGNALED && snapshot.reason != REASON_CRASH_NATIVE) {
			return Integer.toString(snapshot.status);
		}
		String signal = signalName(snapshot.status);
		String value = signal == null ? Integer.toString(snapshot.status)
				: signal + " (" + snapshot.status + ")";
		if (snapshot.status == OsConstants.SIGKILL && !snapshot.lowMemoryKillReportSupported) {
			return value + "; may represent low-memory kill on this device";
		}
		return value;
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

	/** Returns bounded head+tail text only for ANR traces. Binary native tombstones stay raw/local. */
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

	/** Pure retention policy so noise filtering is unit-testable on the JVM. */
	static boolean shouldRetain(int reason, int status, int importance, boolean midletProcess) {
		boolean foregroundish = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE;
		return switch (reason) {
			case REASON_CRASH,
					REASON_CRASH_NATIVE,
					REASON_ANR,
					REASON_INITIALIZATION_FAILURE,
					REASON_EXCESSIVE_RESOURCE_USAGE -> true;
			case REASON_LOW_MEMORY -> midletProcess || foregroundish;
			case REASON_SIGNALED -> status != OsConstants.SIGKILL || midletProcess || foregroundish;
			case REASON_DEPENDENCY_DIED, REASON_FREEZER, REASON_OTHER -> midletProcess || foregroundish;
			case REASON_EXIT_SELF -> status != 0 && (midletProcess || foregroundish);
			case REASON_UNKNOWN,
					REASON_PERMISSION_CHANGE,
					REASON_USER_REQUESTED,
					REASON_USER_STOPPED,
					REASON_PACKAGE_STATE_CHANGE,
					REASON_PACKAGE_UPDATED -> false;
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
			} else {
				kept++;
			}
		}
	}

	private static List<File> recordFiles(Context context) {
		File[] files = recordDirectory(context).listFiles();
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
			String canonical = null;
			if (name.endsWith(RECORD_SUFFIX)) {
				canonical = name;
			} else if (name.endsWith(RECORD_SUFFIX + BACKUP_SUFFIX)) {
				canonical = name.substring(0, name.length() - BACKUP_SUFFIX.length());
			}
			if (canonical == null) {
				continue;
			}
			File base = new File(file.getParentFile(), canonical);
			if (seen.add(base.getAbsolutePath())) {
				result.add(base);
			}
		}
		return result;
	}

	private static Snapshot read(File file) throws IOException {
		Properties p = new Properties();
		try (InputStream input = new AtomicFile(file).openRead()) {
			p.load(input);
		}
		if (parseInt(p, KEY_SCHEMA) != SCHEMA_VERSION) {
			throw new IOException("Unsupported process-exit schema");
		}
		String key = require(p, KEY_KEY);
		if (!isSafeKey(key)) {
			throw new IOException("Unsafe process-exit key");
		}
		long declaredTraceBytes = parseLongDefault(p, KEY_TRACE_BYTES, 0);
		File traceFile = declaredTraceBytes > 0 ? traceFile(file.getParentFile(), key) : null;
		if (traceFile != null && !atomicExists(traceFile)) {
			traceFile = null;
			declaredTraceBytes = 0;
		}
		return new Snapshot(
				file,
				traceFile,
				key,
				parseLong(p, KEY_TIMESTAMP),
				optional(p, KEY_PROCESS_NAME),
				optional(p, KEY_PROCESS_ROLE),
				parseInt(p, KEY_PID),
				parseInt(p, KEY_REASON),
				parseInt(p, KEY_STATUS),
				parseInt(p, KEY_IMPORTANCE),
				parseLongDefault(p, KEY_PSS, 0),
				parseLongDefault(p, KEY_RSS, 0),
				optional(p, KEY_DESCRIPTION),
				Boolean.parseBoolean(p.getProperty(KEY_LMK_SUPPORTED, "false")),
				parseLongDefault(p, KEY_VERSION_CODE, -1),
				parseIntDefault(p, KEY_SDK, -1),
				optional(p, KEY_SESSION_ID),
				optional(p, KEY_DEVICE_BRAND),
				optional(p, KEY_DEVICE_MODEL),
				optional(p, KEY_PRIMARY_ABI),
				optional(p, KEY_TRACE_KIND),
				declaredTraceBytes,
				Boolean.parseBoolean(p.getProperty(KEY_TRACE_TRUNCATED, "false"))
		);
	}

	private static void writeRecord(Snapshot snapshot) throws IOException {
		Properties p = new Properties();
		p.setProperty(KEY_SCHEMA, Integer.toString(SCHEMA_VERSION));
		p.setProperty(KEY_KEY, snapshot.key);
		p.setProperty(KEY_TIMESTAMP, Long.toString(snapshot.timestampMillis));
		put(p, KEY_PROCESS_NAME, snapshot.processName);
		put(p, KEY_PROCESS_ROLE, snapshot.processRole);
		p.setProperty(KEY_PID, Integer.toString(snapshot.pid));
		p.setProperty(KEY_REASON, Integer.toString(snapshot.reason));
		p.setProperty(KEY_STATUS, Integer.toString(snapshot.status));
		p.setProperty(KEY_IMPORTANCE, Integer.toString(snapshot.importance));
		p.setProperty(KEY_PSS, Long.toString(snapshot.pssKb));
		p.setProperty(KEY_RSS, Long.toString(snapshot.rssKb));
		put(p, KEY_DESCRIPTION, snapshot.description);
		p.setProperty(KEY_LMK_SUPPORTED, Boolean.toString(snapshot.lowMemoryKillReportSupported));
		if (snapshot.stateVersionCode >= 0) {
			p.setProperty(KEY_VERSION_CODE, Long.toString(snapshot.stateVersionCode));
		}
		if (snapshot.stateSdk >= 0) {
			p.setProperty(KEY_SDK, Integer.toString(snapshot.stateSdk));
		}
		put(p, KEY_SESSION_ID, snapshot.sessionId);
		put(p, KEY_DEVICE_BRAND, snapshot.deviceBrand);
		put(p, KEY_DEVICE_MODEL, snapshot.deviceModel);
		put(p, KEY_PRIMARY_ABI, snapshot.primaryAbi);
		put(p, KEY_TRACE_KIND, snapshot.traceKind);
		p.setProperty(KEY_TRACE_BYTES, Long.toString(snapshot.traceBytes));
		p.setProperty(KEY_TRACE_TRUNCATED, Boolean.toString(snapshot.traceTruncated));
		writeProperties(snapshot.recordFile, p);
	}

	private static void writeProperties(File file, Properties properties) throws IOException {
		AtomicFile atomic = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			properties.store(output, null);
			atomic.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			rollback(atomic, output);
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Unable to persist process-exit metadata", e);
		}
	}

	private static void writeBytes(File file, byte[] data) throws IOException {
		AtomicFile atomic = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			output.write(data);
			atomic.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			rollback(atomic, output);
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Unable to persist process-exit trace", e);
		}
	}

	private static void rollback(AtomicFile atomic, FileOutputStream output) {
		if (output == null) {
			return;
		}
		try {
			atomic.failWrite(output);
		} catch (Throwable ignored) {}
	}

	private static byte[] readBoundedAtomic(File file, int maxBytes) throws IOException {
		try (InputStream input = new AtomicFile(file).openRead();
			 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024))) {
			byte[] buffer = new byte[8192];
			int remaining = maxBytes;
			while (remaining > 0) {
				int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
				if (count < 0) {
					break;
				}
				output.write(buffer, 0, count);
				remaining -= count;
			}
			return output.toByteArray();
		}
	}

	private static boolean isRepresentedByUnexpectedMidletFailure(Context context, String sessionId) {
		MidletSessionJournal.Snapshot session = findSession(context, sessionId);
		return session != null
				&& session.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
				&& MidletFailureRecovery.isSafeEventId(session.failureEventId);
	}

	private static boolean isIntentionalSessionExit(Context context, String sessionId) {
		MidletSessionJournal.Snapshot session = findSession(context, sessionId);
		return session != null && (session.outcome == MidletSessionJournal.Outcome.MIDLET_REQUEST
				|| session.outcome == MidletSessionJournal.Outcome.USER_STOP
				|| session.outcome == MidletSessionJournal.Outcome.LIFECYCLE_STOP);
	}

	static MidletSessionJournal.Snapshot findSession(Context context, String sessionId) {
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

	private static Set<String> readAcknowledgedKeys(Context context) {
		File[] files = acknowledgmentDirectory(context).listFiles();
		if (files == null || files.length == 0) {
			return Collections.emptySet();
		}
		HashSet<String> result = new HashSet<>();
		for (File file : files) {
			if (file == null || !file.isFile() || !file.getName().endsWith(ACK_SUFFIX)) {
				continue;
			}
			String key = file.getName().substring(0, file.getName().length() - ACK_SUFFIX.length());
			if (isSafeKey(key)) {
				result.add(key);
			}
		}
		return result;
	}

	private static void pruneAcknowledgments(Context context, Set<String> retained) {
		File[] files = acknowledgmentDirectory(context).listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file == null || !file.isFile() || !file.getName().endsWith(ACK_SUFFIX)) {
				continue;
			}
			String key = file.getName().substring(0, file.getName().length() - ACK_SUFFIX.length());
			if (!retained.contains(key) && !file.delete()) {
				Log.w(TAG, "Unable to delete orphan process-exit acknowledgment: " + file.getName());
			}
		}
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

	private static boolean atomicExists(File file) {
		return file.isFile() || new File(file.getPath() + BACKUP_SUFFIX).isFile();
	}

	private static boolean deleteAtomic(File file) {
		return file == null || (deleteIfExists(file)
				&& deleteIfExists(new File(file.getPath() + BACKUP_SUFFIX))
				&& deleteIfExists(new File(file.getPath() + NEW_SUFFIX)));
	}

	private static boolean deleteIfExists(File file) {
		return !file.exists() || (file.isFile() && file.delete());
	}

	private static boolean isSafeKey(String key) {
		if (key == null || key.isEmpty() || key.length() > 96) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if ((c < '0' || c > '9') && c != '-') {
				return false;
			}
		}
		return true;
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

	private static void put(Properties p, String key, String value) {
		if (value != null && !value.isEmpty()) {
			p.setProperty(key, value);
		}
	}

	private static String optional(Properties p, String key) {
		String value = p.getProperty(key);
		return value == null || value.trim().isEmpty() ? null : value;
	}

	private static String require(Properties p, String key) throws IOException {
		String value = optional(p, key);
		if (value == null) {
			throw new IOException("Missing process-exit field: " + key);
		}
		return value;
	}

	private static int parseInt(Properties p, String key) throws IOException {
		return parseIntValue(require(p, key), key);
	}

	private static int parseIntDefault(Properties p, String key, int fallback) throws IOException {
		String value = optional(p, key);
		return value == null ? fallback : parseIntValue(value, key);
	}

	private static int parseIntValue(String value, String key) throws IOException {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IOException("Invalid process-exit integer: " + key, e);
		}
	}

	private static long parseLong(Properties p, String key) throws IOException {
		return parseLongValue(require(p, key), key);
	}

	private static long parseLongDefault(Properties p, String key, long fallback) throws IOException {
		String value = optional(p, key);
		return value == null ? fallback : parseLongValue(value, key);
	}

	private static long parseLongValue(String value, String key) throws IOException {
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
		final long stateVersionCode;
		final int stateSdk;
		final String sessionId;
		final String deviceBrand;
		final String deviceModel;
		final String primaryAbi;
		final String traceKind;
		final long traceBytes;
		final boolean traceTruncated;

		Snapshot(File recordFile, File traceFile, String key, long timestampMillis,
				 String processName, String processRole, int pid, int reason, int status,
				 int importance, long pssKb, long rssKb, String description,
				 boolean lowMemoryKillReportSupported, long stateVersionCode, int stateSdk,
				 String sessionId, String deviceBrand, String deviceModel, String primaryAbi,
				 String traceKind, long traceBytes, boolean traceTruncated) {
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
			this.stateVersionCode = stateVersionCode;
			this.stateSdk = stateSdk;
			this.sessionId = sessionId;
			this.deviceBrand = deviceBrand;
			this.deviceModel = deviceModel;
			this.primaryAbi = primaryAbi;
			this.traceKind = traceKind;
			this.traceBytes = traceBytes;
			this.traceTruncated = traceTruncated;
		}
	}

	public static final class PendingExit {
		private final String id;
		private final String processRole;
		private final String midletName;
		private final String reason;

		private PendingExit(Snapshot snapshot, String midletName) {
			this.id = snapshot.id;
			this.processRole = snapshot.processRole;
			this.midletName = midletName;
			this.reason = reasonLabel(snapshot.reason);
		}

		public String getId() {
			return id;
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
		final long versionCode;
		final int sdk;
		final String sessionId;

		StateSummary(long versionCode, int sdk, String sessionId) {
			this.versionCode = versionCode;
			this.sdk = sdk;
			this.sessionId = sessionId;
		}
	}

	private static final class TraceCapture {
		final byte[] bytes;
		final String kind;
		final boolean truncated;

		TraceCapture(byte[] bytes, String kind, boolean truncated) {
			this.bytes = bytes;
			this.kind = kind;
			this.truncated = truncated;
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private static final class Api30Impl {
		private Api30Impl() {}

		static void setProcessState(Context context, String processRole, String sessionId) {
			ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			if (manager == null) {
				return;
			}
			long versionCode = -1;
			try {
				PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
				versionCode = info.getLongVersionCode();
			} catch (Exception ignored) {}

			String role = bound(processRole, 16);
			if (role == null) {
				role = "other";
			}
			StringBuilder text = new StringBuilder(96)
					.append(STATE_PREFIX)
					.append("|r=").append(role)
					.append("|vc=").append(versionCode)
					.append("|sdk=").append(Build.VERSION.SDK_INT);
			if (MidletFailureRecovery.isSafeEventId(sessionId)) {
				text.append("|s=").append(sessionId);
			}
			byte[] state = text.toString().getBytes(StandardCharsets.US_ASCII);
			if (state.length <= MAX_STATE_SUMMARY_BYTES) {
				manager.setProcessStateSummary(state);
			}
		}

		static void ingest(Context context) {
			ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			if (manager == null) {
				return;
			}
			List<ApplicationExitInfo> history = manager.getHistoricalProcessExitReasons(
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
				StateSummary state = parseState(info.getProcessStateSummary());
				if (isIntentionalSessionExit(context, state.sessionId)) {
					continue;
				}
				if (!shouldRetain(info.getReason(), info.getStatus(), info.getImportance(),
						"midlet".equals(processRole))) {
					continue;
				}

				String key = buildKey(info);
				File metadata = recordFile(directory, key);
				if (atomicExists(metadata)) {
					continue;
				}

				TraceCapture trace = captureTrace(info);
				File traceFile = null;
				long traceBytes = 0;
				if (trace != null && trace.bytes.length > 0) {
					traceFile = traceFile(directory, key);
					try {
						writeBytes(traceFile, trace.bytes);
						traceBytes = trace.bytes.length;
					} catch (IOException e) {
						Log.w(TAG, "Unable to persist process-exit trace: " + key);
						traceFile = null;
					}
				}

				String primaryAbi = Build.SUPPORTED_ABIS.length == 0 ? null : Build.SUPPORTED_ABIS[0];
				Snapshot snapshot = new Snapshot(
						metadata,
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
						state.versionCode,
						state.sdk,
						state.sessionId,
						bound(Build.BRAND, MAX_DEVICE_VALUE_LENGTH),
						bound(Build.MODEL, MAX_DEVICE_VALUE_LENGTH),
						primaryAbi,
						trace == null ? null : trace.kind,
						traceBytes,
						trace != null && trace.truncated
				);
				try {
					writeRecord(snapshot);
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
			return info.getTimestamp() + "-" + info.getPid() + "-"
					+ info.getReason() + "-" + info.getStatus();
		}

		private static StateSummary parseState(byte[] bytes) {
			if (bytes == null || bytes.length == 0 || bytes.length > MAX_STATE_SUMMARY_BYTES) {
				return new StateSummary(-1, -1, null);
			}
			String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("\\|");
			if (fields.length == 0 || !STATE_PREFIX.equals(fields[0])) {
				return new StateSummary(-1, -1, null);
			}
			long versionCode = -1;
			int sdk = -1;
			String sessionId = null;
			for (int i = 1; i < fields.length; i++) {
				int equals = fields[i].indexOf('=');
				if (equals <= 0 || equals == fields[i].length() - 1) {
					continue;
				}
				String key = fields[i].substring(0, equals);
				String value = fields[i].substring(equals + 1);
				try {
					switch (key) {
						case "vc" -> versionCode = Long.parseLong(value);
						case "sdk" -> sdk = Integer.parseInt(value);
						case "s" -> sessionId = MidletFailureRecovery.isSafeEventId(value) ? value : null;
						default -> { }
					}
				} catch (NumberFormatException ignored) {}
			}
			return new StateSummary(versionCode, sdk, sessionId);
		}

		private static TraceCapture captureTrace(ApplicationExitInfo info) {
			try (InputStream input = info.getTraceInputStream()) {
				if (input == null) {
					return null;
				}
				ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
				byte[] buffer = new byte[8192];
				int total = 0;
				while (total < MAX_TRACE_BYTES) {
					int count = input.read(buffer, 0, Math.min(buffer.length, MAX_TRACE_BYTES - total));
					if (count < 0) {
						break;
					}
					output.write(buffer, 0, count);
					total += count;
				}
				boolean truncated = total == MAX_TRACE_BYTES && input.read() >= 0;
				String kind;
				if (info.getReason() == ApplicationExitInfo.REASON_ANR) {
					kind = "anr-text";
				} else if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
						&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					kind = "native-tombstone-protobuf";
				} else {
					kind = "system-trace";
				}
				return new TraceCapture(output.toByteArray(), kind, truncated);
			} catch (IOException | RuntimeException | OutOfMemoryError e) {
				return null;
			}
		}
	}
}
