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

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.h3nb.jlmodplus.BuildConfig;

/**
 * Single app-owned authority for Java diagnostic evidence.
 *
 * New records preserve bounded structured Throwable data while the Throwable is live. Historical
 * ACRA 5.13.1 JSON reports and the old fatal-v1 Properties files are migrated here as legacy
 * evidence; neither legacy format remains an active collector.
 */
final class JavaDiagnosticStore {
	static final int SCHEMA_VERSION = 2;
	static final int MAX_THROWABLES = 16;
	static final int MAX_FRAMES_PER_THROWABLE = 96;
	static final int MAX_TOTAL_FRAMES = 256;
	static final int MAX_STACK_CHARS = 256 * 1024;
	static final int MAX_MESSAGE_CHARS = 4096;

	private static final int MAX_TEXT_CHARS = 512;
	private static final int MAX_FILE_NAME_CHARS = 512;
	private static final int MAX_RECORD_COUNT = 64;
	private static final long MAX_RECORD_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;
	private static final int MAX_LEGACY_ACRA_BYTES = 1024 * 1024;

	private static final String TAG = JavaDiagnosticStore.class.getSimpleName();
	private static final String DIRECTORY = "diagnostics/java";
	private static final String SUFFIX = ".java.properties";
	private static final String LEGACY_FATAL_DIRECTORY = "diagnostics/java-fatal";
	private static final String LEGACY_FATAL_SUFFIX = ".fatal.properties";
	private static final String ACRA_UNAPPROVED = "ACRA-unapproved";
	private static final String ACRA_APPROVED = "ACRA-approved";
	private static final String ACRA_SUFFIX = ".stacktrace";
	private static final String BACKUP_SUFFIX = ".bak";
	private static final String NEW_SUFFIX = ".new";

	private static final String KEY_SCHEMA = "schemaVersion";
	private static final String KEY_KIND = "kind";
	private static final String KEY_TIMESTAMP = "timestampMillis";
	private static final String KEY_PROCESS_NAME = "processName";
	private static final String KEY_PROCESS_ROLE = "processRole";
	private static final String KEY_PID = "pid";
	private static final String KEY_THREAD_NAME = "threadName";
	private static final String KEY_THREAD_ID = "threadId";
	private static final String KEY_THREAD_PRIORITY = "threadPriority";
	private static final String KEY_SESSION_ID = "sessionId";
	private static final String KEY_MIDLET_NAME = "midletName";
	private static final String KEY_MIDLET_VERSION = "midletVersion";
	private static final String KEY_MIDLET_MAIN_CLASS = "midletMainClass";
	private static final String KEY_JAR_SHA256 = "jarSha256";
	private static final String KEY_APP_VERSION = "appVersion";
	private static final String KEY_ANDROID_RELEASE = "androidRelease";
	private static final String KEY_ANDROID_SDK = "androidSdk";
	private static final String KEY_BRAND = "brand";
	private static final String KEY_MODEL = "model";
	private static final String KEY_PRIMARY_ABI = "primaryAbi";
	private static final String KEY_CONTEXT_NOTE = "contextNote";
	private static final String KEY_STACK = "stackTrace";
	private static final String KEY_PRIMARY_INDEX = "primaryThrowableIndex";
	private static final String KEY_THROWABLE_COUNT = "throwableCount";
	private static final String KEY_LEGACY_EVENT_ID = "legacyEventId";
	private static final String KEY_LEGACY_BOUNDARY = "legacyBoundary";
	private static final String KEY_LEGACY_RECORD_ID = "legacyRecordId";
	private static final String KEY_CONTEXT_RUN_ID = "context.runId";
	private static final String KEY_CONTEXT_PROCESS_ROLE = "context.processRole";
	private static final String KEY_CONTEXT_BUILD_COMMIT = "context.buildCommit";
	private static final String KEY_CONTEXT_BUILD_VARIANT = "context.buildVariant";
	private static final String KEY_CONTEXT_LOCATION = "context.location";
	private static final String KEY_CONTEXT_PREVIOUS = "context.previousLocation";
	private static final String KEY_CONTEXT_ACTION = "context.action";
	private static final String KEY_CONTEXT_PHASE = "context.phase";
	private static final String KEY_CONTEXT_UPDATED = "context.updatedWallTimeMillis";
	private static final String KEY_CONTEXT_BREADCRUMB_COUNT = "context.breadcrumbCount";

	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static boolean installed;

	enum Kind {
		FATAL_UNCAUGHT(true, false),
		CAUGHT_INSTALLER(false, false),
		CAUGHT_APP_REPOSITORY(false, false),
		LEGACY_ACRA(true, true),
		LEGACY_FATAL(true, true);

		final boolean fatal;
		final boolean legacy;

		Kind(boolean fatal, boolean legacy) {
			this.fatal = fatal;
			this.legacy = legacy;
		}
	}

	private JavaDiagnosticStore() {}

	static synchronized void install(Context context, String processName, String processRole) {
		if (installed || context == null) return;
		Context appContext = context.getApplicationContext();
		Thread.UncaughtExceptionHandler upstream = Thread.getDefaultUncaughtExceptionHandler();
		FatalHandler handler = new FatalHandler(
				(thread, error) -> persistLive(
						appContext, Kind.FATAL_UNCAUGHT, thread, error,
						CrashReporter.primaryFailure(error), null, processName, processRole),
				upstream,
				() -> {
					Process.killProcess(Process.myPid());
				});
		Thread.setDefaultUncaughtExceptionHandler(handler);
		installed = true;
	}

	static void captureCaught(Context context, Kind kind, Throwable reported,
			Throwable primary, String contextNote) {
		if (context == null || reported == null || kind == null || kind.fatal) return;
		CrashReporter.DiagnosticContext diagnostic = CrashReporter.currentDiagnosticContext();
		try {
			persistLive(
					context.getApplicationContext(),
					kind,
					Thread.currentThread(),
					reported,
					primary == null ? reported : primary,
					contextNote,
					diagnostic == null ? null : diagnostic.processName,
					diagnostic == null ? null : diagnostic.processRole);
		} catch (Throwable error) {
			logFailure("Unable to persist caught Java diagnostic", error);
		}
	}

	private static void persistLive(Context context, Kind kind, Thread thread, Throwable reported,
			Throwable primary, String contextNote, String processName, String processRole)
			throws IOException {
		CrashReporter.DiagnosticContext diagnostic = CrashReporter.currentDiagnosticContext();
		CrashContextStore.Snapshot appContext = CrashContextStore.currentSnapshot();
		ThrowableCapture captured = captureThrowableChain(reported, primary);
		long timestamp = System.currentTimeMillis();
		String primaryAbi = Build.SUPPORTED_ABIS.length == 0 ? null : Build.SUPPORTED_ABIS[0];
		Snapshot snapshot = new Snapshot(
				null,
				kind,
				timestamp,
				bound(processName, MAX_TEXT_CHARS),
				bound(processRole, 32),
				Process.myPid(),
				bound(thread == null ? null : thread.getName(), MAX_TEXT_CHARS),
				thread == null ? -1 : thread.getId(),
				thread == null ? -1 : thread.getPriority(),
				diagnostic == null ? null : diagnostic.sessionId,
				diagnostic == null ? null : diagnostic.midletName,
				diagnostic == null ? null : diagnostic.midletVersion,
				diagnostic == null ? null : diagnostic.midletMainClass,
				diagnostic == null ? null : diagnostic.jarSha256,
				BuildConfig.VERSION_NAME,
				bound(Build.VERSION.RELEASE, 64),
				Build.VERSION.SDK_INT,
				bound(Build.BRAND, 128),
				bound(Build.MODEL, 128),
				bound(primaryAbi, 128),
				bound(contextNote, 768),
				captured.stackTrace,
				captured.throwables,
				captured.primaryIndex,
				null,
				null,
				appContext);
		writeNew(context, snapshot);
		prune(context);
	}

	static List<Snapshot> loadStored(Context context) {
		if (context == null) return Collections.emptyList();
		File[] files = directory(context).listFiles();
		if (files == null || files.length == 0) return Collections.emptyList();
		ArrayList<File> bases = new ArrayList<>();
		for (File file : files) {
			File base = canonicalRecordFile(file);
			if (base != null && !containsPath(bases, base)) bases.add(base);
		}
		bases.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
		ArrayList<Snapshot> result = new ArrayList<>(bases.size());
		for (File file : bases) {
			try {
				result.add(read(file));
			} catch (IOException | RuntimeException error) {
				Log.w(TAG, "Ignoring unreadable Java diagnostic: " + file.getName(), error);
			}
		}
		return result;
	}

	static void migrateLegacyAndPrune(Context context) {
		if (context == null) return;
		try {
			migrateAcraDirectory(context, context.getDir(ACRA_UNAPPROVED, Context.MODE_PRIVATE));
			migrateAcraDirectory(context, context.getDir(ACRA_APPROVED, Context.MODE_PRIVATE));
			migrateFatalV1(context);
			deduplicateMigratedFallbacks(context);
			prune(context);
		} catch (Throwable error) {
			logFailure("Java diagnostic legacy migration failed open", error);
		}
	}

	static boolean delete(Snapshot snapshot) {
		if (snapshot == null || snapshot.file == null) return false;
		return deleteAtomic(snapshot.file);
	}

	private static void migrateAcraDirectory(Context context, File sourceDirectory) {
		File[] files = sourceDirectory.listFiles(file ->
				file != null && file.isFile() && file.getName().endsWith(ACRA_SUFFIX));
		if (files == null) return;
		for (File source : files) {
			File destination = migratedFile(context, "acra", source);
			try {
				Snapshot legacy = readLegacyAcra(context, source);
				if (!commitMigration(source, destination, legacy)) {
					Log.w(TAG, "Unable to remove migrated legacy ACRA report: " + source.getName());
				}
			} catch (IOException | RuntimeException error) {
				Log.w(TAG, "Keeping legacy ACRA report after failed migration: " + source.getName());
			}
		}
	}

	private static void migrateFatalV1(Context context) {
		File sourceDirectory = new File(context.getFilesDir(), LEGACY_FATAL_DIRECTORY);
		File[] files = sourceDirectory.listFiles(file ->
				file != null && file.isFile() && file.getName().endsWith(LEGACY_FATAL_SUFFIX));
		if (files == null) return;
		for (File source : files) {
			File destination = migratedFile(context, "fatal-v1", source);
			try {
				Snapshot legacy = readLegacyFatalV1(context, source);
				Snapshot equivalent = findRicherEquivalent(loadStored(context), legacy);
				if (equivalent == null) {
					if (!commitMigration(source, destination, legacy)) {
						Log.w(TAG, "Unable to remove migrated fatal-v1 report: " + source.getName());
					}
				} else {
					// The old repository intentionally kept the fatal fallback as the logical
					// standalone record when the same crash also existed in ACRA. Preserve that
					// exact identity so any already-exported bundle remains owned after migration.
					write(equivalent.file,
							equivalent.withLegacyRecordId(legacy.legacyRecordId));
					read(equivalent.file);
					if (!source.delete()) {
						Log.w(TAG, "Unable to remove redundant fatal-v1 report: " + source.getName());
					}
				}
			} catch (IOException | RuntimeException error) {
				Log.w(TAG, "Keeping fatal-v1 report after failed migration: " + source.getName());
			}
		}
	}

	private static void deduplicateMigratedFallbacks(Context context) {
		List<Snapshot> records = loadStored(context);
		for (Snapshot candidate : records) {
			if (candidate.kind != Kind.LEGACY_FATAL) continue;
			Snapshot richer = findRicherEquivalent(records, candidate);
			if (richer != null && !delete(candidate)) {
				Log.w(TAG, "Unable to remove migrated duplicate fatal-v1 evidence");
			}
		}
	}

	static boolean commitMigration(File source, File destination, Snapshot snapshot)
			throws IOException {
		if (!atomicExists(destination)) {
			write(destination, snapshot.withFile(destination));
		} else {
			read(destination);
		}
		return !source.exists() || source.delete();
	}

	static Snapshot findRicherEquivalent(List<Snapshot> existing, Snapshot candidate) {
		if (candidate == null || candidate.stackTrace == null || candidate.appContext == null
				|| candidate.appContext.runId == null) return null;
		for (Snapshot item : existing) {
			if (item.kind != Kind.LEGACY_ACRA || item.stackTrace == null || item.appContext == null) continue;
			if (candidate.appContext.runId.equals(item.appContext.runId)
					&& equals(candidate.processRole, item.processRole)
					&& candidate.stackTrace.equals(item.stackTrace)) {
				return item;
			}
		}
		return null;
	}

	private static Snapshot readLegacyAcra(Context context, File source) throws IOException {
		String raw = readUtf8Bounded(source, MAX_LEGACY_ACRA_BYTES);
		JSONObject data;
		try {
			data = new JSONObject(raw);
		} catch (Exception error) {
			throw new IOException("Invalid ACRA JSON", error);
		}
		JSONObject custom = object(data.opt("CUSTOM_DATA"));
		String stack = jsonString(data, "STACK_TRACE");
		if (stack == null) throw new IOException("Legacy ACRA report has no stack");
		List<ThrowableData> chain = parseLegacyThrowableChain(stack);
		String boundary = legacyBoundary(stack);
		int primaryIndex = legacyPrimaryIndex(chain, boundary);
		String runId = customString(custom, "jlmod.run.id");
		String reportKey = jsonString(data, "REPORT_ID");
		if (reportKey == null) reportKey = source.getName();
		CrashContextStore.Snapshot appContext = resolveLegacyContext(
				context,
				runId,
				customString(custom, "jlmod.process.role"),
				customString(custom, "jlmod.build.commit"),
				customString(custom, "jlmod.build.variant"),
				customString(custom, "jlmod.context.location"),
				customString(custom, "jlmod.context.previous"),
				customString(custom, "jlmod.context.action"),
				customString(custom, "jlmod.context.phase"),
				parseLong(customString(custom, "jlmod.context.updated"), 0));
		return new Snapshot(
				null,
				Kind.LEGACY_ACRA,
				source.lastModified(),
				customString(custom, "jlmod.process.name"),
				customString(custom, "jlmod.process.role"),
				parseInt(customString(custom, "jlmod.process.pid"), -1),
				jsonString(data, "THREAD_DETAILS"),
				-1,
				-1,
				customString(custom, "jlmod.session.id"),
				customString(custom, "jlmod.midlet.name"),
				customString(custom, "jlmod.midlet.version"),
				customString(custom, "jlmod.midlet.mainClass"),
				customString(custom, "jlmod.midlet.jar.sha256"),
				jsonString(data, "APP_VERSION_NAME"),
				jsonString(data, "ANDROID_VERSION"),
				-1,
				jsonString(data, "BRAND"),
				jsonString(data, "PHONE_MODEL"),
				null,
				null,
				boundStack(stack),
				chain,
				primaryIndex,
				legacyEventId(stack),
				bound(boundary, 64),
				"acra:" + reportKey,
				appContext);
	}

	private static Snapshot readLegacyFatalV1(Context context, File source) throws IOException {
		Properties p = new Properties();
		try (FileInputStream input = new FileInputStream(source)) {
			p.load(input);
		}
		if (!"1".equals(p.getProperty("schemaVersion"))) {
			throw new IOException("Unsupported fatal-v1 schema");
		}
		String stack = value(p, "stackTrace");
		if (stack == null) throw new IOException("Fatal-v1 report has no stack");
		List<ThrowableData> chain = parseLegacyThrowableChain(stack);
		String boundary = legacyBoundary(stack);
		String runId = value(p, "runId");
		CrashContextStore.Snapshot appContext = resolveLegacyContext(
				context,
				runId,
				value(p, "processRole"),
				value(p, "buildCommit"),
				value(p, "buildVariant"),
				value(p, "location"),
				value(p, "previousLocation"),
				value(p, "action"),
				value(p, "phase"),
				parseLong(p.getProperty("contextUpdated"), 0));
		String oldAndroid = value(p, "androidVersion");
		int oldSdk = parseLegacySdk(oldAndroid);
		return new Snapshot(
				null,
				Kind.LEGACY_FATAL,
				parseLong(p.getProperty("timestampMillis"), source.lastModified()),
				null,
				value(p, "processRole"),
				-1,
				value(p, "thread"),
				-1,
				-1,
				null,
				null,
				null,
				null,
				null,
				value(p, "appVersion"),
				null,
				oldSdk,
				value(p, "brand"),
				value(p, "model"),
				null,
				null,
				boundStack(stack),
				chain,
				legacyPrimaryIndex(chain, boundary),
				legacyEventId(stack),
				bound(boundary, 64),
				"acra:" + source.getName(),
				appContext);
	}

	private static CrashContextStore.Snapshot resolveLegacyContext(Context context, String runId,
			String processRole, String buildCommit, String buildVariant, String location,
			String previous, String action, String phase, long updated) {
		CrashContextStore.Snapshot stored = CrashContextStore.readForRun(context, runId);
		if (stored != null) return stored;
		if (!CrashContextStore.isSafeRunId(runId)) return null;
		return new CrashContextStore.Snapshot(
				runId,
				bound(processRole, 16),
				bound(buildCommit, 40),
				bound(buildVariant, 48),
				CrashContextStore.normalizeToken(location, CrashContextStore.MAX_LOCATION_LENGTH),
				CrashContextStore.normalizeToken(previous, CrashContextStore.MAX_LOCATION_LENGTH),
				CrashContextStore.normalizeToken(action, CrashContextStore.MAX_ACTION_LENGTH),
				CrashContextStore.normalizeToken(phase, CrashContextStore.MAX_PHASE_LENGTH),
				updated,
				Collections.emptyList());
	}

	private static int parseLegacySdk(String value) {
		int parsed = parseInt(value, -1);
		return parsed >= 23 && parsed <= 100 ? parsed : -1;
	}

	private static JSONObject object(Object value) {
		if (value instanceof JSONObject) return (JSONObject) value;
		if (value instanceof String) {
			try {
				return new JSONObject((String) value);
			} catch (Exception ignored) {}
		}
		return null;
	}

	private static String jsonString(JSONObject object, String key) {
		if (object == null) return null;
		Object value = object.opt(key);
		if (value == null || JSONObject.NULL.equals(value)) return null;
		return bound(String.valueOf(value), MAX_STACK_CHARS);
	}

	private static String customString(JSONObject custom, String key) {
		return jsonString(custom, key);
	}

	private static int legacyPrimaryIndex(List<ThrowableData> chain, String boundary) {
		if (chain.isEmpty()) return -1;
		if (boundary != null) {
			boolean lifecycle = boundary.startsWith("LIFECYCLE_");
			int index = lifecycle ? 2 : 1;
			return Math.min(index, chain.size() - 1);
		}
		ThrowableData first = chain.get(0);
		if (first.className != null
				&& first.className.endsWith("CrashReporter$InstallerFailureException")
				&& chain.size() > 1) {
			return 1;
		}
		return 0;
	}

	private static String legacyEventId(String stack) {
		return markerValue(stack, "eventId=");
	}

	private static String legacyBoundary(String stack) {
		return markerValue(stack, "boundary=");
	}

	private static String markerValue(String text, String marker) {
		if (text == null) return null;
		int start = text.indexOf(marker);
		if (start < 0) return null;
		start += marker.length();
		int end = start;
		while (end < text.length()) {
			char c = text.charAt(end);
			if (c == ';' || Character.isWhitespace(c)) break;
			end++;
		}
		String value = end <= start ? null : text.substring(start, end);
		return bound(value, 96);
	}

	static List<ThrowableData> parseLegacyThrowableChain(String stack) {
		if (stack == null || stack.trim().isEmpty()) return Collections.emptyList();
		ArrayList<MutableThrowable> mutable = new ArrayList<>();
		MutableThrowable current = null;
		for (String raw : stack.split("\\r?\\n")) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("... ") || line.startsWith("Suppressed:")) continue;
			if (line.startsWith("Caused by:")) {
				if (mutable.size() >= MAX_THROWABLES) break;
				current = parseThrowableHeader(line.substring("Caused by:".length()).trim());
				if (current != null) mutable.add(current);
				continue;
			}
			if (line.startsWith("at ")) {
				if (current != null && current.frames.size() < MAX_FRAMES_PER_THROWABLE) {
					FrameData frame = parseLegacyFrame(line.substring(3));
					if (frame != null) current.frames.add(frame);
				}
				continue;
			}
			if (current == null && mutable.size() < MAX_THROWABLES) {
				current = parseThrowableHeader(line);
				if (current != null) mutable.add(current);
			}
		}
		ArrayList<ThrowableData> result = new ArrayList<>(mutable.size());
		for (MutableThrowable item : mutable) {
			result.add(new ThrowableData(item.className, item.message, item.frames));
		}
		return result;
	}

	private static MutableThrowable parseThrowableHeader(String line) {
		if (line == null || line.isEmpty() || line.startsWith("eventId=") || "jlamf".equals(line)) {
			return null;
		}
		int colon = line.indexOf(':');
		String className = colon < 0 ? line : line.substring(0, colon);
		if (className.indexOf(' ') >= 0 || className.indexOf('.') < 0) return null;
		String message = colon < 0 ? null : line.substring(colon + 1).trim();
		return new MutableThrowable(bound(className, MAX_TEXT_CHARS), bound(message, MAX_MESSAGE_CHARS));
	}

	private static FrameData parseLegacyFrame(String value) {
		if (value == null) return null;
		int open = value.indexOf('(');
		int close = value.lastIndexOf(')');
		String method = open < 0 ? value : value.substring(0, open);
		int dot = method.lastIndexOf('.');
		if (dot <= 0 || dot + 1 >= method.length()) return null;
		String className = method.substring(0, dot);
		String methodName = method.substring(dot + 1);
		String fileName = null;
		int lineNumber = -1;
		boolean nativeMethod = false;
		if (open >= 0 && close > open) {
			String location = value.substring(open + 1, close);
			if ("Native Method".equals(location)) {
				nativeMethod = true;
			} else if (!"Unknown Source".equals(location)) {
				int colon = location.lastIndexOf(':');
				if (colon > 0) {
					fileName = location.substring(0, colon);
					lineNumber = parseInt(location.substring(colon + 1), -1);
				} else {
					fileName = location;
				}
			}
		}
		return new FrameData(
				bound(className, MAX_TEXT_CHARS),
				bound(methodName, MAX_TEXT_CHARS),
				bound(fileName, MAX_FILE_NAME_CHARS),
				lineNumber,
				nativeMethod);
	}

	static ThrowableCapture captureThrowableChain(Throwable reported, Throwable primary) {
		ArrayList<ThrowableData> chain = new ArrayList<>();
		IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
		Throwable current = reported;
		int primaryIndex = -1;
		int totalFrames = 0;
		for (int i = 0; current != null && i < MAX_THROWABLES && !seen.containsKey(current); i++) {
			seen.put(current, Boolean.TRUE);
			if (current == primary) primaryIndex = chain.size();
			ArrayList<FrameData> frames = new ArrayList<>();
			StackTraceElement[] stack = current.getStackTrace();
			if (stack != null) {
				int limit = Math.min(stack.length,
						Math.min(MAX_FRAMES_PER_THROWABLE, MAX_TOTAL_FRAMES - totalFrames));
				for (int j = 0; j < limit; j++) {
					StackTraceElement frame = stack[j];
					frames.add(new FrameData(
							bound(frame.getClassName(), MAX_TEXT_CHARS),
							bound(frame.getMethodName(), MAX_TEXT_CHARS),
							bound(frame.getFileName(), MAX_FILE_NAME_CHARS),
							frame.getLineNumber(),
							frame.isNativeMethod()));
				}
				totalFrames += limit;
			}
			chain.add(new ThrowableData(
					bound(current.getClass().getName(), MAX_TEXT_CHARS),
					bound(current.getMessage(), MAX_MESSAGE_CHARS),
					frames));
			// Preserve the bounded cause chain even after the frame budget is exhausted so an
			// explicitly identified primary Throwable cannot disappear behind large wrappers.
			current = current.getCause();
		}
		if (primaryIndex < 0 && !chain.isEmpty()) primaryIndex = 0;
		return new ThrowableCapture(chain, primaryIndex, boundedStackTrace(reported));
	}

	private static String boundedStackTrace(Throwable error) {
		StringWriter buffer = new StringWriter(4096);
		error.printStackTrace(new PrintWriter(buffer));
		return boundStack(buffer.toString());
	}

	private static String boundStack(String stack) {
		if (stack == null) return null;
		return stack.length() <= MAX_STACK_CHARS ? stack
				: stack.substring(0, MAX_STACK_CHARS) + "\n[stack trace truncated]";
	}

	private static void writeNew(Context context, Snapshot snapshot) throws IOException {
		File directory = directory(context);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IOException("Unable to create Java diagnostic directory");
		}
		File destination = null;
		for (int attempt = 0; attempt < 32; attempt++) {
			String key = snapshot.timestampMillis + "-" + Math.max(0, snapshot.pid) + "-"
					+ Long.toUnsignedString(SEQUENCE.incrementAndGet(), 36);
			File candidate = new File(directory, key + SUFFIX);
			if (!atomicExists(candidate)) {
				destination = candidate;
				break;
			}
		}
		if (destination == null) throw new IOException("Unable to allocate Java diagnostic identity");
		write(destination, snapshot.withFile(destination));
	}

	static void write(File destination, Snapshot snapshot) throws IOException {
		Properties p = new Properties();
		p.setProperty(KEY_SCHEMA, Integer.toString(SCHEMA_VERSION));
		p.setProperty(KEY_KIND, snapshot.kind.name());
		p.setProperty(KEY_TIMESTAMP, Long.toString(snapshot.timestampMillis));
		put(p, KEY_PROCESS_NAME, snapshot.processName);
		put(p, KEY_PROCESS_ROLE, snapshot.processRole);
		p.setProperty(KEY_PID, Integer.toString(snapshot.pid));
		put(p, KEY_THREAD_NAME, snapshot.threadName);
		p.setProperty(KEY_THREAD_ID, Long.toString(snapshot.threadId));
		p.setProperty(KEY_THREAD_PRIORITY, Integer.toString(snapshot.threadPriority));
		put(p, KEY_SESSION_ID, snapshot.sessionId);
		put(p, KEY_MIDLET_NAME, snapshot.midletName);
		put(p, KEY_MIDLET_VERSION, snapshot.midletVersion);
		put(p, KEY_MIDLET_MAIN_CLASS, snapshot.midletMainClass);
		put(p, KEY_JAR_SHA256, snapshot.jarSha256);
		put(p, KEY_APP_VERSION, snapshot.appVersion);
		put(p, KEY_ANDROID_RELEASE, snapshot.androidRelease);
		p.setProperty(KEY_ANDROID_SDK, Integer.toString(snapshot.androidSdk));
		put(p, KEY_BRAND, snapshot.brand);
		put(p, KEY_MODEL, snapshot.model);
		put(p, KEY_PRIMARY_ABI, snapshot.primaryAbi);
		put(p, KEY_CONTEXT_NOTE, snapshot.contextNote);
		put(p, KEY_STACK, snapshot.stackTrace);
		p.setProperty(KEY_PRIMARY_INDEX, Integer.toString(snapshot.primaryThrowableIndex));
		p.setProperty(KEY_THROWABLE_COUNT, Integer.toString(snapshot.throwables.size()));
		put(p, KEY_LEGACY_EVENT_ID, snapshot.legacyEventId);
		put(p, KEY_LEGACY_BOUNDARY, snapshot.legacyBoundary);
		put(p, KEY_LEGACY_RECORD_ID, snapshot.legacyRecordId);
		for (int i = 0; i < snapshot.throwables.size(); i++) {
			ThrowableData item = snapshot.throwables.get(i);
			String prefix = "throwable." + i + ".";
			put(p, prefix + "class", item.className);
			put(p, prefix + "message", item.message);
			p.setProperty(prefix + "frameCount", Integer.toString(item.frames.size()));
			for (int j = 0; j < item.frames.size(); j++) {
				FrameData frame = item.frames.get(j);
				String fp = prefix + "frame." + j + ".";
				put(p, fp + "class", frame.className);
				put(p, fp + "method", frame.methodName);
				put(p, fp + "file", frame.fileName);
				p.setProperty(fp + "line", Integer.toString(frame.lineNumber));
				p.setProperty(fp + "native", Boolean.toString(frame.nativeMethod));
			}
		}
		writeContext(p, snapshot.appContext);
		writeAtomic(destination, p);
	}

	static Snapshot read(File file) throws IOException {
		Properties p = new Properties();
		try (FileInputStream input = new AtomicFile(file).openRead()) {
			p.load(input);
		}
		if (parseInt(p.getProperty(KEY_SCHEMA), -1) != SCHEMA_VERSION) {
			throw new IOException("Unsupported Java diagnostic schema");
		}
		Kind kind;
		try {
			kind = Kind.valueOf(require(p, KEY_KIND));
		} catch (IllegalArgumentException error) {
			throw new IOException("Invalid Java diagnostic kind", error);
		}
		int count = Math.min(MAX_THROWABLES,
				Math.max(0, parseInt(p.getProperty(KEY_THROWABLE_COUNT), 0)));
		ArrayList<ThrowableData> chain = new ArrayList<>(count);
		int totalFrames = 0;
		for (int i = 0; i < count; i++) {
			String prefix = "throwable." + i + ".";
			int frameCount = Math.min(MAX_FRAMES_PER_THROWABLE,
					Math.max(0, parseInt(p.getProperty(prefix + "frameCount"), 0)));
			ArrayList<FrameData> frames = new ArrayList<>();
			for (int j = 0; j < frameCount && totalFrames < MAX_TOTAL_FRAMES; j++) {
				String fp = prefix + "frame." + j + ".";
				frames.add(new FrameData(
						value(p, fp + "class"),
						value(p, fp + "method"),
						value(p, fp + "file"),
						parseInt(p.getProperty(fp + "line"), -1),
						Boolean.parseBoolean(p.getProperty(fp + "native", "false"))));
				totalFrames++;
			}
			chain.add(new ThrowableData(value(p, prefix + "class"), value(p, prefix + "message"), frames));
		}
		return new Snapshot(
				file,
				kind,
				parseLong(p.getProperty(KEY_TIMESTAMP), file.lastModified()),
				value(p, KEY_PROCESS_NAME),
				value(p, KEY_PROCESS_ROLE),
				parseInt(p.getProperty(KEY_PID), -1),
				value(p, KEY_THREAD_NAME),
				parseLong(p.getProperty(KEY_THREAD_ID), -1),
				parseInt(p.getProperty(KEY_THREAD_PRIORITY), -1),
				value(p, KEY_SESSION_ID),
				value(p, KEY_MIDLET_NAME),
				value(p, KEY_MIDLET_VERSION),
				value(p, KEY_MIDLET_MAIN_CLASS),
				value(p, KEY_JAR_SHA256),
				value(p, KEY_APP_VERSION),
				value(p, KEY_ANDROID_RELEASE),
				parseInt(p.getProperty(KEY_ANDROID_SDK), -1),
				value(p, KEY_BRAND),
				value(p, KEY_MODEL),
				value(p, KEY_PRIMARY_ABI),
				value(p, KEY_CONTEXT_NOTE),
				value(p, KEY_STACK),
				chain,
				parseInt(p.getProperty(KEY_PRIMARY_INDEX), chain.isEmpty() ? -1 : 0),
				value(p, KEY_LEGACY_EVENT_ID),
				value(p, KEY_LEGACY_BOUNDARY),
				value(p, KEY_LEGACY_RECORD_ID),
				readContext(p));
	}

	private static void writeContext(Properties p, CrashContextStore.Snapshot context) {
		if (context == null) return;
		put(p, KEY_CONTEXT_RUN_ID, context.runId);
		put(p, KEY_CONTEXT_PROCESS_ROLE, context.processRole);
		put(p, KEY_CONTEXT_BUILD_COMMIT, context.buildCommit);
		put(p, KEY_CONTEXT_BUILD_VARIANT, context.buildVariant);
		put(p, KEY_CONTEXT_LOCATION, context.location);
		put(p, KEY_CONTEXT_PREVIOUS, context.previousLocation);
		put(p, KEY_CONTEXT_ACTION, context.action);
		put(p, KEY_CONTEXT_PHASE, context.phase);
		p.setProperty(KEY_CONTEXT_UPDATED, Long.toString(context.updatedWallTimeMillis));
		p.setProperty(KEY_CONTEXT_BREADCRUMB_COUNT, Integer.toString(context.breadcrumbs.size()));
		for (int i = 0; i < context.breadcrumbs.size(); i++) {
			CrashContextStore.Breadcrumb item = context.breadcrumbs.get(i);
			String prefix = "context.breadcrumb." + i + ".";
			p.setProperty(prefix + "time", Long.toString(item.wallTimeMillis));
			put(p, prefix + "location", item.location);
			put(p, prefix + "action", item.action);
			put(p, prefix + "phase", item.phase);
		}
	}

	private static CrashContextStore.Snapshot readContext(Properties p) {
		String runId = value(p, KEY_CONTEXT_RUN_ID);
		if (!CrashContextStore.isSafeRunId(runId)) return null;
		int count = Math.min(CrashContextStore.MAX_BREADCRUMBS,
				Math.max(0, parseInt(p.getProperty(KEY_CONTEXT_BREADCRUMB_COUNT), 0)));
		ArrayList<CrashContextStore.Breadcrumb> breadcrumbs = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String prefix = "context.breadcrumb." + i + ".";
			String location = CrashContextStore.normalizeToken(
					value(p, prefix + "location"), CrashContextStore.MAX_LOCATION_LENGTH);
			String phase = CrashContextStore.normalizeToken(
					value(p, prefix + "phase"), CrashContextStore.MAX_PHASE_LENGTH);
			if (location != null && phase != null) {
				breadcrumbs.add(new CrashContextStore.Breadcrumb(
						parseLong(p.getProperty(prefix + "time"), 0),
						location,
						CrashContextStore.normalizeToken(
								value(p, prefix + "action"), CrashContextStore.MAX_ACTION_LENGTH),
						phase));
			}
		}
		return new CrashContextStore.Snapshot(
				runId,
				value(p, KEY_CONTEXT_PROCESS_ROLE),
				value(p, KEY_CONTEXT_BUILD_COMMIT),
				value(p, KEY_CONTEXT_BUILD_VARIANT),
				CrashContextStore.normalizeToken(
						value(p, KEY_CONTEXT_LOCATION), CrashContextStore.MAX_LOCATION_LENGTH),
				CrashContextStore.normalizeToken(
						value(p, KEY_CONTEXT_PREVIOUS), CrashContextStore.MAX_LOCATION_LENGTH),
				CrashContextStore.normalizeToken(
						value(p, KEY_CONTEXT_ACTION), CrashContextStore.MAX_ACTION_LENGTH),
				CrashContextStore.normalizeToken(
						value(p, KEY_CONTEXT_PHASE), CrashContextStore.MAX_PHASE_LENGTH),
				parseLong(p.getProperty(KEY_CONTEXT_UPDATED), 0),
				breadcrumbs);
	}

	private static File migratedFile(Context context, String sourceKind, File source) {
		File directory = directory(context);
		if (!directory.isDirectory()) directory.mkdirs();
		String identity = sourceKind + "\n"
				+ (source.getParentFile() == null ? "" : source.getParentFile().getName())
				+ "\n" + source.getName();
		return new File(directory, "legacy-" + sourceKind + "-" + shortHash(identity) + SUFFIX);
	}

	private static String shortHash(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(16);
			for (int i = 0; i < 8; i++) {
				result.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError("SHA-256 unavailable", impossible);
		}
	}

	private static String readUtf8Bounded(File file, int maxBytes) throws IOException {
		if (file.length() > maxBytes) throw new IOException("Legacy report exceeds migration bound");
		try (FileInputStream input = new FileInputStream(file)) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int total = 0;
			int count;
			while ((count = input.read(buffer)) != -1) {
				total += count;
				if (total > maxBytes) throw new IOException("Legacy report exceeds migration bound");
				output.write(buffer, 0, count);
			}
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static void writeAtomic(File destination, Properties p) throws IOException {
		AtomicFile atomic = new AtomicFile(destination);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			p.store(output, null);
			atomic.finishWrite(output);
		} catch (Throwable error) {
			if (output != null) {
				try {
					atomic.failWrite(output);
				} catch (Throwable ignored) {}
			}
			if (error instanceof IOException) throw (IOException) error;
			if (error instanceof Error) throw (Error) error;
			throw new IOException("Unable to persist Java diagnostic", error);
		}
	}

	private static void prune(Context context) {
		List<Snapshot> records = loadStored(context);
		long now = System.currentTimeMillis();
		for (int i = 0; i < records.size(); i++) {
			Snapshot item = records.get(i);
			long age = item.timestampMillis > 0 && now >= item.timestampMillis
					? now - item.timestampMillis : 0;
			if (i >= MAX_RECORD_COUNT || age > MAX_RECORD_AGE_MILLIS) {
				delete(item);
			}
		}
	}

	private static File directory(Context context) {
		return new File(context.getFilesDir(), DIRECTORY);
	}

	private static File canonicalRecordFile(File file) {
		if (file == null || !file.isFile()) return null;
		String path = file.getPath();
		if (path.endsWith(SUFFIX)) return file;
		if (path.endsWith(SUFFIX + BACKUP_SUFFIX)) {
			return new File(path.substring(0, path.length() - BACKUP_SUFFIX.length()));
		}
		return null;
	}

	private static boolean containsPath(List<File> files, File candidate) {
		for (File file : files) if (file.getPath().equals(candidate.getPath())) return true;
		return false;
	}

	private static boolean atomicExists(File file) {
		return file.isFile() || new File(file.getPath() + BACKUP_SUFFIX).isFile();
	}

	private static boolean deleteAtomic(File file) {
		boolean result = true;
		result &= deleteIfExists(file);
		result &= deleteIfExists(new File(file.getPath() + BACKUP_SUFFIX));
		result &= deleteIfExists(new File(file.getPath() + NEW_SUFFIX));
		return result;
	}

	private static boolean deleteIfExists(File file) {
		return !file.exists() || file.isFile() && file.delete();
	}

	private static String require(Properties p, String key) throws IOException {
		String value = value(p, key);
		if (value == null) throw new IOException("Missing Java diagnostic field: " + key);
		return value;
	}

	private static void put(Properties p, String key, String value) {
		if (value != null && !value.trim().isEmpty()) p.setProperty(key, value);
	}

	private static String value(Properties p, String key) {
		String value = p.getProperty(key);
		return value == null || value.trim().isEmpty() ? null : value;
	}

	private static int parseInt(String value, int fallback) {
		try {
			return value == null ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static long parseLong(String value, long fallback) {
		try {
			return value == null ? fallback : Long.parseLong(value.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static String bound(String value, int maxLength) {
		if (value == null || maxLength <= 0) return null;
		String normalized = value.replace('\u0000', ' ').replace('\r', ' ').trim();
		if (normalized.isEmpty()) return null;
		return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
	}

	private static boolean equals(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}

	private static void logFailure(String message, Throwable error) {
		try {
			Log.w(TAG, message, error);
		} catch (Throwable ignored) {}
	}

	interface FatalCapture {
		void capture(Thread thread, Throwable error) throws Throwable;
	}

	static final class FatalHandler implements Thread.UncaughtExceptionHandler {
		private final FatalCapture capture;
		private final Thread.UncaughtExceptionHandler upstream;
		private final Runnable fallback;
		private final AtomicBoolean capturing = new AtomicBoolean();

		FatalHandler(FatalCapture capture, Thread.UncaughtExceptionHandler upstream, Runnable fallback) {
			this.capture = capture;
			this.upstream = upstream;
			this.fallback = fallback;
		}

		@Override
		public void uncaughtException(Thread thread, Throwable error) {
			if (capturing.compareAndSet(false, true)) {
				try {
					if (capture != null) capture.capture(thread, error);
				} catch (Throwable captureFailure) {
					logFailure("Fatal Java diagnostic capture failed open", captureFailure);
				} finally {
					capturing.set(false);
				}
			}
			if (upstream != null && upstream != this) {
				try {
					upstream.uncaughtException(thread, error);
					return;
				} catch (Throwable upstreamFailure) {
					logFailure("Upstream uncaught handler failed", upstreamFailure);
				}
			}
			if (fallback != null) {
				try {
					fallback.run();
				} catch (Throwable ignored) {}
			}
		}
	}

	static final class FrameData {
		final String className;
		final String methodName;
		final String fileName;
		final int lineNumber;
		final boolean nativeMethod;

		FrameData(String className, String methodName, String fileName,
				int lineNumber, boolean nativeMethod) {
			this.className = className;
			this.methodName = methodName;
			this.fileName = fileName;
			this.lineNumber = lineNumber;
			this.nativeMethod = nativeMethod;
		}

		String display() {
			if (className == null || methodName == null) return null;
			StringBuilder text = new StringBuilder(className).append('.').append(methodName).append('(');
			if (nativeMethod) {
				text.append("Native Method");
			} else if (fileName != null) {
				text.append(fileName);
				if (lineNumber >= 0) text.append(':').append(lineNumber);
			} else {
				text.append("Unknown Source");
			}
			return text.append(')').toString();
		}
	}

	static final class ThrowableData {
		final String className;
		final String message;
		final List<FrameData> frames;

		ThrowableData(String className, String message, List<FrameData> frames) {
			this.className = bound(className, MAX_TEXT_CHARS);
			this.message = bound(message, MAX_MESSAGE_CHARS);
			this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
		}

		String simpleName() {
			if (className == null) return null;
			int dot = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
			return dot >= 0 && dot + 1 < className.length()
					? className.substring(dot + 1) : className;
		}

		FrameData firstFrame() {
			return frames.isEmpty() ? null : frames.get(0);
		}
	}

	static final class Snapshot {
		final File file;
		final Kind kind;
		final long timestampMillis;
		final String processName;
		final String processRole;
		final int pid;
		final String threadName;
		final long threadId;
		final int threadPriority;
		final String sessionId;
		final String midletName;
		final String midletVersion;
		final String midletMainClass;
		final String jarSha256;
		final String appVersion;
		final String androidRelease;
		final int androidSdk;
		final String brand;
		final String model;
		final String primaryAbi;
		final String contextNote;
		final String stackTrace;
		final List<ThrowableData> throwables;
		final int primaryThrowableIndex;
		final String legacyEventId;
		final String legacyBoundary;
		final String legacyRecordId;
		final CrashContextStore.Snapshot appContext;

		Snapshot(File file, Kind kind, long timestampMillis, String processName, String processRole,
				int pid, String threadName, long threadId, int threadPriority, String sessionId,
				String midletName, String midletVersion, String midletMainClass, String jarSha256,
				String appVersion, String androidRelease, int androidSdk, String brand, String model,
				String primaryAbi, String contextNote, String stackTrace,
				List<ThrowableData> throwables, int primaryThrowableIndex,
				String legacyEventId, String legacyBoundary, CrashContextStore.Snapshot appContext) {
			this(file, kind, timestampMillis, processName, processRole, pid, threadName, threadId,
					threadPriority, sessionId, midletName, midletVersion, midletMainClass, jarSha256,
					appVersion, androidRelease, androidSdk, brand, model, primaryAbi, contextNote,
					stackTrace, throwables, primaryThrowableIndex, legacyEventId, legacyBoundary,
					null, appContext);
		}

		Snapshot(File file, Kind kind, long timestampMillis, String processName, String processRole,
				int pid, String threadName, long threadId, int threadPriority, String sessionId,
				String midletName, String midletVersion, String midletMainClass, String jarSha256,
				String appVersion, String androidRelease, int androidSdk, String brand, String model,
				String primaryAbi, String contextNote, String stackTrace,
				List<ThrowableData> throwables, int primaryThrowableIndex,
				String legacyEventId, String legacyBoundary, String legacyRecordId,
				CrashContextStore.Snapshot appContext) {
			this.file = file;
			this.kind = kind;
			this.timestampMillis = timestampMillis;
			this.processName = bound(processName, MAX_TEXT_CHARS);
			this.processRole = bound(processRole, 32);
			this.pid = pid;
			this.threadName = bound(threadName, MAX_TEXT_CHARS);
			this.threadId = threadId;
			this.threadPriority = threadPriority;
			this.sessionId = bound(sessionId, 96);
			this.midletName = bound(midletName, MAX_TEXT_CHARS);
			this.midletVersion = bound(midletVersion, MAX_TEXT_CHARS);
			this.midletMainClass = bound(midletMainClass, MAX_TEXT_CHARS);
			this.jarSha256 = bound(jarSha256, 128);
			this.appVersion = bound(appVersion, 128);
			this.androidRelease = bound(androidRelease, 64);
			this.androidSdk = androidSdk;
			this.brand = bound(brand, 128);
			this.model = bound(model, 128);
			this.primaryAbi = bound(primaryAbi, 128);
			this.contextNote = bound(contextNote, 768);
			this.stackTrace = boundStack(stackTrace);
			this.throwables = Collections.unmodifiableList(new ArrayList<>(throwables));
			this.primaryThrowableIndex = primaryThrowableIndex >= 0
					&& primaryThrowableIndex < this.throwables.size() ? primaryThrowableIndex : -1;
			this.legacyEventId = bound(legacyEventId, 96);
			this.legacyBoundary = bound(legacyBoundary, 64);
			this.legacyRecordId = bound(legacyRecordId, 1024);
			this.appContext = appContext;
		}

		Snapshot withFile(File nextFile) {
			return new Snapshot(
					nextFile, kind, timestampMillis, processName, processRole, pid,
					threadName, threadId, threadPriority, sessionId,
					midletName, midletVersion, midletMainClass, jarSha256,
					appVersion, androidRelease, androidSdk, brand, model, primaryAbi,
					contextNote, stackTrace, throwables, primaryThrowableIndex,
					legacyEventId, legacyBoundary, legacyRecordId, appContext);
		}

		Snapshot withLegacyRecordId(String value) {
			return new Snapshot(
					file, kind, timestampMillis, processName, processRole, pid,
					threadName, threadId, threadPriority, sessionId,
					midletName, midletVersion, midletMainClass, jarSha256,
					appVersion, androidRelease, androidSdk, brand, model, primaryAbi,
					contextNote, stackTrace, throwables, primaryThrowableIndex,
					legacyEventId, legacyBoundary, value, appContext);
		}

		ThrowableData primaryThrowable() {
			return primaryThrowableIndex >= 0 && primaryThrowableIndex < throwables.size()
					? throwables.get(primaryThrowableIndex) : null;
		}

		ThrowableData underlyingCause() {
			int next = primaryThrowableIndex + 1;
			return primaryThrowableIndex >= 0 && next < throwables.size()
					? throwables.get(next) : null;
		}
	}

	static final class ThrowableCapture {
		final List<ThrowableData> throwables;
		final int primaryIndex;
		final String stackTrace;

		ThrowableCapture(List<ThrowableData> throwables, int primaryIndex, String stackTrace) {
			this.throwables = throwables;
			this.primaryIndex = primaryIndex;
			this.stackTrace = stackTrace;
		}
	}

	private static final class MutableThrowable {
		final String className;
		final String message;
		final ArrayList<FrameData> frames = new ArrayList<>();

		MutableThrowable(String className, String message) {
			this.className = className;
			this.message = message;
		}
	}
}
