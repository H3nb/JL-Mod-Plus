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

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
	static final int SCHEMA_VERSION = 2;
	private static final int LEGACY_SCHEMA_VERSION = 1;
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
	// Android 17 documentation is transitional: newer references expose reason 17, while
	// current behavior documentation also identifies the exact marker below under REASON_OTHER.
	static final int REASON_MEMORY_LIMITER = 17;
	static final String MEMORY_LIMITER_ANON_SWAP_MARKER = "MemoryLimiter:AnonSwap";

	// Linux/Android signal numbers are ABI-stable values from signal(7). Keep these primitives in
	// common diagnostic code so unit tests and API23 devices do not depend on android.system stubs.
	static final int SIGNAL_ILLEGAL = 4;
	static final int SIGNAL_TRAP = 5;
	static final int SIGNAL_ABORT = 6;
	static final int SIGNAL_BUS = 7;
	static final int SIGNAL_FPE = 8;
	static final int SIGNAL_KILL = 9;
	static final int SIGNAL_SEGV = 11;
	static final int SIGNAL_TERM = 15;

	static final String SOURCE_APPLICATION_EXIT_INFO = "android-application-exit-info";
	static final String SOURCE_LEGACY_PROCESS_DISAPPEARANCE = "legacy-process-disappearance";
	static final String LEGACY_FALLBACK_DESCRIPTION =
			"Exact termination cause unavailable on Android 6-10; ApplicationExitInfo requires API 30+.";

	private static final int MAX_HISTORY_RESULTS = 64;
	private static final int MAX_DESCRIPTION_LENGTH = 1024;
	private static final int MAX_DEVICE_VALUE_LENGTH = 128;
	private static final int MAX_PROCESS_NAME_LENGTH = 256;
	private static final int MAX_DISPLAY_TRACE_BYTES = 128 * 1024;
	private static final int DISPLAY_TRACE_HEAD_BYTES = 96 * 1024;

	private static final Object INGEST_LOCK = new Object();

	private static final String TAG = ProcessExitStore.class.getSimpleName();
	private static final String RECORD_DIR = "diagnostics/process-exits";
	private static final String ACK_DIR = "diagnostics/process-exit-acks";
	private static final String RECORD_SUFFIX = ".properties";
	private static final String TRACE_SUFFIX = ".trace";
	private static final String ACK_SUFFIX = ".ack";
	private static final String BACKUP_SUFFIX = ".bak";
	private static final String NEW_SUFFIX = ".new";

	private static final String KEY_SCHEMA = "schemaVersion";
	private static final String KEY_KEY = "key";
	private static final String KEY_SOURCE = "source";
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
	private static final String KEY_ANDROID_RELEASE = "androidRelease";
	private static final String KEY_SESSION_ID = "sessionId";
	private static final String KEY_DEVICE_BRAND = "deviceBrand";
	private static final String KEY_DEVICE_MODEL = "deviceModel";
	private static final String KEY_PRIMARY_ABI = "primaryAbi";
	private static final String KEY_TRACE_KIND = "traceKind";
	private static final String KEY_TRACE_BYTES = "traceBytes";
	private static final String KEY_TRACE_TRUNCATED = "traceTruncated";
	private static final String KEY_ANR_TYPE = "anrType";
	private static final String KEY_ANR_TIMEOUT = "anrTimeoutMillis";
	private static final String KEY_ANR_ID = "anrId";
	private static final String KEY_ANR_USER_PERCEPTIBLE = "anrUserPerceptible";
	private static final String KEY_CONTEXT_RUN_ID = "context.runId";
	private static final String KEY_CONTEXT_BUILD_COMMIT = "context.buildCommit";
	private static final String KEY_CONTEXT_BUILD_VARIANT = "context.buildVariant";
	private static final String KEY_CONTEXT_LOCATION = "context.location";
	private static final String KEY_CONTEXT_PREVIOUS = "context.previousLocation";
	private static final String KEY_CONTEXT_ACTION = "context.action";
	private static final String KEY_CONTEXT_PHASE = "context.phase";
	private static final String KEY_CONTEXT_UPDATED = "context.updatedWallTimeMillis";
	private static final String KEY_CONTEXT_BREADCRUMB_COUNT = "context.breadcrumbCount";

	private static volatile String publishedSessionId;

	private ProcessExitStore() {}

	/** Publishes only current-process state; historical harvesting is deferred after startup. */
	static void initializeProcess(Context context, String processRole) {
		publishedSessionId = null;
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		try {
			Api30Impl.setProcessState(context, null);
		} catch (RuntimeException e) {
			Log.w(TAG, "Process-exit diagnostics initialization failed open", e);
		} catch (OutOfMemoryError e) {
			logLowMemory("Process-exit diagnostics initialization skipped under low memory");
		}
	}

	/** Publishes the immutable MIDlet session ID into Android's <=128-byte process state summary. */
	static void setMidletSession(Context context, String sessionId) {
		if (!MidletFailureRecovery.isSafeEventId(sessionId)) {
			return;
		}
		publishedSessionId = sessionId;
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		try {
			Api30Impl.setProcessState(context, sessionId);
		} catch (RuntimeException e) {
			Log.w(TAG, "Unable to publish MIDlet process-exit session identity", e);
		} catch (OutOfMemoryError e) {
			logLowMemory("Unable to publish MIDlet process-exit identity under low memory");
		}
	}

	/** Republishes the last high-level app context without losing a MIDlet session correlation key. */
	static void updateProcessContext(Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		try {
			Api30Impl.setProcessState(context, publishedSessionId);
		} catch (RuntimeException e) {
			Log.w(TAG, "Unable to publish process-exit app context", e);
		} catch (OutOfMemoryError e) {
			logLowMemory("Unable to publish process-exit app context under low memory");
		}
	}

	/** Copies useful framework exit history into app-private durable storage. */
	static void ingest(Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		// Startup maintenance and on-demand repository loading can enter here concurrently.
		// Keep serialization local to this store so persisted evidence remains single-writer.
		synchronized (INGEST_LOCK) {
			try {
				Api30Impl.ingest(context);
				prune(context);
			} catch (RuntimeException e) {
				Log.w(TAG, "Unable to ingest Android process-exit diagnostics", e);
			} catch (OutOfMemoryError e) {
				logLowMemory("Unable to ingest Android process-exit diagnostics under low memory");
			}
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
				// A user deletion marker outranks a stale/racing local projection. Keep the marker
				// durable until its source can no longer recreate this exact key.
				if (ProcessExitDeletionStore.isDeleted(context, snapshot.key)) {
					delete(context, snapshot);
					continue;
				}
				String retainedRole = retainedStoredProcessRole(
						context.getPackageName(),
						snapshot.processName,
						snapshot.processRole,
						snapshot.reason,
						snapshot.status,
						snapshot.importance,
						snapshot.description);
				// Re-apply the current retention policy to local projections. Policy pruning is not
				// a user deletion, so it deliberately does not create a deletion tombstone.
				if (retainedRole == null) {
					delete(context, snapshot);
					continue;
				}
				if (!same(snapshot.processRole, retainedRole)) {
					snapshot = snapshot.withProcessRole(retainedRole);
				}
				// The isolated MIDlet process is deliberately killed after a graceful MIDlet exit.
				// Exact journal outcome keeps that expected SIGKILL out of the crash inbox.
				if (isExpectedIntentionalSessionExit(context, snapshot)) {
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

	/** Refreshes system history, then returns one unacknowledged abnormal exit. */
	public static PendingExit findPendingExit(Context context) {
		ingest(context);
		return findPendingStoredExit(context);
	}

	/** Returns one pending exit from already-persisted evidence without harvesting system history. */
	public static PendingExit findPendingStoredExit(Context context) {
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
					|| isRepresentedByUnexpectedMidletFailure(context, record)) {
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

	/** Deletes canonical trace evidence before the metadata that points to it. */
	static boolean delete(Context context, Snapshot snapshot) {
		if (snapshot == null || snapshot.recordFile == null || !isSafeKey(snapshot.key)) {
			return false;
		}
		File directory = snapshot.recordFile.getParentFile();
		if (directory == null || !deleteAtomic(traceFile(directory, snapshot.key))) {
			return false;
		}
		if (!deleteAtomic(snapshot.recordFile)) {
			return false;
		}
		File marker = new File(acknowledgmentDirectory(context), snapshot.key + ACK_SUFFIX);
		return !marker.exists() || (marker.isFile() && marker.delete());
	}

	static String reasonLabel(Snapshot snapshot) {
		return snapshot == null ? null : reasonLabel(snapshot.reason, snapshot.description);
	}

	static String reasonLabel(int reason) {
		return reasonLabel(reason, null);
	}

	static String reasonLabel(int reason, String description) {
		if (isMemoryLimiterTermination(reason, description)) {
			return "Memory-limit termination";
		}
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
			case REASON_OTHER -> "Other system termination";
			default -> "Process termination (reason " + reason + ")";
		};
	}

	static boolean isMemoryLimiterTermination(int reason, String description) {
		return reason == REASON_MEMORY_LIMITER
				|| reason == REASON_OTHER
				&& description != null
				&& description.contains(MEMORY_LIMITER_ANON_SWAP_MARKER);
	}

	static boolean isKnownUserInitiatedCleanup(int reason, String description) {
		if (reason != REASON_OTHER || description == null) {
			return false;
		}
		return switch (description.trim()) {
			case "OneKeyClean",
					"ForceClean",
					"GarbageClean",
					"LockScreenClean",
					"GameClean",
					"OptimizationClean",
					"SwipeUpClean" -> true;
			default -> false;
		};
	}

	private static boolean isKnownMemoryEngineResourceTermination(int reason, String description) {
		if (isMemoryLimiterTermination(reason, description)) {
			return true;
		}
		if (reason != REASON_OTHER || description == null) {
			return false;
		}
		String marker = description.trim();
		return "AutoPowerKill".equals(marker) || "AutoThermalKill".equals(marker);
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
			case REASON_LOW_MEMORY,
					REASON_UNKNOWN,
					REASON_OTHER,
					REASON_MEMORY_LIMITER -> midletProcess || foregroundish;
			case REASON_SIGNALED -> status != SIGNAL_KILL || midletProcess || foregroundish;
			case REASON_DEPENDENCY_DIED, REASON_FREEZER -> midletProcess || foregroundish;
			case REASON_EXIT_SELF -> status != 0 && (midletProcess || foregroundish);
			case REASON_PERMISSION_CHANGE,
					REASON_USER_REQUESTED,
					REASON_USER_STOPPED,
					REASON_PACKAGE_STATE_CHANGE,
					REASON_PACKAGE_UPDATED -> false;
			default -> midletProcess || foregroundish;
		};
	}

	/** Historical ACRA helper-process exits are reporting infrastructure, not user incidents. */
	static boolean shouldRetainProcess(String processRole, int reason, int status, int importance) {
		return shouldRetainProcess(processRole, reason, status, importance, null);
	}

	static boolean shouldRetainProcess(String processRole, int reason, int status, int importance,
			String description) {
		if ("reporter".equals(processRole) || isKnownUserInitiatedCleanup(reason, description)) {
			return false;
		}
		if ("memory_engine".equals(processRole)) {
			return shouldRetainMemoryEngine(reason, status, description);
		}
		return shouldRetain(reason, status, importance, "midlet".equals(processRole));
	}

	private static boolean shouldRetainMemoryEngine(int reason, int status, String description) {
		if (isKnownMemoryEngineResourceTermination(reason, description)) {
			return true;
		}
		return switch (reason) {
			case REASON_CRASH,
					REASON_CRASH_NATIVE,
					REASON_ANR,
					REASON_INITIALIZATION_FAILURE,
					REASON_EXCESSIVE_RESOURCE_USAGE -> true;
			case REASON_SIGNALED -> status != SIGNAL_KILL;
			default -> false;
		};
	}

	static String currentProcessRole(String packageName, String processName, String storedRole) {
		String current = CrashReporter.classifyProcess(packageName, processName);
		return "other".equals(current) && storedRole != null && !"other".equals(storedRole)
				? storedRole : current;
	}

	static String retainedStoredProcessRole(
			String packageName,
			String processName,
			String storedRole,
			int reason,
			int status,
			int importance,
			String description) {
		String currentRole = currentProcessRole(packageName, processName, storedRole);
		return shouldRetainProcess(currentRole, reason, status, importance, description)
				? currentRole : null;
	}

	private static boolean same(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}

	static boolean isControlledRuntimeShutdown(Snapshot exit) {
		return exit != null
				&& isControlledRuntimeShutdown(exit.reason, exit.status, exit.processRole);
	}

	static boolean isControlledRuntimeShutdown(int reason, int status, String processRole) {
		return "midlet".equals(processRole)
				&& reason == REASON_SIGNALED
				&& status == SIGNAL_KILL;
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
		int schema = parseInt(p, KEY_SCHEMA);
		if (schema != LEGACY_SCHEMA_VERSION && schema != SCHEMA_VERSION) {
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
				readSource(p),
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
				optional(p, KEY_ANDROID_RELEASE),
				optional(p, KEY_SESSION_ID),
				optional(p, KEY_DEVICE_BRAND),
				optional(p, KEY_DEVICE_MODEL),
				optional(p, KEY_PRIMARY_ABI),
				optional(p, KEY_TRACE_KIND),
				declaredTraceBytes,
				Boolean.parseBoolean(p.getProperty(KEY_TRACE_TRUNCATED, "false")),
				parseIntDefault(p, KEY_ANR_TYPE, -1),
				parseLongDefault(p, KEY_ANR_TIMEOUT, -1),
				parseIntDefault(p, KEY_ANR_ID, -1),
				optionalBoolean(p, KEY_ANR_USER_PERCEPTIBLE),
				readStoredContext(p)
		);
	}

	private static void writeRecord(Snapshot snapshot) throws IOException {
		Properties p = new Properties();
		p.setProperty(KEY_SCHEMA, Integer.toString(SCHEMA_VERSION));
		p.setProperty(KEY_KEY, snapshot.key);
		put(p, KEY_SOURCE, snapshot.source);
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
		put(p, KEY_ANDROID_RELEASE, snapshot.androidRelease);
		put(p, KEY_SESSION_ID, snapshot.sessionId);
		put(p, KEY_DEVICE_BRAND, snapshot.deviceBrand);
		put(p, KEY_DEVICE_MODEL, snapshot.deviceModel);
		put(p, KEY_PRIMARY_ABI, snapshot.primaryAbi);
		put(p, KEY_TRACE_KIND, snapshot.traceKind);
		p.setProperty(KEY_TRACE_BYTES, Long.toString(snapshot.traceBytes));
		p.setProperty(KEY_TRACE_TRUNCATED, Boolean.toString(snapshot.traceTruncated));
		if (snapshot.anrType >= 0) p.setProperty(KEY_ANR_TYPE, Integer.toString(snapshot.anrType));
		if (snapshot.anrTimeoutMillis >= 0) {
			p.setProperty(KEY_ANR_TIMEOUT, Long.toString(snapshot.anrTimeoutMillis));
		}
		if (snapshot.anrId >= 0) p.setProperty(KEY_ANR_ID, Integer.toString(snapshot.anrId));
		if (snapshot.anrUserPerceptible != null) {
			p.setProperty(KEY_ANR_USER_PERCEPTIBLE,
					Boolean.toString(snapshot.anrUserPerceptible));
		}
		writeStoredContext(p, snapshot.appContext);
		writeProperties(snapshot.recordFile, p);
	}

	private static void writeStoredContext(Properties p, CrashContextStore.Snapshot context) {
		if (context == null) {
			return;
		}
		put(p, KEY_CONTEXT_RUN_ID, context.runId);
		put(p, KEY_CONTEXT_BUILD_COMMIT, context.buildCommit);
		put(p, KEY_CONTEXT_BUILD_VARIANT, context.buildVariant);
		put(p, KEY_CONTEXT_LOCATION, context.location);
		put(p, KEY_CONTEXT_PREVIOUS, context.previousLocation);
		put(p, KEY_CONTEXT_ACTION, context.action);
		put(p, KEY_CONTEXT_PHASE, context.phase);
		if (context.updatedWallTimeMillis > 0) {
			p.setProperty(KEY_CONTEXT_UPDATED, Long.toString(context.updatedWallTimeMillis));
		}
		p.setProperty(KEY_CONTEXT_BREADCRUMB_COUNT, Integer.toString(context.breadcrumbs.size()));
		for (int i = 0; i < context.breadcrumbs.size(); i++) {
			CrashContextStore.Breadcrumb breadcrumb = context.breadcrumbs.get(i);
			String prefix = "context.breadcrumb." + i + ".";
			if (breadcrumb.wallTimeMillis > 0) {
				p.setProperty(prefix + "time", Long.toString(breadcrumb.wallTimeMillis));
			}
			put(p, prefix + "location", breadcrumb.location);
			put(p, prefix + "action", breadcrumb.action);
			put(p, prefix + "phase", breadcrumb.phase);
		}
	}

	private static CrashContextStore.Snapshot readStoredContext(Properties p) {
		String runId = optional(p, KEY_CONTEXT_RUN_ID);
		if (!CrashContextStore.isSafeRunId(runId)) {
			return null;
		}
		try {
			int count = Math.min(CrashContextStore.MAX_BREADCRUMBS,
					Math.max(0, parseIntDefault(p, KEY_CONTEXT_BREADCRUMB_COUNT, 0)));
			ArrayList<CrashContextStore.Breadcrumb> breadcrumbs = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				String prefix = "context.breadcrumb." + i + ".";
				String location = CrashContextStore.normalizeToken(
						optional(p, prefix + "location"), CrashContextStore.MAX_LOCATION_LENGTH);
				String phase = CrashContextStore.normalizeToken(
						optional(p, prefix + "phase"), CrashContextStore.MAX_PHASE_LENGTH);
				if (location != null && phase != null) {
					breadcrumbs.add(new CrashContextStore.Breadcrumb(
							parseLongDefault(p, prefix + "time", 0),
							location,
							CrashContextStore.normalizeToken(optional(p, prefix + "action"),
									CrashContextStore.MAX_ACTION_LENGTH),
							phase
					));
				}
			}
			return new CrashContextStore.Snapshot(
					runId,
					null,
					bound(optional(p, KEY_CONTEXT_BUILD_COMMIT), 40),
					bound(optional(p, KEY_CONTEXT_BUILD_VARIANT), 48),
					CrashContextStore.normalizeToken(optional(p, KEY_CONTEXT_LOCATION),
							CrashContextStore.MAX_LOCATION_LENGTH),
					CrashContextStore.normalizeToken(optional(p, KEY_CONTEXT_PREVIOUS),
							CrashContextStore.MAX_LOCATION_LENGTH),
					CrashContextStore.normalizeToken(optional(p, KEY_CONTEXT_ACTION),
							CrashContextStore.MAX_ACTION_LENGTH),
					CrashContextStore.normalizeToken(optional(p, KEY_CONTEXT_PHASE),
							CrashContextStore.MAX_PHASE_LENGTH),
					parseLongDefault(p, KEY_CONTEXT_UPDATED, 0),
					breadcrumbs
			);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void writeProperties(File file, Properties properties) throws IOException {
		AtomicFile atomic = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			properties.store(output, null);
			atomic.finishWrite(output);
		} catch (Throwable error) {
			rollback(atomic, output);
			throw asIOException(error, "Unable to persist process-exit metadata");
		}
	}

	private static TraceWriteResult writeTrace(File file, InputStream input) throws IOException {
		AtomicFile atomic = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			TraceWriteResult result = copyBounded(input, output, MAX_TRACE_BYTES);
			atomic.finishWrite(output);
			return result;
		} catch (Throwable error) {
			rollback(atomic, output);
			throw asIOException(error, "Unable to persist process-exit trace");
		}
	}

	private static IOException asIOException(Throwable error, String message) {
		if (error instanceof IOException) {
			return (IOException) error;
		}
		if (error instanceof Error) {
			throw (Error) error;
		}
		return new IOException(message, error);
	}

	/** Copies at most maxBytes and probes one extra byte only to preserve truncation semantics. */
	static TraceWriteResult copyBounded(InputStream input, OutputStream output, int maxBytes)
			throws IOException {
		if (maxBytes < 0) {
			throw new IllegalArgumentException("maxBytes < 0");
		}
		byte[] buffer = new byte[Math.min(8192, Math.max(1, maxBytes))];
		int total = 0;
		while (total < maxBytes) {
			int count = input.read(buffer, 0, Math.min(buffer.length, maxBytes - total));
			if (count < 0) {
				break;
			}
			if (count == 0) {
				continue;
			}
			output.write(buffer, 0, count);
			total += count;
		}
		boolean truncated = total == maxBytes && input.read() >= 0;
		return new TraceWriteResult(total, truncated);
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

	private static boolean isRepresentedByUnexpectedMidletFailure(
			Context context, Snapshot exit) {
		MidletSessionJournal.Snapshot session =
				exit == null ? null : findSession(context, exit.sessionId);
		return session != null
				&& session.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
				&& MidletFailureRecovery.isSafeEventId(session.failureEventId)
				&& isControlledRuntimeShutdown(exit);
	}

	private static boolean isExpectedIntentionalSessionExit(Context context, Snapshot exit) {
		if (exit == null) return false;
		return isExpectedIntentionalSessionExit(
				findSession(context, exit.sessionId),
				exit.sessionId,
				exit.reason,
				exit.status,
				exit.processRole);
	}

	private static boolean isExpectedIntentionalSessionExit(
			Context context, String sessionId, int reason, int status, String processRole) {
		return isExpectedIntentionalSessionExit(
				findSession(context, sessionId), sessionId, reason, status, processRole);
	}

	static boolean isExpectedIntentionalSessionExit(
			MidletSessionJournal.Snapshot session, String exitSessionId,
			int reason, int status, String processRole) {
		return session != null
				&& session.sessionId != null
				&& session.sessionId.equals(exitSessionId)
				&& isIntentionalOutcome(session.outcome)
				&& isControlledRuntimeShutdown(reason, status, processRole);
	}

	private static boolean isIntentionalOutcome(MidletSessionJournal.Outcome outcome) {
		return outcome == MidletSessionJournal.Outcome.MIDLET_REQUEST
				|| outcome == MidletSessionJournal.Outcome.USER_STOP
				|| outcome == MidletSessionJournal.Outcome.LIFECYCLE_STOP;
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
		if (signal == SIGNAL_ABORT) return "SIGABRT";
		if (signal == SIGNAL_BUS) return "SIGBUS";
		if (signal == SIGNAL_FPE) return "SIGFPE";
		if (signal == SIGNAL_ILLEGAL) return "SIGILL";
		if (signal == SIGNAL_KILL) return "SIGKILL";
		if (signal == SIGNAL_SEGV) return "SIGSEGV";
		if (signal == SIGNAL_TERM) return "SIGTERM";
		if (signal == SIGNAL_TRAP) return "SIGTRAP";
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

	private static String readSource(Properties p) {
		String source = optional(p, KEY_SOURCE);
		if (SOURCE_APPLICATION_EXIT_INFO.equals(source)
				|| SOURCE_LEGACY_PROCESS_DISAPPEARANCE.equals(source)) {
			return source;
		}
		// Before schema v2 gained an explicit provenance field, only the API23-29 fallback used
		// this exact app-authored description. Other retained records came from ApplicationExitInfo.
		return LEGACY_FALLBACK_DESCRIPTION.equals(optional(p, KEY_DESCRIPTION))
				? SOURCE_LEGACY_PROCESS_DISAPPEARANCE
				: SOURCE_APPLICATION_EXIT_INFO;
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

	private static Boolean optionalBoolean(Properties p, String key) {
		String value = optional(p, key);
		return value == null ? null : Boolean.valueOf(value);
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
		final String source;
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
		final String androidRelease;
		final String sessionId;
		final String deviceBrand;
		final String deviceModel;
		final String primaryAbi;
		final String traceKind;
		final long traceBytes;
		final boolean traceTruncated;
		final int anrType;
		final long anrTimeoutMillis;
		final int anrId;
		final Boolean anrUserPerceptible;
		final CrashContextStore.Snapshot appContext;

		Snapshot(File recordFile, File traceFile, String key, String source, long timestampMillis,
				 String processName, String processRole, int pid, int reason, int status,
				 int importance, long pssKb, long rssKb, String description,
				 boolean lowMemoryKillReportSupported, long stateVersionCode, int stateSdk,
				 String androidRelease, String sessionId, String deviceBrand, String deviceModel,
				 String primaryAbi, String traceKind, long traceBytes, boolean traceTruncated,
				 int anrType, long anrTimeoutMillis, int anrId, Boolean anrUserPerceptible,
				 CrashContextStore.Snapshot appContext) {
			this.recordFile = recordFile;
			this.traceFile = traceFile;
			this.key = key;
			this.id = "exit:" + key;
			this.source = SOURCE_LEGACY_PROCESS_DISAPPEARANCE.equals(source)
					? SOURCE_LEGACY_PROCESS_DISAPPEARANCE : SOURCE_APPLICATION_EXIT_INFO;
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
			this.androidRelease = androidRelease;
			this.sessionId = sessionId;
			this.deviceBrand = deviceBrand;
			this.deviceModel = deviceModel;
			this.primaryAbi = primaryAbi;
			this.traceKind = traceKind;
			this.traceBytes = traceBytes;
			this.traceTruncated = traceTruncated;
			this.anrType = anrType;
			this.anrTimeoutMillis = anrTimeoutMillis;
			this.anrId = anrId;
			this.anrUserPerceptible = anrUserPerceptible;
			this.appContext = appContext;
		}

		Snapshot withProcessRole(String role) {
			return new Snapshot(
					recordFile,
					traceFile,
					key,
					source,
					timestampMillis,
					processName,
					role,
					pid,
					reason,
					status,
					importance,
					pssKb,
					rssKb,
					description,
					lowMemoryKillReportSupported,
					stateVersionCode,
					stateSdk,
					androidRelease,
					sessionId,
					deviceBrand,
					deviceModel,
					primaryAbi,
					traceKind,
					traceBytes,
					traceTruncated,
					anrType,
					anrTimeoutMillis,
					anrId,
					anrUserPerceptible,
					appContext);
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
			this.reason = reasonLabel(snapshot);
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

	static final class TraceWriteResult {
		final int bytes;
		final boolean truncated;

		TraceWriteResult(int bytes, boolean truncated) {
			this.bytes = bytes;
			this.truncated = truncated;
		}
	}

	private static final class TraceCapture {
		final int bytes;
		final String kind;
		final boolean truncated;

		TraceCapture(int bytes, String kind, boolean truncated) {
			this.bytes = bytes;
			this.kind = kind;
			this.truncated = truncated;
		}
	}

	private static final class AnrData {
		final int type;
		final long timeoutMillis;
		final int id;
		final Boolean userPerceptible;

		AnrData(int type, long timeoutMillis, int id, boolean userPerceptible) {
			this.type = type;
			this.timeoutMillis = timeoutMillis;
			this.id = id;
			this.userPerceptible = userPerceptible;
		}
	}

	@RequiresApi(37)
	private static final class Api37Impl {
		private Api37Impl() {}

		static AnrData readAnr(ApplicationExitInfo exit) {
			ApplicationExitInfo.AnrInfo info = exit.getAnrInfo();
			return info == null ? null : new AnrData(
					info.getAnrType(),
					info.getTimeoutMillis(),
					info.getAnrId(),
					info.isUserPerceptible());
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private static final class Api30Impl {
		private Api30Impl() {}

		static void setProcessState(Context context, String sessionId) {
			ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			if (manager == null) {
				return;
			}
			CrashContextStore.Snapshot appContext = CrashContextStore.currentSnapshot();
			byte[] state = ProcessStateSummary.build(
					appContext == null ? null : appContext.runId,
					appContext == null ? null : appContext.buildCommit,
					Build.VERSION.SDK_INT,
					Build.VERSION.RELEASE,
					sessionId,
					appContext == null ? null : appContext.location,
					appContext == null ? null : appContext.action,
					appContext == null ? null : appContext.phase
			);
			if (state.length <= ProcessStateSummary.MAX_BYTES) {
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
			if (history == null) {
				history = Collections.emptyList();
			}
			HashSet<String> historicalKeys = new HashSet<>(history.size());
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
				String key = buildKey(info);
				historicalKeys.add(key);
				if (ProcessExitDeletionStore.isDeleted(context, key)) {
					continue;
				}
				String processRole = CrashReporter.classifyProcess(context.getPackageName(), processName);
				ProcessStateSummary.Data state = ProcessStateSummary.parse(info.getProcessStateSummary());
				if (isExpectedIntentionalSessionExit(
						context, state.sessionId, info.getReason(), info.getStatus(), processRole)) {
					continue;
				}
				if (!shouldRetainProcess(
						processRole, info.getReason(), info.getStatus(), info.getImportance(),
						info.getDescription())) {
					continue;
				}

				File metadata = recordFile(directory, key);
				if (atomicExists(metadata)) {
					continue;
				}

				File candidateTraceFile = traceFile(directory, key);
				TraceCapture trace = captureTrace(info, candidateTraceFile);
				File retainedTraceFile = trace != null && trace.bytes > 0 ? candidateTraceFile : null;
				if (retainedTraceFile == null) {
					deleteAtomic(candidateTraceFile);
				}

				CrashContextStore.Snapshot appContext = resolveAppContext(context, processRole, state);
				String primaryAbi = Build.SUPPORTED_ABIS.length == 0 ? null : Build.SUPPORTED_ABIS[0];
				AnrData anr = info.getReason() == ApplicationExitInfo.REASON_ANR
						&& Build.VERSION.SDK_INT >= 37 ? Api37Impl.readAnr(info) : null;
				Snapshot snapshot = new Snapshot(
						metadata,
						retainedTraceFile,
						key,
						SOURCE_APPLICATION_EXIT_INFO,
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
						state.androidRelease,
						state.sessionId,
						bound(Build.BRAND, MAX_DEVICE_VALUE_LENGTH),
						bound(Build.MODEL, MAX_DEVICE_VALUE_LENGTH),
						primaryAbi,
						trace == null ? null : trace.kind,
						trace == null ? 0 : trace.bytes,
						trace != null && trace.truncated,
						anr == null ? -1 : anr.type,
						anr == null ? -1 : anr.timeoutMillis,
						anr == null ? -1 : anr.id,
						anr == null ? null : anr.userPerceptible,
						appContext
				);
				try {
					writeRecord(snapshot);
				} catch (IOException e) {
					Log.w(TAG, "Unable to persist process-exit record: " + key);
					if (retainedTraceFile != null) {
						deleteAtomic(retainedTraceFile);
					}
				} catch (Error error) {
					if (retainedTraceFile != null) {
						deleteAtomic(retainedTraceFile);
					}
					throw error;
				}
			}
			ProcessExitDeletionStore.pruneAgainstHistoricalKeys(context, historicalKeys);
		}

		private static CrashContextStore.Snapshot resolveAppContext(Context context, String processRole,
				ProcessStateSummary.Data state) {
			CrashContextStore.Snapshot stored = CrashContextStore.readForRun(context, state.runId);
			if (stored != null) {
				return stored;
			}
			if (state.runId == null) {
				return null;
			}
			return new CrashContextStore.Snapshot(
					state.runId,
					processRole,
					state.buildCommit,
					null,
					state.location,
					null,
					state.action,
					state.phase,
					0,
					Collections.emptyList()
			);
		}

		private static boolean isOwnedProcess(String packageName, String processName) {
			return processName != null
					&& (processName.equals(packageName) || processName.startsWith(packageName + ":"));
		}

		private static String buildKey(ApplicationExitInfo info) {
			return info.getTimestamp() + "-" + info.getPid() + "-"
					+ info.getReason() + "-" + info.getStatus();
		}

		private static TraceCapture captureTrace(ApplicationExitInfo info, File destination) {
			String kind;
			if (info.getReason() == ApplicationExitInfo.REASON_ANR) {
				kind = "anr-text";
			} else if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
					&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				kind = "native-tombstone-protobuf";
			} else {
				kind = "system-trace";
			}
			try (InputStream input = info.getTraceInputStream()) {
				if (input == null) {
					return null;
				}
				TraceWriteResult result = writeTrace(destination, input);
				return new TraceCapture(result.bytes, kind, result.truncated);
			} catch (IOException | RuntimeException | OutOfMemoryError e) {
				deleteAtomic(destination);
				return null;
			}
		}
	}
}
