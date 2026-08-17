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

import static org.acra.ReportField.ANDROID_VERSION;
import static org.acra.ReportField.APP_VERSION_NAME;
import static org.acra.ReportField.BRAND;
import static org.acra.ReportField.CUSTOM_DATA;
import static org.acra.ReportField.PHONE_MODEL;
import static org.acra.ReportField.REPORT_ID;
import static org.acra.ReportField.STACK_TRACE;
import static org.acra.ReportField.STACK_TRACE_HASH;
import static org.acra.ReportField.THREAD_DETAILS;

import android.app.ApplicationExitInfo;
import android.content.Context;
import android.system.OsConstants;
import android.util.Log;

import org.acra.data.CrashReportData;
import org.acra.file.CrashReportPersister;
import org.acra.file.ReportLocator;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads, correlates, and projects bounded local evidence into actionable logical incidents. */
public final class LocalDiagnosticRepository {
	private static final String TAG = LocalDiagnosticRepository.class.getSimpleName();

	private LocalDiagnosticRepository() {}

	/** Self-contained load for background/non-UI callers that also snapshots framework history. */
	public static List<Record> load(Context context) {
		ProcessExitStore.ingest(context);
		return loadStored(context);
	}

	/** Reads only the already-maintained durable projection without historical ingestion. */
	public static List<Record> loadStored(Context context) {
		ArrayList<SessionRecord> sessions = readSessionRecords(context);
		Map<String, SessionRecord> allSessions = new HashMap<>();
		ArrayList<MutableRecord> journalRecords = new ArrayList<>();
		Map<String, MutableRecord> failuresBySession = new HashMap<>();
		for (SessionRecord session : sessions) {
			if (session.snapshot.sessionId != null) {
				allSessions.put(session.snapshot.sessionId, session);
			}
			if (session.snapshot.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
					&& MidletFailureRecovery.isSafeEventId(session.snapshot.failureEventId)) {
				MutableRecord failure = new MutableRecord(session);
				journalRecords.add(failure);
				failuresBySession.put(failure.sessionId, failure);
			}
		}

		ArrayList<Record> standaloneReports = new ArrayList<>();
		for (RawJavaReport raw : readRawJavaReports(context)) {
			MutableRecord journal = failuresBySession.get(raw.sessionId);
			if (journal != null && isExactEventMatch(
					journal.sessionId, journal.eventId, raw.sessionId, raw.stackTrace)) {
				journal.attach(raw);
			} else {
				standaloneReports.add(Record.fromRaw(raw));
			}
		}

		for (ProcessExitStore.Snapshot exit : ProcessExitStore.loadStored(context)) {
			MutableRecord journal = failuresBySession.get(exit.sessionId);
			if (journal != null) {
				journal.attach(exit);
			} else {
				standaloneReports.add(Record.fromProcessExit(exit, allSessions.get(exit.sessionId)));
			}
		}

		ArrayList<Record> records = new ArrayList<>(journalRecords.size() + standaloneReports.size());
		for (MutableRecord journal : journalRecords) {
			records.add(journal.freeze());
		}
		records.addAll(standaloneReports);
		Collections.sort(records, (left, right) -> {
			if (left.timestampMillis == right.timestampMillis) {
				return left.id.compareTo(right.id);
			}
			return left.timestampMillis < right.timestampMillis ? 1 : -1;
		});
		return records;
	}

	public static Record find(Context context, String id) {
		if (id == null) {
			return null;
		}
		for (Record record : load(context)) {
			if (id.equals(record.id)) {
				return record;
			}
		}
		return null;
	}

	public static Record findStored(Context context, String id) {
		if (id == null) {
			return null;
		}
		for (Record record : loadStored(context)) {
			if (id.equals(record.id)) {
				return record;
			}
		}
		return null;
	}

	/**
	 * Deletes exactly the selected logical record. The durable suppression marker is created only
	 * immediately before process-exit evidence is removed, so an earlier dependent-file failure does
	 * not hide evidence that the failed delete left behind.
	 */
	public static boolean delete(Context context, Record record) {
		if (record == null) {
			return false;
		}
		for (File rawFile : record.rawFiles) {
			if (rawFile.isFile() && !rawFile.delete()) {
				Log.w(TAG, "Unable to delete raw crash report: " + rawFile.getName());
				return false;
			}
		}
		if (record.journalFile != null) {
			if (!MidletSessionJournal.delete(record.journalFile)) {
				Log.w(TAG, "Unable to delete MIDlet session journal: " + record.journalFile.getName());
				return false;
			}
			MidletFailureRecovery.deleteAcknowledgment(context, record.eventId);
		}
		if (record.processExit != null) {
			if (!ProcessExitDeletionStore.markDeleted(context, record.processExit.key)) {
				Log.w(TAG, "Unable to persist process-exit deletion marker: " + record.processExit.key);
				return false;
			}
			if (!ProcessExitStore.delete(context, record.processExit)) {
				Log.w(TAG, "Unable to delete process-exit diagnostic: " + record.processExit.key);
				return false;
			}
		}
		return true;
	}

	static boolean isExactEventMatch(String journalSessionId, String eventId, String rawSessionId,
			String stackTrace) {
		return journalSessionId != null
				&& journalSessionId.equals(rawSessionId)
				&& MidletFailureRecovery.isSafeEventId(eventId)
				&& stackTrace != null
				&& stackTrace.contains("eventId=" + eventId + ";");
	}

	static String failureHeadline(String stackTrace) {
		if (stackTrace == null) {
			return null;
		}
		for (String line : stackTrace.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("eventId=")) {
				continue;
			}
			if (!trimmed.startsWith("at ") && !trimmed.startsWith("...")
					&& !trimmed.startsWith("Caused by:")) {
				return trimmed;
			}
		}
		return null;
	}

	static String topAppFrame(String stackTrace) {
		if (stackTrace == null) {
			return null;
		}
		for (String line : stackTrace.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("at ru.playsoftware.j2meloader.")) {
				return trimmed.substring(3);
			}
		}
		return null;
	}

	private static ArrayList<SessionRecord> readSessionRecords(Context context) {
		List<File> files = MidletSessionJournal.journalFiles(context);
		ArrayList<SessionRecord> records = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				records.add(new SessionRecord(file, MidletSessionJournal.read(file)));
			} catch (IOException | RuntimeException e) {
				Log.w(TAG, "Ignoring unreadable MIDlet diagnostic journal: " + file.getName(), e);
			}
		}
		return records;
	}

	private static List<RawJavaReport> readRawJavaReports(Context context) {
		ReportLocator locator = new ReportLocator(context);
		ArrayList<File> files = new ArrayList<>();
		Collections.addAll(files, locator.getApprovedReports());
		Collections.addAll(files, locator.getUnapprovedReports());
		ArrayList<RawJavaReport> reports = new ArrayList<>(files.size());
		CrashReportPersister persister = new CrashReportPersister();
		Set<String> seenPaths = new HashSet<>();
		for (File file : files) {
			if (file == null || !file.isFile() || !seenPaths.add(file.getAbsolutePath())) {
				continue;
			}
			try {
				CrashReportData data = persister.load(file);
				reports.add(RawJavaReport.from(context, file, data));
			} catch (Exception e) {
				Log.w(TAG, "Ignoring unreadable local Java crash report: " + file.getName(), e);
			}
		}
		return reports;
	}

	private static String stringValue(Object value) {
		if (value == null || JSONObject.NULL.equals(value)) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static String custom(JSONObject custom, String key) {
		return custom == null ? null : stringValue(custom.opt(key));
	}

	private static void appendLine(StringBuilder text, String label, String value) {
		if (value != null && !value.isEmpty()) {
			text.append(label).append(": ").append(value).append('\n');
		}
	}

	private static void appendSessionSummary(StringBuilder detail, MidletSessionJournal.Snapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		appendLine(detail, "Lifecycle stage", snapshot.stage == null ? null : snapshot.stage.name());
		appendLine(detail, "MIDlet", snapshot.midletName);
		appendLine(detail, "MIDlet version", snapshot.midletVersion);
		appendLine(detail, "Entrypoint", snapshot.mainClass);
		appendLine(detail, "JAR fingerprint", shortFingerprint(snapshot.jarSha256));
	}

	private static void appendJavaFailureSummary(StringBuilder detail, RawJavaReport raw) {
		appendLine(detail, "Failure", failureHeadline(raw.stackTrace));
		appendLine(detail, "Top app frame", topAppFrame(raw.stackTrace));
		appendLine(detail, "Thread", raw.threadDetails);
		appendLine(detail, "Fingerprint", shortFingerprint(raw.stackTraceHash));
	}

	private static void appendProcessExitSummary(StringBuilder detail, ProcessExitStore.Snapshot exit) {
		if (exit == null) {
			return;
		}
		String status = ProcessExitStore.statusLabel(exit);
		String mechanism = ProcessExitStore.reasonLabel(exit.reason);
		if ((exit.reason == ApplicationExitInfo.REASON_SIGNALED
				|| exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) && status != null) {
			mechanism = mechanism + " · " + status;
		}
		appendLine(detail, "Failure", mechanism);
		if (exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status == OsConstants.SIGKILL
				&& exit.lowMemoryKillReportSupported) {
			appendLine(detail, "Cause", "unknown");
		}
		appendAppContext(detail, exit.appContext, exit.timestampMillis);
		appendBuild(detail, exit.appContext, null);
		appendEnvironment(detail, exit.stateSdk, joinDevice(exit.deviceBrand, exit.deviceModel), null);
		if (shouldShowMemory(exit)) {
			appendLine(detail, "Memory sample", memorySample(exit.pssKb, exit.rssKb));
		}
		appendLine(detail, "System description", exit.description);
		if (exit.traceBytes > 0) {
			String evidence = "native-tombstone-protobuf".equals(exit.traceKind)
					? "native tombstone retained" : "system trace retained";
			if (exit.traceTruncated) {
				evidence += " (truncated at retention limit)";
			}
			appendLine(detail, "Evidence", evidence);
		}
		String trace = ProcessExitStore.readDisplayTrace(exit);
		if (trace != null && !trace.trim().isEmpty()) {
			detail.append("\nANR trace:\n").append(trace.trim()).append('\n');
		}
	}

	private static boolean shouldShowMemory(ProcessExitStore.Snapshot exit) {
		return exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY
				|| exit.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
				|| (exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status == OsConstants.SIGKILL);
	}

	private static String memorySample(long pssKb, long rssKb) {
		StringBuilder value = new StringBuilder();
		if (pssKb > 0) {
			value.append("PSS ").append(pssKb).append(" kB");
		}
		if (rssKb > 0) {
			if (value.length() > 0) value.append(" · ");
			value.append("RSS ").append(rssKb).append(" kB");
		}
		return value.length() == 0 ? null : value.toString();
	}

	private static void appendBuild(StringBuilder detail, CrashContextStore.Snapshot context,
			String fallbackVersion) {
		if (context != null) {
			String commit = shortFingerprint(context.buildCommit);
			if (commit != null || context.buildVariant != null) {
				StringBuilder value = new StringBuilder();
				if (commit != null) value.append(commit);
				if (context.buildVariant != null) {
					if (value.length() > 0) value.append(" · ");
					value.append(context.buildVariant);
				}
				appendLine(detail, "Build", value.toString());
				return;
			}
		}
		appendLine(detail, "App version", fallbackVersion);
	}

	private static void appendEnvironment(StringBuilder detail, int sdk, String device,
			String androidVersion) {
		StringBuilder value = new StringBuilder();
		if (sdk >= 0) {
			value.append("Android SDK ").append(sdk);
		} else if (androidVersion != null) {
			value.append("Android ").append(androidVersion);
		}
		if (device != null) {
			if (value.length() > 0) value.append(" · ");
			value.append(device);
		}
		appendLine(detail, "Environment", value.length() == 0 ? null : value.toString());
	}

	private static void appendAppContext(StringBuilder detail, CrashContextStore.Snapshot context,
			long failureTimeMillis) {
		if (context == null || context.location == null) {
			return;
		}
		detail.append("\nLast app context\n");
		appendLine(detail, "Location", context.location);
		appendLine(detail, "Previous", context.previousLocation);
		appendLine(detail, "Action", context.action);
		appendLine(detail, "Phase", context.phase);
		appendLine(detail, "Context age", relativeAge(failureTimeMillis, context.updatedWallTimeMillis));
		if (!context.breadcrumbs.isEmpty()) {
			detail.append("Recent transitions:\n");
			for (CrashContextStore.Breadcrumb breadcrumb : context.breadcrumbs) {
				detail.append("- ");
				String age = relativeAge(failureTimeMillis, breadcrumb.wallTimeMillis);
				if (age != null) {
					detail.append(age).append(" · ");
				}
				detail.append(breadcrumb.location);
				if (breadcrumb.action != null) detail.append(" · ").append(breadcrumb.action);
				if (breadcrumb.phase != null) detail.append(" · ").append(breadcrumb.phase);
				detail.append('\n');
			}
		}
	}

	private static String relativeAge(long failureTimeMillis, long contextTimeMillis) {
		if (failureTimeMillis <= 0 || contextTimeMillis <= 0 || failureTimeMillis < contextTimeMillis) {
			return null;
		}
		long delta = failureTimeMillis - contextTimeMillis;
		if (delta < 1000) {
			return delta + " ms before failure";
		}
		if (delta < 60_000) {
			return String.format(Locale.US, "%.1f s before failure", delta / 1000.0);
		}
		return (delta / 60_000) + " min before failure";
	}

	private static String shortFingerprint(String value) {
		if (value == null || value.trim().isEmpty() || "unknown".equalsIgnoreCase(value.trim())) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
	}

	private static boolean same(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}

	public enum Kind {
		MIDLET_FAILURE,
		JAVA_REPORT,
		PROCESS_EXIT
	}

	public static final class Record {
		private final String id;
		private final Kind kind;
		private final long timestampMillis;
		private final String eventId;
		private final String sessionId;
		private final String midletName;
		private final String processRole;
		private final String stackTrace;
		private final String detailText;
		private final File journalFile;
		private final List<File> rawFiles;
		private final ProcessExitStore.Snapshot processExit;

		private Record(String id, Kind kind, long timestampMillis, String eventId, String sessionId,
				String midletName, String processRole, String stackTrace, String detailText,
				File journalFile, List<File> rawFiles, ProcessExitStore.Snapshot processExit) {
			this.id = id;
			this.kind = kind;
			this.timestampMillis = timestampMillis;
			this.eventId = eventId;
			this.sessionId = sessionId;
			this.midletName = midletName;
			this.processRole = processRole;
			this.stackTrace = stackTrace;
			this.detailText = detailText;
			this.journalFile = journalFile;
			this.rawFiles = Collections.unmodifiableList(new ArrayList<>(rawFiles));
			this.processExit = processExit;
		}

		private static Record fromRaw(RawJavaReport raw) {
			StringBuilder detail = new StringBuilder("Type: Java diagnostic report\n");
			appendJavaFailureSummary(detail, raw);
			appendAppContext(detail, raw.appContext, raw.timestampMillis);
			appendBuild(detail, raw.appContext, raw.appVersion);
			appendEnvironment(detail, -1, joinDevice(raw.brand, raw.phoneModel), raw.androidVersion);
			if (raw.midletName != null) {
				detail.append('\n');
				appendLine(detail, "MIDlet", raw.midletName);
				appendLine(detail, "MIDlet version", raw.midletVersion);
				appendLine(detail, "Entrypoint", raw.mainClass);
				appendLine(detail, "JAR fingerprint", shortFingerprint(raw.jarSha256));
			}
			if (raw.stackTrace != null) {
				detail.append("\nStack trace:\n").append(raw.stackTrace.trim()).append('\n');
			}
			String reportKey = raw.reportId != null ? raw.reportId : raw.file.getName();
			return new Record(
					"acra:" + reportKey,
					Kind.JAVA_REPORT,
					raw.timestampMillis,
					null,
					raw.sessionId,
					raw.midletName,
					raw.processRole,
					raw.stackTrace,
					detail.toString().trim(),
					null,
					Collections.singletonList(raw.file),
					null
			);
		}

		private static Record fromProcessExit(ProcessExitStore.Snapshot exit, SessionRecord session) {
			StringBuilder detail = new StringBuilder("Type: Process exit diagnostic\n");
			appendProcessExitSummary(detail, exit);
			if (session != null) {
				detail.append('\n');
				appendSessionSummary(detail, session.snapshot);
			}
			return new Record(
					exit.id,
					Kind.PROCESS_EXIT,
					exit.timestampMillis,
					null,
					exit.sessionId,
					session == null ? null : session.snapshot.midletName,
					exit.processRole,
					null,
					detail.toString().trim(),
					session == null ? null : session.file,
					Collections.emptyList(),
					exit
			);
		}

		public String getId() { return id; }
		public Kind getKind() { return kind; }
		public long getTimestampMillis() { return timestampMillis; }
		public String getEventId() { return eventId; }
		public String getSessionId() { return sessionId; }
		public String getMidletName() { return midletName; }
		public String getProcessRole() { return processRole; }
		public String getStackTrace() { return stackTrace; }
		public String getDetailText() { return detailText; }
		public boolean hasJavaReport() { return !rawFiles.isEmpty(); }
		public boolean hasProcessExit() { return processExit != null; }
	}

	private static final class SessionRecord {
		private final File file;
		private final MidletSessionJournal.Snapshot snapshot;

		private SessionRecord(File file, MidletSessionJournal.Snapshot snapshot) {
			this.file = file;
			this.snapshot = snapshot;
		}
	}

	private static final class MutableRecord {
		private final File journalFile;
		private final MidletSessionJournal.Snapshot snapshot;
		private final String eventId;
		private final String sessionId;
		private final String midletName;
		private final ArrayList<RawJavaReport> rawReports = new ArrayList<>();
		private ProcessExitStore.Snapshot processExit;

		private MutableRecord(SessionRecord session) {
			this.journalFile = session.file;
			this.snapshot = session.snapshot;
			this.eventId = snapshot.failureEventId;
			this.sessionId = snapshot.sessionId;
			this.midletName = snapshot.midletName;
		}

		private void attach(RawJavaReport raw) {
			rawReports.add(raw);
		}

		private void attach(ProcessExitStore.Snapshot exit) {
			if (processExit == null || exit.timestampMillis > processExit.timestampMillis) {
				processExit = exit;
			}
		}

		private Record freeze() {
			StringBuilder detail = new StringBuilder("Type: MIDlet session failure\n");
			appendLine(detail, "Failure boundary", snapshot.failureBoundary == null
					? null : snapshot.failureBoundary.name());
			appendSessionSummary(detail, snapshot);

			String stackTrace = null;
			String processRole = "midlet";
			ArrayList<File> rawFiles = new ArrayList<>();
			RawJavaReport primaryRaw = null;
			for (RawJavaReport raw : rawReports) {
				rawFiles.add(raw.file);
				if (primaryRaw == null && raw.stackTrace != null) {
					primaryRaw = raw;
					stackTrace = raw.stackTrace;
				}
				if (raw.processRole != null) {
					processRole = raw.processRole;
				}
			}
			if (primaryRaw != null) {
				appendJavaFailureSummary(detail, primaryRaw);
				appendAppContext(detail, primaryRaw.appContext, primaryRaw.timestampMillis);
				appendBuild(detail, primaryRaw.appContext, primaryRaw.appVersion);
				appendEnvironment(detail, -1,
						joinDevice(primaryRaw.brand, primaryRaw.phoneModel), primaryRaw.androidVersion);
			}
			if (processExit != null) {
				detail.append('\n');
				appendProcessExitSummary(detail, processExit);
				if (processExit.processRole != null) {
					processRole = processExit.processRole;
				}
			}
			if (stackTrace != null) {
				detail.append("\nStack trace:\n").append(stackTrace.trim()).append('\n');
			}
			return new Record(
					"event:" + eventId,
					Kind.MIDLET_FAILURE,
					Math.max(snapshot.updatedWallTimeMillis,
							processExit == null ? 0 : processExit.timestampMillis),
					eventId,
					sessionId,
					midletName,
					processRole,
					stackTrace,
					detail.toString().trim(),
					journalFile,
					rawFiles,
					processExit
			);
		}
	}

	private static final class RawJavaReport {
		private final File file;
		private final long timestampMillis;
		private final String reportId;
		private final String sessionId;
		private final String processRole;
		private final String midletName;
		private final String midletVersion;
		private final String mainClass;
		private final String jarSha256;
		private final String appVersion;
		private final String androidVersion;
		private final String brand;
		private final String phoneModel;
		private final String threadDetails;
		private final String stackTrace;
		private final String stackTraceHash;
		private final CrashContextStore.Snapshot appContext;

		private RawJavaReport(File file, long timestampMillis, String reportId, String sessionId,
				String processRole, String midletName, String midletVersion, String mainClass,
				String jarSha256, String appVersion, String androidVersion, String brand,
				String phoneModel, String threadDetails, String stackTrace, String stackTraceHash,
				CrashContextStore.Snapshot appContext) {
			this.file = file;
			this.timestampMillis = timestampMillis;
			this.reportId = reportId;
			this.sessionId = sessionId;
			this.processRole = processRole;
			this.midletName = midletName;
			this.midletVersion = midletVersion;
			this.mainClass = mainClass;
			this.jarSha256 = jarSha256;
			this.appVersion = appVersion;
			this.androidVersion = androidVersion;
			this.brand = brand;
			this.phoneModel = phoneModel;
			this.threadDetails = threadDetails;
			this.stackTrace = stackTrace;
			this.stackTraceHash = stackTraceHash;
			this.appContext = appContext;
		}

		private static RawJavaReport from(Context context, File file, CrashReportData data) {
			Object customValue = data.get(CUSTOM_DATA.toString());
			JSONObject custom = customValue instanceof JSONObject ? (JSONObject) customValue : null;
			String runId = custom(custom, CrashReporter.KEY_RUN_ID);
			String capturedLocation = CrashContextStore.normalizeToken(
					custom(custom, CrashReporter.KEY_CONTEXT_LOCATION), CrashContextStore.MAX_LOCATION_LENGTH);
			String capturedPrevious = CrashContextStore.normalizeToken(
					custom(custom, CrashReporter.KEY_CONTEXT_PREVIOUS), CrashContextStore.MAX_LOCATION_LENGTH);
			String capturedAction = CrashContextStore.normalizeToken(
					custom(custom, CrashReporter.KEY_CONTEXT_ACTION), CrashContextStore.MAX_ACTION_LENGTH);
			String capturedPhase = CrashContextStore.normalizeToken(
					custom(custom, CrashReporter.KEY_CONTEXT_PHASE), CrashContextStore.MAX_PHASE_LENGTH);
			long capturedUpdated = parseLong(custom(custom, CrashReporter.KEY_CONTEXT_UPDATED));

			CrashContextStore.Snapshot appContext = CrashContextStore.readForRun(context, runId);
			boolean exactStoredSnapshot = appContext != null
					&& appContext.updatedWallTimeMillis == capturedUpdated
					&& same(appContext.location, capturedLocation)
					&& same(appContext.previousLocation, capturedPrevious)
					&& same(appContext.action, capturedAction)
					&& same(appContext.phase, capturedPhase);
			if (!exactStoredSnapshot && CrashContextStore.isSafeRunId(runId)) {
				appContext = new CrashContextStore.Snapshot(
						runId,
						custom(custom, CrashReporter.KEY_PROCESS_ROLE),
						custom(custom, CrashReporter.KEY_BUILD_COMMIT),
						custom(custom, CrashReporter.KEY_BUILD_VARIANT),
						capturedLocation,
						capturedPrevious,
						capturedAction,
						capturedPhase,
						capturedUpdated,
						Collections.emptyList()
				);
			}
			return new RawJavaReport(
					file,
					file.lastModified(),
					stringValue(data.get(REPORT_ID.toString())),
					custom(custom, CrashReporter.KEY_SESSION_ID),
					custom(custom, CrashReporter.KEY_PROCESS_ROLE),
					custom(custom, CrashReporter.KEY_MIDLET_NAME),
					custom(custom, CrashReporter.KEY_MIDLET_VERSION),
					custom(custom, CrashReporter.KEY_MIDLET_MAIN_CLASS),
					custom(custom, CrashReporter.KEY_MIDLET_JAR_SHA256),
					stringValue(data.get(APP_VERSION_NAME.toString())),
					stringValue(data.get(ANDROID_VERSION.toString())),
					stringValue(data.get(BRAND.toString())),
					stringValue(data.get(PHONE_MODEL.toString())),
					stringValue(data.get(THREAD_DETAILS.toString())),
					stringValue(data.get(STACK_TRACE.toString())),
					stringValue(data.get(STACK_TRACE_HASH.toString())),
					appContext
			);
		}
	}

	private static long parseLong(String value) {
		if (value == null) {
			return 0;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static String joinDevice(String brand, String model) {
		if (brand == null) return model;
		if (model == null) return brand;
		return brand + " " + model;
	}
}
